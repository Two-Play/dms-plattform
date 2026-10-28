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
import dms.documents.metadata.MetadataValue
import dms.identity.*
import dms.kernel.{DocumentId, TemplateId, TenantId, UserId}
import dms.templates.*
import dms.templates.core.{TemplateDraft, TemplateService}
import org.testcontainers.utility.DockerImageName
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.*
import zio.http.{Header, Headers, Request, Status, URL}
import zio.json.*
import zio.test.*

import java.time.{Instant, LocalDate, ZoneId}
import java.util.{Date, UUID}
import javax.sql.DataSource

/** Compliance cockpit (DMS-59) end to end: HTTP endpoints over documents published through the documents
  * service, acknowledgements, role assignments and the audit trail in a Testcontainers database.
  *
  * Population (all assignments tenant-wide unless noted): `officer` (compliance officer), `officerHr`
  * (compliance officer for HR), `ownerA`, `ownerB` (owners), `readerHr1`, `readerHr2` (readers for HR),
  * `readerFin` (reader for Finance), `readerAll` (reader), `admin` (tenant administrator, no business role).
  * Author and approver hold no role. The readers of an Internal HR version are therefore officer, officerHr,
  * ownerA, ownerB, readerHr1, readerHr2 and readerAll (7); of a Finance version officer, ownerA, ownerB,
  * readerFin and readerAll (5).
  *
  * Documents: `hrPolicy` (HR, ownerA, review overdue since 5 days), `finance` (Finance, ownerB, review in 30
  * days; version 1.0 superseded by 2.0), `hrHandbook` (HR, ownerB, overdue since 2 days), `secret`
  * (Confidential, HR, ownerA, overdue; no compliance officer is cleared for it).
  */
