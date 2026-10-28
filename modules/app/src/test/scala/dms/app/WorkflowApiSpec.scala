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
import dms.audit.core.{AuditTrailLive, TsaTrust}
import dms.authz.{Authorizer, Groups, Role, RoleAssignment, RoleAssignments}
import dms.db.{Db, DbConfig, DbError, Migrations}
import dms.documents.core.*
import dms.documents.metadata.*
import dms.identity.*
import dms.kernel.{TemplateId, TenantId, UserId}
import dms.templates.*
import dms.templates.core.ContentJson.given
import dms.templates.core.{TemplateDraft, TemplateService}
import dms.audit.AuditTrail
import dms.rendering.core.RenditionSource
import dms.workflow.core.{ApprovalWorkflow, Deputyships, FlowableEngine, WorkflowConfig}
import org.testcontainers.utility.DockerImageName
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.RichZServerEndpoint
import zio.*
import zio.http.{Body, Header, Headers, MediaType, Request, Status, URL}
import zio.json.*
import zio.test.*

import java.time.Instant
import java.util.{Date, UUID}
import javax.sql.DataSource

/** The review and approval workflow end to end (DMS-40, DMS-41, DMS-42): HTTP task endpoints, the real
  * documents module, Flowable and the audit trail against a Testcontainers database.
  */
