package dms.app

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import dms.audit.AuditTrail
import dms.audit.core.{AuditTrailLive, TsaTrust}
import dms.authz.{Authorizer, Groups, Role, RoleAssignment, RoleAssignments}
import dms.archive.core.LegalHoldService
import dms.db.queue.OutboxEvent
import dms.db.{Db, DbConfig, DbError, Jsonb, Migrations}
import dms.documents.ReviewPolicy
import dms.documents.core.*
import dms.identity.*
import dms.integration.{JobRunner, OutboxRelay, WorkerConfig}
import dms.kernel.{TemplateId, TenantId, UserId}
import dms.notifications.{Mail, MailSender, Person, RecipientDirectory}
import dms.notifications.core.{DeliverySettings, NotificationJobs}
import dms.templates.*
import dms.templates.core.{TemplateDraft, TemplateService}
import dms.rendering.core.RenditionSource
import dms.workflow.core.{
  ApprovalWorkflow,
  DeadlineJobs,
  DeadlineScheduleConfig,
  DeadlineSource,
  DeadlineStage,
  Deadlines,
  Deputyships,
  FlowableEngine,
  WorkflowConfig,
  WorkflowError
}
import dms.workflow.{DeadlinePolicy, NewDeputyship}
import org.testcontainers.utility.DockerImageName
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.RichZServerEndpoint
import zio.*
import zio.http.{Body, Header, Headers, MediaType, Request, Status, URL}
import zio.json.*
import zio.test.*

import java.time.{Instant, LocalDate}
import java.util.{Date, UUID}
import javax.sql.DataSource

/** Periodic review and recertification (DMS-55) end to end: HTTP endpoints, the real documents and workflow
  * modules, the review check and the audit trail against a Testcontainers database. Review dates count from
  * today (live clock).
  */
