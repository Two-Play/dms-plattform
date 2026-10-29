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
import dms.documents.*
import dms.documents.LifecycleEvent.*
import dms.documents.core.*
import dms.documents.metadata.MetadataValue
import dms.identity.*
import dms.kernel.{DocumentId, TemplateId, TenantId, UserId}
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

import java.time.{Instant, LocalDate, ZoneId}
import java.util.{Date, UUID}
import javax.sql.DataSource

/** Acknowledgement overview for owners, reminders and recipients per document class (DMS-100, ADR 0048) end
  * to end: HTTP endpoints, documents published through the documents service, the reminder job and the audit
  * trail and outbox in a Testcontainers database.
  *
  * Population: `ownerA`, `ownerB` (owners), `officer` (compliance officer), `hr1`, `hr2` (readers for HR),
  * `fin` (reader for Finance), `everyone` (reader), `boardMember` (reader, member of the access group
  * `board`), `admin` (tenant administrator). Author and approver hold no role.
  *
  * The class `policy` sets recipients "unit HR or group board" with reminders every 7 days; its subclass
  * `hr-policy` inherits them; `general` has no settings (every reader, the installation's 14 days).
  * Documents: `hrPolicy` (hr-policy, unit HR, owner ownerA; readers ownerA, ownerB, officer, hr1, hr2,
  * everyone, boardMember; recipients hr1, hr2, boardMember; hr1 acknowledged), `general` (general, unit
  * Finance, owner ownerB; recipients are its six readers ownerA, ownerB, officer, fin, everyone,
  * boardMember).
  */
