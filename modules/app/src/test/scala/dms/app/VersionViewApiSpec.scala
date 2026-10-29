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
import dms.authz.{Authorizer, Groups, Role, RoleAssignment, RoleAssignments}
import dms.db.{Db, DbConfig, DbError, Migrations}
import dms.documents.core.*
import dms.documents.metadata.*
import dms.identity.*
import dms.integration.{JobRunner, WorkerConfig}
import dms.kernel.{TemplateId, TenantId, UserId}
import dms.rendering.core.*
import dms.templates.*
import dms.templates.core.{ProfileSource, TemplateDraft, TemplateService}
import dms.workflow.core.{ApprovalWorkflow, FlowableEngine, WorkflowConfig}
import org.testcontainers.utility.DockerImageName
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.RichZServerEndpoint
import zio.*
import zio.http.{Body, Header, Headers, MediaType, Request, Status, URL}
import zio.json.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.{Date, HexFormat, UUID}
import javax.sql.DataSource

/** Reading and printing versions on demand (DMS-112, ADR 0039) end to end: the HTML of a published version
  * comes from its frozen package while its successor is edited; open versions carry the draft marking; the
  * draft PDF and the copy with change history are printed by the real rendering worker (built from
  * `workers/rendering`) and audited. The approval rendition itself is produced with stand-ins (covered by
  * `RenditionApiSpec`).
  */
