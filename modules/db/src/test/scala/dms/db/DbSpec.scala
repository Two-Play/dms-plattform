package dms.db

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.kernel.TenantId
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

import java.util.UUID
import javax.sql.DataSource

/** Integration tests against a real PostgreSQL started by Testcontainers. */
object DbSpec extends ZIOSpecDefault:

  /** Application connections (role dms_app, subject to RLS) and owner connections (bypass RLS). */
  final case class Databases(app: Db, owner: Db)

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  private val container: ZLayer[Any, Throwable, PostgreSQLContainer] = ZLayer.scoped(
    ZIO.acquireRelease(ZIO.attemptBlocking {
      val c = PostgreSQLContainer(DockerImageName.parse("postgres:17"))
      c.start()
      c
    })(c => ZIO.attemptBlocking(c.stop()).orDie)
  )

  private val databases: ZLayer[PostgreSQLContainer, Throwable, Databases] = ZLayer.scoped(
    for
      c <- ZIO.service[PostgreSQLContainer]
      _ <- ZIO.attemptBlocking {
        val connection = java.sql.DriverManager.getConnection(c.jdbcUrl, c.username, c.password)
        try connection.createStatement().execute(appUserSql)
        finally connection.close()
      }
      _     <- Migrations.migrate(c.jdbcUrl, c.username, c.password)
      app   <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", poolSize = 1))
      owner <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, poolSize = 2))
    yield Databases(Db(app), Db(owner))
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  private def newTenant(owner: Db): IO[DbError, TenantId] =
    val id = TenantId.random()
    owner
      .transactSystem(
        Db.sql(
          sql"insert into dms.tenant (id, name) values (${id.value}, ${"Tenant " + id.value})".update.run()
        )
      )
      .as(id)

  private def insertOutbox(tenant: TenantId): IO[DbError, Int] =
    Db.sql(
      sql"""insert into dms.outbox_event (tenant_id, aggregate_type, aggregate_id, event_type, payload)
            values (${tenant.value}, ${"document"}, ${UUID
          .randomUUID()}, ${"created"}, ${Jsonb.empty})""".update
        .run()
    )

  private val countOutbox: IO[DbError, Long] =
    Db.sql(sql"select count(*) from dms.outbox_event".query[Long].run().head)

  private def insertAudit(tenant: TenantId): IO[DbError, Int] =
    Db.sql(
      sql"""insert into dms.audit_event (tenant_id, occurred_at, actor_id, actor_kind, action, object_type, channel, hash)
            values (${tenant.value}, now(), ${"user-1"}, ${"person"}, ${"test"}, ${"tenant"}, ${"automation"}, ${Array[
          Byte
        ](0)})""".update
        .run()
    )

  def spec = suite("Db")(
    test("a tenant only sees its own rows") {
      for
        dbs     <- ZIO.service[Databases]
        a       <- newTenant(dbs.owner)
        b       <- newTenant(dbs.owner)
        _       <- dbs.app.transact(a)(insertOutbox(a))
        _       <- dbs.app.transact(b)(insertOutbox(b) *> insertOutbox(b))
        seenByA <- dbs.app.transact(a)(countOutbox)
        seenByB <- dbs.app.transact(b)(countOutbox)
      yield assertTrue(seenByA == 1L, seenByB == 2L)
    },
    test("writing a row for another tenant is rejected") {
      for
        dbs    <- ZIO.service[Databases]
        a      <- newTenant(dbs.owner)
        b      <- newTenant(dbs.owner)
        result <- dbs.app.transact(a)(insertOutbox(b)).either
      yield assertTrue(result match
        case Left(DbError.PermissionDenied(_)) => true
        case _                                 => false)
    },
    test("access without tenant context fails closed") {
      for
        dbs    <- ZIO.service[Databases]
        result <- dbs.app.transactSystem(countOutbox).either
      yield assertTrue(result == Left(DbError.TenantContextMissing))
    },
    test("the tenant setting does not leak into the next transaction on the same connection") {
      for
        dbs     <- ZIO.service[Databases]
        a       <- newTenant(dbs.owner)
        pidA    <- dbs.app.transact(a)(Db.sql(sql"select pg_backend_pid()".query[Int].run().head))
        pidNext <- dbs.app.transactSystem(Db.sql(sql"select pg_backend_pid()".query[Int].run().head))
        setting <- dbs.app.transactSystem(
          Db.sql(sql"select coalesce(current_setting('app.tenant_id', true), '')".query[String].run().head)
        )
      yield assertTrue(pidA == pidNext, setting == "")
    },
    test("a failure rolls back all writes of the transaction") {
      for
        dbs    <- ZIO.service[Databases]
        a      <- newTenant(dbs.owner)
        result <- dbs.app.transact(a)(insertOutbox(a) *> ZIO.fail("business rule violated")).either
        count  <- dbs.app.transact(a)(countOutbox)
      yield assertTrue(result == Left("business rule violated"), count == 0L)
    },
    test("the audit trail is append-only") {
      for
        dbs       <- ZIO.service[Databases]
        a         <- newTenant(dbs.owner)
        _         <- dbs.app.transact(a)(insertAudit(a))
        appUpdate <- dbs.app
          .transact(a)(Db.sql(sql"update dms.audit_event set action = 'x'".update.run()))
          .either
        appDelete   <- dbs.app.transact(a)(Db.sql(sql"delete from dms.audit_event".update.run())).either
        ownerUpdate <- dbs.owner
          .transactSystem(Db.sql(sql"update dms.audit_event set action = 'x'".update.run()))
          .either
      yield assertTrue(
        appUpdate.left.exists(_.isInstanceOf[DbError.PermissionDenied]),
        appDelete.left.exists(_.isInstanceOf[DbError.PermissionDenied]),
        ownerUpdate.left.exists(_.isInstanceOf[DbError.AppendOnlyViolation])
      )
    },
    test("Magnum queries and raw JDBC use the same transaction connection") {
      for
        dbs  <- ZIO.service[Databases]
        a    <- newTenant(dbs.owner)
        pids <- dbs.app.transact(a)(
          for
            viaMagnum <- Db.sql(sql"select pg_backend_pid()".query[Int].run().head)
            viaJdbc   <- Db.connection.flatMap(c =>
              ZIO.attemptBlocking {
                val rs = c.createStatement().executeQuery("select pg_backend_pid()")
                rs.next()
                rs.getInt(1)
              }.orDie
            )
          yield (viaMagnum, viaJdbc)
        )
      yield assertTrue(pids._1 == pids._2)
    },
    test("database code outside a transaction fails with NoTransaction") {
      for result <- Db.sql(sql"select 1".query[Int].run()).either
      yield assertTrue(result == Left(DbError.NoTransaction))
    }
  ).provideShared(container >>> databases) @@ TestAspect.sequential @@ TestAspect.withLiveClock
