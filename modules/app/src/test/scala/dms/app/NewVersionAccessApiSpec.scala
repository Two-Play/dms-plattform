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

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.time.{Instant, LocalDate}
import java.util.{Date, UUID}
import javax.sql.DataSource

/** Who may start a version of a confidential document and what a draft of a later version can change
  * (DMS-158, ADR 0074): clearance for the version a draft starts from, no lowering of its confidentiality,
  * and owner and title of the document staying as they are until the version takes effect. Against PostgreSQL
  * and SeaweedFS started by Testcontainers.
  */
object NewVersionAccessApiSpec extends ZIOSpecDefault:

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

  /** Holds the owner role in Finance only: answers for a document, and has no right in another unit. */
  private val financeOwner = UserId("owner-finance")

  /** An author of another unit, in the access group: reaches a draft only if it moves there. */
  private val salesAuthor = UserId("author-sales")

  /** A records manager for the whole tenant, in the access group. */
  private val records = UserId("records-1")

  /** A records manager for the whole tenant, in no access group. */
  private val unclearedRecords = UserId("records-uncleared")

  /** A records manager of another unit, in the access group. */
  private val salesRecords = UserId("records-sales")

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
            _ <- RoleAssignments.Postgres.grant(
              financeOwner,
              RoleAssignment(Role.Owner, organizationalUnit = Some("Finance")),
              admin
            )
            _ <- RoleAssignments.Postgres.grant(
              salesAuthor,
              RoleAssignment(Role.Author, organizationalUnit = Some("Sales")),
              admin
            )
            _ <- RoleAssignments.Postgres.grant(records, RoleAssignment(Role.RecordsManager), admin)
            _ <- RoleAssignments.Postgres.grant(unclearedRecords, RoleAssignment(Role.RecordsManager), admin)
            _ <- RoleAssignments.Postgres.grant(
              salesRecords,
              RoleAssignment(Role.RecordsManager, organizationalUnit = Some("Sales")),
              admin
            )
            _ <- Groups.Postgres.create("board", "Board", admin)
            _ <- Groups.Postgres.create("works-council", "Works council", admin)
            _ <- ZIO.foreachDiscard(List(member, reviewer, approver, salesAuthor, records, salesRecords))(
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
        AttachmentApi.endpoints.map(_.widen[Env]) ++ RecordsApi.endpoints.map(_.widen[Env])
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

  private def upload(user: UserId, doc: UUID, name: String, bytes: Array[Byte]) =
    val head =
      s"--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\n" +
        "Content-Type: application/octet-stream\r\n\r\n"
    val all = head.getBytes(UTF_8) ++ bytes ++ s"\r\n--$boundary--\r\n".getBytes(UTF_8)
    send(
      user,
      Request
        .post(URL.decode(s"/api/v1/documents/$doc/attachments").toOption.get, Body.fromArray(all))
        .addHeader(Header.ContentType(MediaType.multipart.`form-data`, Some(zio.http.Boundary(boundary))))
    )

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
      validFrom: LocalDate = past,
      unit: String = "Finance"
  ) =
    val confidentiality = level.fold("")(l => s""""confidentiality":{"type":"Selection","code":"$l"},""")
    val allowed         = groups.map(g => s"""{"type":"Text","value":"$g"}""").mkString(",")
    val access = if groups.isEmpty then "" else s""""allowedGroups":{"type":"Many","values":[$allowed]},"""
    s"""{"owner":{"type":"PersonRef","userId":"${ownerId.value}"},
       |"organizationalUnit":{"type":"Text","value":"$unit"},
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

  /** Owner and title as `dms.document` has them. */
  private def stored(doc: UUID) =
    ownerQuery(
      sql"select owner_id, title from dms.document where id = $doc".query[(Option[String], String)].run().head
    )

  private def versionCount(doc: UUID) =
    ownerQuery(
      sql"select count(*) from dms.document_version where document_id = $doc".query[Long].run().head
    )

  private def auditActions(doc: UUID) =
    ownerQuery(
      sql"select action, details::text from dms.audit_event where object_id = $doc order by seq"
        .query[(String, String)]
        .run()
        .toList
    )

  private def issues(response: Response) = as[ApiError](response).issues.toList.flatten

  private def outbox(doc: UUID) =
    ownerQuery(
      sql"select event_type, payload::text from dms.outbox_event where aggregate_id = $doc order by id"
        .query[(String, String)]
        .run()
        .toList
    )

  /** What a draft saved before ADR 0074 left on a document. */
  private def takeOver(doc: UUID, by: UserId = accomplice, title: String = "Hijacked") =
    ownerQuery(
      sql"update dms.document set owner_id = ${by.value}, title = $title where id = $doc".update.run()
    )

  /** What a draft saved before ADR 0074 could do to its own level. */
  private def lowerOpenVersion(doc: UUID, level: String = "Internal") =
    val code = s"\"$level\""
    ownerQuery(
      sql"""update dms.document_version
            set field_values = jsonb_set(field_values, '{confidentiality,value,code}', $code::jsonb)
            where document_id = $doc and status = 'Draft'""".update.run()
    )

  private def differences(user: UserId) =
    call(user, "GET", "/api/v1/records/document-differences")

  private def belowBasis(user: UserId) =
    call(user, "GET", "/api/v1/records/drafts-below-basis")

  private def difference(user: UserId, doc: UUID) =
    differences(user).map(as[RecordDifferencesView](_).differences.find(_.documentId == doc))

  private def correct(
      user: UserId,
      doc: UUID,
      version: UUID,
      owner: Boolean = true,
      title: Boolean = true,
      reason: String = "Taken over by a draft"
  ) =
    call(
      user,
      "POST",
      s"/api/v1/documents/$doc/record-correction",
      Some(CorrectRecord(version, owner, title, reason).toJson)
    )

  private def lastReading(action: String) =
    ownerQuery(
      sql"select actor_id, details::text from dms.audit_event where action = $action order by seq desc limit 1"
        .query[(String, String)]
        .run()
        .headOption
    )

  def spec = suite("New versions of confidential documents")(
    suite("Starting a version")(
      test("an author outside the allowed groups gets 403 and sees nothing") {
        for
          doc      <- published()
          response <- startVersion(outsider, doc.id)
          count    <- versionCount(doc.id)
          read     <- call(outsider, "GET", s"/api/v1/documents/${doc.id}")
        yield assertTrue(
          response.status == Status.Forbidden,
          !response.text.contains("Travel expenses"),
          !response.text.contains("\"values\""),
          count == 1L,
          read.status == Status.Forbidden
        )
      },
      test("the same for a strictly confidential document") {
        for
          doc      <- published(values(level = Some("StrictlyConfidential")))
          response <- startVersion(outsider, doc.id)
          minor    <- startVersion(outsider, doc.id, "Minor")
          count    <- versionCount(doc.id)
        yield assertTrue(
          response.status == Status.Forbidden,
          minor.status == Status.Forbidden,
          count == 1L
        )
      },
      test("attachments of the valid version stay out of reach") {
        for
          doc      <- draft()
          uploaded <- upload(author, doc.id, "salaries.txt", "secret".getBytes(UTF_8))
          _        <- approve(doc.id, author)
          _        <- publishDue
          response <- startVersion(outsider, doc.id)
          listed   <- call(
            outsider,
            "GET",
            s"/api/v1/documents/${doc.id}/versions/${doc.current.id}/attachments"
          )
          count <- versionCount(doc.id)
        yield assertTrue(
          uploaded.status == Status.Created,
          response.status == Status.Forbidden,
          listed.status == Status.Forbidden,
          count == 1L
        )
      },
      test("a member of an allowed group still can, and is offered it") {
        for
          doc     <- published()
          view    <- document(member, doc.id)
          started <- startVersion(member, doc.id)
          draft = as[DocumentVersionView](started)
        yield assertTrue(
          view.canStartVersion,
          started.status == Status.Created,
          draft.createdBy == member.value,
          draft.content == doc.current.content
        )
      },
      test("the author and the owner of the valid version still can") {
        for
          first    <- published()
          second   <- published()
          byAuthor <- startVersion(author, first.id)
          byOwner  <- startVersion(owner, second.id)
        yield assertTrue(byAuthor.status == Status.Created, byOwner.status == Status.Created)
      },
      test("any author can start a version of a document that is not confidential") {
        for
          doc      <- published(values(level = Some("Internal"), groups = Nil))
          view     <- document(outsider, doc.id)
          response <- startVersion(outsider, doc.id)
        yield assertTrue(view.canStartVersion, response.status == Status.Created)
      }
    ),
    suite("Confidentiality of a later version")(
      test("a draft cannot lower its confidentiality, not even by leaving the field out") {
        for
          doc     <- published()
          _       <- startVersion(member, doc.id)
          lowered <- save(member, doc.id, values(level = Some("Internal"), groups = Nil))
          omitted <- save(member, doc.id, values(level = None, groups = Nil))
          byOwner <- save(owner, doc.id, values(level = Some("Internal"), groups = Nil))
          outside <- call(outsider, "GET", s"/api/v1/documents/${doc.id}")
        yield assertTrue(
          lowered.status == Status.UnprocessableEntity,
          lowered.error.contains("invalid_values"),
          issues(lowered).map(i => (i.field, i.code)) == List(("confidentiality", "confidentiality_lowered")),
          omitted.status == Status.UnprocessableEntity,
          byOwner.status == Status.UnprocessableEntity,
          outside.status == Status.Forbidden
        )
      },
      test("a draft may raise its confidentiality and narrow its groups") {
        for
          doc    <- published(values(groups = List("board", "works-council")))
          _      <- startVersion(member, doc.id)
          raised <- save(member, doc.id, values(level = Some("StrictlyConfidential"), groups = List("board")))
        yield assertTrue(raised.status == Status.Ok)
      },
      test("only the document's owner may add an access group") {
        for
          doc     <- published()
          _       <- startVersion(member, doc.id)
          widened <- save(member, doc.id, values(groups = List("board", "works-council")))
          byOwner <- save(owner, doc.id, values(groups = List("board", "works-council")))
          again   <- save(member, doc.id, values(groups = List("board", "works-council")))
        yield assertTrue(
          widened.status == Status.UnprocessableEntity,
          issues(widened).map(i => (i.field, i.code)) == List(("allowedGroups", "access_groups_widened")),
          byOwner.status == Status.Ok,
          // What the owner added stays when the author goes on working on the draft.
          again.status == Status.Ok
        )
      },
      test("a draft lowered before this rule existed cannot be submitted") {
        for
          doc   <- published()
          draft <- startVersion(member, doc.id).map(as[DocumentVersionView])
          _     <- ownerQuery(
            sql"""update dms.document_version
                  set field_values = jsonb_set(field_values, '{confidentiality,value,code}', '"Internal"')
                  where id = ${draft.id}""".update.run()
          )
          response <- call(member, "POST", s"/api/v1/documents/${doc.id}/submit")
        yield assertTrue(
          response.status == Status.UnprocessableEntity,
          issues(response).map(_.code) == List("confidentiality_lowered")
        )
      },
      test("the first version chooses its confidentiality freely") {
        for
          doc     <- draft()
          lowered <- save(author, doc.id, values(level = Some("Internal"), groups = Nil))
        yield assertTrue(lowered.status == Status.Ok)
      }
    ),
    suite("Owner and title of the document")(
      test("a first version's draft sets them") {
        for
          doc   <- draft()
          saved <- save(author, doc.id, values(ownerId = accomplice), "Renamed")
          after <- stored(doc.id)
        yield assertTrue(saved.status == Status.Ok, after == (Some(accomplice.value), "Renamed"))
      },
      test("a later version's draft leaves them, and the owner in force keeps clearance and rights") {
        for
          doc      <- published()
          _        <- startVersion(member, doc.id)
          saved    <- save(member, doc.id, values(ownerId = member), "Hijacked").map(as[DocumentVersionView])
          after    <- stored(doc.id)
          view     <- document(owner, doc.id)
          list     <- call(owner, "GET", "/api/v1/documents").map(as[List[DocumentSummaryView]])
          proposed <- call(accomplice, "GET", s"/api/v1/documents/${doc.id}")
        yield assertTrue(
          after == (Some(owner.value), "Board minutes"),
          view.title == "Board minutes",
          view.ownerId.contains(owner.value),
          list.find(_.id == doc.id).map(_.title).contains("Board minutes"),
          // The draft itself keeps what its author entered.
          saved.values.get("title").map(_.value).contains(MetadataValue.Text("Hijacked")),
          proposed.status == Status.Forbidden
        )
      },
      test("the owner named in a draft is not cleared for the document before publication") {
        for
          doc    <- published()
          _      <- startVersion(member, doc.id)
          _      <- save(member, doc.id, values(ownerId = accomplice))
          before <- call(accomplice, "GET", s"/api/v1/documents/${doc.id}/versions")
        yield assertTrue(before.status == Status.Forbidden)
      },
      test("discarding a draft leaves them unchanged") {
        for
          doc      <- published()
          _        <- startVersion(member, doc.id)
          _        <- save(member, doc.id, values(ownerId = member), "Hijacked")
          response <- call(member, "POST", s"/api/v1/documents/${doc.id}/draft/discard", Some("{}"))
          after    <- stored(doc.id)
        yield assertTrue(response.status == Status.Ok, after == (Some(owner.value), "Board minutes"))
      },
      test("a version withdrawn before it took effect leaves them unchanged") {
        for
          doc   <- published()
          draft <- startVersion(member, doc.id).map(as[DocumentVersionView])
          _     <- save(member, doc.id, values(ownerId = accomplice, validFrom = future), "Renamed")
          _     <- approve(doc.id, member)
          waits <- stored(doc.id)
          gone  <- call(
            owner,
            "POST",
            s"/api/v1/documents/${doc.id}/versions/${draft.id}/withdraw",
            Some("""{"reason":"Stopped"}""")
          )
          after <- stored(doc.id)
        yield assertTrue(
          gone.status == Status.Ok,
          waits == (Some(owner.value), "Board minutes"),
          after == (Some(owner.value), "Board minutes")
        )
      },
      test("publication applies them and records the change") {
        for
          doc    <- published()
          _      <- startVersion(member, doc.id)
          _      <- save(member, doc.id, values(ownerId = accomplice), "Renamed")
          _      <- approve(doc.id, member)
          before <- stored(doc.id)
          _      <- publishDue
          after  <- stored(doc.id)
          audit  <- auditActions(doc.id)
          read   <- call(accomplice, "GET", s"/api/v1/documents/${doc.id}")
        yield assertTrue(
          before == (Some(owner.value), "Board minutes"),
          after == (Some(accomplice.value), "Renamed"),
          read.status == Status.Ok,
          audit.exists((action, details) =>
            action == "document.owner-and-title-applied" && details.contains(accomplice.value) &&
              details.contains("Renamed")
          )
        )
      },
      test("the editorial confirmation goes to the owner in force, not the one the draft names") {
        for
          doc <- published()
          _   <- startVersion(member, doc.id, "Minor")
          _   <- save(member, doc.id, values(ownerId = accomplice))
          _   <- call(member, "POST", s"/api/v1/documents/${doc.id}/submit")
          all <- cases(doc.id)
          task = all.last.tasks.find(_.status == "Open").get
        yield assertTrue(task.candidates == List(owner.value))
      }
    ),
    suite("Moving a draft to another organizational unit")(
      test("only the document's owner moves a later version, and it stays where they moved it") {
        for
          doc     <- published()
          _       <- startVersion(member, doc.id)
          moved   <- save(member, doc.id, values(unit = "Sales"))
          byOwner <- save(owner, doc.id, values(unit = "Sales"))
          again   <- save(member, doc.id, values(unit = "Sales"))
          further <- save(member, doc.id, values(unit = "Legal"))
          back    <- save(member, doc.id, values())
        yield assertTrue(
          moved.status == Status.UnprocessableEntity,
          issues(moved).map(i => (i.field, i.code)) == List(("organizationalUnit", "unit_moved")),
          byOwner.status == Status.Ok,
          again.status == Status.Ok,
          further.status == Status.UnprocessableEntity,
          back.status == Status.Ok
        )
      },
      test("the owner moves it only to a unit in which they can edit it, and nothing is saved") {
        for
          doc   <- published(values(level = Some("Internal"), groups = Nil, ownerId = financeOwner))
          draft <- startVersion(member, doc.id).map(as[DocumentVersionView])
          moved <- save(
            financeOwner,
            doc.id,
            values(level = Some("Internal"), groups = Nil, ownerId = financeOwner, unit = "Sales")
          )
          unit <- ownerQuery(
            sql"""select field_values #>> '{organizationalUnit,value,value}', working_state
                  from dms.document_version where id = ${draft.id}""".query[(String, Int)].run().head
          )
        yield assertTrue(
          moved.status == Status.UnprocessableEntity,
          issues(moved).map(i => (i.field, i.code)) == List(("organizationalUnit", "unit_out_of_reach")),
          unit == ("Finance", draft.workingState)
        )
      },
      test("authors of the other unit reach the copied content only after the owner moved it") {
        val open = values(level = Some("Internal"), groups = Nil)
        for
          doc    <- published(open)
          _      <- startVersion(member, doc.id)
          before <- call(salesAuthor, "GET", s"/api/v1/documents/${doc.id}")
          _      <- save(member, doc.id, values(level = Some("Internal"), groups = Nil, unit = "Sales"))
          still  <- call(salesAuthor, "GET", s"/api/v1/documents/${doc.id}")
          _      <- save(owner, doc.id, values(level = Some("Internal"), groups = Nil, unit = "Sales"))
          after  <- call(salesAuthor, "GET", s"/api/v1/documents/${doc.id}")
        yield assertTrue(
          before.status == Status.Forbidden,
          still.status == Status.Forbidden,
          after.status == Status.Ok
        )
      },
      test("a first version moves freely, it copied nothing") {
        for
          doc   <- draft()
          moved <- save(author, doc.id, values(unit = "Sales"))
        yield assertTrue(moved.status == Status.Ok)
      }
    ),
    suite("Owner and title that differ from the version last in force")(
      test("records managers see the difference, within scope and clearance, and reading is recorded") {
        for
          doc     <- published()
          _       <- takeOver(doc.id)
          row     <- difference(records, doc.id)
          hidden  <- differences(unclearedRecords).map(as[RecordDifferencesView])
          other   <- differences(salesRecords).map(as[RecordDifferencesView])
          refused <- differences(owner)
          audited <- lastReading(RecordsApi.DifferencesViewed)
        yield assertTrue(
          row.exists(r =>
            r.documentOwner.contains(accomplice.value) && r.versionOwner.contains(owner.value) &&
              r.documentTitle == "Hijacked" && r.versionTitle.contains("Board minutes") &&
              r.versionLabel == "1.0" && r.inWorkflow.isEmpty && r.unit.contains("Finance")
          ),
          !hidden.differences.exists(_.documentId == doc.id),
          hidden.notCleared >= 1,
          !other.differences.exists(_.documentId == doc.id),
          other.notCleared == 0,
          refused.status == Status.Forbidden,
          audited.exists((_, details) => details.contains("notCleared"))
        )
      },
      test("a document that matches its version is not listed, nor is one with an open draft's values") {
        for
          doc <- published()
          _   <- startVersion(member, doc.id)
          _   <- save(member, doc.id, values(ownerId = accomplice), "Renamed")
          row <- difference(records, doc.id)
        yield assertTrue(row.isEmpty)
      },
      test("a correction is a decision per value, with a reason, and is recorded") {
        for
          doc       <- published()
          _         <- takeOver(doc.id)
          row       <- difference(records, doc.id).map(_.get)
          noReason  <- correct(records, doc.id, row.versionId, reason = "  ")
          nothing   <- correct(records, doc.id, row.versionId, owner = false, title = false)
          uncleared <- correct(unclearedRecords, doc.id, row.versionId)
          byOwner   <- correct(owner, doc.id, row.versionId)
          untouched <- stored(doc.id)
          ownerOnly <- correct(records, doc.id, row.versionId, title = false)
          afterOne  <- stored(doc.id)
          left      <- difference(records, doc.id)
          twice     <- correct(records, doc.id, row.versionId, title = false)
          titleOnly <- correct(records, doc.id, row.versionId, owner = false)
          afterAll  <- stored(doc.id)
          gone      <- difference(records, doc.id)
          audit     <- auditActions(doc.id)
          reasons   <- ownerQuery(
            sql"""select reason from dms.audit_event
                  where object_id = ${doc.id} and action = ${DocumentService.RecordCorrected}"""
              .query[Option[String]]
              .run()
              .toList
          )
          sent <- outbox(doc.id)
        yield assertTrue(
          noReason.status == Status.UnprocessableEntity,
          nothing.status == Status.UnprocessableEntity,
          uncleared.status == Status.Forbidden,
          byOwner.status == Status.Forbidden,
          untouched == (Some(accomplice.value), "Hijacked"),
          ownerOnly.status == Status.Ok,
          afterOne == (Some(owner.value), "Hijacked"),
          left.exists(r => r.versionOwner.isEmpty && r.versionTitle.contains("Board minutes")),
          twice.status == Status.Conflict,
          titleOnly.status == Status.Ok,
          afterAll == (Some(owner.value), "Board minutes"),
          gone.isEmpty,
          audit.count(_._1 == DocumentService.RecordCorrected) == 2,
          audit.exists((action, details) =>
            action == DocumentService.RecordCorrected && details.contains(accomplice.value) &&
              details.contains("Hijacked")
          ),
          reasons == List(Some("Taken over by a draft"), Some("Taken over by a draft")),
          sent.count(_._1 == DocumentService.OwnerCorrected) == 1,
          sent.exists((event, payload) =>
            event == DocumentService.OwnerCorrected && payload.contains(accomplice.value) &&
              payload.contains(owner.value)
          )
        )
      },
      test("the corrected owner has the document's rights, the former one loses them") {
        for
          doc    <- published()
          _      <- takeOver(doc.id)
          row    <- difference(records, doc.id).map(_.get)
          before <- call(owner, "GET", s"/api/v1/documents/${doc.id}")
          _      <- correct(records, doc.id, row.versionId)
          now    <- call(owner, "GET", s"/api/v1/documents/${doc.id}")
          former <- call(accomplice, "GET", s"/api/v1/documents/${doc.id}")
        yield assertTrue(
          before.status == Status.Forbidden,
          now.status == Status.Ok,
          former.status == Status.Forbidden
        )
      },
      test("a correction waits for a version in the workflow, not for a draft") {
        for
          doc   <- published()
          _     <- takeOver(doc.id)
          row   <- difference(records, doc.id).map(_.get)
          draft <- startVersion(member, doc.id)
          _     <- call(member, "POST", s"/api/v1/documents/${doc.id}/submit")
          shown <- difference(records, doc.id)
          held  <- correct(records, doc.id, row.versionId)
          after <- stored(doc.id)
        yield assertTrue(
          draft.status == Status.Created,
          shown.exists(_.inWorkflow.isDefined),
          held.status == Status.Conflict,
          after == (Some(accomplice.value), "Hijacked")
        )
      },
      test("a correction waits for a draft its workflow returned, whose votes stand") {
        for
          doc    <- published()
          _      <- takeOver(doc.id)
          row    <- difference(records, doc.id).map(_.get)
          _      <- startVersion(member, doc.id)
          _      <- call(member, "POST", s"/api/v1/documents/${doc.id}/submit")
          review <- call(member, "GET", s"/api/v1/documents/${doc.id}/workflow")
            .map(as[List[WorkflowCaseView]](_).last.tasks.find(_.status == "Open").get)
          back <- call(
            reviewer,
            "POST",
            s"/api/v1/workflow/tasks/${review.id}/votes",
            Some(CastVote("RequestChanges", Some("Figures are missing")).toJson)
          )
          shown <- difference(records, doc.id)
          held  <- correct(records, doc.id, row.versionId)
          after <- stored(doc.id)
        yield assertTrue(
          back.status == Status.Ok,
          shown.exists(_.inWorkflow.isDefined),
          held.status == Status.Conflict,
          after == (Some(accomplice.value), "Hijacked")
        )
      },
      test("a draft that never went into the workflow holds nothing back") {
        for
          doc       <- published()
          _         <- takeOver(doc.id)
          row       <- difference(records, doc.id).map(_.get)
          _         <- startVersion(member, doc.id)
          shown     <- difference(records, doc.id)
          corrected <- correct(records, doc.id, row.versionId)
        yield assertTrue(shown.exists(_.inWorkflow.isEmpty), corrected.status == Status.Ok)
      },
      test("a correction is refused on a version that is not the last in force") {
        for
          doc     <- published()
          _       <- takeOver(doc.id)
          draft   <- startVersion(member, doc.id).map(as[DocumentVersionView])
          refused <- correct(records, doc.id, draft.id)
        yield assertTrue(refused.status == Status.Conflict)
      }
    ),
    suite("Open versions below their basis")(
      test("records managers see them, within scope and clearance, and reading is recorded") {
        for
          doc     <- published()
          _       <- startVersion(member, doc.id)
          _       <- lowerOpenVersion(doc.id)
          list    <- belowBasis(records).map(as[DraftsBelowBasisView])
          hidden  <- belowBasis(unclearedRecords).map(as[DraftsBelowBasisView])
          other   <- belowBasis(salesRecords).map(as[DraftsBelowBasisView])
          refused <- belowBasis(author)
          audited <- lastReading(RecordsApi.DraftsViewed)
        yield assertTrue(
          list.drafts.exists(d =>
            d.documentId == doc.id && d.level == "Internal" && d.basisLevel == "Confidential" &&
              d.basisLabel == "1.0" && d.versionStatus == "Draft" && d.author == member.value &&
              d.owner.contains(owner.value)
          ),
          !hidden.drafts.exists(_.documentId == doc.id),
          !other.drafts.exists(_.documentId == doc.id),
          refused.status == Status.Forbidden,
          audited.isDefined
        )
      },
      test("the list shows what the basis says, nothing of what the draft proposes") {
        for
          doc  <- published()
          _    <- startVersion(member, doc.id)
          _    <- save(owner, doc.id, values(unit = "Sales"), "Project Moth")
          _    <- lowerOpenVersion(doc.id)
          read <- belowBasis(records)
          row = as[DraftsBelowBasisView](read).drafts.find(_.documentId == doc.id)
        yield assertTrue(
          row.exists(d => d.title == "Board minutes" && d.unit.contains("Finance")),
          !read.text.contains("Project Moth")
        )
      },
      test("a draft at the level of its basis is not listed") {
        for
          doc  <- published()
          _    <- startVersion(member, doc.id)
          list <- belowBasis(records).map(as[DraftsBelowBasisView])
        yield assertTrue(!list.drafts.exists(_.documentId == doc.id))
      }
    )
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
