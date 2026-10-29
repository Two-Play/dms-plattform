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
import dms.authz.{Authorizer, Groups, Role, RoleAssignment, RoleAssignments}
import dms.db.{Db, DbConfig, DbError, Migrations}
import dms.documents.*
import dms.documents.LifecycleEvent.*
import dms.documents.core.*
import dms.documents.metadata.{DocumentClassVersion, FieldBinding, FieldKey, FieldType, MetadataValue}
import dms.identity.*
import dms.kernel.{DocumentId, TemplateId, TenantId, UserId}
import dms.templates.*
import dms.templates.core.{TemplateDraft, TemplateService}
import dms.workflow.core.*
import org.testcontainers.utility.DockerImageName
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.*
import zio.http.{Header, Headers, Request, Status, URL}
import zio.json.*
import zio.test.*

import java.time.{Instant, LocalDate, ZoneId}
import java.util.Date
import javax.sql.DataSource

/** The library (DMS-119) end to end: HTTP endpoint over documents published through the documents service,
  * acknowledgements and role assignments in a Testcontainers database.
  *
  * Population: `readerAll` (Reader), `readerHr` (Reader for HR), `boardMember` (Reader, member of the access
  * group `board`), `admin` (tenant administrator, no business role). Author and approver hold no role.
  *
  * Documents: `travel` (class `instruction`, HR, number ZA-001, tags travel and costs, a summary, first
  * published today), `security` (class `policy`, Finance, tag security; version 1.0 published 60 days ago,
  * replaced today by 2.0), `board` (class `instruction`, HR, Confidential for the group `board`), `draft`
  * (class `instruction`, never submitted). readerHr acknowledged `travel`.
  */
