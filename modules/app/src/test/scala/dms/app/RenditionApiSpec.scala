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
import dms.authz.{Authorizer, Role, RoleAssignment, RoleAssignments}
import dms.db.{Db, DbConfig, DbError, Migrations}
import dms.documents.core.*
import dms.documents.metadata.*
import dms.identity.*
import dms.integration.{JobRunner, WorkerConfig}
import dms.kernel.{TemplateId, TenantId, UserId}
import dms.rendering.{ArchiveStandard, RenditionRequest}
import dms.rendering.core.*
import dms.storage.{ObjectStore, S3ObjectStore, StorageConfig}
import dms.templates.*
import dms.templates.core.{ProfileSource, TemplateDraft, TemplateService}
import dms.workflow.core.{ApprovalWorkflow, FlowableEngine, WorkflowConfig}
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.services.s3.S3Client
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.RichZServerEndpoint
import zio.*
import zio.http.{Body, Header, Headers, MediaType, Request, Status, URL}
import zio.json.*
import zio.test.*

import java.security.MessageDigest
import java.time.Instant
import java.util.{Base64, Date, HexFormat, UUID}
import javax.sql.DataSource

/** Archive renditions end to end (DMS-44): the last approval vote enqueues the rendering job; the job renders
  * the version with the real rendering worker (built from `workers/rendering`), validates it with veraPDF,
  * stores it in SeaweedFS, attaches it and approves the version; the rendition can be downloaded with
  * `ReadDocument`. A failing rendering leaves the version in approval and notifies the owner through the
  * outbox.
  */
