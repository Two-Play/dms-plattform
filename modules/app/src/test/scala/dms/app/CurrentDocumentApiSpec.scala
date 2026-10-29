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
import com.sun.net.httpserver.HttpServer
import dms.audit.AuditTrail
import dms.audit.core.{AuditTrailLive, TsaTrust}
import dms.authz.{Authorizer, Groups, Role, RoleAssignment, RoleAssignments}
import dms.db.queue.ClaimedJob
import dms.db.{Db, DbConfig, DbError, Jsonb, Migrations}
import dms.documents.{LifecycleEvent, Publication}
import dms.documents.core.*
import dms.identity.*
import dms.integration.webhooks.*
import dms.integration.{JobRunner, OutboxRelay, WorkerConfig}
import dms.kernel.{TemplateId, TenantId, UserId}
import dms.rendering.core.{RenditionArchive, RenditionSource}
import dms.templates.*
import dms.templates.core.{ProfileSource, TemplateDraft, TemplateService}
import dms.workflow.core.{ApprovalWorkflow, FlowableEngine, WorkflowConfig}
import org.testcontainers.utility.DockerImageName
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.RichZServerEndpoint
import zio.*
import zio.http.{Body, Header, Headers, MediaType, Request, Response, Status, URL}
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.{Instant, LocalDate, ZoneId}
import java.util.{Date, UUID}
import javax.sql.DataSource
import scala.jdk.CollectionConverters.*

/** Stable addresses of the current version and webhooks for connected systems (US-14.1, DMS-58) end to end:
  * HTTP endpoints, documents, workflow, the publication job with a test clock, the outbox relay with the
  * webhook subscriber, the job queue and a fake receiver, against a Testcontainers database.
  */
