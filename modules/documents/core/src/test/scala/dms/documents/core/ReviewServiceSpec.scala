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

  final case class Fixture(tenant: TenantId, templateId: TemplateId, classId: DocumentClassId)

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

  /** A tenant with a class and a template of it. With `parent`, the class lies below a class with these
    * settings.
    */
  private def fixture(
      review: ReviewPolicy = yearly,
      zone: String = "Europe/Berlin",
      parent: Option[ReviewPolicy] = None
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
      above <- ZIO.foreach(parent)(policy =>
        e.app.transact(tenant)(
          classes.createClass("parent", None, ClassDraft("Parent", None, Nil, review = Some(policy)), admin)
        )
      )
      cls <- e.app.transact(tenant)(
        classes.createClass(
          "policy",
          above.map(_.classId),
          ClassDraft("Policy", None, Nil, review = Some(review)),
          admin
        )
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
    yield Fixture(tenant, templateId, cls.classId)

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

  /** A document whose version 1.0 is valid and whose review task is decided as "revise": the document, the
    * valid version, the decided task and the draft.
    */
  private def revisedTask(f: Fixture) =
    for
      created <- valid(f)
      (doc, v1) = created
      _       <- at("2026-11-10T09:00:00Z")
      _       <- check(f.tenant)
      task    <- openTasks(f.tenant).map(_.head)
      revised <- review(f.tenant)(_.revise(task.id, ChangeKind.Major, Some("New regulation"), owner))
    yield (doc, v1, revised._1, revised._2)

  /** The draft of a revision, approved and valid from 2027-06-01. */
  private def approveRevision(f: Fixture, doc: DocumentId) =
    as(f.tenant)(_.updateDraft(doc, DraftChange(None, values(LocalDate.of(2027, 6, 1)), None), author)) *>
      approveOpen(f, doc)

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

  /** A new version of a class with these review settings; returns its number. */
  private def setPolicy(f: Fixture, id: DocumentClassId, policy: ReviewPolicy): ZIO[Env, Any, Int] =
    ZIO.serviceWithZIO[Env](e =>
      e.app
        .transact(f.tenant)(
          DocumentClassService(RecordingAuditTrail(e.audit))
            .newClassVersion(id, ClassDraft("Policy", None, Nil, review = Some(policy)), admin)
        )
        .map(_.version)
    )

  /** The recalculation job of a class version: how many dates it moved or kept. */
  private def recalculate(f: Fixture, id: DocumentClassId, version: Int): ZIO[Env, String, Int] =
    for
      e <- ZIO.service[Env]
      r <- reviews
      n <- ReviewJobs(e.app, r, ReviewScheduleConfig())
        .recalculate(f.tenant, ReviewJobs.Recalculation(id.value, version))
    yield n

  /** Changes the review settings of the fixture's class and runs the recalculation. */
  private def changeInterval(f: Fixture, policy: ReviewPolicy) =
    setPolicy(f, f.classId, policy).flatMap(recalculate(f, f.classId, _))

  private def status(tenant: TenantId, version: UUID) =
    ZIO.serviceWithZIO[Env](_.app.transact(tenant)(Db.sql(ReviewStore.versionStatus(version))))

  private def dateEvents(doc: DocumentId, action: String) =
    auditOf(doc).map(_.filter(_.action == action).map(_.details.value))

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
        open  <- as(f.tenant)(_.newVersion(two._1, ChangeKind.Major, Some("Content revised"), author))
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
    test("a discarded revision gives the valid version an open review task again, with the same date") {
      for
        f       <- fixture()
        revised <- revisedTask(f)
        (doc, v1, decided, _) = revised
        before    <- openTasks(f.tenant)
        _         <- as(f.tenant)(_.discardDraft(doc, Some("Not needed after all"), owner))
        open      <- openTasks(f.tenant)
        checked   <- check(f.tenant)
        again     <- openTasks(f.tenant)
        all       <- review(f.tenant)(_.tasksOfDocument(doc))
        deadlines <- review(f.tenant)(_.openForDeadlines(decided.dueOn))
        locked    <- review(f.tenant)(_.lockForDeadline(open.head.id))
        events    <- outbox(doc, ReviewService.Reopened)
        audit     <- auditOf(doc).map(_.filter(_.action == ReviewService.Reopened))
        done      <- review(f.tenant)(_.recertify(open.head.id, None, None, owner))
      yield assertTrue(
        before.isEmpty,
        open.map(t => (t.versionId, t.dueOn, t.reopens)) == List((v1.id, decided.dueOn, Some(decided.id))),
        checked == ReviewCheck(0, 0, 0),
        again == open,
        // the decision stays as it was recorded
        all.size == 2,
        all.find(_.id == decided.id).contains(decided),
        // the deadline check of ADR 0057 sees it, without notices of the decided task
        deadlines.map((t, sent) => (t.id, sent)) == List((open.head.id, Set.empty[String])),
        locked.map(_._1.id).contains(open.head.id),
        events.size == 1,
        events.head.contains("\"revisionStatus\": \"Discarded\""),
        audit.size == 1,
        audit.head.actor.kind == ActorKind.Technical,
        done.status == ReviewStatus.Recertified
      )
    },
    test("a revision withdrawn before it took effect gives the valid version its task again") {
      for
        f       <- fixture()
        revised <- revisedTask(f)
        (doc, v1, decided, _) = revised
        v2      <- approveRevision(f, doc)
        waiting <- openTasks(f.tenant)
        _       <- as(f.tenant)(_.withdrawApproved(v2.id, "Approved by mistake", Set(approver), owner))
        open    <- openTasks(f.tenant)
        _       <- check(f.tenant)
        _       <- check(f.tenant)
        all     <- review(f.tenant)(_.tasksOfDocument(doc))
        events  <- outbox(doc, ReviewService.Reopened)
        current <- as(f.tenant)(_.get(doc))
      yield assertTrue(
        waiting.isEmpty,
        open.map(t => (t.versionId, t.dueOn, t.reopens)) == List((v1.id, decided.dueOn, Some(decided.id))),
        all.size == 2,
        all.find(_.id == decided.id).contains(decided),
        events.size == 1,
        events.head.contains("\"revisionStatus\": \"Withdrawn\""),
        current.versions.map(_.status) == List(Published, Withdrawn)
      )
    },
    test("a revision published as usual leaves the task decided: nothing opens again") {
      for
        f       <- fixture()
        revised <- revisedTask(f)
        (doc, _, decided, _) = revised
        v2      <- approveRevision(f, doc)
        _       <- at("2027-06-01T09:00:00Z")
        _       <- as(f.tenant)(_.publishScheduled(v2.id))
        checked <- check(f.tenant)
        all     <- review(f.tenant)(_.tasksOfDocument(doc))
        open    <- openTasks(f.tenant)
        events  <- outbox(doc, ReviewService.Reopened)
        current <- as(f.tenant)(_.get(doc))
      yield assertTrue(
        checked.reopened == 0,
        all == List(decided),
        open.isEmpty,
        events.isEmpty,
        current.versions.map(_.status) == List(Superseded, Published)
      )
    },
    test("a revision published for another scope leaves the version valid, so its task opens again") {
      val otherScope = values(LocalDate.of(2027, 6, 1)) +
        ("scope" -> FieldEntry(
          MetadataValue.Many(List(MetadataValue.Text("Plant Bremen"))),
          ValueOrigin.Manual
        ))
      for
        f       <- fixture()
        revised <- revisedTask(f)
        (doc, v1, decided, _) = revised
        _       <- as(f.tenant)(_.updateDraft(doc, DraftChange(None, otherScope, None), author))
        v2      <- approveOpen(f, doc)
        waiting <- openTasks(f.tenant)
        _       <- at("2027-06-01T09:00:00Z")
        _       <- as(f.tenant)(_.publishScheduled(v2.id))
        open    <- openTasks(f.tenant)
        _       <- check(f.tenant)
        all     <- review(f.tenant)(_.tasksOfDocument(doc))
        events  <- outbox(doc, ReviewService.Reopened)
        current <- as(f.tenant)(_.get(doc))
      yield assertTrue(
        waiting.isEmpty,
        open.map(t => (t.versionId, t.dueOn, t.reopens)) == List((v1.id, decided.dueOn, Some(decided.id))),
        all.size == 2,
        all.find(_.id == decided.id).contains(decided),
        events.size == 1,
        events.head.contains("\"revisionStatus\": \"Published\""),
        current.versions.filter(_.id == v1.id).forall(v => Versioning.isValid(v.status)),
        current.versions.find(_.id == v2.id).exists(_.status == Published)
      )
    },
    test("repeated events add no second task; a task opened again can be revised and opened again") {
      for
        f       <- fixture()
        revised <- revisedTask(f)
        (doc, _, decided, _) = revised
        _        <- as(f.tenant)(_.discardDraft(doc, None, owner))
        repeat   <- review(f.tenant)(_.reopen(decided.id))
        parallel <- ZIO.foreachPar(List(1, 2, 3))(_ => review(f.tenant)(_.reopen(decided.id)))
        second   <- openTasks(f.tenant).map(_.head)
        // while the second revision is alive, there is nothing to open
        next    <- review(f.tenant)(_.revise(second.id, ChangeKind.Major, Some("Second try"), owner))
        early   <- review(f.tenant)(_.reopen(next._1.id))
        _       <- as(f.tenant)(_.discardDraft(doc, None, owner))
        third   <- openTasks(f.tenant)
        all     <- review(f.tenant)(_.tasksOfDocument(doc))
        checked <- check(f.tenant)
        events  <- outbox(doc, ReviewService.Reopened)
      yield assertTrue(
        repeat.isEmpty,
        parallel.forall(_.isEmpty),
        early.isEmpty,
        third.map(_.reopens) == List(Some(second.id)),
        all.map(_.status).sortBy(_.ordinal) ==
          List(ReviewStatus.Open, ReviewStatus.Revised, ReviewStatus.Revised),
        all.map(_.dueOn).toSet == Set(decided.dueOn),
        checked == ReviewCheck(0, 0, 0),
        events.size == 2
      )
    },
    test("the periodic check opens what was lost before, once") {
      for
        f       <- fixture()
        revised <- revisedTask(f)
        (_, v1, decided, draft) = revised
        // nothing reopens on this path at once: it stands for a task lost before the change
        _     <- as(f.tenant)(_.changeStatus(draft.id, Withdraw, owner))
        none  <- openTasks(f.tenant)
        first <- check(f.tenant)
        again <- check(f.tenant)
        open  <- openTasks(f.tenant)
      yield assertTrue(
        none.isEmpty,
        first == ReviewCheck(0, 0, 0, reopened = 1),
        again == ReviewCheck(0, 0, 0),
        open.map(t => (t.versionId, t.dueOn, t.reopens)) == List((v1.id, decided.dueOn, Some(decided.id)))
      )
    },
    test("a task whose version is not valid any more stays closed") {
      for
        f       <- fixture()
        revised <- revisedTask(f)
        (doc, v1, _, _) = revised
        _       <- as(f.tenant)(_.changeStatus(v1.id, Withdraw, owner))
        _       <- as(f.tenant)(_.discardDraft(doc, None, owner))
        checked <- check(f.tenant)
        open    <- openTasks(f.tenant)
      yield assertTrue(checked.reopened == 0, open.isEmpty)
    },
    test("the database opens a decided task again only for a revision that never took effect, and once") {
      def insert(f: Fixture, doc: DocumentId, version: UUID, dueOn: LocalDate, reopens: UUID) =
        ZIO.serviceWithZIO[Env](
          _.app
            .transact(f.tenant)(
              Db.sql(
                sql"""insert into dms.document_review (id, tenant_id, document_id, version_id, due_on, reopens_id)
                      values (${UUID.randomUUID()}, ${f.tenant.value}, ${doc.value}, $version, ${java.sql.Date
                    .valueOf(dueOn)},
                              $reopens)""".update.run()
              )
            )
            .either
        )
      for
        f       <- fixture()
        revised <- revisedTask(f)
        (doc, v1, decided, _) = revised
        e             <- ZIO.service[Env]
        whileAlive    <- insert(f, doc, v1.id, decided.dueOn, decided.id)
        _             <- as(f.tenant)(_.discardDraft(doc, None, owner))
        twice         <- insert(f, doc, v1.id, decided.dueOn, decided.id)
        open          <- openTasks(f.tenant).map(_.head)
        otherDate     <- insert(f, doc, v1.id, decided.dueOn.plusDays(1), decided.id)
        openIsNoBasis <- insert(f, doc, v1.id, decided.dueOn, open.id)
        relinked      <- e.app
          .transact(f.tenant)(
            Db.sql(
              sql"update dms.document_review set reopens_id = null where id = ${open.id}".update.run()
            )
          )
          .either
        firstRow <- e.app
          .transact(f.tenant)(
            Db.sql(
              sql"""insert into dms.document_review (id, tenant_id, document_id, version_id, due_on)
                    values (${UUID.randomUUID()}, ${f.tenant.value}, ${doc.value}, ${v1.id},
                            ${java.sql.Date.valueOf(decided.dueOn)})""".update.run()
            )
          )
          .either
      yield assertTrue(
        whileAlive.isLeft,
        twice.isLeft,
        otherDate.isLeft,
        openIsNoBasis.isLeft,
        relinked.isLeft,
        firstRow.isLeft
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
        _      <- as(f.tenant)(_.newVersion(doc, ChangeKind.Major, Some("Content revised"), author))
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
        draft     <- as(f.tenant)(_.newVersion(doc, ChangeKind.Major, Some("Content revised"), author))
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
    },
    test("a longer interval moves a date that is not due yet; it is audited and recorded once") {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        _        <- at("2026-03-01T09:00:00Z")
        version  <- setPolicy(f, f.classId, ReviewPolicy(Some(24), 60))
        first    <- recalculate(f, f.classId, version)
        due      <- dueOn(f.tenant, v1.id)
        again    <- recalculate(f, f.classId, version)
        changed  <- dateEvents(doc, ReviewService.DateChanged)
        events   <- outbox(doc, ReviewService.DateChanged)
        e        <- ZIO.service[Env]
        recorded <- e.app.transact(f.tenant)(
          Db.sql(
            sql"""select outcome, previous_due_on::text, next_due_on::text from dms.document_review_recalculation
                  where version_id = ${v1.id}""".query[(String, String, String)].run().toList
          )
        )
        update <- e.app
          .transact(f.tenant)(
            Db.sql(sql"update dms.document_review_recalculation set outcome = 'Kept'".update.run())
          )
          .either
      yield assertTrue(
        first == 1,
        again == 0,
        due.contains(LocalDate.of(2028, 1, 1)),
        changed.size == 1,
        changed.head.contains("\"previousDueOn\":\"2027-01-01\""),
        changed.head.contains("\"nextDueOn\":\"2028-01-01\""),
        changed.head.contains("\"trigger\":\"class-change\""),
        events.size == 1,
        recorded == List(("Moved", "2027-01-01", "2028-01-01")),
        update.isLeft
      )
    },
    test(
      "a shorter interval ends the open task for the old date; a passed date becomes today and is marked"
    ) {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        _      <- at("2026-11-10T09:00:00Z")
        _      <- check(f.tenant)
        before <- openTasks(f.tenant).map(_.head)
        _      <- changeInterval(f, ReviewPolicy(Some(6), 60))
        due    <- dueOn(f.tenant, v1.id)
        state  <- status(f.tenant, v1.id)
        tasks  <- review(f.tenant)(_.tasksOfDocument(doc))
        moved  <- dateEvents(doc, ReviewService.DateChanged)
      yield assertTrue(
        due.contains(LocalDate.of(2026, 11, 10)),
        state.contains(ReviewDue),
        tasks.map(t => (t.id == before.id, t.dueOn, t.status)).toSet == Set(
          (true, LocalDate.of(2027, 1, 1), ReviewStatus.Obsolete),
          (false, LocalDate.of(2026, 11, 10), ReviewStatus.Open)
        ),
        moved.head.contains("\"computedDueOn\":\"2026-07-01\"")
      )
    },
    test("an overdue review keeps its date, status and task in both directions, and that is recorded") {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        _       <- at("2027-02-01T09:00:00Z")
        _       <- check(f.tenant)
        longer  <- changeInterval(f, ReviewPolicy(Some(24), 60))
        shorter <- changeInterval(f, ReviewPolicy(Some(6), 60))
        due     <- dueOn(f.tenant, v1.id)
        state   <- status(f.tenant, v1.id)
        tasks   <- openTasks(f.tenant)
        kept    <- dateEvents(doc, ReviewService.DateKept)
        changed <- dateEvents(doc, ReviewService.DateChanged)
      yield assertTrue(
        longer == 1,
        shorter == 1,
        due.contains(LocalDate.of(2027, 1, 1)),
        state.contains(ReviewDue),
        tasks.map(_.dueOn) == List(LocalDate.of(2027, 1, 1)),
        kept.size == 2,
        kept.forall(_.contains("\"reason\":\"Overdue\"")),
        changed.isEmpty
      )
    },
    test("a date reached today counts as overdue before the check marked it") {
      for
        f       <- fixture()
        created <- valid(f)
        (_, v1) = created
        _     <- at("2027-01-01T09:00:00Z")
        _     <- changeInterval(f, ReviewPolicy(Some(24), 60))
        due   <- dueOn(f.tenant, v1.id)
        state <- status(f.tenant, v1.id)
      yield assertTrue(due.contains(LocalDate.of(2027, 1, 1)), state.contains(Published))
    },
    test("a longer interval leaves a date alone whose owner was asked already") {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        _     <- at("2026-11-10T09:00:00Z")
        _     <- check(f.tenant)
        task  <- openTasks(f.tenant).map(_.head)
        _     <- changeInterval(f, ReviewPolicy(Some(24), 60))
        due   <- dueOn(f.tenant, v1.id)
        tasks <- openTasks(f.tenant)
        kept  <- dateEvents(doc, ReviewService.DateKept)
      yield assertTrue(
        due.contains(LocalDate.of(2027, 1, 1)),
        tasks.map(_.id) == List(task.id),
        kept.head.contains("\"reason\":\"OpenTask\"")
      )
    },
    test("a date whose task is decided stays and the decision is not touched") {
      for
        f       <- fixture()
        created <- revisedTask(f)
        (doc, v1, task, _) = created
        _     <- changeInterval(f, ReviewPolicy(Some(6), 60))
        due   <- dueOn(f.tenant, v1.id)
        after <- review(f.tenant)(_.task(task.id))
        all   <- review(f.tenant)(_.tasksOfDocument(doc))
        kept  <- dateEvents(doc, ReviewService.DateKept)
      yield assertTrue(
        due.contains(task.dueOn),
        after == task,
        all.size == 1,
        kept.head.contains("\"reason\":\"Decided\"")
      )
    },
    test("a recertified date only moves earlier, counted from the recertification, and its task stays") {
      val chosen = LocalDate.of(2027, 6, 1)
      for
        f       <- fixture()
        created <- valid(f)
        (_, v1) = created
        _       <- at("2026-11-10T09:00:00Z")
        _       <- check(f.tenant)
        task    <- openTasks(f.tenant).map(_.head)
        decided <- review(f.tenant)(_.recertify(task.id, Some(chosen), None, owner))
        _       <- changeInterval(f, ReviewPolicy(Some(24), 60))
        kept    <- dueOn(f.tenant, v1.id)
        _       <- changeInterval(f, ReviewPolicy(Some(3), 60))
        moved   <- dueOn(f.tenant, v1.id)
        after   <- review(f.tenant)(_.task(task.id))
      yield assertTrue(
        kept.contains(chosen),
        moved.contains(LocalDate.of(2027, 2, 10)),
        after == decided,
        after.nextDueOn.contains(chosen)
      )
    },
    test("removing the interval keeps the review date") {
      for
        f       <- fixture()
        created <- valid(f)
        (doc, v1) = created
        _    <- at("2026-03-01T09:00:00Z")
        _    <- changeInterval(f, ReviewPolicy())
        due  <- dueOn(f.tenant, v1.id)
        kept <- dateEvents(doc, ReviewService.DateKept)
      yield assertTrue(
        due.contains(LocalDate.of(2027, 1, 1)),
        kept.head.contains("\"reason\":\"NoInterval\"")
      )
    },
    test(
      "the review date and the lead time come from the parent, and a parent's change reaches the subclass"
    ) {
      for
        f       <- fixture(ReviewPolicy.inherit, parent = Some(ReviewPolicy(Some(12), 30)))
        created <- valid(f)
        (_, v1) = created
        e      <- ZIO.service[Env]
        parent <- e.app.transact(f.tenant)(
          Db.sql(sql"select id from dms.document_class where key = 'parent'".query[UUID].run().head)
        )
        due        <- dueOn(f.tenant, v1.id)
        _          <- at("2026-11-15T09:00:00Z")
        beforeLead <- check(f.tenant)
        _          <- at("2026-12-05T09:00:00Z")
        atLead     <- check(f.tenant)
        version    <- setPolicy(f, DocumentClassId(parent), ReviewPolicy(Some(6), 30))
        moved      <- recalculate(f, DocumentClassId(parent), version)
        after      <- dueOn(f.tenant, v1.id)
        // With settings of its own the subclass is out of reach of the parent.
        _       <- setPolicy(f, f.classId, ReviewPolicy(Some(36), 30))
        reached <- review(f.tenant)(_.reachedBy(parent))
      yield assertTrue(
        due.contains(LocalDate.of(2027, 1, 1)),
        beforeLead.opened == 0,
        atLead.opened == 1,
        moved == 1,
        after.contains(LocalDate.of(2026, 12, 5)),
        reached.isEmpty
      )
    },
    test("versions without a date get one from the check, once, and never before today") {
      for
        f       <- fixture(ReviewPolicy())
        created <- valid(f)
        (doc, v1) = created
        none    <- dueOn(f.tenant, v1.id)
        idle    <- check(f.tenant)
        _       <- setPolicy(f, f.classId, yearly)
        _       <- at("2028-03-01T09:00:00Z")
        first   <- check(f.tenant)
        due     <- dueOn(f.tenant, v1.id)
        state   <- status(f.tenant, v1.id)
        tasks   <- openTasks(f.tenant)
        again   <- check(f.tenant)
        changed <- dateEvents(doc, ReviewService.DateChanged)
        e       <- ZIO.service[Env]
        records <- e.app.transact(f.tenant)(
          Db.sql(sql"select count(*) from dms.document_review_recalculation".query[Long].run().head)
        )
      yield assertTrue(
        none.isEmpty,
        idle.scheduled == 0,
        first.scheduled == 1,
        again.scheduled == 0,
        due.contains(LocalDate.of(2028, 3, 1)),
        state.contains(ReviewDue),
        tasks.map(_.dueOn) == List(LocalDate.of(2028, 3, 1)),
        changed.size == 1,
        changed.head.contains("\"previousDueOn\":null"),
        changed.head.contains("\"computedDueOn\":\"2027-01-01\""),
        changed.head.contains("\"trigger\":\"missing-date\""),
        records == 0L
      )
    },
    test("the backfill leaves dated, superseded and other tenants' versions alone") {
      for
        f       <- fixture(ReviewPolicy())
        other   <- fixture(ReviewPolicy())
        created <- valid(f)
        (doc, v1) = created
        foreign <- valid(other)
        _       <- as(f.tenant)(_.newVersion(doc, ChangeKind.Major, Some("Content revised"), author))
        _       <- as(f.tenant)(
          _.updateDraft(doc, DraftChange(None, values(LocalDate.of(2026, 2, 1)), None), author)
        )
        a       <- approveOpen(f, doc)
        _       <- at("2026-02-02T09:00:00Z")
        v2      <- as(f.tenant)(_.changeStatus(a.id, Publish, approver))
        _       <- setPolicy(f, f.classId, yearly)
        work    <- review(f.tenant)(_.pendingWork)
        _       <- check(f.tenant)
        old     <- dueOn(f.tenant, v1.id)
        current <- dueOn(f.tenant, v2.id)
        outside <- dueOn(other.tenant, foreign._2.id)
      yield assertTrue(
        work.withoutDate == List(v2.id),
        old.isEmpty,
        current.contains(LocalDate.of(2027, 2, 1)),
        outside.isEmpty
      )
    }
  ).provideShared(env) @@ TestAspect.sequential
