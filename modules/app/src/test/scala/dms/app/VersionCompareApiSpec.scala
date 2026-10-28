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
import dms.documents.metadata.{FieldBinding, FieldKey, FieldType, MetadataValue}
import dms.identity.*
import dms.integration.{JobRunner, WorkerConfig}
import dms.kernel.{TemplateId, TenantId, UserId}
import dms.templates.*
import dms.templates.core.ContentJson.given
import dms.templates.core.{TemplateDraft, TemplateService}
import dms.rendering.core.RenditionSource
import dms.workflow.core.{ApprovalWorkflow, FlowableEngine, WorkflowConfig}
import org.testcontainers.utility.DockerImageName
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.*
import zio.http.{Body, Header, Headers, MediaType, Request, Status, URL}
import zio.json.*
import zio.test.*

import java.time.{Instant, LocalDate}
import java.util.{Date, UUID}
import javax.sql.DataSource

/** Version comparison (US-05.1, DMS-54) end to end: HTTP endpoint, the real documents and workflow modules
  * and the publication job against a Testcontainers database.
  */
object VersionCompareApiSpec extends ZIOSpecDefault:

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
            _   <- classes.createField("audience", FieldDraft("Audience", None, FieldType.Text()), admin)
            cls <- classes.createClass(
              "policy",
              None,
              ClassDraft("Policy", None, List(FieldBinding(FieldKey.unsafe("audience"), 1))),
              admin
            )
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

  private type Env = DocumentApi.Env & WorkflowApi.Env & RenditionSource & AuditTrail

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
      DocumentService.configured(DocumentSettings(editorialChangeThreshold = 0.1)),
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

  private val routes =
    ZioHttpInterpreter().toHttp(DocumentApi.endpoints ++ VersionCompareApi.endpoints ++ WorkflowApi.endpoints)

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

  private def values(
      ownerId: UserId,
      validFrom: LocalDate,
      unit: String = "Compliance",
      audience: String = "All staff"
  ) =
    s"""{"owner":{"type":"PersonRef","userId":"${ownerId.value}"},
       |"audience":{"type":"Text","value":"$audience"},
       |"organizationalUnit":{"type":"Text","value":"$unit"},
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

  private def compare(doc: UUID, from: UUID, to: UUID, user: UserId) =
    call(user, "GET", s"/api/v1/documents/$doc/compare?from=$from&to=$to")

  def spec = suite("Version comparison")(
    test("API documentation contains the compare endpoint") {
      val docs = sttp.tapir.docs.openapi
        .OpenAPIDocsInterpreter()
        .toOpenAPI(VersionCompareApi.endpoints.map(_.endpoint), "DMS Platform", "test")
      assertTrue(docs.paths.pathItems.contains("/api/v1/documents/{id}/compare"))
    },
    test("the author compares the valid version with the new draft: text, fields and metadata separately") {
      val changed = content(words.updated(4, "rule5x") ++ List("appendix"))
      for
        doc   <- validDocument(owner)
        major <- newVersion(doc.id, """{"changeKind":"Major","reason":"Rule 5 tightened, appendix added"}""")
          .map(as[DocumentVersionView])
        _ <- call(
          author,
          "PUT",
          s"/api/v1/documents/${doc.id}/draft",
          Some(
            s"""{"values":${values(owner, past, unit = "Legal", audience = "Managers")},
               |"content":${changed.toJson}}""".stripMargin
          )
        )
        published <- versions(doc.id).map(_.head)
        result    <- compare(doc.id, published.id, major.id, author)
        view   = as[VersionComparisonView](result)
        blocks = view.text.sections.flatMap(_.blocks)
      yield assertTrue(
        result._1 == Status.Ok,
        view.from.version == "1.0",
        view.from.status == "Published",
        view.to.version == "2.0-draft.2",
        view.summaries.map(s => (s.version, s.changeKind, s.summary)) ==
          List(("2.0-draft.2", "Major", Some("Rule 5 tightened, appendix added"))),
        view.text.changed,
        view.text.insertedWords == 2,
        view.text.deletedWords == 1,
        blocks.map(_.status) == List("Changed"),
        blocks.head.spans.map(s => (s.kind, s.text)) == List(
          ("Equal", "rule1 rule2 rule3 rule4"),
          ("Deleted", "rule5"),
          ("Inserted", "rule5x"),
          ("Equal", (6 to 20).map(i => s"rule$i").mkString(" ")),
          ("Inserted", "appendix")
        ),
        view.fields.map(f => (f.key, f.label, f.before, f.after, f.originAfter)) == List(
          (
            "audience",
            "Audience",
            Some(MetadataValue.Text("All staff")),
            Some(MetadataValue.Text("Managers")),
            Some("Manual")
          )
        ),
        view.metadata.find(_.key == "organizationalUnit").map(m => (m.before, m.after)) ==
          Some((Some(MetadataValue.Text("Compliance")), Some(MetadataValue.Text("Legal")))),
        view.metadata.find(_.key == "status").map(m => (m.before, m.after)) == Some(
          (Some(MetadataValue.Selection("Published")), Some(MetadataValue.Selection("Draft")))
        )
      )
    },
    test("a reader may compare readable versions but not with a draft (403); unknown versions are 404") {
      for
        doc   <- validDocument(owner)
        major <- newVersion(doc.id, """{"changeKind":"Major","reason":"Rework"}""").map(
          as[DocumentVersionView]
        )
        _   <- approveDefault(doc.id)
        _   <- runPublicationJobs
        all <- versions(doc.id)
        (v1, v2) = (all.head, all.last)
        readable <- compare(doc.id, v1.id, v2.id, reader)
        view = as[VersionComparisonView](readable)
        third <- newVersion(doc.id, """{"changeKind":"Major","reason":"Secret plans"}""")
          .map(as[DocumentVersionView])
        withDraft <- compare(doc.id, v2.id, third.id, reader)
        backwards <- compare(doc.id, third.id, v1.id, reader)
        unknown   <- compare(doc.id, v1.id, UUID.randomUUID(), reader)
        // The author may read the draft and sees all summaries up to it.
        byAuthor <- compare(doc.id, v1.id, third.id, author).map(as[VersionComparisonView])
      yield assertTrue(
        major.id == v2.id,
        readable._1 == Status.Ok,
        (view.from.status, view.to.status) == ("Superseded", "Published"),
        !view.text.changed,
        view.summaries.map(_.summary) == List(Some("Rework")),
        withDraft._1 == Status.Forbidden,
        error(withDraft._2).exists(_.code == "forbidden"),
        backwards._1 == Status.Forbidden,
        unknown._1 == Status.NotFound,
        byAuthor.summaries.map(_.summary) == List(Some("Rework"), Some("Secret plans"))
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