object LibraryApiSpec extends ZIOSpecDefault:

  private val issuer   = "http://localhost:8085"
  private val audience = "project-1"
  private val orgId    = "org-1"
  private val tenantId = TenantId.random()

  private val admin       = UserId("admin")
  private val author      = UserId("author")
  private val approver    = UserId("approver")
  private val readerAll   = UserId("reader-all")
  private val readerHr    = UserId("reader-hr")
  private val boardMember = UserId("board-member")

  private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("key-1").generate()

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  final case class Fixture(
      travel: DocumentVersion,
      securityV1: DocumentVersion,
      securityV2: DocumentVersion,
      board: DocumentVersion,
      draft: DocumentId
  )

  private val setup = Unsafe.unsafe(implicit u => Promise.unsafe.make[Nothing, Fixture](FiberId.None))

  private val today = LocalDate.now(ZoneId.of("Europe/Berlin"))

  private val content = TemplateContent(
    List(
      Section(
        SectionId("rules"),
        Some("Rules"),
        locked = false,
        List(Block.Paragraph(List(Inline.Text((1 to 20).map(i => s"rule$i").mkString(" ")))))
      )
    )
  )

  private def values(
      unit: String,
      validFrom: LocalDate,
      tags: List[String] = Nil,
      summary: Option[String] = None,
      confidential: Boolean = false
  ): Map[String, FieldEntry] =
    (Map(
      "owner"                -> MetadataValue.PersonRef(author.value),
      "organizationalUnit"   -> MetadataValue.Text(unit),
      "validFrom"            -> MetadataValue.Date(validFrom),
      "confidentiality"      -> MetadataValue.Selection(if confidential then "Confidential" else "Internal"),
      "language"             -> MetadataValue.Text("de"),
      "retentionRule"        -> MetadataValue.Text("policy-10y"),
      "containsPersonalData" -> MetadataValue.YesNo(false)
    ) ++ Option.when(tags.nonEmpty)("tags" -> MetadataValue.Many(tags.map(MetadataValue.Text(_)))) ++
      summary.map(s => "summary" -> MetadataValue.Text(s)) ++
      Option.when(confidential)("allowedGroups" -> MetadataValue.Many(List(MetadataValue.Text("board")))))
      .map((k, v) => k -> FieldEntry(v, ValueOrigin.Manual))

  /** Moves the open version through review and approval and publishes it. */
  private def publishOpen(service: DocumentService, doc: DocumentId) =
    for
      v <- service.submit(doc, author)
      _ <- service.changeStatus(v.id, ReviewPassed, UserId("reviewer"))
      _ <- service.attachRendition(v.id, Rendition(s"r/${v.id}.pdf", None, "a" * 64), author)
      _ <- service.changeStatus(v.id, Approve, approver)
      p <- service.changeStatus(v.id, Publish, approver)
    yield p

  private def document(
      service: DocumentService,
      template: TemplateId,
      title: String,
      vs: Map[String, FieldEntry]
  ) =
    for
      created <- service.create(NewDocument(template, 1, title, vs), author)
      v       <- publishOpen(service, created._1.id)
    yield (created._1.id, v)

  private def template(templates: TemplateService, name: String, cls: DocumentClassVersion) =
    for
      t <- templates.create(TemplateDraft(name, None, cls.classId.value, 1, content), UserId("designer"))
      _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Testing, UserId("designer"))
      _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Released, UserId("designer-2"))
    yield t.templateId

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
      documents = DocumentService(
        audit,
        classes,
        DocumentTemplates(templates, DocumentClassProfiles(classes))
      )
      acks  = AcknowledgementService(audit)
      grant = (user: UserId, a: RoleAssignment) => RoleAssignments.Postgres.grant(user, a, admin)
      fixture <- app
        .transact(tenantId)(
          for
            _       <- grant(admin, RoleAssignment(Role.TenantAdmin))
            _       <- grant(readerAll, RoleAssignment(Role.Reader))
            _       <- grant(readerHr, RoleAssignment(Role.Reader, Some("HR")))
            _       <- grant(boardMember, RoleAssignment(Role.Reader))
            _       <- Groups.Postgres.create("board", "Board", admin)
            _       <- Groups.Postgres.addMember("board", boardMember, admin)
            summary <- classes.createField("summary", FieldDraft("Summary", None, FieldType.Text()), admin)
            binding = List(FieldBinding(FieldKey.unsafe("summary"), summary.version))
            instruction <- classes.createClass(
              "instruction",
              None,
              ClassDraft("Central instruction", None, binding),
              admin
            )
            policy        <- classes.createClass("policy", None, ClassDraft("Policy", None, binding), admin)
            instructions  <- template(templates, "Instruction", instruction)
            policies      <- template(templates, "Policy", policy)
            travelCreated <- documents.create(
              NewDocument(
                instructions,
                1,
                "Travel expenses",
                values("HR", today, List("travel", "costs"), Some("How to   claim travel expenses."))
              ),
              author
            )
            _      <- documents.assignNumber(travelCreated._1.id, "ZA-001", "Taken over", author)
            travel <- publishOpen(documents, travelCreated._1.id)
            sec    <- document(
              documents,
              policies,
              "IT security",
              values("Finance", today.minusDays(60), List("security"))
            )
            _ <- documents.newVersion(sec._1, ChangeKind.Major, None, author)
            _ <- documents.updateDraft(
              sec._1,
              DraftChange(Some("IT security policy"), values("Finance", today, List("security")), None),
              author
            )
            sec2  <- publishOpen(documents, sec._1)
            board <- document(
              documents,
              instructions,
              "Board rules",
              values("HR", today, confidential = true)
            )
            draft <- documents.create(
              NewDocument(instructions, 1, "Draft instruction", values("HR", today)),
              author
            )
            _ <- acks.markOpened(travel, readerHr) *> acks.acknowledge(travel, readerHr)
          yield Fixture(travel, sec._2, sec2, board._2, draft._1.id)
        )
        .mapError(e => RuntimeException(e.toString))
        .orDie
      // Version 1.0 of the security policy was published 60 days ago (the triggers protect the time).
      _ <- schemaOwner
        .transactSystem(Db.sql {
          val _ = sql"set local session_replication_role = replica".update.run()
          sql"""update dms.document_version set published_at = now() - interval '60 days'
                where id = ${fixture.securityV1.id}""".update.run()
        })
        .orDieWith(e => RuntimeException(e.toString))
      _ <- setup.succeed(fixture)
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

  private val env: ZLayer[Any, Throwable, LibraryApi.Env] =
    ZLayer.make[LibraryApi.Env](
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
      DocumentService.layer,
      AcknowledgementService.layer,
      FlowableEngine.layer,
      WorkflowDocuments.layer,
      RoleDirectory.layer,
      WorkflowExtraReview.layer,
      ZLayer.succeed(WorkflowConfig.default),
      WorkflowRendition.layer,
      ApprovalWorkflow.layer
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

  private val routes = ZioHttpInterpreter().toHttp(LibraryApi.endpoints)

  private def get(user: UserId, query: String) =
    val request =
      Request
        .get(URL.decode(s"/api/v1/library$query").toOption.get)
        .addHeaders(Headers(Header.Authorization.Bearer(token(user))))
    ZIO.scoped(routes.runZIO(request)).flatMap(r => r.body.asString.map(b => (r.status, b)).orDie)

  private def library(user: UserId, query: String = "") =
    get(user, query).map(r => r._2.fromJson[LibraryView].fold(e => throw AssertionError(s"$e: $r"), identity))

  private def titles(user: UserId, query: String = "") = library(user, query).map(_.items.map(_.title))

  def spec = suite("Library API")(
    test("API documentation contains the library") {
      val docs = sttp.tapir.docs.openapi
        .OpenAPIDocsInterpreter()
        .toOpenAPI(LibraryApi.endpoints.map(_.endpoint), "DMS Platform", "test")
      assertTrue(docs.paths.pathItems.contains("/api/v1/library"))
    },
    test("lists the valid versions the caller may read, with number, index, unit, description and markers") {
      for
        f    <- setup.await
        view <- library(readerAll)
        travel   = view.items.find(_.documentId == f.travel.documentId.value).get
        security = view.items.find(_.documentId == f.securityV2.documentId.value).get
      yield assertTrue(
        // the confidential board rules, the draft and the superseded version 1.0 are not listed
        view.items.map(_.title) == List("IT security policy", "Travel expenses"),
        view.total == 2,
        view.sort == "title",
        view.order == "asc",
        view.since == today.minusDays(Library.DefaultChangeWindowDays),
        view.timeZone == "Europe/Berlin",
        travel.versionId == f.travel.id,
        travel.documentNumber == Some("ZA-001"),
        travel.version == "1.0",
        travel.classPath == List("instruction"),
        travel.classLabel == "Central instruction",
        travel.organizationalUnit == Some("HR"),
        travel.confidentiality == "Internal",
        travel.tags == List("travel", "costs"),
        travel.summary == Some("How to claim travel expenses."),
        travel.validFrom == Some(today),
        travel.isNew,
        !travel.changed,
        travel.acknowledgementPending,
        security.versionId == f.securityV2.id,
        security.version == "2.0",
        security.documentNumber.isEmpty,
        !security.isNew,
        security.changed,
        view.facets.changed == 2,
        view.facets.pending == 2
      )
    },
    test("reading permissions decide: unit scope, confidentiality for the allowed group, no business role") {
      for
        hr    <- titles(readerHr)
        board <- titles(boardMember)
        none  <- library(admin)
      yield assertTrue(
        hr == List("Travel expenses"),
        board == List("Board rules", "IT security policy", "Travel expenses"),
        none.total == 0,
        none.facets.`class`.isEmpty
      )
    },
    test(
      "filters by class, unit, tag, confidentiality and free text; facets keep the other values of a facet"
    ) {
      for
        byClass  <- library(readerAll, "?class=instruction")
        byUnit   <- titles(readerAll, "?unit=Finance")
        byTag    <- titles(readerAll, "?tag=travel,security")
        byLevel  <- titles(boardMember, "?confidentiality=Confidential")
        byText   <- titles(readerAll, "?q=CLAIM%20travel")
        byNumber <- titles(readerAll, "?q=za-001")
        nothing  <- library(readerAll, "?q=unknown")
      yield assertTrue(
        byClass.items.map(_.title) == List("Travel expenses"),
        byClass.total == 1,
        byClass.facets.`class`.map(f => (f.value, f.label, f.count)) ==
          List(("instruction", Some("Central instruction"), 1), ("policy", Some("Policy"), 1)),
        byClass.facets.unit.map(f => (f.value, f.count)) == List(("HR", 1)),
        byClass.facets.tag.map(_.value) == List("costs", "travel"),
        byUnit == List("IT security policy"),
        byTag == List("IT security policy", "Travel expenses"),
        byLevel == List("Board rules"),
        byText == List("Travel expenses"),
        byNumber == List("Travel expenses"),
        nothing.total == 0,
        nothing.items.isEmpty
      )
    },
    test("new and changed since a date, and open acknowledgements of the caller") {
      for
        changed  <- titles(readerAll, "?changed=true")
        later    <- library(readerAll, s"?since=${today.plusDays(1)}&changed=true")
        earlier  <- library(readerAll, s"?since=${today.minusDays(90)}")
        pendingA <- titles(readerAll, "?pending=true")
        pendingH <- library(readerHr, "?pending=true")
      yield assertTrue(
        changed == List("IT security policy", "Travel expenses"),
        later.items.isEmpty,
        later.facets.changed == 0,
        // 90 days back both documents are new
        earlier.items.forall(i => i.isNew && !i.changed),
        pendingA == List("IT security policy", "Travel expenses"),
        pendingH.items.isEmpty,
        pendingH.facets.pending == 0
      )
    },
    test("sorts by title, number, valid from and last change, and pages") {
      for
        titleDesc <- titles(readerAll, "?sort=title&order=desc")
        number    <- titles(readerAll, "?sort=number")
        validFrom <- library(readerAll, "?sort=validFrom")
        changed   <- titles(readerAll, "?sort=lastChanged&order=asc")
        page2     <- library(readerAll, "?size=1&page=2")
      yield assertTrue(
        titleDesc == List("Travel expenses", "IT security policy"),
        // documents without number come last
        number == List("Travel expenses", "IT security policy"),
        validFrom.order == "desc",
        validFrom.items.map(_.title) == List("IT security policy", "Travel expenses"),
        changed == List("Travel expenses", "IT security policy"),
        page2.total == 2,
        page2.size == 1,
        page2.items.map(_.title) == List("Travel expenses")
      )
    },
    test("rejects unknown sort orders and out-of-range pages") {
      for
        sort  <- get(readerAll, "?sort=author")
        order <- get(readerAll, "?order=up")
        size  <- get(readerAll, "?size=0")
        page  <- get(readerAll, "?page=0")
      yield assertTrue(
        sort._1 == Status.UnprocessableEntity,
        order._1 == Status.UnprocessableEntity,
        size._1 == Status.UnprocessableEntity,
        page._1 == Status.UnprocessableEntity
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
