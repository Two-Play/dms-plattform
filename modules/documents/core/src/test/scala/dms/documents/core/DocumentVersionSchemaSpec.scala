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

/** The version model in PostgreSQL (migrations V9, V10, V12), tested with plain SQL as the application role:
  * the guards hold for every writer, not only for the service.
  */
object DocumentVersionSchemaSpec extends ZIOSpecDefault:

  final case class Env(app: Db, owner: Db, appSource: DataSource)

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
    yield Env(Db(app), Db(owner), app)
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

  /** Supersedes a valid version, naming its successor (migration V12). */
  private def supersede(version: UUID, successor: UUID)(using DbTx): Int =
    sql"""update dms.document_version set status = 'Superseded', superseded_by = $successor
          where id = $version""".update.run()

  /** The valid versions of a document as the transaction of `connection` sees them. */
  private def validVersions(connection: java.sql.Connection, tenant: TenantId, document: UUID): List[UUID] =
    useTenant(connection, tenant)
    val statement = connection.prepareStatement(
      "select id from dms.document_version where document_id = ? and status in ('Published', 'ReviewDue')"
    )
    try
      statement.setObject(1, document)
      val rows = statement.executeQuery()
      Iterator.continually(rows).takeWhile(_.next()).map(_.getObject(1, classOf[UUID])).toList
    finally statement.close()

  /** Runs an update on `connection` for `tenant`. */
  private def execute(connection: java.sql.Connection, tenant: TenantId, sql: String, params: Any*): Int =
    useTenant(connection, tenant)
    val statement = connection.prepareStatement(sql)
    try
      params.zipWithIndex.foreach((p, i) => statement.setObject(i + 1, p))
      statement.executeUpdate()
    finally statement.close()

  /** Sets the tenant for the current transaction of `connection`, like [[Db.transact]] does. */
  private def useTenant(connection: java.sql.Connection, tenant: TenantId): Unit =
    val statement = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")
    try
      statement.setString(1, tenant.value.toString)
      statement.executeQuery().close()
    finally statement.close()

  /** A connection of the application role outside [[Db.transact]], to keep a transaction open while another
    * one reads.
    */
  private def appConnection(source: DataSource): java.sql.Connection =
    val connection = source.getConnection
    connection.setAutoCommit(false)
    connection

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

  private def insertRequest(version: UUID, section: String, reason: String = "Customer requirement")(using
      DbTx
  ): UUID =
    val id = UUID.randomUUID()
    val _  = sql"""insert into dms.document_deviation_request
                     (id, tenant_id, version_id, section_id, reason, requested_by)
                   values ($id, dms.current_tenant(), $version, $section, $reason, ${"author"})""".update
      .run()
    id

  private def withdraw(request: UUID)(using DbTx): Int =
    sql"""update dms.document_deviation_request set withdrawn_by = ${"author"}, withdrawn_at = now()
          where id = $request""".update.run()

  private def updateReport(version: UUID, deviations: String = "[{}]")(using DbTx): Int =
    sql"""update dms.document_version set deviation_report = ${Jsonb(
        s"""{"format":1,"deviations":$deviations}"""
      )}
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
        superseded <- asApp(f.tenant)(supersede(v1, v2) + setStatus(v2, "Published"))
        v3         <- asApp(f.tenant)(insertVersion(f, doc, 3, scope = "AT"))
        _          <- asApp(f.tenant)(approve(v3))
        otherScope <- asApp(f.tenant)(setStatus(v3, "Published"))
      yield assertTrue(isUnique(twoValid, "document_version_one_valid"), superseded == 2, otherScope == 1)
    },
    test("a valid version is superseded only by naming a successor that is valid when the transaction ends") {
      for
        f   <- fixture
        doc <- asApp(f.tenant)(insertDocument(f))
        v1  <- asApp(f.tenant)(insertVersion(f, doc, 1))
        _   <- asApp(f.tenant) { approve(v1); setStatus(v1, "Published") }
        v2  <- asApp(f.tenant)(insertVersion(f, doc, 2))
        _   <- asApp(f.tenant)(approve(v2))
        // Never no valid version: superseding alone fails, with or without a successor.
        unnamed  <- asApp(f.tenant)(setStatus(v1, "Superseded")).either
        notValid <- asApp(f.tenant)(supersede(v1, v2)).either
        // Never two valid versions: publishing before superseding fails.
        twoValid <- asApp(f.tenant)(setStatus(v2, "Published")).either
        // A version of another scope is no successor.
        v3         <- asApp(f.tenant)(insertVersion(f, doc, 3, scope = "AT"))
        _          <- asApp(f.tenant) { approve(v3); setStatus(v3, "Published") }
        otherScope <- asApp(f.tenant)(supersede(v1, v3)).either
        // Both in one transaction, in the order the unique index allows.
        switched <- asApp(f.tenant)(supersede(v1, v2) + setStatus(v2, "Published"))
        valid    <- ZIO.serviceWithZIO[Env](e =>
          ZIO.attemptBlocking {
            val c = appConnection(e.appSource)
            try validVersions(c, f.tenant, doc).toSet
            finally
              c.rollback()
              c.close()
          }
        )
        renamed <- asApp(f.tenant)(
          sql"update dms.document_version set superseded_by = $v3 where id = $v1".update.run()
        ).either
      yield assertTrue(
        isGuardViolation(unnamed),
        isGuardViolation(notValid),
        isUnique(twoValid, "document_version_one_valid"),
        isGuardViolation(otherScope),
        switched == 2,
        valid == Set(v2, v3),
        isGuardViolation(renamed)
      )
    },
    test("readers see exactly one valid version before, during and after the switch") {
      for
        f    <- fixture
        doc  <- asApp(f.tenant)(insertDocument(f))
        v1   <- asApp(f.tenant)(insertVersion(f, doc, 1))
        _    <- asApp(f.tenant) { approve(v1); setStatus(v1, "Published") }
        v2   <- asApp(f.tenant)(insertVersion(f, doc, 2))
        _    <- asApp(f.tenant)(approve(v2))
        seen <- ZIO.serviceWithZIO[Env](e =>
          ZIO.attemptBlocking {
            val writer = appConnection(e.appSource)
            val reader = appConnection(e.appSource)
            try
              val before = validVersions(reader, f.tenant, doc)
              reader.rollback()
              val _ = execute(
                writer,
                f.tenant,
                "update dms.document_version set status = 'Superseded', superseded_by = ? where id = ?",
                v2,
                v1
              )
              val _ = execute(
                writer,
                f.tenant,
                "update dms.document_version set status = 'Published' where id = ?",
                v2
              )
              // The switch is not finished: the reader still sees the old version, not none and not both.
              val during = validVersions(reader, f.tenant, doc)
              reader.rollback()
              writer.commit()
              val after = validVersions(reader, f.tenant, doc)
              reader.rollback()
              (before, during, after)
            finally
              writer.close()
              reader.close()
          }
        )
      yield assertTrue(seen == (List(v1), List(v1), List(v2)))
    },
    test(
      "the valid-from date is recorded on approval, the acknowledgement flag on creation; both are frozen"
    ) {
      for
        f         <- fixture
        doc       <- asApp(f.tenant)(insertDocument(f))
        v         <- asApp(f.tenant)(insertVersion(f, doc, 1))
        draftDate <- asApp(f.tenant)(
          sql"update dms.document_version set valid_from = '2027-01-01'::date where id = $v".update.run()
        ).either
        _ <- asApp(f.tenant)(setStatus(v, "InReview") + setStatus(v, "InApproval"))
        _ <- asApp(f.tenant)(
          sql"""update dms.document_version set rendition_key = ${"k"}, rendition_sha256 = $checksum
                where id = $v""".update.run()
        )
        approved <- asApp(f.tenant)(
          sql"""update dms.document_version
                set status = 'Approved', metadata_snapshot = $snapshot, released_by = ${"approver"},
                    released_at = now(), valid_from = '2027-01-01'::date
                where id = $v""".update.run()
        )
        later <- asApp(f.tenant)(
          sql"update dms.document_version set valid_from = '2026-01-01'::date where id = $v".update.run()
        ).either
        acknowledgement <- asApp(f.tenant)(
          sql"update dms.document_version set requires_acknowledgement = false where id = $v".update.run()
        ).either
        ownerAcknowledgement <- ZIO.serviceWithZIO[Env](
          _.owner
            .transactSystem(
              Db.sql(
                sql"update dms.document_version set requires_acknowledgement = false where id = $v".update
                  .run()
              )
            )
            .either
        )
        stored <- asApp(f.tenant)(
          sql"select valid_from::text from dms.document_version where id = $v".query[String].run().head
        )
        preset <- asApp(f.tenant)(
          sql"""insert into dms.document_version (id, tenant_id, document_id, major, minor, class_version,
                                                  template_id, template_version, content, created_by,
                                                  status_changed_by, valid_from)
                values (${UUID
              .randomUUID()}, dms.current_tenant(), $doc, 2, 0, 1, ${f.templateId}, 1, $content,
                        ${"author"}, ${"author"}, '2027-01-01'::date)""".update.run()
        ).either
      yield assertTrue(
        isGuardViolation(draftDate),
        approved == 1,
        isGuardViolation(later),
        acknowledgement.left.exists(_.isInstanceOf[DbError.PermissionDenied]),
        isGuardViolation(ownerAcknowledgement),
        stored == "2027-01-01",
        isGuardViolation(preset)
      )
    },
    test("tenants have a time zone, Europe/Berlin by default; unknown zones are rejected") {
      for
        f    <- fixture
        zone <- asApp(f.tenant)(
          sql"select time_zone from dms.tenant where id = dms.current_tenant()".query[String].run().head
        )
        changed <- ZIO.serviceWithZIO[Env](
          _.owner.transactSystem(
            Db.sql(
              sql"update dms.tenant set time_zone = 'America/New_York' where id = ${f.tenant.value}".update
                .run()
            )
          )
        )
        unknown <- ZIO.serviceWithZIO[Env](
          _.owner
            .transactSystem(
              Db.sql(
                sql"update dms.tenant set time_zone = 'Mars/Olympus' where id = ${f.tenant.value}".update
                  .run()
              )
            )
            .either
        )
        byApp <- asApp(f.tenant)(
          sql"update dms.tenant set time_zone = 'UTC' where id = dms.current_tenant()".update.run()
        ).either
      yield assertTrue(
        zone == "Europe/Berlin",
        changed == 1,
        unknown.left.exists(_.isInstanceOf[DbError.Other]),
        byApp.left.exists(_.isInstanceOf[DbError.PermissionDenied])
      )
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
    test("deviation requests are recorded and withdrawn only while the version is a draft") {
      for
        f   <- fixture
        doc <- asApp(f.tenant)(insertDocument(f))
        v   <- asApp(f.tenant)(insertVersion(f, doc, 1))
        r1  <- asApp(f.tenant)(insertRequest(v, "liability"))
        r2  <- asApp(f.tenant)(insertRequest(v, "payment"))
        // One active request per section; a withdrawn one makes room for a new one.
        duplicate <- asApp(f.tenant)(insertRequest(v, "liability")).either
        withdrawn <- asApp(f.tenant)(withdraw(r1))
        again     <- asApp(f.tenant)(insertRequest(v, "liability")).either
        twice     <- asApp(f.tenant)(withdraw(r1)).either
        reworded  <- asApp(f.tenant)(
          sql"update dms.document_deviation_request set reason = 'other' where id = $r2".update.run()
        ).either
        ownerReworded <- ZIO.serviceWithZIO[Env](
          _.owner
            .transactSystem(
              Db.sql(
                sql"update dms.document_deviation_request set reason = 'other' where id = $r2".update.run()
              )
            )
            .either
        )
        delete <- asApp(f.tenant)(
          sql"delete from dms.document_deviation_request where id = $r2".update.run()
        ).either
        ownerDelete <- ZIO.serviceWithZIO[Env](
          _.owner
            .transactSystem(
              Db.sql(sql"delete from dms.document_deviation_request where id = $r2".update.run())
            )
            .either
        )
        report <- asApp(f.tenant)(updateReport(v))
        // Submission freezes the requests and the report.
        _              <- asApp(f.tenant)(setStatus(v, "InReview"))
        afterSubmit    <- asApp(f.tenant)(insertRequest(v, "termination")).either
        withdrawLater  <- asApp(f.tenant)(withdraw(r2)).either
        reportInReview <- asApp(f.tenant)(updateReport(v, "[{},{}]")).either
        // A rejected version is a draft again.
        _        <- asApp(f.tenant)(setStatus(v, "Draft"))
        reopened <- asApp(f.tenant)(withdraw(r2))
      yield assertTrue(
        isUnique(duplicate, "document_deviation_request_active"),
        withdrawn == 1,
        again.isRight,
        isGuardViolation(twice),
        reworded.left.exists(_.isInstanceOf[DbError.PermissionDenied]),
        isGuardViolation(ownerReworded),
        delete.left.exists(_.isInstanceOf[DbError.PermissionDenied]),
        isGuardViolation(ownerDelete),
        report == 1,
        isGuardViolation(afterSubmit),
        isGuardViolation(withdrawLater),
        isGuardViolation(reportInReview),
        reopened == 1
      )
    },
    test("a request needs a reason and a section") {
      for
        f         <- fixture
        doc       <- asApp(f.tenant)(insertDocument(f))
        v         <- asApp(f.tenant)(insertVersion(f, doc, 1))
        noReason  <- asApp(f.tenant)(insertRequest(v, "liability", reason = " ")).either
        noSection <- asApp(f.tenant)(insertRequest(v, " ")).either
      yield assertTrue(noReason.isLeft, noSection.isLeft)
    },
    test("tenants only see their own deviation requests") {
      for
        f     <- fixture
        other <- fixture
        doc   <- asApp(f.tenant)(insertDocument(f))
        v     <- asApp(f.tenant)(insertVersion(f, doc, 1))
        r     <- asApp(f.tenant)(insertRequest(v, "liability"))
        own   <- asApp(f.tenant)(
          sql"select count(*) from dms.document_deviation_request".query[Long].run().head
        )
        foreign <- asApp(other.tenant)(
          sql"select count(*) from dms.document_deviation_request".query[Long].run().head
        )
        foreignWithdraw <- asApp(other.tenant)(withdraw(r))
        // Requests for another tenant's version fail: the composite foreign key includes the tenant.
        crossInsert <- asApp(other.tenant)(insertRequest(v, "payment")).either
      yield assertTrue(own == 1L, foreign == 0L, foreignWithdraw == 0, crossInsert.isLeft)
    },
    test("the status transitions of the guard are those of the lifecycle") {
      val names     = DocumentStatus.values.map(_.toString).toSet
      val lifecycle = for
        from  <- DocumentStatus.values.toSet
        event <- LifecycleEvent.values.toSet
        to    <- Lifecycle.transition(from, event).toOption
      yield (from.toString, to.toString)
      // V10 replaces the guard of V9; both must allow exactly the lifecycle's transitions.
      val pairs = List("V9__document_versions.sql", "V10__deviation_requests.sql").map { file =>
        val migration = scala.io.Source.fromResource(s"db/migration/$file").mkString
        """\('(\w+)', '(\w+)'\)""".r
          .findAllMatchIn(migration)
          .map(m => (m.group(1), m.group(2)))
          .filter((a, b) => names(a) && names(b))
          .toSet
      }
      assertTrue(pairs.forall(_ == lifecycle))
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
