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
import dms.archive.{ObjectRetentionPolicy, ObjectRetentions}
import dms.archive.core.ObjectRetentionSync
import dms.audit.AuditTrail
import dms.audit.core.{AuditTrailLive, TsaTrust}
import dms.authz.{Authorizer, Groups, Role, RoleAssignment, RoleAssignments}
import dms.db.queue.{JobQueue, OutboxEvent}
import dms.db.{Db, DbConfig, DbError, Jsonb, Migrations}
import dms.documents.core.*
import dms.documents.metadata.*
import dms.identity.*
import dms.integration.{JobRunner, WorkerConfig}
import dms.kernel.{TemplateId, TenantId, UserId}
import dms.notifications.{Mail, MailSender, Person, RecipientDirectory}
import dms.notifications.core.{DeliverySettings, NotificationJobs}
import dms.rendering.{RenditionEvents, ArchiveStandard, RenditionRequest}
import dms.rendering.core.*
import dms.storage.{ObjectStore, RetentionMode, S3ObjectStore, StorageConfig}
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
  * `ReadDocument`. A failing rendering leaves the version in approval. When the rendering or publication job
  * is dead, owner and records managers are told once, in the app and by email (DMS-142), through the real
  * outbox subscriber, delivery job and read check.
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
  private val records  = UserId("records-1")
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
            _ <- RoleAssignments.Postgres.grant(records, RoleAssignment(Role.RecordsManager), admin)
            _ <- classes
              .createField("contractPartner", FieldDraft("Contract partner", None, FieldType.Text()), admin)
            cls <- classes.createClass(
              "contract",
              None,
              ClassDraft("Contract", None, List(FieldBinding(FieldKey.unsafe("contractPartner"), 1))),
              admin
            )
            _ <- RetentionFixtures.assign(classes, cls.classId, "contract-10y")
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
    AuditTrail & ObjectStore

  private val env: ZLayer[Any, Throwable, Env] =
    ZLayer.make[Env](
      database,
      authenticator,
      objectStore,
      RenditionWiring.archive,
      RenderingWorker.renderer,
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
        RenditionApi.endpoints.map(_.widen[Env]) ++ RenditionStatusApi.endpoints.map(_.widen[Env]) ++
        PublicationStatusApi.endpoints.map(_.widen[Env])
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

  private def values(partner: String, validFrom: String, owner: UserId, access: Option[String]) =
    s""""contractPartner":{"type":"Text","value":"$partner"},
       |"owner":{"type":"PersonRef","userId":"${owner.value}"},
       |"organizationalUnit":{"type":"Text","value":"Legal"},
       |"validFrom":{"type":"Date","value":"$validFrom"},
       |"confidentiality":{"type":"Selection","code":"${access.fold("Internal")(_ => "Confidential")}"},
       |${access.fold("")(g =>
        s""""allowedGroups":{"type":"Many","values":[{"type":"Text","value":"$g"}]},"""
      )}
       |"language":{"type":"Text","value":"de"},
       |"retentionRule":{"type":"Text","value":"contract-10y"},
       |"containsPersonalData":{"type":"YesNo","value":false}""".stripMargin

  /** A document submitted, reviewed and approved by the last vote: its case waits for the rendition. */
  private def approvedByVotes(
      partner: String,
      validFrom: String = "2027-01-01",
      owner: UserId = author,
      access: Option[String] = None
  ): ZIO[Env, Nothing, (DocumentView, WorkflowCaseView)] =
    for
      s       <- setup.await
      created <- call(
        author,
        "POST",
        "/api/v1/documents",
        Some(
          s"""{"templateId":"${s.released.value}","templateVersion":1,"title":"Vertrag $partner",
             |"values":{${values(partner, validFrom, owner, access)}}}""".stripMargin
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

  private def status(user: UserId, doc: UUID, version: UUID) =
    call(user, "GET", s"/api/v1/documents/$doc/versions/$version/rendition/status")

  private def retry(user: UserId, doc: UUID, version: UUID) =
    call(user, "POST", s"/api/v1/documents/$doc/versions/$version/rendition/retry")

  private def failures(user: UserId) = call(user, "GET", "/api/v1/renditions/failures")

  private def withdrawal(user: UserId, doc: UUID, version: UUID) =
    call(
      user,
      "POST",
      s"/api/v1/documents/$doc/versions/$version/withdraw",
      Some("""{"reason":"Nobody else can finish this"}""")
    )

  /** The retention job of the archive module (DMS-93) for a version, as the job runner would run it. */
  private def retain(doc: UUID, version: UUID) =
    for
      s       <- setup.await
      objects <- ZIO.service[ObjectStore]
      audit   <- ZIO.service[AuditTrail]
      result  <- ObjectRetentionSync(s.app, objects, ObjectRetentionPolicy.default, audit)
        .lock(tenantId, dms.kernel.DocumentId(doc), version)
        .orDieWith(RuntimeException(_))
    yield result

  private def retentionJobs(version: UUID) =
    ownerQuery(
      sql"""select count(*) from dms.job where kind = ${ObjectRetentions.JobKind}
            and idempotency_key = ${"object-retention:" + version}""".query[Long].run().head
    )

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

  /** The dead-job events of a document as the outbox relay hands them out. */
  private def deadEvents(documentId: UUID): ZIO[Any, DbError, List[OutboxEvent]] =
    events(documentId, "%-dead")

  private def events(documentId: UUID, types: String): ZIO[Any, DbError, List[OutboxEvent]] =
    ownerQuery(
      sql"""select id, event_type, payload::text from dms.outbox_event
            where aggregate_id = $documentId and event_type like $types order by id"""
        .query[(Long, String, String)]
        .run()
        .toList
        .map((id, kind, payload) =>
          OutboxEvent(id, tenantId, "document", documentId, kind, Jsonb(payload), None, 1)
        )
    )

  /** A notification delivery of the application with a test double for directory and mail. Every call is a
    * new instance, like a restarted process.
    */
  private def notifications(mails: Ref[List[Mail]]): ZIO[Env, Nothing, NotificationJobs] =
    for
      s         <- setup.await
      documents <- ZIO.service[DocumentService]
      workflow  <- ZIO.service[ApprovalWorkflow]
      authz     <- ZIO.service[Authorizer]
      audit     <- ZIO.service[AuditTrail]
      directory = new RecipientDirectory:
        def people(tenant: TenantId, users: Set[UserId]) =
          ZIO.succeed(users.map(u => u -> Person(Some(u.value), Some(s"${u.value}@example.org"))).toMap)
      mail = new MailSender:
        def send(m: Mail) = mails.update(_ :+ m)
    yield NotificationJobs(
      s.app,
      PlatformNotificationEvents(
        documents,
        RoleAssignments.Postgres,
        dms.archive.core.LegalHoldService(audit),
        Some(workflow),
        Some(Groups.Postgres)
      ),
      PolicyNotificationDocuments(documents, workflow, authz, RoleAssignments.Postgres, Groups.Postgres),
      directory,
      mail,
      DeliverySettings("https://dms.example.org", emailEnabled = true)
    )

  /** Hands the document's dead-job events to the subscriber and runs the delivery jobs. */
  private def deliverDead(documentId: UUID, jobs: NotificationJobs) = deliver(documentId, "%-dead", jobs)

  private def deliver(documentId: UUID, types: String, jobs: NotificationJobs) =
    for
      s     <- setup.await
      found <- events(documentId, types)
      _     <- ZIO.foreachDiscard(found)(jobs.deliver).orDieWith(RuntimeException(_))
      _     <- JobRunner(s.app, jobs.handlers, WorkerConfig("notice-worker")).runOnce
    yield ()

  /** Runs `body` while `grants` hold; they would otherwise count as records managers in the other tests. */
  private def withRoles[A](grants: (UserId, RoleAssignment)*)(body: ZIO[Env, DbError, A]) =
    for
      s   <- setup.await
      ids <- s.app.transact(tenantId)(
        ZIO.foreach(grants)((user, role) => RoleAssignments.Postgres.grant(user, role, admin))
      )
      result <- body.ensuring(
        s.app
          .transact(tenantId)(ZIO.foreach(ids)(RoleAssignments.Postgres.revoke(_, Instant.now())))
          .orDieWith(e => RuntimeException(e.toString))
      )
    yield result

  private def failedAt(versionId: UUID) =
    ownerQuery(
      sql"""select finished_at from dms.job where idempotency_key = ${"rendition:" + versionId}"""
        .query[java.time.OffsetDateTime]
        .run()
        .head
        .toInstant
    )

  /** The follow-ups of a process with the default reminder days. */
  private val followUps =
    (setup.await <*> ZIO.service[AuditTrail]).map((s, audit) =>
      FailureFollowUps(s.app, audit, FailureReminderConfig())
    )

  /** The in-app notices of a document: recipient, subject, body and link, oldest first. */
  private def notices(documentId: UUID) =
    ownerQuery(
      sql"""select user_id, subject, body, link from dms.notification
            where document_id = $documentId order by created_at, user_id"""
        .query[(String, String, String, Option[String])]
        .run()
        .toList
    )

  private def publishJob(versionId: UUID) =
    ownerQuery(
      sql"""select status, attempts, last_error from dms.job
            where kind = ${PublicationJobs.PublishKind} and payload ->> 'versionId' = ${versionId.toString}"""
        .query[(String, Int, Option[String])]
        .run()
        .headOption
    )

  private def makePublishDue(versionId: UUID, maxAttempts: Int) =
    ownerQuery(
      sql"""update dms.job set run_at = now(), max_attempts = $maxAttempts
            where kind = ${PublicationJobs.PublishKind} and payload ->> 'versionId' = ${versionId.toString}""".update
        .run()
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
        finished        <- renditionJob(version)
        again           <- jobs().flatMap(runJobs)
        actions         <- auditActions(doc.id)
        done2           <- status(author, doc.id, version).map(as[RenditionStatusView])
        queuedRetention <- retentionJobs(version)
        retention       <- retain(doc.id, version)
        objects         <- ZIO.service[ObjectStore]
        attachedNow     <- document(doc.id).map(_.current.rendition.get)
        head            <- objects.head(attachedNow.objectKey, attachedNow.objectVersion)
        events          <- ownerQuery(
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
          repeated == Right(None),
          // DMS-93: provenance of the rendition, and its Object Lock retention after the approval
          done2.state == "Done",
          done2.lastError.isEmpty,
          !done2.canRetry,
          done2.renderer.exists(_.contains("WeasyPrint")),
          done2.workerImage.contains(RenderingWorker.ImageReference),
          done2.workerBuild.exists(_.matches("sha256:[0-9a-f]{64}")),
          queuedRetention == 1L,
          retention.exists(r => r.locked.size == 1 && r.skipped.isEmpty),
          head.retention.exists(r =>
            r.mode == RetentionMode.Governance && r.until.isAfter(Instant.now().plusSeconds(9L * 365 * 86400))
          )
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
        pending    <- status(author, doc.id, version).map(as[RenditionStatusView])
        first      <- jobs(renderer = Some(unavailable)).flatMap(runJobs)
        afterFirst <- renditionJob(version)
        deadEarly  <- deadEvents(doc.id)
        retrying   <- status(author, doc.id, version).map(as[RenditionStatusView])
        byAdmin    <- status(admin, doc.id, version)
        listed     <- failures(records).map(as[List[RenditionFailureView]])
        stillOpen  <- document(doc.id)
        caseOpen   <- cases(doc.id).map(_.head)
        _          <- makeDue(version, maxAttempts = 2)
        _          <- jobs(validator = notArchivable).flatMap(runJobs)
        afterLast  <- renditionJob(version)
        failed     <- status(author, doc.id, version).map(as[RenditionStatusView])
        deadLate   <- deadEvents(doc.id)
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
        deadEarly.isEmpty,
        deadLate.map(_.eventType) == List("document.rendition-dead"),
        deadLate.head.payload.value.contains("\"reason\": \"not_archivable\""),
        !deadLate.head.payload.value.contains("font not embedded"),
        parsed.size == 2,
        parsed.forall(o => fields(o, "recipients").contains(s"""["${author.value}"]""")),
        parsed.forall(o => fields(o, "versionId").contains(s"\"$version\"")),
        parsed.map(o => fields(o, "final")) == List(Some("false"), Some("true")),
        parsed.last.get("reason").exists(_.toString.contains("font not embedded")),
        actions == List("document.rendition-failed", "document.rendition-failed"),
        download.status == Status.NotFound,
        pending.state == "Pending",
        pending.attempts == 0,
        retrying.state == "Retrying",
        retrying.attempts == 1,
        retrying.nextAttemptAt.isDefined,
        retrying.lastError.exists(e =>
          e.code.contains("unavailable") && e.message.contains("connection refused")
        ),
        // the author is named owner: the role does not matter (ADR 0064)
        retrying.canRetry,
        // the tenant administrator cannot read a version in approval (no business role)
        byAdmin.status == Status.Forbidden,
        listed.exists(f =>
          f.versionId == version && f.state == "Retrying" && f.canRetry &&
            f.title.contains("Vertrag Initech")
        ),
        failed.state == "Failed",
        failed.attempts == 2,
        failed.failedAt.isDefined,
        failed.nextAttemptAt.isEmpty,
        failed.lastError.exists(e =>
          e.code.contains("not_archivable") && e.message.contains("font not embedded")
        )
      )
    },
    test("a failed rendition is retried by a records manager, audited, and then completes the approval") {
      for
        pair <- approvedByVotes("Umbrella")
        (doc, waiting) = pair
        version        = waiting.versionId
        _          <- makeDue(version, maxAttempts = 1)
        _          <- jobs(renderer = Some(unavailable)).flatMap(runJobs)
        dead       <- renditionJob(version)
        listed     <- failures(admin).map(as[List[RenditionFailureView]])
        ofReader   <- failures(reader).map(as[List[RenditionFailureView]])
        byReader   <- retry(reader, doc.id, version)
        byReviewer <- retry(reviewer, doc.id, version)
        byRecords  <- retry(records, doc.id, version)
        view = as[RenditionStatusView](byRecords)
        requeued <- renditionJob(version)
        actions  <- auditActions(doc.id)
        details  <- ownerQuery(
          sql"""select actor_id, details ->> 'previousState' from dms.audit_event
                where object_id = ${doc.id} and action = ${RenditionStatusApi.RetryRequested}"""
            .query[(String, String)]
            .run()
            .toList
        )
        processed  <- jobs().flatMap(runJobs)
        approved   <- document(doc.id)
        again      <- retry(records, doc.id, version)
        unknown    <- retry(records, doc.id, UUID.randomUUID())
        afterwards <- failures(records).map(as[List[RenditionFailureView]])
      yield assertTrue(
        dead.exists((status, attempts, _) => status == "dead" && attempts == 1),
        listed.exists(f => f.versionId == version && f.state == "Failed" && f.canRetry),
        ofReader.isEmpty,
        byReader.status == Status.Forbidden,
        byReviewer.status == Status.Forbidden,
        byRecords.status == Status.Ok,
        view.state == "Pending",
        view.attempts == 0,
        requeued.exists((status, attempts, _) => status == "queued" && attempts == 0),
        actions.contains(RenditionStatusApi.RetryRequested),
        details == List((records.value, "Failed")),
        processed == 1,
        approved.current.status == "Approved",
        again.status == Status.Conflict,
        again.text.contains("rendition_not_failed"),
        unknown.status == Status.NotFound,
        !afterwards.exists(_.versionId == version)
      )
    },
    test("a dead rendition job tells owner and records managers once, single retries tell nobody (DMS-142)") {
      for
        pair <- approvedByVotes("Stark")
        (doc, waiting) = pair
        version        = waiting.versionId
        mails      <- Ref.make(List.empty[Mail])
        _          <- makeDue(version, maxAttempts = 2)
        _          <- jobs(renderer = Some(unavailable)).flatMap(runJobs)
        _          <- notifications(mails).flatMap(deliverDead(doc.id, _))
        afterRetry <- notices(doc.id)
        _          <- makeDue(version, maxAttempts = 2)
        _          <- jobs(renderer = Some(unavailable)).flatMap(runJobs)
        // the relay hands the event out again and the process restarts in between: nothing is added
        _     <- notifications(mails).flatMap(deliverDead(doc.id, _))
        _     <- notifications(mails).flatMap(deliverDead(doc.id, _))
        first <- notices(doc.id)
        sent  <- mails.get
        // what the link promises: the list on the legal holds page has the failure, with title and retry
        listed <- failures(records).map(as[List[RenditionFailureView]])
        // a retry that fails again is a new failure, told once more
        _      <- retry(records, doc.id, version)
        _      <- makeDue(version, maxAttempts = 1)
        _      <- jobs(renderer = Some(unavailable)).flatMap(runJobs)
        _      <- notifications(mails).flatMap(deliverDead(doc.id, _))
        second <- notices(doc.id)
      yield
        val byRecords = first.find(_._1 == records.value)
        val byOwner   = first.find(_._1 == author.value)
        assertTrue(
          afterRetry.isEmpty,
          first.map(_._1).toSet == Set(author.value, records.value),
          first.size == 2,
          sent.map(_.to).toSet == Set(s"${author.value}@example.org", s"${records.value}@example.org"),
          sent.size == 2,
          sent.forall(m => m.subject == "[DMS] Archiv-PDF fehlgeschlagen: Vertrag Stark (1.0-draft.1)"),
          sent.exists(_.text.contains(s"https://dms.example.org/documents/${doc.id}?version=$version")),
          sent.exists(_.text.contains("https://dms.example.org/legal-holds")),
          // records managers cannot read a version in approval, so the link is the list, with the title
          byRecords.exists(_._2.contains("Vertrag Stark (1.0-draft.1)")),
          byRecords.exists(_._3.contains("nicht erreichbar")),
          byRecords.exists(_._4.contains("/legal-holds")),
          listed.exists(f => f.versionId == version && f.canRetry && f.title.contains("Vertrag Stark")),
          // the named owner can read and retry, whichever role they hold (ADR 0064)
          byOwner.exists(_._3.contains("auf der Dokumentseite erneut versuchen")),
          byOwner.exists(_._4.contains(s"/documents/${doc.id}?version=$version")),
          second.size == 4
        )
    },
    test("a document over the worker's limits dies on its first attempt and says why (DMS-171, ADR 0081)") {
      val tooLong: PdfRenderer =
        (_: PdfJob) => ZIO.fail(RenderingError.TooLong("about 900 pages, the limit is 300"))
      for
        pair <- approvedByVotes("Wayne")
        (doc, waiting) = pair
        version        = waiting.versionId
        mails  <- Ref.make(List.empty[Mail])
        _      <- makeDue(version, maxAttempts = 5)
        _      <- jobs(renderer = Some(tooLong)).flatMap(runJobs)
        dead   <- renditionJob(version)
        failed <- status(author, doc.id, version).map(as[RenditionStatusView])
        events <- outbox(RenditionEvents.Failed, doc.id)
        _      <- notifications(mails).flatMap(deliverDead(doc.id, _))
        told   <- notices(doc.id)
        // the database and the job agree on what ends a job
        finals <- ZIO.foreach(RenderingError.NoRetry.toList :+ "unavailable")(code =>
          ownerQuery(sql"select dms.job_failure_is_final(${s"[$code] x"})".query[Boolean].run().head)
        )
        // a raised limit is what a retry is still good for
        _     <- retry(records, doc.id, version)
        again <- renditionJob(version)
      yield assertTrue(
        dead.exists(j => j._1 == "dead" && j._2 == 1),
        failed.state == "Failed",
        failed.lastError.flatMap(_.code).contains("too_long"),
        failed.canRetry,
        events.size == 1,
        events.head.contains("\"final\":true"),
        told.map(_._1).toSet == Set(author.value, records.value),
        told.forall(_._3.contains("zu lang")),
        finals == List(true, true, false),
        again.exists(_._1 == "queued")
      )
    },
    test("a last approver who changed the version cannot finish: told, no retry, withdrawn (DMS-163)") {
      withRoles(author -> RoleAssignment(Role.Owner)) {
        for
          pair <- approvedByVotes("Soylent")
          (doc, waiting) = pair
          version        = waiting.versionId
          mails <- Ref.make(List.empty[Mail])
          // A case in `Rendering` at deployment: V53 finds that the last approver changed the version.
          _ <- ownerQuery(
            sql"""insert into dms.document_version_editor (tenant_id, version_id, user_id)
                  values (${tenantId.value}, $version, ${approver.value})""".update.run()
          )
          tooEarly <- withdrawal(author, doc.id, version)
          _        <- makeDue(version, maxAttempts = 1)
          _        <- jobs().flatMap(runJobs)
          dead     <- renditionJob(version)
          failed   <- status(author, doc.id, version).map(as[RenditionStatusView])
          listed   <- failures(records).map(as[List[RenditionFailureView]])
          retried  <- retry(records, doc.id, version)
          stuck    <- document(doc.id)
          _        <- notifications(mails).flatMap(deliverDead(doc.id, _))
          told     <- notices(doc.id)
          // Neither a reader nor records management withdraws: they are told who can.
          refused   <- withdrawal(records, doc.id, version)
          withdrawn <- withdrawal(author, doc.id, version)
          closed    <- cases(doc.id).map(_.head)
          after     <- document(doc.id)
          relisted  <- failures(records).map(as[List[RenditionFailureView]])
          again     <- retry(records, doc.id, version)
          next      <- call(
            author,
            "POST",
            s"/api/v1/documents/${doc.id}/versions",
            Some("""{"changeKind":"Major","reason":"A new approver takes it"}""")
          )
          actions <- ownerQuery(
            sql"""select action from dms.audit_event where object_id = ${doc.id}
                  and action in ('document.withdrawn-before-valid', 'workflow.withdrawn') order by seq"""
              .query[String]
              .run()
              .toList
          )
          _ <- notifications(mails).flatMap(deliver(doc.id, "document.approved-version-withdrawn", _))
          afterWithdrawal <- notices(doc.id)
        yield
          val byRecords = told.find(_._1 == records.value)
          val byOwner   = told.find(_._1 == author.value)
          assertTrue(
            tooEarly.status == Status.Conflict,
            tooEarly.text.contains("version_not_approved"),
            dead.exists((s, _, error) =>
              s == "dead" && error.exists(_.startsWith("[segregation_of_duties]"))
            ),
            failed.state == "Failed",
            failed.lastError.exists(_.code.contains("segregation_of_duties")),
            !failed.canRetry,
            listed.exists(f => f.versionId == version && !f.canRetry),
            retried.status == Status.Conflict,
            retried.text.contains("rendition_not_failed"),
            stuck.current.status == "InApproval",
            stuck.withdrawable.exists(w => w.versionId == version && w.renditionFailed),
            told.map(_._1).toSet == Set(author.value, records.value),
            byRecords.exists(_._3.contains("Vier-Augen-Prinzip")),
            byRecords.exists(_._3.contains("Ein erneuter Versuch hilft nicht")),
            byRecords.exists(_._3.contains("Inhaber der Eigentümerrolle")),
            byOwner.exists(_._3.contains("Sie können die Version auf der Dokumentseite zurückziehen")),
            refused.status == Status.Forbidden,
            withdrawn.status == Status.Ok,
            closed.status == "Withdrawn",
            closed.approval.isEmpty,
            closed.votes.nonEmpty,
            after.current.status == "Withdrawn",
            after.current.withdrawal.exists(_.reason == "Nobody else can finish this"),
            after.withdrawable.isEmpty,
            !relisted.exists(_.versionId == version),
            again.status == Status.Conflict,
            next.status == Status.Created,
            actions == List("document.withdrawn-before-valid", "workflow.withdrawn"),
            afterWithdrawal.exists((user, _, body, _) =>
              user == approver.value && body.contains("weil ihr Archiv-PDF nicht erzeugt werden konnte")
            )
          )
      }
    },
    test("any dead rendition can be withdrawn; a retry that gets there first closes that way (DMS-163)") {
      withRoles(author -> RoleAssignment(Role.Owner)) {
        for
          pair <- approvedByVotes("Tyrell")
          (doc, waiting) = pair
          version        = waiting.versionId
          _          <- makeDue(version, maxAttempts = 1)
          _          <- jobs(renderer = Some(unavailable)).flatMap(runJobs)
          failed     <- status(author, doc.id, version).map(as[RenditionStatusView])
          stuck      <- document(doc.id)
          retried    <- retry(records, doc.id, version)
          afterRetry <- document(doc.id)
          lost       <- withdrawal(author, doc.id, version)
          _          <- makeDue(version, maxAttempts = 1)
          _          <- jobs(renderer = Some(unavailable)).flatMap(runJobs)
          withdrawn  <- withdrawal(author, doc.id, version)
          lateRetry  <- retry(records, doc.id, version)
        yield assertTrue(
          failed.canRetry,
          stuck.withdrawable.exists(_.renditionFailed),
          retried.status == Status.Ok,
          afterRetry.withdrawable.isEmpty,
          lost.status == Status.Conflict,
          lost.text.contains("version_not_approved"),
          withdrawn.status == Status.Ok,
          lateRetry.status == Status.Conflict
        )
      }
    },
    test("a worker that crashed on the last attempt is noticed, and older failures are announced by V40") {
      for
        pair <- approvedByVotes("Oscorp")
        (doc, waiting) = pair
        version        = waiting.versionId
        s <- setup.await
        _ <- ownerQuery(
          sql"""update dms.job set status = 'running', attempts = 2, max_attempts = 2, locked_by = ${"gone"},
                  locked_until = now() - interval '1 minute', run_at = now()
                where idempotency_key = ${"rendition:" + version}""".update.run()
        )
        _      <- JobRunner(s.app, Map("test.nothing" -> (_ => ZIO.unit)), WorkerConfig("other")).runOnce
        job    <- renditionJob(version)
        events <- deadEvents(doc.id)
        _      <- ownerQuery(
          sql"""delete from dms.outbox_event where aggregate_id = ${doc.id} and event_type like '%-dead'""".update
            .run()
        )
        script = scala.io.Source
          .fromResource("db/migration/V40__dead_job_events.sql")
          .mkString
          .split("-- Failures from before this migration")
          .last
          .dropWhile(_ != '\n')
        _ <- ownerQuery(summon[com.augustnagro.magnum.DbCon].connection.createStatement().execute(script))
        counted <- deadEvents(doc.id)
      yield assertTrue(
        job.exists(_._1 == "dead"),
        events.map(_.eventType) == List("document.rendition-dead"),
        events.head.payload.value.contains("\"reason\": \"timeout\""),
        counted.map(_.eventType) == List("document.rendition-dead")
      )
    },
    test("without records manager the tenant administrators are told; an owner with the role may retry") {
      val roles = new RoleAssignments:
        private val real          = RoleAssignments.Postgres
        def forUser(user: UserId) =
          ZIO.succeed(Set(RoleAssignment(if user == admin then Role.TenantAdmin else Role.Owner)))
        def forUsers(users: Set[UserId]) =
          ZIO.foreach(users.toList)(u => forUser(u).map(u -> _)).map(_.toMap)
        def grant(user: UserId, assignment: RoleAssignment, by: UserId) = real.grant(user, assignment, by)
        def revoke(id: UUID, at: Instant)                               = real.revoke(id, at)
        def activeAt(at: Instant)                                       = real.activeAt(at)
        def holders(role: Role)                                         =
          if role == Role.RecordsManager then ZIO.succeed(Nil) else real.holders(role)
      for
        pair <- approvedByVotes("Globex")
        (doc, waiting) = pair
        version        = waiting.versionId
        s         <- setup.await
        documents <- ZIO.service[DocumentService]
        audit     <- ZIO.service[AuditTrail]
        _         <- makeDue(version, maxAttempts = 1)
        _         <- jobs(renderer = Some(unavailable)).flatMap(runJobs)
        event     <- deadEvents(doc.id).map(_.head)
        told      <- s.app.transact(tenantId)(
          PlatformNotificationEvents(documents, roles, dms.archive.core.LegalHoldService(audit))
            .describe(event)
        )
      yield assertTrue(
        told.map(n => (n.recipients, n.params.get("audience"), n.params.get("canRetry"))) == List(
          (List(admin), Some("admin"), None),
          (List(author), Some("owner"), Some("true"))
        ),
        told.forall(_.params.get("reason").contains("unavailable"))
      )
    },
    test("a dead publication job is told and can be retried; a version no longer waiting tells nobody") {
      val failing: Map[String, dms.integration.JobHandler] =
        Map(PublicationJobs.PublishKind -> (_ => ZIO.fail("[conflict] the scope is taken")))
      for
        pair <- approvedByVotes("Hooli", validFrom = "2026-01-01")
        (doc, waiting) = pair
        version        = waiting.versionId
        s        <- setup.await
        service  <- ZIO.service[DocumentService]
        mails    <- Ref.make(List.empty[Mail])
        _        <- jobs().flatMap(runJobs)
        approved <- document(doc.id)
        publishing = PublicationJobs(s.app, service, PublicationScheduleConfig())
        _        <- publishing.enqueue(tenantId).orDieWith(e => RuntimeException(e.toString))
        _        <- makePublishDue(version, maxAttempts = 2)
        _        <- JobRunner(s.app, failing, WorkerConfig("publisher")).runOnce
        retrying <- call(author, "GET", s"/api/v1/documents/${doc.id}/versions/$version/publication/status")
          .map(as[PublicationStatusView])
        deadEarly <- deadEvents(doc.id)
        _         <- makePublishDue(version, maxAttempts = 2)
        _         <- JobRunner(s.app, failing, WorkerConfig("publisher")).runOnce
        dead      <- publishJob(version)
        // the periodic enqueueing does not bring a dead job back
        _      <- publishing.enqueue(tenantId).orDieWith(e => RuntimeException(e.toString))
        still  <- publishJob(version)
        _      <- notifications(mails).flatMap(deliverDead(doc.id, _))
        told   <- notices(doc.id).map(_.filter(_._2.contains("Veröffentlichung")))
        sent   <- mails.get
        failed <- call(author, "GET", s"/api/v1/documents/${doc.id}/versions/$version/publication/status")
          .map(as[PublicationStatusView])
        listed <- call(records, "GET", "/api/v1/publications/failures").map(as[List[PublicationFailureView]])
        ofReader <- call(reader, "GET", "/api/v1/publications/failures").map(as[List[PublicationFailureView]])
        byReader <- call(reader, "POST", s"/api/v1/documents/${doc.id}/versions/$version/publication/retry")
        byRecords <- call(records, "POST", s"/api/v1/documents/${doc.id}/versions/$version/publication/retry")
        requeued  <- publishJob(version)
        actions   <- ownerQuery(
          sql"""select action from dms.audit_event where object_id = ${doc.id}
                and action = ${PublicationStatusApi.RetryRequested}""".query[String].run().toList
        )
        _         <- JobRunner(s.app, publishing.handlers, WorkerConfig("publisher")).runOnce
        published <- document(doc.id)
        again     <- call(records, "POST", s"/api/v1/documents/${doc.id}/versions/$version/publication/retry")
        after <- call(records, "GET", "/api/v1/publications/failures").map(as[List[PublicationFailureView]])
        late  <- deadEvents(doc.id).map(_.map(_.copy(id = 999999L)))
        _     <- notifications(mails).flatMap(n =>
          ZIO.foreachDiscard(late)(n.deliver).orDieWith(RuntimeException(_))
        )
        unchanged <- ownerQuery(
          sql"""select count(*) from dms.notification_delivery where event_id = 999999"""
            .query[Long]
            .run()
            .head
        )
      yield assertTrue(
        approved.current.status == "Approved",
        retrying.state == "Retrying",
        retrying.lastError.exists(_.code.contains("conflict")),
        deadEarly.isEmpty,
        dead.exists((status, attempts, _) => status == "dead" && attempts == 2),
        still == dead,
        told.map(_._1).toSet == Set(author.value, records.value, approver.value),
        told.size == 3,
        told.forall(_._2.contains("Vertrag Hooli (1.0)")),
        told.forall(_._3.contains("Der Stand des Dokuments lässt den Schritt nicht zu.")),
        told.find(_._1 == records.value).exists(_._4.contains("/legal-holds")),
        told.find(_._1 == author.value).exists(_._4.contains(s"/documents/${doc.id}?version=$version")),
        sent.count(_.subject.contains("Veröffentlichung fehlgeschlagen")) == 3,
        failed.state == "Failed",
        failed.failedAt.isDefined,
        // the author is named owner: the role does not matter (ADR 0064)
        failed.canRetry,
        listed.exists(f =>
          f.versionId == version && f.state == "Failed" && f.canRetry && f.title.contains("Vertrag Hooli")
        ),
        ofReader.isEmpty,
        byReader.status == Status.Forbidden,
        byRecords.status == Status.Ok,
        as[PublicationStatusView](byRecords).state == "Pending",
        requeued.exists((status, attempts, _) => status == "queued" && attempts == 0),
        actions == List(PublicationStatusApi.RetryRequested),
        published.current.status == "Published",
        again.status == Status.Conflict,
        again.text.contains("publication_not_failed"),
        !after.exists(_.versionId == version),
        // a late event of a version that was published in the meantime
        unchanged == 0L
      )
    },
    test("records managers of a unit are told of what they cover, and reach it (DMS-146)") {
      val inLegal = UserId("records-legal")
      val inSales = UserId("records-sales")
      withRoles(
        inLegal -> RoleAssignment(Role.RecordsManager, organizationalUnit = Some("Legal")),
        inSales -> RoleAssignment(Role.RecordsManager, organizationalUnit = Some("Sales"))
      )(
        for
          pair <- approvedByVotes("Soylent")
          (doc, waiting) = pair
          version        = waiting.versionId
          mails   <- Ref.make(List.empty[Mail])
          _       <- makeDue(version, maxAttempts = 1)
          _       <- jobs(renderer = Some(unavailable)).flatMap(runJobs)
          _       <- notifications(mails).flatMap(deliverDead(doc.id, _))
          told    <- notices(doc.id)
          ofLegal <- failures(inLegal).map(as[List[RenditionFailureView]])
          ofSales <- failures(inSales).map(as[List[RenditionFailureView]])
          bySales <- retry(inSales, doc.id, version)
          byLegal <- retry(inLegal, doc.id, version)
        yield assertTrue(
          told.map(_._1).toSet == Set(author.value, records.value, inLegal.value),
          told.find(_._1 == inLegal.value).exists(_._2.contains("Vertrag Soylent (1.0-draft.1)")),
          told.find(_._1 == inLegal.value).exists(_._3.contains("„Fehlgeschlagene Verarbeitungen“")),
          told.find(_._1 == inLegal.value).exists(_._4.contains("/failures")),
          told.find(_._1 == records.value).exists(_._4.contains("/legal-holds")),
          ofLegal.exists(f => f.versionId == version && f.canRetry && f.title.contains("Vertrag Soylent")),
          !ofSales.exists(_.versionId == version),
          bySales.status == Status.Forbidden,
          byLegal.status == Status.Ok
        )
      )
    },
    test(
      "the person named owner can retry without the owner role, also if nobody else is cleared (DMS-146)"
    ) {
      val named = UserId("named-1")
      for
        s <- setup.await
        _ <- s.app.transact(tenantId)(
          Groups.Postgres.create("legal-team", "Legal team", admin) *>
            ZIO.foreachDiscard(List(reviewer, approver))(Groups.Postgres.addMember("legal-team", _, admin))
        )
        result <- withRoles(named -> RoleAssignment(Role.Reader))(
          for
            pair <- approvedByVotes("Wonka", owner = named, access = Some("legal-team"))
            (doc, waiting) = pair
            version        = waiting.versionId
            mails     <- Ref.make(List.empty[Mail])
            _         <- makeDue(version, maxAttempts = 1)
            _         <- jobs(renderer = Some(unavailable)).flatMap(runJobs)
            _         <- notifications(mails).flatMap(deliverDead(doc.id, _))
            told      <- notices(doc.id)
            ofNamed   <- failures(named).map(as[List[RenditionFailureView]])
            ofRecords <- failures(records).map(as[List[RenditionFailureView]])
            onPage    <- status(named, doc.id, version)
            byRecords <- retry(records, doc.id, version)
            byNamed   <- retry(named, doc.id, version)
            requeued  <- renditionJob(version)
            actors    <- ownerQuery(
              sql"""select actor_id from dms.audit_event
                    where object_id = ${doc.id} and action = ${RenditionStatusApi.RetryRequested}"""
                .query[String]
                .run()
                .toList
            )
            _        <- jobs().flatMap(runJobs)
            approved <- document(doc.id)
          yield assertTrue(
            told.map(_._1).toSet == Set(named.value, records.value),
            // not able to open the version: the list is the place of the retry
            told.find(_._1 == named.value).exists(_._2.contains("Vertrag Wonka (1.0-draft.1)")),
            told.find(_._1 == named.value).exists(_._3.contains("„Fehlgeschlagene Verarbeitungen“")),
            told.find(_._1 == named.value).exists(_._4.contains("/failures")),
            // not cleared: no title, and no retry promised
            told.find(_._1 == records.value).exists(_._2 == "Archiv-PDF fehlgeschlagen: Dokument"),
            told.find(_._1 == records.value).exists(_._3.contains("nicht freigegeben")),
            ofNamed.exists(f => f.versionId == version && f.canRetry && f.title.contains("Vertrag Wonka")),
            ofRecords.exists(f => f.versionId == version && !f.canRetry && f.title.isEmpty),
            onPage.status == Status.Forbidden,
            byRecords.status == Status.Forbidden,
            byNamed.status == Status.Ok,
            requeued.exists((status, attempts, _) => status == "queued" && attempts == 0),
            actors == List(named.value),
            approved.current.status == "Approved"
          )
        )
      yield result
    },
    test("the deputies of the owner are told; they decide nothing by that (DMS-146)") {
      val named  = UserId("owner-away")
      val deputy = UserId("owner-deputy")
      withRoles(
        named  -> RoleAssignment(Role.Owner),
        deputy -> RoleAssignment(Role.Owner, organizationalUnit = Some("Sales"))
      )(
        for
          // The deputyship API is not part of these routes; WorkflowApiSpec covers it.
          _ <- ownerQuery(
            sql"""insert into dms.deputyship
                    (id, tenant_id, principal_id, deputy_id, valid_from, valid_until, roles, created_by)
                  values (${UUID.randomUUID()}, ${tenantId.value}, ${named.value}, ${deputy.value},
                          now() - interval '1 minute', now() + interval '1 hour', ${Jsonb("""["Owner"]""")},
                          ${named.value})""".update.run()
          )
          pair <- approvedByVotes("Stark", owner = named)
          (doc, waiting) = pair
          version        = waiting.versionId
          mails    <- Ref.make(List.empty[Mail])
          _        <- makeDue(version, maxAttempts = 1)
          _        <- jobs(renderer = Some(unavailable)).flatMap(runJobs)
          _        <- notifications(mails).flatMap(deliverDead(doc.id, _))
          told     <- notices(doc.id)
          byDeputy <- retry(deputy, doc.id, version)
        yield assertTrue(
          told.map(_._1).toSet == Set(named.value, deputy.value, records.value),
          told.find(_._1 == deputy.value).exists(_._3.contains(s"als Vertretung von ${named.value}")),
          told
            .find(_._1 == deputy.value)
            .exists(_._3.contains("Erneut versuchen kann es das Records Management")),
          // the version is outside the deputy's own role: no title, and no link to a page closed to them
          told.find(_._1 == deputy.value).exists(_._2 == "Archiv-PDF fehlgeschlagen: Dokument"),
          told.find(_._1 == deputy.value).exists(_._4.isEmpty),
          !told.find(_._1 == named.value).exists(_._3.contains("Vertretung")),
          byDeputy.status == Status.Forbidden
        )
      )
    },
    test("a notice left unread is reminded of on the reminder days, until someone retries (DMS-146)") {
      val days = (n: Long) => java.time.Duration.ofDays(n)
      for
        pair <- approvedByVotes("Tyrell")
        (doc, waiting) = pair
        version        = waiting.versionId
        mails   <- Ref.make(List.empty[Mail])
        follow  <- followUps
        n       <- notifications(mails)
        _       <- makeDue(version, maxAttempts = 1)
        _       <- jobs(renderer = Some(unavailable)).flatMap(runJobs)
        _       <- deliverDead(doc.id, n)
        died    <- failedAt(version)
        tooSoon <- follow.check(tenantId, died.plus(days(2)))
        _       <- ownerQuery(
          sql"""update dms.notification set read_at = now()
                where user_id = ${records.value} and document_id = ${doc.id}""".update.run()
        )
        first    <- follow.check(tenantId, died.plus(days(3)))
        repeated <- follow.check(tenantId, died.plus(days(4)))
        _        <- deliver(doc.id, FailureFollowUps.ReminderEvent, n)
        once     <- notices(doc.id).map(_.filter(_._2.startsWith("Erinnerung: ")))
        second   <- follow.check(tenantId, died.plus(days(30)))
        done     <- follow.check(tenantId, died.plus(days(31)))
        _        <- deliver(doc.id, FailureFollowUps.ReminderEvent, n)
        twice    <- notices(doc.id).map(_.filter(_._2.startsWith("Erinnerung: ")))
        sent     <- mails.get
        _        <- retry(records, doc.id, version)
        retried  <- follow.check(tenantId, died.plus(days(60)))
        rounds   <- ownerQuery(
          sql"""select count(*) from dms.job_failure_reminder r join dms.job j on j.id = r.job_id
                where j.idempotency_key = ${"rendition:" + version}""".query[Long].run().head
        )
      yield assertTrue(
        tooSoon == 0,
        // other tests left failures open in this tenant; `rounds` counts those of this job
        first >= 1,
        repeated == 0,
        // who read a notice of this failure is left alone
        once.map(_._1) == List(author.value),
        once.exists(_._2 == "Erinnerung: Archiv-PDF fehlgeschlagen: Vertrag Tyrell (1.0-draft.1)"),
        once.exists(_._3.startsWith("Seit ")),
        once.exists(_._4.contains(s"/documents/${doc.id}?version=$version")),
        // the days in between are not caught up
        second >= 1,
        done == 0,
        twice.map(_._1) == List(author.value, author.value),
        sent.count(_.subject.contains("Erinnerung: Archiv-PDF fehlgeschlagen")) == 2,
        retried == 0,
        rounds == 2L
      )
    },
    test("a dead publication is audited once, whatever the relay repeats; its owner retries it (DMS-146)") {
      val failing: Map[String, dms.integration.JobHandler] =
        Map(PublicationJobs.PublishKind -> (_ => ZIO.fail("[conflict] the scope of Secret Corp is taken")))
      for
        pair <- approvedByVotes("Massive", validFrom = "2026-01-01")
        (doc, waiting) = pair
        version        = waiting.versionId
        s       <- setup.await
        service <- ZIO.service[DocumentService]
        audit   <- ZIO.service[AuditTrail]
        follow  <- followUps
        _       <- jobs().flatMap(runJobs)
        publishing = PublicationJobs(s.app, service, PublicationScheduleConfig())
        _       <- publishing.enqueue(tenantId).orDieWith(e => RuntimeException(e.toString))
        _       <- makePublishDue(version, maxAttempts = 1)
        _       <- JobRunner(s.app, failing, WorkerConfig("publisher")).runOnce
        found   <- deadEvents(doc.id)
        _       <- ZIO.foreachDiscard(found ++ found)(follow.deliver).orDieWith(RuntimeException(_))
        entries <- ownerQuery(
          sql"""select actor_id, actor_kind, channel, details ->> 'versionId', details ->> 'reason', details::text
                from dms.audit_event where object_id = ${doc.id} and action = ${FailureFollowUps.PublicationFailed}"""
            .query[(String, String, String, String, String, String)]
            .run()
            .toList
        )
        chain    <- audit.verify(tenantId)
        retried  <- call(author, "POST", s"/api/v1/documents/${doc.id}/versions/$version/publication/retry")
        requeued <- publishJob(version)
      yield assertTrue(
        found.map(_.eventType) == List(dms.documents.Publication.DeadEvent),
        entries.size == 1,
        entries.exists((actor, kind, channel, entryVersion, reason, _) =>
          actor == FailureFollowUps.PublicationActor && kind == "technical" && channel == "automation" &&
            entryVersion == version.toString && reason == "conflict"
        ),
        // the error text can quote document content
        !entries.exists(_._6.contains("Secret Corp")),
        chain.ok,
        retried.status == Status.Ok,
        requeued.exists((status, attempts, _) => status == "queued" && attempts == 0)
      )
    },
    test("the depth of the rendering queue can be read as a metric, across tenants") {
      val routes                             = ZioHttpInterpreter().toHttp(MetricsApi.metrics)
      def metric(text: String, name: String) =
        text.linesIterator.collectFirst {
          case line if line.startsWith(name + " ") => line.stripPrefix(name + " ").toDouble
        }
      def read =
        ZIO
          .scoped(routes.runZIO(Request.get(URL.decode("/metrics").toOption.get)))
          .flatMap(r => r.body.asString.map((r.status, r.headers.get("Content-Type"), _)).orDie)
      val other = TenantId.random()
      def key   = Some(UUID.randomUUID().toString)
      for
        s <- setup.await
        _ <- ownerQuery(
          sql"insert into dms.tenant (id, name) values (${other.value}, ${"Other tenant"})".update.run()
        )
        before <- read
        now    <- Clock.instant
        _      <- s.app.transact(tenantId)(
          JobQueue.enqueue(
            RenditionRequest.JobKind,
            idempotencyKey = key,
            runAt = Some(now.minusSeconds(120))
          )
        )
        _ <- s.app.transact(other)(JobQueue.enqueue(RenditionRequest.JobKind, idempotencyKey = key))
        _ <- s.app.transact(other)(
          JobQueue.enqueue(RenditionRequest.JobKind, idempotencyKey = key, runAt = Some(now.plusSeconds(600)))
        )
        // another kind does not count
        _     <- s.app.transact(other)(JobQueue.enqueue("search.other-kind", idempotencyKey = key))
        after <- read
        // the jobs have no valid payload: they must not reach a later test
        _ <- ownerQuery(
          sql"""update dms.job set status = 'done' where status = 'queued' and payload = '{}'
                and kind in (${RenditionRequest.JobKind}, 'search.other-kind')""".update.run()
        )
        due     = """dms_rendering_queue_depth{state="due"}"""
        delayed = """dms_rendering_queue_depth{state="delayed"}"""
        oldest  = "dms_rendering_queue_oldest_seconds"
      yield assertTrue(
        before._1 == Status.Ok,
        after._2.exists(_.startsWith("text/plain; version=0.0.4")),
        metric(after._3, due).zip(metric(before._3, due)).exists((a, b) => a - b == 2),
        metric(after._3, delayed).zip(metric(before._3, delayed)).exists((a, b) => a - b == 1),
        metric(after._3, """dms_rendering_queue_depth{state="running"}""").isDefined,
        metric(after._3, oldest).exists(_ >= 120),
        !after._3.contains(tenantId.value.toString)
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
