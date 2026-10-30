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
import java.util.{Date, HexFormat, UUID}
import javax.sql.DataSource

/** Discarding drafts end to end (DMS-129, ADR 0050): who may discard, which statuses can be discarded, the
  * first version (the document disappears) versus a new version (the valid version stays), the workflow case
  * of a returned draft, attachment links and files (released, or kept under legal hold) and the audit trail.
  * Against PostgreSQL and SeaweedFS started by Testcontainers.
  */
object DiscardDraftApiSpec extends ZIOSpecDefault:

  private val issuer    = "http://localhost:8085"
  private val audience  = "project-1"
  private val orgId     = "org-1"
  private val tenantId  = TenantId.random()
  private val admin     = UserId("admin-1")
  private val author    = UserId("author-1")
  private val author2   = UserId("author-2")
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
            _   <- RoleAssignments.Postgres.grant(author2, RoleAssignment(Role.Author), admin)
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

  /** The audit entries of a document: action, version label, reason and details. */
  private def auditOf(doc: UUID) =
    ownerQuery(
      sql"""select action, coalesce(object_version, ''), coalesce(reason, ''), details::text
            from dms.audit_event where object_id = $doc order by seq"""
        .query[(String, String, String, String)]
        .run()
        .toList
    )

  private def discard(user: UserId, doc: UUID, reason: Option[String] = None) =
    call(user, "POST", s"/api/v1/documents/$doc/draft/discard", Some(DiscardDraft(reason).toJson))

  private def versionStatuses(doc: UUID) =
    ownerQuery(
      sql"""select major, minor, working_state, status from dms.document_version where document_id = $doc
            order by created_at, id""".query[(Int, Int, Int, String)].run().toList
    )

  private def caseStatus(doc: UUID) =
    ownerQuery(
      sql"select status from dms.workflow_case where document_id = $doc order by started_at desc limit 1"
        .query[String]
        .run()
        .headOption
    )

  private def objectKeyOf(doc: UUID, sha: String) =
    ownerQuery(
      sql"""select object_key from dms.document_attachment_file where document_id = $doc and sha256 = $sha"""
        .query[String]
        .run()
        .head
    )

  /** Runs the queued clean-up jobs of released files. */
  private def runCleanup: ZIO[Env, Any, Unit] =
    for
      db      <- ZIO.service[Db]
      objects <- ZIO.service[ObjectStore]
      _       <- JobRunner(db, FileCleanupJobs(db, objects).handlers, WorkerConfig("cleanup-test")).runOnce
    yield ()

  private def objectExists(key: String) =
    ZIO.serviceWithZIO[ObjectStore](_.listKeys(key).map(_.contains(key)).orElseSucceed(false))

  private def fileCount(doc: UUID) =
    ownerQuery(
      sql"select count(*) from dms.document_attachment_file where document_id = $doc".query[Long].run().head
    )

  private val pdf = "%PDF-1.7\nTravel expense form\n%%EOF\n".getBytes(UTF_8)

  def spec = suite("Discarding drafts")(
    test(
      "the author discards a first draft: the document disappears, the audit trail keeps who, when and why"
    ) {
      for
        doc <- draft
        number = "POL-" + doc.id.toString.take(8)
        _ <- ownerQuery(
          sql"update dms.document set document_number = $number where id = ${doc.id}".update.run()
        )
        shown    <- call(author, "GET", s"/api/v1/documents/${doc.id}").map(as[DocumentView])
        listed   <- call(author, "GET", "/api/v1/documents").map(as[List[DocumentSummaryView]])
        response <- discard(author, doc.id, Some("  Created by accident  "))
        result = as[DiscardedDraftView](response)
        afterGet  <- call(author, "GET", s"/api/v1/documents/${doc.id}")
        ownerGet  <- call(owner, "GET", s"/api/v1/documents/${doc.id}")
        versions  <- call(author, "GET", s"/api/v1/documents/${doc.id}/versions")
        afterList <- call(author, "GET", "/api/v1/documents").map(as[List[DocumentSummaryView]])
        again     <- discard(author, doc.id)
        edit      <- call(author, "PUT", s"/api/v1/documents/${doc.id}/draft", Some("""{"values":{}}"""))
        statuses  <- versionStatuses(doc.id)
        stored    <- ownerQuery(
          sql"select document_number from dms.document where id = ${doc.id}".query[Option[String]].run().head
        )
        audit  <- auditOf(doc.id)
        outbox <- ownerQuery(
          sql"""select count(*) from dms.outbox_event
                where aggregate_id = ${doc.id} and event_type = 'document.discarded'""".query[Long].run().head
        )
      yield
        val entry = audit.find(_._1 == "document.discarded")
        assertTrue(
          shown.canDiscardDraft,
          listed.exists(_.id == doc.id),
          response.status == Status.Ok,
          result.documentDiscarded,
          result.version == "1.0-draft.1",
          result.validVersionId.isEmpty,
          afterGet.status == Status.NotFound,
          ownerGet.status == Status.NotFound,
          versions.status == Status.NotFound,
          !afterList.exists(_.id == doc.id),
          again.status == Status.NotFound,
          edit.status == Status.Conflict,
          statuses == List((1, 0, 1, "Discarded")),
          // The number stays with the discarded document: reserved, never handed out again.
          stored.contains(number),
          entry.exists(_._2 == "1.0"),
          entry.exists(_._3 == "Created by accident"),
          entry.exists(_._4.contains("\"documentDiscarded\": true")),
          entry.exists(_._4.contains(s"\"documentNumber\": \"$number\"")),
          outbox == 1L
        )
    },
    test("the owner and tenant administrators may discard; other authors, reviewers and readers may not") {
      for
        first     <- draft
        second    <- draft
        third     <- draft
        other     <- discard(author2, first.id)
        reviewed  <- discard(reviewer, first.id)
        read      <- discard(reader, first.id)
        flag      <- call(owner, "GET", s"/api/v1/documents/${first.id}").map(as[DocumentView])
        byOwner   <- discard(owner, first.id)
        byAdmin   <- discard(admin, second.id)
        tooLong   <- discard(author, third.id, Some("x" * 4001))
        stillOpen <- call(author, "GET", s"/api/v1/documents/${third.id}").map(as[DocumentView])
        audit     <- auditOf(second.id)
      yield assertTrue(
        other.status == Status.Forbidden,
        reviewed.status == Status.Forbidden,
        read.status == Status.Forbidden,
        flag.canDiscardDraft,
        byOwner.status == Status.Ok,
        byAdmin.status == Status.Ok,
        as[DiscardedDraftView](byAdmin).documentDiscarded,
        tooLong.status == Status.UnprocessableEntity,
        stillOpen.current.status == "Draft",
        audit.exists(e => e._1 == "document.discarded" && e._3 == "")
      )
    },
    test("submitted, approved and published versions cannot be discarded (409)") {
      for
        doc       <- draft
        _         <- call(author, "POST", s"/api/v1/documents/${doc.id}/submit")
        inReview  <- discard(author, doc.id)
        flag      <- call(author, "GET", s"/api/v1/documents/${doc.id}").map(as[DocumentView])
        published <- draft
        _         <- approveAndPublish(published.id)
        valid     <- discard(owner, published.id)
        statuses  <- versionStatuses(published.id)
      yield assertTrue(
        inReview.status == Status.Conflict,
        !flag.canDiscardDraft,
        flag.current.status == "InReview",
        valid.status == Status.Conflict,
        statuses.map(_._4) == List("Published")
      )
    },
    test("a new version is discarded alone: the valid version stays and a new version can be started again") {
      for
        doc     <- draft
        _       <- approveAndPublish(doc.id)
        started <- call(
          author,
          "POST",
          s"/api/v1/documents/${doc.id}/versions",
          Some("""{"changeKind":"Major","reason":"New travel rules"}""")
        ).map(as[DocumentVersionView])
        result   <- discard(author, doc.id, Some("Wrong approach")).map(as[DiscardedDraftView])
        shown    <- call(author, "GET", s"/api/v1/documents/${doc.id}").map(as[DocumentView])
        versions <- call(owner, "GET", s"/api/v1/documents/${doc.id}/versions")
          .map(as[List[DocumentVersionView]])
        listed <- call(author, "GET", "/api/v1/documents").map(as[List[DocumentSummaryView]])
        again  <- call(
          author,
          "POST",
          s"/api/v1/documents/${doc.id}/versions",
          Some("""{"changeKind":"Major","reason":"Second attempt"}""")
        )
        statuses <- versionStatuses(doc.id)
      yield assertTrue(
        started.version == "2.0-draft.1",
        !result.documentDiscarded,
        result.version == "2.0-draft.1",
        result.validVersionId.contains(doc.current.id),
        result.validVersion.contains("1.0"),
        shown.current.id == doc.current.id,
        shown.current.status == "Published",
        shown.canStartVersion,
        !shown.canDiscardDraft,
        // The discarded draft is not readable, not even for the owner.
        versions.map(_.status) == List("Published"),
        listed.find(_.id == doc.id).exists(_.status == "Published"),
        again.status == Status.Created,
        // The number 2.0 is free again; working states continue after the discarded draft's.
        as[DocumentVersionView](again).version == "2.0-draft.2",
        statuses.map(_._4) == List("Published", "Discarded", "Draft")
      )
    },
    test("the workflow case of a returned draft ends and its pending tasks are cancelled") {
      for
        doc    <- draft
        _      <- call(author, "POST", s"/api/v1/documents/${doc.id}/submit")
        review <- cases(doc.id).map(_.last.tasks.find(_.status == "Open").get)
        _      <- call(
          reviewer,
          "POST",
          s"/api/v1/workflow/tasks/${review.id}/votes",
          Some(CastVote("RequestChanges", Some("Please add the limits")).toJson)
        )
        returned <- caseStatus(doc.id)
        caseId   <- cases(doc.id).map(_.last.id)
        // A task still pending on the returned case (e.g. left by an earlier failure) is cancelled as well.
        taskId = s"pending-$caseId"
        _ <- ownerQuery(
          sql"""insert into dms.workflow_task (id, tenant_id, case_id, round, step_key, step_kind, lane_key, role,
                                               candidates, status)
                values ($taskId, ${tenantId.value}, $caseId, 1, ${"review"}, ${"Review"}, ${"reviewer"},
                        ${"Reviewer"}, ${"[\"reviewer-1\"]"}::jsonb, ${"Open"})""".update.run()
        )
        result <- discard(author, doc.id).map(as[DiscardedDraftView])
        ended  <- caseStatus(doc.id)
        open   <- ownerQuery(
          sql"""select count(*) from dms.workflow_task where case_id = $caseId and status in ('Open', 'Waiting')"""
            .query[Long]
            .run()
            .head
        )
        inbox <- call(reviewer, "GET", "/api/v1/workflow/tasks").map(as[List[TaskView]])
        audit <- auditOf(doc.id)
      yield assertTrue(
        returned.contains("Returned"),
        result.cancelledTasks == 1,
        ended.contains("Discarded"),
        open == 0L,
        !inbox.exists(_.documentId == doc.id),
        audit.exists(_._1 == "workflow.discarded")
      )
    },
    test(
      "attachments of the draft are unlinked; files only it referenced are deleted, other versions keep theirs"
    ) {
      for
        doc  <- draft
        kept <- upload(author, doc.id, "Annex.pdf", pdf).map(as[AttachmentView])
        _    <- approveAndPublish(doc.id)
        _    <- call(
          author,
          "POST",
          s"/api/v1/documents/${doc.id}/versions",
          Some("""{"changeKind":"Minor","reason":"Typo"}""")
        )
        added <- upload(author, doc.id, "Draft only.txt", "only in the draft".getBytes(UTF_8), "text/plain")
          .map(as[AttachmentView])
        keptKey  <- objectKeyOf(doc.id, kept.sha256)
        addedKey <- objectKeyOf(doc.id, added.sha256)
        result   <- discard(author, doc.id).map(as[DiscardedDraftView])
        files    <- fileCount(doc.id)
        before   <- objectExists(addedKey)
        jobs     <- ownerQuery(
          sql"""select count(*) from dms.job
                where kind = 'documents.file-cleanup' and payload->>'documentId' = ${doc.id.toString}"""
            .query[Long]
            .run()
            .head
        )
        _      <- runCleanup
        after  <- objectExists(addedKey)
        stays  <- objectExists(keptKey)
        listed <- attachments(author, doc.id, doc.current.id).map(as[AttachmentsView])
        got    <- download(author, doc.id, listed.attachments.head.id)
      yield assertTrue(
        result.removedAttachments == 2,
        result.releasedFiles == 1,
        result.keptFiles == 0,
        files == 1L,
        jobs == 1L,
        before,
        !after,
        stays,
        listed.attachments.map(_.name) == List("Annex.pdf"),
        got.status == Status.Ok,
        sha256(got.body.toArray) == kept.sha256
      )
    },
    test("under legal hold the files of a discarded draft are kept") {
      for
        doc   <- draft
        added <- upload(author, doc.id, "Evidence.pdf", "keep me".getBytes(UTF_8)).map(as[AttachmentView])
        key   <- objectKeyOf(doc.id, added.sha256)
        hold = UUID.randomUUID()
        _ <- ownerQuery {
          val _ = sql"""insert into dms.legal_hold (id, tenant_id, name, reason, created_by)
                        values ($hold, ${tenantId.value}, ${"Litigation"}, ${"Pending case"}, ${"records-1"})""".update
            .run()
          sql"""insert into dms.legal_hold_document (tenant_id, hold_id, document_id, added_via, reason, added_by)
                values (${tenantId.value}, $hold, ${doc.id}, ${"Document"}, ${"Pending case"}, ${"records-1"})""".update
            .run()
        }
        result <- discard(author, doc.id).map(as[DiscardedDraftView])
        files  <- fileCount(doc.id)
        _      <- runCleanup
        stays  <- objectExists(key)
        audit  <- auditOf(doc.id)
      yield assertTrue(
        result.documentDiscarded,
        result.releasedFiles == 0,
        result.keptFiles == 1,
        files == 1L,
        stays,
        audit.exists(e => e._1 == "document.discarded" && e._4.contains("\"legalHold\": true"))
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
