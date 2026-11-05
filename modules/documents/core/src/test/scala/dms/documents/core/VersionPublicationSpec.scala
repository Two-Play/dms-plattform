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

import java.time.{Instant, LocalDate, ZoneOffset}
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
    ): IO[DocumentError, List[MissingComputed]]                     = ZIO.succeed(Nil)
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

  /** @param requiredLater
    *   the class has a field required in Published and Withdrawn only, so that an approved version can lack
    *   it
    */
  private def fixture(
      zone: String = "Europe/Berlin",
      requiredLater: Boolean = false
  ): ZIO[Env, Any, Fixture] =
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
      fields  = Option
        .when(requiredLater)(FieldBinding(FieldKey.unsafe(LaterField), 1, Set(Published, Withdrawn)))
        .toList
      cls <- e.app.transact(tenant)(
        ZIO.when(requiredLater)(
          classes.createField(LaterField, FieldDraft("Legal basis", None, FieldType.Text()), admin)
        ) *> classes.createClass("policy", None, ClassDraft("Policy", None, fields), admin)
      )
      _ <- e.app.transact(tenant)(RetentionFixtures.assign(classes, cls.classId, "policy-10y"))
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

  /** Like [[scheduled]], as approved before DMS-147: the field required on publication is empty. Submission
    * refuses that now, so the value goes after approval, past the guard that forbids this to the application.
    */
  private def scheduledWithGap(f: Fixture, doc: DocumentId, validFrom: LocalDate) =
    val filled = values(validFrom) +
      (LaterField -> FieldEntry(MetadataValue.Text("Section 1"), ValueOrigin.Manual))
    for
      _ <- as(f.tenant)(_.newVersion(doc, ChangeKind.Major, Some("Content revised"), author))
      _ <- as(f.tenant)(_.updateDraft(doc, DraftChange(None, filled, None), author))
      a <- approveOpen(f, doc)
      _ <- schemaOwner {
        val _ = sql"set local session_replication_role = replica".update.run()
        sql"""update dms.document_version set field_values = field_values - $LaterField
              where id = ${a.id}""".update.run()
      }
    yield a

  private val LaterField = "legalBasis"

  /** Like [[valid]], with the field of `fixture(requiredLater = true)` filled in for version 1.0. */
  private def validWithLaterField(f: Fixture): ZIO[Env, Any, (DocumentId, DocumentVersion)] =
    val filled = values(LocalDate.of(2026, 1, 1)) +
      (LaterField -> FieldEntry(MetadataValue.Text("Section 1"), ValueOrigin.Manual))
    for
      _       <- TestClock.setTime(Instant.parse("2026-01-02T12:00:00Z"))
      created <- as(f.tenant)(_.create(NewDocument(f.templateId, 1, "Code of conduct", filled), author))
      (doc, v1) = created
      _         <- approveOpen(f, doc.id)
      published <- as(f.tenant)(_.changeStatus(v1.id, Publish, approver))
    yield (doc.id, published)

  /** Sets the publication jobs of a version to what the queue leaves after the last failed attempt. */
  private def markDead(version: UUID, error: String = "[invalid] a required field is empty") =
    schemaOwner(
      sql"""update dms.job set status = 'dead', attempts = max_attempts, last_error = $error,
                               finished_at = now(), locked_by = null, locked_until = null
            where kind = ${PublicationJobs.PublishKind} and payload ->> 'versionId' = ${version.toString}""".update
        .run()
    )

  /** A retry as the API does it: the lock of the version first, then the job. */
  private def retry(s: DocumentService, version: UUID) =
    s.lockForPublicationRetry(version).tap { v =>
      ZIO.when(v.status == Approved)(
        Db.sql(
          sql"""select dms.job_retry(${PublicationJobs.PublishKind}, idempotency_key) from dms.job
                where kind = ${PublicationJobs.PublishKind} and payload ->> 'versionId' = ${version.toString}"""
            .query[Option[String]]
            .run()
            .toList
        ).mapError(DocumentError.Database(_))
      )
    }

  private def refusal[A](result: Either[DocumentError | DbError, A]) = result.left.toOption.collect {
    case DocumentError.WithdrawalRefused(r, _) => r
  }

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

  private val ownerUser = UserId("owner-1")

  /** 2027-01-01 00:00 in Berlin, when a version valid from that date becomes due. */
  private val due           = Instant.parse("2026-12-31T23:00:00Z")
  private val validFrom     = LocalDate.of(2027, 1, 1)
  private val justBefore    = Clock.ClockJava(java.time.Clock.fixed(due.minusMillis(1), ZoneOffset.UTC))
  private val justAtOrAfter = Clock.ClockJava(java.time.Clock.fixed(due, ZoneOffset.UTC))

  private def withdraw(
      f: Fixture,
      version: UUID,
      reason: String = "Approved by mistake",
      by: UserId = ownerUser
  ) =
    as(f.tenant)(_.withdrawApproved(version, reason, Set(approver), by))

  private def schemaOwner[A](query: DbTx ?=> A): ZIO[Env, DbError, A] =
    ZIO.serviceWithZIO[Env](_.owner.transactSystem(Db.sql(query)))

  private def jobStates(version: UUID) =
    schemaOwner(
      sql"""select status, coalesce(last_error, '') from dms.job
            where kind = ${PublicationJobs.PublishKind} and payload ->> 'versionId' = ${version.toString}"""
        .query[(String, String)]
        .run()
        .toList
    )

  private def outbox(doc: DocumentId, eventType: String) =
    schemaOwner(
      sql"""select payload::text from dms.outbox_event
            where aggregate_id = ${doc.value} and event_type = $eventType order by id"""
        .query[String]
        .run()
        .toList
    )

  private def audited(doc: DocumentId, action: String) =
    ZIO.serviceWithZIO[Env](
      _.audit.get.map(_.filter(e => e.action == action && e.target.id.contains(doc.value)))
    )

  /** Waits until a statement waits for a lock: the other side of a race got to its row lock. */
  private val someoneWaitsForALock =
    schemaOwner(
      sql"select count(*) from pg_stat_activity where wait_event_type = 'Lock' and state = 'active'"
        .query[Long]
        .run()
        .head
    ).repeatUntil(_ >= 1L)

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
    test("a withdrawn version is never published; the valid version stays; frozen data stays as it was") {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        v2     <- scheduled(f, doc, validFrom)
        runner <- jobs()
        _      <- TestClock.setTime(Instant.parse("2026-12-30T10:00:00Z"))
        _      <- runner.enqueue(f.tenant)
        queued <- jobStates(v2.id)
        // Stands for a publication job a worker claimed just before the withdrawal.
        withdrawn    <- withdraw(f, v2.id, "  Approved by mistake  ")
        cancelled    <- jobStates(v2.id)
        again        <- runner.enqueue(f.tenant)
        _            <- TestClock.setTime(due.plusSeconds(5))
        _            <- runner.handlers(PublicationJobs.PublishKind)(claimed(f.tenant, v2.id))
        direct       <- as(f.tenant)(_.publishScheduled(v2.id))
        forced       <- as(f.tenant)(_.changeStatus(v2.id, Publish, approver)).either
        after        <- as(f.tenant)(_.get(doc))
        scheduledNow <- as(f.tenant)(_.scheduledPublications)
        current      <- as(f.tenant)(_.currentlyValid)
        validOn      <- as(f.tenant)(_.validAt(Instant.parse("2027-06-01T12:00:00Z")))
        action       <- audited(doc, "document.withdrawn-before-valid")
        published    <- audited(doc, "document.published")
        notices      <- outbox(doc, "document.approved-version-withdrawn")
        changes      <- outbox(doc, "document.status-changed")
        stored = after.versions.find(_.id == v2.id).get
        old    = after.versions.find(_.id == v1.id).get
      yield assertTrue(
        queued.map(_._1) == List("queued"),
        cancelled.size == 1,
        cancelled.head._1 == "cancelled",
        cancelled.head._2.contains("withdrawn"),
        again == 0,
        direct.isEmpty,
        forced.isLeft,
        withdrawn.status == Withdrawn,
        withdrawn.withdrawal.map(w => (w.by, w.reason)) == Some((ownerUser, "Approved by mistake")),
        after.versions.map(v => (v.label, v.status)) == List(("1.0", Published), ("2.0", Withdrawn)),
        stored.withdrawal.map(_.reason) == Some("Approved by mistake"),
        stored.withdrawal.map(_.at) == Some(Instant.parse("2026-12-30T10:00:00Z")),
        stored.publishedAt.isEmpty,
        stored.rendition == v2.rendition,
        stored.metadataSnapshot == v2.metadataSnapshot,
        stored.releasedBy == v2.releasedBy,
        stored.content == v2.content,
        old.supersededBy.isEmpty,
        old.withdrawal.isEmpty,
        after.successor(old).isEmpty,
        after.publicationDue(stored).isEmpty,
        scheduledNow.isEmpty,
        current.map(_.versionId) == List(v1.id),
        validOn.map(_.versionId) == List(v1.id),
        action.size == 1,
        action.head.reason.contains("Approved by mistake"),
        action.head.actor.id == ownerUser.value,
        action.head.actor.kind == ActorKind.Person,
        action.head.target.version.contains("2.0"),
        !published.exists(_.target.version.contains("2.0")),
        notices.size == 1,
        notices.head.contains("""recipients": ["approver-1"]"""),
        notices.head.contains("Approved by mistake"),
        changes.count(c =>
          c.contains("""status": "Withdrawn"""") && c.contains("""previousStatus": "Approved"""")
        ) == 1
      )
    },
    test(
      "the retention job runs for the withdrawn version; no acknowledgement or review ever exists for it"
    ) {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        v2   <- scheduled(f, doc, validFrom)
        _    <- TestClock.setTime(Instant.parse("2026-12-30T10:00:00Z"))
        _    <- withdraw(f, v2.id)
        keys <- schemaOwner(
          sql"""select idempotency_key from dms.job where kind = 'archive.object-retention'
                and payload ->> 'versionId' = ${v2.id.toString} order by id""".query[String].run().toList
        )
        audience <- schemaOwner(
          sql"""select count(*) from dms.version_acknowledgement_audience where version_id = ${v2.id}"""
            .query[Long]
            .run()
            .head
        )
        reviewDue <- schemaOwner(
          sql"select review_due_on is null, withdrawn_at is not null from dms.document_version where id = ${v2.id}"
            .query[(Boolean, Boolean)]
            .run()
            .head
        )
        // The archive PDF stays what it was: the application cannot change the reference, even after withdrawal.
        changed <- ZIO
          .serviceWithZIO[Env](
            _.app.transact(f.tenant)(
              Db.sql(
                sql"update dms.document_version set rendition_sha256 = ${"b" * 64} where id = ${v2.id}".update
                  .run()
              )
            )
          )
          .either
        attach <- as(f.tenant)(
          _.attachRendition(v2.id, Rendition("r/other.pdf", None, "c" * 64), admin)
        ).either
        kept <- as(f.tenant)(_.get(doc)).map(_.versions.find(_.id == v2.id).get)
      yield assertTrue(
        keys == List(s"object-retention:${v2.id}", s"object-retention:${v2.id}:Withdrawn"),
        audience == 0L,
        reviewDue == (true, true),
        changed.isLeft,
        attach.isLeft,
        kept.rendition == v2.rendition,
        // The valid version keeps its own state.
        v1.status == Published
      )
    },
    test("the reason is written once, and another tenant cannot cancel a publication job") {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        v2      <- scheduled(f, doc, validFrom)
        runner  <- jobs()
        _       <- TestClock.setTime(Instant.parse("2026-12-30T10:00:00Z"))
        _       <- runner.enqueue(f.tenant)
        e       <- ZIO.service[Env]
        other   <- fixture()
        foreign <- e.app.transact(other.tenant)(PublicationJobs.cancel(v2.id, "not yours"))
        queued  <- jobStates(v2.id)
        _       <- withdraw(f, v2.id)
        rewrite <- e.app
          .transact(f.tenant)(
            Db.sql(
              sql"""update dms.document_version set withdrawal_reason = ${"Another one"}
                    where id = ${v2.id}""".update.run()
            )
          )
          .either
        onValid <- e.app
          .transact(f.tenant)(
            Db.sql(
              sql"""update dms.document_version
                    set status = 'Withdrawn', status_changed_at = now(), status_changed_by = ${"x"},
                        withdrawn_by = ${"x"}, withdrawal_reason = ${"Not this way"}
                    where id = ${v1.id}""".update.run()
            )
          )
          .either
      yield assertTrue(foreign == 0, queued.map(_._1) == List("queued"), rewrite.isLeft, onValid.isLeft)
    },
    test("a reason is required, and only the service's withdrawal can stop an approved version") {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, _) = created
        v2       <- scheduled(f, doc, validFrom)
        _        <- TestClock.setTime(Instant.parse("2026-12-30T10:00:00Z"))
        blank    <- withdraw(f, v2.id, "   ").either
        long     <- withdraw(f, v2.id, "x" * 4001).either
        noReason <- as(f.tenant)(_.changeStatus(v2.id, Withdraw, ownerUser)).either
        direct   <- ZIO
          .serviceWithZIO[Env](
            _.app.transact(f.tenant)(
              Db.sql(
                sql"""update dms.document_version set status = 'Withdrawn', status_changed_at = now(),
                      status_changed_by = ${"x"} where id = ${v2.id}""".update.run()
              )
            )
          )
          .either
        after <- as(f.tenant)(_.get(doc))
      yield assertTrue(
        blank.left.exists(_.isInstanceOf[DocumentError.Invalid]),
        long.left.exists(_.isInstanceOf[DocumentError.Invalid]),
        noReason.left.exists(_.isInstanceOf[DocumentError.Invalid]),
        direct.isLeft,
        after.versions.map(_.status) == List(Published, Approved)
      )
    },
    test("a version cannot be withdrawn from its valid-from date on, twice, or before it is approved") {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        v2        <- scheduled(f, doc, validFrom)
        _         <- TestClock.setTime(due)
        late      <- withdraw(f, v2.id).either
        still     <- as(f.tenant)(_.get(doc))
        _         <- as(f.tenant)(_.publishScheduled(v2.id))
        published <- withdraw(f, v2.id).either
        // The day before the date in the tenant's time zone is still early enough; twice is not.
        other <- fixture("Asia/Tokyo")
        again <- valid(other)
        (doc2, _) = again
        v3    <- scheduled(other, doc2, validFrom)
        _     <- TestClock.setTime(Instant.parse("2026-12-31T14:59:59Z"))
        first <- withdraw(other, v3.id)
        twice <- withdraw(other, v3.id).either
        draft <- as(other.tenant)(_.newVersion(doc2, ChangeKind.Major, Some("Next"), author))
        open  <- withdraw(other, draft.id).either
        none  <- withdraw(other, UUID.randomUUID()).either
      yield assertTrue(
        refusal(late).contains(WithdrawalRefusal.Due),
        still.versions.map(_.status) == List(Published, Approved),
        refusal(published).contains(WithdrawalRefusal.AlreadyPublished),
        first.status == Withdrawn,
        refusal(twice).contains(WithdrawalRefusal.AlreadyWithdrawn),
        refusal(open).contains(WithdrawalRefusal.NotApproved),
        none.left.exists(_.isInstanceOf[DocumentError.NotFound]),
        v1.status == Published
      )
    },
    test("a version that can never be published is withdrawn after its date, and the document goes on") {
      for
        f       <- fixture(requiredLater = true)
        created <- validWithLaterField(f)
        (doc, v1) = created
        // The field is required in Published and Withdrawn only and empty, as submission allowed before DMS-147.
        v2        <- scheduledWithGap(f, doc, validFrom)
        runner    <- jobs()
        _         <- runner.enqueue(f.tenant)
        _         <- TestClock.setTime(due)
        failed    <- runner.handlers(PublicationJobs.PublishKind)(claimed(f.tenant, v2.id)).flip
        retrying  <- withdraw(f, v2.id).either
        blocked   <- as(f.tenant)(_.newVersion(doc, ChangeKind.Major, Some("Too early"), author)).either
        _         <- markDead(v2.id, failed)
        noReason  <- withdraw(f, v2.id, reason = "   ").either
        withdrawn <- withdraw(f, v2.id, reason = "A required field is empty")
        twice     <- withdraw(f, v2.id).either
        stillDead <- jobStates(v2.id)
        next      <- as(f.tenant)(_.newVersion(doc, ChangeKind.Major, Some("Field filled in"), author))
        after     <- as(f.tenant)(_.get(doc))
        trail     <- audited(doc, "document.withdrawn-before-valid")
        notices   <- outbox(doc, "document.approved-version-withdrawn")
      yield assertTrue(
        failed.startsWith("[invalid] "),
        refusal(retrying).contains(WithdrawalRefusal.Due),
        blocked.left.exists(_.isInstanceOf[DocumentError.Conflict]),
        noReason.left.exists(_.isInstanceOf[DocumentError.Invalid]),
        withdrawn.status == Withdrawn,
        withdrawn.withdrawal.map(_.reason).contains("A required field is empty"),
        refusal(twice).contains(WithdrawalRefusal.AlreadyWithdrawn),
        stillDead.map(_._1) == List("dead"),
        next.label == "3.0-draft.1",
        after.versions.map(v => (v.id, v.status)) == List(
          (v1.id, Published),
          (v2.id, Withdrawn),
          (next.id, Draft)
        ),
        trail.size == 1,
        trail.head.details.value.contains("\"publicationFailed\":true"),
        notices.size == 1,
        notices.head.contains("\"publicationFailed\": true")
      )
    },
    test("a field required in Withdrawn does not stop withdrawing a valid version (DMS-148)") {
      for
        f       <- fixture(requiredLater = true)
        created <- validWithLaterField(f)
        (_, v1) = created
        _ <- schemaOwner {
          val _ = sql"set local session_replication_role = replica".update.run()
          sql"""update dms.document_version set field_values = field_values - $LaterField
                where id = ${v1.id}""".update.run()
        }
        withdrawn <- as(f.tenant)(_.changeStatus(v1.id, Withdraw, approver))
      yield assertTrue(withdrawn.status == Withdrawn)
    },
    test("the date rule stays for every version whose newest publication job is not dead") {
      def setJob(version: UUID, status: String, attempts: Int) =
        schemaOwner(
          sql"""update dms.job set status = $status, attempts = $attempts, last_error = ${"[conflict] busy"},
                                   locked_by = ${Option.when(status == "running")("worker")},
                                   locked_until = ${Option.when(status == "running")(
              java.time.OffsetDateTime.parse("2030-01-01T00:00:00Z")
            )}
                where kind = ${PublicationJobs.PublishKind}
                  and payload ->> 'versionId' = ${version.toString}""".update.run()
        )
      for
        f       <- fixture()
        created <- valid(f)
        (doc, _) = created
        v2      <- scheduled(f, doc, validFrom)
        runner  <- jobs()
        _       <- TestClock.setTime(due)
        noJob   <- withdraw(f, v2.id).either
        _       <- runner.enqueue(f.tenant)
        queued  <- withdraw(f, v2.id).either
        _       <- setJob(v2.id, "queued", 1)
        backoff <- withdraw(f, v2.id).either
        _       <- setJob(v2.id, "running", 1)
        running <- withdraw(f, v2.id).either
        // A time zone change adds a job for the new moment: a dead job of the old moment does not count.
        _ <- markDead(v2.id)
        _ <- schemaOwner(
          sql"update dms.tenant set time_zone = 'Asia/Tokyo' where id = ${f.tenant.value}".update.run()
        )
        _      <- runner.enqueue(f.tenant)
        jobs   <- jobStates(v2.id)
        newest <- withdraw(f, v2.id).either
        still  <- as(f.tenant)(_.get(doc))
      yield assertTrue(
        refusal(noJob).contains(WithdrawalRefusal.Due),
        refusal(queued).contains(WithdrawalRefusal.Due),
        refusal(backoff).contains(WithdrawalRefusal.Due),
        refusal(running).contains(WithdrawalRefusal.Due),
        jobs.map(_._1).toSet == Set("dead", "queued"),
        refusal(newest).contains(WithdrawalRefusal.Due),
        still.versions.map(_.status) == List(Published, Approved)
      )
    },
    test(
      "race: the retry of a dead publication holds the version, the withdrawal waits and is told it lost"
    ) {
      for
        e       <- ZIO.service[Env]
        s       <- service(DocumentSettings.default)
        runner  <- jobs()
        f       <- fixture()
        created <- valid(f)
        (doc, _) = created
        v2      <- scheduled(f, doc, validFrom)
        _       <- runner.enqueue(f.tenant)
        _       <- markDead(v2.id)
        locked  <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        retried <- ZIO
          .withClock(justAtOrAfter)(
            e.app.transact(f.tenant)(retry(s, v2.id) <* locked.succeed(()) <* release.await)
          )
          .fork
        _          <- locked.await
        withdrawal <- ZIO.withClock(justAtOrAfter)(withdraw(f, v2.id)).either.fork
        _          <- someoneWaitsForALock
        _          <- release.succeed(())
        _          <- retried.join
        refused    <- withdrawal.join
        after      <- as(f.tenant)(_.get(doc))
        job        <- jobStates(v2.id)
      yield assertTrue(
        refusal(refused).contains(WithdrawalRefusal.Due),
        after.versions.map(_.status) == List(Published, Approved),
        job.map(_._1) == List("queued")
      )
    },
    test(
      "race: the withdrawal of a dead publication holds the version, the retry waits and finds it withdrawn"
    ) {
      for
        e       <- ZIO.service[Env]
        s       <- service(DocumentSettings.default)
        runner  <- jobs()
        f       <- fixture()
        created <- valid(f)
        (doc, _) = created
        v2         <- scheduled(f, doc, validFrom)
        _          <- runner.enqueue(f.tenant)
        _          <- markDead(v2.id)
        locked     <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        withdrawal <- ZIO
          .withClock(justAtOrAfter)(
            e.app.transact(f.tenant)(
              s.withdrawApproved(v2.id, "Cannot be published", Set(approver), ownerUser) <*
                locked.succeed(()) <* release.await
            )
          )
          .fork
        _       <- locked.await
        retried <- ZIO.withClock(justAtOrAfter)(e.app.transact(f.tenant)(retry(s, v2.id))).fork
        _       <- someoneWaitsForALock
        _       <- release.succeed(())
        _       <- withdrawal.join
        found   <- retried.join
        job     <- jobStates(v2.id)
      yield assertTrue(found.status == Withdrawn, job.map(_._1) == List("dead"))
    },
    test("race: the publication holds the version, the withdrawal waits and is told it lost") {
      for
        e       <- ZIO.service[Env]
        s       <- service(DocumentSettings.default)
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        v2      <- scheduled(f, doc, validFrom)
        locked  <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
        job     <- ZIO
          .withClock(justAtOrAfter)(
            e.app.transact(f.tenant)(s.publishScheduled(v2.id) <* locked.succeed(()) <* release.await)
          )
          .fork
        _          <- locked.await
        withdrawal <- ZIO.withClock(justBefore)(withdraw(f, v2.id)).either.fork
        _          <- someoneWaitsForALock
        _          <- release.succeed(())
        published  <- job.join
        refused    <- withdrawal.join
        after      <- as(f.tenant)(_.get(doc))
        action     <- audited(doc, "document.withdrawn-before-valid")
        notices    <- outbox(doc, "document.approved-version-withdrawn")
      yield assertTrue(
        published.exists(_.status == Published),
        refused.left.exists {
          case DocumentError.WithdrawalRefused(WithdrawalRefusal.AlreadyPublished, m) => m.contains("2.0")
          case _                                                                      => false
        },
        after.versions.map(v => (v.id, v.status)) == List((v1.id, Superseded), (v2.id, Published)),
        after.versions.forall(_.withdrawal.isEmpty),
        action.isEmpty,
        notices.isEmpty
      )
    },
    test("race: the withdrawal holds the version, the job that was claimed already waits and does nothing") {
      for
        e       <- ZIO.service[Env]
        s       <- service(DocumentSettings.default)
        runner  <- jobs()
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        v2         <- scheduled(f, doc, validFrom)
        locked     <- Promise.make[Nothing, Unit]
        release    <- Promise.make[Nothing, Unit]
        withdrawal <- ZIO
          .withClock(justBefore)(
            e.app.transact(f.tenant)(
              s.withdrawApproved(v2.id, "Approved by mistake", Set(approver), ownerUser) <*
                locked.succeed(()) <* release.await
            )
          )
          .fork
        _   <- locked.await
        job <- ZIO
          .withClock(justAtOrAfter)(runner.handlers(PublicationJobs.PublishKind)(claimed(f.tenant, v2.id)))
          .either
          .fork
        _         <- someoneWaitsForALock
        _         <- release.succeed(())
        withdrawn <- withdrawal.join
        finished  <- job.join
        after     <- as(f.tenant)(_.get(doc))
        published <- audited(doc, "document.published")
      yield assertTrue(
        withdrawn.status == Withdrawn,
        finished.isRight,
        after.versions.map(v => (v.id, v.status)) == List((v1.id, Published), (v2.id, Withdrawn)),
        after.versions.find(_.id == v2.id).exists(_.publishedAt.isEmpty),
        !published.exists(_.target.version.contains("2.0"))
      )
    },
    test("race: run at the same moment, exactly one of withdrawal and publication changes the version") {
      def round(f: Fixture) =
        for
          e       <- ZIO.service[Env]
          s       <- service(DocumentSettings.default)
          created <- valid(f)
          (doc, v1) = created
          v2     <- scheduled(f, doc, validFrom)
          result <- ZIO.withClock(justBefore)(withdraw(f, v2.id)).either <&>
            ZIO.withClock(justAtOrAfter)(e.app.transact(f.tenant)(s.publishScheduled(v2.id)))
          (withdrawal, published) = result
          after  <- as(f.tenant)(_.get(doc))
          action <- audited(doc, "document.withdrawn-before-valid")
        yield
          val statuses = after.versions.map(_.status)
          val stored   = after.versions.find(_.id == v2.id).get
          if withdrawal.isRight then
            published.isEmpty && statuses == List(Published, Withdrawn) && stored.publishedAt.isEmpty &&
            stored.withdrawal.isDefined && action.size == 1 && after.versions.head.id == v1.id
          else
            published.isDefined && statuses == List(Superseded, Published) && stored.publishedAt.isDefined &&
            stored.withdrawal.isEmpty && action.isEmpty && withdrawal.left.exists {
              case DocumentError.WithdrawalRefused(WithdrawalRefusal.AlreadyPublished, _) => true
              case _                                                                      => false
            }
      for
        f      <- fixture()
        rounds <- ZIO.foreach((1 to 10).toList)(_ => round(f))
      yield assertTrue(rounds.forall(identity))
    },
    test("the next version counts on, starts from the valid version and is compared with it") {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        v2      <- scheduled(f, doc, validFrom)
        _       <- TestClock.setTime(Instant.parse("2026-12-30T10:00:00Z"))
        _       <- withdraw(f, v2.id)
        minor   <- as(f.tenant)(_.newVersion(doc, ChangeKind.Minor, Some("Typo"), author))
        details <- as(f.tenant)(_.get(doc))
      yield assertTrue(
        minor.label == "2.1-draft.1",
        EditorialCheck.basis(minor, details.versions).map(_.id) == Some(v1.id)
      )
    },
    test("a first version approved by mistake leaves no valid version and can be corrected by a new one") {
      for
        f       <- fixture()
        _       <- TestClock.setTime(Instant.parse("2026-01-02T12:00:00Z"))
        created <- as(f.tenant)(
          _.create(NewDocument(f.templateId, 1, "Code of conduct", values(validFrom)), author)
        )
        (doc, v1) = created
        approved  <- approveOpen(f, doc.id)
        withdrawn <- withdraw(f, approved.id)
        current   <- as(f.tenant)(_.currentlyValid)
        noSummary <- as(f.tenant)(_.newVersion(doc.id, ChangeKind.Major, None, author)).either
        minor     <- as(f.tenant)(_.newVersion(doc.id, ChangeKind.Minor, Some("Corrected"), author)).either
        next      <- as(f.tenant)(_.newVersion(doc.id, ChangeKind.Major, Some("Corrected"), author))
      yield assertTrue(
        withdrawn.id == v1.id,
        !current.exists(_.document.id == doc.id),
        noSummary.isLeft,
        minor.isLeft,
        next.label == "2.0-draft.1",
        next.content == withdrawn.content,
        next.withdrawal.isEmpty
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
    },
    test("a failed publication job's error starts with a code its status and notices can explain (DMS-142)") {
      for
        f      <- fixture()
        runner <- jobs()
        failed <- runner.handlers(PublicationJobs.PublishKind)(claimed(f.tenant, UUID.randomUUID())).flip
      yield assertTrue(
        failed.startsWith("[not_found] "),
        PublicationJobs.jobError(DocumentError.Conflict("x")) == "[conflict] x",
        PublicationJobs.jobError(DocumentError.Invalid(List("x"))) == "[invalid] x",
        PublicationJobs.jobError(DocumentError.Lifecycle(LifecycleError.LegalHoldActive)) ==
          "[legal_hold] a legal hold is active",
        PublicationJobs
          .jobError(DocumentError.Lifecycle(LifecycleError.TransitionNotAllowed(Draft, Publish)))
          .startsWith("[conflict] ")
      )
    }
  ).provideShared(env) @@ TestAspect.sequential
