package dms.db

import com.dimafeng.testcontainers.PostgreSQLContainer
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

import java.net.URLClassLoader
import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager}
import java.util.UUID
import java.util.jar.{JarEntry, JarOutputStream}

/** The convention for migrations without a transaction (DMS-138, ADR 0066, `modules/db/README.md`), against
  * the configuration of [[Migrations]] and a real PostgreSQL. The migrations under `db/nontransactional`
  * exist for these tests only; each test runs on its own copy of the fully migrated database.
  */
object MigrationsSpec extends ZIOSpecDefault:

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |end $$""".stripMargin

  private val migrated: ZLayer[Any, Throwable, PostgreSQLContainer] = ZLayer.scoped(
    for
      c <- ZIO.acquireRelease(ZIO.attemptBlocking {
        val c = PostgreSQLContainer(DockerImageName.parse("postgres:17"))
        c.start()
        c
      })(c => ZIO.attemptBlocking(c.stop()).orDie)
      _ <- ZIO.attemptBlocking(withConnection(c.jdbcUrl, c)(_.createStatement().execute(appUserSql)))
      _ <- Migrations.migrate(c.jdbcUrl, c.username, c.password)
    yield c
  )

  private def withConnection[A](url: String, c: PostgreSQLContainer)(f: Connection => A): A =
    val connection = DriverManager.getConnection(url, c.username, c.password)
    try f(connection)
    finally connection.close()

  /** A copy of the migrated database, by its URL. */
  private def copy(name: String): RIO[PostgreSQLContainer, String] =
    ZIO.serviceWithZIO[PostgreSQLContainer](c =>
      ZIO.attemptBlocking {
        val _ = withConnection(c.jdbcUrl.replace(s"/${c.databaseName}", "/postgres"), c)(
          _.createStatement().execute(s"create database $name template ${c.databaseName}")
        )
        c.jdbcUrl.replace(s"/${c.databaseName}", s"/$name")
      }
    )

  private def flyway(url: String, scenario: String): URIO[PostgreSQLContainer, Flyway] =
    ZIO.serviceWith[PostgreSQLContainer](c =>
      Migrations.flyway(
        url,
        c.username,
        c.password,
        Migrations.Location,
        s"classpath:db/nontransactional/$scenario"
      )
    )

  private def query[A](url: String, statement: String)(read: java.sql.ResultSet => A) =
    ZIO.serviceWithZIO[PostgreSQLContainer](c =>
      ZIO.attemptBlocking(
        withConnection(url, c) { connection =>
          val result = connection.createStatement().executeQuery(statement)
          result.next()
          read(result)
        }
      )
    )

  private def insertTenant(connection: Connection, name: String = UUID.randomUUID().toString): Unit =
    val statement = connection.prepareStatement("insert into dms.tenant (id, name) values (?, ?)")
    statement.setObject(1, UUID.randomUUID())
    statement.setString(2, name)
    val _ = statement.executeUpdate()

  /** Whether another session can write to the table while the migration of `scenario` builds its index: the
    * build waits for a writer in progress, the other session tries to write meanwhile. The migration ends
    * once that writer has committed.
    */
  private def writesDuringBuild(url: String, scenario: String) =
    ZIO.serviceWithZIO[PostgreSQLContainer] { c =>
      ZIO.acquireReleaseWith(ZIO.attemptBlocking(DriverManager.getConnection(url, c.username, c.password)))(
        connection => ZIO.succeed(connection.close())
      ) { inProgress =>
        for
          _         <- ZIO.attemptBlocking { inProgress.setAutoCommit(false); insertTenant(inProgress) }
          migrating <- flyway(url, scenario).flatMap(f => ZIO.attemptBlocking(f.migrate())).fork
          _         <- query(
            url,
            "select count(*) from pg_stat_activity where query ilike '%index%tenant_name_test%' " +
              "and wait_event_type = 'Lock'"
          )(_.getLong(1)).repeat(Schedule.spaced(50.millis) && Schedule.recurUntil[Long](_ > 0))
          written <- ZIO
            .attemptBlocking(withConnection(url, c) { other =>
              other.createStatement().execute("set lock_timeout = '1s'")
              insertTenant(other)
            })
            .isSuccess
          _      <- ZIO.attemptBlocking(inProgress.commit())
          result <- migrating.join
        yield (written, result.migrationsExecuted)
      }
    }

  private val valid =
    "select coalesce(bool_and(i.indisvalid), false) from pg_index i " +
      "join pg_class c on c.oid = i.indexrelid where c.relname = 'tenant_name_test'"

  private val failedMigrations = "select count(*) from dms.flyway_schema_history where not success"

  def spec = suite("Migrations")(
    // With Flyway's default lock the first of these never ends: see Migrations.flyway.
    test("an index built concurrently lets others write; the build in a transaction does not") {
      for
        concurrent <- copy("concurrent")
        plain      <- copy("plain")
        built      <- writesDuringBuild(concurrent, "concurrent")
        blocked    <- writesDuringBuild(plain, "plain")
        usable     <- query(concurrent, valid)(_.getBoolean(1))
      yield assertTrue(built == (true, 1), blocked == (false, 1), usable)
    },
    test("two runs at the same time still apply a migration once: the lock on the session holds") {
      for
        url  <- copy("parallel")
        runs <- ZIO.collectAllPar(
          List.fill(2)(flyway(url, "concurrent").flatMap(f => ZIO.attemptBlocking(f.migrate())))
        )
      yield assertTrue(runs.map(_.migrationsExecuted).sum == 1)
    },
    test("Flyway refuses a migration that mixes both kinds of statement, and nothing of it is applied") {
      for
        url     <- copy("mixed")
        refused <- flyway(url, "mixed").flatMap(f => ZIO.attemptBlocking(f.migrate())).flip
        table   <- query(url, "select to_regclass('dms.mixed_test') is not null")(_.getBoolean(1))
      yield assertTrue(
        refused.isInstanceOf[FlywayException],
        refused.getMessage.contains("mixed"),
        !table
      )
    },
    test("after a failed build: the row of the failure is removed and the same migration runs again") {
      for
        url <- copy("failed")
        c   <- ZIO.service[PostgreSQLContainer]
        _   <- ZIO.attemptBlocking(
          withConnection(url, c) { connection =>
            insertTenant(connection, "twice")
            insertTenant(connection, "twice")
          }
        )
        failed       <- flyway(url, "unique").flatMap(f => ZIO.attemptBlocking(f.migrate())).flip
        invalid      <- query(url, valid)(_.getBoolean(1))
        recorded     <- query(url, failedMigrations)(_.getLong(1))
        stillBlocked <- flyway(url, "unique").flatMap(f => ZIO.attemptBlocking(f.migrate())).flip
        _            <- ZIO.attemptBlocking(
          withConnection(url, c) { connection =>
            val statement = connection.createStatement()
            statement.execute("delete from dms.tenant where name = 'twice'")
            statement.execute("delete from dms.flyway_schema_history where not success")
          }
        )
        applied <- flyway(url, "unique").flatMap(f => ZIO.attemptBlocking(f.migrate()))
        usable  <- query(url, valid)(_.getBoolean(1))
      yield assertTrue(
        failed.isInstanceOf[FlywayException],
        !invalid,
        recorded == 1L,
        stillBlocked.getMessage.contains("failed"),
        applied.migrationsExecuted == 1,
        usable
      )
    },
    test("a statement Flyway does not recognize needs executeInTransaction=false beside the migration") {
      for
        url      <- copy("explicit")
        migrated <- flyway(url, "explicit").flatMap(f => ZIO.attemptBlocking(f.migrate()))
      yield assertTrue(migrated.migrationsExecuted == 1)
    },
    test("that setting also works from a jar, where bin/migrate reads the migrations from") {
      val migration = "V9001__reindex_tenant.sql"
      for
        marked   <- copy("packaged").flatMap(fromJar(_, migration, s"$migration.conf"))
        unmarked <- copy("unmarked").flatMap(fromJar(_, migration)).flip
      yield assertTrue(
        marked.migrationsExecuted == 1,
        unmarked.getMessage.contains("cannot run inside a transaction block")
      )
    }
  ).provideShared(migrated) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(
    2.minutes
  )

  private def fromJar(url: String, files: String*) =
    ZIO.serviceWithZIO[PostgreSQLContainer](c =>
      ZIO.attemptBlocking {
        val thread   = Thread.currentThread()
        val original = thread.getContextClassLoader
        val loader   = URLClassLoader(Array(packaged(files*).toUri.toURL), original)
        thread.setContextClassLoader(loader)
        try
          Migrations
            .flyway(url, c.username, c.password, Migrations.Location, "classpath:packaged")
            .migrate()
        finally
          thread.setContextClassLoader(original)
          loader.close()
      }
    )

  /** A jar with the given files of the scenario `explicit` under `packaged/`. */
  private def packaged(files: String*): Path =
    val jar    = Files.createTempFile("migrations", ".jar")
    val output = JarOutputStream(Files.newOutputStream(jar))
    try
      files.foreach { file =>
        output.putNextEntry(JarEntry(s"packaged/$file"))
        output.write(getClass.getResourceAsStream(s"/db/nontransactional/explicit/$file").readAllBytes())
        output.closeEntry()
      }
    finally output.close()
    jar.toFile.deleteOnExit()
    jar
