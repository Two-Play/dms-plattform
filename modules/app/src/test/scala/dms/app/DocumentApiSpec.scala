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
import dms.authz.{Authorizer, Role, RoleAssignment, RoleAssignments}
import dms.db.{Db, DbConfig, DbError, Migrations}
import dms.documents.DocumentStatus
import dms.documents.core.*
import dms.documents.metadata.*
import dms.identity.*
import dms.kernel.{TemplateId, TenantId, UserId}
import dms.templates.*
import dms.templates.core.{TemplateDraft, TemplateService}
import org.testcontainers.utility.DockerImageName
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.*
import zio.http.{Body, Header, Headers, MediaType, Request, Status, URL}
import zio.json.*
import zio.test.*

import java.time.Instant
import java.util.{Date, UUID}
import javax.sql.DataSource

/** HTTP tests of documents created from templates (authorization, validation, submission) against a
  * Testcontainers database, with the real audit trail to show that the document, its audit entry and its
  * outbox event commit or roll back together.
  */
object DocumentApiSpec extends ZIOSpecDefault:

  private val issuer    = "http://localhost:8085"
  private val audience  = "project-1"
  private val orgId     = "org-1"
  private val tenantId  = TenantId.random()
  private val admin     = UserId("admin-1")
  private val author    = UserId("author-1")
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

  final case class Setup(app: Db, owner: Db, released: TemplateId, draft: TemplateId)

  private val setup = Unsafe.unsafe(implicit u => Promise.unsafe.make[Nothing, Setup](FiberId.None))

  private val content = TemplateContent(
    List(
      Section(
        SectionId("parties"),
        Some("Parties"),
        locked = true,
        List(Block.Paragraph(List(Inline.Text("Between "), Inline.Placeholder("contractPartner"))))
      ),
      Section(
        SectionId("terms"),
        Some("Terms"),
        locked = false,
        List(Block.Paragraph(List(Inline.Text("Terms"))))
      )
    ),
    computed = List(ComputedField("totalValue", "monthlyFee.amount * termMonths"))
  )

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
      owner <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, poolSize = 1)).map(Db(_))
      _     <- owner
        .transactSystem(
          Db.sql(sql"insert into dms.tenant (id, name) values (${tenantId.value}, ${"Tenant"})".update.run())
        )
        .orDieWith(e => RuntimeException(e.toString))
      app <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", poolSize = 2)).map(Db(_))
      audit     = AuditTrailLive(app, TsaTrust(Nil))
      classes   = DocumentClassService(audit)
      templates = TemplateService(audit, DocumentClassProfiles(classes))
      ids <- app
        .transact(tenantId)(
          for
            _ <- RoleAssignments.Postgres.grant(admin, RoleAssignment(Role.TenantAdmin), admin)
            _ <- RoleAssignments.Postgres.grant(author, RoleAssignment(Role.Author), admin)
            _ <- RoleAssignments.Postgres.grant(reader, RoleAssignment(Role.Reader), admin)
            _ <- classes
              .createField("contractPartner", FieldDraft("Contract partner", None, FieldType.Text()), admin)
            _ <- classes.createField("monthlyFee", FieldDraft("Monthly fee", None, FieldType.Amount()), admin)
            _ <- classes
              .createField("termMonths", FieldDraft("Term in months", None, FieldType.Number()), admin)
            cls <- classes.createClass(
              "contract",
              None,
              ClassDraft(
                "Contract",
                None,
                List(
                  FieldBinding(
                    FieldKey.unsafe("contractPartner"),
                    1,
                    Requiredness.from(DocumentStatus.InReview)
                  ),
                  FieldBinding(FieldKey.unsafe("monthlyFee"), 1),
                  FieldBinding(FieldKey.unsafe("termMonths"), 1)
                )
              ),
              admin
            )
            draft = TemplateDraft("Service contract", None, cls.classId.value, 1, content)
            released   <- templates.create(draft, designer)
            _          <- templates.changeStatus(released.templateId, 1, TemplateStatus.Testing, designer)
            _          <- templates.changeStatus(released.templateId, 1, TemplateStatus.Released, designer2)
            unreleased <- templates.create(draft, designer)
          yield (released.templateId, unreleased.templateId)
        )
        .mapError(e => RuntimeException(e.toString))
        .orDie
      _ <- setup.succeed(Setup(app, owner, ids._1, ids._2))
    yield app
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

  private val env: ZLayer[Any, Throwable, DocumentApi.Env & AuditTrail] =
    ZLayer.make[DocumentApi.Env & AuditTrail](
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
      DocumentService.layer
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

  private val routes = ZioHttpInterpreter().toHttp(DocumentApi.endpoints)

  private def call(user: UserId, method: String, path: String, body: Option[String] = None) =
    val url     = URL.decode(path).toOption.get
    val headers =
      Headers(Header.Authorization.Bearer(token(user)), Header.ContentType(MediaType.application.json))
    val request = method match
      case "POST" => Request.post(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case "PUT"  => Request.put(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case _      => Request.get(url).addHeaders(headers)
    ZIO.scoped(routes.runZIO(request)).flatMap(r => r.body.asString.map(b => (r.status, b)).orDie)

  private def error(body: String): Option[ApiError] = body.fromJson[ApiError].toOption

  private val baseValues =
    """"contractPartner":{"type":"Text","value":"ACME GmbH"},
      |"monthlyFee":{"type":"Amount","value":100,"currency":"EUR"},
      |"termMonths":{"type":"Number","value":12}""".stripMargin

  private val reviewValues =
    baseValues + """,
      |"owner":{"type":"PersonRef","userId":"owner-1"},
      |"organizationalUnit":{"type":"Text","value":"Legal"},
      |"validFrom":{"type":"Date","value":"2027-01-01"},
      |"confidentiality":{"type":"Selection","code":"Internal"},
      |"language":{"type":"Text","value":"de"},
      |"retentionRule":{"type":"Text","value":"contract-10y"},
      |"containsPersonalData":{"type":"YesNo","value":false}""".stripMargin

  private def createBody(template: TemplateId, values: String = baseValues, version: Int = 1) =
    s"""{"templateId":"${template.value}","templateVersion":$version,"title":"Service contract ACME",
       |"values":{$values}}""".stripMargin

  private def createDocument(values: String = baseValues): ZIO[DocumentApi.Env, Nothing, DocumentView] =
    for
      s       <- setup.await
      created <- call(author, "POST", "/api/v1/documents", Some(createBody(s.released, values)))
    yield created._2.fromJson[DocumentView].fold(e => throw AssertionError(s"$e: ${created._2}"), identity)

  private def count(table: String, document: UUID): ZIO[Any, DbError, Long] =
    setup.await.flatMap(s =>
      s.owner.transactSystem(Db.sql(table match
        case "audit" =>
          sql"select count(*) from dms.audit_event where object_id = $document".query[Long].run().head
        case "outbox" =>
          sql"select count(*) from dms.outbox_event where aggregate_id = $document".query[Long].run().head
        case _ => sql"select count(*) from dms.document where id = $document".query[Long].run().head))
    )

  def spec = suite("DocumentApi")(
    test("API documentation can be generated") {
      val docs = sttp.tapir.docs.openapi
        .OpenAPIDocsInterpreter()
        .toOpenAPI(DocumentApi.endpoints.map(_.endpoint), "DMS Platform", "test")
      assertTrue(
        docs.paths.pathItems.contains("/api/v1/documents"),
        docs.paths.pathItems.contains("/api/v1/documents/{id}/draft"),
        docs.paths.pathItems.contains("/api/v1/documents/{id}/submit")
      )
    },
    test("authors create documents from released templates; readers and administrators cannot") {
      for
        s           <- setup.await
        byReader    <- call(reader, "POST", "/api/v1/documents", Some(createBody(s.released)))
        byAdmin     <- call(admin, "POST", "/api/v1/documents", Some(createBody(s.released)))
        byAuthor    <- call(author, "POST", "/api/v1/documents", Some(createBody(s.released)))
        notReleased <- call(author, "POST", "/api/v1/documents", Some(createBody(s.draft)))
        view = byAuthor._2.fromJson[DocumentView].toOption
      yield assertTrue(
        byReader._1 == Status.Forbidden,
        error(byReader._2).exists(_.code == "forbidden"),
        byAdmin._1 == Status.Forbidden,
        byAuthor._1 == Status.Created,
        view.exists(_.current.version == "1.0-draft.1"),
        view.exists(_.templateVersion == 1),
        view.exists(_.classPath == List("contract")),
        view.exists(_.current.values.get("contractPartner").exists(_.origin == "Manual")),
        view.exists(
          _.current.values.get("documentId").exists(v => v.origin == "System" && v.source.contains("dms"))
        ),
        view.exists(_.current.values.get("totalValue").exists(v => v.origin == "Computed")),
        view.exists(_.current.content == content),
        notReleased._1 == Status.Conflict
      )
    },
    test("invalid values are rejected with their issues") {
      for
        s      <- setup.await
        result <- call(
          author,
          "POST",
          "/api/v1/documents",
          Some(createBody(s.released, """"termMonths":{"type":"Text","value":"twelve"}"""))
        )
      yield assertTrue(
        result._1 == Status.UnprocessableEntity,
        error(result._2).exists(e =>
          e.code == "invalid_values" && e.issues.exists(
            _.map(i => i.field -> i.code) == List("termMonths" -> "wrong_type")
          )
        )
      )
    },
    test("drafts are visible to authors, not to readers") {
      for
        doc      <- createDocument()
        byAuthor <- call(author, "GET", s"/api/v1/documents/${doc.id}")
        byReader <- call(reader, "GET", s"/api/v1/documents/${doc.id}")
        versions <- call(author, "GET", s"/api/v1/documents/${doc.id}/versions")
        missing  <- call(author, "GET", s"/api/v1/documents/${UUID.randomUUID()}")
      yield assertTrue(
        byAuthor._1 == Status.Ok,
        byAuthor._2.fromJson[DocumentView].toOption.exists(_.id == doc.id),
        byReader._1 == Status.Forbidden,
        versions._2
          .fromJson[List[DocumentVersionView]]
          .toOption
          .map(_.map(_.version))
          .contains(List("1.0-draft.1")),
        missing._1 == Status.NotFound
      )
    },
    test("authors change drafts; locked sections stay as in the template") {
      for
        doc      <- createDocument()
        byReader <- call(
          reader,
          "PUT",
          s"/api/v1/documents/${doc.id}/draft",
          Some(s"""{"values":{$baseValues}}""")
        )
        changed <- call(
          author,
          "PUT",
          s"/api/v1/documents/${doc.id}/draft",
          Some(s"""{"title":"Renamed","values":{$baseValues,"termMonths":{"type":"Number","value":24}}}""")
        )
        locked <- call(
          author,
          "PUT",
          s"/api/v1/documents/${doc.id}/draft",
          Some(
            s"""{"values":{$baseValues},"content":{"sections":[
               |{"id":"parties","title":"Parties","locked":true,"blocks":[]},
               |{"id":"terms","title":"Terms","locked":false,"blocks":[]}],
               |"computed":[{"key":"totalValue","expression":"monthlyFee.amount * termMonths"}]}}""".stripMargin
          )
        )
        view = changed._2.fromJson[DocumentVersionView].toOption
      yield assertTrue(
        byReader._1 == Status.Forbidden,
        changed._1 == Status.Ok,
        view.exists(_.version == "1.0-draft.2"),
        view.exists(_.values.get("totalValue").exists(_.value == MetadataValue.Number(2400))),
        locked._1 == Status.UnprocessableEntity,
        error(locked._2).exists(_.message.contains("Locked section 'parties'"))
      )
    },
    test("submission is blocked while required fields are missing and lists them") {
      for
        doc     <- createDocument()
        blocked <- call(author, "POST", s"/api/v1/documents/${doc.id}/submit")
        _       <- call(
          author,
          "PUT",
          s"/api/v1/documents/${doc.id}/draft",
          Some(s"""{"values":{$reviewValues}}""")
        )
        byReader  <- call(reader, "POST", s"/api/v1/documents/${doc.id}/submit")
        submitted <- call(author, "POST", s"/api/v1/documents/${doc.id}/submit")
        editAfter <- call(
          author,
          "PUT",
          s"/api/v1/documents/${doc.id}/draft",
          Some(s"""{"values":{$reviewValues}}""")
        )
        outbox <- count("outbox", doc.id)
      yield assertTrue(
        blocked._1 == Status.UnprocessableEntity,
        error(blocked._2).exists(e =>
          e.code == "incomplete" && e.issues.exists(
            _.map(_.field).toSet == Set(
              "owner",
              "organizationalUnit",
              "validFrom",
              "confidentiality",
              "language",
              "retentionRule",
              "containsPersonalData"
            )
          )
        ),
        byReader._1 == Status.Forbidden,
        submitted._1 == Status.Ok,
        submitted._2.fromJson[DocumentVersionView].toOption.exists(_.status == "InReview"),
        editAfter._1 == Status.Forbidden,
        outbox == 2L
      )
    },
    test("the document, its audit entry and its outbox event commit or roll back together") {
      for
        s       <- setup.await
        service <- ZIO.service[DocumentService]
        request = NewDocument(
          s.released,
          1,
          "Atomic",
          Map(
            "contractPartner" -> dms.documents.FieldEntry(
              MetadataValue.Text("ACME"),
              dms.documents.ValueOrigin.Manual
            )
          )
        )
        failed <- s.app
          .transact(tenantId)(
            service.create(request, author).flatMap((d, _) => ZIO.fail(d.id.value))
          )
          .either
        failedId = failed.left.toOption.collect { case id: UUID => id }.getOrElse(UUID.randomUUID())
        committed  <- s.app.transact(tenantId)(service.create(request, author)).map(_._1.id.value)
        rolledBack <- ZIO.foreach(List("document", "audit", "outbox"))(count(_, failedId))
        kept       <- ZIO.foreach(List("document", "audit", "outbox"))(count(_, committed))
      yield assertTrue(failed.isLeft, rolledBack == List(0L, 0L, 0L), kept == List(1L, 1L, 1L))
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
