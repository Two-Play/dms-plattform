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
import dms.audit.core.{AuditTrailLive, TsaTrust}
import dms.authz.{Authorizer, Role, RoleAssignment, RoleAssignments}
import dms.db.{Db, DbConfig, DbError, Migrations}
import dms.documents.core.*
import dms.documents.metadata.*
import dms.identity.*
import dms.integration.partners.*
import dms.kernel.{TemplateId, TenantId, UserId}
import dms.templates.*
import dms.templates.core.{TemplateDraft, TemplateService}
import dms.workflow.core.{ApprovalWorkflow, FlowableEngine, WorkflowConfig}
import org.testcontainers.utility.DockerImageName
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.RichZServerEndpoint
import zio.*
import zio.http.{Body, Header, Headers, MediaType, Request, Status, URL}
import zio.json.*
import zio.test.*

import java.time.Instant
import java.util.Date
import javax.sql.DataSource

/** HTTP tests of the partner master (US-03.1): search, taking a partner over into a draft as system values,
  * rejecting manual changes of them, and saving the draft while the partner master is down. Against a
  * Testcontainers database; the partner master is the in-memory demo directory behind a switch.
  */
object PartnerApiSpec extends ZIOSpecDefault:

  private val issuer   = "http://localhost:8085"
  private val audience = "project-1"
  private val orgId    = "org-1"
  private val tenantId = TenantId.random()
  private val admin    = UserId("admin-1")
  private val author   = UserId("author-1")
  private val nobody   = UserId("nobody-1")

  private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("key-1").generate()

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  private val setup = Unsafe.unsafe(implicit u => Promise.unsafe.make[Nothing, TemplateId](FiberId.None))

  /** Whether the partner master answers. */
  private val reachable = Unsafe.unsafe(implicit u => Ref.unsafe.make(true))

  private val directory: PartnerDirectory = new PartnerDirectory:
    private val down = PartnerDirectoryError.Unavailable("the partner master did not answer within 3000 ms")
    def search(tenant: TenantId, query: String, limit: Int) =
      reachable.get.flatMap(up =>
        if up then InMemoryPartnerDirectory.demo.search(tenant, query, limit) else ZIO.fail(down)
      )
    def get(tenant: TenantId, id: String) =
      reachable.get.flatMap(up =>
        if up then InMemoryPartnerDirectory.demo.get(tenant, id) else ZIO.fail(down)
      )

  private val content = TemplateContent(
    List(
      Section(
        SectionId("parties"),
        Some("Parties"),
        locked = true,
        List(
          Block.Paragraph(
            List(
              Inline.Text("Between "),
              Inline.Placeholder("contractPartner"),
              Inline.Text(", "),
              Inline.Placeholder("partnerAddress")
            )
          )
        )
      )
    ),
    references = List(
      ReferenceField(
        "contractPartner",
        "Contract partner",
        ReferenceSource.PartnerMaster,
        List(
          ReferenceMapping("id", "partnerId"),
          ReferenceMapping("name", "contractPartner"),
          ReferenceMapping("address", "partnerAddress")
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
      appSource <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", poolSize = 2))
      app       = Db(appSource)
      audit     = AuditTrailLive(app, TsaTrust(Nil))
      classes   = DocumentClassService(audit)
      templates = TemplateService(audit, DocumentClassProfiles(classes))
      released <- app
        .transact(tenantId)(
          for
            _ <- RoleAssignments.Postgres.grant(admin, RoleAssignment(Role.TenantAdmin), admin)
            _ <- RoleAssignments.Postgres.grant(author, RoleAssignment(Role.Author), admin)
            _ <- ZIO.foreachDiscard(
              List(
                "contractPartner" -> "Contract partner",
                "partnerId"       -> "Partner number",
                "partnerAddress"  -> "Address"
              )
            )((key, label) => classes.createField(key, FieldDraft(label, None, FieldType.Text()), admin))
            cls <- classes.createClass(
              "contract",
              None,
              ClassDraft(
                "Contract",
                None,
                List("contractPartner", "partnerId", "partnerAddress").map(k =>
                  FieldBinding(FieldKey.unsafe(k), 1)
                )
              ),
              admin
            )
            created <- templates
              .create(TemplateDraft("Service contract", None, cls.classId.value, 1, content), admin)
            _ <- templates.changeStatus(created.templateId, 1, TemplateStatus.Testing, admin)
            _ <- templates.changeStatus(created.templateId, 1, TemplateStatus.Released, UserId("designer-2"))
          yield created.templateId
        )
        .mapError(e => RuntimeException(e.toString))
        .orDie
      _ <- setup.succeed(released)
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

  private type Env = Authenticator & Db & Authorizer & DocumentService & ApprovalWorkflow & PartnerDirectory &
    TemplateService

  private val env: ZLayer[Any, Throwable, Env] =
    ZLayer.make[Env](
      database,
      authenticator,
      RoleAssignments.postgres,
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
      ZLayer.succeed(directory)
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

  private val routes = ZioHttpInterpreter().toHttp(
    PartnerApi.endpoints.map(_.widen[Env]) ++ DocumentApi.endpoints.map(_.widen[Env]) ++
      TemplatePreviewApi.endpoints.map(_.widen[Env])
  )

  private def call(user: UserId, method: String, path: String, body: Option[String] = None) =
    val url     = URL.decode(path).toOption.get
    val headers =
      Headers(Header.Authorization.Bearer(token(user)), Header.ContentType(MediaType.application.json))
    val request = method match
      case "POST"   => Request.post(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case "PUT"    => Request.put(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case "DELETE" => Request.delete(url).addHeaders(headers)
      case _        => Request.get(url).addHeaders(headers)
    ZIO.scoped(routes.runZIO(request)).flatMap(r => r.body.asString.map(b => (r.status, b)).orDie)

  private def error(body: String): Option[ApiError] = body.fromJson[ApiError].toOption

  private def version(body: String): DocumentVersionView =
    body.fromJson[DocumentVersionView].fold(e => throw AssertionError(s"$e: $body"), identity)

  private def createDocument(values: String = "") =
    for
      template <- setup.await
      created  <- call(
        author,
        "POST",
        "/api/v1/documents",
        Some(
          s"""{"templateId":"${template.value}","templateVersion":1,"title":"Contract","values":{$values}}"""
        )
      )
    yield created

  private def pick(document: java.util.UUID, partner: String, key: String = "contractPartner") =
    call(author, "PUT", s"/api/v1/documents/$document/references/$key", Some(s"""{"entryId":"$partner"}"""))

  def spec = suite("PartnerApi")(
    test("API documentation can be generated") {
      val docs = sttp.tapir.docs.openapi
        .OpenAPIDocsInterpreter()
        .toOpenAPI(PartnerApi.endpoints.map(_.endpoint), "DMS Platform", "test")
      assertTrue(
        docs.paths.pathItems.contains("/api/v1/partners"),
        docs.paths.pathItems.contains("/api/v1/documents/{id}/references/{key}")
      )
    },
    test("templates and the partner master agree on the attributes that can be taken over") {
      assertTrue(ReferenceSource.PartnerMaster.attributes == Partner.Attributes)
    },
    test("the partner master is searched by name and identifier") {
      for
        byName  <- call(author, "GET", "/api/v1/partners?q=nordlicht")
        byId    <- call(author, "GET", "/api/v1/partners?q=P-2000&limit=1")
        short   <- call(author, "GET", "/api/v1/partners?q=a")
        tooMany <- call(author, "GET", "/api/v1/partners?q=gmbh&limit=500")
        denied  <- call(nobody, "GET", "/api/v1/partners?q=nordlicht")
      yield assertTrue(
        byName._1 == Status.Ok,
        byName._2.fromJson[List[PartnerView]].toOption.map(_.map(_.id)).contains(List("P-10001")),
        byName._2
          .fromJson[List[PartnerView]]
          .toOption
          .exists(_.head.address == "Hafenstraße 12, 20457 Hamburg, DE"),
        byId._2
          .fromJson[List[PartnerView]]
          .toOption
          .exists(ps => ps.size == 1 && ps.head.id.startsWith("P-2000")),
        short._1 == Status.UnprocessableEntity,
        tooMany._1 == Status.UnprocessableEntity,
        denied._1 == Status.Forbidden
      )
    },
    test("the form marks the fields a reference field fills") {
      for
        template <- setup.await
        fields   <- call(author, "GET", s"/api/v1/templates/${template.value}/versions/1/fields")
        view = fields._2.fromJson[TemplateFieldsView].toOption
      yield assertTrue(
        fields._1 == Status.Ok,
        view.exists(_.references.map(_.key) == List("contractPartner")),
        view.exists(_.references.head.source == "partner-master"),
        view.exists(
          _.fields.find(_.key == "partnerAddress").flatMap(_.reference) ==
            Some(FieldReferenceView("contractPartner", "address"))
        ),
        view.exists(_.fields.find(_.key == "title").exists(_.reference.isEmpty))
      )
    },
    test("a picked partner is taken over as system values that cannot be edited, only replaced or removed") {
      for
        created <- createDocument()
        doc = created._2.fromJson[DocumentView].toOption.get
        picked <- pick(doc.id, "P-10001")
        taken = version(picked._2)
        edited <- call(
          author,
          "PUT",
          s"/api/v1/documents/${doc.id}/draft",
          Some("""{"values":{"contractPartner":{"type":"Text","value":"Someone else"}}}""")
        )
        echoed <- call(
          author,
          "PUT",
          s"/api/v1/documents/${doc.id}/draft",
          Some("""{"values":{"contractPartner":{"type":"Text","value":"Nordlicht Logistik GmbH"}}}""")
        )
        typedAtCreation <- createDocument(""""contractPartner":{"type":"Text","value":"Typed GmbH"}""")
        replaced        <- pick(doc.id, "P-10003")
        unknown         <- pick(doc.id, "P-99999")
        noReference     <- pick(doc.id, "P-10001", key = "supplier")
        removed         <- call(author, "DELETE", s"/api/v1/documents/${doc.id}/references/contractPartner")
        byNobody        <- pick(doc.id, "P-10001").flatMap(_ =>
          call(
            nobody,
            "PUT",
            s"/api/v1/documents/${doc.id}/references/contractPartner",
            Some("""{"entryId":"P-1"}""")
          )
        )
      yield assertTrue(
        created._1 == Status.Created,
        picked._1 == Status.Ok,
        taken.version == "1.0-draft.2",
        taken.values.get("contractPartner") ==
          Some(
            FieldValueView(MetadataValue.Text("Nordlicht Logistik GmbH"), "System", Some("partner-master"))
          ),
        taken.values.get("partnerId").map(_.value) == Some(MetadataValue.Text("P-10001")),
        taken.values.get("partnerAddress").map(_.value) ==
          Some(MetadataValue.Text("Hafenstraße 12, 20457 Hamburg, DE")),
        edited._1 == Status.UnprocessableEntity,
        error(edited._2).exists(e =>
          e.code == "invalid_values" &&
            e.issues.exists(_.map(i => i.field -> i.code) == List("contractPartner" -> "not_editable"))
        ),
        echoed._1 == Status.Ok,
        version(echoed._2).values.get("contractPartner").exists(_.source.contains("partner-master")),
        version(echoed._2).values.contains("partnerId"),
        typedAtCreation._1 == Status.UnprocessableEntity,
        version(replaced._2).values.get("partnerId").map(_.value) == Some(MetadataValue.Text("P-10003")),
        // P-10003 has no VAT id or similar gaps; the address is still taken over in one line.
        version(replaced._2).values.get("partnerAddress").map(_.value) ==
          Some(MetadataValue.Text("Am Markt 3, 04109 Leipzig, DE")),
        unknown._1 == Status.UnprocessableEntity,
        error(unknown._2).exists(_.code == "unknown_entry"),
        noReference._1 == Status.NotFound,
        removed._1 == Status.Ok,
        !version(removed._2).values.contains("contractPartner"),
        !version(removed._2).values.contains("partnerId"),
        byNobody._1 == Status.Forbidden
      )
    },
    test("while the partner master is down the author gets a clear message and can still save the draft") {
      for
        created <- createDocument()
        doc = created._2.fromJson[DocumentView].toOption.get
        _      <- reachable.set(false)
        search <- call(author, "GET", "/api/v1/partners?q=nordlicht")
        picked <- pick(doc.id, "P-10001")
        saved  <- call(
          author,
          "PUT",
          s"/api/v1/documents/${doc.id}/draft",
          Some("""{"title":"Saved anyway","values":{}}""")
        )
        _     <- reachable.set(true)
        later <- pick(doc.id, "P-10001")
      yield assertTrue(
        search._1 == Status.ServiceUnavailable,
        error(search._2).exists(e =>
          e.code == "partner_directory_unavailable" && e.message.contains("save the draft") &&
            e.message.contains("did not answer")
        ),
        picked._1 == Status.ServiceUnavailable,
        saved._1 == Status.Ok,
        version(saved._2).version == "1.0-draft.2",
        later._1 == Status.Ok,
        version(later._2).version == "1.0-draft.3",
        version(later._2).values.get("contractPartner").exists(_.origin == "System")
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
