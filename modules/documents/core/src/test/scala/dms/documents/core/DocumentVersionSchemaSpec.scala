package dms.documents.core

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.db.{Db, DbConfig, DbError, Jsonb, Migrations}
import dms.documents.{DocumentStatus, Lifecycle, LifecycleEvent}
import dms.kernel.TenantId
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

import java.util.UUID
import javax.sql.DataSource

/** The version model in PostgreSQL (migration V9), tested with plain SQL as the application role: the guards
  * hold for every writer, not only for the service.
  */
object DocumentVersionSchemaSpec extends ZIOSpecDefault:

  final case class Env(app: Db, owner: Db)

  /** A tenant with a document class and a released template version. */
  final case class Fixture(tenant: TenantId, classId: UUID, templateId: UUID)

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  private val env: ZLayer[Any, Throwable, Env] = ZLayer.scoped(
    for
      c <- ZIO.acquireRelease(ZIO.attemptBlocking {
        val c = PostgreSQLContainer(DockerImageName.parse("postgres:17"))
        c.start()
        c
      })(c => ZIO.attemptBlocking(c.stop()).orDie)
      _ <- ZIO.attemptBlocking {
        val connection = java.sql.DriverManager.getConnection(c.jdbcUrl, c.username, c.password)
        try connection.createStatement().execute(appUserSql)
        finally connection.close()
      }
      _     <- Migrations.migrate(c.jdbcUrl, c.username, c.password)
      app   <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", poolSize = 2))
      owner <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, poolSize = 2))
    yield Env(Db(app), Db(owner))
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  private val content = Jsonb("""{"format":1,"sections":[],"computed":[]}""")
  private val edited  = Jsonb(
    """{"format":1,"sections":[{"id":"x","locked":false,"blocks":[]}],"computed":[]}"""
  )
  private val snapshot = Jsonb("""{"values":{},"takenAt":"2026-10-28T12:00:00Z"}""")
  private val checksum = "a" * 64

  private def fixture: ZIO[Env, DbError, Fixture] =
    val tenant     = TenantId.random()
    val classId    = UUID.randomUUID()
    val templateId = UUID.randomUUID()
    ZIO.serviceWithZIO[Env](
      _.owner
        .transactSystem(Db.sql {
          val _ = sql"insert into dms.tenant (id, name) values (${tenant.value}, ${"Tenant"})".update.run()
          val _ = sql"""insert into dms.document_class (id, tenant_id, key, created_by)
                        values ($classId, ${tenant.value}, ${"contract"}, ${"admin"})""".update.run()
          val _ =
            sql"""insert into dms.document_class_version (tenant_id, class_id, version, label, created_by)
                        values (${tenant.value}, $classId, 1, ${"Contract"}, ${"admin"})""".update.run()
          val _ = sql"""insert into dms.template (id, tenant_id, created_by)
                        values ($templateId, ${tenant.value}, ${"designer"})""".update.run()
          val _ = sql"""insert into dms.template_version
                          (tenant_id, template_id, version, name, document_class_id, document_class_version, status,
                           content, created_by, status_changed_by)
                        values (${tenant.value}, $templateId, 1, ${"Contract"}, $classId, 1, ${"Draft"}, $content,
                                ${"designer"}, ${"designer"})""".update.run()
          // Version 1 is released, version 2 stays a draft.
          val _ =
            sql"update dms.template_version set status = 'Testing' where template_id = $templateId".update
              .run()
          val _ =
            sql"""update dms.template_version set status = 'Released', released_by = ${"designer-2"}, released_at = now()
                        where template_id = $templateId""".update.run()
          sql"""insert into dms.template_version
                  (tenant_id, template_id, version, name, document_class_id, document_class_version, status, content,
                   created_by, status_changed_by)
                values (${tenant.value}, $templateId, 2, ${"Contract"}, $classId, 1, ${"Draft"}, $content,
                        ${"designer"}, ${"designer"})""".update.run()
        })
        .as(Fixture(tenant, classId, templateId))
    )

  private def asApp[A](tenant: TenantId)(f: DbTx ?=> A): ZIO[Env, DbError, A] =
    ZIO.serviceWithZIO[Env](_.app.transact(tenant)(Db.sql(f)))

  private def insertDocument(f: Fixture, templateVersion: Int = 1)(using DbTx): UUID =
    val id = UUID.randomUUID()
    val _  =
      sql"""insert into dms.document (id, tenant_id, class_id, class_version, title, author_id, template_id,
                                              template_version)
                   values ($id, dms.current_tenant(), ${f.classId}, 1, ${"Contract"}, ${"author"}, ${f.templateId},
                           $templateVersion)""".update.run()
    id

  private def insertVersion(f: Fixture, document: UUID, major: Int, scope: String = "")(using DbTx): UUID =
    val id = UUID.randomUUID()
    val _  =
      sql"""insert into dms.document_version (id, tenant_id, document_id, major, minor, scope, class_version,
                                                     template_id, template_version, content, created_by,
                                                     status_changed_by)
                   values ($id, dms.current_tenant(), $document, $major, 0, $scope, 1, ${f.templateId}, 1, $content,
                           ${"author"}, ${"author"})""".update.run()
    id

  private def setStatus(version: UUID, status: String)(using DbTx): Int =
    sql"update dms.document_version set status = $status where id = $version".update.run()

  /** Moves a draft through review and approval (with snapshot, rendition and a second person) to `Approved`.
    */
  private def approve(version: UUID)(using DbTx): Unit =
    val _ = setStatus(version, "InReview")
    val _ = setStatus(version, "InApproval")
    val _ =
      sql"""update dms.document_version set rendition_key = ${"renditions/x.pdf"}, rendition_sha256 = $checksum
                  where id = $version""".update.run()
    val _ = sql"""update dms.document_version
                  set status = 'Approved', metadata_snapshot = $snapshot, released_by = ${"approver"},
                      released_at = now()
                  where id = $version""".update.run()

  private def isGuardViolation(e: Either[DbError, ?]): Boolean =
    e.left.exists(_.isInstanceOf[DbError.AppendOnlyViolation])

  private def isUnique(e: Either[DbError, ?], constraint: String): Boolean =
    e.left.exists {
      case DbError.UniqueViolation(c, _) => c == constraint
      case _                             => false
    }

  def spec = suite("Document versions in PostgreSQL")(
    test("documents are created only from released template versions") {
      for
        f        <- fixture
        released <- asApp(f.tenant)(insertDocument(f)).either
        draft    <- asApp(f.tenant)(insertDocument(f, templateVersion = 2)).either
      yield assertTrue(released.isRight, isGuardViolation(draft))
    },
    test("a document has at most one open version") {
      for
        f      <- fixture
        doc    <- asApp(f.tenant)(insertDocument(f))
        _      <- asApp(f.tenant)(insertVersion(f, doc, 1))
        second <- asApp(f.tenant)(insertVersion(f, doc, 2)).either
      yield assertTrue(isUnique(second, "document_version_one_open"))
    },
    test("a version starts as a draft") {
      for
        f         <- fixture
        doc       <- asApp(f.tenant)(insertDocument(f))
        published <- asApp(f.tenant)(
          sql"""insert into dms.document_version (id, tenant_id, document_id, major, minor, status, class_version,
                                                  template_id, template_version, content, metadata_snapshot,
                                                  rendition_key, rendition_sha256, released_by, released_at,
                                                  created_by, status_changed_by)
                values (${UUID
              .randomUUID()}, dms.current_tenant(), $doc, 1, 0, 'Published', 1, ${f.templateId}, 1,
                        $content, $snapshot, ${"k"}, $checksum, ${"approver"}, now(), ${"author"}, ${"author"})""".update
            .run()
        ).either
      yield assertTrue(isGuardViolation(published))
    },
    test("only drafts change; review and approval freeze the content") {
      for
        f    <- fixture
        doc  <- asApp(f.tenant)(insertDocument(f))
        v    <- asApp(f.tenant)(insertVersion(f, doc, 1))
        edit <- asApp(f.tenant)(
          sql"""update dms.document_version set field_values = ${Jsonb("""{"a":1}""")}, working_state = 2
                where id = $v""".update.run()
        )
        _        <- asApp(f.tenant)(setStatus(v, "InReview"))
        inReview <- asApp(f.tenant)(
          sql"update dms.document_version set content = $edited where id = $v".update.run()
        ).either
        lowered <- asApp(f.tenant)(
          setStatus(v, "Draft") + sql"update dms.document_version set working_state = 1 where id = $v".update
            .run()
        ).either
      yield assertTrue(edit == 1, isGuardViolation(inReview), isGuardViolation(lowered))
    },
    test("status changes follow the lifecycle") {
      for
        f       <- fixture
        doc     <- asApp(f.tenant)(insertDocument(f))
        v       <- asApp(f.tenant)(insertVersion(f, doc, 1))
        skipped <- asApp(f.tenant)(setStatus(v, "Published")).either
        _       <- asApp(f.tenant)(setStatus(v, "InReview"))
        back    <- asApp(f.tenant)(setStatus(v, "Draft"))
      yield assertTrue(isGuardViolation(skipped), back == 1)
    },
    test("approval needs a metadata snapshot, a rendition and a person other than the author") {
      for
        f    <- fixture
        doc  <- asApp(f.tenant)(insertDocument(f))
        v    <- asApp(f.tenant)(insertVersion(f, doc, 1))
        _    <- asApp(f.tenant)(setStatus(v, "InReview") + setStatus(v, "InApproval"))
        bare <- asApp(f.tenant)(
          sql"update dms.document_version set status = 'Approved', released_by = 'approver', released_at = now() where id = $v".update
            .run()
        ).either
        _ <- asApp(f.tenant)(
          sql"""update dms.document_version set rendition_key = ${"k"}, rendition_sha256 = $checksum where id = $v""".update
            .run()
        )
        byAuthor <- asApp(f.tenant)(
          sql"""update dms.document_version
                set status = 'Approved', metadata_snapshot = $snapshot, released_by = ${"author"}, released_at = now()
                where id = $v""".update.run()
        ).either
        byOther <- asApp(f.tenant)(
          sql"""update dms.document_version
                set status = 'Approved', metadata_snapshot = $snapshot, released_by = ${"approver"}, released_at = now()
                where id = $v""".update.run()
        )
      yield assertTrue(
        bare.left.exists(_.isInstanceOf[DbError.Other]),
        byAuthor.left.exists(_.isInstanceOf[DbError.Other]),
        byOther == 1
      )
    },
    test("an approved version package is immutable") {
      for
        f   <- fixture
        doc <- asApp(f.tenant)(insertDocument(f))
        v   <- asApp(f.tenant)(insertVersion(f, doc, 1))
        _   <- asApp(f.tenant)(approve(v))
        changes = List(
          sql"update dms.document_version set content = ${Jsonb("""{"format":1,"sections":[]}""")} where id = $v",
          sql"update dms.document_version set field_values = ${Jsonb("""{"a":1}""")} where id = $v",
          sql"update dms.document_version set metadata_snapshot = ${Jsonb("{}")} where id = $v",
          sql"update dms.document_version set template_version = 2 where id = $v",
          sql"update dms.document_version set rendition_sha256 = ${"b" * 64} where id = $v",
          sql"update dms.document_version set released_by = ${"someone"} where id = $v",
          sql"update dms.document_version set status = 'Draft' where id = $v"
        )
        results <- ZIO.foreach(changes)(frag => asApp(f.tenant)(frag.update.run()).either)
        delete  <- asApp(f.tenant)(sql"delete from dms.document_version where id = $v".update.run()).either
        ownerDelete <- ZIO.serviceWithZIO[Env](
          _.owner
            .transactSystem(Db.sql(sql"delete from dms.document_version where id = $v".update.run()))
            .either
        )
        published    <- asApp(f.tenant)(setStatus(v, "Published"))
        afterPublish <- asApp(f.tenant)(
          sql"update dms.document_version set content = $edited where id = $v".update.run()
        ).either
      yield assertTrue(
        results.forall(isGuardViolation),
        delete.left.exists(_.isInstanceOf[DbError.PermissionDenied]),
        isGuardViolation(ownerDelete),
        published == 1,
        isGuardViolation(afterPublish)
      )
    },
    test("a document has at most one valid version per scope") {
      for
        f        <- fixture
        doc      <- asApp(f.tenant)(insertDocument(f))
        v1       <- asApp(f.tenant)(insertVersion(f, doc, 1))
        _        <- asApp(f.tenant) { approve(v1); setStatus(v1, "Published") }
        v2       <- asApp(f.tenant)(insertVersion(f, doc, 2))
        _        <- asApp(f.tenant)(approve(v2))
        twoValid <- asApp(f.tenant)(setStatus(v2, "Published")).either
        // Superseding the old version first makes room for the new one.
        superseded <- asApp(f.tenant)(setStatus(v1, "Superseded") + setStatus(v2, "Published"))
        v3         <- asApp(f.tenant)(insertVersion(f, doc, 3, scope = "AT"))
        _          <- asApp(f.tenant)(approve(v3))
        otherScope <- asApp(f.tenant)(setStatus(v3, "Published"))
      yield assertTrue(isUnique(twoValid, "document_version_one_valid"), superseded == 2, otherScope == 1)
    },
    test("tenants only see their own documents") {
      for
        f       <- fixture
        other   <- fixture
        doc     <- asApp(f.tenant)(insertDocument(f))
        _       <- asApp(f.tenant)(insertVersion(f, doc, 1))
        own     <- asApp(f.tenant)(sql"select count(*) from dms.document_version".query[Long].run().head)
        foreign <- asApp(other.tenant)(sql"select count(*) from dms.document_version".query[Long].run().head)
        docs    <- asApp(other.tenant)(sql"select count(*) from dms.document".query[Long].run().head)
        // Referencing another tenant's document fails: the composite foreign key includes the tenant.
        crossInsert <- asApp(other.tenant)(insertVersion(other, doc, 2)).either
      yield assertTrue(own == 1L, foreign == 0L, docs == 0L, crossInsert.isLeft)
    },
    test("working states are append-only") {
      for
        f   <- fixture
        doc <- asApp(f.tenant)(insertDocument(f))
        v   <- asApp(f.tenant)(insertVersion(f, doc, 1))
        _   <- asApp(f.tenant)(
          sql"""insert into dms.document_working_state (tenant_id, version_id, working_state, content, field_values, saved_by)
                values (dms.current_tenant(), $v, 1, $content, ${Jsonb("{}")}, ${"author"})""".update.run()
        )
        update <- asApp(f.tenant)(
          sql"update dms.document_working_state set saved_by = 'x' where version_id = $v".update.run()
        ).either
        ownerUpdate <- ZIO.serviceWithZIO[Env](
          _.owner
            .transactSystem(
              Db.sql(
                sql"update dms.document_working_state set saved_by = 'x' where version_id = $v".update.run()
              )
            )
            .either
        )
      yield assertTrue(
        update.left.exists(_.isInstanceOf[DbError.PermissionDenied]),
        isGuardViolation(ownerUpdate)
      )
    },
    test("the status transitions of the guard are those of the lifecycle") {
      val migration = scala.io.Source
        .fromResource("db/migration/V9__document_versions.sql")
        .mkString
      val names = DocumentStatus.values.map(_.toString).toSet
      val pairs = """\('(\w+)', '(\w+)'\)""".r
        .findAllMatchIn(migration)
        .map(m => (m.group(1), m.group(2)))
        .filter((a, b) => names(a) && names(b))
        .toSet
      val lifecycle = for
        from  <- DocumentStatus.values.toSet
        event <- LifecycleEvent.values.toSet
        to    <- Lifecycle.transition(from, event).toOption
      yield (from.toString, to.toString)
      assertTrue(pairs == lifecycle)
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