object RenditionApiSpec extends ZIOSpecDefault:

  private val issuer   = "http://localhost:8085"
  private val audience = "project-1"
  private val orgId    = "org-1"
  private val tenantId = TenantId.random()
  private val admin    = UserId("admin-1")
  private val author   = UserId("author-1")
  private val reviewer = UserId("reviewer-1")
  private val approver = UserId("approver-1")
  private val reader   = UserId("reader-1")
  private val designer = UserId("designer-1")
  private val designr2 = UserId("designer-2")

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
        Some("Vertragsparteien"),
        locked = true,
        List(Block.Paragraph(List(Inline.Text("Zwischen "), Inline.Placeholder("contractPartner"))))
      ),
      Section(
        SectionId("terms"),
        Some("Laufzeit"),
        locked = false,
        List(
          Block.Paragraph(List(Inline.Text("Der Vertrag gilt ab "), Inline.Placeholder("validFrom"))),
          Block.PageBreak,
          Block.Paragraph(
            List(Inline.Text("Gerichtsstand ist der Sitz von "), Inline.Placeholder("contractPartner"))
          )
        )
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
            _ <- RoleAssignments.Postgres.grant(reviewer, RoleAssignment(Role.Reviewer), admin)
            _ <- RoleAssignments.Postgres.grant(approver, RoleAssignment(Role.Approver), admin)
            _ <- RoleAssignments.Postgres.grant(reader, RoleAssignment(Role.Reader), admin)
            _ <- classes
              .createField("contractPartner", FieldDraft("Contract partner", None, FieldType.Text()), admin)
            cls <- classes.createClass(
              "contract",
              None,
              ClassDraft("Contract", None, List(FieldBinding(FieldKey.unsafe("contractPartner"), 1))),
              admin
            )
            draft = TemplateDraft("Dienstleistungsvertrag", None, cls.classId.value, 1, content)
            t <- templates.create(draft, designer)
            _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Testing, designer)
            _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Released, designr2)
          yield t.templateId
        )
        .mapError(e => RuntimeException(e.toString))
        .orDie
      _ <- setup.succeed(Setup(app, owner, released))
    yield ZEnvironment(app, appSource)
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  /** Same image as compose.yaml. */
  private val seaweedImage =
    "chrislusf/seaweedfs:4.51@sha256:f7a49e3f3972ebed5be2ed4befa4a0c68eb7bdddba6e692d42729ceb545134b8"

  /** SeaweedFS with an Object Lock bucket. */
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

  private type Env = DocumentApi.Env & WorkflowApi.Env & RenditionApi.Env & RenditionSource & PdfRenderer &
    AuditTrail

  private val env: ZLayer[Any, Throwable, Env] =
    ZLayer.make[Env](
      database,
      authenticator,
      objectStore,
      RenditionWiring.archive,
      RenderingWorker.renderer,
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
      WorkflowRendition.layer,
      ApprovalWorkflow.layer,
      DocumentRenditions.layer
    )

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

  private val routes =
    ZioHttpInterpreter().toHttp(
      DocumentApi.endpoints.map(_.widen[Env]) ++ WorkflowApi.endpoints.map(_.widen[Env]) ++
        RenditionApi.endpoints.map(_.widen[Env])
    )

  final case class Response(status: Status, headers: Headers, body: Chunk[Byte]):
    def text: String                         = String(body.toArray, java.nio.charset.StandardCharsets.UTF_8)
    def header(name: String): Option[String] = headers.get(name)

  private def call(
      user: UserId,
      method: String,
      path: String,
      body: Option[String] = None,
      signedIn: Long = 3600
  ) =
    val url     = URL.decode(path).toOption.get
    val headers = Headers(
      Header.Authorization.Bearer(token(user, signedIn)),
      Header.ContentType(MediaType.application.json)
    )
    val request = method match
      case "POST" => Request.post(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case _      => Request.get(url).addHeaders(headers)
    ZIO
      .scoped(routes.runZIO(request))
      .flatMap(r => r.body.asChunk.map(b => Response(r.status, r.headers, b)).orDie)

  private def as[A: JsonDecoder](response: Response): A =
    response.text
      .fromJson[A]
      .fold(e => throw AssertionError(s"$e: ${response.status} ${response.text}"), identity)

  private def values(partner: String) =
    s""""contractPartner":{"type":"Text","value":"$partner"},
       |"owner":{"type":"PersonRef","userId":"${author.value}"},
       |"organizationalUnit":{"type":"Text","value":"Legal"},
       |"validFrom":{"type":"Date","value":"2027-01-01"},
       |"confidentiality":{"type":"Selection","code":"Internal"},
       |"language":{"type":"Text","value":"de"},
       |"retentionRule":{"type":"Text","value":"contract-10y"},
       |"containsPersonalData":{"type":"YesNo","value":false}""".stripMargin

  /** A document submitted, reviewed and approved by the last vote: its case waits for the rendition. */
  private def approvedByVotes(partner: String): ZIO[Env, Nothing, (DocumentView, WorkflowCaseView)] =
    for
      s       <- setup.await
      created <- call(
        author,
        "POST",
        "/api/v1/documents",
        Some(
          s"""{"templateId":"${s.released.value}","templateVersion":1,"title":"Vertrag $partner",
             |"values":{${values(partner)}}}""".stripMargin
        )
      ).map(as[DocumentView])
      _       <- call(author, "POST", s"/api/v1/documents/${created.id}/submit")
      started <- cases(created.id).map(_.head)
      review = started.tasks.find(_.status == "Open").get
      reviewed <- vote(reviewer, review, signedIn = 3600).map(as[WorkflowCaseView])
      approval = reviewed.tasks.find(t => t.status == "Open" && t.kind == "Approval").get
      approved <- vote(approver, approval, signedIn = 30).map(as[WorkflowCaseView])
    yield (created, approved)

  private def cases(doc: UUID) =
    call(author, "GET", s"/api/v1/documents/$doc/workflow").map(as[List[WorkflowCaseView]])

  private def vote(user: UserId, task: TaskView, signedIn: Long) =
    call(
      user,
      "POST",
      s"/api/v1/workflow/tasks/${task.id}/votes",
      Some(CastVote("Approve", None).toJson),
      signedIn
    )

  private def document(doc: UUID) = call(author, "GET", s"/api/v1/documents/$doc").map(as[DocumentView])

  private def rendition(user: UserId, doc: UUID, version: UUID) =
    call(user, "GET", s"/api/v1/documents/$doc/versions/$version/rendition")

  /** The rendering job of the application (worker, veraPDF, SeaweedFS), or with a replaced step. */
  private def jobs(
      renderer: Option[PdfRenderer] = None,
      validator: PdfValidator = VeraPdfValidator
  ): ZIO[Env, Nothing, RenditionJobs] =
    for
      s       <- setup.await
      source  <- ZIO.service[RenditionSource]
      real    <- ZIO.service[PdfRenderer]
      archive <- ZIO.service[RenditionArchive]
      audit   <- ZIO.service[AuditTrail]
    yield RenditionJobs(s.app, source, renderer.getOrElse(real), validator, archive, audit)

  private def runJobs(rendering: RenditionJobs): ZIO[Any, DbError, Int] =
    setup.await.flatMap(s =>
      JobRunner(s.app, Map(RenditionRequest.JobKind -> rendering.handle), WorkerConfig("test-worker")).runOnce
    )

  private def ownerQuery[A](f: DbTx ?=> A): ZIO[Any, DbError, A] =
    setup.await.flatMap(s => s.owner.transactSystem(Db.sql(f)))

  private def outbox(eventType: String, documentId: UUID) =
    ownerQuery(
      sql"""select payload::text from dms.outbox_event where event_type = $eventType
            and aggregate_id = $documentId order by id""".query[String].run().toList
    )

  private def renditionJob(versionId: UUID) =
    ownerQuery(
      sql"""select status, attempts, last_error from dms.job where idempotency_key = ${"rendition:" + versionId}"""
        .query[(String, Int, Option[String])]
        .run()
        .headOption
    )

  private def makeDue(versionId: UUID, maxAttempts: Int) =
    ownerQuery(
      sql"""update dms.job set run_at = now(), max_attempts = $maxAttempts
            where idempotency_key = ${"rendition:" + versionId}""".update.run()
    )

  private def auditActions(documentId: UUID) =
    ownerQuery(
      sql"""select action from dms.audit_event where object_id = $documentId and action like 'document.rendition%'
            order by seq""".query[String].run().toList
    )

  private def sha256(bytes: Chunk[Byte]) =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toArray))

  private val unavailable: PdfRenderer = (_: PdfJob) =>
    ZIO.fail(RenderingError.Unavailable("connection refused"))

  private val notArchivable: PdfValidator = (_: Chunk[Byte], standard: ArchiveStandard) =>
    ZIO.succeed(
      ValidationReport(standard, compliant = false, "test validator", List("6.2.11.4.1-1: font not embedded"))
    )

  def spec = suite("Archive renditions")(
    test("the last approval renders a valid PDF/A, stores and attaches it, then approves the version") {
      for
        pair <- approvedByVotes("ACME GmbH")
        (doc, waiting) = pair
        version        = waiting.versionId
        inApproval <- document(doc.id)
        early      <- rendition(author, doc.id, version)
        queued     <- renditionJob(version)
        processed  <- jobs().flatMap(runJobs)
        done       <- cases(doc.id).map(_.head)
        approved   <- document(doc.id)
        download   <- rendition(author, doc.id, version)
        report     <- VeraPdfValidator.validate(download.body, ArchiveStandard.PdfA2b)
        texts = RenderingWorker.pageTexts(download.body).map(RenderingWorker.normalized)
        finished <- renditionJob(version)
        again    <- jobs().flatMap(runJobs)
        actions  <- auditActions(doc.id)
        events   <- ownerQuery(
          sql"select count(*) from dms.outbox_event where event_type = 'workflow.approved' and payload->>'documentId' = ${doc.id.toString}"
            .query[Long]
            .run()
            .head
        )
        repeated <- jobs()
          .flatMap(j =>
            j.render(
              tenantId,
              RenditionRequest(
                dms.kernel.DocumentId(doc.id),
                version,
                waiting.id,
                approver,
                Instant.now(),
                Instant.now(),
                Nil
              )
            )
          )
          .either
      yield
        val attached = approved.current.rendition.get
        assertTrue(
          waiting.status == "Rendering",
          waiting.approval.isEmpty,
          inApproval.current.status == "InApproval",
          early.status == Status.NotFound,
          queued.map(_._1).contains("queued"),
          processed == 1,
          done.status == "Approved",
          done.approval.exists(a => a.approvedBy == approver.value && a.version == "1.0"),
          approved.current.status == "Approved",
          approved.current.releasedBy.contains(approver.value),
          attached.objectKey == s"renditions/${tenantId.value}/${doc.id}/$version.pdf",
          attached.objectVersion.isDefined,
          download.status == Status.Ok,
          download.header("Content-Type").exists(_.startsWith("application/pdf")),
          download.header("Content-Disposition").exists(_.contains(s"${doc.id}-1.0.pdf")),
          download.header("ETag").contains("\"" + attached.sha256 + "\""),
          download
            .header("Repr-Digest")
            .contains(
              "sha-256=:" + Base64.getEncoder.encodeToString(HexFormat.of().parseHex(attached.sha256)) + ":"
            ),
          sha256(download.body) == attached.sha256,
          report.compliant,
          texts.size == 2,
          texts.forall(_.contains(s"Dokument-ID ${doc.id} · Version 1.0")),
          texts.forall(_.contains("Gültig ab 01.01.2027")),
          texts.forall(t =>
            "Freigegeben am \\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2} UTC von approver-1".r
              .findFirstIn(t)
              .isDefined
          ),
          texts.zipWithIndex.forall((t, i) => t.contains(s"Seite ${i + 1} von 2")),
          texts.head.contains("Zwischen ACME GmbH"),
          finished.map(_._1).contains("done"),
          again == 0,
          actions == List("document.rendition-attached", "document.rendition-created"),
          events == 1L,
          repeated == Right(None)
        )
    },
    test("only callers who may read the version download its rendition") {
      for
        pair <- approvedByVotes("Globex")
        (doc, waiting) = pair
        _        <- jobs().flatMap(runJobs)
        byReader <- rendition(reader, doc.id, waiting.versionId)
        unknown  <- rendition(author, doc.id, UUID.randomUUID())
        byAppr   <- rendition(approver, doc.id, waiting.versionId)
      yield assertTrue(
        byReader.status == Status.Forbidden,
        unknown.status == Status.NotFound,
        byAppr.status == Status.Ok
      )
    },
    test("a failing rendering keeps the version in approval, notifies the owner and is retried") {
      for
        pair <- approvedByVotes("Initech")
        (doc, waiting) = pair
        version        = waiting.versionId
        first      <- jobs(renderer = Some(unavailable)).flatMap(runJobs)
        afterFirst <- renditionJob(version)
        stillOpen  <- document(doc.id)
        caseOpen   <- cases(doc.id).map(_.head)
        _          <- makeDue(version, maxAttempts = 2)
        _          <- jobs(validator = notArchivable).flatMap(runJobs)
        afterLast  <- renditionJob(version)
        events     <- outbox("document.rendition-failed", doc.id)
        actions    <- auditActions(doc.id)
        download   <- rendition(author, doc.id, version)
        parsed = events.map(_.fromJson[zio.json.ast.Json.Obj].toOption.get)
        fields = (o: zio.json.ast.Json.Obj, key: String) => o.get(key).map(_.toString)
      yield assertTrue(
        first == 1,
        afterFirst.exists((status, attempts, error) =>
          status == "queued" && attempts == 1 && error.exists(_.contains("connection refused"))
        ),
        stillOpen.current.status == "InApproval",
        stillOpen.current.rendition.isEmpty,
        caseOpen.status == "Rendering",
        caseOpen.approval.isEmpty,
        afterLast.exists((status, attempts, error) =>
          status == "dead" && attempts == 2 && error.exists(_.contains("not valid PDF/A-2b"))
        ),
        parsed.size == 2,
        parsed.forall(o => fields(o, "recipients").contains(s"""["${author.value}"]""")),
        parsed.forall(o => fields(o, "versionId").contains(s"\"$version\"")),
        parsed.map(o => fields(o, "final")) == List(Some("false"), Some("true")),
        parsed.last.get("reason").exists(_.toString.contains("font not embedded")),
        actions == List("document.rendition-failed", "document.rendition-failed"),
        download.status == Status.NotFound
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
