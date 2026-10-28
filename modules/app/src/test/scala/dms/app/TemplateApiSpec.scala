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
import dms.audit.*
import dms.authz.{Authorizer, Groups, Role, RoleAssignment, RoleAssignments}
import dms.db.{Db, DbConfig, DbError, Migrations}
import dms.documents.core.{ClassDraft, DocumentClassService, FieldDraft}
import dms.documents.metadata.*
import dms.identity.*
import dms.kernel.{TenantId, UserId}
import dms.templates.core.TemplateService
import org.testcontainers.utility.DockerImageName
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.*
import zio.http.{Body, Header, Headers, MediaType, Request, Status, URL}
import zio.json.*
import zio.test.*

import java.time.Instant
import java.util.{Date, UUID}
import javax.sql.DataSource

/** HTTP tests of template administration, test mode and lifecycle against a Testcontainers database. */
object TemplateApiSpec extends ZIOSpecDefault:

  private val issuer    = "http://localhost:8085"
  private val audience  = "project-1"
  private val orgId     = "org-1"
  private val tenantId  = TenantId.random()
  private val admin     = UserId("admin-1")
  private val author    = UserId("author-1")
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

  private val audit: AuditTrail = new AuditTrail:
    def record(event: AuditEvent): IO[DbError, AuditRecordId]     = ZIO.succeed(AuditRecordId(0L))
    def verify(tenant: TenantId): IO[DbError, VerificationReport] =
      ZIO.succeed(VerificationReport(tenant, 0, None, None, 0, None))

  /** The id of the contract class created during setup. */
  private val contractClass = Unsafe.unsafe(implicit u => Promise.unsafe.make[Nothing, UUID](FiberId.None))

  private val database: ZLayer[Any, Throwable, Db] = ZLayer.scoped(
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
      owner <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, poolSize = 1))
      _     <- Db(owner)
        .transactSystem(
          Db.sql(sql"insert into dms.tenant (id, name) values (${tenantId.value}, ${"Tenant"})".update.run())
        )
        .orDieWith(e => RuntimeException(e.toString))
      app <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", poolSize = 2))
      db      = Db(app)
      classes = DocumentClassService(audit)
      _ <- db
        .transact(tenantId)(
          RoleAssignments.Postgres.grant(admin, RoleAssignment(Role.TenantAdmin), admin) *>
            RoleAssignments.Postgres.grant(author, RoleAssignment(Role.Author), admin) *>
            RoleAssignments.Postgres.grant(designer, RoleAssignment(Role.TemplateDesigner), admin) *>
            RoleAssignments.Postgres.grant(designer2, RoleAssignment(Role.TemplateDesigner), admin) *>
            classes.createField(
              "termType",
              FieldDraft(
                "Term",
                None,
                FieldType.Selection(
                  List(SelectOption("fixed", "Fixed term"), SelectOption("open", "Open-ended"))
                )
              ),
              admin
            ) *>
            classes
              .createClass(
                "contract",
                None,
                ClassDraft("Contract", None, List(FieldBinding(FieldKey.unsafe("termType"), 1))),
                admin
              )
              .flatMap(cls => contractClass.succeed(cls.classId.value))
        )
        .orDieWith(e => RuntimeException(e.toString))
    yield db
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

  private val env: ZLayer[Any, Throwable, TemplateApi.Env] =
    ZLayer.make[TemplateApi.Env](
      database,
      authenticator,
      RoleAssignments.postgres,
      Groups.postgres,
      Authorizer.layer,
      ZLayer.succeed(audit),
      DocumentClassService.layer,
      DocumentClassProfiles.layer,
      TemplateService.layer
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
      .build()
    val jwt = SignedJWT(
      JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID).`type`(JOSEObjectType.JWT).build(),
      claims
    )
    jwt.sign(RSASSASigner(signingKey))
    jwt.serialize()

  private val endpoints =
    TemplateApi.endpoints ++ TemplateLifecycleApi.endpoints ++ TemplatePreviewApi.endpoints
  private val routes = ZioHttpInterpreter().toHttp(endpoints)

  private def call(user: UserId, method: String, path: String, body: Option[String] = None) =
    val url     = URL.decode(path).toOption.get
    val headers =
      Headers(Header.Authorization.Bearer(token(user)), Header.ContentType(MediaType.application.json))
    val request = method match
      case "POST" => Request.post(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case "PUT"  => Request.put(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case _      => Request.get(url).addHeaders(headers)
    ZIO.scoped(routes.runZIO(request)).flatMap(r => r.body.asString.map(b => (r.status, b)).orDie)

  private def responseCode(body: String): Option[String] = body.fromJson[ApiError].toOption.map(_.code)

  private def template(classId: UUID, condition: String = "termType == 'fixed'") =
    s"""{"name":"Service contract","documentClassId":"$classId","documentClassVersion":1,
       |"content":{"sections":[{"id":"main","title":"Contract","locked":false,"blocks":[
       |{"type":"Paragraph","content":[{"type":"Text","value":"Title: "},{"type":"Placeholder","path":"title"}]},
       |{"type":"Conditional","condition":"$condition",
       | "body":[{"type":"Paragraph","content":[{"type":"Text","value":"Fixed term"}]}],
       | "otherwise":[{"type":"Paragraph","content":[{"type":"Placeholder","path":"termType"}]}]}]}]}}""".stripMargin

  private def created(body: String): TemplateVersionView = body.fromJson[TemplateVersionView].toOption.get

  def spec = suite("TemplateApi")(
    test("API documentation can be generated, including the recursive block schemas") {
      val docs = sttp.tapir.docs.openapi
        .OpenAPIDocsInterpreter()
        .toOpenAPI(endpoints.map(_.endpoint), "DMS Platform", "test")
      assertTrue(
        docs.paths.pathItems.contains("/api/v1/admin/templates/{id}/versions/{version}/test"),
        docs.paths.pathItems.contains("/api/v1/templates"),
        docs.paths.pathItems.contains("/api/v1/templates/{id}/versions/{version}/preview"),
        docs.paths.pathItems.contains("/api/v1/templates/{id}/versions/{version}/fields"),
        docs.components.exists(c => c.schemas.contains("Block") && c.schemas.contains("ResolvedBlock"))
      )
    },
    test("only template designers administer templates") {
      for
        classId  <- contractClass.await
        byAuthor <- call(author, "POST", "/api/v1/admin/templates", Some(template(classId)))
        byAdmin  <- call(admin, "GET", "/api/v1/admin/templates")
      yield assertTrue(
        byAuthor._1 == Status.Forbidden,
        responseCode(byAuthor._2).contains("forbidden"),
        byAdmin._1 == Status.Forbidden
      )
    },
    test("invalid expressions are reported with their position on save and by the check endpoint") {
      for
        classId <- contractClass.await
        save    <- call(
          designer,
          "POST",
          "/api/v1/admin/templates",
          Some(template(classId, "termType = 'fixed'"))
        )
        check <- call(
          designer,
          "POST",
          "/api/v1/admin/templates/check",
          Some(
            s"""{"documentClassId":"$classId","documentClassVersion":1,
               |"content":${template(classId, "termTyp == 'fixed'")
                .fromJson[zio.json.ast.Json.Obj]
                .toOption
                .get
                .get("content")
                .get
                .toJson}}""".stripMargin
          )
        )
        result = check._2.fromJson[CheckResult].toOption
      yield assertTrue(
        save._1 == Status.UnprocessableEntity,
        responseCode(save._2).contains("invalid_content"),
        save._2.contains("section main, /blocks/1 (condition, line 1, column"),
        check._1 == Status.Ok,
        result.exists(r =>
          !r.valid && r.issues.map(_.location) == List(
            LocationView(Some("main"), "/blocks/1", "condition", 1, 1)
          )
        )
      )
    },
    test("a designer creates a template and runs the test mode with generated and given values") {
      for
        classId <- contractClass.await
        create  <- call(designer, "POST", "/api/v1/admin/templates", Some(template(classId)))
        id = created(create._2).templateId
        generated <- call(
          designer,
          "POST",
          s"/api/v1/admin/templates/$id/versions/1/test",
          Some("""{"language":"de"}""")
        )
        withValues <- call(
          designer,
          "POST",
          s"/api/v1/admin/templates/$id/versions/1/test",
          Some(
            """{"values":{"title":{"type":"Text","value":"ACME"},"termType":{"type":"Selection","code":"open"}}}"""
          )
        )
        run      = generated._2.fromJson[TestRunView].toOption
        givenRun = withValues._2.fromJson[TestRunView].toOption
      yield assertTrue(
        create._1 == Status.Created,
        created(create._2).status == "Draft",
        generated._1 == Status.Ok,
        run.exists(_.allBranchesCovered),
        run.exists(_.cases.forall(_.error.isEmpty)),
        givenRun.exists(_.cases.head.text.contains("Contract\nTitle: ACME\nOpen-ended"))
      )
    },
    test("release needs a second template designer; only released versions are in the catalog") {
      for
        classId <- contractClass.await
        create  <- call(designer, "POST", "/api/v1/admin/templates", Some(template(classId)))
        id     = created(create._2).templateId
        status = (to: String) => Some(s"""{"status":"$to"}""")
        path   = s"/api/v1/admin/templates/$id/versions/1/status"
        testing     <- call(designer, "POST", path, status("Testing"))
        editTesting <- call(
          designer,
          "PUT",
          s"/api/v1/admin/templates/$id/versions/1",
          Some(template(classId))
        )
        before      <- call(author, "GET", "/api/v1/templates")
        selfRelease <- call(designer, "POST", path, status("Released"))
        byAuthor    <- call(author, "POST", path, status("Released"))
        released    <- call(designer2, "POST", path, status("Released"))
        catalog     <- call(author, "GET", "/api/v1/templates")
        adminView   <- call(admin, "GET", "/api/v1/templates")
        invalid     <- call(designer2, "POST", path, status("Draft"))
      yield assertTrue(
        testing._1 == Status.Ok,
        editTesting._1 == Status.Conflict,
        before._2.fromJson[List[CatalogEntry]].toOption.exists(!_.exists(_.templateId == id)),
        selfRelease._1 == Status.Forbidden,
        selfRelease._2.contains("segregation of duties"),
        byAuthor._1 == Status.Forbidden,
        released._1 == Status.Ok,
        created(released._2).releasedBy.contains(designer2.value),
        catalog._2
          .fromJson[List[CatalogEntry]]
          .toOption
          .exists(_.exists(e => e.templateId == id && e.version == 1)),
        adminView._1 == Status.Forbidden,
        invalid._1 == Status.Conflict
      )
    },
    test("authors get the form fields and a live preview of released versions only") {
      for
        classId <- contractClass.await
        create  <- call(designer, "POST", "/api/v1/admin/templates", Some(template(classId)))
        id      = created(create._2).templateId
        preview = s"/api/v1/templates/$id/versions/1/preview"
        values  =
          """{"values":{"title":{"type":"Text","value":"<b>ACME</b>"},"termType":{"type":"Selection","code":"nope"}},"language":"de"}"""
        draft <- call(author, "POST", preview, Some(values))
        _     <- call(
          designer,
          "POST",
          s"/api/v1/admin/templates/$id/versions/1/status",
          Some("""{"status":"Testing"}""")
        )
        _ <- call(
          designer2,
          "POST",
          s"/api/v1/admin/templates/$id/versions/1/status",
          Some("""{"status":"Released"}""")
        )
        fields   <- call(author, "GET", s"/api/v1/templates/$id/versions/1/fields")
        byAuthor <- call(author, "POST", preview, Some(values))
        cached   <- call(author, "POST", preview, Some("""{"values":{}}"""))
        byAdmin  <- call(admin, "POST", preview, Some(values))
        badLang  <- call(author, "POST", preview, Some("""{"values":{},"language":"fr"}"""))
        formFields = fields._2.fromJson[TemplateFieldsView].toOption
        result     = byAuthor._2.fromJson[PreviewView].toOption
        empty      = cached._2.fromJson[PreviewView].toOption
      yield assertTrue(
        draft._1 == Status.NotFound,
        fields._1 == Status.Ok,
        formFields.exists(
          _.fields.take(2).map(f => (f.key, f.usedInTemplate)) == List("title" -> true, "termType" -> true)
        ),
        formFields.exists(f => !f.fields.exists(_.key == "documentId")),
        byAuthor._1 == Status.Ok,
        result.exists(_.html.contains("Entwurf – nicht gültig")),
        result.exists(_.html.contains("&lt;b&gt;ACME&lt;/b&gt;")),
        result.exists(_.html.contains("data-field=\"termType\"")),
        result.exists(_.issues.map(i => (i.field, i.code)) == List(Some("termType") -> "unknown_option")),
        result.exists(_.missingRequired.exists(_.field == "owner")),
        empty.exists(_.missingRequired.headOption.contains(MissingValueView("title", "Title"))),
        empty.exists(_.html.contains("dms-missing-required")),
        byAdmin._1 == Status.Forbidden,
        badLang._1 == Status.UnprocessableEntity
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
