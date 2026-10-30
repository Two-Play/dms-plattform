package dms.documents.core

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.audit.*
import dms.db.queue.ClaimedJob
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

/** Scheduled publication (DMS-45) and editorial minor versions (DMS-46) in the documents service, against a
  * Testcontainers database. Time comes from the test clock: the publication job runs at chosen instants.
  */
object VersionPublicationSpec extends ZIOSpecDefault:

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

  private def withText(t: String) =
    TemplateContent(
      List(
        Section(
          SectionId("terms"),
          Some("Terms"),
          locked = false,
          List(Block.Paragraph(List(Inline.Text(t))))
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
      _ <- as(f.tenant)(_.newVersion(doc, ChangeKind.Major, Some("Content revised"), author))
      _ <- as(f.tenant)(_.updateDraft(doc, DraftChange(None, values(validFrom), None), author))
      a <- approveOpen(f, doc)
    yield a

  private def jobs(settings: DocumentSettings = DocumentSettings.default) =
    for
      e <- ZIO.service[Env]
      s <- service(settings)
    yield PublicationJobs(e.app, s, PublicationScheduleConfig())

  private def claimed(tenant: TenantId, version: UUID) =
    ClaimedJob(
      1L,
      Some(tenant),
      PublicationJobs.PublishKind,
      Jsonb(s"""{"versionId":"$version"}"""),
      1,
      10
    )

  private def jobRows(version: UUID): ZIO[Env, DbError, List[(String, Instant)]] =
    ZIO.serviceWithZIO[Env](
      _.owner.transactSystem(
        Db.sql(
          // Publication jobs only: an approval also queues the Object Lock retention of the version (DMS-93).
          sql"""select idempotency_key, run_at from dms.job where payload ->> 'versionId' = ${version.toString}
                and kind = ${PublicationJobs.PublishKind} order by run_at"""
            .query[(String, java.time.OffsetDateTime)]
            .run()
            .toList
            .map((k, t) => (k, t.toInstant))
        )
      )
    )

  def spec = suite("Scheduled publication and editorial versions")(
    test(
      "an approved version is published at 00:00 of its valid-from date, replacing the valid one at once"
    ) {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        v2        <- scheduled(f, doc, LocalDate.of(2027, 1, 1))
        runner    <- jobs()
        _         <- TestClock.setTime(Instant.parse("2026-12-31T22:59:59Z"))
        enqueued  <- runner.enqueue(f.tenant)
        again     <- runner.enqueue(f.tenant)
        rows      <- jobRows(v2.id)
        early     <- as(f.tenant)(_.changeStatus(v2.id, Publish, approver)).either
        _         <- runner.handlers(PublicationJobs.PublishKind)(claimed(f.tenant, v2.id))
        beforeDue <- as(f.tenant)(_.get(doc))
        _         <- TestClock.setTime(Instant.parse("2026-12-31T23:00:00Z"))
        _         <- runner.handlers(PublicationJobs.PublishKind)(claimed(f.tenant, v2.id))
        // At-least-once delivery: running the job again changes nothing.
        _     <- runner.handlers(PublicationJobs.PublishKind)(claimed(f.tenant, v2.id))
        after <- as(f.tenant)(_.get(doc))
        audit <- ZIO.serviceWithZIO[Env](_.audit.get)
        old = after.versions.find(_.id == v1.id).get
      yield assertTrue(
        enqueued == 1,
        again == 0,
        rows == List(
          (
            s"document.publish:${v2.id}:${Instant.parse("2026-12-31T23:00:00Z").getEpochSecond}",
            Instant.parse("2026-12-31T23:00:00Z")
          )
        ),
        early.left.exists {
          case DocumentError.Conflict(m) => m.contains("2027-01-01")
          case _                         => false
        },
        beforeDue.versions.map(_.status) == List(Published, Approved),
        beforeDue.successor(beforeDue.versions.head).map(_.id) == Some(v2.id),
        beforeDue.publicationDue(beforeDue.versions(1)) == Some(Instant.parse("2026-12-31T23:00:00Z")),
        after.versions.map(v => (v.label, v.status)) == List(("1.0", Superseded), ("2.0", Published)),
        old.supersededBy == Some(v2.id),
        after.successor(old).map(_.id) == Some(v2.id),
        after.versions(1).validFrom == Some(LocalDate.of(2027, 1, 1)),
        audit.count(e =>
          e.action == "document.published" && e.target.id.contains(doc.value) && e.target.version.contains(
            "2.0"
          )
        ) == 1,
        audit.exists(e =>
          e.action == "document.published" && e.actor.kind == ActorKind.Technical &&
            e.channel == Channel.Automation && e.actor.id == DocumentService.Scheduler.value
        )
      )
    },
    test("the switch follows the tenant's time zone") {
      for
        f       <- fixture("America/New_York")
        created <- valid(f)
        (doc, _) = created
        v2     <- scheduled(f, doc, LocalDate.of(2027, 3, 1))
        listed <- as(f.tenant)(_.scheduledPublications)
        _      <- TestClock.setTime(Instant.parse("2027-03-01T04:59:59Z"))
        early  <- as(f.tenant)(_.publishScheduled(v2.id))
        _      <- TestClock.setTime(Instant.parse("2027-03-01T05:00:00Z"))
        onTime <- as(f.tenant)(_.publishScheduled(v2.id))
      yield assertTrue(
        listed == List(ScheduledPublication(doc, v2.id, Instant.parse("2027-03-01T05:00:00Z"))),
        early.isEmpty,
        onTime.map(_.status) == Some(Published)
      )
    },
    test("a version whose valid-from date has passed is published by the next job run") {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, _) = created
        _      <- TestClock.setTime(Instant.parse("2026-03-01T08:00:00Z"))
        v2     <- scheduled(f, doc, LocalDate.of(2026, 2, 1))
        runner <- jobs()
        _      <- runner.enqueue(f.tenant)
        rows   <- jobRows(v2.id)
        _      <- runner.handlers(PublicationJobs.PublishKind)(claimed(f.tenant, v2.id))
        after  <- as(f.tenant)(_.get(doc))
      yield assertTrue(
        rows.map(_._2) == List(Instant.parse("2026-01-31T23:00:00Z")),
        after.versions.map(_.status) == List(Superseded, Published)
      )
    },
    test("no new version starts while an approved version waits for its valid-from date") {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, _) = created
        _       <- scheduled(f, doc, LocalDate.of(2027, 1, 1))
        blocked <- as(f.tenant)(_.newVersion(doc, ChangeKind.Minor, Some("Typo"), author)).either
      yield assertTrue(blocked.left.exists {
        case DocumentError.Conflict(m) => m.contains("2.0") && m.contains("2027-01-01")
        case _                         => false
      })
    },
    test("an editorial version needs a reason and does not ask for a new acknowledgement by default") {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        noReason <- as(f.tenant)(_.newVersion(doc, ChangeKind.Minor, Some("  "), author)).either
        minor    <- as(f.tenant)(_.newVersion(doc, ChangeKind.Minor, Some("Typo in section 1"), author))
        events   <- ZIO.serviceWithZIO[Env](e =>
          e.owner.transactSystem(
            Db.sql(
              sql"""select payload::text from dms.outbox_event
                    where aggregate_id = ${doc.value} and event_type = 'document.version-created'"""
                .query[String]
                .run()
                .toList
            )
          )
        )
      yield assertTrue(
        noReason.left.exists(_.isInstanceOf[DocumentError.Invalid]),
        v1.requiresAcknowledgement,
        minor.label == "1.1-draft.1",
        !minor.requiresAcknowledgement,
        events.exists(_.replace(" ", "").contains("\"requiresAcknowledgement\":false"))
      )
    },
    test(
      "whether minor versions ask for acknowledgement is configurable, per tenant setting and per version"
    ) {
      val configured = DocumentSettings(minorVersionsRequireAcknowledgement = true)
      for
        f       <- fixture()
        created <- valid(f)
        (doc, _) = created
        byDefault <- as(f.tenant, configured)(_.newVersion(doc, ChangeKind.Minor, Some("Typo"), author))
        _         <- approveOpen(f, doc)
        _         <- as(f.tenant)(_.changeStatus(byDefault.id, Publish, approver))
        explicit  <- as(f.tenant, configured)(
          _.newVersion(doc, ChangeKind.Minor, Some("Layout"), author, requiresAcknowledgement = Some(false))
        )
      yield assertTrue(byDefault.requiresAcknowledgement, !explicit.requiresAcknowledgement)
    },
    test("the automatic comparison warns when an editorial version changes more text than the threshold") {
      val settings = DocumentSettings(editorialChangeThreshold = 0.1)
      for
        f       <- fixture()
        created <- valid(f)
        (doc, _) = created
        minor <- as(f.tenant, settings)(_.newVersion(doc, ChangeKind.Minor, Some("Typos"), author))
        // Two of fifty words: 4 %.
        typo = withText(text.replace("word7 ", "wort7 ").replace("word9 ", "wort9 "))
        _ <- as(f.tenant)(
          _.updateDraft(doc, DraftChange(None, values(LocalDate.of(2026, 1, 1)), Some(typo)), author)
        )
        small <- as(f.tenant)(_.get(doc))
        // Ten of fifty words: 20 %; still possible, but flagged.
        rewrite = withText((1 to 50).map(i => if i <= 10 then s"new$i" else s"word$i").mkString(" "))
        _ <- as(f.tenant)(
          _.updateDraft(doc, DraftChange(None, values(LocalDate.of(2026, 1, 1)), Some(rewrite)), author)
        )
        large     <- as(f.tenant)(_.get(doc))
        submitted <- as(f.tenant, settings)(_.submit(doc, author))
        audit     <- ZIO.serviceWithZIO[Env](_.audit.get)
        check = audit.filter(e => e.action == "document.editorial-check" && e.target.id.contains(doc.value))
      yield assertTrue(
        small.editorialCheck(small.versions.last, 0.1) == Some(EditorialCheck("1.0", 0.04, 0.1)),
        large.editorialCheck(large.versions.last, 0.1).exists(_.exceedsThreshold),
        submitted.status == InReview,
        check.size == 1,
        check.head.details.value.replace(" ", "").contains("\"exceedsThreshold\":true"),
        check.head.details.value.contains("Typos"),
        minor.changeKind == ChangeKind.Minor
      )
    }
  ).provideShared(env) @@ TestAspect.sequential
