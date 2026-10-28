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

/** Periodic review and recertification (DMS-55) in the documents service, against a Testcontainers database.
  * Time comes from the test clock: the review check runs at chosen instants.
  */
object ReviewServiceSpec extends ZIOSpecDefault:

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

  private val yearly   = ReviewPolicy(Some(12), 60)
  private val owner    = UserId("owner-1")
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

  private def fixture(review: ReviewPolicy = yearly, zone: String = "Europe/Berlin"): ZIO[Env, Any, Fixture] =
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
        classes.createClass("policy", None, ClassDraft("Policy", None, Nil, review = Some(review)), admin)
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

  private def reviews: ZIO[Env, Nothing, ReviewService] =
    ZIO.serviceWith[Env] { e =>
      val audit   = RecordingAuditTrail(e.audit)
      val classes = DocumentClassService(audit)
      ReviewService(audit, DocumentService(audit, classes, Templates), classes)
    }

  private def review[A](tenant: TenantId)(f: ReviewService => IO[DocumentError, A]) =
    for
      e <- ZIO.service[Env]
      r <- reviews
      a <- e.app.transact(tenant)(f(r))
    yield a

  /** The review check as the job runs it. */
  private def check(tenant: TenantId): ZIO[Env, String, ReviewCheck] =
    for
      e <- ZIO.service[Env]
      r <- reviews
      c <- ReviewJobs(e.app, r, ReviewScheduleConfig()).check(tenant)
    yield c

  private def openTasks(tenant: TenantId) = review(tenant)(_.tasksOf(owner, open = true))

  private def dueOn(tenant: TenantId, version: UUID): ZIO[Env, DbError, Option[LocalDate]] =
    ZIO.serviceWithZIO[Env](_.app.transact(tenant)(Db.sql(ReviewStore.dueOn(version))))

  private def outbox(doc: DocumentId, eventType: String): ZIO[Env, DbError, List[String]] =
    ZIO.serviceWithZIO[Env](
      _.owner.transactSystem(
        Db.sql(
          sql"""select payload::text from dms.outbox_event
                where aggregate_id = ${doc.value} and event_type = $eventType order by id"""
            .query[String]
            .run()
            .toList
        )
      )
    )

  private def at(time: String) = TestClock.setTime(Instant.parse(time))

  private def auditOf(doc: DocumentId) =
    ZIO.serviceWithZIO[Env](_.audit.get).map(_.filter(_.target.id.contains(doc.value)))

  private def isInvalid[A](result: Either[DocumentError | DbError, A]) =
    result.left.exists(_.isInstanceOf[DocumentError.Invalid])

  private def isConflict[A](result: Either[DocumentError | DbError, A]) =
    result.left.exists(_.isInstanceOf[DocumentError.Conflict])

  def spec = suite("Periodic review")(
    test("publication sets the review date; the review task is created once at the lead time") {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        due       <- dueOn(f.tenant, v1.id)
        _         <- at("2026-11-01T22:59:59Z")
        early     <- check(f.tenant)
        _         <- at("2026-11-01T23:00:00Z")
        first     <- check(f.tenant)
        again     <- check(f.tenant)
        tasks     <- openTasks(f.tenant)
        others    <- review(f.tenant)(_.tasksOf(UserId("someone-else"), open = true))
        events    <- outbox(doc, "document.review-task-created")
        _         <- at("2026-12-31T22:59:59Z")
        beforeDue <- check(f.tenant)
        _         <- at("2026-12-31T23:00:00Z")
        onDue     <- check(f.tenant)
        dueAgain  <- check(f.tenant)
        after     <- as(f.tenant)(_.get(doc))
        audit     <- auditOf(doc)
      yield assertTrue(
        due == Some(LocalDate.of(2027, 1, 1)),
        audit.exists(e => e.action == "document.published" && e.details.value.contains("\"2027-01-01\"")),
        early == ReviewCheck(0, 0, 0),
        first == ReviewCheck(1, 0, 0),
        again == ReviewCheck(0, 0, 0),
        tasks.map(t => (t.versionId, t.dueOn, t.status)) ==
          List((v1.id, LocalDate.of(2027, 1, 1), ReviewStatus.Open)),
        tasks.head.createdAt == Instant.parse("2026-11-01T23:00:00Z"),
        others.isEmpty,
        events.size == 1,
        events.head.contains(tasks.head.id.toString),
        audit.count(_.action == "document.review-task-created") == 1,
        audit.exists(e =>
          e.action == "document.review-task-created" && e.actor.kind == ActorKind.Technical &&
            e.actor.id == ReviewService.ReviewActor.value && e.channel == Channel.Automation &&
            e.target.version.contains("1.0")
        ),
        beforeDue == ReviewCheck(0, 0, 0),
        onDue == ReviewCheck(0, 1, 0),
        dueAgain == ReviewCheck(0, 0, 0),
        // Still valid: overdue reviews are marked, never withdrawn automatically (business concept 6.2).
        after.versions.map(_.status) == List(ReviewDue),
        audit.exists(e =>
          e.action == "document.status-changed" && e.actor.id == ReviewService.ReviewActor.value &&
            e.actor.kind == ActorKind.Technical && e.details.value.contains("ReviewDueReached")
        )
      )
    },
    test("recertification sets a new review date without a new version and is audited") {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        _     <- at("2026-12-31T23:00:00Z")
        first <- check(f.tenant)
        task  <- openTasks(f.tenant).map(_.head)
        _     <- at("2027-01-05T09:00:00Z")
        past  <- review(f.tenant)(_.recertify(task.id, Some(LocalDate.of(2027, 1, 5)), None, owner)).either
        done  <- review(f.tenant)(_.recertify(task.id, None, Some("Still accurate"), owner))
        twice <- review(f.tenant)(_.recertify(task.id, None, None, owner)).either
        after <- as(f.tenant)(_.get(doc))
        due   <- dueOn(f.tenant, v1.id)
        open  <- openTasks(f.tenant)
        dec   <- review(f.tenant)(_.tasksOf(owner, open = false))
        audit <- auditOf(doc)
        _     <- at("2027-11-05T22:59:59Z")
        none  <- check(f.tenant)
        _     <- at("2027-11-05T23:00:00Z")
        next  <- check(f.tenant)
        again <- openTasks(f.tenant)
        recertified = audit.filter(_.action == "document.recertified")
      yield assertTrue(
        first == ReviewCheck(1, 1, 0),
        isInvalid(past),
        done.status == ReviewStatus.Recertified,
        done.nextDueOn == Some(LocalDate.of(2028, 1, 5)),
        done.decidedBy == Some(owner),
        done.comment == Some("Still accurate"),
        isConflict(twice),
        after.versions.map(v => (v.label, v.status)) == List(("1.0", Published)),
        after.versions.head.content == v1.content,
        after.versions.head.metadataSnapshot == v1.metadataSnapshot,
        due == Some(LocalDate.of(2028, 1, 5)),
        open.isEmpty,
        dec.map(t => (t.id, t.status)) == List((task.id, ReviewStatus.Recertified)),
        recertified.size == 1,
        recertified.head.actor == Actor(owner.value, ActorKind.Person),
        recertified.head.details.value.replace(" ", "").contains("\"previousDueOn\":\"2027-01-01\""),
        recertified.head.details.value.replace(" ", "").contains("\"nextDueOn\":\"2028-01-05\""),
        audit.exists(e => e.action == "document.status-changed" && e.details.value.contains("Recertify")),
        none == ReviewCheck(0, 0, 0),
        next == ReviewCheck(1, 0, 0),
        again.map(_.dueOn) == List(LocalDate.of(2028, 1, 5))
      )
    },
    test(
      "revision starts a draft from the valid version, or links the open one; the valid one stays in force"
    ) {
      for
        f     <- fixture()
        one   <- valid(f)
        two   <- valid(f)
        open  <- as(f.tenant)(_.newVersion(two._1, ChangeKind.Major, None, author))
        _     <- at("2026-11-10T09:00:00Z")
        _     <- check(f.tenant)
        tasks <- openTasks(f.tenant)
        taskOne = tasks.find(_.documentId == one._1).get
        taskTwo = tasks.find(_.documentId == two._1).get
        revised <- review(f.tenant)(_.revise(taskOne.id, ChangeKind.Major, Some("New regulation"), owner))
        (decided, draft) = revised
        linked <- review(f.tenant)(_.revise(taskTwo.id, ChangeKind.Major, None, owner))
        after  <- as(f.tenant)(_.get(one._1))
        events <- outbox(one._1, "document.review-revised")
      yield assertTrue(
        decided.status == ReviewStatus.Revised,
        decided.newVersionId == Some(draft.id),
        decided.comment == Some("New regulation"),
        draft.label == "2.0-draft.1",
        draft.createdBy == owner,
        after.versions.map(_.status) == List(Published, Draft),
        linked._1.newVersionId == Some(open.id),
        linked._2.id == open.id,
        events.size == 1
      )
    },
    test("withdrawal puts the version out of force and leaves the approved successor alone") {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        v2        <- scheduled(f, doc, LocalDate.of(2027, 6, 1))
        _         <- at("2026-11-10T09:00:00Z")
        _         <- check(f.tenant)
        task      <- openTasks(f.tenant).map(_.head)
        noReason  <- review(f.tenant)(_.withdraw(task.id, "  ", owner)).either
        withdrawn <- review(f.tenant)(_.withdraw(task.id, "Replaced by the group policy", owner))
        after     <- as(f.tenant)(_.get(doc))
        before    <- as(f.tenant)(_.validAt(Instant.parse("2026-11-10T08:00:00Z")))
        now       <- as(f.tenant)(_.validAt(Instant.parse("2026-11-10T10:00:00Z")))
        events    <- outbox(doc, "document.status-changed")
        again     <- review(f.tenant)(_.withdraw(task.id, "Twice", owner)).either
        _         <- at("2027-05-31T22:00:00Z")
        published <- as(f.tenant)(_.publishScheduled(v2.id))
        last      <- as(f.tenant)(_.get(doc))
        open      <- openTasks(f.tenant)
      yield assertTrue(
        isInvalid(noReason),
        withdrawn.status == ReviewStatus.Withdrawn,
        withdrawn.comment == Some("Replaced by the group policy"),
        after.versions.map(_.status) == List(Withdrawn, Approved),
        before.map(_.versionId) == List(v1.id),
        before.head.period.until.isDefined,
        now.isEmpty,
        events.last.contains("\"Withdrawn\""),
        isConflict(again),
        published.map(_.status) == Some(Published),
        last.versions.map(v => (v.label, v.status)) == List(("1.0", Withdrawn), ("2.0", Published)),
        last.versions.head.supersededBy.isEmpty,
        open.isEmpty
      )
    },
    test("an open task is closed when its version is superseded outside the review") {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        _      <- at("2026-11-10T09:00:00Z")
        _      <- check(f.tenant)
        _      <- as(f.tenant)(_.newVersion(doc, ChangeKind.Major, None, author))
        v2     <- approveOpen(f, doc)
        _      <- as(f.tenant)(_.changeStatus(v2.id, Publish, approver))
        result <- check(f.tenant)
        again  <- check(f.tenant)
        done   <- review(f.tenant)(_.tasksOf(owner, open = false))
        open   <- openTasks(f.tenant)
      yield assertTrue(
        // The task of 1.0 closes; 2.0 (valid from the same date) is due for review as well.
        result == ReviewCheck(1, 0, 1),
        again == ReviewCheck(0, 0, 0),
        done.map(t => (t.versionId, t.status)) == List((v1.id, ReviewStatus.Obsolete)),
        open.map(_.versionId) == List(v2.id)
      )
    },
    test("the document's next review date shortens the cycle; without interval or date there is no review") {
      for
        f <- fixture(ReviewPolicy())
        _ <- at("2026-01-02T12:00:00Z")
        withDate = values(LocalDate.of(2026, 1, 1)) +
          ("nextReview" -> FieldEntry(MetadataValue.Date(LocalDate.of(2026, 3, 1)), ValueOrigin.Manual))
        created <- as(f.tenant)(_.create(NewDocument(f.templateId, 1, "Dated", withDate), author))
        _       <- approveOpen(f, created._1.id)
        dated   <- as(f.tenant)(_.changeStatus(created._2.id, Publish, approver))
        plain   <- valid(f)
        d1      <- dueOn(f.tenant, dated.id)
        d2      <- dueOn(f.tenant, plain._2.id)
        _       <- at("2026-01-05T09:00:00Z")
        result  <- check(f.tenant)
        tasks   <- openTasks(f.tenant)
      yield assertTrue(
        d1 == Some(LocalDate.of(2026, 3, 1)),
        d2.isEmpty,
        result == ReviewCheck(1, 0, 0),
        tasks.map(_.versionId) == List(dated.id)
      )
    },
    test("the database keeps review dates of invalid versions and decided tasks unchanged") {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        _         <- at("2026-11-10T09:00:00Z")
        _         <- check(f.tenant)
        task      <- openTasks(f.tenant).map(_.head)
        draft     <- as(f.tenant)(_.newVersion(doc, ChangeKind.Major, None, author))
        e         <- ZIO.service[Env]
        draftDate <- e.app
          .transact(f.tenant)(
            Db.sql(
              sql"update dms.document_version set review_due_on = '2030-01-01' where id = ${draft.id}".update
                .run()
            )
          )
          .either
        validDate <- e.app
          .transact(f.tenant)(
            Db.sql(
              sql"update dms.document_version set review_due_on = '2030-01-01' where id = ${v1.id}".update
                .run()
            )
          )
          .either
        decidedInsert <- e.app
          .transact(f.tenant)(
            Db.sql(
              sql"""insert into dms.document_review (id, tenant_id, document_id, version_id, due_on, status,
                                                     decided_at, decided_by, next_due_on)
                    values (${UUID.randomUUID()}, ${f.tenant.value}, ${doc.value}, ${v1.id}, '2031-01-01',
                            'Recertified', now(), 'owner-1', '2032-01-01')""".update.run()
            )
          )
          .either
        deleted <- e.app
          .transact(f.tenant)(Db.sql(sql"delete from dms.document_review where id = ${task.id}".update.run()))
          .either
        _        <- review(f.tenant)(_.recertify(task.id, Some(LocalDate.of(2027, 6, 1)), None, owner))
        reopened <- e.app
          .transact(f.tenant)(
            Db.sql(
              sql"""update dms.document_review set status = 'Open', decided_at = null, decided_by = null,
                    next_due_on = null where id = ${task.id}""".update.run()
            )
          )
          .either
      yield assertTrue(
        draftDate.isLeft,
        validDate == Right(1),
        decidedInsert.isLeft,
        deleted.isLeft,
        reopened.isLeft
      )
    }
  ).provideShared(env) @@ TestAspect.sequential
