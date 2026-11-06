package dms.app

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.{GenericContainer, PostgreSQLContainer}
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
import dms.documents.{AttachmentScanner, AttachmentSettings, ScanError, ScanVerdict}
import dms.documents.core.*
import dms.documents.metadata.MetadataValue
import dms.identity.*
import dms.integration.{JobRunner, WorkerConfig}
import dms.kernel.{TemplateId, TenantId, UserId}
import dms.rendering.core.RenditionSource
import dms.storage.{ObjectStore, S3ObjectStore, StorageConfig}
import dms.templates.*
import dms.templates.core.{TemplateDraft, TemplateService}
import dms.workflow.WorkflowDefinition
import dms.workflow.core.{ApprovalWorkflow, Deputyships, FlowableEngine, WorkflowConfig}
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.services.s3.S3Client
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.RichZServerEndpoint
import zio.*
import zio.http.{Body, Header, Headers, MediaType, Request, Status, URL}
import zio.json.*
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.time.{Instant, LocalDate}
import java.util.{Date, UUID}
import javax.sql.DataSource

/** The additional approval for lowering the confidentiality of a later version (DMS-162, ADR 0083): who asks,
  * who decides, what the version and access do until it is published, and what the database refuses. Against
  * PostgreSQL and SeaweedFS started by Testcontainers.
  */
