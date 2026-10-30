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
import dms.documents.{AttachmentScanner, AttachmentSettings}
import dms.documents.core.*
import dms.identity.*
import dms.integration.{JobRunner, WorkerConfig}
import dms.kernel.{DocumentId, TemplateId, TenantId, UserId}
import dms.rendering.{ArchiveStandard, RenditionRequest}
import dms.rendering.core.*
import dms.rendering.core.WordFixtures.*
import dms.storage.{ObjectStore, S3ObjectStore, StorageConfig}
import dms.templates.*
import dms.templates.core.{ProfileSource, TemplateDraft, TemplateService}
import dms.workflow.core.{ApprovalWorkflow, FlowableEngine, WorkflowConfig}
import org.apache.pdfbox.pdmodel.{PDDocument, PDPage}
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

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.{Instant, LocalDate}
import java.util.{Date, HexFormat, UUID}
import javax.sql.DataSource

/** File documents and Word templates end to end (DMS-115): a designer uploads a Word template with content
  * controls and edits its field definitions; it is released with four eyes; an author creates a document from
  * it, enters the manual fields, downloads the filled Word document and a preview, and the approval produces
  * the archived rendition from the filled document. Plain file uploads, the checks and the permissions.
  * Against PostgreSQL and SeaweedFS started by Testcontainers; the conversion worker and the rendering worker
  * are replaced by stand-ins that record their input (the real workers are covered by `ConversionWorkerSpec`
  * and the rendering specs).
  */