object CurrentDocumentApiSpec extends ZIOSpecDefault:

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

  /** The intranet's technical user: Reader on the class `policy` only. */
  private val intranet = UserId("machine-intranet")

  private val berlin = ZoneId.of("Europe/Berlin")

  private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("key-1").generate()

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  final case class Setup(app: Db, owner: Db, policy: TemplateId, contract: TemplateId)

  private val setup = Unsafe.unsafe(implicit u => Promise.unsafe.make[Nothing, Setup](FiberId.None))

  private val content = TemplateContent(
    List(
      Section(
        SectionId("rules"),
        Some("Rules"),
        locked = false,
        List(Block.Paragraph(List(Inline.Text("Be kind to each other."))))
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
            _ <- RoleAssignments.Postgres.grant(owner, RoleAssignment(Role.Owner), admin)
            _ <- RoleAssignments.Postgres.grant(reviewer, RoleAssignment(Role.Reviewer), admin)
            _ <- RoleAssignments.Postgres.grant(approver, RoleAssignment(Role.Approver), admin)
            _ <- RoleAssignments.Postgres.grant(reader, RoleAssignment(Role.Reader), admin)
            _ <- RoleAssignments.Postgres
              .grant(intranet, RoleAssignment(Role.Reader, documentClass = Some("policy")), admin)
            // Reviewer and approver may see the confidential test document (ADR 0032); the intranet may not.
            _        <- Groups.Postgres.create("board", "Board", admin)
            _        <- Groups.Postgres.addMember("board", reviewer, admin)
            _        <- Groups.Postgres.addMember("board", approver, admin)
            policy   <- classes.createClass("policy", None, ClassDraft("Policy", None, Nil), admin)
            contract <- classes.createClass("contract", None, ClassDraft("Contract", None, Nil), admin)
            ids      <- ZIO.foreach(List(policy, contract)) { cls =>
              for
                t <- templates
                  .create(TemplateDraft("Template", None, cls.classId.value, 1, content), designer)
                _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Testing, designer)
                _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Released, designer2)
              yield t.templateId
            }
          yield ids
        )
        .mapError(e => RuntimeException(e.toString))
        .orDie
      _ <- setup.succeed(Setup(app, schemaOwner, released.head, released(1)))
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

  private val webhookSettings = WebhookSettings(allowedHosts = Set("127.0.0.1"), timeout = 2.seconds)

  private type Env = Authenticator & Db & Authorizer & DocumentService & ApprovalWorkflow & RenditionSource &
    AuditTrail & RenditionArchive & TemplateService & ProfileSource & WebhookSettings

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
      DocumentRenditions.layer,
      ZLayer(RenditionArchive.inMemory),
      ZLayer.succeed(webhookSettings)
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

  private val routes = ZioHttpInterpreter().toHttp(
    DocumentApi.endpoints.map(_.widen[Env]) ++ WorkflowApi.endpoints.map(_.widen[Env]) ++
      CurrentDocumentApi.endpoints.map(_.widen[Env]) ++ WebhookApi.endpoints.map(_.widen[Env])
  )

  private def send(user: UserId, method: String, path: String, body: Option[String], extra: Headers) =
    val url     = URL.decode(path).toOption.get
    val headers = Headers(
      Header.Authorization.Bearer(token(user)),
      Header.ContentType(MediaType.application.json)
    ) ++ extra
    val request = method match
      case "POST"   => Request.post(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case "PUT"    => Request.put(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case "DELETE" => Request.delete(url).addHeaders(headers)
      case _        => Request.get(url).addHeaders(headers)
    ZIO.scoped(routes.runZIO(request))

  private def call(
      user: UserId,
      method: String,
      path: String,
      body: Option[String] = None,
      extra: Headers = Headers.empty
  ): ZIO[Env, Nothing, (Status, String)] =
    send(user, method, path, body, extra).flatMap(r => r.body.asString.map(b => (r.status, b)).orDie)

  private def raw(user: UserId, path: String, extra: Headers = Headers.empty): ZIO[Env, Nothing, Response] =
    send(user, "GET", path, None, extra)

  private def as[A: JsonDecoder](response: (Status, String)): A =
    response._2.fromJson[A].fold(e => throw AssertionError(s"$e: ${response._1} ${response._2}"), identity)

  private def error(response: (Status, String)): Option[ApiError] = response._2.fromJson[ApiError].toOption

  private def values(validFrom: LocalDate, scope: List[String] = Nil, confidential: Boolean = false) =
    val scopes = scope.map(s => s"""{"type":"Text","value":"$s"}""").mkString(",")
    val level  = if confidential then "Confidential" else "Internal"
    s"""{"owner":{"type":"PersonRef","userId":"${owner.value}"},
       |"organizationalUnit":{"type":"Text","value":"Compliance"},
       |"validFrom":{"type":"Date","value":"$validFrom"},
       |"confidentiality":{"type":"Selection","code":"$level"},
       |"allowedGroups":{"type":"Many","values":[${
        if confidential then """{"type":"Text","value":"board"}""" else ""
      }]},
       |"scope":{"type":"Many","values":[$scopes]},
       |"language":{"type":"Text","value":"en"},
       |"retentionRule":{"type":"Text","value":"policy-10y"},
       |"containsPersonalData":{"type":"YesNo","value":false}}""".stripMargin

  private val past = LocalDate.of(2026, 1, 1)

  /** The rendering jobs with stand-ins for worker and veraPDF, storing into the shared in-memory archive. */
  private def renderPending: ZIO[Env, DbError, Int] =
    for
      s       <- setup.await
      source  <- ZIO.service[RenditionSource]
      audit   <- ZIO.service[AuditTrail]
      archive <- ZIO.service[RenditionArchive]
      jobs = dms.rendering.core.RenditionJobs(
        s.app,
        source,
        job =>
          ZIO.succeed(dms.rendering.core.RenderedPdf(Chunk.fromArray(job.html.getBytes), Some(1), "stub")),
        (_, standard) =>
          ZIO.succeed(dms.rendering.core.ValidationReport(standard, compliant = true, "stub", Nil)),
        archive,
        audit
      )
      ran <- JobRunner(
        s.app,
        Map(dms.rendering.RenditionRequest.JobKind -> jobs.handle),
        WorkerConfig("render")
      ).runOnce
    yield ran

  private def cases(doc: UUID) =
    call(author, "GET", s"/api/v1/documents/$doc/workflow").map(as[List[WorkflowCaseView]])

  private def vote(user: UserId, task: TaskView) =
    call(user, "POST", s"/api/v1/workflow/tasks/${task.id}/votes", Some(CastVote("Approve", None).toJson))

  private def publicationJobs: ZIO[Env, Nothing, PublicationJobs] =
    for
      s       <- setup.await
      service <- ZIO.service[DocumentService]
    yield PublicationJobs(s.app, service, PublicationScheduleConfig())

  /** Enqueues and runs the due publication jobs, as the background processing does. */
  private def runPublicationJobs: ZIO[Env, Any, Int] =
    for
      s    <- setup.await
      jobs <- publicationJobs
      _    <- jobs.enqueue(tenantId)
      ran  <- JobRunner(s.app, jobs.handlers, WorkerConfig("publication")).runOnce
    yield ran

  /** Submits the open draft and approves it through the default workflow (review, approval). */
  private def approve(doc: UUID): ZIO[Env, Any, Unit] =
    for
      _           <- call(author, "POST", s"/api/v1/documents/$doc/submit")
      review      <- cases(doc).map(_.last.tasks.find(_.status == "Open").get)
      afterReview <- vote(reviewer, review).map(as[WorkflowCaseView])
      _           <- vote(approver, afterReview.tasks.find(_.status == "Open").get)
      _           <- renderPending
    yield ()

  private def create(template: UUID, title: String, body: String): ZIO[Env, Nothing, DocumentView] =
    call(
      author,
      "POST",
      "/api/v1/documents",
      Some(s"""{"templateId":"$template","templateVersion":1,"title":"$title","values":$body}""")
    ).map(as[DocumentView])

  /** A document whose version 1.0 is valid. */
  private def validDocument(
      title: String,
      scope: List[String] = Nil,
      confidential: Boolean = false,
      contract: Boolean = false
  ): ZIO[Env, Any, DocumentView] =
    for
      s       <- setup.await
      created <- create(
        (if contract then s.contract else s.policy).value,
        title,
        values(past, scope, confidential)
      )
      _ <- approve(created.id)
      _ <- runPublicationJobs
    yield created

  /** Starts a major version with the given values and approves it. */
  private def approveNewVersion(doc: UUID, body: String): ZIO[Env, Any, DocumentVersionView] =
    for
      v <- call(author, "POST", s"/api/v1/documents/$doc/versions", Some("""{"changeKind":"Major"}"""))
        .map(as[DocumentVersionView])
      _ <- call(author, "PUT", s"/api/v1/documents/$doc/draft", Some(s"""{"values":$body}"""))
      _ <- approve(doc)
    yield v

  private def current(user: UserId, doc: UUID, query: String = "", extra: Headers = Headers.empty) =
    call(user, "GET", s"/api/v1/documents/$doc/current$query", extra = extra)

  /** Runs the outbox relay with the webhook subscriber and then the webhook jobs once. */
  private def deliverWebhooks: ZIO[Env, DbError, Unit] =
    for
      s       <- setup.await
      service <- ZIO.service[DocumentService]
      jobs = WebhookJobs(
        s.app,
        DocumentWebhookEvents(service),
        HttpWebhookSender(webhookSettings),
        webhookSettings
      )
      _ <- OutboxRelay(s.app, jobs.deliver, WorkerConfig("relay", batchSize = 50)).runOnce.repeatUntil(_ == 0)
      _ <- JobRunner(s.app, jobs.handlers, WorkerConfig("webhooks", batchSize = 50)).runOnce
    yield ()

  /** A minimal receiver on 127.0.0.1 recording requests: headers (lower case) and body. */
  private def receiver: ZIO[Scope, Throwable, (String, Ref[Vector[(Map[String, String], String)]])] =
    for
      received <- Ref.make(Vector.empty[(Map[String, String], String)])
      server   <- ZIO.acquireRelease(ZIO.attemptBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
          "/hook",
          exchange =>
            val body    = String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
            val headers =
              exchange.getRequestHeaders.asScala.map((k, v) => k.toLowerCase -> v.asScala.mkString(",")).toMap
            Unsafe.unsafe(implicit u =>
              Runtime.default.unsafe.run(received.update(_ :+ (headers, body))).getOrThrowFiberFailure()
            )
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        )
        server.start()
        server
      })(s => ZIO.succeed(s.stop(0)))
    yield (s"http://127.0.0.1:${server.getAddress.getPort}/hook", received)

  private def eventsOf(received: Vector[(Map[String, String], String)], doc: UUID): Vector[(String, String)] =
    received.flatMap { (_, body) =>
      for
        json <- body.fromJson[Json.Obj].toOption
        kind <- json.get("type").collect { case Json.Str(t) => t }
        data <- json.get("data").collect { case o: Json.Obj => o }
        if data.get("documentId").contains(Json.Str(doc.toString))
        version <- data.get("version").collect { case Json.Str(v) => v }
      yield (kind, version)
    }

  def spec = suite("Stable address of the current version and webhooks")(
    test("API documentation contains the stable addresses and the webhook administration") {
      val docs = sttp.tapir.docs.openapi
        .OpenAPIDocsInterpreter()
        .toOpenAPI((CurrentDocumentApi.endpoints ++ WebhookApi.endpoints).map(_.endpoint), "DMS", "test")
      assertTrue(
        docs.paths.pathItems.contains("/api/v1/documents/{id}/current"),
        docs.paths.pathItems.contains("/api/v1/documents/{id}/current.pdf"),
        docs.paths.pathItems.contains("/api/v1/documents/{id}/current.html"),
        docs.paths.pathItems.contains("/api/v1/admin/webhooks/{id}/deliveries")
      )
    },
    test("the stable address switches to the successor when the publication job publishes it at 00:00") {
      ZIO.scoped(
        for
          _    <- TestClock.setTime(Instant.now())
          hook <- receiver
          (url, received) = hook
          created <- call(
            admin,
            "POST",
            "/api/v1/admin/webhooks",
            Some(WebhookRequest(url, WebhookEventType.all.toList, List("policy")).toJson)
          ).map(as[WebhookWithSecret])
          doc    <- validDocument("Code of conduct")
          first  <- current(intranet, doc.id).map(as[CurrentVersionView])
          before <- raw(intranet, s"/api/v1/documents/${doc.id}/current")
          etag = before.headers.get("ETag").getOrElse("")
          unchanged <- current(intranet, doc.id, extra = Headers("If-None-Match", etag))
          pdf1      <- raw(intranet, s"/api/v1/documents/${doc.id}/current.pdf")
          html1     <- raw(intranet, s"/api/v1/documents/${doc.id}/current.html")
          htmlBody  <- html1.body.asString.orDie
          now       <- Clock.instant
          tomorrow = LocalDate.ofInstant(now, berlin).plusDays(1)
          next      <- approveNewVersion(doc.id, values(tomorrow))
          published <- runPublicationJobs
          still     <- current(intranet, doc.id).map(as[CurrentVersionView])
          // The job runs at 00:00 of the valid-from date; the test clock gets there.
          _    <- TestClock.setTime(Publication.dueAt(tomorrow, berlin).plusSeconds(1))
          jobs <- publicationJobs
          _    <- jobs.handlers(PublicationJobs.PublishKind)(
            ClaimedJob(
              0L,
              Some(tenantId),
              PublicationJobs.PublishKind,
              Jsonb(s"""{"versionId":"${next.id}"}"""),
              1,
              10
            )
          )
          second      <- current(intranet, doc.id).map(as[CurrentVersionView])
          revalidated <- current(intranet, doc.id, extra = Headers("If-None-Match", etag))
          pdf2        <- raw(intranet, s"/api/v1/documents/${doc.id}/current.pdf")
          _           <- deliverWebhooks
          events      <- received.get
        yield assertTrue(
          created.webhook.documentClasses == List("policy"),
          created.secret.startsWith("whsec_"),
          first.version == "1.0",
          first.status == "Published",
          first.classPath == List("policy"),
          first.links.pdf == s"/api/v1/documents/${doc.id}/current.pdf",
          before.headers.get("Cache-Control").contains("private, no-cache"),
          etag.startsWith("\""),
          unchanged._1 == Status.NotModified,
          pdf1.status == Status.Ok,
          pdf1.headers.get("Content-Type").exists(_.startsWith("application/pdf")),
          pdf1.headers.get("ETag").contains(s"\"${first.renditionSha256.get}\""),
          pdf1.headers
            .get("Content-Location")
            .contains(s"/api/v1/documents/${doc.id}/versions/${first.versionId}/rendition"),
          html1.headers.get("Content-Type").exists(_.startsWith("text/html")),
          html1.headers.get("Content-Security-Policy").exists(_.contains("default-src 'none'")),
          htmlBody.contains("Be kind to each other."),
          published == 0,
          still.version == "1.0",
          second.version == "2.0",
          second.versionId == next.id,
          second.validFrom.contains(tomorrow),
          second.validSince.contains(Publication.dueAt(tomorrow, berlin).plusSeconds(1)),
          revalidated._1 == Status.Ok,
          pdf2.headers.get("ETag").exists(_ != pdf1.headers.get("ETag").getOrElse("")),
          // Webhooks promise no order; receivers read the stable address for the current state.
          eventsOf(events, doc.id).sorted == Vector(
            (WebhookEventType.Published, "1.0"),
            (WebhookEventType.Published, "2.0"),
            (WebhookEventType.Superseded, "1.0")
          ),
          events.forall((headers, body) =>
            WebhookSignature.verify(
              created.secret,
              headers.getOrElse("webhook-id", ""),
              headers.getOrElse("webhook-timestamp", "0").toLong,
              body,
              headers.getOrElse("webhook-signature", "")
            )
          )
        )
      )
    },
    test("a scope picks the version valid there; without a scope several valid versions are ambiguous") {
      for
        doc       <- validDocument("Travel policy", scope = List("DE"))
        _         <- approveNewVersion(doc.id, values(past, List("AT")))
        _         <- runPublicationJobs
        ambiguous <- current(intranet, doc.id)
        de        <- current(intranet, doc.id, "?scope=DE").map(as[CurrentVersionView])
        at        <- current(intranet, doc.id, "?scope=AT").map(as[CurrentVersionView])
        ch        <- current(intranet, doc.id, "?scope=CH")
      yield assertTrue(
        ambiguous._1 == Status.Conflict,
        error(ambiguous).exists(e => e.code == "scope_required" && e.message.contains("AT, DE")),
        de.version == "1.0",
        de.scope == "DE",
        de.links.self == s"/api/v1/documents/${doc.id}/current?scope=DE",
        at.version == "2.0",
        ch._1 == Status.NotFound
      )
    } @@ TestAspect.withLiveClock,
    test("a version withdrawn without successor answers 410 and is announced to subscribers") {
      ZIO.scoped(
        for
          hook <- receiver
          (url, received) = hook
          _ <- call(
            admin,
            "POST",
            "/api/v1/admin/webhooks",
            Some(WebhookRequest(url, List(WebhookEventType.Withdrawn)).toJson)
          )
          doc <- validDocument("Obsolete rule")
          v   <- current(reader, doc.id).map(as[CurrentVersionView])
          s   <- setup.await
          _   <- s.app.transact(tenantId)(
            ZIO.serviceWithZIO[DocumentService](_.changeStatus(v.versionId, LifecycleEvent.Withdraw, owner))
          )
          gone   <- current(intranet, doc.id)
          pdf    <- raw(intranet, s"/api/v1/documents/${doc.id}/current.pdf")
          other  <- current(intranet, UUID.randomUUID())
          _      <- deliverWebhooks
          events <- received.get
        yield assertTrue(
          gone._1 == Status.Gone,
          error(gone).exists(_.code == "withdrawn"),
          pdf.status == Status.Gone,
          other._1 == Status.NotFound,
          eventsOf(events, doc.id) == Vector((WebhookEventType.Withdrawn, "1.0"))
        )
      )
    } @@ TestAspect.withLiveClock,
    test("the technical user reads exactly its released classes and no confidential documents") {
      ZIO.scoped(
        for
          hook <- receiver
          (url, received) = hook
          _ <- call(
            admin,
            "POST",
            "/api/v1/admin/webhooks",
            Some(WebhookRequest(url, List(WebhookEventType.Published)).toJson)
          )
          contract     <- validDocument("Service contract", contract = true)
          confidential <- validDocument("Board rules", confidential = true)
          byIntranet   <- current(intranet, contract.id)
          byReader     <- current(reader, contract.id)
          secret       <- current(intranet, confidential.id)
          secretPdf    <- raw(intranet, s"/api/v1/documents/${confidential.id}/current.pdf")
          byMember     <- current(approver, confidential.id)
          anonymous    <- ZIO.scoped(
            routes.runZIO(Request.get(URL.decode(s"/api/v1/documents/${contract.id}/current").toOption.get))
          )
          _      <- deliverWebhooks
          events <- received.get
        yield assertTrue(
          byIntranet._1 == Status.NotFound,
          byReader._1 == Status.Ok,
          secret._1 == Status.NotFound,
          secretPdf.status == Status.NotFound,
          byMember._1 == Status.Ok,
          anonymous.status == Status.Unauthorized,
          eventsOf(events, contract.id) == Vector((WebhookEventType.Published, "1.0")),
          eventsOf(events, confidential.id).isEmpty
        )
      )
    } @@ TestAspect.withLiveClock,
    test("the stable address by document number resolves like the one by id (DMS-113)") {
      for
        policy   <- validDocument("Travel policy")
        contract <- validDocument("Supplier contract", contract = true)
        _        <- call(
          owner,
          "POST",
          s"/api/v1/documents/${policy.id}/number",
          Some("""{"number":"RL-042","reason":"number from the policy register"}""")
        )
        _ <- call(
          owner,
          "POST",
          s"/api/v1/documents/${contract.id}/number",
          Some("""{"number":"VTR-2026-0042","reason":"number from the contract register"}""")
        )
        byId       <- current(intranet, policy.id).map(as[CurrentVersionView])
        byNumber   <- call(intranet, "GET", "/api/v1/documents/by-number/RL-042/current")
        unknown    <- call(intranet, "GET", "/api/v1/documents/by-number/RL-999/current")
        notAllowed <- call(intranet, "GET", "/api/v1/documents/by-number/VTR-2026-0042/current")
        byReader   <- call(reader, "GET", "/api/v1/documents/by-number/VTR-2026-0042/current")
      yield assertTrue(
        byId.documentNumber.contains("RL-042"),
        byNumber._1 == Status.Ok,
        as[CurrentVersionView](byNumber) == byId,
        unknown._1 == Status.NotFound,
        notAllowed._1 == Status.NotFound,
        byReader._1 == Status.Ok,
        as[CurrentVersionView](byReader).documentId == contract.id
      )
    } @@ TestAspect.withLiveClock,
    test("tenant administrators manage webhooks; unsafe targets and other roles are rejected") {
      for
        byReader <- call(
          reader,
          "POST",
          "/api/v1/admin/webhooks",
          Some(WebhookRequest("https://1.1.1.1/hook", List(WebhookEventType.Published)).toJson)
        )
        plain <- call(
          admin,
          "POST",
          "/api/v1/admin/webhooks",
          Some(WebhookRequest("http://1.1.1.1/hook", List(WebhookEventType.Published)).toJson)
        )
        internal <- call(
          admin,
          "POST",
          "/api/v1/admin/webhooks",
          Some(WebhookRequest("https://10.0.0.5/hook", List(WebhookEventType.Published)).toJson)
        )
        metadata <- call(
          admin,
          "POST",
          "/api/v1/admin/webhooks",
          Some(WebhookRequest("https://169.254.169.254/x", List(WebhookEventType.Published)).toJson)
        )
        unknown <- call(
          admin,
          "POST",
          "/api/v1/admin/webhooks",
          Some(WebhookRequest("https://1.1.1.1/hook", List("document.created")).toJson)
        )
        created <- call(
          admin,
          "POST",
          "/api/v1/admin/webhooks",
          Some(
            WebhookRequest(
              "https://1.1.1.1/hook",
              List(WebhookEventType.Published),
              description = Some("Intranet")
            ).toJson
          )
        )
          .map(as[WebhookWithSecret])
        id = created.webhook.id
        paused <- call(
          admin,
          "PUT",
          s"/api/v1/admin/webhooks/$id",
          Some(
            WebhookRequest(
              "https://1.1.1.1/hook",
              List(WebhookEventType.Published, WebhookEventType.Withdrawn),
              active = Some(false)
            ).toJson
          )
        ).map(as[WebhookView])
        rotated <- call(admin, "POST", s"/api/v1/admin/webhooks/$id/secret").map(as[WebhookWithSecret])
        log     <- call(admin, "GET", s"/api/v1/admin/webhooks/$id/deliveries?status=dead").map(
          as[List[WebhookDeliveryView]]
        )
        badState <- call(admin, "GET", s"/api/v1/admin/webhooks/$id/deliveries?status=lost")
        deleted  <- call(admin, "DELETE", s"/api/v1/admin/webhooks/$id")
        after    <- call(admin, "GET", s"/api/v1/admin/webhooks/$id")
        listed   <- call(admin, "GET", "/api/v1/admin/webhooks").map(as[List[WebhookView]])
        audited  <- setup.await.flatMap(s =>
          s.owner.transactSystem(
            Db.sql(
              sql"select action, details::text from dms.audit_event where object_type = 'webhook' order by seq"
                .query[(String, String)]
                .run()
                .toList
            )
          )
        )
      yield assertTrue(
        byReader._1 == Status.Forbidden,
        plain._1 == Status.UnprocessableEntity,
        internal._1 == Status.UnprocessableEntity,
        error(internal).exists(_.message.contains("non-public")),
        metadata._1 == Status.UnprocessableEntity,
        unknown._1 == Status.UnprocessableEntity,
        created.webhook.active,
        created.webhook.description.contains("Intranet"),
        !paused.active,
        paused.eventTypes == List(WebhookEventType.Published, WebhookEventType.Withdrawn),
        rotated.secret != created.secret,
        log.isEmpty,
        badState._1 == Status.UnprocessableEntity,
        deleted._1 == Status.NoContent,
        after._1 == Status.NotFound,
        !listed.exists(_.id == id),
        audited
          .map(_._1)
          .containsSlice(
            List("webhook.created", "webhook.changed", "webhook.secret-rotated", "webhook.deleted")
          ),
        !audited.exists((_, details) => details.contains(created.secret) || details.contains(rotated.secret))
      )
    } @@ TestAspect.withLiveClock
  ).provideShared(env) @@ TestAspect.sequential
