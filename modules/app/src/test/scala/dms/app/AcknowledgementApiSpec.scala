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
import dms.authz.{Authorizer, Role, RoleAssignment, RoleAssignments}
import dms.db.{Db, DbConfig, DbError, Migrations}
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

/** Acknowledgement of published versions (DMS-47) end to end: HTTP endpoints, the real documents and workflow
  * modules, the publication job and the audit trail against a Testcontainers database.
  */
object AcknowledgementApiSpec extends ZIOSpecDefault:

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
            cls <- classes.createClass("policy", None, ClassDraft("Policy", None, Nil), admin)
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

  private type Env = AcknowledgementApi.Env & WorkflowApi.Env & RenditionSource & AuditTrail

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
      DocumentService.configured(DocumentSettings(editorialChangeThreshold = 0.1)),
      AcknowledgementService.layer,
      FlowableEngine.layer,
      WorkflowDocuments.layer,
      RoleDirectory.layer,
      WorkflowExtraReview.layer,
      ZLayer.succeed(WorkflowConfig.default),
      WorkflowRendition.layer,
      ApprovalWorkflow.layer,
      DocumentRenditions.layer
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
    DocumentApi.endpoints.map(_.widen[Env]) ++ WorkflowApi.endpoints.map(_.widen[Env]) ++
      AcknowledgementApi.endpoints.map(_.widen[Env])
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

  private def values(ownerId: UserId, validFrom: LocalDate) =
    s"""{"owner":{"type":"PersonRef","userId":"${ownerId.value}"},
       |"organizationalUnit":{"type":"Text","value":"Compliance"},
       |"validFrom":{"type":"Date","value":"$validFrom"},
       |"confidentiality":{"type":"Selection","code":"Internal"},
       |"language":{"type":"Text","value":"de"},
       |"retentionRule":{"type":"Text","value":"policy-10y"},
       |"containsPersonalData":{"type":"YesNo","value":false}}""".stripMargin

  private val past = LocalDate.of(2026, 1, 1)

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

  /** A document by the author whose version 1.0 is valid. */
  private def validDocument(ownerId: UserId): ZIO[Env, Any, DocumentView] =
    for
      s       <- setup.await
      created <- call(
        author,
        "POST",
        "/api/v1/documents",
        Some(
          s"""{"templateId":"${s.released.value}","templateVersion":1,"title":"Code of conduct",
             |"values":${values(ownerId, past)}}""".stripMargin
        )
      ).map(as[DocumentView])
      _ <- approveDefault(created.id)
      _ <- runPublicationJobs
    yield created

  private def versions(doc: UUID, user: UserId = reader) =
    call(user, "GET", s"/api/v1/documents/$doc/versions").map(as[List[DocumentVersionView]])

  private def newVersion(doc: UUID, body: String, user: UserId = author) =
    call(user, "POST", s"/api/v1/documents/$doc/versions", Some(body))

  private def acknowledgements(user: UserId, status: String = "open") =
    call(user, "GET", s"/api/v1/acknowledgements?status=$status").map(as[List[AcknowledgementItemView]])

  private def opened(user: UserId, doc: UUID, version: UUID) =
    call(user, "POST", s"/api/v1/documents/$doc/versions/$version/opened")

  private def acknowledge(user: UserId, doc: UUID, version: UUID) =
    call(user, "POST", s"/api/v1/documents/$doc/versions/$version/acknowledgement")

  private def state(user: UserId, doc: UUID, version: UUID) =
    call(user, "GET", s"/api/v1/documents/$doc/versions/$version/acknowledgement")
      .map(as[AcknowledgementStateView])

  def spec = suite("Acknowledgements over HTTP")(
    test("API documentation contains the acknowledgement endpoints") {
      val docs = sttp.tapir.docs.openapi
        .OpenAPIDocsInterpreter()
        .toOpenAPI(AcknowledgementApi.endpoints.map(_.endpoint), "DMS Platform", "test")
      assertTrue(
        docs.paths.pathItems.contains("/api/v1/acknowledgements"),
        docs.paths.pathItems.contains("/api/v1/documents/{id}/versions/{versionId}/opened"),
        docs.paths.pathItems.contains("/api/v1/documents/{id}/versions/{versionId}/acknowledgement")
      )
    },
    test("a reader acknowledges a published version only after opening it, once") {
      for
        doc         <- validDocument(owner)
        v1          <- versions(doc.id).map(_.head)
        openBefore  <- acknowledgements(reader).map(_.filter(_.documentId == doc.id))
        notOpened   <- acknowledge(reader, doc.id, v1.id)
        marked      <- opened(reader, doc.id, v1.id)
        markedAgain <- opened(reader, doc.id, v1.id)
        openAfter   <- acknowledgements(reader).map(_.filter(_.documentId == doc.id))
        created     <- acknowledge(reader, doc.id, v1.id)
        again       <- acknowledge(reader, doc.id, v1.id)
        openDone    <- acknowledgements(reader).map(_.filter(_.documentId == doc.id))
        done        <- acknowledgements(reader, "done").map(_.filter(_.documentId == doc.id))
        st          <- state(reader, doc.id, v1.id)
        view = as[AcknowledgementView](created)
      yield assertTrue(
        openBefore.map(i => (i.versionId, i.versionLabel, i.title, i.opened)) ==
          List((v1.id, "1.0", "Code of conduct", false)),
        openBefore.head.classPath == List("policy"),
        openBefore.head.validFrom.contains(past),
        openBefore.head.publishedAt.isDefined,
        openBefore.head.acknowledgedAt.isEmpty,
        notOpened._1 == Status.Conflict,
        error(notOpened._2).exists(_.code == "not_opened"),
        marked._1 == Status.NoContent,
        markedAgain._1 == Status.NoContent,
        openAfter.map(_.opened) == List(true),
        created._1 == Status.Created,
        view.versionId == v1.id,
        view.versionLabel == "1.0",
        again._1 == Status.Conflict,
        error(again._2).exists(_.code == "already_acknowledged"),
        openDone.isEmpty,
        done.map(i => (i.versionId, i.versionLabel, i.acknowledgedAt)) ==
          List((v1.id, "1.0", Some(view.acknowledgedAt))),
        st.requested,
        st.opened,
        st.acknowledgedAt.contains(view.acknowledgedAt)
      )
    },
    test("every business role in scope must acknowledge; administrators without one cannot") {
      for
        doc       <- validDocument(owner)
        v1        <- versions(doc.id).map(_.head)
        byAuthor  <- acknowledgements(author).map(_.filter(_.documentId == doc.id))
        byAdmin   <- acknowledgements(admin).map(_.filter(_.documentId == doc.id))
        adminOpen <- opened(admin, doc.id, v1.id)
        adminAck  <- acknowledge(admin, doc.id, v1.id)
        unknown   <- acknowledge(reader, doc.id, UUID.randomUUID())
        badStatus <- call(reader, "GET", "/api/v1/acknowledgements?status=all")
      yield assertTrue(
        byAuthor.map(_.versionId) == List(v1.id),
        byAdmin.isEmpty,
        adminOpen._1 == Status.Forbidden,
        adminAck._1 == Status.Forbidden,
        unknown._1 == Status.NotFound,
        badStatus._1 == Status.BadRequest
      )
    },
    test("a superseded version drops out of the open list; the new major version asks again") {
      for
        doc <- validDocument(owner)
        v1  <- versions(doc.id).map(_.head)
        _   <- opened(reader, doc.id, v1.id)
        _   <- acknowledge(reader, doc.id, v1.id)
        _   <- opened(approver, doc.id, v1.id)
        _   <- newVersion(doc.id, """{"changeKind":"Major"}""")
        _   <- approveDefault(doc.id)
        _   <- runPublicationJobs
        all <- versions(doc.id)
        v2 = all.last
        readerOpen   <- acknowledgements(reader).map(_.filter(_.documentId == doc.id))
        approverOpen <- acknowledgements(approver).map(_.filter(_.documentId == doc.id))
        late         <- acknowledge(approver, doc.id, v1.id)
        done         <- acknowledgements(reader, "done").map(_.filter(_.documentId == doc.id))
        st           <- state(reader, doc.id, v1.id)
      yield assertTrue(
        all.map(v => (v.version, v.status)) == List(("1.0", "Superseded"), ("2.0", "Published")),
        readerOpen.map(i => (i.versionId, i.versionLabel, i.opened)) == List((v2.id, "2.0", false)),
        approverOpen.map(_.versionId) == List(v2.id),
        late._1 == Status.UnprocessableEntity,
        done.map(i => (i.versionLabel, i.status)) == List(("1.0", "Superseded")),
        !st.requested,
        st.acknowledgedAt.isDefined
      )
    },
    test("an editorial version that does not require acknowledgement cannot be acknowledged") {
      for
        doc   <- validDocument(owner)
        _     <- newVersion(doc.id, """{"changeKind":"Minor","reason":"Typo"}""")
        _     <- call(author, "POST", s"/api/v1/documents/${doc.id}/submit")
        task  <- cases(doc.id).map(_.last.tasks.head)
        _     <- vote(owner, task)
        _     <- renderPending
        _     <- runPublicationJobs
        minor <- versions(doc.id).map(_.last)
        mark  <- opened(reader, doc.id, minor.id)
        ack   <- acknowledge(reader, doc.id, minor.id)
        open  <- acknowledgements(reader).map(_.filter(_.documentId == doc.id))
      yield assertTrue(
        minor.version == "1.1",
        minor.status == "Published",
        !minor.requiresAcknowledgement,
        mark._1 == Status.NoContent,
        ack._1 == Status.UnprocessableEntity,
        open.isEmpty
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