object ComplianceApiSpec extends ZIOSpecDefault:

  private val issuer   = "http://localhost:8085"
  private val audience = "project-1"
  private val orgId    = "org-1"
  private val tenantId = TenantId.random()

  private val admin     = UserId("admin")
  private val author    = UserId("author")
  private val approver  = UserId("approver")
  private val officer   = UserId("officer")
  private val officerHr = UserId("officer-hr")
  private val ownerA    = UserId("owner-a")
  private val ownerB    = UserId("owner-b")
  private val readerHr1 = UserId("reader-hr-1")
  private val readerHr2 = UserId("reader-hr-2")
  private val readerFin = UserId("reader-fin")
  private val readerAll = UserId("reader-all")

  private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("key-1").generate()

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  /** The published versions the tests look at. */
  final case class Fixture(
      hrPolicy: DocumentVersion,
      financeV1: DocumentVersion,
      financeV2: DocumentVersion,
      hrHandbook: DocumentVersion,
      secret: DocumentVersion
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

  private def values(
      owner: UserId,
      unit: String,
      validFrom: LocalDate,
      nextReview: LocalDate,
      confidential: Boolean = false
  ): Map[String, FieldEntry] =
    (Map(
      "owner"                -> MetadataValue.PersonRef(owner.value),
      "organizationalUnit"   -> MetadataValue.Text(unit),
      "validFrom"            -> MetadataValue.Date(validFrom),
      "nextReview"           -> MetadataValue.Date(nextReview),
      "confidentiality"      -> MetadataValue.Selection(if confidential then "Confidential" else "Internal"),
      "language"             -> MetadataValue.Text("de"),
      "retentionRule"        -> MetadataValue.Text("policy-10y"),
      "containsPersonalData" -> MetadataValue.YesNo(false)
    ) ++ Option.when(confidential)("allowedGroups" -> MetadataValue.Many(List(MetadataValue.Text("board")))))
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
            _   <- grant(admin, RoleAssignment(Role.TenantAdmin))
            _   <- grant(officer, RoleAssignment(Role.ComplianceOfficer))
            _   <- grant(officerHr, RoleAssignment(Role.ComplianceOfficer, Some("HR")))
            _   <- grant(ownerA, RoleAssignment(Role.Owner))
            _   <- grant(ownerB, RoleAssignment(Role.Owner))
            _   <- grant(readerHr1, RoleAssignment(Role.Reader, Some("HR")))
            _   <- grant(readerHr2, RoleAssignment(Role.Reader, Some("HR")))
            _   <- grant(readerFin, RoleAssignment(Role.Reader, Some("Finance")))
            _   <- grant(readerAll, RoleAssignment(Role.Reader))
            _   <- Groups.Postgres.create("board", "Board", admin)
            cls <- classes.createClass(
              "policy",
              None,
              ClassDraft("Policy", None, Nil, review = Some(ReviewPolicy(Some(12), 60))),
              admin
            )
            t <- templates
              .create(TemplateDraft("Policy", None, cls.classId.value, 1, content), UserId("designer"))
            _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Testing, UserId("designer"))
            _ <- templates.changeStatus(t.templateId, 1, TemplateStatus.Released, UserId("designer-2"))
            id = t.templateId
            hr <- document(
              documents,
              id,
              "HR policy",
              values(ownerA, "HR", today.minusDays(30), today.minusDays(5))
            )
            fin <- document(
              documents,
              id,
              "Finance policy",
              values(ownerB, "Finance", today.minusDays(30), today.plusDays(30))
            )
            handbook <- document(
              documents,
              id,
              "HR handbook",
              values(ownerB, "HR", today.minusDays(30), today.minusDays(2))
            )
            secret <- document(
              documents,
              id,
              "Board policy",
              values(ownerA, "HR", today.minusDays(30), today.minusDays(5), confidential = true)
            )
            // Version 1.0 of the finance policy is acknowledged by two readers, then superseded by 2.0.
            _ <- ZIO.foreachDiscard(List(readerFin, readerAll))(r =>
              acks.markOpened(fin._2, r) *> acks.acknowledge(fin._2, r)
            )
            _ <- documents.newVersion(fin._1, ChangeKind.Major, None, author)
            _ <- documents.updateDraft(
              fin._1,
              DraftChange(None, values(ownerB, "Finance", today.minusDays(1), today.plusDays(30)), None),
              author
            )
            fin2 <- publishOpen(documents, fin._1)
            _    <- acks.markOpened(fin2, readerAll) *> acks.acknowledge(fin2, readerAll)
            // readerHr1 acknowledges the HR policy; readerFin cannot read it, so their acknowledgement (recorded
            // through the service, which leaves authorization to the caller) does not count.
            _ <- ZIO.foreachDiscard(List(readerHr1, readerFin))(r =>
              acks.markOpened(hr._2, r) *> acks.acknowledge(hr._2, r)
            )
            // readerHr2 opened the HR policy but did not acknowledge it.
            _       <- acks.markOpened(hr._2, readerHr2)
            current <- documents.get(fin._1).map(_.versions)
          yield Fixture(hr._2, current.find(_.id == fin._2.id).get, fin2, handbook._2, secret._2)
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

  private val env: ZLayer[Any, Throwable, ComplianceApi.Env] =
    ZLayer.make[ComplianceApi.Env](
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
      AcknowledgementService.layer
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

  private val routes = ZioHttpInterpreter().toHttp(ComplianceApi.endpoints)

  private def get(user: UserId, path: String) =
    val request =
      Request.get(URL.decode(path).toOption.get).addHeaders(Headers(Header.Authorization.Bearer(token(user))))
    ZIO.scoped(routes.runZIO(request)).flatMap(r => r.body.asString.map(b => (r.status, b)).orDie)

  private def as[A: JsonDecoder](response: (Status, String)): A =
    response._2.fromJson[A].fold(e => throw AssertionError(s"$e: ${response._1} ${response._2}"), identity)

  private def code(response: (Status, String)): Option[String] =
    response._2.fromJson[ApiError].toOption.map(_.code)

  private def fixture = setup.await.map(_.fixture)

  private def open(user: UserId, v: DocumentVersion, query: String) =
    get(user, s"/api/v1/compliance/acknowledgements/${v.documentId.value}/${v.id}/open$query")

  private val purpose = "?purpose=escalation&reason=Rate%20below%20target"

  /** Audit entries of the person lists: actor, reason and details. */
  private def personAudit: ZIO[Any, DbError, List[(String, Option[String], String)]] =
    setup.await.flatMap(s =>
      s.owner.transactSystem(
        Db.sql(
          sql"""select actor_id, reason, details::text from dms.audit_event
                where action = ${ComplianceApi.PersonsViewed} order by chain_seq"""
            .query[(String, Option[String], String)]
            .run()
            .toList
        )
      )
    )

  def spec = suite("Compliance cockpit API")(
    test("API documentation contains the compliance endpoints") {
      val docs = sttp.tapir.docs.openapi
        .OpenAPIDocsInterpreter()
        .toOpenAPI(ComplianceApi.endpoints.map(_.endpoint), "DMS Platform", "test")
      assertTrue(
        docs.paths.pathItems.contains("/api/v1/compliance/overview"),
        docs.paths.pathItems.contains("/api/v1/compliance/overdue-reviews"),
        docs.paths.pathItems.contains("/api/v1/compliance/acknowledgements"),
        docs.paths.pathItems.contains("/api/v1/compliance/acknowledgements/{documentId}/{versionId}/open")
      )
    },
    test("the overview counts overdue reviews per unit and owner and acknowledgement rates") {
      for
        response <- get(officer, "/api/v1/compliance/overview")
        view = as[ComplianceOverviewView](response)
      yield assertTrue(
        response._1 == Status.Ok,
        view.today == today,
        // HR policy and HR handbook; the confidential board policy is not visible, the finance policy not due
        view.overdueReviews == 2,
        view.overdueByUnit == List(UnitCountView(Some("HR"), 2)),
        view.overdueByOwner == List(
          OwnerCountView(Some(ownerA.value), 1),
          OwnerCountView(Some(ownerB.value), 1)
        ),
        // HR policy 1/7, HR handbook 0/7, finance 2.0 1/5 (the acknowledgements of 1.0 do not count)
        view.acknowledgements == RateView(3, 19, 2, 17, Some(2.0 / 19)),
        view.acknowledgementsByUnit == List(
          UnitRateView(Some("HR"), RateView(2, 14, 1, 13, Some(1.0 / 14))),
          UnitRateView(Some("Finance"), RateView(1, 5, 1, 4, Some(0.2)))
        )
      )
    },
    test("a compliance officer for one unit sees only that unit") {
      for
        response <- get(officerHr, "/api/v1/compliance/overview")
        view = as[ComplianceOverviewView](response)
        list <- get(officerHr, "/api/v1/compliance/acknowledgements").map(as[List[VersionRateView]])
      yield assertTrue(
        view.overdueReviews == 2,
        view.acknowledgementsByUnit.map(_.unit) == List(Some("HR")),
        list.map(_.title).toSet == Set("HR policy", "HR handbook")
      )
    },
    test("every figure leads to its list: overdue reviews filtered by unit and owner") {
      for
        f       <- fixture
        all     <- get(officer, "/api/v1/compliance/overdue-reviews").map(as[List[OverdueReviewView]])
        byOwner <- get(officer, s"/api/v1/compliance/overdue-reviews?owner=${ownerA.value}")
          .map(as[List[OverdueReviewView]])
        byUnit <- get(officer, "/api/v1/compliance/overdue-reviews?unit=Finance").map(
          as[List[OverdueReviewView]]
        )
        noUnit <- get(officer, "/api/v1/compliance/overdue-reviews?unit=").map(as[List[OverdueReviewView]])
      yield assertTrue(
        all.map(r => (r.title, r.dueOn, r.daysOverdue)) ==
          List(("HR policy", today.minusDays(5), 5L), ("HR handbook", today.minusDays(2), 2L)),
        all.head.versionId == f.hrPolicy.id,
        all.head.unit == Some("HR"),
        all.head.owner == Some(ownerA.value),
        all.head.versionLabel == "1.0",
        byOwner.map(_.versionId) == List(f.hrPolicy.id),
        byUnit.isEmpty,
        noUnit.isEmpty
      )
    },
    test("acknowledgement rates per version; superseded versions drop out") {
      for
        f       <- fixture
        all     <- get(officer, "/api/v1/compliance/acknowledgements").map(as[List[VersionRateView]])
        finance <- get(
          officer,
          s"/api/v1/compliance/acknowledgements?document=${f.financeV2.documentId.value}"
        )
          .map(as[List[VersionRateView]])
        hr <- get(officer, "/api/v1/compliance/acknowledgements?unit=HR").map(as[List[VersionRateView]])
      yield assertTrue(
        f.financeV1.status == DocumentStatus.Superseded,
        !all.exists(_.versionId == f.financeV1.id),
        !all.exists(_.versionId == f.secret.id),
        finance.map(r => (r.versionId, r.versionLabel, r.audience, r.acknowledged, r.open, r.rate)) ==
          List((f.financeV2.id, "2.0", 5, 1, 4, Some(0.2))),
        hr.map(r => (r.title, r.audience, r.acknowledged)) == List(("HR handbook", 7, 0), ("HR policy", 7, 1))
      )
    },
    test("only compliance officers open the cockpit") {
      for
        reader  <- get(readerAll, "/api/v1/compliance/overview")
        owner   <- get(ownerA, "/api/v1/compliance/overdue-reviews")
        byAdmin <- get(admin, "/api/v1/compliance/acknowledgements")
      yield assertTrue(
        reader._1 == Status.Forbidden,
        owner._1 == Status.Forbidden,
        byAdmin._1 == Status.Forbidden,
        code(reader) == Some("forbidden")
      )
    },
    test("names of open acknowledgements need a stated purpose and are audited") {
      for
        f        <- fixture
        before   <- personAudit
        none     <- open(officer, f.hrPolicy, "")
        noReason <- open(officer, f.hrPolicy, "?purpose=escalation")
        unknown  <- open(officer, f.hrPolicy, "?purpose=performance&reason=Check")
        allowed  <- open(officer, f.hrPolicy, purpose)
        after    <- personAudit
      yield assertTrue(
        none._1 == Status.Forbidden,
        code(none) == Some("purpose_required"),
        noReason._1 == Status.Forbidden,
        unknown._1 == Status.Forbidden,
        // denied requests leave no audit entry
        before.size == after.size - 1,
        allowed._1 == Status.Ok,
        as[OpenAcknowledgementsView](allowed).purpose == "Escalation",
        as[OpenAcknowledgementsView](allowed).audience == 7,
        as[OpenAcknowledgementsView](allowed).acknowledged == 1,
        as[OpenAcknowledgementsView](allowed).open ==
          List(officer, officerHr, ownerA, ownerB, readerAll, readerHr2).map(_.value).sorted,
        after.last._1 == officer.value,
        after.last._2 == Some("Rate below target"),
        after.last._3.contains("Escalation"),
        after.last._3.contains(f.hrPolicy.id.toString)
      )
    },
    test("the owner sees names for their own documents only; others and superseded versions are denied") {
      for
        f          <- fixture
        own        <- open(ownerA, f.hrPolicy, "?purpose=reminder&reason=Remind%20the%20team")
        foreign    <- open(ownerB, f.hrPolicy, purpose)
        reader     <- open(readerAll, f.hrPolicy, purpose)
        otherUnit  <- open(officerHr, f.financeV2, purpose)
        superseded <- open(officer, f.financeV1, purpose)
        secret     <- open(officer, f.secret, purpose)
        missing    <- get(
          officer,
          s"/api/v1/compliance/acknowledgements/${UUID.randomUUID()}/${UUID.randomUUID()}/open$purpose"
        )
      yield assertTrue(
        own._1 == Status.Ok,
        as[OpenAcknowledgementsView](own).purpose == "Reminder",
        foreign._1 == Status.Forbidden,
        reader._1 == Status.Forbidden,
        otherUnit._1 == Status.Forbidden,
        superseded._1 == Status.Forbidden,
        secret._1 == Status.Forbidden,
        missing._1 == Status.NotFound
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
