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
import javax.sql.DataSource

/** The validity history (US-09.1, DMS-53): which version of each document and scope was in force at a moment,
  * against a Testcontainers database. Time comes from the test clock, so versions are published at chosen
  * instants.
  */
object ValidityHistorySpec extends ZIOSpecDefault:

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

  private val zone = java.time.ZoneId.of("Europe/Berlin")

  private def values(validFrom: LocalDate, scope: List[String]): Map[String, FieldEntry] =
    (Map(
      "owner"                -> MetadataValue.PersonRef("owner-1"),
      "organizationalUnit"   -> MetadataValue.Text("Compliance"),
      "validFrom"            -> MetadataValue.Date(validFrom),
      "confidentiality"      -> MetadataValue.Selection("Internal"),
      "language"             -> MetadataValue.Text("de"),
      "retentionRule"        -> MetadataValue.Text("policy-10y"),
      "containsPersonalData" -> MetadataValue.YesNo(false)
    ) ++ Option.when(scope.nonEmpty)("scope" -> MetadataValue.Many(scope.map(MetadataValue.Text(_)))))
      .map((k, v) => k -> FieldEntry(v, ValueOrigin.Manual))

  /** Moves the open version of a document through review to approval. */
  private def approveOpen(f: Fixture, doc: DocumentId) =
    for
      v <- as(f.tenant)(_.submit(doc, author))
      _ <- as(f.tenant)(_.changeStatus(v.id, ReviewPassed, UserId("reviewer-1")))
      _ <- as(f.tenant)(_.attachRendition(v.id, Rendition(s"r/${v.id}.pdf", None, "a" * 64), author))
      a <- as(f.tenant)(_.changeStatus(v.id, Approve, approver))
    yield a

  /** A document whose version 1.0 (valid from `validFrom`) is published at `publishedAt`. */
  private def published(
      f: Fixture,
      title: String,
      validFrom: LocalDate,
      publishedAt: Instant,
      scope: List[String] = Nil
  ): ZIO[Env, Any, (DocumentId, DocumentVersion)] =
    for
      _       <- TestClock.setTime(publishedAt.minusSeconds(3600))
      created <- as(f.tenant)(_.create(NewDocument(f.templateId, 1, title, values(validFrom, scope)), author))
      (doc, v1) = created
      _ <- approveOpen(f, doc.id)
      _ <- TestClock.setTime(publishedAt)
      p <- as(f.tenant)(_.changeStatus(v1.id, Publish, approver))
    yield (doc.id, p)

  /** The next version of `doc`, approved with `validFrom` (and `scope`) at `approvedAt`. */
  private def approvedNext(
      f: Fixture,
      doc: DocumentId,
      validFrom: LocalDate,
      approvedAt: Instant,
      scope: List[String] = Nil
  ) =
    for
      _ <- TestClock.setTime(approvedAt)
      _ <- as(f.tenant)(_.newVersion(doc, ChangeKind.Major, Some("Content revised"), author))
      _ <- as(f.tenant)(_.updateDraft(doc, DraftChange(None, values(validFrom, scope), None), author))
      a <- approveOpen(f, doc)
    yield a

  private def validOn(f: Fixture, date: LocalDate, classKey: Option[String] = None) =
    as(f.tenant)(_.validAt(Validity.endOfDay(date, zone), classKey))

  private def labels(vs: List[ValidVersion]) = vs.map(v => (v.document.title, v.label, v.scope))

  def spec = suite("Validity history")(
    test("per document the version in force on the date, across a scheduled publication") {
      for
        f       <- fixture()
        created <- published(
          f,
          "Code of conduct",
          LocalDate.of(2026, 1, 1),
          Instant.parse("2026-01-02T12:00:00Z")
        )
        (doc, v1) = created
        v2 <- approvedNext(f, doc, LocalDate.of(2027, 1, 1), Instant.parse("2026-06-01T10:00:00Z"))
        // Before the switch: the approved version is not in force yet, not even for its own date.
        _     <- TestClock.setTime(Instant.parse("2026-12-30T10:00:00Z"))
        early <- validOn(f, LocalDate.of(2027, 1, 1))
        // The publication job runs five seconds after 00:00 Berlin time.
        _        <- TestClock.setTime(Instant.parse("2026-12-31T23:00:05Z"))
        switched <- as(f.tenant)(_.publishScheduled(v2.id))
        before   <- validOn(f, LocalDate.of(2026, 1, 1))
        first    <- validOn(f, LocalDate.of(2026, 1, 2))
        lastDay  <- validOn(f, LocalDate.of(2026, 12, 31))
        newDay   <- validOn(f, LocalDate.of(2027, 1, 1))
        later    <- validOn(f, LocalDate.of(2030, 5, 17))
        // Exact moments around the switch: the old version applies until the new one was published.
        midnight <- as(f.tenant)(_.validAt(Instant.parse("2026-12-31T23:00:01Z")))
        after    <- as(f.tenant)(_.validAt(Instant.parse("2026-12-31T23:00:05Z")))
      yield assertTrue(
        early.map(_.versionId) == List(v1.id),
        switched.map(_.status) == Some(Published),
        before.isEmpty,
        first.map(_.versionId) == List(v1.id),
        // Not retroactive: 1.0 was approved after its valid-from date and applies from its publication.
        first.head.period == ValidityPeriod(
          Instant.parse("2026-01-02T12:00:00Z"),
          Some(Instant.parse("2026-12-31T23:00:05Z"))
        ),
        first.head.status == Superseded,
        first.head.releasedBy == Some(approver),
        lastDay.map(_.versionId) == List(v1.id),
        newDay.map(_.versionId) == List(v2.id),
        newDay.head.period == ValidityPeriod(Instant.parse("2026-12-31T23:00:05Z"), None),
        newDay.head.label == "2.0",
        newDay.head.validFrom == Some(LocalDate.of(2027, 1, 1)),
        later.map(_.versionId) == List(v2.id),
        midnight.map(_.versionId) == List(v1.id),
        after.map(_.versionId) == List(v2.id)
      )
    },
    test("a version published on time is in force from 00:00 of its valid-from date") {
      for
        f       <- fixture()
        created <- published(
          f,
          "Travel policy",
          LocalDate.of(2026, 1, 1),
          Instant.parse("2026-02-01T09:00:00Z")
        )
        (doc, v1) = created
        v2 <- approvedNext(f, doc, LocalDate.of(2026, 4, 1), Instant.parse("2026-03-10T09:00:00Z"))
        // The job publishes it at 00:00 Berlin time (summer time).
        _    <- TestClock.setTime(Instant.parse("2026-03-31T22:00:00Z"))
        _    <- as(f.tenant)(_.publishScheduled(v2.id))
        on   <- validOn(f, LocalDate.of(2026, 3, 31))
        next <- validOn(f, LocalDate.of(2026, 4, 1))
      yield assertTrue(
        on.map(_.versionId) == List(v1.id),
        on.head.period.until == Some(Instant.parse("2026-03-31T22:00:00Z")),
        next.map(_.versionId) == List(v2.id),
        next.head.period.from == Instant.parse("2026-03-31T22:00:00Z")
      )
    },
    test("each validity scope has its own version; documents are listed by title") {
      for
        f   <- fixture()
        doc <- published(
          f,
          "Working hours",
          LocalDate.of(2026, 1, 1),
          Instant.parse("2026-01-05T08:00:00Z"),
          List("DE")
        ).map(_._1)
        other <- published(f, "Anti-bribery", LocalDate.of(2026, 1, 1), Instant.parse("2026-01-06T08:00:00Z"))
        at    <- approvedNext(
          f,
          doc,
          LocalDate.of(2026, 2, 1),
          Instant.parse("2026-01-20T08:00:00Z"),
          List("AT")
        )
        _   <- TestClock.setTime(Instant.parse("2026-01-31T23:00:00Z"))
        _   <- as(f.tenant)(_.publishScheduled(at.id))
        jan <- validOn(f, LocalDate.of(2026, 1, 15))
        feb <- validOn(f, LocalDate.of(2026, 2, 15))
      yield assertTrue(
        labels(jan) == List(("Anti-bribery", "1.0", ""), ("Working hours", "1.0", "DE")),
        labels(feb) == List(
          ("Anti-bribery", "1.0", ""),
          ("Working hours", "2.0", "AT"),
          ("Working hours", "1.0", "DE")
        ),
        feb.forall(_.status == Published),
        other._2.status == Published
      )
    },
    test("superseded and archived versions stay in the history; the class filter covers subclasses") {
      for
        f       <- fixture()
        created <- published(
          f,
          "Data protection",
          LocalDate.of(2026, 1, 1),
          Instant.parse("2026-01-02T08:00:00Z")
        )
        (doc, v1) = created
        v2       <- approvedNext(f, doc, LocalDate.of(2026, 7, 1), Instant.parse("2026-05-01T08:00:00Z"))
        _        <- TestClock.setTime(Instant.parse("2026-06-30T22:00:01Z"))
        _        <- as(f.tenant)(_.publishScheduled(v2.id))
        archived <- as(f.tenant)(_.changeStatus(v1.id, Archive, UserId("records-1")))
        june     <- validOn(f, LocalDate.of(2026, 6, 15))
        july     <- validOn(f, LocalDate.of(2026, 7, 15))
        policy   <- validOn(f, LocalDate.of(2026, 6, 15), Some("policy"))
        contract <- validOn(f, LocalDate.of(2026, 6, 15), Some("contract"))
      yield assertTrue(
        archived.status == Archived,
        june.map(v => (v.versionId, v.status)) == List((v1.id, Archived)),
        june.head.period.until == Some(Instant.parse("2026-06-30T22:00:01Z")),
        june.head.classPath == List("policy"),
        july.map(_.versionId) == List(v2.id),
        policy.map(_.versionId) == List(v1.id),
        contract.isEmpty
      )
    },
    test("the end of a date is read in the tenant's time zone") {
      val newYork = java.time.ZoneId.of("America/New_York")
      for
        f <- fixture("America/New_York")
        // Published at 03:00 UTC on 2 March, which is still 1 March in New York.
        created <- published(
          f,
          "Safety rules",
          LocalDate.of(2026, 3, 1),
          Instant.parse("2026-03-02T03:00:00Z")
        )
        (_, v1) = created
        march1   <- as(f.tenant)(_.validAt(Validity.endOfDay(LocalDate.of(2026, 3, 1), newYork)))
        inBerlin <- as(f.tenant)(_.validAt(Validity.endOfDay(LocalDate.of(2026, 3, 1), zone)))
      yield assertTrue(
        march1.map(_.versionId) == List(v1.id),
        inBerlin.isEmpty,
        Validity.endOfDay(LocalDate.of(2026, 3, 1), newYork) == Instant.parse("2026-03-02T04:59:59.999999Z")
      )
    }
  ).provideShared(env) @@ TestAspect.sequential