object AcknowledgementOverviewApiSpec extends ZIOSpecDefault:

  private val issuer   = "http://localhost:8085"
  private val audience = "project-1"
  private val orgId    = "org-1"
  private val tenantId = TenantId.random()

  private val admin       = UserId("admin")
  private val author      = UserId("author")
  private val approver    = UserId("approver")
  private val ownerA      = UserId("owner-a")
  private val ownerB      = UserId("owner-b")
  private val officer     = UserId("officer")
  private val hr1         = UserId("reader-hr-1")
  private val hr2         = UserId("reader-hr-2")
  private val fin         = UserId("reader-fin")
  private val everyone    = UserId("reader-all")
  private val boardMember = UserId("board-member")

  private val names = Map(hr1 -> "Hanna Hansen", hr2 -> "Hugo Heller", boardMember -> "Berta Brandt")

  private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("key-1").generate()

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  final case class Fixture(
      policyClass: UUID,
      generalClass: UUID,
      hrPolicy: DocumentVersion,
      general: DocumentVersion
  )

  final case class Setup(app: Db, owner: Db, fixture: Fixture)

  private val setup = Unsafe.unsafe(implicit u => Promise.unsafe.make[Nothing, Setup](FiberId.None))

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

  private def values(owner: UserId, unit: String): Map[String, FieldEntry] =
    Map(
      "owner"                -> MetadataValue.PersonRef(owner.value),
      "organizationalUnit"   -> MetadataValue.Text(unit),
      "validFrom"            -> MetadataValue.Date(today.minusDays(30)),
      "confidentiality"      -> MetadataValue.Selection("Internal"),
      "language"             -> MetadataValue.Text("de"),
      "retentionRule"        -> MetadataValue.Text("policy-10y"),
      "containsPersonalData" -> MetadataValue.YesNo(false)
    ).map((k, v) => k -> FieldEntry(v, ValueOrigin.Manual))

  private def publishOpen(service: DocumentService, doc: DocumentId) =
    for
      v <- service.submit(doc, author)
      _ <- service.changeStatus(v.id, ReviewPassed, UserId("reviewer"))
      _ <- service.attachRendition(v.id, Rendition(s"r/${v.id}.pdf", None, "a" * 64), author)
      _ <- service.changeStatus(v.id, Approve, approver)
      p <- service.changeStatus(v.id, Publish, approver)
    yield p

  private def template(templates: TemplateService, cls: UUID, title: String) =
    for
      t <- templates.create(TemplateDraft(title, None, cls, 1, content), UserId("designer"))
      _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Testing, UserId("designer"))
      _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Released, UserId("designer-2"))
    yield t.templateId

  private def document(
      service: DocumentService,
      template: TemplateId,
      title: String,
      vs: Map[String, FieldEntry]
  ) =
    for
      created <- service.create(NewDocument(template, 1, title, vs), author)
      v       <- publishOpen(service, created._1.id)
    yield v

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
            _       <- grant(ownerA, RoleAssignment(Role.Owner))
            _       <- grant(ownerB, RoleAssignment(Role.Owner))
            _       <- grant(officer, RoleAssignment(Role.ComplianceOfficer))
            _       <- grant(hr1, RoleAssignment(Role.Reader, Some("HR")))
            _       <- grant(hr2, RoleAssignment(Role.Reader, Some("HR")))
            _       <- grant(fin, RoleAssignment(Role.Reader, Some("Finance")))
            _       <- grant(everyone, RoleAssignment(Role.Reader))
            _       <- grant(boardMember, RoleAssignment(Role.Reader))
            _       <- Groups.Postgres.create("board", "Board", admin)
            _       <- Groups.Postgres.addMember("board", boardMember, admin)
            policy  <- classes.createClass("policy", None, ClassDraft("Policy", None, Nil), admin)
            hrClass <- classes
              .createClass("hr-policy", Some(policy.classId), ClassDraft("HR policy", None, Nil), admin)
            general <- classes.createClass("general", None, ClassDraft("General", None, Nil), admin)
            _       <- acks.changeSettings(
              Some(policy.classId.value),
              AcknowledgementSettings(
                AcknowledgementRecipients(groups = Set("board"), units = Set("HR")),
                Some(7)
              ),
              admin
            )
            hrTemplate      <- template(templates, hrClass.classId.value, "HR policy")
            generalTemplate <- template(templates, general.classId.value, "General policy")
            hrPolicy        <- document(documents, hrTemplate, "Leave policy", values(ownerA, "HR"))
            generalPolicy <- document(documents, generalTemplate, "Expense policy", values(ownerB, "Finance"))
            _             <- acks.markOpened(hrPolicy, hr1) *> acks.acknowledge(hrPolicy, hr1)
          yield Fixture(policy.classId.value, general.classId.value, hrPolicy, generalPolicy)
        )
        .mapError(e => RuntimeException(e.toString))
        .orDie
      _ <- setup.succeed(Setup(app, schemaOwner, fixture))
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

  private val directory: ULayer[UserDirectory & TenantOrganizations] =
    ZLayer.succeed[UserDirectory](new UserDirectory:
      def lookup(organizationId: String, ids: Set[UserId]): IO[UserDirectoryError, Map[UserId, UserInfo]] =
        ZIO.succeed(ids.flatMap(id => names.get(id).map(n => id -> UserInfo(id, n, None))).toMap)) ++ ZLayer
      .succeed[TenantOrganizations](new TenantOrganizations:
        def organizationOf(tenant: TenantId): IO[DbError, Option[String]] = ZIO.succeed(Some(orgId)))

  /** What the overview, acknowledgement, settings and compliance endpoints need, each type once. */
  private type Env = Authenticator & Db & Authorizer & DocumentService & ApprovalWorkflow &
    AcknowledgementService & RoleAssignments & Groups & AuditTrail & TenantOrganizations & UserDirectory &
    AcknowledgementReminderConfig & DocumentClassService

  private val env: ZLayer[Any, Throwable, Env] =
    ZLayer.make[Env](
      database,
      authenticator,
      directory,
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
      ZLayer.succeed(AcknowledgementReminderConfig(defaultDays = 14)),
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

  private val routes = ZioHttpInterpreter().toHttp(
    AcknowledgementOverviewApi.endpoints.map(_.widen[Env]) ++ AcknowledgementApi.endpoints.map(
      _.widen[Env]
    ) ++
      AcknowledgementSettingsApi.endpoints.map(_.widen[Env]) ++ ComplianceApi.endpoints.map(_.widen[Env])
  )

  private def call(user: UserId, method: String, path: String, body: Option[String] = None) =
    val url     = URL.decode(path).toOption.get
    val headers = Headers(
      Header.Authorization.Bearer(token(user)),
      Header.ContentType(MediaType.application.json)
    )
    val request = method match
      case "POST"   => Request.post(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case "PUT"    => Request.put(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case "DELETE" => Request.delete(url).addHeaders(headers)
      case _        => Request.get(url).addHeaders(headers)
    ZIO.scoped(routes.runZIO(request)).flatMap(r => r.body.asString.map(b => (r.status, b)).orDie)

  private def get(user: UserId, path: String) = call(user, "GET", path)

  private def as[A: JsonDecoder](response: (Status, String)): A =
    response._2.fromJson[A].fold(e => throw AssertionError(s"$e: ${response._1} ${response._2}"), identity)

  private def code(response: (Status, String)): Option[String] =
    response._2.fromJson[ApiError].toOption.map(_.code)

  private def fixture = setup.await.map(_.fixture)

  private def system[A](f: DbTx ?=> A): ZIO[Any, DbError, A] =
    setup.await.flatMap(_.owner.transactSystem(Db.sql(f)))

  private def overview(user: UserId, v: DocumentVersion) =
    get(user, s"/api/v1/documents/${v.documentId.value}/acknowledgement-overview")

  private def recipients(user: UserId, v: DocumentVersion, query: String) =
    get(user, s"/api/v1/documents/${v.documentId.value}/versions/${v.id}/acknowledgement-recipients$query")

  private def remind(user: UserId, v: DocumentVersion) =
    call(user, "POST", s"/api/v1/documents/${v.documentId.value}/versions/${v.id}/acknowledgement-reminders")

  private val purpose = "?purpose=reminder&reason=Policy%20rollout"

  private def auditCount(action: String) =
    system(sql"select count(*) from dms.audit_event where action = $action".query[Long].run().head)

  private def reminderJobs =
    for
      db        <- ZIO.service[Db]
      documents <- ZIO.service[DocumentService]
      acks      <- ZIO.service[AcknowledgementService]
    yield AcknowledgementReminderJobs(
      db,
      documents,
      acks,
      RoleAssignments.Postgres,
      Groups.Postgres,
      AcknowledgementReminderConfig(defaultDays = 14)
    )

  private def change(roles: List[String], groups: List[String], units: List[String], days: Option[Int]) =
    Some(AcknowledgementSettingsChange(roles, groups, units, days).toJson)

  def spec = suite("Acknowledgement overview, reminders and recipients per class")(
    test("API documentation contains the new endpoints") {
      val docs = sttp.tapir.docs.openapi
        .OpenAPIDocsInterpreter()
        .toOpenAPI(
          (AcknowledgementOverviewApi.endpoints ++ AcknowledgementSettingsApi.endpoints).map(_.endpoint),
          "DMS Platform",
          "test"
        )
      assertTrue(
        docs.paths.pathItems.contains("/api/v1/documents/{id}/acknowledgement-overview"),
        docs.paths.pathItems.contains(
          "/api/v1/documents/{id}/versions/{versionId}/acknowledgement-recipients"
        ),
        docs.paths.pathItems.contains(
          "/api/v1/documents/{id}/versions/{versionId}/acknowledgement-reminders"
        ),
        docs.paths.pathItems.contains("/api/v1/admin/acknowledgement-settings"),
        docs.paths.pathItems.contains("/api/v1/admin/acknowledgement-settings/classes/{classId}")
      )
    },
    test("the class's recipients are recorded at publication and inherited by subclasses") {
      for
        f        <- fixture
        response <- overview(ownerA, f.hrPolicy)
        view = as[AcknowledgementOverviewView](response)
        general <- overview(ownerB, f.general).map(as[AcknowledgementOverviewView])
      yield assertTrue(
        response._1 == Status.Ok,
        view.versions.map(v =>
          (
            v.versionId,
            v.recipients.units,
            v.recipients.groups,
            v.recipients.source,
            v.recipients.sourceClassId
          )
        ) ==
          List((f.hrPolicy.id, List("HR"), List("board"), "class", Some(f.policyClass))),
        view.versions.map(v => (v.audience, v.acknowledged, v.open, v.reminderDays)) == List((3, 1, 2, 7)),
        // Figures only: no user ids in the overview.
        !response._2.contains(hr2.value),
        !response._2.contains(boardMember.value),
        general.versions.map(v =>
          (v.recipients.everyReader, v.recipients.source, v.audience, v.reminderDays)
        ) ==
          List((true, "default", 6, 14))
      )
    },
    test("only recipients are asked: readers outside the class's recipients see nothing to acknowledge") {
      for
        f       <- fixture
        mineAll <- get(everyone, "/api/v1/acknowledgements?status=open").map(
          as[List[AcknowledgementItemView]]
        )
        mineHr2   <- get(hr2, "/api/v1/acknowledgements?status=open").map(as[List[AcknowledgementItemView]])
        mineBoard <- get(boardMember, "/api/v1/acknowledgements?status=open").map(
          as[List[AcknowledgementItemView]]
        )
        state <- get(
          everyone,
          s"/api/v1/documents/${f.hrPolicy.documentId.value}/versions/${f.hrPolicy.id}/acknowledgement"
        )
          .map(as[AcknowledgementStateView])
        stateHr2 <- get(
          hr2,
          s"/api/v1/documents/${f.hrPolicy.documentId.value}/versions/${f.hrPolicy.id}/acknowledgement"
        )
          .map(as[AcknowledgementStateView])
        _ <- call(
          everyone,
          "POST",
          s"/api/v1/documents/${f.hrPolicy.documentId.value}/versions/${f.hrPolicy.id}/opened"
        )
        denied <- call(
          everyone,
          "POST",
          s"/api/v1/documents/${f.hrPolicy.documentId.value}/versions/${f.hrPolicy.id}/acknowledgement"
        )
        cockpit <- get(
          officer,
          s"/api/v1/compliance/acknowledgements?document=${f.hrPolicy.documentId.value}"
        )
          .map(as[List[VersionRateView]])
      yield assertTrue(
        mineAll.map(_.versionId) == List(f.general.id),
        mineHr2.map(_.versionId) == List(f.hrPolicy.id),
        mineBoard.map(_.versionId).toSet == Set(f.hrPolicy.id, f.general.id),
        !state.requested,
        stateHr2.requested,
        denied._1 == Status.UnprocessableEntity,
        code(denied) == Some("acknowledgement_not_requested"),
        cockpit.map(r => (r.audience, r.acknowledged, r.open)) == List((3, 1, 2))
      )
    },
    test("the overview is for the document's owner and compliance officers only") {
      for
        f         <- fixture
        own       <- overview(ownerA, f.hrPolicy)
        byOfficer <- overview(officer, f.hrPolicy)
        foreign   <- overview(ownerB, f.hrPolicy)
        reader    <- overview(hr2, f.hrPolicy)
        byAdmin   <- overview(admin, f.hrPolicy)
      yield assertTrue(
        own._1 == Status.Ok,
        byOfficer._1 == Status.Ok,
        foreign._1 == Status.Forbidden,
        reader._1 == Status.Forbidden,
        byAdmin._1 == Status.Forbidden,
        !reader._2.contains(hr2.value),
        !foreign._2.contains(boardMember.value)
      )
    },
    test("names need a stated purpose, are audited and never reach other readers or owners") {
      for
        f        <- fixture
        before   <- auditCount(ComplianceApi.PersonsViewed)
        none     <- recipients(ownerA, f.hrPolicy, "")
        all      <- recipients(ownerA, f.hrPolicy, purpose)
        open     <- recipients(ownerA, f.hrPolicy, s"$purpose&status=open")
        done     <- recipients(ownerA, f.hrPolicy, s"$purpose&status=done")
        reader   <- recipients(hr2, f.hrPolicy, purpose)
        foreign  <- recipients(ownerB, f.hrPolicy, purpose)
        badState <- recipients(ownerA, f.hrPolicy, s"$purpose&status=late")
        after    <- auditCount(ComplianceApi.PersonsViewed)
      yield assertTrue(
        none._1 == Status.Forbidden,
        code(none) == Some("purpose_required"),
        all._1 == Status.Ok,
        as[RecipientListView](all).recipients.map(r => (r.displayName, r.status)) ==
          List(
            (Some("Berta Brandt"), "open"),
            (Some("Hanna Hansen"), "done"),
            (Some("Hugo Heller"), "open")
          ),
        as[RecipientListView](open).recipients.map(_.userId) == List(boardMember.value, hr2.value),
        as[RecipientListView](done).recipients.map(r => (r.userId, r.acknowledgedAt.isDefined)) ==
          List((hr1.value, true)),
        as[RecipientListView](all).audience == 3,
        as[RecipientListView](all).purpose == "Reminder",
        reader._1 == Status.Forbidden,
        foreign._1 == Status.Forbidden,
        !reader._2.contains("Hanna"),
        !foreign._2.contains(hr1.value),
        badState._1 == Status.BadRequest,
        // three successful accesses, each audited; denied ones are not
        after == before + 3
      )
    },
    test("a manual reminder goes to the open recipients once per day, audited and through the outbox") {
      for
        f      <- fixture
        reader <- remind(hr2, f.hrPolicy)
        first  <- remind(ownerA, f.hrPolicy)
        second <- remind(officer, f.hrPolicy)
        events <- system(
          sql"""select payload::text from dms.outbox_event
                where event_type = ${AcknowledgementService.ReminderEvent} order by id"""
            .query[String]
            .run()
            .toList
        )
        audited <- system(
          sql"""select actor_id, details::text from dms.audit_event
                where action = ${AcknowledgementService.ReminderAction} order by chain_seq"""
            .query[(String, String)]
            .run()
            .toList
        )
        view <- overview(ownerA, f.hrPolicy).map(as[AcknowledgementOverviewView])
      yield assertTrue(
        reader._1 == Status.Forbidden,
        first._1 == Status.Created,
        as[ReminderSentView](first).reminded == 2,
        second._1 == Status.TooManyRequests,
        code(second) == Some("reminder_too_soon"),
        events.size == 1,
        events.head.contains(hr2.value),
        events.head.contains(boardMember.value),
        !events.head.contains(hr1.value),
        events.head.contains("\"kind\": \"manual\""),
        audited.map(_._1) == List(ownerA.value),
        // the audit entry counts the readers, it names none
        !audited.head._2.contains(hr2.value),
        view.versions.head.lastReminderAt.isDefined,
        view.versions.head.nextReminderAt.exists(_.isAfter(Instant.now().plusSeconds(23 * 3600)))
      )
    },
    test("automatic reminders are due per interval and sent once per reader and round") {
      for
        f      <- fixture
        jobs   <- reminderJobs
        now    <- Clock.instant
        early  <- jobs.check(tenantId, now.plusSeconds(6 * 86400L))
        round1 <- jobs.check(tenantId, now.plusSeconds(8 * 86400L))
        again  <- jobs.check(tenantId, now.plusSeconds(8 * 86400L))
        round2 <- jobs.check(tenantId, now.plusSeconds(15 * 86400L))
        repeat <- jobs.check(tenantId, now.plusSeconds(15 * 86400L + 3600L))
        rows   <- system(
          sql"""select version_id, user_id, round from dms.acknowledgement_reminder
                where kind = 'automatic' order by version_id, user_id, round"""
            .query[(UUID, String, Int)]
            .run()
            .toList
        )
      yield assertTrue(
        early == 0,
        // hr policy (every 7 days): hr2 and the board member; the general policy (14 days) is not due yet
        round1 == 2,
        again == 0,
        // hr policy round 2 for both, general policy round 1 for its six readers
        round2 == 8,
        repeat == 0,
        rows.filter(_._1 == f.hrPolicy.id).map(r => (r._2, r._3)) ==
          List((boardMember.value, 1), (boardMember.value, 2), (hr2.value, 1), (hr2.value, 2)),
        rows.count(_._1 == f.general.id) == 6
      )
    },
    test("administrators configure recipients per class and the tenant default; others may not") {
      for
        f       <- fixture
        listed  <- get(admin, "/api/v1/admin/acknowledgement-settings")
        byOwner <- get(ownerA, "/api/v1/admin/acknowledgement-settings")
        path = s"/api/v1/admin/acknowledgement-settings/classes/${f.generalClass}"
        badRole  <- call(admin, "PUT", path, change(List("Nonsense"), Nil, Nil, None))
        badGroup <- call(admin, "PUT", path, change(Nil, List("missing"), Nil, None))
        badDays  <- call(admin, "PUT", path, change(Nil, Nil, Nil, Some(400)))
        noClass  <- call(
          admin,
          "PUT",
          s"/api/v1/admin/acknowledgement-settings/classes/${UUID.randomUUID()}",
          change(Nil, Nil, Nil, None)
        )
        denied <- call(ownerA, "PUT", path, change(List("Owner"), Nil, Nil, None))
        saved  <- call(admin, "PUT", path, change(List("Owner"), List("board"), List(" Finance "), Some(0)))
        tenant <- call(
          admin,
          "PUT",
          "/api/v1/admin/acknowledgement-settings/tenant",
          change(Nil, Nil, Nil, Some(30))
        )
        after <- get(admin, "/api/v1/admin/acknowledgement-settings").map(
          as[AcknowledgementSettingsOverviewView]
        )
        removed <- call(admin, "DELETE", path)
        twice   <- call(admin, "DELETE", path)
        dropped <- call(admin, "DELETE", "/api/v1/admin/acknowledgement-settings/tenant")
        audited <- auditCount("acknowledgement-settings.changed")
        // versions published before keep their recorded settings
        general <- overview(ownerB, f.general).map(as[AcknowledgementOverviewView])
      yield assertTrue(
        listed._1 == Status.Ok,
        as[AcknowledgementSettingsOverviewView](listed).defaultReminderDays == 14,
        !as[AcknowledgementSettingsOverviewView](listed).roles.contains("TenantAdmin"),
        as[AcknowledgementSettingsOverviewView](listed).classes.map(c =>
          (c.classKey, c.settings.map(_.units))
        ) ==
          List(("general", None), ("hr-policy", None), ("policy", Some(List("HR")))),
        byOwner._1 == Status.Forbidden,
        badRole._1 == Status.UnprocessableEntity,
        badGroup._1 == Status.UnprocessableEntity,
        badDays._1 == Status.UnprocessableEntity,
        noClass._1 == Status.NotFound,
        denied._1 == Status.Forbidden,
        saved._1 == Status.Ok,
        as[AcknowledgementSettingsView](saved).units == List("Finance"),
        as[AcknowledgementSettingsView](saved).reminderDays == Some(0),
        tenant._1 == Status.Ok,
        after.tenant.flatMap(_.reminderDays) == Some(30),
        after.classes.find(_.classId == f.generalClass).flatMap(_.settings).map(_.roles) == Some(
          List("Owner")
        ),
        removed._1 == Status.NoContent,
        twice._1 == Status.NotFound,
        dropped._1 == Status.NoContent,
        audited >= 3L,
        general.versions.map(v => (v.recipients.everyReader, v.audience)) == List((true, 6))
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