object VersionViewApiSpec extends ZIOSpecDefault:

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

  private val names = Map(author -> "Ada Author", approver -> "Anna Approver", reader -> "Rita Reader")

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
          Block.Paragraph(List(Inline.Text("Gerichtsstand ist Hamburg.")))
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

  private type Env = DocumentApi.Env & WorkflowApi.Env & VersionViewApi.Env & RenditionSource &
    RenditionArchive

  private val env: ZLayer[Any, Throwable, Env] =
    ZLayer.make[Env](
      database,
      authenticator,
      directory,
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
      DocumentRenditions.layer,
      ZLayer(RenditionArchive.inMemory),
      ZLayer.succeed(DocumentConverter.unavailable),
      FileVersions.layer
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
        VersionViewApi.endpoints.map(_.widen[Env])
    )

  final case class Response(status: Status, headers: Headers, body: Chunk[Byte]):
    def text: String                         = String(body.toArray, StandardCharsets.UTF_8)
    def header(name: String): Option[String] = headers.get(name)

  private def call(user: UserId, method: String, path: String, body: Option[String] = None) =
    val url     = URL.decode(path).toOption.get
    val headers = Headers(
      Header.Authorization.Bearer(token(user)),
      Header.ContentType(MediaType.application.json)
    )
    val request = method match
      case "POST" => Request.post(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case "PUT"  => Request.put(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case _      => Request.get(url).addHeaders(headers)
    ZIO
      .scoped(routes.runZIO(request))
      .flatMap(r => r.body.asChunk.map(b => Response(r.status, r.headers, b)).orDie)

  private def as[A: JsonDecoder](response: Response): A =
    response.text
      .fromJson[A]
      .fold(e => throw AssertionError(s"$e: ${response.status} ${response.text}"), identity)

  private def values(partner: String) =
    s"""{"contractPartner":{"type":"Text","value":"$partner"},
       |"owner":{"type":"PersonRef","userId":"${author.value}"},
       |"organizationalUnit":{"type":"Text","value":"Legal"},
       |"validFrom":{"type":"Date","value":"2026-01-01"},
       |"confidentiality":{"type":"Selection","code":"Internal"},
       |"language":{"type":"Text","value":"de"},
       |"retentionRule":{"type":"Text","value":"contract-10y"},
       |"containsPersonalData":{"type":"YesNo","value":false}}""".stripMargin

  private def cases(doc: UUID) =
    call(author, "GET", s"/api/v1/documents/$doc/workflow").map(as[List[WorkflowCaseView]])

  private def vote(user: UserId, task: TaskView) =
    call(user, "POST", s"/api/v1/workflow/tasks/${task.id}/votes", Some(CastVote("Approve", None).toJson))

  /** Submits the open draft, approves it through the default workflow and renders it with stand-ins. */
  private def approve(doc: UUID): ZIO[Env, Any, Unit] =
    for
      s           <- setup.await
      _           <- call(author, "POST", s"/api/v1/documents/$doc/submit")
      review      <- cases(doc).map(_.last.tasks.find(_.status == "Open").get)
      afterReview <- vote(reviewer, review).map(as[WorkflowCaseView])
      _           <- vote(approver, afterReview.tasks.find(_.status == "Open").get)
      source      <- ZIO.service[RenditionSource]
      audit       <- ZIO.service[AuditTrail]
      archive     <- ZIO.service[RenditionArchive]
      jobs = RenditionJobs(
        s.app,
        source,
        job => ZIO.succeed(RenderedPdf(Chunk.fromArray(job.html.getBytes), Some(1), "stub")),
        (_, standard) => ZIO.succeed(ValidationReport(standard, compliant = true, "stub", Nil)),
        archive,
        audit
      )
      _ <- JobRunner(
        s.app,
        Map(dms.rendering.RenditionRequest.JobKind -> jobs.handle),
        WorkerConfig("render")
      ).runOnce
    yield ()

  /** Publishes the approved versions whose valid-from date has come. */
  private def publish: ZIO[Env, Any, Int] =
    for
      s       <- setup.await
      service <- ZIO.service[DocumentService]
      jobs = PublicationJobs(s.app, service, PublicationScheduleConfig())
      _   <- jobs.enqueue(tenantId)
      ran <- JobRunner(s.app, jobs.handlers, WorkerConfig("publication")).runOnce
    yield ran

  /** A document whose version 1.0 (partner `first`) is published and whose version 2.0 (partner `second`,
    * change summary "Neuer Vertragspartner") is an open draft.
    */
  private def publishedWithDraft(
      first: String,
      second: String
  ): ZIO[Env, Any, (DocumentView, DocumentVersionView, DocumentVersionView)] =
    for
      s       <- setup.await
      created <- call(
        author,
        "POST",
        "/api/v1/documents",
        Some(s"""{"templateId":"${s.released.value}","templateVersion":1,"title":"Vertrag $first",
                |"values":${values(first)}}""".stripMargin)
      ).map(as[DocumentView])
      _    <- approve(created.id)
      _    <- publish
      next <- call(
        author,
        "POST",
        s"/api/v1/documents/${created.id}/versions",
        Some("""{"changeKind":"Major","reason":"Neuer Vertragspartner"}""")
      ).map(as[DocumentVersionView])
      _ <- call(
        author,
        "PUT",
        s"/api/v1/documents/${created.id}/draft",
        Some(s"""{"values":${values(second)}}""")
      )
      versions <- call(author, "GET", s"/api/v1/documents/${created.id}/versions")
        .map(as[List[DocumentVersionView]])
    yield (created, versions.head, next)

  private def html(user: UserId, doc: UUID, version: UUID) =
    call(user, "GET", s"/api/v1/documents/$doc/versions/$version/html")

  private def pdf(user: UserId, doc: UUID, version: UUID, file: String, changelog: Boolean) =
    call(user, "GET", s"/api/v1/documents/$doc/versions/$version/$file?changelog=$changelog")

  private def texts(response: Response): List[String] =
    RenderingWorker.pageTexts(response.body).map(RenderingWorker.normalized)

  private def sha256(bytes: Chunk[Byte]) =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toArray))

  private def audited(doc: UUID, action: String) =
    setup.await.flatMap(s =>
      s.owner.transactSystem(
        Db.sql(
          sql"""select actor_id, object_version, details::text from dms.audit_event
                where object_id = $doc and action = $action order by seq"""
            .query[(String, Option[String], String)]
            .run()
            .toList
        )
      )
    )

  def spec = suite("Reading and printing versions")(
    test("a published version shows its frozen content while its successor is a marked draft") {
      for
        found <- publishedWithDraft("ACME GmbH", "Globex AG")
        (doc, first, second) = found
        publishedByReader <- html(reader, doc.id, first.id)
        publishedByAuthor <- html(author, doc.id, first.id).map(as[VersionHtmlView])
        draft             <- html(author, doc.id, second.id).map(as[VersionHtmlView])
        draftByReader     <- html(reader, doc.id, second.id)
        unknown           <- html(author, doc.id, UUID.randomUUID())
        published = as[VersionHtmlView](publishedByReader)
      yield assertTrue(
        first.status == "Published",
        second.status == "Draft",
        publishedByReader.status == Status.Ok,
        published.version == "1.0",
        published.status == "Published",
        published.draftMarking.isEmpty,
        published.language == "de",
        published.html.startsWith("<article class=\"dms-document\""),
        published.html.contains("ACME GmbH"),
        !published.html.contains("dms-draft"),
        !published.html.contains("Globex"),
        publishedByAuthor.html == published.html,
        draft.version == "2.0-draft.2",
        draft.draftMarking.contains("Entwurf – nicht gültig"),
        draft.html.contains("dms-draft"),
        draft.html.contains("data-watermark=\"Entwurf – nicht gültig\""),
        draft.html.contains("Globex AG"),
        !draft.html.contains("ACME"),
        draftByReader.status == Status.Forbidden,
        unknown.status == Status.NotFound
      )
    },
    test("an open version prints as marked draft PDF with the change history; the download is audited") {
      for
        found <- publishedWithDraft("Initech", "Umbrella AG")
        (doc, first, second) = found
        withHistory <- pdf(author, doc.id, second.id, "draft.pdf", changelog = true)
        plain       <- pdf(author, doc.id, second.id, "draft.pdf", changelog = false)
        ofApproved  <- pdf(author, doc.id, first.id, "draft.pdf", changelog = false)
        byReader    <- pdf(reader, doc.id, second.id, "draft.pdf", changelog = false)
        entries     <- audited(doc.id, VersionViewApi.DraftPdfAction)
        pages      = texts(withHistory)
        plainPages = texts(plain)
        history    = pages.last
      yield assertTrue(
        withHistory.status == Status.Ok,
        withHistory.header("Content-Type").exists(_.startsWith("application/pdf")),
        withHistory.header("Content-Disposition").exists(_.contains(s"${doc.id}-2.0-draft.2-draft.pdf")),
        withHistory.header("Cache-Control").contains("private, no-store"),
        pages.size == 3,
        pages.forall(_.contains("Entwurf – nicht gültig")),
        pages.forall(_.contains(s"Dokument-ID ${doc.id} · Version 2.0-draft.2")),
        pages.forall(_.contains("von Ada Author · nicht freigegeben, nicht archiviert")),
        pages.zipWithIndex.forall((t, i) => t.contains(s"Seite ${i + 1} von 3")),
        pages.head.contains("Zwischen Umbrella AG"),
        history.contains("Änderungshistorie"),
        history.contains("Versionen bis einschließlich Version 2.0-draft.2"),
        history.contains("Neuer Vertragspartner"),
        history.contains("Abgelöst") || history.contains("Veröffentlicht"),
        history.contains("Anna Approver"),
        history.contains("Erstellt"),
        history.contains("Gültig ab 01.01.2026"),
        plain.status == Status.Ok,
        plainPages.size == 2,
        !plainPages.exists(_.contains("Änderungshistorie")),
        ofApproved.status == Status.Conflict,
        byReader.status == Status.Forbidden,
        entries.size == 2,
        entries.forall((actor, version, _) => actor == author.value && version.contains("2.0-draft.2")),
        entries.head._3.contains(s""""sha256": "${sha256(withHistory.body)}"""") ||
          entries.head._3.contains(s""""sha256":"${sha256(withHistory.body)}""""),
        entries.head._3.contains("\"changelog\": true") || entries.head._3.contains("\"changelog\":true")
      )
    },
    test("an approved version prints as copy with the change history, its archived rendition untouched") {
      for
        found <- publishedWithDraft("Hooli", "Pied Piper")
        (doc, first, second) = found
        copy    <- pdf(reader, doc.id, first.id, "copy.pdf", changelog = true)
        ofDraft <- pdf(author, doc.id, second.id, "copy.pdf", changelog = true)
        entries <- audited(doc.id, VersionViewApi.CopyPdfAction)
        pages = texts(copy)
        sha   = first.rendition.map(_.sha256).getOrElse("")
      yield assertTrue(
        copy.status == Status.Ok,
        pages.size == 3,
        pages.forall(_.contains("Kopie – maßgeblich ist die archivierte Fassung")),
        pages.forall(_.contains(s"Archivierte Fassung (PDF/A) SHA-256 $sha")),
        pages.forall(_.contains("Gültig ab 01.01.2026")),
        pages.forall(_.contains("UTC von Anna Approver")),
        pages.forall(!_.contains("Entwurf – nicht gültig")),
        pages.head.contains("Zwischen Hooli"),
        pages.last.contains("Änderungshistorie"),
        // Only versions up to the printed one: the newer draft of the author is not listed.
        !pages.last.contains("Neuer Vertragspartner"),
        !pages.last.contains("2.0-draft"),
        ofDraft.status == Status.Conflict,
        entries.map(_._1) == List(reader.value),
        first.rendition.isDefined
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
