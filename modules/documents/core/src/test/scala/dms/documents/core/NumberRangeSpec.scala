package dms.documents.core

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.audit.*
import dms.db.{Db, DbConfig, DbError, Jsonb, Migrations}
import dms.documents.*
import dms.documents.LifecycleEvent.*
import dms.documents.metadata.*
import dms.kernel.{DocumentId, TemplateId, TenantId, UserId}
import dms.templates.*
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.time.{Instant, LocalDate}
import java.util.UUID
import javax.sql.DataSource

/** Number ranges and document numbers (DMS-113, ADR 0040) against a Testcontainers database: automatic
  * numbers on creation (consecutive and distinct under concurrency, no gap after a rollback, yearly reset
  * with the test clock), the frozen number across versions and manual assignment.
  */
object NumberRangeSpec extends ZIOSpecDefault:

  final case class Env(app: Db, owner: Db, audit: Ref[List[AuditEvent]])

  final case class Fixture(tenant: TenantId, classId: DocumentClassId, templateId: TemplateId)

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
      app    <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", poolSize = 8))
      owner  <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, poolSize = 2))
      events <- Ref.make(List.empty[AuditEvent])
    yield Env(Db(app), Db(owner), events)
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  private val admin    = UserId("admin-1")
  private val author   = UserId("author-1")
  private val approver = UserId("approver-1")
  private val records  = UserId("records-1")

  private val content = TemplateContent(
    List(
      Section(
        SectionId("terms"),
        Some("Terms"),
        locked = false,
        List(Block.Paragraph(List(Inline.Text("Text"))))
      )
    )
  )

  private def documents: ZIO[Env, Nothing, DocumentService] =
    ZIO.serviceWith[Env](e =>
      val audit = RecordingAuditTrail(e.audit)
      DocumentService(audit, DocumentClassService(audit), Templates)
    )

  private def ranges: ZIO[Env, Nothing, NumberRangeService] =
    ZIO.serviceWith[Env](e => NumberRangeService(RecordingAuditTrail(e.audit)))

  private def fixture: ZIO[Env, Any, Fixture] =
    val tenant     = TenantId.random()
    val templateId = TemplateId.random()
    for
      e <- ZIO.service[Env]
      _ <- e.owner.transactSystem(
        Db.sql(
          sql"insert into dms.tenant (id, name, time_zone) values (${tenant.value}, ${"Tenant"}, ${"Europe/Berlin"})".update
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
    yield Fixture(tenant, cls.classId, templateId)

  private def inTenant[A](tenant: TenantId)(f: => ZIO[Any, Any, A]): ZIO[Env, Any, A] =
    ZIO.serviceWithZIO[Env](_.app.transact(tenant)(f))

  /** A range assigned to the policy class. */
  private def numbered(
      f: Fixture,
      pattern: String,
      start: Long = 1,
      yearlyReset: Boolean = true
  ): ZIO[Env, Any, NumberRangeInfo] =
    for
      service <- ranges
      created <- inTenant(f.tenant)(
        service.create(NumberRangeDraft("policies", "Policies", pattern, start, yearlyReset), admin)
      )
      _ <- inTenant(f.tenant)(service.assign(f.classId.value, Some(created.range.id), admin))
    yield created

  private val reviewValues: Map[String, FieldEntry] =
    Map(
      "owner"                -> MetadataValue.PersonRef("owner-1"),
      "organizationalUnit"   -> MetadataValue.Text("Legal & Compliance"),
      "validFrom"            -> MetadataValue.Date(LocalDate.of(2026, 1, 1)),
      "confidentiality"      -> MetadataValue.Selection("Internal"),
      "language"             -> MetadataValue.Text("de"),
      "retentionRule"        -> MetadataValue.Text("policy-10y"),
      "containsPersonalData" -> MetadataValue.YesNo(false)
    ).map((k, v) => k -> FieldEntry(v, ValueOrigin.Manual))

  private def create(f: Fixture, values: Map[String, FieldEntry] = reviewValues) =
    documents.flatMap(s =>
      inTenant(f.tenant)(s.create(NewDocument(f.templateId, 1, "Policy", values), author))
    )

  private def numberOf(v: DocumentVersion): Option[String] =
    v.values.get(CoreFields.DocumentNumber).collect { case FieldEntry(MetadataValue.Text(n), _) => n }

  private def approveAndPublish(f: Fixture, doc: DocumentId) =
    for
      s <- documents
      v <- inTenant(f.tenant)(s.submit(doc, author))
      _ <- inTenant(f.tenant)(s.changeStatus(v.id, ReviewPassed, UserId("reviewer-1")))
      _ <- inTenant(f.tenant)(s.attachRendition(v.id, Rendition(s"r/${v.id}.pdf", None, "a" * 64), author))
      a <- inTenant(f.tenant)(s.changeStatus(v.id, Approve, approver))
      p <- inTenant(f.tenant)(s.changeStatus(v.id, Publish, approver))
    yield (a, p)

  /** Sets the creation time, which the database records (`now()`), e.g. to a past year; the guard that keeps
    * a document's identity is bypassed for this test setup only (superuser, replication role).
    */
  private def createdAt(doc: DocumentId, at: String): ZIO[Env, DbError, Unit] =
    val time = java.time.OffsetDateTime.parse(at)
    ZIO.serviceWithZIO[Env](
      _.owner.transactSystem(Db.sql {
        val _ = sql"set local session_replication_role = replica".update.run()
        val _ = sql"update dms.document set created_at = $time where id = ${doc.value}".update.run()
      })
    )

  private def counterRows(f: Fixture): ZIO[Env, DbError, List[(Int, Long)]] =
    ZIO.serviceWithZIO[Env](
      _.owner.transactSystem(
        Db.sql(
          sql"""select period, next_value from dms.number_range_counter where tenant_id = ${f.tenant.value}
                order by period""".query[(Int, Long)].run().toList
        )
      )
    )

  def spec = suite("Number ranges and document numbers")(
    test("documents of a class with a number range get consecutive numbers in the same transaction") {
      for
        _      <- TestClock.setTime(Instant.parse("2026-05-04T10:00:00Z"))
        f      <- fixture
        range  <- numbered(f, "POL-{YYYY}-{NNNN}")
        first  <- create(f)
        second <- create(f)
        s      <- documents
        loaded <- inTenant(f.tenant)(s.get(first._1.id))
        info   <- ranges.flatMap(r => inTenant(f.tenant)(r.get(range.range.id)))
      yield assertTrue(
        range.nextNumber == "POL-2026-0001",
        first._1.number.contains("POL-2026-0001"),
        numberOf(first._2).contains("POL-2026-0001"),
        first._2.values.get(CoreFields.DocumentNumber).exists(_.origin == ValueOrigin.Platform),
        second._1.number.contains("POL-2026-0002"),
        loaded.document.number.contains("POL-2026-0001"),
        info.nextNumber == "POL-2026-0003",
        info.classIds == List(f.classId.value)
      )
    },
    test("parallel creations get distinct consecutive numbers") {
      for
        f       <- fixture
        _       <- numbered(f, "P-{NNN}", yearlyReset = false)
        created <- ZIO.foreachPar((1 to 12).toList)(_ => create(f)).withParallelism(6)
        numbers = created.flatMap(_._1.number).sorted
      yield assertTrue(numbers == (1 to 12).map(i => f"P-$i%03d").toList)
    } @@ TestAspect.withLiveClock,
    test("a creation that rolls back leaves no gap: the counter rolls back with it") {
      for
        _      <- TestClock.setTime(Instant.parse("2026-05-04T10:00:00Z"))
        f      <- fixture
        _      <- numbered(f, "P-{NNNN}", yearlyReset = false)
        s      <- documents
        e      <- ZIO.service[Env]
        failed <- e.app
          .transact(f.tenant)(
            s.create(NewDocument(f.templateId, 1, "Policy", reviewValues), author)
              .flatMap(created => ZIO.fail(DocumentError.Conflict(s"rolled back ${created._1.number}")))
          )
          .either
        next     <- create(f)
        counters <- counterRows(f)
      yield assertTrue(
        failed.left.exists(_.toString.contains("P-0001")),
        next._1.number.contains("P-0001"),
        counters == List(0 -> 2L)
      )
    },
    test(
      "with a yearly reset the counter starts again at the start value at 00:00 in the tenant's time zone"
    ) {
      for
        f        <- fixture
        _        <- numbered(f, "POL-{YY}-{NNNN}", start = 5)
        _        <- TestClock.setTime(Instant.parse("2026-12-31T22:59:59Z"))
        late     <- create(f)
        later    <- create(f)
        _        <- TestClock.setTime(Instant.parse("2026-12-31T23:00:00Z"))
        newYear  <- create(f)
        counters <- counterRows(f)
      yield assertTrue(
        late._1.number.contains("POL-26-0005"),
        later._1.number.contains("POL-26-0006"),
        newYear._1.number.contains("POL-27-0005"),
        counters == List(2026 -> 7L, 2027 -> 6L)
      )
    },
    test("a higher start value raises the next number; numbers taken manually are skipped") {
      for
        _       <- TestClock.setTime(Instant.parse("2026-05-04T10:00:00Z"))
        f       <- fixture
        range   <- numbered(f, "P-{NNNN}", yearlyReset = false)
        first   <- create(f)
        service <- ranges
        changed <- inTenant(f.tenant)(
          service.update(range.range.id, NumberRangeDraft("", "Policies", "P-{NNNN}", 10, false), admin)
        )
        manual <- create(f)
        s      <- documents
        _      <- inTenant(f.tenant)(
          s.assignNumber(manual._1.id, "P-0011", "Taken over from the paper register", records)
        )
        next  <- create(f)
        lower <- inTenant(f.tenant)(
          service.update(range.range.id, NumberRangeDraft("", "Policies", "PX-{NNNN}", 1, false), admin)
        )
        renamed <- create(f)
      yield assertTrue(
        first._1.number.contains("P-0001"),
        changed.nextNumber == "P-0010",
        manual._1.number.contains("P-0010"),
        next._1.number.contains("P-0012"),
        lower.nextNumber == "PX-0013",
        renamed._1.number.contains("PX-0013")
      )
    },
    test("a pattern with the organizational unit needs one; it becomes part of the number") {
      for
        _       <- TestClock.setTime(Instant.parse("2026-05-04T10:00:00Z"))
        f       <- fixture
        _       <- numbered(f, "{OU}-{NNN}", yearlyReset = false)
        missing <- create(f, Map.empty).either
        created <- create(f)
      yield assertTrue(
        missing.left.exists {
          case DocumentError.InvalidValues(List(issue)) => issue.field == "organizationalUnit"
          case _                                        => false
        },
        created._1.number.contains("Legal-Compliance-001")
      )
    },
    test("the number is frozen with the approved version and taken over by the next version") {
      for
        _       <- TestClock.setTime(Instant.parse("2026-05-04T10:00:00Z"))
        f       <- fixture
        _       <- numbered(f, "POL-{YYYY}-{NNNN}")
        created <- create(f)
        doc = created._1.id
        published <- approveAndPublish(f, doc)
        s         <- documents
        next      <- inTenant(f.tenant)(s.newVersion(doc, ChangeKind.Major, None, author))
        replace   <- inTenant(f.tenant)(s.assignNumber(doc, "POL-OLD-1", "correction", records)).either
      yield assertTrue(
        published._1.metadataSnapshot.exists(
          _.values.get(CoreFields.DocumentNumber).contains(MetadataValue.Text("POL-2026-0001"))
        ),
        numberOf(next).contains("POL-2026-0001"),
        replace.left.exists {
          case DocumentError.Conflict(m) => m.contains("frozen")
          case _                         => false
        }
      )
    },
    test(
      "manual assignment: reason required, number unique in the tenant, replaced only while a draft; audited"
    ) {
      for
        _        <- TestClock.setTime(Instant.parse("2026-05-04T10:00:00Z"))
        f        <- fixture
        a        <- create(f)
        b        <- create(f)
        s        <- documents
        e        <- ZIO.service[Env]
        _        <- e.audit.set(Nil)
        noReason <- inTenant(f.tenant)(s.assignNumber(a._1.id, "VTR-1", "  ", records)).either
        invalid  <- inTenant(f.tenant)(s.assignNumber(a._1.id, "VTR/1", "reason", records)).either
        assigned <- inTenant(f.tenant)(s.assignNumber(a._1.id, "VTR-1", "from the old system", records))
        taken    <- inTenant(f.tenant)(s.assignNumber(b._1.id, "VTR-1", "duplicate", records)).either
        replaced <- inTenant(f.tenant)(s.assignNumber(a._1.id, "VTR-2", "typo", records))
        details  <- inTenant(f.tenant)(s.get(a._1.id))
        events   <- e.audit.get
        assignedEvents = events.filter(_.action == "document.number-assigned")
        afterSubmit <- inTenant(f.tenant)(s.submit(a._1.id, author)) *>
          inTenant(f.tenant)(s.assignNumber(a._1.id, "VTR-3", "again", records)).either
      yield assertTrue(
        a._1.number.isEmpty,
        noReason.left.exists(_.isInstanceOf[DocumentError.Invalid]),
        invalid.left.exists(_.isInstanceOf[DocumentError.Invalid]),
        assigned.number.contains("VTR-1"),
        taken.left.exists(_.isInstanceOf[DocumentError.Conflict]),
        replaced.number.contains("VTR-2"),
        details.document.number.contains("VTR-2"),
        details.open.flatMap(numberOf).contains("VTR-2"),
        details.open.exists(_.workingState == 3),
        assignedEvents.size == 2,
        assignedEvents.lastOption.exists(ev =>
          ev.details.value
            .fromJson[Json.Obj]
            .toOption
            .exists(d =>
              d.get("before").contains(Json.Str("VTR-1")) && d.get("after").contains(Json.Str("VTR-2")) &&
                d.get("reason").contains(Json.Str("typo"))
            )
        ),
        afterSubmit.left.exists(_.isInstanceOf[DocumentError.Conflict])
      )
    },
    test("a valid document without number gets one manually; later versions take it over") {
      for
        _       <- TestClock.setTime(Instant.parse("2026-05-04T10:00:00Z"))
        f       <- fixture
        created <- create(f)
        doc = created._1.id
        _        <- approveAndPublish(f, doc)
        s        <- documents
        assigned <- inTenant(f.tenant)(s.assignNumber(doc, "RL-007", "numbering introduced", records))
        next     <- inTenant(f.tenant)(s.newVersion(doc, ChangeKind.Major, None, author))
        found    <- inTenant(f.tenant)(s.findByNumber("RL-007"))
        unknown  <- inTenant(f.tenant)(s.findByNumber("RL-008"))
        other    <- fixture
        isolated <- inTenant(other.tenant)(s.findByNumber("RL-007"))
      yield assertTrue(
        assigned.number.contains("RL-007"),
        numberOf(next).contains("RL-007"),
        found.contains(doc),
        unknown.isEmpty,
        isolated.isEmpty
      )
    },
    test(
      "backfill numbers existing documents in creation order with the counter of their creation year, gap-free"
    ) {
      for
        f         <- fixture
        _         <- TestClock.setTime(Instant.parse("2025-03-01T10:00:00Z"))
        first     <- create(f)
        _         <- TestClock.setTime(Instant.parse("2025-07-01T10:00:00Z"))
        published <- create(
          f,
          reviewValues + ("validFrom" -> FieldEntry(
            MetadataValue.Date(LocalDate.of(2025, 1, 1)),
            ValueOrigin.Manual
          ))
        )
        _        <- approveAndPublish(f, published._1.id)
        _        <- TestClock.setTime(Instant.parse("2026-02-01T10:00:00Z"))
        third    <- create(f)
        inReview <- create(f)
        s        <- documents
        _        <- inTenant(f.tenant)(s.submit(inReview._1.id, author))
        _        <- createdAt(first._1.id, "2025-03-01T10:00:00Z")
        _        <- createdAt(published._1.id, "2025-07-01T10:00:00Z")
        _        <- createdAt(third._1.id, "2026-02-01T10:00:00Z")
        _        <- createdAt(inReview._1.id, "2026-02-02T10:00:00Z")
        _        <- TestClock.setTime(Instant.parse("2026-05-04T10:00:00Z"))
        range    <- numbered(f, "POL-{YYYY}-{NNNN}")
        onCreate <- create(f)
        service  <- ranges
        scope    <- inTenant(f.tenant)(service.backfillScope(f.classId.value))
        e        <- ZIO.service[Env]
        _        <- e.audit.set(Nil)
        outcomes <- ZIO.foreach(scope.documents)(id => inTenant(f.tenant)(s.numberExisting(id, admin)))
        events   <- e.audit.get
        again    <- inTenant(f.tenant)(service.backfillScope(f.classId.value))
        repeated <- inTenant(f.tenant)(s.numberExisting(first._1.id, admin))
        counters <- counterRows(f)
        firstDoc <- inTenant(f.tenant)(s.get(first._1.id))
        oldDoc   <- inTenant(f.tenant)(s.get(published._1.id))
      yield assertTrue(
        onCreate._1.number.contains("POL-2026-0001"),
        scope.range.id == range.range.id,
        scope.classIds == Set(f.classId.value),
        scope.documents == List(first._1.id, published._1.id, third._1.id, inReview._1.id),
        outcomes == List(
          BackfillOutcome.Numbered("POL-2025-0001"),
          BackfillOutcome.Numbered("POL-2025-0002"),
          BackfillOutcome.Numbered("POL-2026-0002"),
          BackfillOutcome.Skipped(BackfillSkip.InReview)
        ),
        // The skipped document took no counter value: no gap.
        counters == List(2025 -> 3L, 2026 -> 3L),
        again.documents == List(inReview._1.id),
        repeated == BackfillOutcome.Skipped(BackfillSkip.HasNumber),
        firstDoc.document.number.contains("POL-2025-0001"),
        firstDoc.open.flatMap(numberOf).contains("POL-2025-0001"),
        firstDoc.open.exists(_.workingState == 2),
        // The approved version keeps its frozen values; the document carries the number for later versions.
        oldDoc.document.number.contains("POL-2025-0002"),
        oldDoc.versions.forall(v => !v.metadataSnapshot.exists(_.values.contains(CoreFields.DocumentNumber))),
        events.count(_.action == "document.number-assigned") == 3,
        events
          .filter(_.action == "document.number-assigned")
          .forall(ev =>
            ev.actor.id == admin.value &&
              ev.details.value
                .fromJson[Json.Obj]
                .toOption
                .exists(d =>
                  d.get("backfill").contains(Json.Bool(true)) && d.get("manual").contains(Json.Bool(false)) &&
                    d.get("numberRange").contains(Json.Str("policies"))
                )
          )
      )
    },
    test("backfill covers subclasses that inherit the range, needs the unit for {OU} and a range at all") {
      for
        _      <- TestClock.setTime(Instant.parse("2026-05-04T10:00:00Z"))
        f      <- fixture
        e      <- ZIO.service[Env]
        noUnit <- create(f, reviewValues - "organizationalUnit")
        unit   <- create(f)
        none   <- ranges.flatMap(r => inTenant(f.tenant)(r.backfillScope(f.classId.value))).either
        classes = DocumentClassService(RecordingAuditTrail(e.audit))
        child <- inTenant(f.tenant)(
          classes.createClass("hr-policy", Some(f.classId), ClassDraft("HR policy", None, Nil), admin)
        )
        _        <- numbered(f, "{OU}-{NNN}", yearlyReset = false)
        service  <- ranges
        scope    <- inTenant(f.tenant)(service.backfillScope(f.classId.value))
        s        <- documents
        results  <- ZIO.foreach(scope.documents)(id => inTenant(f.tenant)(s.numberExisting(id, admin)))
        _        <- inTenant(f.tenant)(service.assign(child.classId.value, None, admin))
        narrowed <- inTenant(f.tenant)(service.backfillScope(f.classId.value))
      yield assertTrue(
        none.left.exists(_.isInstanceOf[MetadataError.Conflict]),
        scope.classIds == Set(f.classId.value, child.classId.value),
        scope.documents == List(noUnit._1.id, unit._1.id),
        results == List(
          BackfillOutcome.Skipped(BackfillSkip.UnitMissing),
          BackfillOutcome.Numbered("Legal-Compliance-001")
        ),
        narrowed.classIds == Set(f.classId.value)
      )
    },
    test("ranges are checked; classes inherit the range of their parent unless they override it") {
      for
        _       <- TestClock.setTime(Instant.parse("2026-05-04T10:00:00Z"))
        f       <- fixture
        service <- ranges
        invalid <- inTenant(f.tenant)(
          service.create(NumberRangeDraft("bad", "Bad", "X-{NNNN}", 1, true), admin)
        ).either
        range <- numbered(f, "POL-{YYYY}-{NNNN}")
        dup   <- inTenant(f.tenant)(
          service.create(NumberRangeDraft("policies", "Again", "Y-{NN}", 1, false), admin)
        ).either
        e <- ZIO.service[Env]
        classes = DocumentClassService(RecordingAuditTrail(e.audit))
        child <- inTenant(f.tenant)(
          classes.createClass("hr-policy", Some(f.classId), ClassDraft("HR policy", None, Nil), admin)
        )
        inherited <- inTenant(f.tenant)(service.classes)
        none      <- inTenant(f.tenant)(service.assign(child.classId.value, None, admin))
        back      <- inTenant(f.tenant)(service.unassign(child.classId.value, admin))
        preview   <- inTenant(f.tenant)(service.preview("VTR-{YYYY}-{NNNN}", 1, true))
        bad       <- inTenant(f.tenant)(service.preview("VTR-{X}", 1, false))
      yield assertTrue(
        invalid.left.exists {
          case MetadataError.Invalid(p) => p.exists(_.contains("yearly reset"))
          case _                        => false
        },
        dup.left.exists(_.isInstanceOf[MetadataError.Conflict]),
        inherited
          .find(_.classKey == "hr-policy")
          .exists(c =>
            c.own.isEmpty && c.effective.contains(range.range.id) && c.inheritedFrom.contains(f.classId.value)
          ),
        none.own.contains(None) && none.effective.isEmpty,
        back.own.isEmpty && back.effective.contains(range.range.id),
        preview == Right("VTR-2026-0001"),
        bad.isLeft
      )
    }
  ).provideShared(env) @@ TestAspect.sequential
