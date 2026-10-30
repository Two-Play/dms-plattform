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
import dms.archive.core.{DestructionService, LegalHoldService, ObjectCleanup, OrphanCleanupConfig}
import dms.audit.{AuditEvidence, AuditTrail, EvidenceReportRegistry}
import dms.audit.core.{
  AuditCheckpoints,
  AuditEvidenceLive,
  AuditTrailLive,
  CheckpointConfig,
  EvidenceReportStore,
  TsaTrust
}
import dms.authz.{Authorizer, Groups, Role, RoleAssignment, RoleAssignments}
import dms.db.{Db, DbConfig, DbError, Migrations}
import dms.documents.core.*
import dms.documents.metadata.*
import dms.identity.*
import dms.integration.{JobRunner, WorkerConfig}
import dms.kernel.{TemplateId, TenantId, UserId}
import dms.rendering.{ArchiveStandard, RenditionRequest}
import dms.rendering.core.*
import dms.storage.*
import dms.templates.*
import dms.templates.core.{TemplateDraft, TemplateService}
import dms.workflow.core.{ApprovalWorkflow, Deputyships, FlowableEngine, WorkflowConfig}
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.services.s3.S3Client
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.RichZServerEndpoint
import zio.*
import zio.http.{Body, Header, Headers, MediaType, Request, Status, URL}
import zio.json.*
import zio.stream.ZStream
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.{Base64, Date, HexFormat, UUID}
import javax.sql.DataSource

/** Evidence reports end to end (DMS-57): a document through the whole workflow (votes with reasons, a
  * deputy's approval, the real rendition, publication, acknowledgement), the report as registered JSON and as
  * PDF/A, its checksum, authorization, and the detection of a tampered rendition object and audit entry.
  */
