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
import dms.db.{Db, DbConfig, DbError, Migrations}
import dms.documents.ReviewPolicy
import dms.documents.core.*
import dms.identity.*
import dms.integration.{JobRunner, WorkerConfig}
import dms.kernel.{TemplateId, TenantId, UserId}
import dms.templates.*
import dms.templates.core.{TemplateDraft, TemplateService}
import dms.rendering.core.RenditionSource
import dms.workflow.core.{ApprovalWorkflow, FlowableEngine, WorkflowConfig}
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

  private type Env = ReviewApi.Env & DocumentClassApi.Env & WorkflowApi.Env & RenditionSource & AuditTrail

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
  private def validDocument(ownerId: UserId = owner): ZIO[Env, Any, DocumentView] =
    for
      s       <- setup.await
      created <- call(
        author,
        "POST",
        "/api/v1/documents",
        Some(
          s"""{"templateId":"${s.released.value}","templateVersion":1,"title":"Code of conduct",
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
        cls.review == ReviewSettingsView(Some(24), 30),
        as[DocumentClassView](kept).review == ReviewSettingsView(Some(24), 30),
        invalid._1 == Status.UnprocessableEntity,
        byOwner._1 == Status.Forbidden
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