object ReviewApiSpec extends ZIOSpecDefault:

  private val issuer    = "http://localhost:8085"
  private val audience  = "project-1"
  private val orgId     = "org-1"
  private val tenantId  = TenantId.random()
  private val admin     = UserId("admin-1")
  private val author    = UserId("author-1")
  private val owner     = UserId("owner-1")
  private val reviewer  = UserId("reviewer-1")
  private val approver  = UserId("approver-1")
  private val reader    = UserId("reader-1")
  private val owner2    = UserId("owner-2")
  private val designer  = UserId("designer-1")
  private val designer2 = UserId("designer-2")

  private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("key-1").generate()

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  final case class Setup(app: Db, owner: Db, released: TemplateId)

  private val setup = Unsafe.unsafe(implicit u => Promise.unsafe.make[Nothing, Setup](FiberId.None))

  /** Twenty words of policy text. */
  private val words = (1 to 20).map(i => s"rule$i").toList

  private def content(text: List[String]) = TemplateContent(
    List(
      Section(
        SectionId("rules"),
        Some("Rules"),
        locked = false,
        List(Block.Paragraph(List(Inline.Text(text.mkString(" ")))))
      )
    )
  )

  private val database: ZLayer[Any, Throwable, Db & DataSource] = ZLayer.scopedEnvironment(
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
      _           <- Migrations.migrate(c.jdbcUrl, c.username, c.password)
      _           <- FlowableEngine.migrateSchema(c.jdbcUrl, c.username, c.password)
      schemaOwner <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, poolSize = 1)).map(Db(_))
      _           <- schemaOwner
        .transactSystem(
          Db.sql(sql"insert into dms.tenant (id, name) values (${tenantId.value}, ${"Tenant"})".update.run())
        )
        .orDieWith(e => RuntimeException(e.toString))
      appSource <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", poolSize = 4))
      app       = Db(appSource)
      audit     = AuditTrailLive(app, TsaTrust(Nil))
      classes   = DocumentClassService(audit)
      templates = TemplateService(audit, DocumentClassProfiles(classes))
      released <- app
        .transact(tenantId)(
          for
            _   <- RoleAssignments.Postgres.grant(admin, RoleAssignment(Role.TenantAdmin), admin)
            _   <- RoleAssignments.Postgres.grant(author, RoleAssignment(Role.Author), admin)
            _   <- RoleAssignments.Postgres.grant(owner, RoleAssignment(Role.Owner), admin)
            _   <- RoleAssignments.Postgres.grant(reviewer, RoleAssignment(Role.Reviewer), admin)
            _   <- RoleAssignments.Postgres.grant(approver, RoleAssignment(Role.Approver), admin)
            _   <- RoleAssignments.Postgres.grant(reader, RoleAssignment(Role.Reader), admin)
            _   <- RoleAssignments.Postgres.grant(owner2, RoleAssignment(Role.Owner), admin)
            cls <- classes.createClass(
              "policy",
              None,
              ClassDraft("Policy", None, Nil, review = Some(ReviewPolicy(Some(12), 60))),
              admin
            )
            _ <- RetentionFixtures.assign(classes, cls.classId, "policy-10y")
            draft = TemplateDraft("Code of conduct", None, cls.classId.value, 1, content(words))
            t <- templates.create(draft, designer)
            _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Testing, designer)
            _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Released, designer2)
          yield t.templateId
        )
        .mapError(e => RuntimeException(e.toString))
        .orDie
      _ <- setup.succeed(Setup(app, schemaOwner, released))
    yield ZEnvironment(app, appSource)
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  private val authenticator: ULayer[Authenticator] = ZLayer.succeed {
    val verifier = TokenVerifier(
      AuthConfig(issuer, audience),
      ImmutableJWKSet[SecurityContext](JWKSet(signingKey.toPublicJWK))
    )
    val tenants = new TenantDirectory:
      def tenantFor(organizationId: String): IO[DbError, Option[TenantId]] =
        ZIO.succeed(Option.when(organizationId == orgId)(tenantId))
    Authenticator(verifier, tenants)
  }

  private type Env = ReviewApi.Env & DocumentClassApi.Env & WorkflowApi.Env & RenditionSource & AuditTrail &
    Groups & RoleAssignments

  private val env: ZLayer[Any, Throwable, Env] =
    ZLayer.make[Env](
      database,
      authenticator,
      RoleAssignments.postgres,
      Groups.postgres,
      Authorizer.layer,
      ZLayer.succeed(TsaTrust(Nil)),
      AuditTrailLive.layer,
      DocumentClassService.layer,
      DocumentClassProfiles.layer,
      TemplateService.layer,
      DocumentTemplates.layer,
      DocumentService.configured(DocumentSettings(editorialChangeThreshold = 0.1)),
      FlowableEngine.layer,
      WorkflowDocuments.layer,
      RoleDirectory.layer,
      WorkflowExtraReview.layer,
      ZLayer.succeed(WorkflowConfig.default),
      WorkflowRendition.layer,
      ApprovalWorkflow.layer,
      DocumentRenditions.layer,
      ReviewService.layer
    )

  /** `signedInSecondsAgo` sets `auth_time`; by default the sign-in is one hour old. */
  private def token(user: UserId, signedInSecondsAgo: Long): String =
    val now    = Instant.now()
    val claims = JWTClaimsSet
      .Builder()
      .issuer(issuer)
      .audience(audience)
      .subject(user.value)
      .issueTime(Date.from(now.minusSeconds(10)))
      .expirationTime(Date.from(now.plusSeconds(300)))
      .claim(TokenVerifier.OrganizationClaim, orgId)
      .claim("auth_time", now.minusSeconds(signedInSecondsAgo).getEpochSecond)
      .build()
    val jwt = SignedJWT(
      JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID).`type`(JOSEObjectType.JWT).build(),
      claims
    )
    jwt.sign(RSASSASigner(signingKey))
    jwt.serialize()

  private val routes = ZioHttpInterpreter().toHttp(
    ReviewApi.endpoints ++ DocumentApi.endpoints.map(_.widen[ReviewApi.Env]) ++
      WorkflowApi.endpoints.map(_.widen[ReviewApi.Env]) ++ DocumentClassApi.endpoints.map(
        _.widen[ReviewApi.Env]
      )
  )

  private def call(
      user: UserId,
      method: String,
      path: String,
      body: Option[String] = None,
      signedInSecondsAgo: Long = 3600
  ) =
    val url     = URL.decode(path).toOption.get
    val headers = Headers(
      Header.Authorization.Bearer(token(user, signedInSecondsAgo)),
      Header.ContentType(MediaType.application.json)
    )
    val request = method match
      case "POST" => Request.post(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case "PUT"  => Request.put(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case _      => Request.get(url).addHeaders(headers)
    ZIO.scoped(routes.runZIO(request)).flatMap(r => r.body.asString.map(b => (r.status, b)).orDie)

  private def as[A: JsonDecoder](response: (Status, String)): A =
    response._2.fromJson[A].fold(e => throw AssertionError(s"$e: ${response._1} ${response._2}"), identity)

  private def error(body: String): Option[ApiError] = body.fromJson[ApiError].toOption

  private def values(ownerId: UserId, validFrom: LocalDate, nextReview: LocalDate) =
    s"""{"owner":{"type":"PersonRef","userId":"${ownerId.value}"},
       |"nextReview":{"type":"Date","value":"$nextReview"},
       |"organizationalUnit":{"type":"Text","value":"Compliance"},
       |"validFrom":{"type":"Date","value":"$validFrom"},
       |"confidentiality":{"type":"Selection","code":"Internal"},
       |"language":{"type":"Text","value":"de"},
       |"retentionRule":{"type":"Text","value":"policy-10y"},
       |"containsPersonalData":{"type":"YesNo","value":false}}""".stripMargin

  private def today = LocalDate.now(java.time.ZoneId.of("Europe/Berlin"))

  /** The last approval vote requests the rendition (DMS-44); stand-ins render it and complete the approval.
    */
  private def renderPending: ZIO[Env, DbError, Int] =
    setup.await.flatMap(s => StubRendering.runPending(s.app))

  private def cases(doc: UUID) =
    call(author, "GET", s"/api/v1/documents/$doc/workflow").map(as[List[WorkflowCaseView]])

  private def vote(user: UserId, task: TaskView, signedInSecondsAgo: Long = 30) =
    call(
      user,
      "POST",
      s"/api/v1/workflow/tasks/${task.id}/votes",
      Some(CastVote("Approve", None).toJson),
      signedInSecondsAgo
    )

  /** Runs the publication job the way the background processing does: enqueue, then the job runner. */
  private def runPublicationJobs: ZIO[Env, Any, Int] =
    for
      db      <- ZIO.service[Db]
      service <- ZIO.service[DocumentService]
      jobs = PublicationJobs(db, service, PublicationScheduleConfig())
      _   <- jobs.enqueue(tenantId)
      ran <- JobRunner(db, jobs.handlers, WorkerConfig("publication-test")).runOnce
    yield ran

  /** Submits the open draft and approves it through the default workflow (review, approval). */
  private def approveDefault(doc: UUID): ZIO[Env, Any, Unit] =
    for
      _           <- call(author, "POST", s"/api/v1/documents/$doc/submit")
      review      <- cases(doc).map(_.last.tasks.find(_.status == "Open").get)
      afterReview <- vote(reviewer, review).map(as[WorkflowCaseView])
      _           <- vote(approver, afterReview.tasks.find(_.status == "Open").get)
      _           <- renderPending
    yield ()

  /** A document by the author whose version 1.0 is valid since ten days, due for review in 30 days (its
    * `nextReview` date shortens the class's yearly cycle), so its review task is due at once.
    */
  private def validDocument(
      ownerId: UserId = owner,
      title: String = "Code of conduct"
  ): ZIO[Env, Any, DocumentView] =
    for
      s       <- setup.await
      created <- call(
        author,
        "POST",
        "/api/v1/documents",
        Some(
          s"""{"templateId":"${s.released.value}","templateVersion":1,"title":"$title",
             |"values":${values(ownerId, today.minusDays(10), today.plusDays(30))}}""".stripMargin
        )
      ).map(as[DocumentView])
      _ <- approveDefault(created.id)
      _ <- runPublicationJobs
    yield created

  private def versions(doc: UUID, user: UserId = owner) =
    call(user, "GET", s"/api/v1/documents/$doc/versions").map(as[List[DocumentVersionView]])

  private def outbox(eventType: String): ZIO[Any, DbError, List[String]] =
    setup.await.flatMap(s =>
      s.owner.transactSystem(
        Db.sql(
          sql"select payload::text from dms.outbox_event where event_type = $eventType order by id"
            .query[String]
            .run()
            .toList
        )
      )
    )

  /** Runs the review check of the tenant the way the job does. */
  private def runReviewCheck: ZIO[Env, Any, ReviewCheck] =
    for
      db      <- ZIO.service[Db]
      reviews <- ZIO.service[ReviewService]
      checked <- ReviewJobs(db, reviews, ReviewScheduleConfig()).check(tenantId)
    yield checked

  private def tasks(user: UserId, status: String = "open") =
    call(user, "GET", s"/api/v1/reviews?status=$status").map(as[List[ReviewTaskView]])

  /** The open review task of `doc` for its owner. */
  private def taskOf(doc: DocumentView) =
    tasks(owner).map(_.find(_.documentId == doc.id).getOrElse(throw AssertionError(s"no task for ${doc.id}")))

  private def decide(user: UserId, task: ReviewTaskView, action: String, body: String) =
    call(user, "POST", s"/api/v1/reviews/${task.id}/$action", Some(body))

  // ---- notifications (DMS-141, ADR 0057) --------------------------------------------------------------------

  private val zone = java.time.ZoneId.of("Europe/Berlin")

  /** Runs `effect` with the clock at `at` plus a minute. Tokens are still checked against the real time. */
  private def at[R, E, A](instant: Instant)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.withClock(
      Clock.ClockJava(java.time.Clock.fixed(instant.plusSeconds(60), java.time.ZoneOffset.UTC))
    )(effect)

  /** One running instance of the platform as far as notices go: everything it holds in memory is new, so
    * calling this again stands for a restart or a second instance; only `sent` is the outside world.
    */
  private final class Instance(val deadlines: DeadlineJobs, delivery: NotificationJobs, db: Db, name: String):
    private val relay  = OutboxRelay(db, delivery.deliver, WorkerConfig(s"$name-outbox"))
    private val runner = JobRunner(db, delivery.handlers, WorkerConfig(s"$name-jobs"))

    /** Relays events and runs delivery jobs until nothing is left. */
    def drain: IO[DbError, Unit] =
      (relay.runOnce <*> runner.runOnce).repeatUntil((events, jobs) => events == 0 && jobs == 0).unit

    def replay(event: OutboxEvent): IO[String, Unit] = delivery.deliver(event)

  private def instance(name: String, sent: Ref[List[Mail]]): ZIO[Env, Nothing, Instance] =
    for
      s         <- setup.await
      documents <- ZIO.service[DocumentService]
      reviews   <- ZIO.service[ReviewService]
      workflow  <- ZIO.service[ApprovalWorkflow]
      authz     <- ZIO.service[Authorizer]
      groups    <- ZIO.service[Groups]
      roles     <- ZIO.service[RoleAssignments]
      audit     <- ZIO.service[AuditTrail]
      directory = new RecipientDirectory:
        def people(t: TenantId, users: Set[UserId]) =
          ZIO.succeed(users.map(u => u -> Person(Some(u.value), Some(s"${u.value}@example.org"))).toMap)
      mail = new MailSender:
        def send(m: Mail) = sent.update(m :: _)
    yield Instance(
      DeadlineJobs(
        s.app,
        workflow,
        DeadlineScheduleConfig(1.day),
        List(ReviewDeadlines(reviews, documents, workflow))
      ),
      NotificationJobs(
        s.app,
        PlatformNotificationEvents(documents, roles, LegalHoldService(audit)),
        PolicyNotificationDocuments(documents, workflow, authz, roles, groups),
        directory,
        mail,
        DeliverySettings("http://app.test", emailEnabled = true)
      ),
      s.app,
      name
    )

  /** The delivered notifications of a document: recipient, kind, channel; in-app ones also with their text.
    */
  private def deliveries(doc: UUID) =
    setup.await.flatMap(s =>
      s.owner.transactSystem(
        Db.sql(
          sql"""select recipient, kind, channel from dms.notification_delivery
                where document_id = $doc and status = 'delivered' order by kind, recipient, channel"""
            .query[(String, String, String)]
            .run()
            .toList
        )
      )
    )

  private def inApp(doc: UUID) =
    setup.await.flatMap(s =>
      s.owner.transactSystem(
        Db.sql(
          sql"""select user_id, kind, subject, body, link from dms.notification
                where document_id = $doc order by created_at, kind"""
            .query[(String, String, String, String, Option[String])]
            .run()
            .toList
        )
      )
    )

  private def notices(task: UUID) =
    setup.await.flatMap(s =>
      s.owner.transactSystem(
        Db.sql(
          sql"""select kind from dms.document_review_deadline_notice where review_id = $task
                order by noticed_at, kind"""
            .query[String]
            .run()
            .toList
        )
      )
    )

  private def eventsOf(eventType: String, task: UUID) =
    setup.await.flatMap(s =>
      s.owner.transactSystem(
        Db.sql(
          sql"""select id, aggregate_id, payload::text from dms.outbox_event
                where event_type = $eventType and payload ->> 'taskId' = ${task.toString} order by id"""
            .query[(Long, UUID, String)]
            .run()
            .toList
            .map((id, aggregate, payload) =>
              OutboxEvent(id, tenantId, "document", aggregate, eventType, Jsonb(payload), None, 1)
            )
        )
      )
    )

  private def audited(action: String, doc: UUID) =
    setup.await.flatMap(s =>
      s.owner.transactSystem(
        Db.sql(
          sql"""select actor_id from dms.audit_event
                where action = $action and object_id = $doc"""
            .query[String]
            .run()
            .toList
        )
      )
    )

  /** The times of `task` under `policy`. */
  private def deadlineOf(task: ReviewTaskView, policy: DeadlinePolicy = DeadlinePolicy.default) =
    Deadlines.onDate(policy, task.dueOn, task.createdAt, zone)

  private def mailsTo(sent: Ref[List[Mail]], user: UserId, subject: String) =
    sent.get.map(_.filter(m => m.to == s"${user.value}@example.org" && m.subject.contains(subject)))

  def spec = suite("Periodic review API")(
    test("API documentation contains the review endpoints") {
      val docs = sttp.tapir.docs.openapi
        .OpenAPIDocsInterpreter()
        .toOpenAPI(ReviewApi.endpoints.map(_.endpoint), "DMS Platform", "test")
      assertTrue(
        docs.paths.pathItems.contains("/api/v1/reviews"),
        docs.paths.pathItems.contains("/api/v1/reviews/{taskId}/recertify"),
        docs.paths.pathItems.contains("/api/v1/reviews/{taskId}/revise"),
        docs.paths.pathItems.contains("/api/v1/reviews/{taskId}/withdraw")
      )
    },
    test("the owner gets the review task at the lead time and recertifies without a new version") {
      for
        doc       <- validDocument()
        checked   <- runReviewCheck
        task      <- taskOf(doc)
        forOther  <- tasks(owner2)
        badStatus <- call(owner, "GET", "/api/v1/reviews?status=later")
        byOther   <- decide(owner2, task, "recertify", "{}")
        byReader  <- decide(reader, task, "recertify", "{}")
        pastDate  <- decide(owner, task, "recertify", s"""{"nextReviewOn":"$today"}""")
        done      <- decide(owner, task, "recertify", """{"comment":"Still accurate"}""")
        twice     <- decide(owner, task, "recertify", "{}")
        open      <- tasks(owner)
        decided   <- tasks(owner, "done")
        found     <- versions(doc.id)
        audit     <- outbox("document.recertified")
      yield assertTrue(
        checked.opened >= 1,
        task.dueOn == today.plusDays(30),
        task.versionLabel == "1.0",
        task.versionStatus == "Published",
        task.status == "Open",
        task.title == "Code of conduct",
        task.classPath == List("policy"),
        task.reviewIntervalMonths == Some(12),
        !forOther.exists(_.documentId == doc.id),
        badStatus._1 == Status.BadRequest,
        byOther._1 == Status.Forbidden,
        byReader._1 == Status.Forbidden,
        pastDate._1 == Status.UnprocessableEntity,
        done._1 == Status.Ok,
        as[ReviewTaskView](done).status == "Recertified",
        as[ReviewTaskView](done).nextDueOn == Some(today.plusMonths(12)),
        as[ReviewTaskView](done).comment == Some("Still accurate"),
        as[ReviewTaskView](done).decidedBy == Some(owner.value),
        twice._1 == Status.Conflict,
        !open.exists(_.documentId == doc.id),
        decided.exists(t => t.id == task.id && t.status == "Recertified"),
        found.map(v => (v.version, v.status)) == List(("1.0", "Published")),
        audit.exists(_.contains(task.id.toString))
      )
    },
    test("revising starts a draft from the valid version; only the owner may") {
      for
        doc     <- validDocument()
        _       <- runReviewCheck
        task    <- taskOf(doc)
        byOther <- decide(owner2, task, "revise", "{}")
        badKind <- decide(owner, task, "revise", """{"changeKind":"Patch"}""")
        minor   <- decide(owner, task, "revise", """{"changeKind":"Minor"}""")
        major   <- decide(owner, task, "revise", """{"changeKind":"Major","reason":"  "}""")
        revised <- decide(owner, task, "revise", """{"reason":"New regulation"}""")
        found   <- versions(doc.id)
        view = as[ReviewTaskView](revised)
      yield assertTrue(
        byOther._1 == Status.Forbidden,
        badKind._1 == Status.UnprocessableEntity,
        // An editorial version needs a reason; nothing was changed.
        minor._1 == Status.UnprocessableEntity,
        // So does a major one (DMS-126).
        major._1 == Status.UnprocessableEntity,
        revised._1 == Status.Ok,
        view.status == "Revised",
        view.newVersionId == found.find(_.status == "Draft").map(_.id),
        found.map(v => (v.version, v.status)) == List(("1.0", "Published"), ("2.0-draft.1", "Draft"))
      )
    },
    test("withdrawing puts the version out of force; a reason is required") {
      for
        doc       <- validDocument()
        _         <- runReviewCheck
        task      <- taskOf(doc)
        noReason  <- decide(owner, task, "withdraw", """{"reason":" "}""")
        byOther   <- decide(owner2, task, "withdraw", """{"reason":"Obsolete"}""")
        withdrawn <- decide(owner, task, "withdraw", """{"reason":"Obsolete"}""")
        found     <- versions(doc.id)
        forReader <- call(reader, "GET", s"/api/v1/documents/${doc.id}")
      yield assertTrue(
        noReason._1 == Status.UnprocessableEntity,
        byOther._1 == Status.Forbidden,
        withdrawn._1 == Status.Ok,
        as[ReviewTaskView](withdrawn).status == "Withdrawn",
        as[ReviewTaskView](withdrawn).versionStatus == "Withdrawn",
        as[ReviewTaskView](withdrawn).comment == Some("Obsolete"),
        found.map(v => (v.version, v.status)) == List(("1.0", "Withdrawn")),
        forReader._1 == Status.Forbidden
      )
    },
    test("an unknown review task is not found") {
      for missing <- call(owner, "POST", s"/api/v1/reviews/${UUID.randomUUID()}/recertify", Some("{}"))
      yield assertTrue(missing._1 == Status.NotFound, error(missing._2).map(_.code) == Some("not_found"))
    },
    test("tenant administrators configure the review interval and lead time of a class") {
      for
        created <- call(
          admin,
          "POST",
          "/api/v1/admin/document-classes",
          Some(
            """{"key":"manual","label":"Manual","fields":[],"review":{"intervalMonths":24,"leadDays":30}}"""
          )
        )
        cls = as[DocumentClassView](created)
        kept <- call(
          admin,
          "POST",
          s"/api/v1/admin/document-classes/${cls.id}/versions",
          Some("""{"label":"Manual (v2)","fields":[]}""")
        )
        invalid <- call(
          admin,
          "POST",
          s"/api/v1/admin/document-classes/${cls.id}/versions",
          Some("""{"label":"Manual (v3)","fields":[],"review":{"leadDays":999}}""")
        )
        byOwner <- call(
          owner,
          "POST",
          s"/api/v1/admin/document-classes/${cls.id}/versions",
          Some("""{"label":"Manual (v3)","fields":[],"review":{"leadDays":10}}""")
        )
      yield assertTrue(
        created._1 == Status.Created,
        cls.review == ReviewSettingsView(Some(24), 30, false, Some(24), 30),
        as[DocumentClassView](kept).review == ReviewSettingsView(Some(24), 30, false, Some(24), 30),
        invalid._1 == Status.UnprocessableEntity,
        byOwner._1 == Status.Forbidden
      )
    },
    test("the owner is told in the app and by email when a review task is created, and once") {
      for
        sent  <- Ref.make(List.empty[Mail])
        first <- instance("created-1", sent)
        doc   <- validDocument(title = "Created policy")
        _     <- runReviewCheck
        // the check runs again on every instance: the task exists, nothing new is published
        _      <- runReviewCheck
        task   <- taskOf(doc)
        events <- eventsOf("document.review-task-created", task.id)
        _      <- first.drain
        // the relay delivers at least once: the same event again, and after a restart
        restarted <- instance("created-2", sent)
        _         <- first.replay(events.head)
        _         <- restarted.replay(events.head)
        _         <- restarted.drain
        delivered <- deliveries(doc.id)
        stored    <- inApp(doc.id)
        mails     <- mailsTo(sent, owner, "Überprüfung steht an: Created policy")
        _         <- decide(owner, task, "recertify", "{}")
      yield assertTrue(
        events.size == 1,
        delivered.filter(_._2 == "review.task-created") == List(
          (owner.value, "review.task-created", "email"),
          (owner.value, "review.task-created", "in-app")
        ),
        stored.filter(_._2 == "review.task-created").size == 1,
        stored.exists((_, kind, subject, body, link) =>
          kind == "review.task-created" && subject == "Überprüfung steht an: Created policy (1.0)" &&
            body.contains(
              today.plusDays(30).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
            ) &&
            link.contains("/tasks")
        ),
        mails.size == 1,
        mails.head.text.contains("http://app.test/tasks")
      )
    },
    test("a task whose revision was discarded is back in the inbox, told to the owner, and goes overdue") {
      for
        sent    <- Ref.make(List.empty[Mail])
        running <- instance("reopened", sent)
        doc     <- validDocument(title = "Reopened policy")
        _       <- runReviewCheck
        first   <- taskOf(doc)
        _       <- decide(owner, first, "revise", """{"reason":"New regulation"}""")
        gone    <- tasks(owner).map(_.filter(_.documentId == doc.id))
        _       <- call(owner, "POST", s"/api/v1/documents/${doc.id}/draft/discard", Some("{}"))
        open    <- taskOf(doc)
        decided <- tasks(owner, "done").map(_.filter(_.documentId == doc.id))
        _       <- running.drain
        stored  <- inApp(doc.id)
        mails   <- mailsTo(sent, owner, "Überprüfung wieder offen: Reopened policy")
        _       <- at(deadlineOf(open).dueAt)(instance("late", sent).flatMap(_.deadlines.check(tenantId)))
        _       <- running.drain
        late    <- notices(open.id)
        overdue <- mailsTo(sent, owner, "Überprüfung überfällig: Reopened policy")
        byOwner <- decide(owner, open, "recertify", "{}")
      yield assertTrue(
        gone.isEmpty,
        open.id != first.id,
        open.status == "Open",
        open.dueOn == first.dueOn,
        open.reopens.contains(first.id),
        decided.map(t => (t.id, t.status)) == List((first.id, "Revised")),
        stored.count(_._2 == "review.task-reopened") == 1,
        stored.exists((_, kind, _, body, link) =>
          kind == "review.task-reopened" && body.contains("verworfen") && link.contains("/tasks")
        ),
        mails.size == 1,
        late == List("overdue"),
        overdue.size == 1,
        byOwner._1 == Status.Ok
      )
    },
    test("reminder, overdue notice and escalation go out once each across instances and restarts") {
      for
        sent <- Ref.make(List.empty[Mail])
        doc  <- validDocument(title = "Staged policy")
        _    <- runReviewCheck
        task <- taskOf(doc)
        dl = deadlineOf(task)
        // three instances at once, each stage; a fresh instance afterwards is a restart
        stage = (when: Instant) =>
          at(when)(
            ZIO
              .foreachPar(List("a", "b", "c"))(n => instance(n, sent).flatMap(_.deadlines.check(tenantId)))
              .withParallelism(3)
          ) *> at(when)(instance("restarted", sent).flatMap(_.deadlines.check(tenantId))) *>
            ZIO.foreachParDiscard(List("d", "e"))(n => instance(n, sent).flatMap(_.drain))
        early     <- instance("early", sent).flatMap(_.deadlines.check(tenantId))
        noneYet   <- notices(task.id)
        _         <- stage(dl.remindAt.get)
        reminded  <- notices(task.id)
        _         <- stage(dl.dueAt)
        late      <- notices(task.id)
        _         <- stage(dl.escalateAt.get)
        _         <- stage(dl.escalateAt.get.plus(java.time.Duration.ofDays(30)))
        all       <- notices(task.id)
        soon      <- eventsOf("document.review-task-due-soon", task.id)
        overdue   <- eventsOf("document.review-task-overdue", task.id)
        escalated <- eventsOf("document.review-task-escalated", task.id)
        // the relay delivers at least once: the events again, from an instance that started later
        again     <- instance("replay", sent)
        _         <- ZIO.foreachDiscard(soon ++ overdue ++ escalated)(again.replay)
        _         <- again.drain
        delivered <- deliveries(doc.id)
        reminders <- mailsTo(sent, owner, "Überprüfung bald fällig: Staged policy")
        overdues  <- mailsTo(sent, owner, "Überprüfung überfällig: Staged policy")
        toOwner   <- mailsTo(sent, owner, "Eskalation Überprüfung: Staged policy")
        toAdmin   <- mailsTo(sent, admin, "Eskalation Überprüfung: Dokument")
        trail     <- audited("document.review-task-escalated", doc.id)
        kinds = delivered.filter(d => d._2.startsWith("review.") && d._2 != "review.task-created")
      yield assertTrue(
        early >= 0,
        noneYet.isEmpty,
        reminded == List("reminder"),
        late == List("reminder", "overdue"),
        all == List("reminder", "overdue", "escalated"),
        soon.size == 1,
        overdue.size == 1,
        escalated.size == 1,
        // no compliance officer yet, and the installation escalates to the owner only: the administrators hear
        kinds == List(
          (owner.value, "review.task-due-soon", "email"),
          (owner.value, "review.task-due-soon", "in-app"),
          (admin.value, "review.task-escalated", "email"),
          (admin.value, "review.task-escalated", "in-app"),
          (owner.value, "review.task-escalated", "email"),
          (owner.value, "review.task-escalated", "in-app"),
          (owner.value, "review.task-overdue", "email"),
          (owner.value, "review.task-overdue", "in-app")
        ),
        reminders.size == 1,
        overdues.size == 1,
        toOwner.size == 1,
        // the administrator may not read the document: no title (ADR 0052)
        toAdmin.size == 1,
        trail == List("system:review")
      )
    },
    test("two runs of the same job slot enqueue one deadline check") {
      for
        sent  <- Ref.make(List.empty[Mail])
        one   <- instance("tick-1", sent)
        two   <- instance("tick-2", sent)
        when  <- Clock.instant.map(_.plus(java.time.Duration.ofDays(400)))
        added <- at(when)(ZIO.foreachPar(List(one, two))(_.deadlines.tick))
      yield assertTrue(added.sum == 1)
    },
    test("a crash before the commit leaves no trace and the notice goes out afterwards") {
      for
        sent      <- Ref.make(List.empty[Mail])
        reviews   <- ZIO.service[ReviewService]
        documents <- ZIO.service[DocumentService]
        workflow  <- ZIO.service[ApprovalWorkflow]
        s         <- setup.await
        doc       <- validDocument()
        _         <- runReviewCheck
        task      <- taskOf(doc)
        when   = deadlineOf(task).remindAt.get
        source = ReviewDeadlines(reviews, documents, workflow)
        crashed <- at(when)(
          s.app
            .transact(tenantId)(
              source.notice(task.id.toString, DeadlineStage.Reminder, when.plusSeconds(60)) *>
                ZIO.fail(WorkflowError.Engine("instance died"))
            )
            .either
        )
        afterCrash <- notices(task.id)
        events     <- eventsOf("document.review-task-due-soon", task.id)
        _          <- at(when)(instance("after-crash", sent).flatMap(_.deadlines.check(tenantId)))
        recovered  <- notices(task.id)
        published  <- eventsOf("document.review-task-due-soon", task.id)
      yield assertTrue(
        crashed.isLeft,
        afterCrash.isEmpty,
        events.isEmpty,
        recovered == List("reminder"),
        published.size == 1
      )
    },
    test("a decided review is not reminded of; a stage missed while nothing ran is not caught up") {
      for
        sent <- Ref.make(List.empty[Mail])
        doc  <- validDocument()
        _    <- runReviewCheck
        task <- taskOf(doc)
        dl = deadlineOf(task)
        other <- validDocument()
        _     <- runReviewCheck
        open  <- taskOf(other)
        _     <- decide(owner, task, "recertify", "{}")
        // straight to the overdue stage: the reminder of the other document's task is skipped
        _       <- at(deadlineOf(open).dueAt)(instance("skip", sent).flatMap(_.deadlines.check(tenantId)))
        decided <- notices(task.id)
        skipped <- notices(open.id)
        _       <- decide(owner, open, "recertify", "{}")
      yield assertTrue(dl.remindAt.isDefined, decided.isEmpty, skipped == List("overdue"))
    },
    test("deputies are told when overdue, and escalation follows the deadline policy") {
      val officer = UserId("officer-1")
      for
        sent     <- Ref.make(List.empty[Mail])
        s        <- setup.await
        audit    <- ZIO.service[AuditTrail]
        groups   <- ZIO.service[Groups]
        roles    <- ZIO.service[RoleAssignments]
        workflow <- ZIO.service[ApprovalWorkflow]
        _        <- s.app.transact(tenantId)(
          roles.grant(officer, RoleAssignment(Role.ComplianceOfficer), admin)
        )
        deputies = Deputyships(audit, RoleDirectory(roles, groups))
        now        <- Clock.instant
        deputyship <- s.app.transact(tenantId)(
          deputies.create(
            NewDeputyship(
              owner,
              owner2,
              None,
              now.plus(java.time.Duration.ofDays(100)),
              List("Owner"),
              None,
              Some("Parental leave")
            ),
            admin
          )
        )
        doc  <- validDocument()
        _    <- runReviewCheck
        task <- taskOf(doc)
        dl = deadlineOf(task)
        _         <- at(dl.remindAt.get)(instance("policy-1", sent).flatMap(_.deadlines.check(tenantId)))
        _         <- at(dl.dueAt)(instance("policy-2", sent).flatMap(_.deadlines.check(tenantId)))
        _         <- at(dl.escalateAt.get)(instance("policy-3", sent).flatMap(_.deadlines.check(tenantId)))
        _         <- instance("policy-4", sent).flatMap(_.drain)
        delivered <- deliveries(doc.id)
        stored    <- inApp(doc.id)
        // a tenant that escalates to its administrators: the compliance officer is not the fallback then
        _ <- s.app.transact(tenantId)(
          workflow.changeDeadlineSettings(DeadlinePolicy(5, 2, 1, List(DeadlinePolicy.TenantAdmin)), admin)
        )
        second <- validDocument(owner2)
        _      <- runReviewCheck
        task2  <- tasks(owner2).map(_.find(_.documentId == second.id).get)
        dl2 = deadlineOf(task2, DeadlinePolicy(5, 2, 1, List(DeadlinePolicy.TenantAdmin)))
        _        <- at(dl2.remindAt.get)(instance("policy-5", sent).flatMap(_.deadlines.check(tenantId)))
        _        <- at(dl2.escalateAt.get)(instance("policy-6", sent).flatMap(_.deadlines.check(tenantId)))
        _        <- instance("policy-7", sent).flatMap(_.drain)
        byTenant <- deliveries(second.id)
        _        <- s.app.transact(tenantId)(workflow.resetDeadlineSettings(admin))
        _        <- s.app.transact(tenantId)(deputies.end(deputyship.id, admin))
        _        <- decide(owner, task, "recertify", "{}")
        _        <- decide(owner2, task2, "recertify", "{}")
        people = (kind: String) => delivered.filter(d => d._2 == kind && d._3 == "in-app").map(_._1)
      yield assertTrue(
        people("review.task-due-soon") == List(owner.value),
        people("review.task-overdue") == List(owner.value, owner2.value),
        people("review.task-escalated") == List(officer.value, owner.value, owner2.value),
        stored.exists((to, kind, _, body, _) =>
          to == owner2.value && kind == "review.task-overdue" &&
            body.contains(
              "Sie erhalten dies als Vertretung von owner-1; entscheiden kann nur der Eigentümer."
            )
        ),
        byTenant.filter(_._3 == "in-app").filter(_._2 == "review.task-due-soon").map(_._1) == List(
          owner2.value
        ),
        byTenant.filter(_._3 == "in-app").filter(_._2 == "review.task-escalated").map(_._1) ==
          List(admin.value, owner2.value)
      )
    },
    test("a failing deadline source does not stop the others, and the job is retried") {
      val broken = new DeadlineSource:
        def dueNotices(now: Instant) = ZIO.fail(WorkflowError.Engine("source down"))
        def notice(id: String, stage: DeadlineStage, now: Instant) = ZIO.succeed(false)
      for
        s         <- setup.await
        reviews   <- ZIO.service[ReviewService]
        documents <- ZIO.service[DocumentService]
        workflow  <- ZIO.service[ApprovalWorkflow]
        doc       <- validDocument()
        _         <- runReviewCheck
        task      <- taskOf(doc)
        jobs = DeadlineJobs(
          s.app,
          workflow,
          DeadlineScheduleConfig(),
          List(broken, ReviewDeadlines(reviews, documents, workflow))
        )
        result   <- at(deadlineOf(task).remindAt.get)(jobs.check(tenantId)).either
        reminded <- notices(task.id)
      yield assertTrue(result.left.exists(_.contains("source down")), reminded == List("reminder"))
    },
    test("deadline notices are limited to the tenant and cannot be changed by the application") {
      for
        s    <- setup.await
        doc  <- validDocument()
        _    <- runReviewCheck
        task <- taskOf(doc)
        sent <- Ref.make(List.empty[Mail])
        _    <- at(deadlineOf(task).remindAt.get)(instance("rls", sent).flatMap(_.deadlines.check(tenantId)))
        own  <- s.app.transact(tenantId)(
          Db.sql(sql"select count(*) from dms.document_review_deadline_notice".query[Long].run().head)
        )
        foreign <- s.app.transact(TenantId.random())(
          Db.sql(sql"select count(*) from dms.document_review_deadline_notice".query[Long].run().head)
        )
        deleted <- s.app
          .transact(tenantId)(
            Db.sql(sql"delete from dms.document_review_deadline_notice".update.run())
          )
          .either
      yield assertTrue(own >= 1, foreign == 0, deleted.isLeft)
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
