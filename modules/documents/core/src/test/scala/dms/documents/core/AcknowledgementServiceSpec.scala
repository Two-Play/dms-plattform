package dms.documents.core

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.audit.*
import dms.db.{Db, DbConfig, DbError, Jsonb, Migrations}
import dms.documents.*
import dms.documents.DocumentStatus.*
import dms.documents.LifecycleEvent.*
import dms.documents.metadata.*
import dms.kernel.{DocumentId, TemplateId, TenantId, UserId}
import dms.templates.*
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.json.*
import zio.test.*

import java.time.{Instant, LocalDate}
import java.util.UUID
import javax.sql.DataSource

/** Acknowledgement of valid versions (DMS-47, migration V14) in the documents service and in the database:
  * opening before acknowledging, once per reader and version, superseded versions, row-level security and
  * immutability. Against a Testcontainers database; time comes from the test clock.
  */
object AcknowledgementServiceSpec extends ZIOSpecDefault:

  final case class Env(app: Db, owner: Db, audit: Ref[List[AuditEvent]])

  final case class Fixture(tenant: TenantId, templateId: TemplateId)

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  private final class RecordingAuditTrail(events: Ref[List[AuditEvent]]) extends AuditTrail:
    def record(event: AuditEvent): IO[DbError, AuditRecordId] =
      events.update(_ :+ event).as(AuditRecordId(0L))
    def verify(tenant: TenantId): IO[DbError, VerificationReport] =
      ZIO.succeed(VerificationReport(tenant, 0, None, None, 0, None))

  /** Content codecs for the stub; the real format lives in templates-core (`ContentJson`). */
  private object Codecs:
    given JsonCodecConfiguration =
      JsonCodecConfiguration(sumTypeHandling =
        JsonCodecConfiguration.SumTypeHandling.DiscriminatorField("type")
      )
    given JsonCodec[SectionId]       = JsonCodec.string.transform(SectionId(_), _.value)
    given JsonCodec[Mark]            = JsonCodec.string.transform(Mark.valueOf, _.toString)
    given JsonCodec[Inline]          = DeriveJsonCodec.gen
    given JsonCodec[Block]           = DeriveJsonCodec.gen
    given JsonCodec[Section]         = DeriveJsonCodec.gen
    given JsonCodec[ComputedField]   = DeriveJsonCodec.gen
    given JsonCodec[ReferenceSource] =
      JsonCodec.string.transform(
        k => ReferenceSource.parse(k).getOrElse(ReferenceSource.PartnerMaster),
        _.key
      )
    given JsonCodec[ReferenceMapping]   = DeriveJsonCodec.gen
    given JsonCodec[ReferenceField]     = DeriveJsonCodec.gen
    given JsonCodec[TemplateContent]    = DeriveJsonCodec.gen
    given JsonCodec[UserId]             = JsonCodec.string.transform(UserId(_), _.value)
    given JsonCodec[Inline.Placeholder] = DeriveJsonCodec.gen
    given JsonCodec[PathStep]           = DeriveJsonCodec.gen
    given JsonCodec[DeviationLocation]  = DeriveJsonCodec.gen
    given JsonCodec[DeviationChange]    = DeriveJsonCodec.gen
    given JsonCodec[DeviationRequest]   = DeriveJsonCodec.gen
    given JsonCodec[Deviation]          = DeriveJsonCodec.gen
    given JsonCodec[DeviationReport]    = DeriveJsonCodec.gen

  /** Released template versions by id and number; no computed fields. */
  private object Templates extends TemplateSource:
    import Codecs.given
    val versions = java.util.concurrent.ConcurrentHashMap[(TemplateId, Int), TemplateVersion]()

    def version(id: TemplateId, version: Int): IO[DocumentError, TemplateVersion] =
      ZIO
        .succeed(Option(versions.get((id, version))))
        .someOrFail(DocumentError.NotFound(s"template ${id.value} version $version does not exist"))
    def check(template: TemplateVersion, content: TemplateContent): IO[DocumentError, List[String]] =
      ZIO.succeed(Nil)
    def computed(
        template: TemplateVersion,
        content: TemplateContent,
        values: Map[String, MetadataValue]
    ): IO[DocumentError, Map[String, MetadataValue]] = ZIO.succeed(Map.empty)
    def missingComputed(
        template: TemplateVersion,
        content: TemplateContent,
        values: Map[String, MetadataValue]
    ): IO[DocumentError, Map[String, List[String]]]                 = ZIO.succeed(Map.empty)
    def encode(content: TemplateContent): String                    = content.toJson
    def decode(json: String): Either[String, TemplateContent]       = json.fromJson[TemplateContent]
    def encodeReport(report: DeviationReport): String               = report.toJson
    def decodeReport(json: String): Either[String, DeviationReport] = json.fromJson[DeviationReport]

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
      _      <- Migrations.migrate(c.jdbcUrl, c.username, c.password)
      app    <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", poolSize = 2))
      owner  <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, poolSize = 2))
      events <- Ref.make(List.empty[AuditEvent])
    yield Env(Db(app), Db(owner), events)
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  private val admin    = UserId("admin-1")
  private val author   = UserId("author-1")
  private val approver = UserId("approver-1")

  private val text    = (1 to 50).map(i => s"word$i").mkString(" ")
  private val content = TemplateContent(
    List(
      Section(
        SectionId("terms"),
        Some("Terms"),
        locked = false,
        List(Block.Paragraph(List(Inline.Text(text))))
      )
    )
  )

  private def service(settings: DocumentSettings): ZIO[Env, Nothing, DocumentService] =
    ZIO.serviceWith[Env](e =>
      val audit = RecordingAuditTrail(e.audit)
      DocumentService(audit, DocumentClassService(audit), Templates, settings)
    )

  private def fixture(zone: String = "Europe/Berlin"): ZIO[Env, Any, Fixture] =
    val tenant     = TenantId.random()
    val templateId = TemplateId.random()
    for
      e <- ZIO.service[Env]
      _ <- e.owner.transactSystem(
        Db.sql(
          sql"insert into dms.tenant (id, name, time_zone) values (${tenant.value}, ${"Tenant"}, $zone)".update
            .run()
        )
      )
      classes = DocumentClassService(RecordingAuditTrail(e.audit))
      cls <- e.app.transact(tenant)(
        classes.createClass("policy", None, ClassDraft("Policy", None, Nil), admin)
      )
      stored = Jsonb(Templates.encode(content))
      _ <- e.owner.transactSystem(Db.sql {
        val _ = sql"""insert into dms.template (id, tenant_id, created_by)
                      values (${templateId.value}, ${tenant.value}, ${"designer"})""".update.run()
        val _ = sql"""insert into dms.template_version
                        (tenant_id, template_id, version, name, document_class_id, document_class_version, status,
                         content, created_by, status_changed_by)
                      values (${tenant.value}, ${templateId.value}, 1, ${"Policy"}, ${cls.classId.value}, 1,
                              ${"Testing"}, $stored, ${"designer"}, ${"designer"})""".update.run()
        sql"""update dms.template_version set status = 'Released', released_by = ${"designer-2"}, released_at = now()
              where template_id = ${templateId.value}""".update.run()
      })
      _ = Templates.versions.put(
        (templateId, 1),
        TemplateVersion(
          templateId,
          1,
          "Policy",
          None,
          cls.classId.value,
          1,
          TemplateStatus.Released,
          content,
          UserId("designer"),
          Instant.EPOCH
        )
      )
    yield Fixture(tenant, templateId)

  private def as[A](tenant: TenantId, settings: DocumentSettings = DocumentSettings.default)(
      f: DocumentService => IO[DocumentError, A]
  ) =
    for
      e <- ZIO.service[Env]
      s <- service(settings)
      a <- e.app.transact(tenant)(f(s))
    yield a

  private def values(validFrom: LocalDate): Map[String, FieldEntry] =
    Map(
      "owner"                -> MetadataValue.PersonRef("owner-1"),
      "organizationalUnit"   -> MetadataValue.Text("Compliance"),
      "validFrom"            -> MetadataValue.Date(validFrom),
      "confidentiality"      -> MetadataValue.Selection("Internal"),
      "language"             -> MetadataValue.Text("de"),
      "retentionRule"        -> MetadataValue.Text("policy-10y"),
      "containsPersonalData" -> MetadataValue.YesNo(false)
    ).map((k, v) => k -> FieldEntry(v, ValueOrigin.Manual))

  /** Moves the open version of a document through review to approval. */
  private def approveOpen(
      f: Fixture,
      doc: DocumentId,
      settings: DocumentSettings = DocumentSettings.default
  ) =
    for
      v <- as(f.tenant, settings)(_.submit(doc, author))
      _ <- as(f.tenant)(_.changeStatus(v.id, ReviewPassed, UserId("reviewer-1")))
      _ <- as(f.tenant)(_.attachRendition(v.id, Rendition(s"r/${v.id}.pdf", None, "a" * 64), author))
      a <- as(f.tenant)(_.changeStatus(v.id, Approve, approver))
    yield a

  /** A document whose version 1.0 is valid since 2026-01-01. */
  private def valid(f: Fixture): ZIO[Env, Any, (DocumentId, DocumentVersion)] =
    for
      _       <- TestClock.setTime(Instant.parse("2026-01-02T12:00:00Z"))
      created <- as(f.tenant)(
        _.create(NewDocument(f.templateId, 1, "Code of conduct", values(LocalDate.of(2026, 1, 1))), author)
      )
      (doc, v1) = created
      _         <- approveOpen(f, doc.id)
      published <- as(f.tenant)(_.changeStatus(v1.id, Publish, approver))
    yield (doc.id, published)

  /** Version 2.0 of `doc`, approved and valid from `validFrom`. */
  private def scheduled(f: Fixture, doc: DocumentId, validFrom: LocalDate) =
    for
      _ <- as(f.tenant)(_.newVersion(doc, ChangeKind.Major, None, author))
      _ <- as(f.tenant)(_.updateDraft(doc, DraftChange(None, values(validFrom), None), author))
      a <- approveOpen(f, doc)
    yield a

  private val reader = UserId("reader-1")

  private def acks: ZIO[Env, Nothing, AcknowledgementService] =
    ZIO.serviceWith[Env](e => AcknowledgementService(RecordingAuditTrail(e.audit)))

  private def withAcks[A](tenant: TenantId)(f: AcknowledgementService => IO[AcknowledgementError, A]) =
    for
      e <- ZIO.service[Env]
      s <- acks
      a <- e.app.transact(tenant)(f(s))
    yield a

  /** Runs `f` as the application user in `tenant`. */
  private def appSql[A](tenant: TenantId)(f: DbTx ?=> A): ZIO[Env, DbError, A] =
    ZIO.serviceWithZIO[Env](_.app.transact(tenant)(Db.sql(f)))

  /** Runs `f` as the schema owner (no row-level security, but the triggers apply). */
  private def ownerSql[A](f: DbTx ?=> A): ZIO[Env, DbError, A] =
    ZIO.serviceWithZIO[Env](_.owner.transactSystem(Db.sql(f)))

  private def count(table: String)(using DbTx): Long = table match
    case "acknowledgement" => sql"select count(*) from dms.document_acknowledgement".query[Long].run().head
    case _                 => sql"select count(*) from dms.document_version_opened".query[Long].run().head

  def spec = suite("Acknowledgements")(
    test("a reader acknowledges a valid version after opening it, once, with version and timestamp") {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        _          <- TestClock.setTime(Instant.parse("2026-01-03T09:00:00Z"))
        notOpened  <- withAcks(f.tenant)(_.acknowledge(v1, reader)).either
        opened     <- withAcks(f.tenant)(_.markOpened(v1, reader))
        _          <- TestClock.setTime(Instant.parse("2026-01-03T10:00:00Z"))
        openedLate <- withAcks(f.tenant)(_.markOpened(v1, reader))
        pending    <- withAcks(f.tenant)(_.pending(reader))
        done       <- withAcks(f.tenant)(_.acknowledge(v1, reader))
        again      <- withAcks(f.tenant)(_.acknowledge(v1, reader)).either
        status     <- withAcks(f.tenant)(_.status(v1.id, reader))
        after      <- withAcks(f.tenant)(_.pending(reader))
        mine       <- withAcks(f.tenant)(_.acknowledgementsOf(reader))
        audit      <- ZIO.serviceWithZIO[Env](_.audit.get)
        outbox     <- ownerSql(
          sql"""select payload::text from dms.outbox_event
                where aggregate_id = ${doc.value} and event_type = 'document.acknowledged'"""
            .query[String]
            .run()
            .toList
        )
        published <- as(f.tenant)(_.get(doc)).map(_.versions.head.publishedAt)
      yield assertTrue(
        notOpened.left.exists(_.isInstanceOf[AcknowledgementError.NotOpened]),
        opened.contains(Instant.parse("2026-01-03T09:00:00Z")),
        // The first opening is kept.
        openedLate == opened,
        pending == List((doc, v1.id)),
        done.versionLabel == "1.0",
        done.userId == reader,
        done.openedAt == Instant.parse("2026-01-03T09:00:00Z"),
        done.acknowledgedAt == Instant.parse("2026-01-03T10:00:00Z"),
        again.left.exists(_.isInstanceOf[AcknowledgementError.AlreadyAcknowledged]),
        status.openedAt == opened,
        status.acknowledgement.contains(done),
        after.isEmpty,
        mine == List(done),
        audit.count(e => e.action == "document.opened" && e.actor.id == reader.value) == 1,
        audit.exists(e =>
          e.action == "document.acknowledged" && e.target.id.contains(doc.value) &&
            e.target.version.contains("1.0") && e.details.value.contains(done.id.toString)
        ),
        outbox.size == 1,
        outbox.head.contains(reader.value),
        published.contains(Instant.parse("2026-01-02T12:00:00Z"))
      )
    },
    test("a version that does not require acknowledgement or is not valid does not ask for it") {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        minor <- as(f.tenant)(_.newVersion(doc, ChangeKind.Minor, Some("Typo"), author))
        // Open versions are not valid yet.
        openDraft <- withAcks(f.tenant)(_.markOpened(minor, reader))
        draftAck  <- withAcks(f.tenant)(_.acknowledge(minor, reader)).either
        _         <- approveOpen(f, doc)
        published <- as(f.tenant)(_.changeStatus(minor.id, Publish, approver))
        openMinor <- withAcks(f.tenant)(_.markOpened(published, reader))
        minorAck  <- withAcks(f.tenant)(_.acknowledge(published, reader)).either
        pending   <- withAcks(f.tenant)(_.pending(reader))
        rows      <- appSql(f.tenant)(count("opened"))
      yield assertTrue(
        v1.requiresAcknowledgement,
        !minor.requiresAcknowledgement,
        openDraft.isEmpty,
        draftAck.left.exists(_.isInstanceOf[AcknowledgementError.NotRequested]),
        openMinor.isEmpty,
        minorAck.left.exists(_.isInstanceOf[AcknowledgementError.NotRequested]),
        // 1.0 was superseded by 1.1, which does not ask for acknowledgement.
        pending.isEmpty,
        rows == 0L
      )
    },
    test("superseding a version ends its open acknowledgements; the new major version asks on its own") {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        _      <- withAcks(f.tenant)(_.markOpened(v1, reader))
        before <- withAcks(f.tenant)(_.pending(reader))
        v2     <- scheduled(f, doc, LocalDate.of(2026, 1, 1))
        _      <- as(f.tenant)(_.publishScheduled(v2.id))
        after  <- withAcks(f.tenant)(_.pending(reader))
        old    <- as(f.tenant)(_.get(doc)).map(_.versions.head)
        oldAck <- withAcks(f.tenant)(_.acknowledge(old, reader)).either
        opened <- withAcks(f.tenant)(_.openedValid(reader))
      yield assertTrue(
        before == List((doc, v1.id)),
        after == List((doc, v2.id)),
        old.status == Superseded,
        oldAck.left.exists(_.isInstanceOf[AcknowledgementError.NotRequested]),
        // The opening of the superseded version does not count for its successor.
        opened.isEmpty
      )
    },
    test("acknowledgements and openings are insert-only and need an opening of a valid version") {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        _         <- withAcks(f.tenant)(_.markOpened(v1, reader))
        ack       <- withAcks(f.tenant)(_.acknowledge(v1, reader))
        appUpdate <- appSql(f.tenant)(
          sql"update dms.document_acknowledgement set acknowledged_at = now()".update.run()
        ).either
        appDelete   <- appSql(f.tenant)(sql"delete from dms.document_version_opened".update.run()).either
        ownerUpdate <- ownerSql(
          sql"update dms.document_acknowledgement set version_label = '9.9' where id = ${ack.id}".update.run()
        ).either
        ownerDelete <- ownerSql(
          sql"delete from dms.document_version_opened where version_id = ${v1.id}".update.run()
        ).either
        // Another user who did not open the version.
        unopened <- appSql(f.tenant)(
          sql"""insert into dms.document_acknowledgement
                  (id, tenant_id, document_id, version_id, version_label, user_id, opened_at)
                values (${UUID.randomUUID()}, dms.current_tenant(), ${doc.value}, ${v1.id}, '1.0', 'someone',
                        now())""".update.run()
        ).either
        wrongLabel <- appSql(f.tenant)(
          sql"""insert into dms.document_acknowledgement
                  (id, tenant_id, document_id, version_id, version_label, user_id, opened_at)
                select ${UUID.randomUUID()}, dms.current_tenant(), ${doc.value}, ${v1.id}, '2.0', 'reader-2',
                       opened_at
                from dms.document_version_opened where user_id = ${reader.value}""".update.run()
        ).either
        publishedAtChange <- ownerSql(
          sql"update dms.document_version set published_at = now() where id = ${v1.id}".update.run()
        ).either
        appPublishedAt <- appSql(f.tenant)(
          sql"update dms.document_version set published_at = now() where id = ${v1.id}".update.run()
        ).either
      yield assertTrue(
        appUpdate.left.exists(_.isInstanceOf[DbError.PermissionDenied]),
        appDelete.left.exists(_.isInstanceOf[DbError.PermissionDenied]),
        ownerUpdate.left.exists(_.isInstanceOf[DbError.AppendOnlyViolation]),
        ownerDelete.left.exists(_.isInstanceOf[DbError.AppendOnlyViolation]),
        unopened.left.exists(_.toString.contains("was not opened")),
        wrongLabel.left.exists(_.toString.contains("requires acknowledgement as 2.0")),
        publishedAtChange.left.exists(_.toString.contains("recorded only on publication")),
        appPublishedAt.left.exists(_.isInstanceOf[DbError.PermissionDenied])
      )
    },
    test("row-level security: openings and acknowledgements are visible only in their tenant") {
      for
        f       <- fixture()
        other   <- fixture()
        created <- valid(f)
        (_, v1) = created
        _             <- withAcks(f.tenant)(_.markOpened(v1, reader))
        _             <- withAcks(f.tenant)(_.acknowledge(v1, reader))
        own           <- appSql(f.tenant)(count("acknowledgement"))
        foreignAcks   <- appSql(other.tenant)(count("acknowledgement"))
        foreignOpened <- appSql(other.tenant)(count("opened"))
        crossInsert   <- appSql(other.tenant)(
          sql"""insert into dms.document_version_opened (tenant_id, version_id, user_id)
                values (${f.tenant.value}, ${v1.id}, 'intruder')""".update.run()
        ).either
        listed <- withAcks(other.tenant)(_.acknowledgementsOf(reader))
      yield assertTrue(
        own == 1L,
        foreignAcks == 0L,
        foreignOpened == 0L,
        crossInsert.isLeft,
        listed.isEmpty
      )
    }
  ).provideShared(env) @@ TestAspect.sequential