object WorkflowApiSpec extends ZIOSpecDefault:

  private val issuer    = "http://localhost:8085"
  private val audience  = "project-1"
  private val clientId  = "web-client-1"
  private val orgId     = "org-1"
  private val tenantId  = TenantId.random()
  private val admin     = UserId("admin-1")
  private val author    = UserId("author-1")
  private val author2   = UserId("author-2")
  private val reviewer1 = UserId("reviewer-1")
  private val reviewer2 = UserId("reviewer-2")
  private val approver  = UserId("approver-1")
  private val reader    = UserId("reader-1")
  private val designer  = UserId("designer-1")
  private val designer2 = UserId("designer-2")
  private val deputy    = UserId("deputy-1")

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

  private val content = TemplateContent(
    List(
      Section(
        SectionId("parties"),
        Some("Parties"),
        locked = true,
        List(Block.Paragraph(List(Inline.Text("Between "), Inline.Placeholder("contractPartner"))))
      ),
      Section(
        SectionId("terms"),
        Some("Terms"),
        locked = false,
        List(Block.Paragraph(List(Inline.Text("Terms with "), Inline.Placeholder("contractPartner"))))
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
      _     <- Migrations.migrate(c.jdbcUrl, c.username, c.password)
      _     <- FlowableEngine.migrateSchema(c.jdbcUrl, c.username, c.password)
      owner <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, poolSize = 1)).map(Db(_))
      _     <- owner
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
            _ <- RoleAssignments.Postgres.grant(admin, RoleAssignment(Role.TenantAdmin), admin)
            _ <- RoleAssignments.Postgres.grant(author, RoleAssignment(Role.Author), admin)
            // The author also holds the reviewer role: the four-eyes rule must exclude them.
            _ <- ZIO.foreachDiscard(List(reviewer1, reviewer2, author))(u =>
              RoleAssignments.Postgres.grant(u, RoleAssignment(Role.Reviewer), admin)
            )
            _ <- RoleAssignments.Postgres.grant(approver, RoleAssignment(Role.Approver), admin)
            // Scoped to another class: not a participant for contracts.
            _ <- RoleAssignments.Postgres
              .grant(reader, RoleAssignment(Role.Approver, documentClass = Some("policy")), admin)
            _ <- RoleAssignments.Postgres.grant(reader, RoleAssignment(Role.Reader), admin)
            _ <- classes
              .createField("contractPartner", FieldDraft("Contract partner", None, FieldType.Text()), admin)
            cls <- classes.createClass(
              "contract",
              None,
              ClassDraft("Contract", None, List(FieldBinding(FieldKey.unsafe("contractPartner"), 1))),
              admin
            )
            draft = TemplateDraft("Service contract", None, cls.classId.value, 1, content)
            t <- templates.create(draft, designer)
            _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Testing, designer)
            _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Released, designer2)
          yield t.templateId
        )
        .mapError(e => RuntimeException(e.toString))
        .orDie
      _ <- setup.succeed(Setup(app, owner, released))
    yield ZEnvironment(app, appSource)
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  private val authenticator: ULayer[Authenticator] = ZLayer.succeed {
    val verifier = TokenVerifier(
      AuthConfig(issuer, audience, Some(clientId)),
      ImmutableJWKSet[SecurityContext](JWKSet(signingKey.toPublicJWK))
    )
    val tenants = new TenantDirectory:
      def tenantFor(organizationId: String): IO[DbError, Option[TenantId]] =
        ZIO.succeed(Option.when(organizationId == orgId)(tenantId))
    Authenticator(verifier, tenants)
  }

  private type Env =
    DocumentApi.Env & WorkflowApi.Env & Deputyships & RoleAssignments & Groups & RenditionSource & AuditTrail

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
      DocumentService.layer,
      FlowableEngine.layer,
      WorkflowDocuments.layer,
      RoleDirectory.layer,
      WorkflowExtraReview.layer,
      ZLayer.succeed(WorkflowConfig.default),
      WorkflowRendition.layer,
      ApprovalWorkflow.layer,
      Deputyships.layer,
      DocumentRenditions.layer
    )

  /** `signedInSecondsAgo` sets `auth_time`; by default the sign-in is one hour old. A negative value leaves
    * `auth_time` out, like ZITADEL's access tokens do (DMS-86).
    */
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
      .claim(
        "auth_time",
        if signedInSecondsAgo < 0 then null else now.minusSeconds(signedInSecondsAgo).getEpochSecond
      )
      .build()
    sign(claims)

  /** An OIDC ID token of the web client as ZITADEL issues it, bound to `accessToken` by `at_hash`. */
  private def idToken(
      user: UserId,
      accessToken: String,
      signedInSecondsAgo: Long,
      aud: String = clientId
  ): String =
    val now = Instant.now()
    sign(
      JWTClaimsSet
        .Builder()
        .issuer(issuer)
        .audience(java.util.List.of(aud, audience))
        .subject(user.value)
        .issueTime(Date.from(now.minusSeconds(10)))
        .expirationTime(Date.from(now.plusSeconds(300)))
        .claim("auth_time", now.minusSeconds(signedInSecondsAgo).getEpochSecond)
        .claim("azp", aud)
        .claim("at_hash", TokenVerifier.accessTokenHash(accessToken))
        .build()
    )

  private def sign(claims: JWTClaimsSet): String =
    val jwt = SignedJWT(
      JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID).`type`(JOSEObjectType.JWT).build(),
      claims
    )
    jwt.sign(RSASSASigner(signingKey))
    jwt.serialize()

  private val routes =
    ZioHttpInterpreter().toHttp(
      DocumentApi.endpoints.map(_.widen[Env]) ++ WorkflowApi.endpoints.map(_.widen[Env]) ++
        DeputyshipApi.endpoints.map(_.widen[Env]) ++ GroupApi.endpoints.map(_.widen[Env]) :+
        MeApi.me.widen[Env]
    )

  private def call(
      user: UserId,
      method: String,
      path: String,
      body: Option[String] = None,
      signedInSecondsAgo: Long = 3600,
      idTokenFor: Option[String => String] = None
  ) =
    val url     = URL.decode(path).toOption.get
    val access  = token(user, signedInSecondsAgo)
    val headers = Headers(
      Header.Authorization.Bearer(access),
      Header.ContentType(MediaType.application.json)
    ) ++ Headers(idTokenFor.map(f => Header.Custom(SecuredApi.IdTokenHeader, f(access))).toList)
    val request = method match
      case "POST"   => Request.post(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case "PUT"    => Request.put(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case "DELETE" => Request.delete(url).addHeaders(headers)
      case _        => Request.get(url).addHeaders(headers)
    ZIO.scoped(routes.runZIO(request)).flatMap(r => r.body.asString.map(b => (r.status, b)).orDie)

  private def as[A: JsonDecoder](response: (Status, String)): A =
    response._2.fromJson[A].fold(e => throw AssertionError(s"$e: ${response._1} ${response._2}"), identity)

  private def error(body: String): Option[ApiError] = body.fromJson[ApiError].toOption

  private def values(partner: String) =
    s""""contractPartner":{"type":"Text","value":"$partner"},
       |"owner":{"type":"PersonRef","userId":"${author.value}"},
       |"organizationalUnit":{"type":"Text","value":"Legal"},
       |"validFrom":{"type":"Date","value":"2027-01-01"},
       |"confidentiality":{"type":"Selection","code":"Internal"},
       |"language":{"type":"Text","value":"de"},
       |"retentionRule":{"type":"Text","value":"contract-10y"},
       |"containsPersonalData":{"type":"YesNo","value":false}""".stripMargin

  private val contractWorkflow =
    """{"definition":{"steps":[
      |  {"key":"review","name":"Department and compliance review","kind":"Review",
      |   "lanes":[{"key":"department","role":"Reviewer"},{"key":"compliance","role":"Reviewer"}]},
      |  {"key":"approval","name":"Approval","kind":"Approval","lanes":[{"key":"approver","role":"Approver"}]}
      |],"resetTo":"review"}}""".stripMargin

  /** A draft with all review fields. */
  private def draftDocument(partner: String = "ACME GmbH"): ZIO[Env, Nothing, DocumentView] =
    for
      s       <- setup.await
      created <- call(
        author,
        "POST",
        "/api/v1/documents",
        Some(
          s"""{"templateId":"${s.released.value}","templateVersion":1,"title":"Contract $partner",
             |"values":{${values(partner)}}}""".stripMargin
        )
      ).map(as[DocumentView])
    yield created

  private def renderPending: ZIO[Env, DbError, Int] =
    setup.await.flatMap(s => StubRendering.runPending(s.app))

  private def submit(doc: UUID) = call(author, "POST", s"/api/v1/documents/$doc/submit")

  private def cases(doc: UUID, user: UserId = author) =
    call(user, "GET", s"/api/v1/documents/$doc/workflow").map(as[List[WorkflowCaseView]])

  private def openTask(c: WorkflowCaseView, lane: String): TaskView =
    c.tasks.find(t => t.lane == lane && (t.status == "Open" || t.status == "Waiting")).get

  private def vote(
      user: UserId,
      task: TaskView,
      decision: String,
      reason: Option[String] = None,
      signedInSecondsAgo: Long = 3600
  ) =
    call(
      user,
      "POST",
      s"/api/v1/workflow/tasks/${task.id}/votes",
      Some(CastVote(decision, reason).toJson),
      signedInSecondsAgo
    )

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

  private def auditActions(prefix: String): ZIO[Any, DbError, List[(String, Option[String])]] =
    setup.await.flatMap(s =>
      s.owner.transactSystem(
        Db.sql(
          sql"select action, reason from dms.audit_event where action like ${prefix + "%"} order by seq"
            .query[(String, Option[String])]
            .run()
            .toList
        )
      )
    )

  /** Actor, on-behalf-of and reason of the latest audit entry of `action`. */
  private def lastAudit(action: String): ZIO[Any, DbError, Option[(String, Option[String], Option[String])]] =
    setup.await.flatMap(s =>
      s.owner.transactSystem(
        Db.sql(
          sql"""select actor_id, on_behalf_of, reason from dms.audit_event where action = $action
                order by seq desc limit 1"""
            .query[(String, Option[String], Option[String])]
            .run()
            .headOption
        )
      )
    )

  /** A submitted document whose review is done: its approval task is open. */
  private def awaitingApproval(partner: String): ZIO[Env, Nothing, (UUID, TaskView)] =
    for
      doc     <- draftDocument(partner)
      _       <- submit(doc.id)
      started <- cases(doc.id).map(_.head)
      // the class's latest workflow (from earlier tests) has a department lane and possibly a compliance lane
      first  <- vote(reviewer1, openTask(started, "department"), "Approve").map(as[WorkflowCaseView])
      review <- first.tasks.find(t => t.lane == "compliance" && t.status == "Open") match
        case Some(t) => vote(reviewer2, t, "Approve").map(as[WorkflowCaseView])
        case None    => ZIO.succeed(first)
    yield (doc.id, openTask(review, "approver"))

  private def deputyships(user: UserId, query: String = "") =
    call(user, "GET", s"/api/v1/deputyships$query")

  /** The locked section changed: needs a deviation request and then the extra review step. */
  private val deviating = content.copy(sections =
    content.sections.map(s =>
      if s.locked then
        s.copy(blocks =
          List(
            Block.Paragraph(
              List(
                Inline.Text("Between "),
                Inline.Placeholder("contractPartner"),
                Inline.Text(" and affiliates")
              )
            )
          )
        )
      else s
    )
  )

  def spec = suite("WorkflowApi")(
    test("API documentation can be generated") {
      val docs = sttp.tapir.docs.openapi
        .OpenAPIDocsInterpreter()
        .toOpenAPI(WorkflowApi.endpoints.map(_.endpoint), "DMS Platform", "test")
      assertTrue(
        docs.paths.pathItems.contains("/api/v1/workflow/tasks"),
        docs.paths.pathItems.contains("/api/v1/workflow/tasks/{taskId}/votes"),
        docs.paths.pathItems.contains("/api/v1/admin/workflows/{classKey}")
      )
    },
    test("tenant administrators define the workflow of a class; invalid definitions are rejected") {
      for
        byAuthor <- call(author, "POST", "/api/v1/admin/workflows/contract", Some(contractWorkflow))
        invalid  <- call(
          admin,
          "POST",
          "/api/v1/admin/workflows/contract",
          Some(
            """{"definition":{"steps":[{"key":"approval","name":"Approval","kind":"Approval",
              |"lanes":[{"key":"x","role":"Reviewer"}]}]}}""".stripMargin
          )
        )
        noReason <- call(
          admin,
          "POST",
          "/api/v1/admin/workflows/contract",
          Some(contractWorkflow.dropRight(1) + ""","fourEyes":false}""")
        )
        unknown <- call(admin, "POST", "/api/v1/admin/workflows/nope", Some(contractWorkflow))
        defined <- call(admin, "POST", "/api/v1/admin/workflows/contract", Some(contractWorkflow))
        listed  <- call(admin, "GET", "/api/v1/admin/workflows/contract").map(as[List[StoredWorkflowView]])
      yield assertTrue(
        byAuthor._1 == Status.Forbidden,
        invalid._1 == Status.UnprocessableEntity,
        error(invalid._2).exists(_.message.contains("needs the Approver role")),
        noReason._1 == Status.UnprocessableEntity,
        unknown._1 == Status.NotFound,
        defined._1 == Status.Created,
        listed.map(v => (v.version, v.fourEyes)) == List((1, true)),
        listed.head.definition.steps.map(_.key) == List("review", "approval")
      )
    },
    test("submit, parallel review, approval with step-up: the version is approved with its checksum") {
      for
        doc       <- draftDocument()
        submitted <- submit(doc.id)
        started   <- cases(doc.id).map(_.head)
        inbox1    <- call(reviewer1, "GET", "/api/v1/workflow/tasks").map(as[List[TaskView]])
        inboxA    <- call(author, "GET", "/api/v1/workflow/tasks").map(as[List[TaskView]])
        readByRev <- call(reviewer1, "GET", s"/api/v1/documents/${doc.id}")
        department = openTask(started, "department")
        compliance = openTask(started, "compliance")
        byOutsider <- vote(reader, department, "Approve")
        noReason   <- vote(reviewer1, department, "ApproveWithConditions")
        _          <- vote(reviewer1, department, "Approve").map(r => assertTrue(r._1 == Status.Ok))
        twice      <- vote(reviewer2, department, "Approve")
        afterRev   <- vote(reviewer2, compliance, "ApproveWithConditions", Some("Fix the typo in 3.1"))
          .map(as[WorkflowCaseView])
        approval = openTask(afterRev, "approver")
        inApproval <- call(author, "GET", s"/api/v1/documents/${doc.id}").map(as[DocumentView])
        stale      <- vote(approver, approval, "Approve", signedInSecondsAgo = 3600)
        rendering  <- vote(approver, approval, "Approve", signedInSecondsAgo = 30).map(as[WorkflowCaseView])
        pending    <- call(author, "GET", s"/api/v1/documents/${doc.id}").map(as[DocumentView])
        rendered   <- renderPending
        approved   <- cases(doc.id).map(_.head)
        version    <- call(author, "GET", s"/api/v1/documents/${doc.id}").map(as[DocumentView])
        events     <- outbox("workflow.approved")
      yield assertTrue(
        submitted._1 == Status.Ok,
        started.status == "Running",
        started.definitionVersion.contains(1),
        started.currentStep.contains("review"),
        started.contentSha256.matches("[0-9a-f]{64}"),
        // four-eyes: the author holds the reviewer role but is not a participant
        department.candidates == List(reviewer1.value, reviewer2.value),
        inbox1.map(_.lane).toSet == Set("department", "compliance"),
        inboxA.isEmpty,
        readByRev._1 == Status.Ok,
        byOutsider._1 == Status.Forbidden,
        noReason._1 == Status.UnprocessableEntity,
        twice._1 == Status.Conflict,
        approval.kind == "Approval",
        approval.candidates == List(approver.value),
        inApproval.current.status == "InApproval",
        stale._1 == Status.Unauthorized,
        error(stale._2).exists(_.code == "reauthentication_required"),
        // the last vote requests the archive rendition; the version stays in approval until it is attached
        rendering.status == "Rendering",
        rendering.approval.isEmpty,
        pending.current.status == "InApproval",
        rendered == 1,
        approved.status == "Approved",
        approved.approval.exists(a =>
          a.approvedBy == approver.value && a.version == "1.0" && a.contentSha256 == started.contentSha256
        ),
        approved.votes.map(_.decision) == List("Approve", "ApproveWithConditions", "Approve"),
        approved.votes.forall(_.contentSha256 == started.contentSha256),
        approved.votes.last.authenticatedAt.isDefined,
        version.current.status == "Approved",
        version.current.releasedBy.contains(approver.value),
        version.current.rendition.exists(_.sha256.matches("[0-9a-f]{64}")),
        events.exists(_.contains(doc.id.toString))
      )
    },
    test("a return keeps valid votes; a content change invalidates them and resets to the defined step") {
      for
        doc    <- draftDocument("Globex")
        _      <- submit(doc.id)
        first  <- cases(doc.id).map(_.head)
        _      <- vote(reviewer1, openTask(first, "department"), "Approve")
        review <- vote(reviewer2, openTask(first, "compliance"), "Approve").map(as[WorkflowCaseView])
        noWhy  <- vote(approver, openTask(review, "approver"), "Reject", signedInSecondsAgo = 10)
        back   <- vote(approver, openTask(review, "approver"), "Reject", Some("Wrong term"), 10)
          .map(as[WorkflowCaseView])
        draft   <- call(author, "GET", s"/api/v1/documents/${doc.id}").map(as[DocumentView])
        again   <- submit(doc.id) *> cases(doc.id).map(_.head)
        _       <- vote(approver, openTask(again, "approver"), "Reject", Some("Still wrong"), 10)
        changed <- call(
          author,
          "PUT",
          s"/api/v1/documents/${doc.id}/draft",
          Some(s"""{"values":{${values("Globex Corp")}}}""")
        )
        reset    <- submit(doc.id) *> cases(doc.id).map(_.head)
        audit    <- auditActions("workflow.re")
        returned <- outbox("workflow.returned")
      yield assertTrue(
        noWhy._1 == Status.UnprocessableEntity,
        back.status == "Returned",
        back.returnedStep.contains("approval"),
        back.tasks.forall(t => t.status == "Completed" || t.status == "Cancelled"),
        draft.current.status == "Draft",
        // unchanged content: round 2 continues at the approval step, earlier votes stay valid
        again.round == 2,
        again.currentStep.contains("approval"),
        again.votes.forall(_.invalidatedAt.isEmpty),
        changed._1 == Status.Ok,
        // changed content: round 3 restarts at the review step, all earlier votes are invalid
        reset.round == 3,
        reset.currentStep.contains("review"),
        reset.contentSha256 != first.contentSha256,
        reset.votes.nonEmpty,
        reset.votes.forall(_.invalidatedAt.isDefined),
        reset.tasks.count(t => t.round == 3 && t.status == "Open") == 2,
        audit.map(_._1).contains("workflow.resubmitted"),
        audit.map(_._1).contains("workflow.reset"),
        returned.count(_.contains(doc.id.toString)) == 2
      )
    },
    test("without an eligible participant the task waits and the tenant administrators are notified") {
      for
        running <- draftDocument("Initech")
        _       <- submit(running.id)
        // Version 2: the review lane needs the author role, which only the authors hold.
        _ <- call(
          admin,
          "POST",
          "/api/v1/admin/workflows/contract",
          Some(
            contractWorkflow.replace(
              """"role":"Reviewer"},{"key":"compliance","role":"Reviewer"}""",
              """"role":"Author"}"""
            )
          )
        ).map(r => assertTrue(r._1 == Status.Created))
        doc       <- draftDocument("Umbrella")
        submitted <- submit(doc.id)
        waiting   <- cases(doc.id).map(_.head)
        missing   <- outbox("workflow.participant-missing")
        old       <- cases(running.id).map(_.head)
        task = waiting.tasks.head
        byAuthor     <- call(author, "POST", s"/api/v1/workflow/tasks/${task.id}/participants")
        stillWaiting <- call(admin, "POST", s"/api/v1/workflow/tasks/${task.id}/participants")
          .map(as[TaskView])
        // The administrator assigns the missing role and resolves the participants again.
        s <- setup.await
        _ <- s.app
          .transact(tenantId)(RoleAssignments.Postgres.grant(author2, RoleAssignment(Role.Author), admin))
          .orDieWith(e => RuntimeException(e.toString))
        resolved <- call(admin, "POST", s"/api/v1/workflow/tasks/${task.id}/participants").map(as[TaskView])
        inbox2   <- call(author2, "GET", "/api/v1/workflow/tasks").map(as[List[TaskView]])
        // Version 3 disables the four-eyes rule (with a reason): authors may now review each other's and their own.
        noEyes <- call(
          admin,
          "POST",
          "/api/v1/admin/workflows/contract",
          Some(
            contractWorkflow
              .replace(
                """"role":"Reviewer"},{"key":"compliance","role":"Reviewer"}""",
                """"role":"Author"}"""
              )
              .dropRight(1) + ""","fourEyes":false,"reason":"Two-person team"}"""
          )
        )
        doc3  <- draftDocument("Hooli")
        _     <- submit(doc3.id)
        open3 <- cases(doc3.id).map(_.head)
        audit <- auditActions("workflow.four-eyes")
      yield assertTrue(
        submitted._1 == Status.Ok,
        waiting.definitionVersion.contains(2),
        task.status == "Waiting",
        task.candidates.isEmpty,
        missing
          .map(_.replace(" ", ""))
          .exists(p =>
            p.contains(task.id) && p.contains(s""""recipients":["${admin.value}"]""") &&
              p.contains(""""authorExcluded":true""")
          ),
        // the case started on version 1 stays on it
        old.definitionVersion.contains(1),
        old.tasks.map(_.lane).toSet == Set("department", "compliance"),
        byAuthor._1 == Status.Forbidden,
        stillWaiting.status == "Waiting",
        resolved.status == "Open",
        resolved.candidates == List(author2.value),
        inbox2.map(_.id).contains(task.id),
        noEyes._1 == Status.Created,
        !open3.fourEyes,
        openTask(open3, "department").candidates == List(author.value, author2.value),
        audit == List("workflow.four-eyes-disabled" -> Some("Two-person team"))
      )
    },
    test("the extra review step of the class runs only for versions with deviations from locked text") {
      val withLegal =
        """{"definition":{"steps":[
          |  {"key":"review","name":"Review","kind":"Review","lanes":[{"key":"department","role":"Reviewer"}]},
          |  {"key":"legal","name":"Legal review","kind":"Review","lanes":[{"key":"legal","role":"Reviewer"}],
          |   "when":"ExtraReviewRequired"},
          |  {"key":"approval","name":"Approval","kind":"Approval","lanes":[{"key":"approver","role":"Approver"}]}
          |]}}""".stripMargin
      for
        defined <- call(admin, "POST", "/api/v1/admin/workflows/contract", Some(withLegal))
        plain   <- draftDocument("Stark")
        special <- draftDocument("Wayne")
        path = s"/api/v1/documents/${special.id}"
        _ <- call(
          author,
          "POST",
          s"$path/deviation-requests",
          Some("""{"sectionId":"parties","reason":"Group"}""")
        )
        changed <- call(
          author,
          "PUT",
          s"$path/draft",
          Some(s"""{"values":{${values("Wayne")}},"content":${deviating.toJson}}""")
        ).map(as[DocumentVersionView])
        _           <- submit(plain.id) *> submit(special.id)
        plainCase   <- cases(plain.id).map(_.head)
        specialCase <- cases(special.id).map(_.head)
        plainNext   <- vote(reviewer1, openTask(plainCase, "department"), "Approve").map(as[WorkflowCaseView])
        specialNext <- vote(reviewer1, openTask(specialCase, "department"), "Approve").map(
          as[WorkflowCaseView]
        )
        report <- call(reviewer2, "GET", s"$path/versions/${changed.id}/deviations")
      yield assertTrue(
        defined._1 == Status.Created,
        changed.requiresExtraReview,
        plainNext.currentStep.contains("approval"),
        specialNext.currentStep.contains("legal"),
        openTask(specialNext, "legal").candidates == List(reviewer1.value, reviewer2.value),
        report._1 == Status.Ok
      )
    },
    test("step-up with access tokens without auth_time: the ID token's auth_time counts (DMS-86)") {
      def approve(task: TaskView, idTokenFor: Option[String => String]) =
        call(
          approver,
          "POST",
          s"/api/v1/workflow/tasks/${task.id}/votes",
          Some(CastVote("Approve", None).toJson),
          signedInSecondsAgo = -1,
          idTokenFor = idTokenFor
        )
      for
        doc    <- draftDocument("Tyrell")
        _      <- submit(doc.id)
        review <- cases(doc.id).map(_.head)
        inAppr <- vote(reviewer1, openTask(review, "department"), "Approve").map(as[WorkflowCaseView])
        task = openTask(inAppr, "approver")
        missing   <- approve(task, None)
        stale     <- approve(task, Some(idToken(approver, _, signedInSecondsAgo = 3600)))
        wrongAud  <- approve(task, Some(idToken(approver, _, signedInSecondsAgo = 10, aud = "other-client")))
        otherUser <- approve(task, Some(idToken(reviewer1, _, signedInSecondsAgo = 10)))
        fresh <- approve(task, Some(idToken(approver, _, signedInSecondsAgo = 10))).map(as[WorkflowCaseView])
        rejected = List(missing, stale, wrongAud, otherUser)
      yield assertTrue(
        inAppr.currentStep.contains("approval"),
        rejected.forall(_._1 == Status.Unauthorized),
        rejected.forall(r => error(r._2).exists(_.code == "reauthentication_required")),
        error(missing._2).exists(_.message.contains(SecuredApi.IdTokenHeader)),
        error(stale._2).exists(_.message.contains("too long ago")),
        error(wrongAud._2).exists(_.message.contains("not accepted")),
        error(otherUser._2).exists(_.message.contains("another user")),
        fresh.status == "Rendering",
        fresh.votes.last.authenticatedAt.exists(_.isAfter(Instant.now().minusSeconds(30)))
      )
    },
    test("me returns the caller's active role assignments from the tenant's role administration (DMS-96)") {
      for
        s <- setup.await
        // an expired assignment is not effective
        _ <- s.app
          .transact(tenantId)(
            RoleAssignments.Postgres.grant(
              reader,
              RoleAssignment(Role.Author, validUntil = Some(Instant.now().minusSeconds(60))),
              admin
            )
          )
          .orDieWith(e => RuntimeException(e.toString))
        me <- call(reader, "GET", "/api/v1/me").map(as[Me])
      yield assertTrue(
        me.userId == reader.value,
        me.tenantId == tenantId.value.toString,
        me.roles == List("Approver", "Reader"),
        me.assignments.map(a => (a.role, a.documentClass, a.organizationalUnit)) ==
          List(("Approver", Some("policy"), None), ("Reader", None, None)),
        me.assignments.forall(_.validUntil.isEmpty),
        me.projectRoles.isEmpty
      )
    },
    test("deputyships need the same authority and a limited period; others' only for admins (DMS-49)") {
      def create(user: UserId, json: String) = call(user, "POST", "/api/v1/deputyships", Some(json))
      val now                                = Instant.now()
      val until                              = now.plusSeconds(10 * 86400)
      for
        s <- setup.await
        _ <- s.app
          .transact(tenantId)(
            // An approver of another document class: not a participant of contracts unless as deputy.
            RoleAssignments.Postgres
              .grant(deputy, RoleAssignment(Role.Approver, documentClass = Some("policy")), admin)
          )
          .orDieWith(e => RuntimeException(e.toString))
        byOther <- create(
          reader,
          s"""{"principal":"${approver.value}","deputy":"${deputy.value}","validUntil":"$until"}"""
        )
        self   <- create(approver, s"""{"deputy":"${approver.value}","validUntil":"$until"}""")
        noRole <- create(approver, s"""{"deputy":"${reviewer1.value}","validUntil":"$until"}""")
        past   <- create(approver, s"""{"deputy":"${deputy.value}","validUntil":"${now.minusSeconds(60)}"}""")
        noEnd  <- create(approver, s"""{"deputy":"${deputy.value}"}""")
        created <- create(
          approver,
          s"""{"deputy":"${deputy.value}","validFrom":"${now.plusSeconds(86400)}","validUntil":"$until",
             |"roles":["Approver"],"reason":"Vacation"}""".stripMargin
        )
        overlap <- create(
          approver,
          s"""{"deputy":"${deputy.value}","validFrom":"${now.plusSeconds(
              2 * 86400
            )}","validUntil":"$until"}"""
        )
        ofDeputy <- deputyships(deputy).map(as[List[DeputyshipView]])
        allByApp <- deputyships(approver, "?all=true")
        allByAdm <- deputyships(admin, "?all=true").map(as[List[DeputyshipView]])
        audit    <- lastAudit("deputyship.created")
      yield assertTrue(
        byOther._1 == Status.Forbidden,
        self._1 == Status.UnprocessableEntity,
        error(self._2).exists(_.message.contains("cannot be their own deputy")),
        noRole._1 == Status.UnprocessableEntity,
        error(noRole._2).exists(_.message.contains("reviewer-1 does not hold role Approver")),
        past._1 == Status.UnprocessableEntity,
        noEnd._1 == Status.BadRequest,
        created._1 == Status.Created,
        as[DeputyshipView](created).status == "Scheduled",
        as[DeputyshipView](created).roles == List("Approver"),
        overlap._1 == Status.Conflict,
        ofDeputy.map(d => (d.principal, d.deputy, d.status)) == List(
          (approver.value, deputy.value, "Scheduled")
        ),
        allByApp._1 == Status.Forbidden,
        allByAdm.map(_.id) == ofDeputy.map(_.id),
        audit.contains((approver.value, None, Some("Vacation")))
      )
    },
    test("during the period the deputy approves on behalf of the approver; afterwards the task returns") {
      for
        s    <- setup.await
        pair <- awaitingApproval("Umbrella")
        (doc, task) = pair
        scheduled <- deputyships(deputy).map(as[List[DeputyshipView]]).map(_.head)
        before    <- call(deputy, "GET", "/api/v1/workflow/tasks").map(as[List[TaskView]])
        early     <- vote(deputy, task, "Approve", signedInSecondsAgo = 10)
        // the period starts (the history guard is bypassed only to move the test into the period)
        _ <- s.owner
          .transactSystem(Db.sql {
            val _ = sql"set local session_replication_role = replica".update.run()
            sql"""update dms.deputyship set valid_from = now() - interval '1 minute'
                  where id = ${scheduled.id}""".update.run()
          })
          .orDieWith(e => RuntimeException(e.toString))
        inbox     <- call(deputy, "GET", "/api/v1/workflow/tasks").map(as[List[TaskView]])
        ownInbox  <- call(approver, "GET", "/api/v1/workflow/tasks").map(as[List[TaskView]])
        running   <- cases(doc).map(_.head)
        read      <- call(deputy, "GET", s"/api/v1/documents/$doc")
        stale     <- vote(deputy, task, "Approve", signedInSecondsAgo = 3600)
        rendering <- vote(deputy, task, "Approve", signedInSecondsAgo = 30).map(as[WorkflowCaseView])
        _         <- renderPending
        approved  <- cases(doc).map(_.head)
        version   <- call(author, "GET", s"/api/v1/documents/$doc").map(as[DocumentView])
        voted     <- lastAudit("workflow.voted")
        approval  <- lastAudit("workflow.approved")
        events    <- outbox("workflow.approved")
        // a second document; the approver ends the deputyship before the deputy acts
        pair2 <- awaitingApproval("Soylent")
        (doc2, task2) = pair2
        during   <- call(deputy, "GET", "/api/v1/workflow/tasks").map(as[List[TaskView]])
        ended    <- call(approver, "DELETE", s"/api/v1/deputyships/${scheduled.id}")
        again    <- call(approver, "DELETE", s"/api/v1/deputyships/${scheduled.id}")
        after    <- call(deputy, "GET", "/api/v1/workflow/tasks").map(as[List[TaskView]])
        late     <- vote(deputy, task2, "Approve", signedInSecondsAgo = 10)
        back     <- call(approver, "GET", "/api/v1/workflow/tasks").map(as[List[TaskView]])
        endAudit <- lastAudit("deputyship.ended")
      yield assertTrue(
        !before.exists(_.id == task.id),
        early._1 == Status.Forbidden,
        inbox.find(_.id == task.id).exists(_.actingFor.contains(approver.value)),
        ownInbox.find(_.id == task.id).exists(_.actingFor.isEmpty),
        openTask(running, "approver").deputies == List(TaskDeputyView(deputy.value, approver.value)),
        openTask(running, "approver").candidates == List(approver.value),
        read._1 == Status.Ok,
        stale._1 == Status.Unauthorized,
        rendering.status == "Rendering",
        rendering.votes.last.voter == deputy.value,
        rendering.votes.last.actingFor.contains(approver.value),
        approved.status == "Approved",
        approved.approval.exists(a => a.approvedBy == deputy.value && a.actingFor.contains(approver.value)),
        version.current.releasedBy.contains(deputy.value),
        voted.exists(v => v._1 == deputy.value && v._2.contains(approver.value)),
        approval.exists(v => v._1 == deputy.value && v._2.contains(approver.value)),
        events.lastOption.exists(e =>
          e.contains(doc.toString) && e.contains(s""""actingFor": "${approver.value}"""")
        ),
        during.find(_.id == task2.id).exists(_.actingFor.contains(approver.value)),
        ended._1 == Status.Ok,
        as[DeputyshipView](ended).status == "Ended",
        again._1 == Status.Conflict,
        !after.exists(_.id == task2.id),
        late._1 == Status.Forbidden,
        back.exists(_.id == task2.id),
        endAudit.exists(_._1 == approver.value),
        doc2 != doc
      )
    },
    test("confidential documents: only members of the allowed groups take part, without one the task waits") {
      // DMS-101: the review and approval of a confidential version without bypassing any database guard.
      def confidential(groups: String*) =
        values("Initech").replace("\"Internal\"", "\"Confidential\"") +
          groups
            .map(g => s"""{"type":"Text","value":"$g"}""")
            .mkString(""","allowedGroups":{"type":"Many","values":[""", ",", "]}")
      val group = "/api/v1/groups"
      for
        s        <- setup.await
        byAuthor <- call(author, "POST", group, Some("""{"key":"legal-board","name":"Legal board"}"""))
        badKey   <- call(admin, "POST", group, Some("""{"key":"Legal Board","name":"Legal board"}"""))
        created  <- call(admin, "POST", group, Some("""{"key":"legal-board","name":"Legal board"}"""))
        taken    <- call(admin, "POST", group, Some("""{"key":"legal-board","name":"Other"}"""))
        joined   <- call(admin, "PUT", s"$group/legal-board/members/${reviewer1.value}")
        listed   <- call(reader, "GET", group).map(as[List[GroupView]])
        unknown  <- call(
          author,
          "POST",
          "/api/v1/documents",
          Some(
            s"""{"templateId":"${s.released.value}","templateVersion":1,"title":"Contract Initech",
               |"values":{${confidential("nope")}}}""".stripMargin
          )
        )
        draft <- call(
          author,
          "POST",
          "/api/v1/documents",
          Some(
            s"""{"templateId":"${s.released.value}","templateVersion":1,"title":"Contract Initech",
               |"values":{${confidential()}}}""".stripMargin
          )
        ).map(as[DocumentView])
        blocked <- submit(draft.id)
        _       <- call(
          author,
          "PUT",
          s"/api/v1/documents/${draft.id}/draft",
          Some(s"""{"values":{${confidential("legal-board")}}}""")
        ).map(r => assertTrue(r._1 == Status.Ok))
        submitted  <- submit(draft.id)
        started    <- cases(draft.id).map(_.head)
        inbox1     <- call(reviewer1, "GET", "/api/v1/workflow/tasks").map(as[List[TaskView]])
        inbox2     <- call(reviewer2, "GET", "/api/v1/workflow/tasks").map(as[List[TaskView]])
        readByRev1 <- call(reviewer1, "GET", s"/api/v1/documents/${draft.id}")
        readByRev2 <- call(reviewer2, "GET", s"/api/v1/documents/${draft.id}")
        me1        <- call(reviewer1, "GET", "/api/v1/me").map(as[Me])
        department = openTask(started, "department")
        byOutsider  <- vote(reviewer2, department, "Approve")
        afterReview <- vote(reviewer1, department, "Approve").map(as[WorkflowCaseView])
        waiting = openTask(afterReview, "approver")
        missing  <- outbox("workflow.participant-missing")
        _        <- call(admin, "PUT", s"$group/legal-board/members/${approver.value}")
        resolved <- call(admin, "POST", s"/api/v1/workflow/tasks/${waiting.id}/participants")
          .map(as[TaskView])
        voted     <- vote(approver, resolved, "Approve", signedInSecondsAgo = 30)
        _         <- renderPending
        approved  <- cases(draft.id).map(_.head)
        members   <- call(admin, "GET", s"$group/legal-board/members").map(as[List[GroupMemberView]])
        left      <- call(admin, "DELETE", s"$group/legal-board/members/${reviewer1.value}")
        again     <- call(admin, "DELETE", s"$group/legal-board/members/${reviewer1.value}")
        afterLeft <- call(reviewer1, "GET", s"/api/v1/documents/${draft.id}")
        audit     <- auditActions("group.")
      yield assertTrue(
        byAuthor._1 == Status.Forbidden,
        badKey._1 == Status.UnprocessableEntity,
        created._1 == Status.Created,
        taken._1 == Status.Conflict,
        joined._1 == Status.Ok,
        listed.contains(GroupView("legal-board", "Legal board")),
        unknown._1 == Status.UnprocessableEntity,
        error(unknown._2).exists(
          _.issues.exists(_.exists(i => i.field == "allowedGroups" && i.code == "unknown_option"))
        ),
        blocked._1 == Status.UnprocessableEntity,
        error(blocked._2).exists(
          _.issues.exists(_.exists(i => i.field == "allowedGroups" && i.code == "missing_required"))
        ),
        submitted._1 == Status.Ok,
        // reviewer-2 holds the role but is no member; the author is excluded by the four-eyes rule
        department.candidates == List(reviewer1.value),
        inbox1.exists(_.id == department.id),
        !inbox2.exists(_.documentId == draft.id),
        readByRev1._1 == Status.Ok,
        readByRev2._1 == Status.Forbidden,
        me1.groups == List("legal-board"),
        byOutsider._1 == Status.Forbidden,
        waiting.status == "Waiting",
        waiting.candidates.isEmpty,
        missing
          .map(_.replace(" ", ""))
          .exists(p => p.contains(waiting.id) && p.contains(""""confidential":true""")),
        resolved.status == "Open",
        resolved.candidates == List(approver.value),
        voted._1 == Status.Ok,
        approved.status == "Approved",
        members.map(m => (m.userId, m.removedAt.isEmpty)).toSet ==
          Set((reviewer1.value, true), (approver.value, true)),
        left._1 == Status.Ok,
        again._1 == Status.NotFound,
        afterLeft._1 == Status.Forbidden,
        audit.map(_._1) ==
          List("group.created", "group.member-added", "group.member-added", "group.member-removed")
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