object ConfidentialityLoweringApiSpec extends ZIOSpecDefault:

  private val issuer     = "http://localhost:8085"
  private val audience   = "project-1"
  private val orgId      = "org-1"
  private val tenantId   = TenantId.random()
  private val admin      = UserId("admin-1")
  private val author     = UserId("author-1")
  private val outsider   = UserId("author-outside")
  private val member     = UserId("author-in-group")
  private val owner      = UserId("owner-1")
  private val reviewer   = UserId("reviewer-1")
  private val approver   = UserId("approver-1")
  private val accomplice = UserId("owner-2")
  private val designer   = UserId("designer-1")
  private val designer2  = UserId("designer-2")

  /** Holds the approver role but is in no access group. */
  private val uncleared = UserId("approver-outside")

  /** In the access group, holds the author and the approver role: changes a draft, then could vote on it. */
  private val editorApprover = UserId("approver-editor")

  /** In the access group, holds the owner and the approver role: asks without touching the text. */
  private val lateApprover = UserId("approver-asking")

  /** In the access group, an approver of another class only: takes part as a deputy or not at all. */
  private val standIn = UserId("approver-deputy")

  /** Small limit, so that oversized uploads are cheap to send. */
  private val maxSize = 64L * 1024

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
        SectionId("rules"),
        Some("Rules"),
        locked = false,
        List(Block.Paragraph(List(Inline.Text("Travel expenses are reimbursed within 30 days."))))
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
            _ <- RoleAssignments.Postgres.grant(admin, RoleAssignment(Role.TenantAdmin), admin)
            _ <- RoleAssignments.Postgres.grant(author, RoleAssignment(Role.Author), admin)
            _ <- RoleAssignments.Postgres.grant(outsider, RoleAssignment(Role.Author), admin)
            _ <- RoleAssignments.Postgres.grant(member, RoleAssignment(Role.Author), admin)
            _ <- RoleAssignments.Postgres.grant(owner, RoleAssignment(Role.Owner), admin)
            _ <- RoleAssignments.Postgres.grant(reviewer, RoleAssignment(Role.Reviewer), admin)
            _ <- RoleAssignments.Postgres.grant(approver, RoleAssignment(Role.Approver), admin)
            _ <- RoleAssignments.Postgres.grant(accomplice, RoleAssignment(Role.Owner), admin)
            _ <- RoleAssignments.Postgres.grant(uncleared, RoleAssignment(Role.Approver), admin)
            _ <- RoleAssignments.Postgres.grant(editorApprover, RoleAssignment(Role.Author), admin)
            _ <- RoleAssignments.Postgres.grant(editorApprover, RoleAssignment(Role.Approver), admin)
            _ <- RoleAssignments.Postgres.grant(lateApprover, RoleAssignment(Role.Owner), admin)
            _ <- RoleAssignments.Postgres.grant(lateApprover, RoleAssignment(Role.Approver), admin)
            _ <- RoleAssignments.Postgres
              .grant(standIn, RoleAssignment(Role.Approver, documentClass = Some("contract")), admin)
            _ <- Groups.Postgres.create("board", "Board", admin)
            _ <- Groups.Postgres.create("works-council", "Works council", admin)
            _ <- ZIO.foreachDiscard(List(member, reviewer, approver, editorApprover, lateApprover, standIn))(
              Groups.Postgres.addMember("board", _, admin)
            )
            cls <- classes.createClass("policy", None, ClassDraft("Policy", None, Nil), admin)
            _   <- RetentionFixtures.assign(classes, cls.classId, "policy-10y")
            draft = TemplateDraft("Travel policy", None, cls.classId.value, 1, content)
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

  /** Same image as compose.yaml. */
  private val seaweedImage =
    "chrislusf/seaweedfs:4.51@sha256:f7a49e3f3972ebed5be2ed4befa4a0c68eb7bdddba6e692d42729ceb545134b8"

  private val objectStore: ZLayer[Any, Throwable, ObjectStore] =
    val container = ZLayer.scoped(
      ZIO.acquireRelease(ZIO.attemptBlocking {
        val c = GenericContainer(
          dockerImage = seaweedImage,
          exposedPorts = Seq(8333),
          env = Map("AWS_ACCESS_KEY_ID" -> "dms-test", "AWS_SECRET_ACCESS_KEY" -> "dms-test-secret"),
          command = Seq("mini", "-dir=/data"),
          waitStrategy = Wait.forLogMessage(".*All enabled components are running.*", 1)
        )
        c.start()
        c
      })(c => ZIO.attemptBlocking(c.stop()).orDie)
    )
    val config = ZLayer.fromFunction((c: GenericContainer) =>
      StorageConfig(
        s"http://${c.host}:${c.mappedPort(8333)}",
        "us-east-1",
        "dms-test",
        "dms-test",
        "dms-test-secret"
      )
    )
    (container >>> config >>> (ObjectStore.client ++ ZLayer.service[StorageConfig])) >>> ZLayer.fromZIO(
      for
        _      <- ObjectStore.bucketSetup.mapError(e => RuntimeException(s"bucket setup failed: $e"))
        s3     <- ZIO.service[S3Client]
        bucket <- ZIO.serviceWith[StorageConfig](_.bucket)
      yield S3ObjectStore(s3, bucket): ObjectStore
    )

  private object NoScanner extends AttachmentScanner:
    val name: String                                             = "test-scanner"
    def scan(file: Path, size: Long): IO[ScanError, ScanVerdict] = ZIO.succeed(ScanVerdict.Clean)

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

  private type Env =
    AttachmentApi.Env & WorkflowApi.Env & RenditionSource & AuditTrail & ObjectStore & Deputyships

  private val env: ZLayer[Any, Throwable, Env] =
    ZLayer.make[Env](
      database,
      authenticator,
      objectStore,
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
      DocumentRenditions.layer,
      ZLayer.succeed[AttachmentScanner](NoScanner),
      ZLayer.succeed(AttachmentSettings(maxSize, AttachmentSettings.DefaultBlockedExtensions)),
      AttachmentService.layer,
      AttachmentUploads.layer
    )

  private def token(user: UserId): String =
    val now    = Instant.now()
    val claims = JWTClaimsSet
      .Builder()
      .issuer(issuer)
      .audience(audience)
      .subject(user.value)
      .issueTime(Date.from(now.minusSeconds(10)))
      .expirationTime(Date.from(now.plusSeconds(300)))
      .claim(TokenVerifier.OrganizationClaim, orgId)
      .claim("auth_time", now.minusSeconds(30).getEpochSecond)
      .build()
    val jwt = SignedJWT(
      JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID).`type`(JOSEObjectType.JWT).build(),
      claims
    )
    jwt.sign(RSASSASigner(signingKey))
    jwt.serialize()

  private val routes =
    ZioHttpInterpreter().toHttp(
      DocumentApi.endpoints.map(_.widen[Env]) ++ WorkflowApi.endpoints.map(_.widen[Env]) ++
        ConfidentialityLoweringApi.endpoints.map(_.widen[Env]) ++
        AttachmentApi.endpoints.map(_.widen[Env]) ++ DeputyshipApi.endpoints.map(_.widen[Env])
    )

  final case class Response(status: Status, headers: Headers, body: Chunk[Byte]):
    def text: String                         = String(body.toArray, UTF_8)
    def header(name: String): Option[String] = headers.get(name)
    def error: Option[String]                = text.fromJson[ApiError].toOption.map(_.code)

  private def send(user: UserId, request: Request): ZIO[Env, Nothing, Response] =
    ZIO
      .scoped(routes.runZIO(request.addHeader(Header.Authorization.Bearer(token(user)))))
      .flatMap(r => r.body.asChunk.map(b => Response(r.status, r.headers, b)).orDie)

  private def call(user: UserId, method: String, path: String, body: Option[String] = None) =
    val url     = URL.decode(path).toOption.get
    val json    = Headers(Header.ContentType(MediaType.application.json))
    val request = method match
      case "POST"   => Request.post(url, Body.fromString(body.getOrElse("{}"))).addHeaders(json)
      case "PUT"    => Request.put(url, Body.fromString(body.getOrElse("{}"))).addHeaders(json)
      case "PATCH"  => Request.patch(url, Body.fromString(body.getOrElse("{}"))).addHeaders(json)
      case "DELETE" => Request.delete(url)
      case _        => Request.get(url)
    send(user, request)

  private def as[A: JsonDecoder](response: Response): A =
    response.text
      .fromJson[A]
      .fold(e => throw AssertionError(s"$e: ${response.status} ${response.text}"), identity)

  private val past   = LocalDate.of(2026, 1, 1)
  private val future = LocalDate.now().plusYears(2)

  private def values(
      level: Option[String] = Some("Confidential"),
      groups: List[String] = List("board"),
      ownerId: UserId = owner,
      validFrom: LocalDate = past
  ) =
    val confidentiality = level.fold("")(l => s""""confidentiality":{"type":"Selection","code":"$l"},""")
    val allowed         = groups.map(g => s"""{"type":"Text","value":"$g"}""").mkString(",")
    val access = if groups.isEmpty then "" else s""""allowedGroups":{"type":"Many","values":[$allowed]},"""
    s"""{"owner":{"type":"PersonRef","userId":"${ownerId.value}"},
       |"organizationalUnit":{"type":"Text","value":"Finance"},
       |"validFrom":{"type":"Date","value":"$validFrom"},
       |$confidentiality$access
       |"language":{"type":"Text","value":"en"},
       |"retentionRule":{"type":"Text","value":"policy-10y"},
       |"containsPersonalData":{"type":"YesNo","value":false}}""".stripMargin

  private def draft(body: String = values()): ZIO[Env, Any, DocumentView] =
    for
      s       <- setup.await
      created <- call(
        author,
        "POST",
        "/api/v1/documents",
        Some(
          s"""{"templateId":"${s.released.value}","templateVersion":1,"title":"Board minutes",
             |"values":$body}""".stripMargin
        )
      ).map(as[DocumentView])
    yield created

  private def cases(doc: UUID) =
    call(owner, "GET", s"/api/v1/documents/$doc/workflow").map(as[List[WorkflowCaseView]])

  private def vote(user: UserId, task: TaskView) =
    call(user, "POST", s"/api/v1/workflow/tasks/${task.id}/votes", Some(CastVote("Approve", None).toJson))

  /** Submits the open version and takes it through review and approval; rendered with stand-ins. */
  private def approve(doc: UUID, submitter: UserId): ZIO[Env, Any, Unit] =
    for
      s           <- setup.await
      _           <- call(submitter, "POST", s"/api/v1/documents/$doc/submit")
      review      <- cases(doc).map(_.last.tasks.find(_.status == "Open").get)
      afterReview <- vote(reviewer, review).map(as[WorkflowCaseView])
      _           <- vote(approver, afterReview.tasks.find(_.status == "Open").get)
      _           <- StubRendering.runPending(s.app)
    yield ()

  private def renderPending: ZIO[Env, Any, Unit] =
    setup.await.flatMap(s => StubRendering.runPending(s.app)).unit

  /** Runs the publication jobs that are due. */
  private def publishDue: ZIO[Env, Any, Unit] =
    for
      db      <- ZIO.service[Db]
      service <- ZIO.service[DocumentService]
      jobs = PublicationJobs(db, service, PublicationScheduleConfig())
      _ <- jobs.enqueue(tenantId)
      _ <- JobRunner(db, jobs.handlers, WorkerConfig("publication-test")).runOnce
    yield ()

  private def published(body: String = values()): ZIO[Env, Any, DocumentView] =
    for
      doc <- draft(body)
      _   <- approve(doc.id, author)
      _   <- publishDue
    yield doc

  private def startVersion(user: UserId, doc: UUID, kind: String = "Major") =
    call(
      user,
      "POST",
      s"/api/v1/documents/$doc/versions",
      Some(s"""{"changeKind":"$kind","reason":"Next year's figures"}""")
    )

  private def save(user: UserId, doc: UUID, body: String, title: String = "Board minutes") =
    call(user, "PUT", s"/api/v1/documents/$doc/draft", Some(s"""{"title":"$title","values":$body}"""))

  private def document(user: UserId, doc: UUID) =
    call(user, "GET", s"/api/v1/documents/$doc").map(as[DocumentView])

  private def ownerQuery[A](f: DbTx ?=> A): ZIO[Any, DbError, A] =
    setup.await.flatMap(s => s.owner.transactSystem(Db.sql(f)))

  private def auditEntries(doc: UUID) =
    ownerQuery(
      sql"""select action, actor_id, reason, details::text from dms.audit_event
            where object_id = $doc order by seq"""
        .query[(String, String, Option[String], String)]
        .run()
        .toList
    )

  private def outbox(doc: UUID) =
    ownerQuery(
      sql"""select event_type, payload::text from dms.outbox_event where aggregate_id = $doc order by id"""
        .query[(String, String)]
        .run()
        .toList
    )

  private def issues(response: Response) = as[ApiError](response).issues.toList.flatten

  private def ask(
      user: UserId,
      doc: UUID,
      level: String = "Internal",
      reason: String = "No longer sensitive"
  ) =
    call(
      user,
      "POST",
      s"/api/v1/documents/$doc/confidentiality-lowering",
      Some(RequestLowering(level, reason).toJson)
    )

  private def state(user: UserId, doc: UUID, version: UUID) =
    call(user, "GET", s"/api/v1/documents/$doc/versions/$version/confidentiality-lowering")

  private def later(body: String = values()) =
    for
      doc   <- published(body)
      draft <- startVersion(member, doc.id).map(as[DocumentVersionView])
    yield (doc, draft)

  /** A later version in the additional approval step, past review and the regular approval. */
  private def inLoweringStep(body: String = values(), asker: UserId = member) =
    for
      pair <- later(body)
      (doc, draft) = pair
      _    <- ask(asker, doc.id)
      task <- passRegularSteps(doc.id, member)
    yield (doc, draft, task)

  private def passRegularSteps(doc: UUID, submitter: UserId) =
    for
      _ <- call(submitter, "POST", s"/api/v1/documents/$doc/submit").map(r => assert(r.status == Status.Ok))
      review <- cases(doc).map(_.last.tasks.find(_.status == "Open").get)
      after  <- vote(reviewer, review).map(as[WorkflowCaseView])
      next   <- vote(approver, after.tasks.find(_.status == "Open").get).map(as[WorkflowCaseView])
    yield next.tasks.find(_.status == "Open").get

  /** The versions `user` may read of a document, by id. */
  private def readable(user: UserId, doc: UUID) =
    call(user, "GET", s"/api/v1/documents/$doc/versions").map(r =>
      if r.status == Status.Ok then as[List[DocumentVersionView]](r).map(v => v.id -> v).toMap
      else Map.empty[UUID, DocumentVersionView]
    )

  private def level(v: DocumentVersionView) =
    v.values.get("confidentiality").map(_.value)

  private def selection(code: String) = Some(MetadataValue.Selection(code))

  def spec = suite("Lowering the confidentiality of a later version")(
    suite("Asking")(
      test("the draft stays at its level, and asking counts as a change") {
        for
          pair <- later()
          (doc, draft) = pair
          response <- ask(owner, doc.id)
          view = as[LoweringsView](response)
          after <- document(owner, doc.id)
          audit <- auditEntries(doc.id)
          sent  <- outbox(doc.id)
          read  <- call(outsider, "GET", s"/api/v1/documents/${doc.id}")
        yield assertTrue(
          response.status == Status.Created,
          view.level == "Confidential",
          view.accessLevel == "Confidential",
          view.requests.map(r => (r.from, r.to, r.status)) == List(("Confidential", "Internal", "Requested")),
          !view.canRequest,
          level(after.current) == selection("Confidential"),
          after.current.editors.contains(owner.value),
          after.current.id == draft.id,
          read.status == Status.Forbidden,
          audit.exists((action, actor, reason, details) =>
            action == "document.confidentiality-lowering-requested" && actor == owner.value &&
              reason.contains("No longer sensitive") && details.contains("\"from\": \"Confidential\"") &&
              details.contains("\"to\": \"Internal\"")
          ),
          sent.exists((event, payload) =>
            event == "document.confidentiality-lowering-requested" && payload.contains(member.value) &&
              !payload.contains("No longer sensitive") && !payload.contains("Internal")
          )
        )
      },
      test("saving below the basis stays refused, also with an open request") {
        for
          pair <- later()
          (doc, _) = pair
          _       <- ask(member, doc.id)
          lowered <- save(member, doc.id, values(level = Some("Internal"), groups = Nil))
          byOwner <- save(owner, doc.id, values(level = Some("Internal"), groups = Nil))
          same    <- save(member, doc.id, values())
        yield assertTrue(
          issues(lowered).map(_.code) == List("confidentiality_lowered"),
          byOwner.status == Status.UnprocessableEntity,
          same.status == Status.Ok
        )
      },
      test("a first version, a level that needs no approval and an empty reason are refused") {
        for
          first <- draft()
          free  <- ask(author, first.id)
          pair  <- later()
          (doc, _) = pair
          same   <- ask(member, doc.id, "Confidential")
          higher <- ask(member, doc.id, "StrictlyConfidential")
          blank  <- ask(member, doc.id, reason = "  ")
          bogus  <- ask(member, doc.id, "Nothing")
        yield assertTrue(
          List(free, same, higher, blank, bogus).forall(_.status == Status.UnprocessableEntity),
          List(free, same, higher, blank, bogus).forall(_.error.contains("invalid"))
        )
      },
      test("one open request at a time; asking needs the right to edit the draft") {
        for
          pair <- later()
          (doc, _) = pair
          foreign <- ask(outsider, doc.id)
          reader  <- ask(reviewer, doc.id)
          first   <- ask(member, doc.id)
          second  <- ask(owner, doc.id)
        yield assertTrue(
          foreign.status == Status.Forbidden,
          reader.status == Status.Forbidden,
          first.status == Status.Created,
          second.status == Status.Conflict
        )
      },
      test("a withdrawn request leaves no step, and the version may ask again") {
        for
          pair <- later()
          (doc, draft) = pair
          _     <- ask(member, doc.id)
          gone  <- call(owner, "POST", s"/api/v1/documents/${doc.id}/confidentiality-lowering/withdraw")
          twice <- call(owner, "POST", s"/api/v1/documents/${doc.id}/confidentiality-lowering/withdraw")
          view  <- state(member, doc.id, draft.id).map(as[LoweringsView])
          _     <- approve(doc.id, member)
          all   <- cases(doc.id)
        yield assertTrue(
          gone.status == Status.Ok,
          twice.status == Status.Conflict,
          view.requests.map(_.status) == List("Withdrawn"),
          view.canRequest,
          !all.last.tasks.exists(_.step == WorkflowDefinition.LoweringStep),
          all.last.status == "Approved"
        )
      }
    ),
    suite("Deciding")(
      test("the step comes last, for approvers cleared for the old level who did not change the version") {
        for
          triple <- inLoweringStep()
          (doc, draft, task) = triple
          current <- readable(member, doc.id).map(_(draft.id))
        yield assertTrue(
          task.step == WorkflowDefinition.LoweringStep,
          task.kind == "Approval",
          task.role == "Approver",
          task.candidates.contains(approver.value),
          !task.candidates.contains(uncleared.value),
          current.status == "InApproval",
          level(current) == selection("Confidential")
        )
      },
      test("an editor who holds the approver role cannot decide, whoever they are in the group") {
        for
          pair <- later()
          (doc, _) = pair
          _      <- save(editorApprover, doc.id, values())
          _      <- ask(member, doc.id)
          task   <- passRegularSteps(doc.id, member)
          direct <- vote(editorApprover, task)
          stored <- cases(doc.id)
        yield assertTrue(
          !task.candidates.contains(editorApprover.value),
          direct.status == Status.Forbidden,
          direct.error.contains("segregation_of_duties"),
          stored.last.tasks.find(_.id == task.id).exists(_.status == "Open")
        )
      },
      test("whoever asked cannot decide, even as an approver who never touched the text") {
        for
          triple <- inLoweringStep(asker = lateApprover)
          (_, _, task) = triple
          response <- vote(lateApprover, task)
        yield assertTrue(
          !task.candidates.contains(lateApprover.value),
          response.status == Status.Forbidden,
          response.error.contains("segregation_of_duties")
        )
      },
      test("an approver outside the access groups is no candidate and sees nothing") {
        for
          triple <- inLoweringStep()
          (doc, draft, task) = triple
          voted <- vote(uncleared, task)
          read  <- readable(uncleared, doc.id)
          asked <- state(uncleared, doc.id, draft.id)
        yield assertTrue(
          voted.status == Status.Forbidden,
          read.isEmpty,
          asked.status == Status.Forbidden,
          !asked.text.contains("No longer sensitive")
        )
      },
      test("a deputy decides for a candidate if cleared; a deputy outside the groups does not") {
        val now = Instant.now()
        for
          triple <- inLoweringStep()
          (doc, _, task) = triple
          _ <- call(
            approver,
            "POST",
            "/api/v1/deputyships",
            Some(
              s"""{"deputy":"${uncleared.value}","validUntil":"${now.plusSeconds(3600)}",
                 |"roles":["Approver"]}""".stripMargin
            )
          ).map(r => assert(r.status == Status.Created, r.text))
          _ <- call(
            approver,
            "POST",
            "/api/v1/deputyships",
            Some(
              s"""{"deputy":"${standIn.value}","validUntil":"${now.plusSeconds(3600)}",
                 |"roles":["Approver"]}""".stripMargin
            )
          ).map(r => assert(r.status == Status.Created, r.text))
          refused <- vote(uncleared, task)
          decided <- vote(standIn, task)
          audit   <- auditEntries(doc.id)
        yield assertTrue(
          refused.status == Status.Forbidden,
          decided.status == Status.Ok,
          audit.exists((action, actor, _, details) =>
            action == "document.confidentiality-lowering-approved" && actor == standIn.value &&
              details.contains(s"\"actingFor\": \"${approver.value}\"")
          )
        )
      },
      test(
        "refusing returns the draft at its level; asked for nothing more, it goes on from where it stood"
      ) {
        for
          triple <- inLoweringStep()
          (doc, draft, task) = triple
          refused <- call(
            approver,
            "POST",
            s"/api/v1/workflow/tasks/${task.id}/votes",
            Some(CastVote("Reject", Some("Still sensitive")).toJson)
          )
          view  <- document(member, doc.id)
          state <- state(member, doc.id, draft.id).map(as[LoweringsView])
          audit <- auditEntries(doc.id)
          sent  <- outbox(doc.id)
          _     <- call(member, "POST", s"/api/v1/documents/${doc.id}/submit")
          next  <- cases(doc.id).map(_.last)
          open = next.tasks.filter(_.status == "Open")
          _    <- vote(approver, open.head)
          _    <- renderPending
          _    <- publishDue
          done <- document(member, doc.id)
        yield assertTrue(
          refused.status == Status.Ok,
          view.current.status == "Draft",
          level(view.current) == selection("Confidential"),
          state.requests.map(r => (r.status, r.decisionReason)) == List(("Refused", Some("Still sensitive"))),
          state.canRequest,
          audit.exists((action, actor, reason, details) =>
            action == "document.confidentiality-lowering-refused" && actor == approver.value &&
              reason.contains("No longer sensitive") && details.contains("Still sensitive")
          ),
          sent.exists((event, payload) =>
            event == "document.confidentiality-lowering-decided" && payload.contains("Refused") &&
              payload.contains(member.value) && !payload.contains("Still sensitive")
          ),
          !next.tasks.exists(t => t.step == WorkflowDefinition.LoweringStep && t.status == "Open"),
          open.map(_.step) == List("approval"),
          done.current.status == "Published",
          level(done.current) == selection("Confidential")
        )
      },
      test("a return before the step leaves the request open, and asking after a return adds the step") {
        for
          pair <- later()
          (doc, _) = pair
          _      <- call(member, "POST", s"/api/v1/documents/${doc.id}/submit")
          review <- cases(doc.id).map(_.last.tasks.find(_.status == "Open").get)
          _      <- call(
            reviewer,
            "POST",
            s"/api/v1/workflow/tasks/${review.id}/votes",
            Some(CastVote("RequestChanges", Some("Update the figures")).toJson)
          )
          _    <- ask(member, doc.id)
          task <- passRegularSteps(doc.id, member)
        yield assertTrue(task.step == WorkflowDefinition.LoweringStep)
      },
      test("a minor version gets the step after the owner's confirmation") {
        for
          doc <- published()
          _   <- startVersion(member, doc.id, "Minor")
          _   <- ask(member, doc.id)
          _   <- call(member, "POST", s"/api/v1/documents/${doc.id}/submit")
          all <- cases(doc.id)
          confirm = all.last.tasks.find(_.status == "Open").get
          next <- vote(owner, confirm).map(as[WorkflowCaseView])
        yield assertTrue(
          confirm.step == "editorial-confirmation",
          next.tasks.find(_.status == "Open").map(_.step).contains(WorkflowDefinition.LoweringStep)
        )
      }
    ),
    suite("Taking effect")(
      test("only with the publication of this version, and never for the versions before it") {
        for
          doc      <- published()
          valid    <- document(owner, doc.id)
          draft    <- startVersion(member, doc.id).map(as[DocumentVersionView])
          _        <- save(member, doc.id, values(validFrom = LocalDate.now()))
          _        <- ask(member, doc.id)
          task     <- passRegularSteps(doc.id, member)
          decided  <- vote(approver, task)
          stated   <- readable(member, doc.id).map(_(draft.id))
          waiting  <- state(member, doc.id, draft.id).map(as[LoweringsView])
          early    <- readable(outsider, doc.id)
          _        <- renderPending
          approved <- readable(outsider, doc.id)
          _        <- publishDue
          outside  <- readable(outsider, doc.id)
          inForce  <- state(member, doc.id, draft.id).map(as[LoweringsView])
          audit    <- auditEntries(doc.id)
          sent     <- outbox(doc.id)
          case_    <- cases(doc.id).map(_.last)
        yield assertTrue(
          decided.status == Status.Ok,
          level(stated) == selection("Internal"),
          waiting.level == "Internal",
          waiting.accessLevel == "Confidential",
          !early.contains(draft.id),
          !approved.contains(draft.id),
          outside.contains(draft.id),
          !outside.contains(valid.current.id),
          inForce.accessLevel == "Internal",
          case_.status == "Approved",
          case_.approval.exists(_.contentSha256 == case_.contentSha256),
          audit.exists((action, _, reason, details) =>
            action == "document.confidentiality-lowered" && reason.contains("No longer sensitive") &&
              details.contains("\"from\": \"Confidential\"") && details.contains("\"to\": \"Internal\"") &&
              details.contains(approver.value) && details.contains(member.value)
          ),
          sent.exists((event, _) => event == "document.confidentiality-lowered")
        )
      },
      test("an approved version waiting for its date keeps the old level, and a withdrawn one for good") {
        for
          pair <- later()
          (doc, draft) = pair
          _       <- save(member, doc.id, values(validFrom = future))
          _       <- ask(member, doc.id)
          task    <- passRegularSteps(doc.id, member)
          _       <- vote(approver, task)
          _       <- renderPending
          _       <- publishDue
          waiting <- readable(owner, doc.id).map(_(draft.id))
          outside <- readable(outsider, doc.id)
          gone    <- call(
            owner,
            "POST",
            s"/api/v1/documents/${doc.id}/versions/${draft.id}/withdraw",
            Some("""{"reason":"Stopped"}""")
          )
          after <- readable(outsider, doc.id)
          next  <- startVersion(member, doc.id).map(as[DocumentVersionView])
          below <- save(member, doc.id, values(level = Some("Internal"), groups = Nil))
        yield assertTrue(
          waiting.status == "Approved",
          level(waiting) == selection("Internal"),
          !outside.contains(draft.id),
          gone.status == Status.Ok,
          !after.contains(draft.id),
          // The next version starts from what governed access, not from what never took effect.
          level(next) == selection("Confidential"),
          below.status == Status.UnprocessableEntity
        )
      },
      test("a reason written at the old level stays with those cleared for it") {
        for
          triple <- inLoweringStep()
          (doc, draft, task) = triple
          _       <- vote(approver, task)
          _       <- renderPending
          _       <- publishDue
          cleared <- state(member, doc.id, draft.id).map(as[LoweringsView])
          reader  <- state(outsider, doc.id, draft.id)
        yield assertTrue(
          cleared.requests.map(_.reason) == List("No longer sensitive"),
          reader.status == Status.Ok,
          as[LoweringsView](reader).requests.isEmpty,
          !reader.text.contains("No longer sensitive")
        )
      }
    ),
    suite("Owner at publication")(
      test("the owner who loses a document is told") {
        for
          pair <- later()
          (doc, _) = pair
          _    <- save(member, doc.id, values(ownerId = accomplice))
          _    <- approve(doc.id, member)
          _    <- publishDue
          sent <- outbox(doc.id)
        yield assertTrue(
          sent.exists((event, payload) =>
            event == DocumentService.OwnerReplaced && payload.contains(
              s"\"recipients\": [\"${owner.value}\"]"
            ) &&
              payload.contains(accomplice.value)
          )
        )
      },
      test("nobody is told if the owner stays") {
        for
          pair <- later()
          (doc, _) = pair
          _    <- save(member, doc.id, values(), "Renamed")
          _    <- approve(doc.id, member)
          _    <- publishDue
          sent <- outbox(doc.id)
        yield assertTrue(!sent.exists(_._1 == DocumentService.OwnerReplaced))
      }
    ),
    suite("The database")(
      test("no other change of a version in approval passes, for the application role either") {
        for
          triple <- inLoweringStep()
          (_, draft, _) = triple
          s       <- setup.await
          changed <- s.app
            .transact(tenantId)(
              Db.sql(
                sql"""update dms.document_version
                      set field_values = jsonb_set(field_values, '{confidentiality,value,code}', '"Public"')
                      where id = ${draft.id}""".update.run()
              )
            )
            .either
        yield assertTrue(changed.isLeft)
      },
      test("an approved request allows the level asked for, and nothing else with it") {
        for
          triple <- inLoweringStep()
          (_, draft, _) = triple
          s     <- setup.await
          other <- s.app
            .transact(tenantId)(
              Db.sql {
                val _ = sql"""update dms.document_confidentiality_lowering
                      set status = 'Approved', decided_by = ${approver.value}, decided_at = now()
                      where version_id = ${draft.id}""".update.run()
                sql"""update dms.document_version
                      set field_values = jsonb_set(field_values, '{confidentiality,value,code}', '"Public"')
                      where id = ${draft.id}""".update.run()
              }
            )
            .either
          more <- s.app
            .transact(tenantId)(
              Db.sql {
                val _ = sql"""update dms.document_confidentiality_lowering
                      set status = 'Approved', decided_by = ${approver.value}, decided_at = now()
                      where version_id = ${draft.id}""".update.run()
                sql"""update dms.document_version
                      set field_values = jsonb_set(jsonb_set(field_values,
                            '{confidentiality,value,code}', '"Internal"'),
                            '{language,value}', '"de"')
                      where id = ${draft.id}""".update.run()
              }
            )
            .either
        yield assertTrue(other.isLeft, more.isLeft)
      },
      test("nobody who changed the version decides, whatever the application does") {
        for
          triple <- inLoweringStep()
          (_, draft, _) = triple
          s        <- setup.await
          byEditor <- s.app
            .transact(tenantId)(
              Db.sql(
                sql"""update dms.document_confidentiality_lowering
                      set status = 'Approved', decided_by = ${member.value}, decided_at = now()
                      where version_id = ${draft.id}""".update.run()
              )
            )
            .either
          removed <- s.app
            .transact(tenantId)(
              Db.sql(
                sql"delete from dms.document_confidentiality_lowering where version_id = ${draft.id}".update
                  .run()
              )
            )
            .either
        yield assertTrue(byEditor.isLeft, removed.isLeft)
      },
      test("the rows of another tenant stay out of reach") {
        for
          triple <- inLoweringStep()
          (_, draft, _) = triple
          s <- setup.await
          other = TenantId.random()
          _ <- ownerQuery(
            sql"insert into dms.tenant (id, name) values (${other.value}, ${"Other"})".update.run()
          )
          seen <- s.app
            .transact(other)(
              Db.sql(
                sql"select count(*) from dms.document_confidentiality_lowering where version_id = ${draft.id}"
                  .query[Long]
                  .run()
                  .head
              )
            )
        yield assertTrue(seen == 0L)
      }
    )
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
