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
import dms.archive.core.LegalHoldService
import dms.audit.AuditTrail
import dms.audit.core.{AuditTrailLive, TsaTrust}
import dms.authz.{Authorizer, Groups, Role, RoleAssignment, RoleAssignments}
import dms.db.{Db, DbConfig, DbError, Migrations}
import dms.documents.core.*
import dms.identity.*
import dms.integration.{JobRunner, OutboxRelay, WorkerConfig}
import dms.kernel.{TemplateId, TenantId, UserId}
import dms.rendering.core.RenditionSource
import dms.search.core.{OpenSearch, OpenSearchContainer, SearchIndex, SearchJobs, SearchSettings}
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
import java.util.{Date, UUID}
import javax.sql.DataSource

/** Legal holds (DMS-56) end to end: documents published through the real workflow and indexed into
  * OpenSearch, holds set on documents and on a confirmed search result, released, and shown in the document
  * view, over HTTP with the records manager and other roles; PostgreSQL and OpenSearch in Testcontainers.
  */
object LegalHoldApiSpec extends ZIOSpecDefault:

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
  private val records   = UserId("records-1")
  private val recordsHr = UserId("records-hr-1")
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

  final case class Setup(app: Db, released: TemplateId)

  private val setup = Unsafe.unsafe(implicit u => Promise.unsafe.make[Nothing, Setup](FiberId.None))

  private val content = TemplateContent(
    List(
      Section(
        SectionId("terms"),
        Some("Bedingungen"),
        locked = false,
        List(Block.Paragraph(List(Inline.Text("Lieferung und Zahlung nach den vereinbarten Bedingungen."))))
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
            _ <- RoleAssignments.Postgres.grant(owner, RoleAssignment(Role.Owner), admin)
            _ <- RoleAssignments.Postgres.grant(reviewer, RoleAssignment(Role.Reviewer), admin)
            _ <- RoleAssignments.Postgres.grant(approver, RoleAssignment(Role.Approver), admin)
            _ <- RoleAssignments.Postgres.grant(reader, RoleAssignment(Role.Reader), admin)
            _ <- RoleAssignments.Postgres.grant(records, RoleAssignment(Role.RecordsManager), admin)
            _ <- RoleAssignments.Postgres.grant(records, RoleAssignment(Role.Reader), admin)
            _ <- RoleAssignments.Postgres
              .grant(recordsHr, RoleAssignment(Role.RecordsManager, Some("HR")), admin)
            cls <- classes.createClass("contract", None, ClassDraft("Contract", None, Nil), admin)
            draft = TemplateDraft("Supply contract", None, cls.classId.value, 1, content)
            t <- templates.create(draft, designer)
            _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Testing, designer)
            _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Released, designer2)
          yield t.templateId
        )
        .mapError(e => RuntimeException(e.toString))
        .orDie
      _ <- setup.succeed(Setup(app, released))
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

  private type Env = DocumentApi.Env & WorkflowApi.Env & LegalHoldService & SearchIndex & RenditionSource &
    AuditTrail

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
      DocumentRenditions.layer,
      OpenSearchContainer.layer("dms-legal-hold-test"),
      ZLayer.succeed(SearchSettings()),
      OpenSearch.layer,
      SearchDocuments.layer,
      SearchIndex.layer,
      LegalHoldService.layer
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

  private val routes = ZioHttpInterpreter().toHttp(
    DocumentApi.endpoints.map(_.widen[Env]) ++ WorkflowApi.endpoints.map(_.widen[Env]) ++
      SearchApi.endpoints.map(_.widen[Env]) ++ LegalHoldApi.endpoints.map(_.widen[Env])
  )

  private def call(user: UserId, method: String, path: String, body: Option[String] = None) =
    val url     = URL.decode(path).toOption.get
    val headers =
      Headers(Header.Authorization.Bearer(token(user)), Header.ContentType(MediaType.application.json))
    val request = method match
      case "POST" => Request.post(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case _      => Request.get(url).addHeaders(headers)
    ZIO.scoped(routes.runZIO(request)).flatMap(r => r.body.asString.map(b => (r.status, b)).orDie)

  private def as[A: JsonDecoder](response: (Status, String)): A =
    response._2.fromJson[A].fold(e => throw AssertionError(s"$e: ${response._1} ${response._2}"), identity)

  private def code(response: (Status, String)): Option[String] =
    response._2.fromJson[ApiError].toOption.map(_.code)

  private def values(unit: String, confidentiality: String = "Internal") =
    s"""{"owner":{"type":"PersonRef","userId":"${owner.value}"},
       |"organizationalUnit":{"type":"Text","value":"$unit"},
       |"validFrom":{"type":"Date","value":"2026-01-01"},
       |"confidentiality":{"type":"Selection","code":"$confidentiality"},
       |"language":{"type":"Text","value":"de"},
       |"retentionRule":{"type":"Text","value":"contract-10y"},
       |"containsPersonalData":{"type":"YesNo","value":false}}""".stripMargin

  private def cases(doc: UUID) =
    call(author, "GET", s"/api/v1/documents/$doc/workflow").map(as[List[WorkflowCaseView]])

  private def vote(user: UserId, task: TaskView) =
    call(user, "POST", s"/api/v1/workflow/tasks/${task.id}/votes", Some(CastVote("Approve", None).toJson))

  private def runPublicationJobs: ZIO[Env, Any, Int] =
    for
      db      <- ZIO.service[Db]
      service <- ZIO.service[DocumentService]
      jobs = PublicationJobs(db, service, PublicationScheduleConfig())
      _   <- jobs.enqueue(tenantId)
      ran <- JobRunner(db, jobs.handlers, WorkerConfig("publication-test")).runOnce
    yield ran

  private def runIndexing: ZIO[Env, Any, Unit] =
    for
      db    <- ZIO.service[Db]
      index <- ZIO.service[SearchIndex]
      jobs = SearchJobs(db, index, SearchSettings())
      _ <- OutboxRelay(db, jobs.deliver, WorkerConfig("hold-test-outbox", batchSize = 100)).runOnce
        .repeatWhile(_ > 0)
      _ <- JobRunner(db, jobs.handlers, WorkerConfig("hold-test-jobs", batchSize = 100)).runOnce
        .repeatWhile(_ > 0)
    yield ()

  /** A document whose version 1.0 is valid and indexed. */
  private def validDocument(title: String, unit: String = "Purchasing"): ZIO[Env, Any, DocumentView] =
    for
      s       <- setup.await
      created <- call(
        author,
        "POST",
        "/api/v1/documents",
        Some(
          s"""{"templateId":"${s.released.value}","templateVersion":1,"title":"$title",
             |"values":${values(unit)}}""".stripMargin
        )
      ).map(as[DocumentView])
      _           <- call(author, "POST", s"/api/v1/documents/${created.id}/submit")
      review      <- cases(created.id).map(_.last.tasks.find(_.status == "Open").get)
      afterReview <- vote(reviewer, review).map(as[WorkflowCaseView])
      _           <- vote(approver, afterReview.tasks.find(_.status == "Open").get)
      _           <- StubRendering.runPending(s.app)
      _           <- runPublicationJobs
      _           <- runIndexing
    yield created

  /** A confidential draft: visible to its author and owner only. */
  private def confidentialDraft(title: String): ZIO[Env, Any, DocumentView] =
    setup.await.flatMap(s =>
      call(
        author,
        "POST",
        "/api/v1/documents",
        Some(
          s"""{"templateId":"${s.released.value}","templateVersion":1,"title":"$title",
             |"values":${values("Board", "Confidential")}}""".stripMargin
        )
      ).map(as[DocumentView])
    )

  private def create(user: UserId, body: String) = call(user, "POST", "/api/v1/legal-holds", Some(body))

  private def hold(name: String, documents: List[UUID], reason: String = "Litigation pending") =
    val ids = documents.map(d => s""""$d"""").mkString(",")
    s"""{"name":"$name","reason":"$reason","matterReference":"LIT-1","documentIds":[$ids]}"""

  private def document(user: UserId, doc: UUID) =
    call(user, "GET", s"/api/v1/documents/$doc").map(as[DocumentView])

  private def release(user: UserId, hold: UUID, reason: String = "Settled") =
    call(user, "POST", s"/api/v1/legal-holds/$hold/release", Some(s"""{"reason":"$reason"}"""))

  private def auditActions(objectId: UUID): ZIO[Env, Any, List[(String, Option[String])]] =
    setup.await.flatMap(s =>
      s.app.transact(tenantId)(
        Db.sql(
          sql"""select action, reason from dms.audit_event where object_id = $objectId order by seq"""
            .query[(String, Option[String])]
            .run()
            .toList
        )
      )
    )

  def spec = suite("Legal holds over HTTP")(
    test("API documentation contains the legal hold endpoints") {
      val docs = sttp.tapir.docs.openapi
        .OpenAPIDocsInterpreter()
        .toOpenAPI(LegalHoldApi.endpoints.map(_.endpoint), "DMS Platform", "test")
      assertTrue(
        docs.paths.pathItems.contains("/api/v1/legal-holds"),
        docs.paths.pathItems.contains("/api/v1/legal-holds/{id}"),
        docs.paths.pathItems.contains("/api/v1/legal-holds/{id}/documents"),
        docs.paths.pathItems.contains("/api/v1/legal-holds/{id}/release")
      )
    },
    test(
      "a records manager puts a document under legal hold; the document view and the audit trail show it"
    ) {
      for
        doc     <- validDocument("Liefervertrag Nord")
        before  <- document(reader, doc.id)
        created <- create(records, hold("Müller GmbH", List(doc.id)))
        view = as[LegalHoldView](created)
        asReader  <- document(reader, doc.id)
        asRecords <- document(records, doc.id)
        single    <- call(records, "GET", s"/api/v1/legal-holds/${view.id}").map(as[LegalHoldView])
        listed    <- call(records, "GET", "/api/v1/legal-holds").map(as[List[LegalHoldView]])
        holdAudit <- auditActions(view.id)
        docAudit  <- auditActions(doc.id)
      yield assertTrue(
        !before.legalHold,
        created._1 == Status.Created,
        view.status == "Active",
        view.matterReference.contains("LIT-1"),
        view.documentCount == 1,
        view.addedDocumentIds.contains(List(doc.id)),
        asReader.legalHold,
        asReader.legalHolds.isEmpty,
        asRecords.legalHold,
        asRecords.legalHolds.exists(_.map(h => (h.id, h.name)) == List((view.id, "Müller GmbH"))),
        single.documents.exists(
          _.map(d => (d.documentId, d.title, d.addedVia, d.reason, d.addedBy)) ==
            List((doc.id, Some("Liefervertrag Nord"), "Document", "Litigation pending", records.value))
        ),
        listed.exists(_.id == view.id),
        holdAudit == List(("legal-hold.created", Some("Litigation pending"))),
        docAudit.contains(("document.legal-hold-set", Some("Litigation pending")))
      )
    },
    test("only records managers for the whole tenant manage holds; documents need their scope too") {
      for
        doc     <- validDocument("Liefervertrag Süd")
        byRead  <- create(reader, hold("Reader", List(doc.id)))
        byHr    <- create(recordsHr, hold("HR", Nil))
        list    <- call(reader, "GET", "/api/v1/legal-holds")
        created <- create(records, hold("Scope", Nil)).map(as[LegalHoldView])
        get     <- call(owner, "GET", s"/api/v1/legal-holds/${created.id}")
        add     <- call(
          author,
          "POST",
          s"/api/v1/legal-holds/${created.id}/documents",
          Some(s"""{"reason":"x","documentIds":["${doc.id}"]}""")
        )
        rel     <- release(approver, created.id)
        unknown <- call(
          records,
          "POST",
          s"/api/v1/legal-holds/${created.id}/documents",
          Some(s"""{"reason":"x","documentIds":["${UUID.randomUUID()}"]}""")
        )
        missing  <- call(records, "GET", s"/api/v1/legal-holds/${UUID.randomUUID()}")
        secret   <- confidentialDraft("Vergleichsentwurf")
        denied   <- create(records, hold("Confidential", List(doc.id, secret.id)))
        nothing  <- call(records, "GET", "/api/v1/legal-holds").map(as[List[LegalHoldView]])
        draft    <- create(records, hold("Draft", List(doc.id))).map(as[LegalHoldView])
        bySecret <- document(author, secret.id)
      yield assertTrue(
        byRead._1 == Status.Forbidden,
        byHr._1 == Status.Forbidden,
        list._1 == Status.Forbidden,
        get._1 == Status.Forbidden,
        add._1 == Status.Forbidden,
        rel._1 == Status.Forbidden,
        unknown._1 == Status.NotFound,
        missing._1 == Status.NotFound,
        // confidential and outside the records manager's groups: nothing is stored
        denied._1 == Status.Forbidden,
        !nothing.exists(_.name == "Confidential"),
        !bySecret.legalHold,
        draft.documentCount == 1
      )
    },
    test("setting, adding and releasing require a reason (422)") {
      for
        doc      <- validDocument("Liefervertrag West")
        noReason <- create(records, s"""{"name":"No reason","documentIds":["${doc.id}"]}""")
        blank    <- create(records, hold("Blank", List(doc.id), reason = " "))
        noName   <- create(records, s"""{"reason":"Litigation","documentIds":["${doc.id}"]}""")
        created  <- create(records, hold("Reasons", Nil)).map(as[LegalHoldView])
        addNoWhy <- call(
          records,
          "POST",
          s"/api/v1/legal-holds/${created.id}/documents",
          Some(s"""{"documentIds":["${doc.id}"]}""")
        )
        addNone <- call(
          records,
          "POST",
          s"/api/v1/legal-holds/${created.id}/documents",
          Some("""{"reason":"Nothing"}""")
        )
        relNoWhy  <- call(records, "POST", s"/api/v1/legal-holds/${created.id}/release", Some("{}"))
        stillFree <- document(records, doc.id)
      yield assertTrue(
        noReason._1 == Status.UnprocessableEntity,
        code(noReason).contains("invalid"),
        blank._1 == Status.UnprocessableEntity,
        noName._1 == Status.UnprocessableEntity,
        addNoWhy._1 == Status.UnprocessableEntity,
        addNone._1 == Status.UnprocessableEntity,
        relNoWhy._1 == Status.UnprocessableEntity,
        !stillFree.legalHold
      )
    },
    test("a confirmed search result is put under legal hold: only with the confirmed hit count") {
      for
        a      <- validDocument("Rahmenvertrag Zulieferer Alpha")
        b      <- validDocument("Rahmenvertrag Zulieferer Beta")
        other  <- validDocument("Mietvertrag Halle")
        result <- call(records, "GET", "/api/v1/search?q=Zulieferer").map(as[SearchResultView])
        filter = (expected: String) =>
          s"""{"name":"Zulieferer","reason":"Dispute with suppliers","searchFilter":{"q":"Zulieferer"$expected}}"""
        unconfirmed <- create(records, filter(""))
        changed     <- create(records, filter(""","expectedCount":3"""))
        confirmed   <- create(records, filter(s""","expectedCount":${result.total}"""))
        view = as[LegalHoldView](confirmed)
        single <- call(records, "GET", s"/api/v1/legal-holds/${view.id}").map(as[LegalHoldView])
        held   <- ZIO.foreach(List(a, b, other))(d => document(reader, d.id).map(_.legalHold))
        // Adding the same search again to the hold adds nothing new.
        again <- call(
          records,
          "POST",
          s"/api/v1/legal-holds/${view.id}/documents",
          Some(s"""{"reason":"Repeat","searchFilter":{"q":"Zulieferer","expectedCount":${result.total}}}""")
        ).map(as[LegalHoldView])
      yield assertTrue(
        result.total == 2L,
        unconfirmed._1 == Status.UnprocessableEntity,
        changed._1 == Status.Conflict,
        code(changed).contains("result_changed"),
        confirmed._1 == Status.Created,
        view.addedDocumentIds.exists(_.toSet == Set(a.id, b.id)),
        single.documents.exists(_.forall(_.addedVia == "Search")),
        held == List(true, true, false),
        again.addedDocumentIds.contains(Nil),
        again.documentCount == 2
      )
    },
    test("a document under several holds stays held until the last one is released") {
      for
        doc    <- validDocument("Liefervertrag Ost")
        first  <- create(records, hold("First", List(doc.id))).map(as[LegalHoldView])
        second <- create(records, hold("Second", Nil)).map(as[LegalHoldView])
        added  <- call(
          records,
          "POST",
          s"/api/v1/legal-holds/${second.id}/documents",
          Some(s"""{"reason":"Same facts","documentIds":["${doc.id}"]}""")
        ).map(as[LegalHoldView])
        both     <- document(records, doc.id)
        released <- release(records, first.id)
        afterOne <- document(records, doc.id)
        twice    <- release(records, first.id)
        addLate  <- call(
          records,
          "POST",
          s"/api/v1/legal-holds/${first.id}/documents",
          Some(s"""{"reason":"Late","documentIds":["${doc.id}"]}""")
        )
        _         <- release(records, second.id, "Proceedings closed")
        afterBoth <- document(reader, doc.id)
        active    <- call(records, "GET", "/api/v1/legal-holds?status=active").map(as[List[LegalHoldView]])
        done      <- call(records, "GET", "/api/v1/legal-holds?status=released").map(as[List[LegalHoldView]])
        badStatus <- call(records, "GET", "/api/v1/legal-holds?status=open")
        docAudit  <- auditActions(doc.id)
      yield assertTrue(
        added.addedDocumentIds.contains(List(doc.id)),
        both.legalHolds.exists(_.map(_.id).toSet == Set(first.id, second.id)),
        released._1 == Status.Ok,
        as[LegalHoldView](released).status == "Released",
        as[LegalHoldView](released).releaseReason.contains("Settled"),
        afterOne.legalHold,
        afterOne.legalHolds.exists(_.map(_.id) == List(second.id)),
        twice._1 == Status.Conflict,
        code(twice).contains("released"),
        addLate._1 == Status.Conflict,
        !afterBoth.legalHold,
        !active.exists(h => h.id == first.id || h.id == second.id),
        done.map(_.id).toSet.contains(first.id),
        done.map(_.id).toSet.contains(second.id),
        badStatus._1 == Status.BadRequest,
        docAudit.collect { case ("document.legal-hold-released", reason) => reason } ==
          List(Some("Settled"), Some("Proceedings closed"))
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(10.minutes)
