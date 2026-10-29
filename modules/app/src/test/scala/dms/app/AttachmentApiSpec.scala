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
import dms.identity.*
import dms.integration.{JobRunner, WorkerConfig}
import dms.kernel.{TemplateId, TenantId, UserId}
import dms.rendering.core.RenditionSource
import dms.storage.{ObjectStore, S3ObjectStore, StorageConfig}
import dms.templates.*
import dms.templates.core.{TemplateDraft, TemplateService}
import dms.workflow.core.{ApprovalWorkflow, FlowableEngine, WorkflowConfig}
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
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.{Instant, LocalDate}
import java.util.{Base64, Date, HexFormat, UUID}
import javax.sql.DataSource

/** Attachments end to end (DMS-114): upload into SeaweedFS through the multipart endpoint, size limit and
  * blocklist, the virus scan hook, reading and downloading with the version's permissions, renaming and
  * removing in the draft, the freeze from submission on and the take-over by the next version. Against
  * PostgreSQL and SeaweedFS started by Testcontainers.
  */
object AttachmentApiSpec extends ZIOSpecDefault:

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
            _   <- RoleAssignments.Postgres.grant(admin, RoleAssignment(Role.TenantAdmin), admin)
            _   <- RoleAssignments.Postgres.grant(author, RoleAssignment(Role.Author), admin)
            _   <- RoleAssignments.Postgres.grant(owner, RoleAssignment(Role.Owner), admin)
            _   <- RoleAssignments.Postgres.grant(reviewer, RoleAssignment(Role.Reviewer), admin)
            _   <- RoleAssignments.Postgres.grant(approver, RoleAssignment(Role.Approver), admin)
            _   <- RoleAssignments.Postgres.grant(reader, RoleAssignment(Role.Reader), admin)
            cls <- classes.createClass("policy", None, ClassDraft("Policy", None, Nil), admin)
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

  /** The beginning of the EICAR test signature. */
  private val eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR"

  /** Stand-in for a virus scanner: flags files that contain the EICAR test string; `down` simulates an
    * unreachable scanner.
    */
  private final class TestScanner(down: Ref[Boolean]) extends AttachmentScanner:
    val name: String                                             = "test-scanner"
    def scan(file: Path, size: Long): IO[ScanError, ScanVerdict] =
      down.get.flatMap(d =>
        if d then ZIO.fail(ScanError.Unavailable("connection refused"))
        else
          ZIO
            .attemptBlocking(String(Files.readAllBytes(file), UTF_8))
            .orDie
            .map(text =>
              if text.contains(eicar) then ScanVerdict.Infected("Eicar-Test-Signature") else ScanVerdict.Clean
            )
      )

  private val scannerDown = Unsafe.unsafe(implicit u => Ref.unsafe.make(false))

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

  private type Env = AttachmentApi.Env & WorkflowApi.Env & RenditionSource & AuditTrail & ObjectStore

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
      DocumentRenditions.layer,
      ZLayer.succeed[AttachmentScanner](TestScanner(scannerDown)),
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
        AttachmentApi.endpoints.map(_.widen[Env])
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

  private val boundary = "----dms-test-boundary-7MA4YWxkTrZu0gW"

  /** A multipart body with one part `file`, cut into chunks of `chunkSize` bytes to exercise the parser. */
  private def multipart(
      name: String,
      bytes: Array[Byte],
      partType: String,
      field: String,
      chunkSize: Int = 1000
  ): Body =
    val head =
      s"--$boundary\r\nContent-Disposition: form-data; name=\"note\"\r\n\r\nignored\r\n" +
        s"--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$name\"\r\n" +
        s"Content-Type: $partType\r\n\r\n"
    val all = head.getBytes(UTF_8) ++ bytes ++ s"\r\n--$boundary--\r\n".getBytes(UTF_8)
    Body.fromStreamChunked(
      ZStream.fromIterable(all.grouped(chunkSize).map(Chunk.fromArray).toList).flattenChunks
    )

  private def upload(
      user: UserId,
      doc: UUID,
      name: String,
      bytes: Array[Byte],
      partType: String = "application/octet-stream",
      field: String = "file"
  ) =
    send(
      user,
      Request
        .post(
          URL.decode(s"/api/v1/documents/$doc/attachments").toOption.get,
          multipart(name, bytes, partType, field)
        )
        .addHeader(Header.ContentType(MediaType.multipart.`form-data`, Some(zio.http.Boundary(boundary))))
    )

  private def as[A: JsonDecoder](response: Response): A =
    response.text
      .fromJson[A]
      .fold(e => throw AssertionError(s"$e: ${response.status} ${response.text}"), identity)

  private def attachments(user: UserId, doc: UUID, version: UUID) =
    call(user, "GET", s"/api/v1/documents/$doc/versions/$version/attachments")

  private def download(user: UserId, doc: UUID, attachment: UUID) =
    call(user, "GET", s"/api/v1/documents/$doc/attachments/$attachment")

  private def sha256(bytes: Array[Byte]) =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private val past = LocalDate.of(2026, 1, 1)

  private def values =
    s"""{"owner":{"type":"PersonRef","userId":"${owner.value}"},
       |"organizationalUnit":{"type":"Text","value":"Finance"},
       |"validFrom":{"type":"Date","value":"$past"},
       |"confidentiality":{"type":"Selection","code":"Internal"},
       |"language":{"type":"Text","value":"en"},
       |"retentionRule":{"type":"Text","value":"policy-10y"},
       |"containsPersonalData":{"type":"YesNo","value":false}}""".stripMargin

  private def draft: ZIO[Env, Any, DocumentView] =
    for
      s       <- setup.await
      created <- call(
        author,
        "POST",
        "/api/v1/documents",
        Some(
          s"""{"templateId":"${s.released.value}","templateVersion":1,"title":"Travel policy",
             |"values":$values}""".stripMargin
        )
      ).map(as[DocumentView])
    yield created

  private def cases(doc: UUID) =
    call(author, "GET", s"/api/v1/documents/$doc/workflow").map(as[List[WorkflowCaseView]])

  private def vote(user: UserId, task: TaskView) =
    call(user, "POST", s"/api/v1/workflow/tasks/${task.id}/votes", Some(CastVote("Approve", None).toJson))

  /** Submits the draft, approves it, renders it with stand-ins and publishes it. */
  private def approveAndPublish(doc: UUID): ZIO[Env, Any, Unit] =
    for
      s           <- setup.await
      _           <- call(author, "POST", s"/api/v1/documents/$doc/submit")
      review      <- cases(doc).map(_.last.tasks.find(_.status == "Open").get)
      afterReview <- vote(reviewer, review).map(as[WorkflowCaseView])
      _           <- vote(approver, afterReview.tasks.find(_.status == "Open").get)
      _           <- StubRendering.runPending(s.app)
      db          <- ZIO.service[Db]
      service     <- ZIO.service[DocumentService]
      jobs = PublicationJobs(db, service, PublicationScheduleConfig())
      _ <- jobs.enqueue(tenantId)
      _ <- JobRunner(db, jobs.handlers, WorkerConfig("publication-test")).runOnce
    yield ()

  private def ownerQuery[A](f: DbTx ?=> A): ZIO[Any, DbError, A] =
    setup.await.flatMap(s => s.owner.transactSystem(Db.sql(f)))

  private def auditActions(doc: UUID) =
    ownerQuery(
      sql"""select action from dms.audit_event where object_id = $doc and action like 'document.attachment-%'
            order by seq""".query[String].run().toList
    )

  private def fileCount(doc: UUID) =
    ownerQuery(
      sql"select count(*) from dms.document_attachment_file where document_id = $doc".query[Long].run().head
    )

  private val pdf = "%PDF-1.7\nTravel expense form\n%%EOF\n".getBytes(UTF_8)

  def spec = suite("Attachments")(
    test("an author uploads a file to the draft; it is listed, scanned and downloaded with its checksum") {
      for
        doc     <- draft
        created <- upload(author, doc.id, "Expense form.pdf", pdf)
        added = as[AttachmentView](created)
        listed   <- attachments(author, doc.id, doc.current.id).map(as[AttachmentsView])
        got      <- download(author, doc.id, added.id)
        sameName <- upload(author, doc.id, "expense FORM.pdf", "other".getBytes(UTF_8))
        sameData <- upload(author, doc.id, "Copy.pdf", pdf)
        actions  <- auditActions(doc.id)
        files    <- fileCount(doc.id)
        objects  <- ZIO.serviceWithZIO[ObjectStore](
          _.listKeys(s"attachments/${tenantId.value}/${doc.id}/").orElseSucceed(Nil)
        )
      yield assertTrue(
        created.status == Status.Created,
        added.name == "Expense form.pdf",
        added.contentType == "application/pdf",
        added.size == pdf.length.toLong,
        added.sha256 == sha256(pdf),
        added.scanStatus == "Clean",
        added.addedBy == author.value,
        added.downloadUrl == s"/api/v1/documents/${doc.id}/attachments/${added.id}",
        listed.editable,
        listed.status == "Draft",
        listed.maxSizeBytes == maxSize,
        listed.attachments.map(_.id) == List(added.id),
        got.status == Status.Ok,
        got.body.toArray.sameElements(pdf),
        got.header("Content-Type").exists(_.startsWith("application/pdf")),
        got.header("Content-Disposition").exists(_.contains("attachment; filename=\"Expense form.pdf\"")),
        got.header("Content-Disposition").exists(_.contains("filename*=UTF-8''Expense%20form.pdf")),
        got.header("ETag").contains("\"" + sha256(pdf) + "\""),
        got
          .header("Repr-Digest")
          .contains(
            "sha-256=:" + Base64.getEncoder.encodeToString(HexFormat.of().parseHex(sha256(pdf))) + ":"
          ),
        got.header("X-Content-Type-Options").contains("nosniff"),
        sameName.status == Status.Conflict,
        sameData.status == Status.Conflict,
        actions == List("document.attachment-added"),
        files == 1L,
        objects.size == 1
      )
    },
    test(
      "uploads above the size limit, blocked types, executables, empty files and bad requests are refused"
    ) {
      for
        doc      <- draft
        tooLarge <- upload(author, doc.id, "big.pdf", Array.fill(maxSize.toInt + 1)('a'.toByte))
        atLimit  <- upload(author, doc.id, "limit.txt", Array.fill(maxSize.toInt)('b'.toByte), "text/plain")
        exe      <- upload(author, doc.id, "setup.EXE", "harmless".getBytes(UTF_8))
        double   <- upload(author, doc.id, "invoice.pdf.js", "alert(1)".getBytes(UTF_8))
        disguise <- upload(author, doc.id, "report.pdf", "MZ\u0090\u0000rest".getBytes(UTF_8))
        empty    <- upload(author, doc.id, "empty.pdf", Array.emptyByteArray)
        noName   <- upload(author, doc.id, "", pdf)
        noFile   <- upload(author, doc.id, "a.pdf", pdf, field = "other")
        notMulti <- call(author, "POST", s"/api/v1/documents/${doc.id}/attachments", Some("{}"))
        infected <- upload(author, doc.id, "eicar.txt", eicar.getBytes(UTF_8), "text/plain")
        _        <- scannerDown.set(true)
        noScan   <- upload(author, doc.id, "later.pdf", "scan me".getBytes(UTF_8))
        _        <- scannerDown.set(false)
        listed   <- attachments(author, doc.id, doc.current.id).map(as[AttachmentsView])
        files    <- fileCount(doc.id)
      yield assertTrue(
        tooLarge.status == Status.RequestEntityTooLarge,
        tooLarge.error.contains("attachment_too_large"),
        atLimit.status == Status.Created,
        exe.status == Status.UnprocessableEntity,
        exe.error.contains("attachment_blocked"),
        double.error.contains("attachment_blocked"),
        disguise.error.contains("attachment_blocked"),
        empty.error.contains("invalid_upload"),
        noName.error.contains("invalid_name"),
        noFile.error.contains("invalid_upload"),
        notMulti.status == Status.UnsupportedMediaType,
        infected.status == Status.UnprocessableEntity,
        infected.error.contains("attachment_infected"),
        noScan.status == Status.ServiceUnavailable,
        noScan.error.contains("scanner_unavailable"),
        listed.attachments.map(_.name) == List("limit.txt"),
        files == 1L
      )
    },
    test("only those who may edit the draft change attachments; only readers of the version see them") {
      for
        doc      <- draft
        byReader <- upload(reader, doc.id, "notes.pdf", pdf)
        added    <- upload(author, doc.id, "notes.pdf", pdf).map(as[AttachmentView])
        listing  <- attachments(reader, doc.id, doc.current.id)
        fetching <- download(reader, doc.id, added.id)
        renaming <- call(
          reader,
          "PATCH",
          s"/api/v1/documents/${doc.id}/attachments/${added.id}",
          Some("""{"name":"x.pdf"}""")
        )
        removing <- call(reader, "DELETE", s"/api/v1/documents/${doc.id}/attachments/${added.id}")
        unknown  <- download(author, doc.id, UUID.randomUUID())
        otherDoc <- draft
        wrongDoc <- download(author, otherDoc.id, added.id)
        _        <- call(author, "POST", s"/api/v1/documents/${doc.id}/submit")
        inReview <- attachments(reviewer, doc.id, doc.current.id).map(as[AttachmentsView])
        byReview <- download(reviewer, doc.id, added.id)
      yield assertTrue(
        byReader.status == Status.Forbidden,
        listing.status == Status.Forbidden,
        fetching.status == Status.Forbidden,
        renaming.status == Status.Forbidden,
        removing.status == Status.Forbidden,
        unknown.status == Status.NotFound,
        wrongDoc.status == Status.NotFound,
        inReview.attachments.map(_.name) == List("notes.pdf"),
        !inReview.editable,
        byReview.status == Status.Ok
      )
    },
    test("attachments are renamed and removed in the draft and frozen from submission on") {
      for
        doc    <- draft
        first  <- upload(author, doc.id, "a.pdf", pdf).map(as[AttachmentView])
        second <- upload(author, doc.id, "b.txt", "second".getBytes(UTF_8), "text/plain").map(
          as[AttachmentView]
        )
        renamed <- call(
          author,
          "PATCH",
          s"/api/v1/documents/${doc.id}/attachments/${first.id}",
          Some("""{"name":"Annex 1.pdf"}""")
        )
        taken <- call(
          author,
          "PATCH",
          s"/api/v1/documents/${doc.id}/attachments/${second.id}",
          Some("""{"name":"annex 1.PDF"}""")
        )
        blocked <- call(
          author,
          "PATCH",
          s"/api/v1/documents/${doc.id}/attachments/${second.id}",
          Some("""{"name":"run.exe"}""")
        )
        invalid <- call(
          author,
          "PATCH",
          s"/api/v1/documents/${doc.id}/attachments/${second.id}",
          Some("""{"name":"  "}""")
        )
        removed  <- call(author, "DELETE", s"/api/v1/documents/${doc.id}/attachments/${second.id}")
        again    <- call(author, "DELETE", s"/api/v1/documents/${doc.id}/attachments/${second.id}")
        _        <- call(author, "POST", s"/api/v1/documents/${doc.id}/submit")
        late     <- upload(author, doc.id, "late.pdf", "late".getBytes(UTF_8))
        lateName <- call(
          author,
          "PATCH",
          s"/api/v1/documents/${doc.id}/attachments/${first.id}",
          Some("""{"name":"x.pdf"}""")
        )
        lateDel <- call(author, "DELETE", s"/api/v1/documents/${doc.id}/attachments/${first.id}")
        listed  <- attachments(author, doc.id, doc.current.id).map(as[AttachmentsView])
        actions <- auditActions(doc.id)
        files   <- fileCount(doc.id)
      yield assertTrue(
        as[AttachmentView](renamed).name == "Annex 1.pdf",
        taken.status == Status.Conflict,
        blocked.error.contains("attachment_blocked"),
        invalid.error.contains("invalid_name"),
        removed.status == Status.NoContent,
        again.status == Status.NotFound,
        late.status == Status.Conflict,
        lateName.status == Status.Conflict,
        lateDel.status == Status.Conflict,
        listed.attachments.map(_.name) == List("Annex 1.pdf"),
        !listed.editable,
        actions == List(
          "document.attachment-added",
          "document.attachment-added",
          "document.attachment-renamed",
          "document.attachment-removed"
        ),
        // removing unlinks only: the stored file stays
        files == 2L
      )
    },
    test(
      "attachments are part of the approved package and are taken over by the next version without copies"
    ) {
      for
        doc      <- draft
        added    <- upload(author, doc.id, "Annex.pdf", pdf).map(as[AttachmentView])
        _        <- approveAndPublish(doc.id)
        approval <- cases(doc.id).map(_.last.approval.get)
        s        <- setup.await
        details  <- ZIO.serviceWithZIO[DocumentService](svc =>
          s.app.transact(tenantId)(svc.get(dms.kernel.DocumentId(doc.id)))
        )
        version = details.versions.head
        published <- attachments(reader, doc.id, doc.current.id).map(as[AttachmentsView])
        byReader  <- download(reader, doc.id, added.id)
        next      <- call(
          author,
          "POST",
          s"/api/v1/documents/${doc.id}/versions",
          Some("""{"changeKind":"Major"}""")
        )
          .map(as[DocumentVersionView])
        carried <- attachments(author, doc.id, next.id).map(as[AttachmentsView])
        taken = carried.attachments.head
        removed <- call(author, "DELETE", s"/api/v1/documents/${doc.id}/attachments/${taken.id}")
        frozen  <- call(author, "DELETE", s"/api/v1/documents/${doc.id}/attachments/${added.id}")
        old     <- attachments(reader, doc.id, doc.current.id).map(as[AttachmentsView])
        files   <- fileCount(doc.id)
      yield assertTrue(
        // the checksum the approval binds covers the attachments
        approval.contentSha256 == WorkflowDocuments.contentSha256(version),
        approval.contentSha256 != WorkflowDocuments.contentSha256(version.copy(attachments = Nil)),
        published.status == "Published",
        published.attachments.map(_.id) == List(added.id),
        byReader.status == Status.Ok,
        byReader.body.toArray.sameElements(pdf),
        carried.status == "Draft",
        carried.editable,
        carried.attachments.size == 1,
        taken.id != added.id,
        taken.basedOn.contains(added.id),
        taken.sha256 == added.sha256,
        taken.name == added.name,
        removed.status == Status.NoContent,
        frozen.status == Status.Conflict,
        old.attachments.map(_.id) == List(added.id),
        files == 1L
      )
    },
    test("the body limit lets only attachment uploads stream large bodies") {
      val streamed = Body.fromStreamChunked(ZStream.fromIterable(Array.fill(10)('x'.toByte)))
      val handler  = Main.limitStreamedBodies(zio.http.Handler.ok)
      ZIO.scoped(
        for
          other   <- handler(Request.put(URL.decode("/api/v1/documents/x/draft").toOption.get, streamed))
          uploadR <- handler(
            Request.post(
              URL.decode(s"/api/v1/documents/${UUID.randomUUID()}/attachments").toOption.get,
              streamed
            )
          )
          small <- handler(
            Request.put(URL.decode("/api/v1/documents/x/draft").toOption.get, Body.fromString("{}"))
          )
        yield assertTrue(
          other.status == Status.RequestEntityTooLarge,
          uploadR.status == Status.Ok,
          small.status == Status.Ok
        )
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
