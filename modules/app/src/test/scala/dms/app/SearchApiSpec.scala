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
import dms.archive.{RetentionRuleInput, RetentionTrigger}
import dms.archive.core.DestructionService
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

import java.time.{Instant, LocalDate}
import java.util.{Date, UUID}
import javax.sql.DataSource

/** Search over published documents (DMS-51, DMS-52) end to end: documents published through the real
  * workflow, indexed through the outbox and the job queue into OpenSearch, searched over HTTP by callers with
  * different role assignments; PostgreSQL and OpenSearch in Testcontainers.
  */
object SearchApiSpec extends ZIOSpecDefault:

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
  private val hrReader  = UserId("hr-reader-1")
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

  final case class Setup(app: Db, released: TemplateId, owner: Db)

  private val setup = Unsafe.unsafe(implicit u => Promise.unsafe.make[Nothing, Setup](FiberId.None))

  private val content = TemplateContent(
    List(
      Section(
        SectionId("rules"),
        Some("Verhaltensregeln"),
        locked = false,
        List(
          Block.Paragraph(
            List(Inline.Text("Alle Mitarbeiter beachten die Verträge und <Richtlinien> & Weisungen."))
          ),
          Block.Paragraph(List(Inline.Text("Gültig ab "), Inline.Placeholder("validFrom")))
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
            _ <- RoleAssignments.Postgres.grant(hrReader, RoleAssignment(Role.Reader, Some("HR")), admin)
            // Access groups (DMS-101): reviewer and approver may see the confidential test documents.
            _   <- Groups.Postgres.create("board", "Board", admin)
            _   <- Groups.Postgres.create("hr", "HR", admin)
            _   <- Groups.Postgres.addMember("board", reviewer, admin)
            _   <- Groups.Postgres.addMember("board", approver, admin)
            cls <- classes.createClass("policy", None, ClassDraft("Policy", None, Nil), admin)
            draft = TemplateDraft("Code of conduct", None, cls.classId.value, 1, content)
            t <- templates.create(draft, designer)
            _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Testing, designer)
            _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Released, designer2)
          yield t.templateId
        )
        .mapError(e => RuntimeException(e.toString))
        .orDie
      _ <- setup.succeed(Setup(app, released, schemaOwner))
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

  private type Env = DocumentApi.Env & WorkflowApi.Env & RenditionSource & AuditTrail & SearchIndex

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
      OpenSearchContainer.layer("dms-app-test"),
      ZLayer.succeed(SearchSettings()),
      OpenSearch.layer,
      SearchDocuments.layer,
      SearchIndex.layer
    )

  private def token(user: UserId, signedInSecondsAgo: Long): String =
    val now     = Instant.now()
    val builder = JWTClaimsSet
      .Builder()
      .issuer(issuer)
      .audience(audience)
      .subject(user.value)
      .issueTime(Date.from(now.minusSeconds(10)))
      .expirationTime(Date.from(now.plusSeconds(300)))
      .claim(TokenVerifier.OrganizationClaim, orgId)
      .claim("auth_time", now.minusSeconds(signedInSecondsAgo).getEpochSecond)
    val jwt = SignedJWT(
      JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID).`type`(JOSEObjectType.JWT).build(),
      builder.build()
    )
    jwt.sign(RSASSASigner(signingKey))
    jwt.serialize()

  private val routes = ZioHttpInterpreter().toHttp(
    DocumentApi.endpoints.map(_.widen[Env]) ++ WorkflowApi.endpoints.map(_.widen[Env]) ++
      SearchApi.endpoints.map(_.widen[Env])
  )

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
      case _      => Request.get(url).addHeaders(headers)
    ZIO.scoped(routes.runZIO(request)).flatMap(r => r.body.asString.map(b => (r.status, b)).orDie)

  private def as[A: JsonDecoder](response: (Status, String)): A =
    response._2.fromJson[A].fold(e => throw AssertionError(s"$e: ${response._1} ${response._2}"), identity)

  private def search(user: UserId, params: String) =
    call(user, "GET", s"/api/v1/search?$params")

  private def results(user: UserId, params: String) =
    search(user, params).map(as[SearchResultView])

  private def values(unit: String, confidentiality: String, validFrom: LocalDate, groups: List[String]) =
    val allowed = groups.map(g => s"""{"type":"Text","value":"$g"}""").mkString(",")
    s"""{"owner":{"type":"PersonRef","userId":"${owner.value}"},
       |"allowedGroups":{"type":"Many","values":[$allowed]},
       |"organizationalUnit":{"type":"Text","value":"$unit"},
       |"validFrom":{"type":"Date","value":"$validFrom"},
       |"confidentiality":{"type":"Selection","code":"$confidentiality"},
       |"language":{"type":"Text","value":"de"},
       |"retentionRule":{"type":"Text","value":"policy-10y"},
       |"containsPersonalData":{"type":"YesNo","value":false}}""".stripMargin

  private val past = LocalDate.of(2026, 1, 1)

  private def cases(doc: UUID) =
    call(author, "GET", s"/api/v1/documents/$doc/workflow").map(as[List[WorkflowCaseView]])

  /** Reviewer and approver are members of the group `board`, which may see the confidential test document. */
  private def vote(user: UserId, task: TaskView) =
    call(user, "POST", s"/api/v1/workflow/tasks/${task.id}/votes", Some(CastVote("Approve", None).toJson), 30)

  private def runPublicationJobs: ZIO[Env, Any, Int] =
    for
      db      <- ZIO.service[Db]
      service <- ZIO.service[DocumentService]
      jobs = PublicationJobs(db, service, PublicationScheduleConfig())
      _   <- jobs.enqueue(tenantId)
      ran <- JobRunner(db, jobs.handlers, WorkerConfig("publication-test")).runOnce
    yield ran

  /** Delivers the outbox events and runs the search jobs, the way the background processing does. */
  private def runIndexing: ZIO[Env, Any, Unit] =
    for
      db    <- ZIO.service[Db]
      index <- ZIO.service[SearchIndex]
      jobs = SearchJobs(db, index, SearchSettings())
      _ <- OutboxRelay(db, jobs.deliver, WorkerConfig("search-test-outbox", batchSize = 100)).runOnce
        .repeatWhile(_ > 0)
      _ <- JobRunner(db, jobs.handlers, WorkerConfig("search-test-jobs", batchSize = 100)).runOnce
        .repeatWhile(_ > 0)
    yield ()

  private def approveDefault(doc: UUID): ZIO[Env, Any, Unit] =
    for
      _           <- call(author, "POST", s"/api/v1/documents/$doc/submit")
      review      <- cases(doc).map(_.last.tasks.find(_.status == "Open").get)
      afterReview <- vote(reviewer, review).map(as[WorkflowCaseView])
      _           <- vote(approver, afterReview.tasks.find(_.status == "Open").get)
      _           <- setup.await.flatMap(s => StubRendering.runPending(s.app))
    yield ()

  /** A document whose version 1.0 is valid and indexed. */
  private def validDocument(
      title: String,
      unit: String,
      confidentiality: String = "Internal",
      groups: List[String] = Nil
  ) =
    for
      s       <- setup.await
      created <- call(
        author,
        "POST",
        "/api/v1/documents",
        Some(
          s"""{"templateId":"${s.released.value}","templateVersion":1,"title":"$title",
             |"values":${values(unit, confidentiality, past, groups)}}""".stripMargin
        )
      ).map(as[DocumentView])
      _ <- approveDefault(created.id)
      _ <- runPublicationJobs
      _ <- runIndexing
    yield created

  private def grant(user: UserId, assignment: RoleAssignment) =
    setup.await.flatMap(s =>
      s.app.transact(tenantId)(RoleAssignments.Postgres.grant(user, assignment, admin))
    )

  private def join(user: UserId, group: String) =
    setup.await.flatMap(s => s.app.transact(tenantId)(Groups.Postgres.addMember(group, user, admin)))

  private def leave(user: UserId, group: String) =
    setup.await.flatMap(s =>
      s.app.transact(tenantId)(Groups.Postgres.removeMember(group, user, admin, Instant.now()))
    )

  private def revoke(id: UUID) =
    setup.await.flatMap(s => s.app.transact(tenantId)(RoleAssignments.Postgres.revoke(id, Instant.now())))

  def spec = suite("Search over HTTP")(
    test("API documentation contains the search endpoints") {
      val docs = sttp.tapir.docs.openapi
        .OpenAPIDocsInterpreter()
        .toOpenAPI(SearchApi.endpoints.map(_.endpoint), "DMS Platform", "test")
      assertTrue(
        docs.paths.pathItems.contains("/api/v1/search"),
        docs.paths.pathItems.contains("/api/v1/admin/search/rebuild")
      )
    },
    test("a published version is indexed through the outbox and found with snippet and facets") {
      for
        doc    <- validDocument("Verhaltenskodex Compliance", "Compliance")
        found  <- results(reader, "q=Vertrag")
        listed <- results(reader, "q=&class=policy&lang=de")
        byUnit <- results(reader, "q=Compliance")
        raw    <- search(reader, "q=Vertrag")
      yield assertTrue(
        found.total == 1L,
        found.hits.map(h => (h.documentId, h.title, h.versionLabel, h.status, h.classPath, h.validFrom)) ==
          List((doc.id, "Verhaltenskodex Compliance", "1.0", "Published", List("policy"), Some(past))),
        found.hits.head.snippet.contains("<mark>Verträge</mark>"),
        found.hits.head.snippet.contains("<Richtlinien> & Weisungen"),
        found.facets.`class` == List(FacetCountView("policy", 1)),
        found.facets.status == List(FacetCountView("Published", 1)),
        found.facets.language == List(FacetCountView("de", 1)),
        listed.total == 1L,
        listed.hits.head.score.isEmpty,
        byUnit.total == 1L,
        raw._2.contains(""""facets":{"class":[""")
      )
    },
    test("only readable versions count; role assignments take effect on the next search") {
      for
        _           <- validDocument("Datenschutzrichtlinie", "Legal")
        hrBefore    <- results(hrReader, "q=Datenschutzrichtlinie")
        adminSearch <- results(admin, "q=Datenschutzrichtlinie")
        granted     <- grant(hrReader, RoleAssignment(Role.Reader, Some("Legal")))
        hrGranted   <- results(hrReader, "q=Datenschutzrichtlinie")
        _           <- revoke(granted)
        hrRevoked   <- results(hrReader, "q=Datenschutzrichtlinie")
      yield assertTrue(
        hrBefore.total == 0L,
        hrBefore.hits.isEmpty,
        hrBefore.facets.`class`.isEmpty,
        hrBefore.facets.status.isEmpty,
        adminSearch.total == 0L,
        hrGranted.total == 1L,
        hrRevoked.total == 0L
      )
    },
    test("confidential versions only for author, owner and members of allowed groups, at query time") {
      for
        _          <- validDocument("Vorstandsbeschluss", "Compliance", "Confidential", List("board"))
        byReader   <- results(reader, "q=Vorstandsbeschluss")
        byOwner    <- results(owner, "q=Vorstandsbeschluss")
        byAuthor   <- results(author, "q=Vorstandsbeschluss")
        _          <- join(reader, "hr")
        otherGroup <- results(reader, "q=Vorstandsbeschluss")
        _          <- join(reader, "board")
        withGroups <- results(reader, "q=Vorstandsbeschluss")
        _          <- leave(reader, "board")
        afterLeave <- results(reader, "q=Vorstandsbeschluss")
        _          <- leave(reader, "hr")
      yield assertTrue(
        byReader.total == 0L,
        byOwner.total == 1L,
        byAuthor.total == 1L,
        otherGroup.total == 0L,
        withGroups.total == 1L,
        afterLeave.total == 0L
      )
    },
    test("a new version supersedes the old one in the index; the history stays searchable") {
      for
        doc <- validDocument("Reisekostenrichtlinie", "Compliance")
        _   <- call(
          author,
          "POST",
          s"/api/v1/documents/${doc.id}/versions",
          Some("""{"changeKind":"Major","reason":"Content revised"}""")
        )
        _          <- approveDefault(doc.id)
        _          <- runPublicationJobs
        _          <- runIndexing
        current    <- results(reader, "q=Reisekostenrichtlinie")
        superseded <- results(reader, "q=Reisekostenrichtlinie&status=Superseded")
        both       <- results(reader, "q=Reisekostenrichtlinie&status=Published,Superseded")
      yield assertTrue(
        current.hits.map(h => (h.versionLabel, h.status)) == List(("2.0", "Published")),
        current.facets.status.toSet == Set(FacetCountView("Published", 1), FacetCountView("Superseded", 1)),
        superseded.hits.map(h => (h.versionLabel, h.status)) == List(("1.0", "Superseded")),
        both.total == 2L
      )
    },
    test("tenant administrators rebuild the index; results stay the same") {
      for
        before    <- results(reader, "q=&status=Published,ReviewDue,Superseded&size=100")
        denied    <- call(reader, "POST", "/api/v1/admin/search/rebuild")
        rebuilt   <- call(admin, "POST", "/api/v1/admin/search/rebuild").map(as[SearchRebuildView])
        after     <- results(reader, "q=&status=Published,ReviewDue,Superseded&size=100")
        badStatus <- search(reader, "status=Draft")
        badSize   <- search(reader, "size=500")
      yield assertTrue(
        // The confidential version is not readable for the reader.
        before.total == 4L,
        denied._1 == Status.Forbidden,
        rebuilt.documents == 4,
        rebuilt.versions == 5,
        after.total == 4L,
        after.hits.map(_.versionId).toSet == before.hits.map(_.versionId).toSet,
        badStatus._1 == Status.BadRequest,
        badSize._1 == Status.BadRequest
      )
    },
    test("a destroyed version leaves the index; the valid version stays (DMS-82)") {
      for
        s     <- setup.await
        audit <- ZIO.service[AuditTrail]
        service = DestructionService(audit)
        before <- results(reader, "q=Reisekostenrichtlinie&status=Superseded")
        old = before.hits.head.versionId
        // Approved in 2010, so that a retention of ten years from approval has ended.
        _ <- s.owner.transactSystem(Db.sql {
          val _ = sql"set local session_replication_role = replica".update.run()
          sql"update dms.document_version set released_at = '2010-01-01T00:00:00Z' where id = $old".update
            .run()
        })
        _ <- s.app.transact(tenantId)(
          service.saveRule(
            "policy-10y",
            RetentionRuleInput("Policies", 120, RetentionTrigger.Approval, fromYearEnd = true),
            admin
          ) *>
            service
              .propose(List(old), "Retention ended", UserId("records-1"))
              .flatMap(p => service.approve(p.id, "Confirmed", UserId("records-2")))
        )
        _          <- runIndexing
        superseded <- results(reader, "q=Reisekostenrichtlinie&status=Superseded")
        current    <- results(reader, "q=Reisekostenrichtlinie")
      yield assertTrue(
        before.hits.map(_.versionLabel) == List("1.0"),
        superseded.hits.isEmpty,
        current.hits.map(h => (h.versionLabel, h.status)) == List(("2.0", "Published"))
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(10.minutes)