object EvidenceReportApiSpec extends ZIOSpecDefault:

  private val issuer   = "http://localhost:8085"
  private val audience = "project-1"
  private val orgId    = "org-1"
  private val tenantId = TenantId.random()
  private val admin    = UserId("admin-1")
  private val author   = UserId("author-1")
  private val reviewer = UserId("reviewer-1")
  private val approver = UserId("approver-1")
  private val deputy   = UserId("deputy-1")
  private val reader   = UserId("reader-1")
  private val auditor  = UserId("auditor-1")
  private val records  = UserId("records-1")
  private val records2 = UserId("records-2")
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
        List(Block.Paragraph(List(Inline.Text("Der Vertrag gilt ab "), Inline.Placeholder("validFrom"))))
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
            // An approver of another class: not a reader of contracts, but a possible deputy (DMS-49).
            _ <- RoleAssignments.Postgres
              .grant(deputy, RoleAssignment(Role.Approver, documentClass = Some("policy")), admin)
            _ <- RoleAssignments.Postgres.grant(reader, RoleAssignment(Role.Reader), admin)
            _ <- RoleAssignments.Postgres.grant(auditor, RoleAssignment(Role.Auditor), admin)
            _ <- RoleAssignments.Postgres.grant(records, RoleAssignment(Role.RecordsManager), admin)
            _ <- RoleAssignments.Postgres.grant(records2, RoleAssignment(Role.RecordsManager), admin)
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

  private val checkpoints: URLayer[Db & AuditTrail & ObjectStore, AuditCheckpoints] =
    ZLayer.fromFunction((db: Db, trail: AuditTrail, store: ObjectStore) =>
      AuditCheckpoints(db, trail, store, CheckpointConfig())
    )

  private type Env = Authenticator & Db & Authorizer & DocumentService & ApprovalWorkflow &
    AcknowledgementService & ReviewService & LegalHoldService & RoleAssignments & AuditTrail & AuditEvidence &
    EvidenceReportRegistry & RenditionArchive & PdfRenderer & PdfValidator & Deputyships & RenditionSource &
    ObjectStore & AuditCheckpoints & AttachmentUploads & DestructionService & ObjectCleanup

  private val env: ZLayer[Any, Throwable, Env] =
    ZLayer.make[Env](
      database,
      authenticator,
      objectStore,
      RenditionWiring.archive,
      RenderingWorker.renderer,
      ZLayer.succeed[PdfValidator](VeraPdfValidator),
      RoleAssignments.postgres,
      Groups.postgres,
      Authorizer.layer,
      ZLayer.succeed(TsaTrust(Nil)),
      AuditTrailLive.layer,
      checkpoints,
      AuditEvidenceLive.withCheckpoints,
      EvidenceReportStore.layer,
      DocumentClassService.layer,
      DocumentClassProfiles.layer,
      TemplateService.layer,
      DocumentTemplates.layer,
      DocumentService.layer,
      AcknowledgementService.layer,
      ReviewService.layer,
      LegalHoldService.layer,
      DestructionService.layer,
      ZLayer.fromFunction((db: Db, store: ObjectStore, audit: AuditTrail) =>
        ObjectCleanup(db, store, audit, OrphanCleanupConfig())
      ),
      FlowableEngine.layer,
      WorkflowDocuments.layer,
      RoleDirectory.layer,
      WorkflowExtraReview.layer,
      ZLayer.succeed(WorkflowConfig.default),
      WorkflowRendition.layer,
      ApprovalWorkflow.layer,
      Deputyships.layer,
      DocumentRenditions.layer,
      ZLayer.succeed(dms.documents.AttachmentScanner.None),
      ZLayer.succeed(dms.documents.AttachmentSettings.default),
      AttachmentUploads.layer
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
        AcknowledgementApi.endpoints.map(_.widen[Env]) ++ DeputyshipApi.endpoints.map(_.widen[Env]) ++
        EvidenceReportApi.endpoints.map(_.widen[Env]) ++ RetentionApi.endpoints.map(_.widen[Env])
    )

  final case class Response(status: Status, headers: Headers, body: Chunk[Byte]):
    def text: String                         = String(body.toArray, UTF_8)
    def header(name: String): Option[String] = headers.get(name)

  /** `archive` replaces the rendition archive of the environment, e.g. by one over a corrupted store. */
  private def call(
      user: UserId,
      method: String,
      path: String,
      body: Option[String] = None,
      signedIn: Long = 3600,
      archive: Option[RenditionArchive] = None
  ): ZIO[Env, Nothing, Response] =
    val url     = URL.decode(path).toOption.get
    val headers = Headers(
      Header.Authorization.Bearer(token(user, signedIn)),
      Header.ContentType(MediaType.application.json)
    )
    val request = method match
      case "POST" => Request.post(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case "PUT"  => Request.put(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case _      => Request.get(url).addHeaders(headers)
    ZIO
      .scoped(routes.runZIO(request))
      .flatMap(r => r.body.asChunk.map(b => Response(r.status, r.headers, b)).orDie)
      .provideSomeEnvironment[Env](e => archive.fold(e)(a => e.add[RenditionArchive](a)))

  private def as[A: JsonDecoder](response: Response): A =
    response.text
      .fromJson[A]
      .fold(e => throw AssertionError(s"$e: ${response.status} ${response.text}"), identity)

  private def values(partner: String) =
    s""""contractPartner":{"type":"Text","value":"$partner"},
       |"owner":{"type":"PersonRef","userId":"${author.value}"},
       |"organizationalUnit":{"type":"Text","value":"Legal"},
       |"validFrom":{"type":"Date","value":"2026-01-01"},
       |"confidentiality":{"type":"Selection","code":"Internal"},
       |"language":{"type":"Text","value":"de"},
       |"retentionRule":{"type":"Text","value":"contract-10y"},
       |"containsPersonalData":{"type":"YesNo","value":false}""".stripMargin

  private def cases(doc: UUID) =
    call(author, "GET", s"/api/v1/documents/$doc/workflow").map(as[List[WorkflowCaseView]])

  private def vote(user: UserId, task: TaskView, decision: String, reason: Option[String], signedIn: Long) =
    call(
      user,
      "POST",
      s"/api/v1/workflow/tasks/${task.id}/votes",
      Some(CastVote(decision, reason).toJson),
      signedIn
    )

  /** The rendering job of the application: the real worker, veraPDF and SeaweedFS. */
  private def runRenditionJobs: ZIO[Env, DbError, Int] =
    for
      s       <- setup.await
      source  <- ZIO.service[RenditionSource]
      real    <- ZIO.service[PdfRenderer]
      archive <- ZIO.service[RenditionArchive]
      audit   <- ZIO.service[AuditTrail]
      jobs = RenditionJobs(s.app, source, real, VeraPdfValidator, archive, audit)
      ran <- JobRunner(
        s.app,
        Map(RenditionRequest.JobKind -> jobs.handle),
        WorkerConfig("test-rendering")
      ).runOnce
    yield ran

  private def runPublicationJobs: ZIO[Env, Any, Int] =
    for
      s       <- setup.await
      service <- ZIO.service[DocumentService]
      jobs = PublicationJobs(s.app, service, PublicationScheduleConfig())
      _   <- jobs.enqueue(tenantId)
      ran <- JobRunner(s.app, jobs.handlers, WorkerConfig("test-publication")).runOnce
    yield ran

  private def ownerQuery[A](f: DbTx ?=> A): ZIO[Any, DbError, A] =
    setup.await.flatMap(s => s.owner.transactSystem(Db.sql(f)))

  private def sha256(bytes: Array[Byte]) =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

  /** A published contract: reviewed with conditions, approved by a deputy on behalf of the approver,
    * rendered, published and acknowledged by one reader.
    */
  private def publishedDocument(
      partner: String,
      attachment: Option[(String, Array[Byte])] = None
  ): ZIO[Env, Any, DocumentView] =
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
      until = Instant.now().plusSeconds(86400)
      _ <- call(
        approver,
        "POST",
        "/api/v1/deputyships",
        Some(
          s"""{"deputy":"${deputy.value}","validUntil":"$until","roles":["Approver"],"reason":"Vacation"}"""
        )
      )
      _        <- ZIO.foreachDiscard(attachment)((name, bytes) => attach(created, name, bytes))
      _        <- call(author, "POST", s"/api/v1/documents/${created.id}/submit")
      started  <- cases(created.id).map(_.head)
      reviewed <- vote(
        reviewer,
        started.tasks.find(_.status == "Open").get,
        "ApproveWithConditions",
        Some("Annex 2 must be attached"),
        3600
      ).map(as[WorkflowCaseView])
      approval = reviewed.tasks.find(t => t.status == "Open" && t.kind == "Approval").get
      _ <- vote(deputy, approval, "Approve", None, 30)
      _ <- runRenditionJobs
      _ <- runPublicationJobs
      v <- call(reader, "GET", s"/api/v1/documents/${created.id}").map(as[DocumentView])
      _ <- call(reader, "POST", s"/api/v1/documents/${created.id}/versions/${v.current.id}/opened")
      _ <- call(reader, "POST", s"/api/v1/documents/${created.id}/versions/${v.current.id}/acknowledgement")
    yield v

  /** Stores a file in object storage and attaches it to the draft (DMS-114), as the upload endpoint does. */
  private def attach(doc: DocumentView, name: String, bytes: Array[Byte]): ZIO[Env, Any, Unit] =
    for
      s     <- setup.await
      store <- ZIO.service[ObjectStore]
      fileId = UUID.randomUUID()
      put <- store.put(
        s"attachments/${tenantId.value}/${doc.id}/$fileId",
        ObjectContent.Bytes(Chunk.fromArray(bytes)),
        "application/pdf"
      )
      _ <- s.app.transact(tenantId)(
        Db.sql {
          val _ = sql"""insert into dms.document_attachment_file
                          (id, tenant_id, document_id, sha256, size_bytes, content_type, object_key, object_version,
                           scan_status, scanner, scanned_at, created_by)
                        values ($fileId, dms.current_tenant(), ${doc.id}, ${put.sha256.hex}, ${bytes.length.toLong},
                                ${"application/pdf"}, ${put.version.key}, ${put.version.versionId}, ${"NotScanned"},
                                ${"none"}, now(), ${author.value})""".update.run()
          sql"""insert into dms.document_version_attachment (id, tenant_id, version_id, file_id, name, added_by)
                values (${UUID.randomUUID()}, dms.current_tenant(), ${doc.current.id}, $fileId, $name,
                        ${author.value})""".update.run()
        }
      )
    yield ()

  private def createReport(user: UserId, doc: UUID, archive: Option[RenditionArchive] = None) =
    call(user, "POST", s"/api/v1/documents/$doc/evidence-report", archive = archive)

  private def payloadOf(user: UserId, view: EvidenceReportView) =
    call(user, "GET", view.jsonUrl)

  /** An object store whose reads return the stored bytes with one byte changed: a corrupted object. */
  private def corrupted(store: ObjectStore): ObjectStore = new ObjectStore:
    def put(
        key: String,
        content: ObjectContent,
        contentType: String,
        retention: Option[Retention],
        legalHold: Boolean,
        expectedSha256: Option[Sha256]
    ): IO[StorageError, PutResult] =
      store.put(key, content, contentType, retention, legalHold, expectedSha256)
    def head(key: String, versionId: Option[String]): IO[StorageError, ObjectInfo] =
      store.head(key, versionId)
    def setLegalHold(key: String, versionId: String, on: Boolean): IO[StorageError, Unit] =
      store.setLegalHold(key, versionId, on)
    def extendRetention(key: String, versionId: String, retention: Retention): IO[StorageError, Unit] =
      store.extendRetention(key, versionId, retention)
    def delete(key: String, versionId: String, bypassGovernance: Boolean): IO[StorageError, Unit] =
      store.delete(key, versionId, bypassGovernance)
    def listKeys(prefix: String): IO[StorageError, List[String]]              = store.listKeys(prefix)
    def versions(key: String): IO[StorageError, List[ObjectVersion]]          = store.versions(key)
    def get(key: String, versionId: String): ZStream[Any, StorageError, Byte] =
      store.get(key, versionId).zipWithIndex.map((b, i) => if i == 100L then (b ^ 0x01).toByte else b)

  def spec = suite("Evidence reports")(
    test("a report covers versions, votes with reasons, the deputy, acknowledgements and integrity") {
      for
        doc     <- publishedDocument("ACME GmbH")
        _       <- ZIO.serviceWithZIO[AuditCheckpoints](_.write(tenantId))
        created <- createReport(auditor, doc.id)
        view = as[EvidenceReportView](created)
        json <- payloadOf(auditor, view)
        parsed  = EvidenceReport.parse(json.text).fold(e => throw AssertionError(e), identity)
        version = parsed.versions.head
        votes   = version.workflow.flatMap(_.votes)
        pdf   <- call(auditor, "GET", view.pdfUrl)
        valid <- VeraPdfValidator.validate(pdf.body, ArchiveStandard.PdfA2b)
        texts = RenderingWorker.pageTexts(pdf.body).map(RenderingWorker.normalized)
        audited <- ownerQuery(
          sql"""select details->>'sha256', actor_id from dms.audit_event
                where object_id = ${doc.id} and action = ${EvidenceReportApi.CreatedAction}"""
            .query[(String, String)]
            .run()
            .toList
        )
        registered <- ownerQuery(
          sql"select sha256, created_by from dms.evidence_report where id = ${view.id}"
            .query[(String, String)]
            .run()
            .toList
        )
      yield assertTrue(
        created.status == Status.Created,
        view.integrityStatus == "Intact",
        view.createdBy == auditor.value,
        view.pdfUrl == s"${view.jsonUrl}.pdf",
        json.status == Status.Ok,
        sha256(json.body.toArray) == view.sha256,
        json.header("ETag").contains("\"" + view.sha256 + "\""),
        json
          .header("Repr-Digest")
          .contains(
            "sha-256=:" + Base64.getEncoder.encodeToString(HexFormat.of().parseHex(view.sha256)) + ":"
          ),
        // the JSON is canonical: parsing and serializing again gives the same bytes
        EvidenceReport.canonical(parsed) == json.text,
        parsed.reportId == view.id,
        parsed.createdBy == auditor.value,
        parsed.createdAt == view.createdAt,
        parsed.document.id == doc.id,
        parsed.versions.size == 1,
        version.version == "1.0",
        version.status == "Published",
        version.rendition.exists(_.sha256 == doc.current.rendition.get.sha256),
        version.validity.exists(_.until.isEmpty),
        version.releasedBy.contains(deputy.value),
        votes.exists(v =>
          v.voter == reviewer.value && v.decision == "ApproveWithConditions" &&
            v.reason.contains("Annex 2 must be attached") && v.actingFor.isEmpty
        ),
        votes.exists(v => v.voter == deputy.value && v.actingFor.contains(approver.value)),
        version.workflow
          .flatMap(_.approval)
          .exists(a => a.approvedBy == deputy.value && a.actingFor.contains(approver.value)),
        // readers of contracts: author, reviewer, approver, reader, auditor, two records managers (not the deputy's
        // policy-only role, not the administrator)
        version.acknowledgements.exists(a =>
          a.audience == 7 && a.acknowledged == 1 && a.ratePercent.contains("14.3") &&
            a.entries.map(_.userId) == List(reader.value)
        ),
        parsed.integrity.checks.map(c => (c.check, c.status)).toSet == Set(
          "approved content"   -> "Match",
          "metadata snapshot"  -> "Match",
          "rendition checksum" -> "Match"
        ),
        parsed.integrity.auditChain.intact,
        parsed.integrity.auditChain.documentEntriesVerified,
        parsed.integrity.auditChain.documentEntriesCheckpointed,
        !parsed.integrity.auditChain.documentEntriesAnchored,
        parsed.auditTrail.map(_.action).contains("document.created"),
        parsed.auditTrail.map(_.action).contains("workflow.voted"),
        parsed.auditTrail.map(_.action).contains("document.acknowledged"),
        parsed.auditTrail.forall(_.hash.matches("[0-9a-f]{64}")),
        parsed.notes.contains(EvidenceReport.PersonNote),
        audited == List((view.sha256, auditor.value)),
        registered == List((view.sha256, auditor.value)),
        pdf.status == Status.Ok,
        pdf.header("Content-Type").exists(_.startsWith("application/pdf")),
        pdf.header("X-Evidence-Report-Sha256").contains(view.sha256),
        valid.compliant,
        texts.forall(_.contains(s"SHA-256 (JSON payload): ${view.sha256}")),
        texts.forall(_.contains(s"Evidence report ${view.id}")),
        texts.exists(_.contains("Annex 2 must be attached")),
        texts.exists(_.contains(s"${deputy.value} on behalf of ${approver.value}")),
        texts.exists(_.contains("1 of 7 readers (14.3 %)"))
      )
    },
    test("only auditors and records managers create and read reports") {
      for
        doc       <- publishedDocument("Globex")
        byReader  <- createReport(reader, doc.id)
        byAuthor  <- createReport(author, doc.id)
        byAdmin   <- createReport(admin, doc.id)
        byRecords <- createReport(records, doc.id)
        view = as[EvidenceReportView](byRecords)
        readByReader  <- payloadOf(reader, view)
        readByAuditor <- payloadOf(auditor, view)
        unknown  <- call(auditor, "GET", s"/api/v1/documents/${doc.id}/evidence-reports/${UUID.randomUUID()}")
        otherDoc <- call(
          auditor,
          "GET",
          s"/api/v1/documents/${UUID.randomUUID()}/evidence-reports/${view.id}"
        )
        pdfByReader <- call(reader, "GET", view.pdfUrl)
      yield assertTrue(
        byReader.status == Status.Forbidden,
        byAuthor.status == Status.Forbidden,
        byAdmin.status == Status.Forbidden,
        byRecords.status == Status.Created,
        view.createdBy == records.value,
        readByReader.status == Status.Forbidden,
        readByAuditor.status == Status.Ok,
        unknown.status == Status.NotFound,
        otherDoc.status == Status.NotFound,
        pdfByReader.status == Status.Forbidden
      )
    },
    test("the registry only accepts a payload with its own checksum") {
      for
        doc    <- publishedDocument("Initech")
        view   <- createReport(auditor, doc.id).map(as[EvidenceReportView])
        forged <- setup.await.flatMap(s =>
          s.app
            .transact(tenantId)(
              ZIO.serviceWithZIO[EvidenceReportRegistry](
                _.register(
                  dms.audit.StoredEvidenceReport(
                    UUID.randomUUID(),
                    doc.id,
                    1,
                    Instant.now(),
                    auditor.value,
                    """{"forged":true}""",
                    view.sha256
                  )
                )
              )
            )
            .either
        )
        changed <- ownerQuery(
          sql"update dms.evidence_report set created_by = 'someone-else' where id = ${view.id}".update.run()
        ).either
      yield assertTrue(forged.isLeft, changed.isLeft)
    },
    test("a corrupted rendition object makes the report Violated") {
      for
        doc   <- publishedDocument("Umbrella")
        store <- ZIO.service[ObjectStore]
        view  <- createReport(records, doc.id, archive = Some(RenditionArchive.objectStore(corrupted(store))))
          .map(as[EvidenceReportView])
        parsed <- payloadOf(records, view).map(r => EvidenceReport.parse(r.text).toOption.get)
        check = parsed.integrity.checks.find(_.check == "rendition checksum").get
      yield assertTrue(
        view.integrityStatus == "Violated",
        check.status == "Mismatch",
        check.expected.contains(doc.current.rendition.get.sha256),
        view.findings.exists(_.contains("rendition checksum mismatch")),
        parsed.integrity.auditChain.intact
      )
    },
    test("attachments are listed with their checksums and read back from object storage (DMS-114)") {
      val annex = "%PDF-1.7 annex 2".getBytes(UTF_8)
      for
        doc    <- publishedDocument("Cyberdyne", Some(("Annex 2.pdf", annex)))
        intact <- createReport(auditor, doc.id).map(as[EvidenceReportView])
        parsed <- payloadOf(auditor, intact).map(r => EvidenceReport.parse(r.text).toOption.get)
        listed = parsed.versions.head.attachments.toList.flatten
        check  = parsed.integrity.checks.find(_.check == "attachment checksum (Annex 2.pdf)")
        // A recorded checksum that does not match the stored bytes is a violation.
        _ <- ownerQuery {
          val _ = sql"set local session_replication_role = replica".update.run()
          sql"""update dms.document_attachment_file set sha256 = ${"0" * 64}
                where document_id = ${doc.id}""".update.run()
        }
        violated <- createReport(auditor, doc.id).map(as[EvidenceReportView])
        broken   <- payloadOf(auditor, violated).map(r => EvidenceReport.parse(r.text).toOption.get)
        mismatch = broken.integrity.checks.find(_.check == "attachment checksum (Annex 2.pdf)")
      yield assertTrue(
        intact.integrityStatus == "Intact",
        listed.map(a => (a.name, a.sha256, a.size)) == List(
          ("Annex 2.pdf", sha256(annex), annex.length.toLong)
        ),
        check.exists(c => c.status == "Match" && c.expected.contains(sha256(annex))),
        violated.integrityStatus == "Violated",
        mismatch.exists(_.status == "Mismatch"),
        // the approval bound the attachments' checksums as part of the content (DMS-114)
        broken.integrity.checks.exists(c => c.check == "approved content" && c.status == "Mismatch")
      )
    },
    test("a changed audit entry of the document makes the report Violated") {
      for
        doc <- publishedDocument("Soylent")
        seq <- ownerQuery(
          sql"""select chain_seq from dms.audit_event where object_id = ${doc.id} and action = 'workflow.voted'
                order by chain_seq limit 1""".query[Long].run().head
        )
        tamper = (reason: Option[String]) =>
          ownerQuery {
            val _ = sql"set local session_replication_role = replica".update.run()
            sql"""update dms.audit_event set reason = $reason
                  where tenant_id = ${tenantId.value} and chain_seq = $seq""".update.run()
          }
        original <- ownerQuery(
          sql"select reason from dms.audit_event where tenant_id = ${tenantId.value} and chain_seq = $seq"
            .query[Option[String]]
            .run()
            .head
        )
        _        <- tamper(Some("approved without conditions"))
        violated <- createReport(auditor, doc.id).map(as[EvidenceReportView])
        parsed   <- payloadOf(auditor, violated).map(r => EvidenceReport.parse(r.text).toOption.get)
        _        <- tamper(original)
        restored <- createReport(auditor, doc.id).map(as[EvidenceReportView])
      yield assertTrue(
        violated.integrityStatus == "Violated",
        !parsed.integrity.auditChain.intact,
        parsed.integrity.auditChain.violation.exists(v => v.chainSeq == seq && v.kind == "HashMismatch"),
        !parsed.integrity.auditChain.documentEntriesVerified,
        violated.findings.exists(_.contains(s"position $seq")),
        restored.integrityStatus == "Intact"
      )
    },
    test(
      "destruction after retention (DMS-82): four eyes, a tombstone, the document is gone, the report intact"
    ) {
      for
        doc <- publishedDocument("Vernichtung AG", attachment = Some(("Annex.pdf", "annex".getBytes(UTF_8))))
        rendition = doc.current.rendition.get
        // Out of force since 2010 (withdrawn); the lifecycle up to there is not under test.
        _ <- ownerQuery {
          val _ = sql"set local session_replication_role = replica".update.run()
          sql"""update dms.document_version
                set status = 'Withdrawn', withdrawn_at = '2010-03-01T10:00:00Z',
                    status_changed_at = '2010-03-01T10:00:00Z'
                where id = ${doc.current.id}""".update.run()
        }
        rule <- call(
          records,
          "PUT",
          "/api/v1/retention/rules/contract-10y",
          Some(
            """{"name":"Contracts, 10 years","periodMonths":120,"trigger":"EndOfValidity","fromYearEnd":true}"""
          )
        )
        badRule <- call(
          records,
          "PUT",
          "/api/v1/retention/rules/Bad%20Key",
          Some("""{"name":"x","periodMonths":12}""")
        )
        notAllowed <- call(reader, "GET", "/api/v1/retention/destruction-list")
        list       <- call(records, "GET", "/api/v1/retention/destruction-list").map(as[DestructionListView])
        candidate = list.candidates.find(_.versionId == doc.current.id)
        noReason <- call(
          records,
          "POST",
          "/api/v1/retention/destruction-proposals",
          Some(s"""{"versionIds":["${doc.current.id}"]}""")
        )
        proposed <- call(
          records,
          "POST",
          "/api/v1/retention/destruction-proposals",
          Some(s"""{"versionIds":["${doc.current.id}"],"reason":"Retention of 2020 ended"}""")
        )
        proposal = as[DestructionProposalView](proposed)
        self <- call(
          records,
          "POST",
          s"/api/v1/retention/destruction-proposals/${proposal.id}/approve",
          Some("""{"reason":"Myself"}""")
        )
        open <- call(records2, "GET", "/api/v1/retention/destruction-proposals").map(
          as[List[DestructionProposalView]]
        )
        approved <- call(
          records2,
          "POST",
          s"/api/v1/retention/destruction-proposals/${proposal.id}/approve",
          Some("""{"reason":"Checked against the register"}""")
        )
        decided = as[DestructionProposalView](approved)
        // The objects go after the commit.
        s             <- setup.await
        cleanup       <- ZIO.service[ObjectCleanup]
        _             <- JobRunner(s.app, cleanup.handlers, WorkerConfig("test-cleanup")).runOnce
        store         <- ZIO.service[ObjectStore]
        renditionGone <- store.head(rendition.objectKey, rendition.objectVersion).either
        opened        <- call(records, "GET", s"/api/v1/documents/${doc.id}")
        listed        <- call(author, "GET", "/api/v1/documents")
        _             <- ZIO.serviceWithZIO[AuditCheckpoints](_.write(tenantId))
        created       <- createReport(auditor, doc.id)
        view = as[EvidenceReportView](created)
        json <- payloadOf(auditor, view)
        parsed  = EvidenceReport.parse(json.text).fold(e => throw AssertionError(e), identity)
        version = parsed.versions.head
        checks  = parsed.integrity.checks
          .filter(_.versionId == doc.current.id)
          .map(c => c.check -> c.status)
          .toMap
        pdf <- call(auditor, "GET", view.pdfUrl)
        texts = RenderingWorker.pageTexts(pdf.body).map(RenderingWorker.normalized).mkString(" ")
      yield assertTrue(
        rule.status == Status.Ok,
        badRule.status == Status.UnprocessableEntity,
        notAllowed.status == Status.Forbidden,
        candidate.exists(c => c.title.contains("Vertrag Vernichtung AG") && c.manageable),
        candidate.exists(_.retentionEndsOn == java.time.LocalDate.of(2020, 12, 31)),
        noReason.status == Status.UnprocessableEntity,
        proposed.status == Status.Created,
        !proposal.canDecide,
        self.status == Status.Forbidden,
        self.text.contains("four_eyes"),
        open.find(_.id == proposal.id).exists(_.canDecide),
        approved.status == Status.Ok,
        decided.status == "Approved",
        decided.items.map(_.outcome) == List("Destroyed"),
        decided.items.head.renditionSha256.contains(rendition.sha256),
        decided.items.head.files.map(f => (f.name, f.released)) == List(("Annex.pdf", true)),
        renditionGone.left.exists(_.isInstanceOf[StorageError.NotFound]),
        opened.status == Status.NotFound,
        listed.status == Status.Ok,
        !listed.text.contains(doc.id.toString),
        version.status == "Destroyed",
        version.destruction.exists(d => d.approvedBy == records2.value && d.proposedBy == records.value),
        version.destruction.exists(_.renditionSha256.contains(rendition.sha256)),
        version.rendition.exists(_.sha256 == rendition.sha256),
        checks.get("destruction record").contains("Match"),
        checks.get("destroyed rendition").contains("Match"),
        !checks.contains("rendition checksum"),
        !checks.values.exists(_ == "Mismatch"),
        view.integrityStatus == "Intact",
        parsed.auditTrail.exists(_.action == "document.destroyed"),
        texts.contains("Destruction")
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
