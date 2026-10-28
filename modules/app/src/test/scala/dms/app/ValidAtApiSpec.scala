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
import dms.identity.*
import dms.integration.{JobRunner, WorkerConfig}
import dms.kernel.{TemplateId, TenantId, UserId}
import dms.rendering.core.RenditionSource
import dms.templates.*
import dms.templates.core.{TemplateDraft, TemplateService}
import dms.workflow.NewDeputyship
import dms.workflow.core.{ApprovalWorkflow, Deputyships, FlowableEngine, WorkflowConfig}
import org.testcontainers.utility.DockerImageName
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.*
import zio.http.{Body, Header, Headers, MediaType, Request, Status, URL}
import zio.json.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.time.{Instant, LocalDate, ZoneId}
import java.util.{Date, UUID}
import javax.sql.DataSource

/** The validity history (US-09.1, DMS-53) end to end: list and CSV export over HTTP with the real documents
  * and workflow modules (approval by a deputy, publication job) against a Testcontainers database.
  */
object ValidAtApiSpec extends ZIOSpecDefault:

  private val issuer   = "http://localhost:8085"
  private val audience = "project-1"
  private val orgId    = "org-1"
  private val tenantId = TenantId.random()
  private val admin    = UserId("admin-1")
  private val author   = UserId("author-1")
  private val owner    = UserId("owner-1")
  private val reviewer = UserId("reviewer-1")
  private val approver = UserId("approver-1")
  private val deputy   = UserId("deputy-1")
  private val reader   = UserId("reader-1")
  private val auditor  = UserId("auditor-1")
  private val outsider = UserId("outsider-1")
  private val designer = UserId("designer-1")

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
        SectionId("rules"),
        Some("Rules"),
        locked = false,
        List(Block.Paragraph(List(Inline.Text("Rules apply."))))
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
            // An approver of another class: takes part in policies only as the approver's deputy.
            _ <- RoleAssignments.Postgres
              .grant(deputy, RoleAssignment(Role.Approver, documentClass = Some("contract")), admin)
            _ <- RoleAssignments.Postgres.grant(reader, RoleAssignment(Role.Reader), admin)
            _ <- RoleAssignments.Postgres.grant(auditor, RoleAssignment(Role.Auditor), admin)
            _ <- RoleAssignments.Postgres
              .grant(outsider, RoleAssignment(Role.Reader, documentClass = Some("contract")), admin)
            // The reviewer and the approver may see the confidential board rules (DMS-101).
            _   <- Groups.Postgres.create("board", "Board", admin)
            _   <- Groups.Postgres.addMember("board", reviewer, admin)
            _   <- Groups.Postgres.addMember("board", approver, admin)
            cls <- classes.createClass("policy", None, ClassDraft("Policy", None, Nil), admin)
            draft = TemplateDraft("Policy", None, cls.classId.value, 1, content)
            t <- templates.create(draft, designer)
            _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Testing, designer)
            _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Released, UserId("designer-2"))
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

  private type Env = DocumentApi.Env & WorkflowApi.Env & Deputyships & RenditionSource & AuditTrail

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
      DocumentService.layer,
      FlowableEngine.layer,
      WorkflowDocuments.layer,
      RoleDirectory.layer,
      WorkflowExtraReview.layer,
      ZLayer.succeed(WorkflowConfig.default),
      WorkflowRendition.layer,
      ApprovalWorkflow.layer,
      Deputyships.layer,
      DocumentRenditions.layer
    )

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

  // As in Main: the valid-at endpoints come before `/documents/{id}`.
  private val routes = ZioHttpInterpreter().toHttp(
    ValidAtApi.endpoints ++ DocumentApi.endpoints ++ WorkflowApi.endpoints
  )

  private def send(
      user: UserId,
      method: String,
      path: String,
      body: Option[String],
      signedInSecondsAgo: Long
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
    ZIO.scoped(routes.runZIO(request))

  private def call(
      user: UserId,
      method: String,
      path: String,
      body: Option[String] = None,
      signedInSecondsAgo: Long = 3600
  ) =
    send(user, method, path, body, signedInSecondsAgo).flatMap(r =>
      r.body.asString.map(b => (r.status, b)).orDie
    )

  private def as[A: JsonDecoder](response: (Status, String)): A =
    response._2.fromJson[A].fold(e => throw AssertionError(s"$e: ${response._1} ${response._2}"), identity)

  private val values =
    s"""{"owner":{"type":"PersonRef","userId":"${owner.value}"},
       |"organizationalUnit":{"type":"Text","value":"Compliance"},
       |"validFrom":{"type":"Date","value":"2026-01-01"},
       |"confidentiality":{"type":"Selection","code":"Internal"},
       |"language":{"type":"Text","value":"de"},
       |"retentionRule":{"type":"Text","value":"policy-10y"},
       |"containsPersonalData":{"type":"YesNo","value":false}}""".stripMargin

  private def renderPending: ZIO[Env, DbError, Int] =
    setup.await.flatMap(s => StubRendering.runPending(s.app))

  private def cases(doc: UUID) =
    call(author, "GET", s"/api/v1/documents/$doc/workflow").map(as[List[WorkflowCaseView]])

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

  /** Submits the open draft, passes review and lets `approving` approve it; then the job publishes it. */
  private def approveAndPublish(doc: UUID, approving: UserId): ZIO[Env, Any, Unit] =
    for
      _           <- call(author, "POST", s"/api/v1/documents/$doc/submit")
      review      <- cases(doc).map(_.last.tasks.find(_.status == "Open").get)
      afterReview <- vote(reviewer, review).map(as[WorkflowCaseView])
      voted       <- vote(approving, afterReview.tasks.find(_.status == "Open").get)
      _           <- ZIO.unless(voted._1 == Status.Ok)(ZIO.fail(AssertionError(s"approval failed: $voted")))
      _           <- renderPending
      _           <- runPublicationJobs
    yield ()

  /** Confidential values: only author, owner and the members of group `board` see the version. */
  private val confidentialValues =
    values
      .replace("\"Internal\"", "\"Confidential\"")
      .stripSuffix("}") + ""","allowedGroups":{"type":"Many","values":[{"type":"Text","value":"board"}]}}"""

  private def create(title: String, values: String = values): ZIO[Env, Any, DocumentView] =
    for
      s       <- setup.await
      created <- call(
        author,
        "POST",
        "/api/v1/documents",
        Some(
          s"""{"templateId":"${s.released.value}","templateVersion":1,"title":${title.toJson},
             |"values":$values}""".stripMargin
        )
      ).map(as[DocumentView])
      _ <- approveAndPublish(created.id, approver)
    yield created

  private val berlin = ZoneId.of("Europe/Berlin")

  private def validAt(user: UserId, query: String) =
    call(user, "GET", s"/api/v1/documents/valid-at?$query")

  final case class Fixture(code: UUID, formula: UUID, board: UUID, firstVersion: UUID, beforeSwitch: Instant)

  private val formulaTitle = "=SUM(1;2) \"Rules\""

  private val fixtureCache = Unsafe.unsafe(implicit u => Ref.unsafe.make(Option.empty[Fixture]))

  /** Built by the first test that needs it; the others share it. */
  private val fixture: ZIO[Env, Any, Fixture] =
    fixtureCache.get.someOrElseZIO(build.tap(f => fixtureCache.set(Some(f))))

  /** Code of conduct 1.0 (approved by the approver), replaced by 2.0 (approved by the deputy on behalf of the
    * approver); a document whose title looks like a formula; a confidential document of the owner.
    */
  private def build: ZIO[Env, Any, Fixture] =
    for
      s     <- setup.await
      code  <- create("Code of conduct")
      first <- call(reader, "GET", s"/api/v1/documents/${code.id}").map(as[DocumentView])
      _     <- ZIO.sleep(10.millis)
      beforeSwitch = Instant.now()
      _ <- s.app
        .transact(tenantId)(
          ZIO.serviceWithZIO[Deputyships](
            _.create(
              NewDeputyship(
                approver,
                deputy,
                None,
                Instant.now().plusSeconds(86400),
                Nil,
                None,
                Some("Vacation")
              ),
              approver
            )
          )
        )
      _ <- call(author, "POST", s"/api/v1/documents/${code.id}/versions", Some("""{"changeKind":"Major"}"""))
      _ <- approveAndPublish(code.id, deputy)
      formula <- create(formulaTitle)
      // Confidential from the start: reviewed and approved by members of its access group (DMS-101).
      board <- create("Board rules", confidentialValues)
    yield Fixture(code.id, formula.id, board.id, first.current.id, beforeSwitch)

  def spec = suite("Validity history API")(
    test("per document the version in force, with approver and the approver a deputy acted for") {
      for
        f       <- fixture
        today   <- ZIO.succeed(LocalDate.now(berlin))
        earlier <- validAt(reader, s"at=${f.beforeSwitch}").map(as[ValidAtView])
        now     <- validAt(reader, s"date=$today").map(as[ValidAtView])
        policy  <- validAt(reader, s"date=$today&class=policy").map(as[ValidAtView])
        other   <- validAt(reader, s"date=$today&class=contract").map(as[ValidAtView])
        code = now.items.find(_.documentId == f.code)
      yield assertTrue(
        earlier.items.map(i => (i.documentId, i.versionId, i.versionLabel, i.status)) ==
          List((f.code, f.firstVersion, "1.0", "Superseded")),
        earlier.items.head.approvedBy.contains(approver.value),
        earlier.items.head.actingFor.isEmpty,
        earlier.items.head.validUntil.exists(_.isAfter(f.beforeSwitch)),
        earlier.at == f.beforeSwitch,
        now.date.contains(today),
        now.timeZone == "Europe/Berlin",
        code.map(i => (i.versionLabel, i.status)).contains(("2.0", "Published")),
        code.exists(_.approvedBy.contains(deputy.value)),
        code.exists(_.actingFor.contains(approver.value)),
        code.exists(_.approvedAt.isDefined),
        code.exists(_.validUntil.isEmpty),
        code.exists(_.classPath == List("policy")),
        code.exists(_.validFrom.contains(LocalDate.of(2026, 1, 1))),
        now.items.exists(_.documentId == f.formula),
        policy.items.map(_.versionId) == now.items.map(_.versionId),
        other.items.isEmpty
      )
    },
    test("only versions the caller may read: confidentiality, class scope and business roles apply") {
      for
        f        <- fixture
        today    <- ZIO.succeed(LocalDate.now(berlin))
        byReader <- validAt(reader, s"date=$today").map(as[ValidAtView])
        byOwner  <- validAt(owner, s"date=$today").map(as[ValidAtView])
        byAudit  <- validAt(auditor, s"date=$today").map(as[ValidAtView])
        byOther  <- validAt(outsider, s"date=$today").map(as[ValidAtView])
        byAdmin  <- validAt(admin, s"date=$today").map(as[ValidAtView])
        history  <- validAt(auditor, s"at=${f.beforeSwitch}").map(as[ValidAtView])
      yield assertTrue(
        !byReader.items.exists(_.documentId == f.board),
        byOwner.items.exists(_.documentId == f.board),
        !byAudit.items.exists(_.documentId == f.board),
        byAudit.items.exists(_.documentId == f.code),
        history.items.exists(_.versionId == f.firstVersion),
        byOther.items.isEmpty,
        byAdmin.items.isEmpty
      )
    },
    test("exactly one of date and at; malformed values are rejected") {
      for
        neither <- validAt(reader, "class=policy")
        both    <- validAt(reader, s"date=2027-01-15&at=${Instant.now()}")
        bad     <- validAt(reader, "date=15.01.2027")
        csv     <- call(reader, "GET", "/api/v1/documents/valid-at/export.csv")
      yield assertTrue(
        neither._1 == Status.UnprocessableEntity,
        both._1 == Status.UnprocessableEntity,
        bad._1 == Status.BadRequest,
        csv._1 == Status.UnprocessableEntity
      )
    },
    test("the CSV export opens in spreadsheets: UTF-8 with BOM, semicolons, CRLF, formulas neutralized") {
      for
        _        <- fixture
        today    <- ZIO.succeed(LocalDate.now(berlin))
        response <- send(auditor, "GET", s"/api/v1/documents/valid-at/export.csv?date=$today", None, 3600)
        bytes    <- response.body.asArray.orDie
        text  = String(bytes, StandardCharsets.UTF_8)
        lines = text.stripPrefix("﻿").split("\r\n", -1).toList
        rows  = lines.drop(1).filter(_.nonEmpty)
      yield assertTrue(
        response.status == Status.Ok,
        response.headers.get("Content-Type").exists(_.toLowerCase.startsWith("text/csv")),
        response.headers.get("Content-Type").exists(_.toLowerCase.contains("charset=utf-8")),
        response.headers
          .get("Content-Disposition")
          .contains(s"""attachment; filename="valid-at-$today.csv""""),
        bytes.take(3).toList == List(0xef.toByte, 0xbb.toByte, 0xbf.toByte),
        text.endsWith("\r\n"),
        lines.head == "Document id;Title;Class;Scope;Version;Status;Valid from (Europe/Berlin);" +
          "Valid until (Europe/Berlin);Approver;On behalf of;Approved at (Europe/Berlin)",
        rows.exists(r =>
          r.contains(";Code of conduct;policy;;2.0;Published;") &&
            r.contains(s";${deputy.value};${approver.value};")
        ),
        rows.exists(_.contains(";\"'=SUM(1;2) \"\"Rules\"\"\";policy;;1.0;Published;")),
        rows.forall(_.split(";", -1).length >= 11),
        !rows.exists(_.contains("Board rules"))
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
