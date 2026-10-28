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
import dms.documents.LifecycleEvent.*
import dms.documents.core.*
import dms.documents.metadata.*
import dms.documents.{ChangeKind, DocumentStatus, FieldEntry, Rendition, ValueOrigin}
import dms.identity.*
import dms.kernel.{DocumentId, TemplateId, TenantId, UserId}
import dms.templates.*
import dms.templates.core.{TemplateDraft, TemplateService}
import org.testcontainers.utility.DockerImageName
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.RichZServerEndpoint
import zio.*
import zio.http.{Body, Header, Headers, MediaType, Request, Status, URL}
import zio.json.*
import zio.test.*

import java.time.{Instant, LocalDate}
import java.util.{Date, UUID}
import javax.sql.DataSource

/** HTTP tests of the impact analysis before releasing a template version (US-01.2, DMS-50) against a
  * Testcontainers database: counts and open drafts, authorization, and that a release changes no existing
  * document.
  */
object TemplateImpactApiSpec extends ZIOSpecDefault:

  private val issuer    = "http://localhost:8085"
  private val audience  = "project-1"
  private val orgId     = "org-1"
  private val tenantId  = TenantId.random()
  private val admin     = UserId("admin-1")
  private val author    = UserId("author-1")
  private val author2   = UserId("author-2")
  private val reader    = UserId("reader-1")
  private val approver  = UserId("approver-1")
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

  /** The template (version 1 released, version 2 in Testing) and the documents on version 1. */
  final case class Setup(
      app: Db,
      owner: Db,
      template: TemplateId,
      published: DocumentId,
      draft: DocumentId,
      otherDraft: DocumentId
  )

  private val setup = Unsafe.unsafe(implicit u => Promise.unsafe.make[Nothing, Setup](FiberId.None))

  private def content(terms: String) = TemplateContent(
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
        List(Block.Paragraph(List(Inline.Text(terms))))
      )
    )
  )

  private def manual(values: (String, MetadataValue)*): Map[String, FieldEntry] =
    values.map((k, v) => k -> FieldEntry(v, ValueOrigin.Manual)).toMap

  private val draftValues = manual("contractPartner" -> MetadataValue.Text("ACME GmbH"))

  private val reviewValues = draftValues ++ manual(
    "owner"                -> MetadataValue.PersonRef("owner-1"),
    "organizationalUnit"   -> MetadataValue.Text("Legal"),
    "validFrom"            -> MetadataValue.Date(LocalDate.of(2026, 1, 1)),
    "confidentiality"      -> MetadataValue.Selection("Internal"),
    "language"             -> MetadataValue.Text("de"),
    "retentionRule"        -> MetadataValue.Text("contract-10y"),
    "containsPersonalData" -> MetadataValue.YesNo(false)
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
      documents = DocumentService(
        audit,
        classes,
        DocumentTemplates(templates, DocumentClassProfiles(classes))
      )
      ids <- app
        .transact(tenantId)(
          for
            _ <- RoleAssignments.Postgres.grant(admin, RoleAssignment(Role.TenantAdmin), admin)
            _ <- RoleAssignments.Postgres.grant(author, RoleAssignment(Role.Author), admin)
            _ <- RoleAssignments.Postgres.grant(reader, RoleAssignment(Role.Reader), admin)
            _ <- RoleAssignments.Postgres.grant(designer, RoleAssignment(Role.TemplateDesigner), admin)
            _ <- RoleAssignments.Postgres.grant(designer2, RoleAssignment(Role.TemplateDesigner), admin)
            _ <- classes
              .createField("contractPartner", FieldDraft("Contract partner", None, FieldType.Text()), admin)
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
                  )
                )
              ),
              admin
            )
            draft = TemplateDraft("Service contract", None, cls.classId.value, 1, content("Terms"))
            t         <- templates.create(draft, designer)
            _         <- templates.changeStatus(t.templateId, 1, TemplateStatus.Testing, designer)
            _         <- templates.changeStatus(t.templateId, 1, TemplateStatus.Released, designer2)
            published <- documents
              .create(NewDocument(t.templateId, 1, "Published contract", reviewValues), author)
            (doc, v) = published
            _     <- documents.submit(doc.id, author)
            _     <- documents.changeStatus(v.id, ReviewPassed, UserId("reviewer-1"))
            _     <- documents.attachRendition(v.id, Rendition("renditions/1.pdf", None, "a" * 64), author)
            _     <- documents.changeStatus(v.id, Approve, approver)
            _     <- documents.changeStatus(v.id, Publish, approver)
            own   <- documents.create(NewDocument(t.templateId, 1, "Draft of author 1", draftValues), author)
            other <- documents.create(NewDocument(t.templateId, 1, "Draft of author 2", draftValues), author2)
            _     <- templates.newVersion(t.templateId, draft.copy(content = content("New terms")), designer)
            _     <- templates.changeStatus(t.templateId, 2, TemplateStatus.Testing, designer)
          yield (t.templateId, doc.id, own._1.id, other._1.id)
        )
        .mapError(e => RuntimeException(e.toString))
        .orDie
      _ <- setup.succeed(Setup(app, owner, ids._1, ids._2, ids._3, ids._4))
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

  private type Env = TemplateImpactApi.Env

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

  private val routes =
    ZioHttpInterpreter().toHttp(
      TemplateImpactApi.endpoints ++ TemplateLifecycleApi.endpoints.map(_.widen[Env])
    )

  private def call(user: UserId, method: String, path: String, body: Option[String] = None) =
    val url     = URL.decode(path).toOption.get
    val headers =
      Headers(Header.Authorization.Bearer(token(user)), Header.ContentType(MediaType.application.json))
    val request = method match
      case "POST" => Request.post(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case _      => Request.get(url).addHeaders(headers)
    ZIO.scoped(routes.runZIO(request)).flatMap(r => r.body.asString.map(b => (r.status, b)).orDie)

  private def impact(user: UserId, version: Int = 2, template: Option[UUID] = None) =
    setup.await.flatMap(s =>
      call(user, "GET", s"/api/v1/templates/${template.getOrElse(s.template.value)}/versions/$version/impact")
    )

  private def parse(body: String): TemplateImpactView =
    body.fromJson[TemplateImpactView].fold(e => throw AssertionError(s"$e: $body"), identity)

  /** Every document and document version row as text, to compare the stored state before and after. */
  private val storedDocuments: ZIO[Any, DbError, List[String]] =
    setup.await.flatMap(s =>
      s.owner.transactSystem(Db.sql {
        sql"select d::text from dms.document d order by id".query[String].run().toList ++
          sql"select v::text from dms.document_version v order by id".query[String].run().toList
      })
    )

  def spec = suite("TemplateImpactApi")(
    test("API documentation can be generated") {
      val docs = sttp.tapir.docs.openapi
        .OpenAPIDocsInterpreter()
        .toOpenAPI(TemplateImpactApi.endpoints.map(_.endpoint), "DMS Platform", "test")
      assertTrue(docs.paths.pathItems.contains("/api/v1/templates/{id}/versions/{version}/impact"))
    },
    test("the analysis counts documents per status on earlier versions and lists open drafts by title") {
      for
        s      <- setup.await
        result <- impact(designer2)
        view = parse(result._2)
      yield assertTrue(
        result._1 == Status.Ok,
        view.templateId == s.template.value,
        view.version == 2,
        view.status == "Testing",
        view.documents == 3,
        view.byStatus == List(StatusCountView("Draft", 2, 2), StatusCountView("Published", 1, 1)),
        view.byVersion == List(TemplateVersionUseView(1, 3, view.byStatus)),
        view.openDrafts
          .map(d => (d.documentId, d.title, d.author, d.version, d.status, d.templateVersion))
          .toSet ==
          Set(
            (s.draft.value, "Draft of author 1", author.value, "1.0-draft.1", "Draft", 1),
            (s.otherDraft.value, "Draft of author 2", author2.value, "1.0-draft.1", "Draft", 1)
          ),
        view.existingDocumentsUnchanged,
        view.explanation == TemplateImpactApi.Explanation,
        // Title and author only: neither content nor field values of other users' drafts.
        !result._2.contains("ACME"),
        !result._2.contains("\"content\""),
        !result._2.contains("\"values\"")
      )
    },
    test("the first version has no earlier documents") {
      for result <- impact(designer, version = 1)
      yield assertTrue(
        result._1 == Status.Ok,
        parse(result._2).documents == 0,
        parse(result._2).byStatus.isEmpty,
        parse(result._2).openDrafts.isEmpty
      )
    },
    test("only template designers see the analysis; unknown versions and templates are not found") {
      for
        byAuthor  <- impact(author)
        byReader  <- impact(reader)
        byAdmin   <- impact(admin)
        byCreator <- impact(designer)
        noVersion <- impact(designer, version = 9)
        noTmpl    <- impact(designer, template = Some(UUID.randomUUID()))
      yield assertTrue(
        byAuthor._1 == Status.Forbidden,
        byReader._1 == Status.Forbidden,
        byAdmin._1 == Status.Forbidden,
        byAuthor._2.fromJson[ApiError].toOption.exists(_.code == "forbidden"),
        // The creator of the version may analyse it, although they cannot release it.
        byCreator._1 == Status.Ok,
        noVersion._1 == Status.NotFound,
        noTmpl._1 == Status.NotFound
      )
    },
    test("releasing the new template version changes no existing document") {
      for
        s       <- setup.await
        before  <- storedDocuments
        details <- s.app.transact(tenantId)(
          ZIO.serviceWithZIO[DocumentService](d =>
            ZIO.foreach(List(s.published, s.draft, s.otherDraft))(d.get)
          )
        )
        released <- call(
          designer2,
          "POST",
          s"/api/v1/admin/templates/${s.template.value}/versions/2/status",
          Some("""{"status":"Released"}""")
        )
        after        <- storedDocuments
        detailsAfter <- s.app.transact(tenantId)(
          ZIO.serviceWithZIO[DocumentService](d =>
            ZIO.foreach(List(s.published, s.draft, s.otherDraft))(d.get)
          )
        )
        analysisAfter <- impact(designer2)
        // A new version of the published document keeps the template version of its basis ...
        next <- s.app.transact(tenantId)(
          ZIO.serviceWithZIO[DocumentService](_.newVersion(s.published, ChangeKind.Major, None, author))
        )
        // ... while new documents can use the released version.
        created <- s.app.transact(tenantId)(
          ZIO.serviceWithZIO[DocumentService](
            _.create(NewDocument(s.template, 2, "New contract", draftValues), author)
          )
        )
      yield assertTrue(
        released._1 == Status.Ok,
        released._2.fromJson[TemplateVersionView].toOption.exists(_.status == "Released"),
        before.size == 6,
        after == before,
        detailsAfter.map(d => (d.document, d.versions)) == details.map(d => (d.document, d.versions)),
        detailsAfter.forall(_.versions.forall(v => v.templateVersion == 1 && v.content == content("Terms"))),
        parse(analysisAfter._2).byStatus == List(
          StatusCountView("Draft", 2, 2),
          StatusCountView("Published", 1, 1)
        ),
        next.templateVersion == 1,
        next.content == content("Terms"),
        created._2.templateVersion == 2,
        created._2.content == content("New terms")
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
