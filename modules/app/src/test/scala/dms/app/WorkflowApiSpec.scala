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
import dms.authz.{Authorizer, Role, RoleAssignment, RoleAssignments}
import dms.db.{Db, DbConfig, DbError, Migrations}
import dms.documents.Rendition
import dms.documents.core.*
import dms.documents.metadata.*
import dms.identity.*
import dms.kernel.{TemplateId, TenantId, UserId}
import dms.templates.*
import dms.templates.core.ContentJson.given
import dms.templates.core.{TemplateDraft, TemplateService}
import dms.workflow.core.{ApprovalWorkflow, FlowableEngine, WorkflowConfig}
import org.testcontainers.utility.DockerImageName
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
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
      AuthConfig(issuer, audience),
      ImmutableJWKSet[SecurityContext](JWKSet(signingKey.toPublicJWK))
    )
    val tenants = new TenantDirectory:
      def tenantFor(organizationId: String): IO[DbError, Option[TenantId]] =
        ZIO.succeed(Option.when(organizationId == orgId)(tenantId))
    Authenticator(verifier, tenants)
  }

  private type Env = DocumentApi.Env & WorkflowApi.Env

  private val env: ZLayer[Any, Throwable, Env] =
    ZLayer.make[Env](
      database,
      authenticator,
      RoleAssignments.postgres,
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
      ApprovalWorkflow.layer
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

  private val routes = ZioHttpInterpreter().toHttp(DocumentApi.endpoints ++ WorkflowApi.endpoints)

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

  /** A draft with all review fields and a rendition (required for approval until rendering exists). */
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
      _ <- attachRendition(created.current.id)
    yield created

  private def attachRendition(versionId: UUID): ZIO[Env, Nothing, Unit] =
    for
      s       <- setup.await
      service <- ZIO.service[DocumentService]
      _       <- s.app
        .transact(tenantId)(service.attachRendition(versionId, Rendition("r/key", None, "c" * 64), author))
        .orDieWith(e => RuntimeException(e.toString))
    yield ()

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
        approved   <- vote(approver, approval, "Approve", signedInSecondsAgo = 30).map(as[WorkflowCaseView])
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
        approved.status == "Approved",
        approved.approval.exists(a =>
          a.approvedBy == approver.value && a.version == "1.0" && a.contentSha256 == started.contentSha256
        ),
        approved.votes.map(_.decision) == List("Approve", "ApproveWithConditions", "Approve"),
        approved.votes.forall(_.contentSha256 == started.contentSha256),
        approved.votes.last.authenticatedAt.isDefined,
        version.current.status == "Approved",
        version.current.releasedBy.contains(approver.value),
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
        _           <- attachRendition(changed.id)
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
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
