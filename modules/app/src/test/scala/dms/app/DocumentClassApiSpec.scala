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
import dms.documents.core.DocumentClassService
import dms.identity.*
import dms.kernel.{TenantId, UserId}
import org.testcontainers.utility.DockerImageName
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.*
import zio.http.{Body, Header, Headers, MediaType, Request, Status, URL}
import zio.json.*
import zio.test.*

import java.time.Instant
import java.util.Date
import javax.sql.DataSource

/** HTTP tests of the document class administration against a Testcontainers database. */
object DocumentClassApiSpec extends ZIOSpecDefault:

  private val issuer    = "http://localhost:8085"
  private val audience  = "project-1"
  private val orgId     = "org-1"
  private val tenantId  = TenantId.random()
  private val admin     = UserId("admin-1")
  private val author    = UserId("author-1")
  private val reviewer  = UserId("reviewer-1")
  private val reader    = UserId("reader-1")
  private val elsewhere = UserId("author-of-another-class")

  private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("key-1").generate()

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

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
      db = Db(app)
      _ <- db
        .transact(tenantId)(
          RoleAssignments.Postgres.grant(admin, RoleAssignment(Role.TenantAdmin), admin) *>
            RoleAssignments.Postgres.grant(author, RoleAssignment(Role.Author), admin) *>
            RoleAssignments.Postgres.grant(reviewer, RoleAssignment(Role.Reviewer), admin) *>
            RoleAssignments.Postgres.grant(reader, RoleAssignment(Role.Reader), admin) *>
            RoleAssignments.Postgres.grant(
              elsewhere,
              RoleAssignment(Role.Author, documentClass = Some("other-class")),
              admin
            )
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

  private val audit: ULayer[AuditTrail] = ZLayer.succeed(new AuditTrail:
    def record(event: AuditEvent): IO[DbError, AuditRecordId]     = ZIO.succeed(AuditRecordId(0L))
    def verify(tenant: TenantId): IO[DbError, VerificationReport] =
      ZIO.succeed(VerificationReport(tenant, 0, None, None, 0, None)))

  private val env: ZLayer[Any, Throwable, DocumentClassApi.Env] =
    ZLayer.make[DocumentClassApi.Env](
      database,
      authenticator,
      RoleAssignments.postgres,
      Groups.postgres,
      Authorizer.layer,
      audit,
      DocumentClassService.layer
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

  private val routes = ZioHttpInterpreter().toHttp(DocumentClassApi.endpoints)

  private def call(user: UserId, method: String, path: String, body: Option[String] = None) =
    val url     = URL.decode(path).toOption.get
    val headers =
      Headers(Header.Authorization.Bearer(token(user)), Header.ContentType(MediaType.application.json))
    val request = method match
      case "POST" => Request.post(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case _      => Request.get(url).addHeaders(headers)
    ZIO.scoped(routes.runZIO(request)).flatMap(r => r.body.asString.map(b => (r.status, b)).orDie)

  private def responseCode(body: String): Option[String] =
    body.fromJson[ApiError].toOption.map(_.code)

  private val contractClass =
    """{"key":"contract","label":"Contract","fields":[]}"""

  def spec = suite("DocumentClassApi")(
    test("API documentation can be generated, including the recursive metadata value schema") {
      val docs = sttp.tapir.docs.openapi
        .OpenAPIDocsInterpreter()
        .toOpenAPI(DocumentClassApi.endpoints.map(_.endpoint), "DMS Platform", "test")
      assertTrue(
        docs.paths.pathItems.contains("/api/v1/document-classes/{id}/validate"),
        docs.components.exists(_.schemas.contains("MetadataValue"))
      )
    },
    test("a user without the tenant administrator role gets 403") {
      for
        create <- call(author, "POST", "/api/v1/admin/document-classes", Some(contractClass))
        list   <- call(author, "GET", "/api/v1/admin/field-definitions")
      yield assertTrue(
        create._1 == Status.Forbidden,
        responseCode(create._2).contains("forbidden"),
        list._1 == Status.Forbidden
      )
    },
    test("a tenant administrator creates field definitions and classes and reads the effective profile") {
      for
        field <- call(
          admin,
          "POST",
          "/api/v1/admin/field-definitions",
          Some(
            """{"key":"contractPartner","label":"Contract partner","fieldType":{"type":"Text","maxLength":100}}"""
          )
        )
        cls <- call(
          admin,
          "POST",
          "/api/v1/admin/document-classes",
          Some(
            """{"key":"contract","label":"Contract",
              |"fields":[{"key":"contractPartner","version":1,"requiredIn":["InReview","InApproval"]}]}""".stripMargin
          )
        )
        id = cls._2.fromJson[DocumentClassView].toOption.map(_.id)
        profile <- call(admin, "GET", s"/api/v1/admin/document-classes/${id.get}/profile")
        invalid <- call(
          admin,
          "POST",
          "/api/v1/admin/field-definitions",
          Some("""{"key":"title","label":"Title","fieldType":{"type":"YesNo"}}""")
        )
      yield assertTrue(
        field._1 == Status.Created,
        cls._1 == Status.Created,
        profile._1 == Status.Ok,
        profile._2
          .fromJson[ProfileView]
          .toOption
          .exists(p =>
            p.fields.exists(f => f.key == "contractPartner" && f.definedBy.contains("contract")) &&
              p.fields.exists(_.key == "renditionChecksum")
          ),
        invalid._1 == Status.UnprocessableEntity
      )
    },
    test("only those who fill in metadata of the class may run its checks (DMS-160)") {
      for
        cls <- call(
          admin,
          "POST",
          "/api/v1/admin/document-classes",
          Some("""{"key":"checks","label":"Checks","fields":[]}""")
        )
        id   = cls._2.fromJson[DocumentClassView].toOption.map(_.id).get
        path = s"/api/v1/document-classes/$id/validate"
        body = Some("""{"values":{},"targetStatus":"Draft"}""")
        byAuthor    <- call(author, "POST", path, body)
        byReviewer  <- call(reviewer, "POST", path, body)
        byReader    <- call(reader, "POST", path, body)
        byAdmin     <- call(admin, "POST", path, body)
        byElsewhere <- call(elsewhere, "POST", path, body)
      yield assertTrue(
        byAuthor._1 == Status.Ok,
        byReviewer._1 == Status.Ok,
        byReader._1 == Status.Forbidden,
        byAdmin._1 == Status.Forbidden,
        byElsewhere._1 == Status.Forbidden
      )
    },
    test("a format RE2 cannot run is refused with a message that says why (DMS-160)") {
      for created <- call(
          admin,
          "POST",
          "/api/v1/admin/field-definitions",
          Some("""{"key":"lookahead","label":"L","fieldType":{"type":"Text","pattern":"(?=a)a"}}""")
        )
      yield assertTrue(created._1 == Status.UnprocessableEntity, created._2.contains("not supported"))
    },
    test("validation names the missing required fields for a target status") {
      for
        _ <- call(
          admin,
          "POST",
          "/api/v1/admin/field-definitions",
          Some("""{"key":"noticePeriod","label":"Notice period","fieldType":{"type":"Text"}}""")
        )
        cls <- call(
          admin,
          "POST",
          "/api/v1/admin/document-classes",
          Some(
            """{"key":"nda","label":"NDA",
              |"fields":[{"key":"noticePeriod","version":1,"requiredIn":["InReview"]}]}""".stripMargin
          )
        )
        id   = cls._2.fromJson[DocumentClassView].toOption.map(_.id).get
        body = """{"values":{"title":{"type":"Text","value":"NDA ACME"}},"targetStatus":"%s"}"""
        draft  <- call(author, "POST", s"/api/v1/document-classes/$id/validate", Some(body.format("Draft")))
        review <- call(
          author,
          "POST",
          s"/api/v1/document-classes/$id/validate",
          Some(body.format("InReview"))
        )
        result = review._2.fromJson[ValidationResult].toOption
      yield assertTrue(
        draft._1 == Status.Ok,
        draft._2.fromJson[ValidationResult].toOption.exists(_.complete),
        review._1 == Status.Ok,
        result.exists(!_.complete),
        result.exists(
          _.issues.exists(i =>
            i.field == "noticePeriod" && i.label == "Notice period" && i.code == "missing_required"
          )
        )
      )
    },
    test("a subclass inherits review settings by default, shows them in force and can have its own") {
      def view(response: (Status, String)) = response._2.fromJson[DocumentClassView].toOption.map(_.review)
      def post(path: String, body: String) =
        call(admin, "POST", s"/api/v1/admin/document-classes$path", Some(body))
      for
        root <- post(
          "",
          """{"key":"guideline","label":"Guideline","fields":[],"review":{"intervalMonths":12,"leadDays":30}}"""
        )
        id = root._2.fromJson[DocumentClassView].toOption.map(_.id).get
        child <- post("", s"""{"key":"guideline-it","label":"IT","fields":[],"parentId":"$id"}""")
        childId = child._2.fromJson[DocumentClassView].toOption.map(_.id).get
        own <- post(
          s"/$childId/versions",
          """{"label":"IT","fields":[],"review":{"intervalMonths":6,"leadDays":10}}"""
        )
        back <- post(s"/$childId/versions", """{"label":"IT","fields":[],"review":{"inherited":true}}""")
        both <- post(
          s"/$childId/versions",
          """{"label":"IT","fields":[],"review":{"intervalMonths":6,"inherited":true}}"""
        )
        rootInherits <- post(
          "",
          """{"key":"loose","label":"Loose","fields":[],"review":{"inherited":true}}"""
        )
        versions <- call(admin, "GET", s"/api/v1/admin/document-classes/$childId")
        list = versions._2.fromJson[List[DocumentClassView]].toOption.toList.flatten
      yield assertTrue(
        view(child).contains(ReviewSettingsView(None, 60, true, Some(12), 30)),
        view(own).contains(ReviewSettingsView(Some(6), 10, false, Some(6), 10)),
        view(back).contains(ReviewSettingsView(None, 60, true, Some(12), 30)),
        both._1 == Status.UnprocessableEntity,
        rootInherits._1 == Status.UnprocessableEntity,
        // Old versions show what they stored; what is in force is the same for all of them.
        list.map(_.review.intervalMonths) == List(None, Some(6), None),
        list.map(_.review.effectiveIntervalMonths).distinct == List(Some(12))
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