object FileDocumentApiSpec extends ZIOSpecDefault:

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

  private val names = Map(author -> "Ada Author", approver -> "Anna Approver", owner -> "Otto Owner")

  private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("key-1").generate()

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  final case class Setup(app: Db, owner: Db, classId: UUID, structured: TemplateId)

  private val setup = Unsafe.unsafe(implicit u => Promise.unsafe.make[Nothing, Setup](FiberId.None))

  /** The virus scanner of the uploads: the ClamAV adapter against an in-process `clamd` (DMS-117). */
  private val clamd = Unsafe.unsafe(implicit u => Promise.unsafe.make[Nothing, FakeClamd](FiberId.None))

  private val scanner: ZLayer[Any, Throwable, AttachmentScanner] = ZLayer.scoped(
    FakeClamd.make
      .tap(clamd.succeed)
      .map(f => ClamAvScanner(ClamAvConfig("127.0.0.1", f.port, 5)): AttachmentScanner)
  )

  private val structuredContent = TemplateContent(
    List(
      Section(
        SectionId("rules"),
        Some("Rules"),
        locked = false,
        List(Block.Paragraph(List(Inline.Text("Maintenance is done monthly."))))
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
      created <- app
        .transact(tenantId)(
          for
            _   <- RoleAssignments.Postgres.grant(admin, RoleAssignment(Role.TenantAdmin), admin)
            _   <- RoleAssignments.Postgres.grant(author, RoleAssignment(Role.Author), admin)
            _   <- RoleAssignments.Postgres.grant(owner, RoleAssignment(Role.Owner), admin)
            _   <- RoleAssignments.Postgres.grant(reviewer, RoleAssignment(Role.Reviewer), admin)
            _   <- RoleAssignments.Postgres.grant(approver, RoleAssignment(Role.Approver), admin)
            _   <- RoleAssignments.Postgres.grant(reader, RoleAssignment(Role.Reader), admin)
            _   <- RoleAssignments.Postgres.grant(designer, RoleAssignment(Role.TemplateDesigner), admin)
            _   <- RoleAssignments.Postgres.grant(designer2, RoleAssignment(Role.TemplateDesigner), admin)
            cls <- classes.createClass("contract", None, ClassDraft("Contract", None, Nil), admin)
            t   <- templates.create(
              TemplateDraft("Maintenance contract", None, cls.classId.value, 1, structuredContent),
              designer
            )
            _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Testing, designer)
            _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Released, designer2)
          yield (cls.classId.value, t.templateId)
        )
        .mapError(e => RuntimeException(e.toString))
        .orDie
      _ <- setup.succeed(Setup(app, schemaOwner, created._1, created._2))
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

  /** A PDF with `pages` empty pages. */
  private def blankPdf(pages: Int): Chunk[Byte] =
    val document = PDDocument()
    try
      (1 to pages).foreach(_ => document.addPage(PDPage()))
      val out = ByteArrayOutputStream()
      document.save(out)
      Chunk.fromArray(out.toByteArray)
    finally document.close()

  /** What the stand-in conversion worker was asked to convert, latest first. */
  private val conversions = Unsafe.unsafe(implicit u => Ref.unsafe.make(List.empty[(String, Chunk[Byte])]))

  private val converter: DocumentConverter = (bytes, name, target) =>
    conversions.update((name, bytes) :: _) *> ZIO.succeed(
      target match
        case ConversionTarget.PdfA2b => ConvertedFile(blankPdf(1), "stub-converter")
        case ConversionTarget.Docx   => ConvertedFile(bytes, "stub-converter")
    )

  private val renderer: PdfRenderer = _ => ZIO.succeed(RenderedPdf(blankPdf(1), Some(1), "stub"))

  private val validator: PdfValidator = (_, standard: ArchiveStandard) =>
    ZIO.succeed(ValidationReport(standard, compliant = true, "stub", Nil))

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

  private val directory: ULayer[UserDirectory & TenantOrganizations] =
    ZLayer.succeed[UserDirectory](new UserDirectory:
      def lookup(organizationId: String, ids: Set[UserId]): IO[UserDirectoryError, Map[UserId, UserInfo]] =
        ZIO.succeed(ids.flatMap(id => names.get(id).map(n => id -> UserInfo(id, n, None))).toMap)) ++ ZLayer
      .succeed[TenantOrganizations](new TenantOrganizations:
        def organizationOf(tenant: TenantId): IO[DbError, Option[String]] = ZIO.succeed(Some(orgId)))

  private type Env = Authenticator & Db & Authorizer & DocumentService & ApprovalWorkflow & TemplateService &
    ProfileSource & PdfRenderer & AuditTrail & TenantOrganizations & UserDirectory & FileVersions &
    WordTemplateService & AttachmentService & AttachmentUploads & RenditionSource & RenditionArchive &
    ObjectStore & DocumentClassService

  private val env: ZLayer[Any, Throwable, Env] =
    ZLayer.make[Env](
      database,
      authenticator,
      directory,
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
      DocumentRenditions.withNames,
      RenditionWiring.archive,
      ZLayer.succeed(converter),
      ZLayer.succeed(renderer),
      FileVersions.layer,
      WordTemplateService.layer,
      scanner,
      ZLayer.succeed(AttachmentSettings(1024L * 1024, AttachmentSettings.DefaultBlockedExtensions)),
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
      FileDocumentApi.endpoints.map(_.widen[Env]) ++ DocumentApi.endpoints.map(_.widen[Env]) ++
        WorkflowApi.endpoints.map(_.widen[Env]) ++ WordTemplateApi.endpoints.map(_.widen[Env]) ++
        VersionViewApi.endpoints.map(_.widen[Env]) ++ AttachmentApi.endpoints.map(_.widen[Env]) ++
        VersionCompareApi.endpoints.map(_.widen[Env]) ++ UploadFlowApi.endpoints.map(_.widen[Env])
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
      case "POST" => Request.post(url, Body.fromString(body.getOrElse("{}"))).addHeaders(json)
      case "PUT"  => Request.put(url, Body.fromString(body.getOrElse("{}"))).addHeaders(json)
      case _      => Request.get(url)
    send(user, request)

  private val boundary = "----dms-test-boundary-7MA4YWxkTrZu0gW"

  private def multipart(name: String, bytes: Array[Byte]): Body =
    val head =
      s"--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\n" +
        "Content-Type: application/octet-stream\r\n\r\n"
    val all = head.getBytes(UTF_8) ++ bytes ++ s"\r\n--$boundary--\r\n".getBytes(UTF_8)
    Body.fromStreamChunked(ZStream.fromIterable(all.grouped(1000).map(Chunk.fromArray).toList).flattenChunks)

  private def put(user: UserId, path: String, name: String, bytes: Array[Byte]) =
    send(
      user,
      Request
        .put(URL.decode(path).toOption.get, multipart(name, bytes))
        .addHeader(Header.ContentType(MediaType.multipart.`form-data`, Some(zio.http.Boundary(boundary))))
    )

  /** A multipart form with an optional part `values` before the part `file` (DMS-127). */
  private def form(
      user: UserId,
      path: String,
      name: String,
      bytes: Array[Byte],
      values: Option[String] = None
  ) =
    val fields =
      values.fold("")(v => s"--$boundary\r\nContent-Disposition: form-data; name=\"values\"\r\n\r\n$v\r\n")
    val head =
      fields + s"--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\n" +
        "Content-Type: application/octet-stream\r\n\r\n"
    val all = head.getBytes(UTF_8) ++ bytes ++ s"\r\n--$boundary--\r\n".getBytes(UTF_8)
    send(
      user,
      Request
        .post(URL.decode(path).toOption.get, Body.fromChunk(Chunk.fromArray(all)))
        .addHeader(Header.ContentType(MediaType.multipart.`form-data`, Some(zio.http.Boundary(boundary))))
    )

  private def as[A: JsonDecoder](response: Response): A =
    response.text
      .fromJson[A]
      .fold(e => throw AssertionError(s"$e: ${response.status} ${response.text}"), identity)

  private def sha256(bytes: Array[Byte]) =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private val past = LocalDate.of(2026, 1, 1)

  private def values(language: String) =
    s"""{"owner":{"type":"PersonRef","userId":"${owner.value}"},
       |"organizationalUnit":{"type":"Text","value":"Finance"},
       |"validFrom":{"type":"Date","value":"$past"},
       |"confidentiality":{"type":"Selection","code":"Internal"},
       |"language":{"type":"Text","value":"$language"},
       |"retentionRule":{"type":"Text","value":"policy-10y"},
       |"containsPersonalData":{"type":"YesNo","value":false}}""".stripMargin

  /** The Word template: automatic fields in the header and body, manual fields as control and placeholder. */
  private val template = docx(
    paragraph(textRun("Wartungsvertrag ") + control("dms:documentNumber", "Dokumentnummer", "Nummer")) +
      paragraph(textRun("Kunde: ") + control("customer", "Customer", "Name des Kunden")) +
      paragraph(textRun("Lieferung bis {{deliveryDate}}")) +
      paragraph(
        textRun("Freigegeben am ") + control("dms:approvedAt", "Freigabe", "–") + textRun(" von ") +
          control("dms:approvedBy", "Freigeber", "–")
      ),
    header = Some(
      paragraph(
        control("dms:title", "Titel", "Titel") + textRun(" · Version ") + control("dms:version", "V", "x")
      ) +
        paragraph(textRun("Herausgeber: ") + control("dms:issuingUnit", "Stelle", "Stelle"))
    )
  )

  private def wordTemplate: ZIO[Env, Any, (UUID, WordTemplateView)] =
    for
      s <- setup.await
      t <- ZIO.serviceWithZIO[TemplateService](svc =>
        s.app.transact(tenantId)(
          svc.create(
            TemplateDraft("Wartungsvertrag (Word)", None, s.classId, 1, WordTemplateApi.EmptyContent),
            designer
          )
        )
      )
      uploaded <- put(
        designer,
        s"/api/v1/admin/templates/${t.templateId.value}/versions/1/word",
        "Wartungsvertrag.docx",
        template
      ).map(as[WordTemplateView])
    yield (t.templateId.value, uploaded)

  private def release(id: UUID, version: Int): ZIO[Env, Any, Unit] =
    for
      s <- setup.await
      _ <- ZIO.serviceWithZIO[TemplateService](svc =>
        s.app.transact(tenantId)(
          svc.changeStatus(TemplateId(id), version, TemplateStatus.Testing, designer) *>
            svc.changeStatus(TemplateId(id), version, TemplateStatus.Released, designer2)
        )
      )
    yield ()

  private def cases(doc: UUID) =
    call(author, "GET", s"/api/v1/documents/$doc/workflow").map(as[List[WorkflowCaseView]])

  private def vote(user: UserId, task: TaskView) =
    call(user, "POST", s"/api/v1/workflow/tasks/${task.id}/votes", Some(CastVote("Approve", None).toJson))

  /** Submits the draft, approves it and runs the rendering job with the stand-in converter. */
  private def approve(doc: UUID): ZIO[Env, Any, Unit] =
    for
      s           <- setup.await
      submitted   <- call(author, "POST", s"/api/v1/documents/$doc/submit")
      _           <- ZIO.unless(submitted.status == Status.Ok)(ZIO.fail(s"submit: ${submitted.text}"))
      review      <- cases(doc).map(_.last.tasks.find(_.status == "Open").get)
      afterReview <- vote(reviewer, review).map(as[WorkflowCaseView])
      _           <- vote(approver, afterReview.tasks.find(_.status == "Open").get)
      source      <- ZIO.service[RenditionSource]
      audit       <- ZIO.service[AuditTrail]
      archive     <- ZIO.service[RenditionArchive]
      jobs = RenditionJobs(
        s.app,
        source,
        renderer,
        validator,
        archive,
        audit,
        ArchiveStandard.default,
        converter
      )
      _ <- JobRunner(s.app, Map(RenditionRequest.JobKind -> jobs.handle), WorkerConfig("file-test")).runOnce
    yield ()

  private def ownerQuery[A](f: DbTx ?=> A): ZIO[Any, DbError, A] =
    setup.await.flatMap(s => s.owner.transactSystem(Db.sql(f)))

  private def actions(doc: UUID) =
    ownerQuery(
      sql"""select action from dms.audit_event where object_id = $doc order by seq"""
        .query[String]
        .run()
        .toList
    )

  private def lastConversion = conversions.get.map(_.head)

  /** Approves the draft and publishes it (valid from a past date), so that a new version can be started. */
  private def approveAndPublish(doc: UUID): ZIO[Env, Any, Unit] =
    for
      _    <- approve(doc)
      s    <- setup.await
      jobs <- ZIO.serviceWith[DocumentService](PublicationJobs(s.app, _, PublicationScheduleConfig()))
      _    <- jobs.enqueue(tenantId)
      _    <- JobRunner(s.app, jobs.handlers, WorkerConfig("publication-test")).runOnce
    yield ()

  private def newVersion(doc: UUID) =
    call(
      author,
      "POST",
      s"/api/v1/documents/$doc/versions",
      Some("""{"changeKind":"Major","reason":"Content revised"}""")
    )
      .map(as[DocumentVersionView])

  def spec = suite("File documents and Word templates")(
    test("a designer uploads a Word template, edits its fields and releases it with four eyes") {
      for
        created <- wordTemplate
        (id, uploaded) = created
        base           = s"/api/v1/admin/templates/$id/versions/1/word"
        byReader <- call(reader, "GET", base)
        edited   <- call(
          designer,
          "PUT",
          s"$base/fields",
          Some(
            WordFieldDefinitions(
              uploaded.fields.map(f =>
                f.key match
                  case "customer"     => f.copy(label = "Kunde", required = true)
                  case "deliveryDate" => f.copy(label = "Liefertermin", `type` = "Date")
                  case _              => f
              )
            ).toJson
          )
        ).map(as[WordTemplateView])
        wrongKeys <- call(
          designer,
          "PUT",
          s"$base/fields",
          Some(WordFieldDefinitions(edited.fields.tail).toJson)
        )
        _          <- release(id, 1)
        frozen     <- put(designer, base, "Other.docx", template)
        catalog    <- call(author, "GET", "/api/v1/word-templates").map(as[List[WordTemplateView]])
        downloaded <- call(designer, "GET", s"$base/file")
        next <- call(designer, "POST", s"/api/v1/admin/templates/$id/word-versions").map(as[WordTemplateView])
      yield assertTrue(
        uploaded.status == "Draft",
        uploaded.sha256 == sha256(template),
        uploaded.fields.map(f => (f.key, f.source)) == List(
          "documentNumber" -> "Automatic",
          "customer"       -> "Manual",
          "deliveryDate"   -> "Manual",
          "approvedAt"     -> "Automatic",
          "approvedBy"     -> "Automatic",
          "title"          -> "Automatic",
          "version"        -> "Automatic",
          "issuingUnit"    -> "Automatic"
        ),
        byReader.status == Status.Forbidden,
        edited.fields.find(_.key == "customer").exists(f => f.required && f.label == "Kunde"),
        edited.fields.find(_.key == "deliveryDate").exists(_.`type` == "Date"),
        wrongKeys.status == Status.UnprocessableEntity,
        frozen.status == Status.Conflict,
        catalog.exists(t => t.templateId == id && t.status == "Released"),
        downloaded.body.toArray.sameElements(template),
        next.version == 2,
        next.status == "Draft",
        next.sha256 == uploaded.sha256,
        next.fields.find(_.key == "customer").exists(_.label == "Kunde")
      )
    },
    test("an author fills a document from the Word template; the approval archives the filled document") {
      for
        s       <- setup.await
        created <- wordTemplate
        (id, uploaded) = created
        _ <- call(
          designer,
          "PUT",
          s"/api/v1/admin/templates/$id/versions/1/word/fields",
          Some(
            WordFieldDefinitions(
              uploaded.fields.map(f =>
                if f.key == "customer" then f.copy(required = true)
                else if f.key == "deliveryDate" then f.copy(`type` = "Date")
                else f
              )
            ).toJson
          )
        )
        _   <- release(id, 1)
        doc <- call(
          author,
          "POST",
          "/api/v1/documents/word",
          Some(
            s"""{"templateId":"$id","templateVersion":1,"title":"Wartungsvertrag ACME","values":${values(
                "de"
              )}}"""
          )
        ).map(as[DocumentView])
        fieldsUrl = s"/api/v1/documents/${doc.id}/versions/${doc.current.id}/word-fields"
        initial    <- call(author, "GET", fieldsUrl).map(as[WordFieldsView])
        incomplete <- call(author, "POST", s"/api/v1/documents/${doc.id}/submit")
        badDate    <- call(
          author,
          "PUT",
          s"/api/v1/documents/${doc.id}/word-fields",
          Some("""{"values":{"deliveryDate":"31.01.2027"}}""")
        )
        filledIn <- call(
          author,
          "PUT",
          s"/api/v1/documents/${doc.id}/word-fields",
          Some("""{"values":{"customer":"ACME GmbH","deliveryDate":"2027-01-31"}}""")
        ).map(as[DocumentVersionView])
        byReader <- call(
          reader,
          "PUT",
          s"/api/v1/documents/${doc.id}/word-fields",
          Some("""{"values":{"customer":"x"}}""")
        )
        filled <- call(author, "GET", s"/api/v1/documents/${doc.id}/versions/${doc.current.id}/filled.docx")
        body = part(filled.body.toArray, "word/document.xml")
        head = part(filled.body.toArray, "word/header1.xml")
        preview <- call(author, "GET", s"/api/v1/documents/${doc.id}/versions/${doc.current.id}/preview.pdf")
        converted <- lastConversion
        draftPdf  <- call(
          author,
          "GET",
          s"/api/v1/documents/${doc.id}/versions/${doc.current.id}/draft.pdf?changelog=true&attachments=true"
        )
        html        <- call(author, "GET", s"/api/v1/documents/${doc.id}/versions/${doc.current.id}/html")
        _           <- approve(doc.id)
        approvedDoc <- call(author, "GET", s"/api/v1/documents/${doc.id}").map(as[DocumentView])
        archived    <- lastConversion
        archivedBody = part(archived._2.toArray, "word/document.xml")
        approval <- cases(doc.id).map(_.last.approval.get)
        details  <- ZIO.serviceWithZIO[DocumentService](svc =>
          s.app.transact(tenantId)(svc.get(DocumentId(doc.id)))
        )
        version = details.versions.head
        afterPreview <- call(
          reviewer,
          "GET",
          s"/api/v1/documents/${doc.id}/versions/${doc.current.id}/preview.pdf"
        )
        original <- call(reviewer, "GET", s"/api/v1/documents/${doc.id}/versions/${doc.current.id}/file")
        audit    <- actions(doc.id)
      yield assertTrue(
        doc.kind == "File",
        doc.current.file.exists(f => f.name == "Wartungsvertrag.docx" && f.sha256 == sha256(template)),
        initial.editable,
        initial.fields.find(_.key == "title").flatMap(_.value).contains("Wartungsvertrag ACME"),
        initial.fields.find(_.key == "issuingUnit").flatMap(_.value).contains("Finance"),
        initial.fields.find(_.key == "customer").exists(f => f.required && f.value.isEmpty),
        incomplete.status == Status.UnprocessableEntity,
        incomplete.text.contains("required"),
        badDate.status == Status.UnprocessableEntity,
        filledIn.workingState == doc.current.workingState + 1,
        filledIn.file.exists(_.sha256 == sha256(template)),
        byReader.status == Status.Forbidden,
        filled.status == Status.Ok,
        filled.header("Content-Disposition").exists(_.contains("Wartungsvertrag.docx")),
        body.contains("ACME GmbH"),
        // the placeholder became a content control (DMS-127); the paragraph reads as before
        WordDocx.text(filled.body.toArray).exists(_.contains("Lieferung bis 31.01.2027")),
        head.contains("Wartungsvertrag ACME"),
        head.contains("1.0-draft"),
        head.contains("Finance"),
        preview.status == Status.Ok,
        preview.header("Content-Type").exists(_.startsWith("application/pdf")),
        converted._1 == "Wartungsvertrag.docx",
        part(converted._2.toArray, "word/document.xml").contains("ACME GmbH"),
        RenderingWorker.pageTexts(preview.body).mkString.contains("Entwurf"),
        draftPdf.status == Status.Ok,
        PdfComposer.pages(draftPdf.body.toArray) == Right(2),
        html.status == Status.Conflict,
        approvedDoc.current.status == "Approved",
        approvedDoc.current.rendition.isDefined,
        archivedBody.contains("ACME GmbH"),
        archivedBody.contains("Anna Approver"),
        part(archived._2.toArray, "word/header1.xml").contains(">1.0</w:t>"),
        // the approval binds the original and the entered values
        approval.contentSha256 == WorkflowDocuments.contentSha256(version),
        approval.contentSha256 != WorkflowDocuments.contentSha256(
          version.copy(file = version.file.map(f => f.copy(values = f.values + ("customer" -> "Other"))))
        ),
        approval.contentSha256 != WorkflowDocuments.contentSha256(
          version.copy(file = version.file.map(_.copy(sha256 = "0" * 64)))
        ),
        // approved: the preview is the archived rendition; the original stays unchanged
        approvedDoc.current.rendition.exists(r => r.sha256 == sha256(afterPreview.body.toArray)),
        original.body.toArray.sameElements(template),
        audit.contains("document.file-uploaded"),
        audit.contains("document.word-values-changed"),
        audit.contains(FileDocumentApi.FilledDocxAction),
        audit.contains(VersionViewApi.DraftPdfAction),
        audit.contains("document.rendition-created")
      )
    },
    test("a new version takes the file and values over; a revised Word file keeps the field definitions") {
      for
        created <- wordTemplate
        (id, uploaded) = created
        _ <- call(
          designer,
          "PUT",
          s"/api/v1/admin/templates/$id/versions/1/word/fields",
          Some(
            WordFieldDefinitions(
              uploaded.fields.map(f => if f.key == "customer" then f.copy(label = "Kunde") else f)
            ).toJson
          )
        )
        _   <- release(id, 1)
        doc <- call(
          author,
          "POST",
          "/api/v1/documents/word",
          Some(s"""{"templateId":"$id","templateVersion":1,"title":"Vertrag B","values":${values("en")}}""")
        ).map(as[DocumentView])
        _ <- call(
          author,
          "PUT",
          s"/api/v1/documents/${doc.id}/word-fields",
          Some("""{"values":{"customer":"Beta AG"}}""")
        )
        _    <- approve(doc.id)
        s    <- setup.await
        jobs <- ZIO.serviceWithZIO[DocumentService](svc =>
          ZIO.succeed(PublicationJobs(s.app, svc, PublicationScheduleConfig()))
        )
        _    <- jobs.enqueue(tenantId)
        _    <- JobRunner(s.app, jobs.handlers, WorkerConfig("publication-test")).runOnce
        next <- call(
          author,
          "POST",
          s"/api/v1/documents/${doc.id}/versions",
          Some("""{"changeKind":"Major","reason":"Content revised"}""")
        ).map(as[DocumentVersionView])
        carried <- call(author, "GET", s"/api/v1/documents/${doc.id}/versions/${next.id}/word-fields")
          .map(as[WordFieldsView])
        revised = docx(
          paragraph(textRun("Revised ") + control("customer", "Customer", "?")) +
            paragraph(control("project", "Projekt", "?"))
        )
        uploadedNew <- put(author, s"/api/v1/documents/${doc.id}/file", "Wartungsvertrag v2.docx", revised)
          .map(as[DocumentVersionView])
        afterUpload <- call(author, "GET", s"/api/v1/documents/${doc.id}/versions/${next.id}/word-fields")
          .map(as[WordFieldsView])
        frozenOld <- call(
          author,
          "GET",
          s"/api/v1/documents/${doc.id}/versions/${doc.current.id}/word-fields"
        ).map(as[WordFieldsView])
      yield assertTrue(
        next.file.exists(f => f.basedOn.contains(doc.current.id) && f.sha256 == sha256(template)),
        carried.fields.find(_.key == "customer").flatMap(_.value).contains("Beta AG"),
        uploadedNew.file.exists(f => f.sha256 == sha256(revised) && f.basedOn.isEmpty),
        afterUpload.fields.map(_.key) == List("customer", "project"),
        afterUpload.fields
          .find(_.key == "customer")
          .exists(f => f.label == "Kunde" && f.value.contains("Beta AG")),
        afterUpload.fields.find(_.key == "project").exists(_.source == "Manual"),
        !frozenOld.editable,
        frozenOld.fields.find(_.key == "customer").flatMap(_.value).contains("Beta AG")
      )
    },
    test(
      "plain file documents: uploads are checked, structured documents refuse files, readers need access"
    ) {
      for
        s <- setup.await
        create = (kind: String) =>
          call(
            author,
            "POST",
            "/api/v1/documents",
            Some(
              s"""{"templateId":"${s.structured.value}","templateVersion":1,"title":"Plan","values":${values(
                  "en"
                )},
                 |"kind":"$kind"}""".stripMargin
            )
          ).map(as[DocumentView])
        doc        <- create("File")
        structured <- create("Structured")
        unknown    <- call(
          author,
          "POST",
          "/api/v1/documents",
          Some(
            s"""{"templateId":"${s.structured.value}","templateVersion":1,"title":"Plan","values":${values(
                "en"
              )},
               |"kind":"Other"}""".stripMargin
          )
        )
        noFile <- call(author, "POST", s"/api/v1/documents/${doc.id}/submit")
        zip <- put(author, s"/api/v1/documents/${doc.id}/file", "data.zip", "PK\u0003\u0004".getBytes(UTF_8))
        exe <- put(author, s"/api/v1/documents/${doc.id}/file", "tool.exe", "MZ".getBytes(UTF_8))
        brokenW <- put(
          author,
          s"/api/v1/documents/${doc.id}/file",
          "broken.docx",
          "not a zip".getBytes(UTF_8)
        )
        toStruct <- put(
          author,
          s"/api/v1/documents/${structured.id}/file",
          "Plan.pdf",
          "%PDF-1.7".getBytes(UTF_8)
        )
        byReader <- put(reader, s"/api/v1/documents/${doc.id}/file", "Plan.pdf", "%PDF-1.7".getBytes(UTF_8))
        pdf = blankPdf(2).toArray
        uploaded <- put(author, s"/api/v1/documents/${doc.id}/file", "Plan.pdf", pdf)
          .map(as[DocumentVersionView])
        before   <- conversions.get.map(_.size)
        preview  <- call(author, "GET", s"/api/v1/documents/${doc.id}/versions/${uploaded.id}/preview.pdf")
        after    <- conversions.get.map(_.size)
        download <- call(author, "GET", s"/api/v1/documents/${doc.id}/versions/${uploaded.id}/file")
        readerDl <- call(reader, "GET", s"/api/v1/documents/${doc.id}/versions/${uploaded.id}/file")
        filled   <- call(author, "GET", s"/api/v1/documents/${doc.id}/versions/${uploaded.id}/filled.docx")
        fields   <- call(author, "GET", s"/api/v1/documents/${doc.id}/versions/${uploaded.id}/word-fields")
          .map(as[WordFieldsView])
        structPv <- call(
          author,
          "GET",
          s"/api/v1/documents/${structured.id}/versions/${structured.current.id}/preview.pdf"
        )
      yield assertTrue(
        doc.kind == "File",
        structured.kind == "Structured",
        unknown.status == Status.UnprocessableEntity,
        noFile.status == Status.UnprocessableEntity,
        zip.error.contains("file_not_convertible"),
        exe.error.contains("attachment_blocked"),
        brokenW.error.contains("invalid_docx"),
        toStruct.status == Status.Conflict,
        byReader.status == Status.Forbidden,
        uploaded.file.exists(f =>
          f.name == "Plan.pdf" && f.sha256 == sha256(pdf) && f.contentType == "application/pdf"
        ),
        preview.status == Status.Ok,
        // a PDF original is shown as it is, without conversion
        after == before,
        PdfComposer.pages(preview.body.toArray) == Right(2),
        download.body.toArray.sameElements(pdf),
        download.header("Content-Disposition").exists(_.contains("filename=\"Plan.pdf\"")),
        download.header("ETag").contains("\"" + sha256(pdf) + "\""),
        download.header("X-Content-Type-Options").contains("nosniff"),
        readerDl.status == Status.Forbidden,
        filled.status == Status.Conflict,
        fields.fields.isEmpty,
        structPv.status == Status.Conflict
      )
    },
    test("Word versions are compared by their filled text, manual values and original (DMS-121)") {
      for
        s       <- setup.await
        created <- wordTemplate
        (id, _) = created
        _   <- release(id, 1)
        doc <- call(
          author,
          "POST",
          "/api/v1/documents/word",
          Some(s"""{"templateId":"$id","templateVersion":1,"title":"Vertrag C","values":${values("de")}}""")
        ).map(as[DocumentView])
        _ <- call(
          author,
          "PUT",
          s"/api/v1/documents/${doc.id}/word-fields",
          Some("""{"values":{"customer":"ACME GmbH","deliveryDate":"2027-01-31"}}""")
        )
        _    <- approveAndPublish(doc.id)
        next <- newVersion(doc.id)
        revised = docx(
          paragraph(textRun("Wartungsvertrag ") + control("dms:documentNumber", "Dokumentnummer", "Nummer")) +
            paragraph(textRun("Kunde: ") + control("customer", "Customer", "Name des Kunden")) +
            paragraph(textRun("Wartung jeden Monat vor Ort"))
        )
        _ <- put(author, s"/api/v1/documents/${doc.id}/file", "Wartungsvertrag.docx", revised)
        _ <- call(
          author,
          "PUT",
          s"/api/v1/documents/${doc.id}/word-fields",
          Some("""{"values":{"customer":"Beta AG"}}""")
        )
        compared <- call(
          author,
          "GET",
          s"/api/v1/documents/${doc.id}/compare?from=${doc.current.id}&to=${next.id}"
        )
          .map(as[VersionComparisonView])
        spans = compared.text.sections.flatMap(_.blocks).flatMap(_.spans)
        same <- call(
          author,
          "GET",
          s"/api/v1/documents/${doc.id}/compare?from=${doc.current.id}&to=${doc.current.id}"
        ).map(as[VersionComparisonView])
        // the published version's filled text is indexed with its name
        source  <- ZIO.environment[FileVersions & TenantOrganizations & UserDirectory]
        indexed <- ZIO.serviceWithZIO[DocumentService](documents =>
          ZIO.serviceWithZIO[TemplateService](templates =>
            ZIO.serviceWithZIO[ProfileSource](profiles =>
              s.app.transact(tenantId)(
                SearchDocuments(documents, templates, profiles, SearchDocuments.Originals.filledWord(source))
                  .load(tenantId, DocumentId(doc.id))
              )
            )
          )
        )
      yield assertTrue(
        compared.original.exists(o =>
          o.changed && o.textCompared && o.before.exists(_.sha256 == sha256(template)) &&
            o.after.exists(_.sha256 == sha256(revised))
        ),
        compared.text.changed,
        compared.text.sections.map(_.id) == List(VersionCompareApi.OriginalSection),
        // the filled values are part of the text: the customer changed, the delivery paragraph was removed
        spans.exists(sp => sp.kind == "Deleted" && sp.text.contains("ACME")),
        spans.exists(sp => sp.kind == "Inserted" && sp.text.contains("Beta")),
        spans.exists(sp => sp.kind == "Deleted" && sp.text.contains("31.01.2027")),
        spans.exists(sp => sp.kind == "Inserted" && sp.text.contains("jeden Monat")),
        compared.fields.exists(f => f.key == "word:customer" && f.label == "Customer"),
        compared.fields.exists(f => f.key == "word:deliveryDate" && f.after.isEmpty),
        compared.metadata.exists(_.key == "originalChecksum"),
        !same.text.changed,
        same.original.exists(o => !o.changed && o.textCompared),
        indexed.map(_.versionLabel) == List("1.0"),
        indexed.exists(d => d.text.startsWith("Wartungsvertrag.docx") && d.text.contains("ACME GmbH")),
        indexed.exists(_.text.contains("Lieferung bis 31.01.2027"))
      )
    },
    test("draft previews are converted once per state; uploads must fit their extension (DMS-121)") {
      for
        s   <- setup.await
        doc <- call(
          author,
          "POST",
          "/api/v1/documents",
          Some(
            s"""{"templateId":"${s.structured.value}","templateVersion":1,"title":"Budget","values":${values(
                "en"
              )},"kind":"File"}"""
          )
        ).map(as[DocumentView])
        upload = (name: String, bytes: Array[Byte]) =>
          put(author, s"/api/v1/documents/${doc.id}/file", name, bytes)
        pdfAsXlsx <- upload("Budget.xlsx", blankPdf(1).toArray)
        zipAsPdf  <- upload("Budget.pdf", "PK\u0003\u0004 not a pdf".getBytes(UTF_8))
        textAsDoc <- upload("Budget.doc", "plain text".getBytes(UTF_8))
        xlsx = WordFieldTypesSpec.workbook("a spreadsheet")
        uploaded <- upload("Budget.xlsx", xlsx).map(as[DocumentVersionView])
        preview = () =>
          call(author, "GET", s"/api/v1/documents/${doc.id}/versions/${uploaded.id}/preview.pdf")
        before <- conversions.get.map(_.size)
        first  <- preview()
        second <- preview()
        draft  <- call(author, "GET", s"/api/v1/documents/${doc.id}/versions/${uploaded.id}/draft.pdf")
        cached <- conversions.get.map(_.size)
        _      <- upload("Budget.xlsx", WordFieldTypesSpec.workbook("revised"))
        third  <- preview()
        after  <- conversions.get.map(_.size)
        // other formats: the originals are compared as files only
        _        <- approveAndPublish(doc.id)
        next     <- newVersion(doc.id)
        _        <- upload("Budget 2027.xlsx", WordFieldTypesSpec.workbook("next year"))
        compared <- call(
          author,
          "GET",
          s"/api/v1/documents/${doc.id}/compare?from=${doc.current.id}&to=${next.id}"
        )
          .map(as[VersionComparisonView])
      yield assertTrue(
        pdfAsXlsx.status == Status.UnprocessableEntity,
        pdfAsXlsx.error.contains("file_type_mismatch"),
        pdfAsXlsx.text.contains("Budget.xlsx"),
        zipAsPdf.error.contains("file_type_mismatch"),
        textAsDoc.error.contains("file_type_mismatch"),
        uploaded.file.exists(_.name == "Budget.xlsx"),
        first.status == Status.Ok,
        second.status == Status.Ok,
        draft.status == Status.Ok,
        first.body == second.body,
        cached == before + 1,
        third.status == Status.Ok,
        after == cached + 1,
        compared.original.exists(o =>
          o.changed && !o.textCompared && o.after.exists(_.name == "Budget 2027.xlsx")
        ),
        !compared.text.changed,
        compared.metadata.exists(m => m.key == "originalName")
      )
    },
    test("designers list the document classes for new templates; readers may not (DMS-121)") {
      for
        s        <- setup.await
        classes  <- call(designer, "GET", "/api/v1/admin/template-classes").map(as[List[TemplateClassView]])
        byReader <- call(reader, "GET", "/api/v1/admin/template-classes")
      yield assertTrue(
        classes.exists(c =>
          c.id == s.classId && c.key == "contract" && c.version == 1 && c.path == List("Contract")
        ),
        byReader.status == Status.Forbidden
      )
    },
    test("typed Word fields, rows and Excel placeholders are filled; the approval binds the rows (DMS-122)") {
      import WordFieldTypesSpec.{checkbox, datePicker, dropDown, positionRow, section, xlsx}
      val typed = docx(
        paragraph(textRun("Agreed: ") + checkbox("agreed", checked = false)) +
          paragraph(textRun("Start: ") + datePicker("startDate", Some("d. MMMM yyyy"))) +
          paragraph(textRun("Priority: ") + dropDown("priority")) +
          section(Some("positions"), positionRow) +
          paragraph(
            """<w:r><w:rPr><w:b/></w:rPr><w:t>Contact: {{con</w:t></w:r><w:r><w:t>tact}}</w:t></w:r>"""
          )
      )
      val setValues = (doc: UUID, json: String) =>
        call(author, "PUT", s"/api/v1/documents/$doc/word-fields", Some(s"""{"values":$json}"""))
      for
        s   <- setup.await
        doc <- call(
          author,
          "POST",
          "/api/v1/documents",
          Some(
            s"""{"templateId":"${s.structured.value}","templateVersion":1,"title":"Order","values":${values(
                "de"
              )},"kind":"File"}"""
          )
        ).map(as[DocumentView])
        uploaded <- put(author, s"/api/v1/documents/${doc.id}/file", "Order.docx", typed)
          .map(as[DocumentVersionView])
        fieldsUrl = s"/api/v1/documents/${doc.id}/versions/${uploaded.id}/word-fields"
        fields    <- call(author, "GET", fieldsUrl).map(as[WordFieldsView])
        badCheck  <- setValues(doc.id, """{"agreed":"yes"}""")
        badChoice <- setValues(doc.id, """{"priority":"Medium"}""")
        badRows   <- setValues(doc.id, """{"positions":"[{\"colour\":\"red\"}]"}""")
        notRows   <- setValues(doc.id, """{"positions":"Pump"}""")
        saved     <- setValues(
          doc.id,
          """{"agreed":"true","startDate":"2027-03-01","priority":"High","contact":"Ada",""" +
            """"positions":"[{\"quantity\":\" 2 \",\"item\":\"Pump\"},{\"item\":\" \"},{\"item\":\"Valve\"}]"}"""
        ).map(as[DocumentVersionView])
        stored <- call(author, "GET", fieldsUrl.replace(uploaded.id.toString, saved.id.toString))
          .map(as[WordFieldsView])
        filled <- call(author, "GET", s"/api/v1/documents/${doc.id}/versions/${saved.id}/filled")
        body = part(filled.body.toArray, "word/document.xml")
        details <- ZIO.serviceWithZIO[DocumentService](svc =>
          s.app.transact(tenantId)(svc.get(DocumentId(doc.id)))
        )
        version = details.versions.head
        // Excel: placeholders are fields; the filled workbook is downloaded as XLSX
        workbook <- put(author, s"/api/v1/documents/${doc.id}/file", "Budget.xlsx", xlsx)
          .map(as[DocumentVersionView])
        xlsxFields <- call(author, "GET", s"/api/v1/documents/${doc.id}/versions/${workbook.id}/word-fields")
          .map(as[WordFieldsView])
        _          <- setValues(doc.id, """{"customer":"ACME","startDate":"2027-03-01"}""")
        filledXlsx <- call(author, "GET", s"/api/v1/documents/${doc.id}/versions/${workbook.id}/filled")
        asDocx     <- call(author, "GET", s"/api/v1/documents/${doc.id}/versions/${workbook.id}/filled.docx")
        brokenXlsx <- put(
          author,
          s"/api/v1/documents/${doc.id}/file",
          "broken.xlsx",
          WordFixtures.docx(paragraph(textRun("not a workbook")))
        )
        audit <- actions(doc.id)
      yield
        val byKey = fields.fields.map(f => f.key -> f).toMap
        assertTrue(
          byKey("agreed").`type` == "Checkbox",
          byKey("startDate").`type` == "Date",
          byKey("priority").`type` == "Choice",
          byKey("priority").options == List("High", "Low"),
          byKey("positions").`type` == "Rows",
          byKey("positions").columns.map(c => (c.key, c.`type`)) ==
            List("item" -> "Text", "quantity" -> "Text", "dueDate" -> "Date"),
          byKey("documentNumber").source == "Automatic",
          byKey("contact").`type` == "Text",
          badCheck.status == Status.UnprocessableEntity,
          badCheck.text.contains("true or false"),
          badChoice.text.contains("one of High, Low"),
          badRows.text.contains("colour is not a column"),
          notRows.text.contains("a list of rows"),
          // rows are stored canonically: trimmed, empty rows left out, columns in the order of the definition
          stored.fields.find(_.key == "positions").flatMap(_.value) ==
            Some("""[{"item":"Pump","quantity":"2"},{"item":"Valve"}]"""),
          filled.status == Status.Ok,
          filled.header("Content-Disposition").exists(_.contains("Order.docx")),
          body.contains("""w14:checked w14:val="1""""),
          body.contains("\u2612"),
          body.contains("1. März 2027"),
          body.contains("""w:fullDate="2027-03-01T00:00:00Z""""),
          body.contains(">High<"),
          ">Pump<".r.findAllIn(body).size == 1,
          ">Valve<".r.findAllIn(body).size == 1,
          "<w:tr>".r.findAllIn(body).size == 2,
          WordDocx.text(filled.body.toArray).exists(_.contains("Contact: Ada")),
          // the approval checksum covers the rows
          WorkflowDocuments.contentSha256(version) != WorkflowDocuments.contentSha256(
            version.copy(file = version.file.map(f => f.copy(values = f.values + ("positions" -> "[]"))))
          ),
          xlsxFields.fields.map(_.key) == List("customer", "documentNumber", "unknown", "startDate"),
          filledXlsx.status == Status.Ok,
          filledXlsx.header("Content-Type").exists(_.contains("spreadsheetml")),
          part(filledXlsx.body.toArray, "xl/sharedStrings.xml").contains("Customer: ACME"),
          part(filledXlsx.body.toArray, "xl/worksheets/sheet1.xml").contains("Start 01.03.2027"),
          asDocx.status == Status.Conflict,
          brokenXlsx.error.contains("invalid_office_file"),
          audit.contains(FileDocumentApi.FilledFileAction)
        )
    },
    test("an uploaded Word file becomes a document of a class through its upload template (DMS-127)") {
      for
        s             <- setup.await
        classes       <- call(author, "GET", "/api/v1/uploads/classes").map(as[List[TemplateClassView]])
        readerClasses <- call(reader, "GET", "/api/v1/uploads/classes").map(as[List[TemplateClassView]])
        byReader      <- form(reader, "/api/v1/uploads/fields", "Vertrag.docx", template)
        analysed      <- form(author, "/api/v1/uploads/fields?language=de", "Vertrag.docx", template)
          .map(as[UploadAnalysisView])
        byDesigner <- form(designer, "/api/v1/uploads/fields", "Vertrag.docx", template)
        preview    <- form(
          author,
          "/api/v1/uploads/preview.pdf?language=de",
          "Vertrag.docx",
          template,
          Some("""{"customer":"ACME","deliveryDate":"2027-01-31"}""")
        )
        previewed <- lastConversion
        invalid   <- form(
          author,
          "/api/v1/uploads/preview.pdf",
          "Vertrag.docx",
          template,
          Some("""{"deliveryDate":"bald"}""")
        )
        notJson   <- form(author, "/api/v1/uploads/preview.pdf", "Vertrag.docx", template, Some("[1]"))
        first     <- call(author, "POST", s"/api/v1/uploads/classes/${s.classId}/template")
        second    <- call(author, "POST", s"/api/v1/uploads/classes/${s.classId}/template")
        notAuthor <- call(designer, "POST", s"/api/v1/uploads/classes/${s.classId}/template")
        unknown   <- call(author, "POST", s"/api/v1/uploads/classes/${UUID.randomUUID()}/template")
        upload = as[UploadTemplateView](first)
        catalog <- ZIO.serviceWithZIO[TemplateService](svc => s.app.transact(tenantId)(svc.catalog))
        listed  <- ZIO.serviceWithZIO[TemplateService](svc => s.app.transact(tenantId)(svc.list))
        doc     <- call(
          author,
          "POST",
          "/api/v1/documents",
          Some(
            s"""{"templateId":"${upload.templateId}","templateVersion":${upload.version},"title":"Vertrag ACME",""" +
              s""""values":${values("de")},"kind":"File"}"""
          )
        ).map(as[DocumentView])
        fieldsUrl = s"/api/v1/documents/${doc.id}/versions/${doc.current.id}/word-fields"
        _ <- put(author, s"/api/v1/documents/${doc.id}/file", "Vertrag.docx", template)
        _ <- call(
          author,
          "PUT",
          s"/api/v1/documents/${doc.id}/word-fields",
          Some("""{"values":{"customer":"ACME","deliveryDate":"2027-01-31"}}""")
        )
        filled <- call(author, "GET", s"/api/v1/documents/${doc.id}/versions/${doc.current.id}/filled.docx")
        // the values are cleared; the re-uploaded filled document brings them back
        _       <- call(author, "PUT", s"/api/v1/documents/${doc.id}/word-fields", Some("""{"values":{}}"""))
        cleared <- call(author, "GET", fieldsUrl).map(as[WordFieldsView])
        reuploaded <- put(
          author,
          s"/api/v1/documents/${doc.id}/file",
          "Vertrag-ACME.docx",
          filled.body.toArray
        )
          .map(as[DocumentVersionView])
        recovered  <- call(author, "GET", fieldsUrl).map(as[WordFieldsView])
        reanalysed <- form(author, "/api/v1/uploads/fields", "Vertrag-ACME.docx", filled.body.toArray)
          .map(as[UploadAnalysisView])
        audit <- actions(upload.templateId)
      yield
        val manual =
          (v: WordFieldsView) => v.fields.filter(_.source == "Manual").map(f => f.key -> f.value).toMap
        val item  = parts(filled.body.toArray).get("customXml/item1.xml").map(String(_, UTF_8)).getOrElse("")
        val shown = String(parts(previewed._2.toArray)("word/document.xml"), UTF_8)
        assertTrue(
          classes.exists(_.id == s.classId),
          readerClasses.isEmpty,
          byReader.status == Status.Forbidden,
          byDesigner.status == Status.Ok,
          analysed.word,
          analysed.fillable,
          !analysed.filledBefore,
          analysed.sha256 == sha256(template),
          analysed.fields.find(_.key == "customer").exists(f => f.source == "Manual" && f.value.isEmpty),
          analysed.fields.find(_.key == "deliveryDate").exists(_.`type` == "Date"),
          analysed.fields.find(_.key == "title").flatMap(_.value).contains("Vertrag"),
          analysed.fields.find(_.key == "version").flatMap(_.value).contains("1.0-draft.1"),
          // the number is assigned when the document is created
          analysed.fields.find(_.key == "documentNumber").exists(_.value.isEmpty),
          preview.status == Status.Ok,
          preview.header("Content-Type").exists(_.startsWith("application/pdf")),
          shown.contains("ACME"),
          shown.contains("31.01.2027"),
          invalid.status == Status.UnprocessableEntity,
          notJson.status == Status.UnprocessableEntity,
          first.status == Status.Ok,
          as[UploadTemplateView](second) == upload,
          upload.documentClassId == s.classId,
          notAuthor.status == Status.Forbidden,
          unknown.status == Status.NotFound,
          // the upload template is neither in the catalog nor in the designers' list
          !catalog.exists(_.templateId.value == upload.templateId),
          !listed.exists(_.templateId.value == upload.templateId),
          audit.contains("template.file-upload-version-released"),
          doc.kind == "File",
          filled.status == Status.Ok,
          item.contains("<customer>ACME</customer>"),
          item.contains("<deliveryDate>2027-01-31</deliveryDate>"),
          manual(cleared).values.forall(_.isEmpty),
          // the re-uploaded original is stored unchanged
          reuploaded.file.exists(_.sha256 == sha256(filled.body.toArray)),
          manual(recovered) == Map("customer" -> Some("ACME"), "deliveryDate" -> Some("2027-01-31")),
          reanalysed.filledBefore,
          reanalysed.fields.find(_.key == "customer").flatMap(_.value).contains("ACME"),
          reanalysed.fields.map(_.key).contains("deliveryDate")
        )
    },
    test(
      "originals, upload previews and attachments are scanned by ClamAV; findings and outages store nothing"
    ) {
      for
        s     <- setup.await
        fake  <- clamd.await
        first <- call(author, "POST", s"/api/v1/uploads/classes/${s.classId}/template")
        upload = as[UploadTemplateView](first)
        doc <- call(
          author,
          "POST",
          "/api/v1/documents",
          Some(
            s"""{"templateId":"${upload.templateId}","templateVersion":${upload.version},"title":"Gescannt",""" +
              s""""values":${values("de")},"kind":"File"}"""
          )
        ).map(as[DocumentView])
        infected = s"Notiz ${FakeClamd.Marker}".getBytes(UTF_8)
        original  <- put(author, s"/api/v1/documents/${doc.id}/file", "Notiz.txt", infected)
        preview   <- form(author, "/api/v1/uploads/preview.pdf", "Notiz.txt", infected)
        annex     <- form(author, s"/api/v1/documents/${doc.id}/attachments", "Anlage.txt", infected)
        _         <- ZIO.succeed(fake.mode.set(FakeClamd.Mode.Close))
        down      <- put(author, s"/api/v1/documents/${doc.id}/file", "Notiz.txt", "Notiz".getBytes(UTF_8))
        downAnnex <- form(
          author,
          s"/api/v1/documents/${doc.id}/attachments",
          "Anlage.txt",
          "Anlage".getBytes(UTF_8)
        )
        _      <- ZIO.succeed(fake.mode.set(FakeClamd.Mode.Answer))
        stored <- s.owner.transactSystem(
          Db.sql(
            sql"select count(*) from dms.document_attachment_file where document_id = ${doc.id}"
              .query[Long]
              .run()
              .head
          )
        )
        clean   <- put(author, s"/api/v1/documents/${doc.id}/file", "Notiz.txt", "Notiz".getBytes(UTF_8))
        scanned <- s.owner.transactSystem(
          Db.sql(
            sql"""select scan_status, scanner from dms.document_attachment_file where document_id = ${doc.id}"""
              .query[(String, String)]
              .run()
              .toList
          )
        )
      yield assertTrue(
        original.status == Status.UnprocessableEntity,
        original.error.contains("attachment_infected"),
        original.text.contains(FakeClamd.Signature),
        preview.status == Status.UnprocessableEntity,
        preview.error.contains("attachment_infected"),
        annex.status == Status.UnprocessableEntity,
        annex.error.contains("attachment_infected"),
        down.status == Status.ServiceUnavailable,
        down.error.contains("scanner_unavailable"),
        downAnnex.status == Status.ServiceUnavailable,
        stored == 0L,
        clean.status == Status.Ok,
        scanned == List(("Clean", "clamav"))
      )
    },
    test("a designer uses a published Word document as template, without its values (DMS-127)") {
      for
        created <- wordTemplate
        (id, uploaded) = created
        _   <- release(id, 1)
        doc <- call(
          author,
          "POST",
          "/api/v1/documents/word",
          Some(
            s"""{"templateId":"$id","templateVersion":1,"title":"Vertrag Muster","values":${values("de")}}"""
          )
        ).map(as[DocumentView])
        _ <- call(
          author,
          "PUT",
          s"/api/v1/documents/${doc.id}/word-fields",
          Some("""{"values":{"customer":"ACME"}}""")
        )
        _ <- approveAndPublish(doc.id)
        url = s"/api/v1/documents/${doc.id}/versions/${doc.current.id}/word-template"
        byAuthor <- call(author, "POST", url, Some("""{"name":"Aus Dokument"}"""))
        noName   <- call(designer, "POST", url, Some("""{"name":" "}"""))
        made <- call(designer, "POST", url, Some("""{"name":"Vertrag aus Dokument","description":"Kopie"}"""))
        copy = as[WordTemplateView](made)
        file <- call(designer, "GET", s"/api/v1/admin/templates/${copy.templateId}/versions/1/word/file")
      yield assertTrue(
        byAuthor.status == Status.Forbidden,
        noName.status == Status.UnprocessableEntity,
        made.status == Status.Created,
        copy.status == "Draft",
        copy.version == 1,
        copy.templateName == "Vertrag aus Dokument",
        copy.sha256 == sha256(template),
        copy.fields.map(_.key) == uploaded.fields.map(_.key),
        file.body.toArray.sameElements(template),
        // the template's file carries no values
        !file.text.contains("ACME")
      )
    },
    test("only Word template uploads and file uploads may stream large bodies") {
      val streamed = Body.fromStreamChunked(ZStream.fromIterable(Array.fill(10)('x'.toByte)))
      val handler  = Main.limitStreamedBodies(zio.http.Handler.ok)
      ZIO.scoped(
        for
          file <- handler(
            Request.put(URL.decode(s"/api/v1/documents/${UUID.randomUUID()}/file").toOption.get, streamed)
          )
          word <- handler(
            Request.put(
              URL.decode(s"/api/v1/admin/templates/${UUID.randomUUID()}/versions/3/word").toOption.get,
              streamed
            )
          )
          fields <- handler(
            Request.put(
              URL.decode(s"/api/v1/admin/templates/${UUID.randomUUID()}/versions/3/word/fields").toOption.get,
              streamed
            )
          )
          analysed <- handler(Request.post(URL.decode("/api/v1/uploads/fields").toOption.get, streamed))
          preview  <- handler(Request.post(URL.decode("/api/v1/uploads/preview.pdf").toOption.get, streamed))
        yield assertTrue(
          file.status == Status.Ok,
          word.status == Status.Ok,
          analysed.status == Status.Ok,
          preview.status == Status.Ok,
          fields.status == Status.RequestEntityTooLarge
        )
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
