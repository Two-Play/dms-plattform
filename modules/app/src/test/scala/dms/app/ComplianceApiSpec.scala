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
import dms.audit.AuditEvidence
import dms.audit.core.{AuditEvidenceLive, AuditTrailLive, TsaTrust}
import dms.authz.{Authorizer, Groups, Role, RoleAssignment, RoleAssignments}
import dms.db.{Db, DbConfig, DbError, Migrations}
import dms.documents.*
import dms.documents.LifecycleEvent.*
import dms.documents.core.*
import dms.documents.metadata.MetadataValue
import dms.identity.*
import dms.kernel.{DocumentId, TemplateId, TenantId, UserId}
import dms.notifications.NotificationCategory
import dms.notifications.core.NotificationTemplates
import dms.templates.*
import dms.templates.core.{TemplateDraft, TemplateService}
import dms.workflow.core.DeadlineAudience
import org.testcontainers.utility.DockerImageName
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.*
import zio.http.{Body, Header, Headers, MediaType, Request, Status, URL}
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
            _ <- RetentionFixtures.assign(classes, cls.classId, "policy-10y")
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
            _ <- documents.newVersion(fin._1, ChangeKind.Major, Some("Content revised"), author)
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

  /** The owner of the HR documents; `admin` beyond them, nobody for `officerHr` (ADR 0077). */
  private val noticeAudience: ULayer[ComplianceNoticeAudience] =
    ZLayer.succeed(new ComplianceNoticeAudience:
      def of(document: DocumentId, version: UUID, escalate: Boolean, sender: UserId) =
        ZIO.succeed(
          DeadlineAudience(List(ownerA), Nil, if escalate && sender != officerHr then List(admin) else Nil)
        ))

  /** A name that would be a formula in a spreadsheet. */
  private val directory: ULayer[UserDirectory & TenantOrganizations] =
    ZLayer.succeed[UserDirectory](new UserDirectory:
      def lookup(organizationId: String, ids: Set[UserId]): IO[UserDirectoryError, Map[UserId, UserInfo]] =
        ZIO.succeed(ids.filter(_ == ownerA).map(id => id -> UserInfo(id, "=Owner A", None)).toMap)) ++ ZLayer
      .succeed[TenantOrganizations](new TenantOrganizations:
        def organizationOf(tenant: TenantId): IO[DbError, Option[String]] = ZIO.succeed(Some(orgId)))

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
      AcknowledgementService.layer,
      noticeAudience,
      directory,
      AuditEvidenceLive.layer
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

  private def post(user: UserId, path: String, body: String) =
    val request = Request
      .post(URL.decode(path).toOption.get, Body.fromString(body))
      .addHeaders(
        Headers(Header.Authorization.Bearer(token(user)), Header.ContentType(MediaType.application.json))
      )
    ZIO.scoped(routes.runZIO(request)).flatMap(r => r.body.asString.map(b => (r.status, b)).orDie)

  private def as[A: JsonDecoder](response: (Status, String)): A =
    response._2.fromJson[A].fold(e => throw AssertionError(s"$e: ${response._1} ${response._2}"), identity)

  private def code(response: (Status, String)): Option[String] =
    response._2.fromJson[ApiError].toOption.map(_.code)

  private def fixture = setup.await.map(_.fixture)

  private def openAt(user: UserId, documentId: UUID, versionId: UUID, body: String) =
    post(user, s"/api/v1/compliance/acknowledgements/$documentId/$versionId/open", body)

  private def open(user: UserId, v: DocumentVersion, body: String) =
    openAt(user, v.documentId.value, v.id, body)

  private val purpose = """{"purpose":"escalation","reason":"Rate below target"}"""

  private def asOwner[A](f: DbTx ?=> A) =
    setup.await.flatMap(_.owner.transactSystem(Db.sql {
      val _ = sql"set local session_replication_role = replica".update.run()
      f
    }))

  private def audited(action: String) =
    asOwner(
      sql"""select actor_id, reason, details::text from dms.audit_event where action = $action
            order by chain_seq""".query[(String, Option[String], String)].run().toList
    )

  private def exportOf(user: UserId, list: String, query: String = "") =
    post(user, s"/api/v1/compliance/exports/$list$query", "{}")

  private def notice(user: UserId, v: DocumentVersion, kind: String) =
    post(
      user,
      s"/api/v1/compliance/overdue-reviews/${v.documentId.value}/${v.id}/notices",
      s"""{"kind":"$kind","reason":"Owner did not react"}"""
    )

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
        docs.paths.pathItems.contains("/api/v1/compliance/acknowledgements/{documentId}/{versionId}/open"),
        docs.paths.pathItems.contains(
          "/api/v1/compliance/acknowledgements/{documentId}/{versionId}/open/export"
        ),
        docs.paths.pathItems.contains("/api/v1/compliance/documents-without-owner"),
        docs.paths.pathItems.contains("/api/v1/compliance/orphaned-documents"),
        docs.paths.pathItems.contains("/api/v1/compliance/audit-verification"),
        docs.paths.pathItems.contains("/api/v1/compliance/deviations"),
        docs.paths.pathItems.contains("/api/v1/compliance/exports/{list}"),
        docs.paths.pathItems.contains("/api/v1/compliance/overdue-reviews/{documentId}/{versionId}/notices")
      )
    },
    test(
      "documents without owner and deviations from standard clauses, within the caller's scope (ADR 0077)"
    ) {
      val report = """{"format":1,"deviations":[{"a":1},{"a":2}]}"""
      for
        f <- fixture
        _ <- asOwner {
          val _ =
            sql"update dms.document set owner_id = null where id = ${f.financeV2.documentId.value}".update
              .run()
          val _ = sql"""update dms.document_version set deviation_report = cast($report as jsonb)
                        where id = ${f.hrPolicy.id}""".update.run()
          sql"""update dms.document_version set deviation_report = cast($report as jsonb)
                where id = ${f.secret.id}""".update.run()
        }
        overview   <- get(officer, "/api/v1/compliance/overview").map(as[ComplianceOverviewView])
        overviewHr <- get(officerHr, "/api/v1/compliance/overview").map(as[ComplianceOverviewView])
        unowned    <- get(officer, "/api/v1/compliance/documents-without-owner").map(
          as[List[UnownedDocumentView]]
        )
        unownedHr <- get(officerHr, "/api/v1/compliance/documents-without-owner")
          .map(as[List[UnownedDocumentView]])
        deviating <- get(officer, "/api/v1/compliance/deviations?unit=HR").map(as[List[DeviationFigureView]])
        otherUnit <- get(officer, "/api/v1/compliance/deviations?unit=Finance").map(
          as[List[DeviationFigureView]]
        )
        denied <- get(readerAll, "/api/v1/compliance/documents-without-owner")
        _      <- asOwner {
          val _ = sql"""update dms.document set owner_id = ${ownerB.value}
                        where id = ${f.financeV2.documentId.value}""".update.run()
          sql"""update dms.document_version
                set deviation_report = cast('{"format":1,"deviations":[]}' as jsonb)
                where id in (${f.hrPolicy.id}, ${f.secret.id})""".update.run()
        }
      yield assertTrue(
        overview.documentsWithoutOwner == 1,
        overviewHr.documentsWithoutOwner == 0,
        // the secret version the officer is not cleared for is not counted
        overview.versionsWithDeviations == 1,
        overview.deviations == 2,
        unowned.map(_.versionId) == List(f.financeV2.id),
        unownedHr.isEmpty,
        deviating.map(d => (d.versionId, d.deviations)) == List((f.hrPolicy.id, 2)),
        otherUnit.isEmpty,
        denied._1 == Status.Forbidden
      )
    },
    test("orphaned documents: the owner's role ended or no longer covers the document (ADR 0080)") {
      def inTenant[A](work: ZIO[Any, DbError, A]) = setup.await.flatMap(_.app.transact(tenantId)(work))
      def endRoles(user: UserId)                  =
        for
          now <- Clock.instant
          ids <- Db.sql(
            sql"""select id from dms.role_assignment where user_id = ${user.value}
                  and role = 'Owner' and valid_until is null""".query[UUID].run().toList
          )
          _ <- ZIO.foreachDiscard(ids)(RoleAssignments.Postgres.revoke(_, now))
        yield ()
      for
        f <- fixture
        // ownerA's role ended; ownerB changed function and answers for Finance only.
        _ <- inTenant(
          endRoles(ownerA) *> endRoles(ownerB) *>
            RoleAssignments.Postgres.grant(ownerB, RoleAssignment(Role.Owner, Some("Finance")), admin)
        )
        overview <- get(officer, "/api/v1/compliance/overview").map(as[ComplianceOverviewView])
        list     <- get(officer, "/api/v1/compliance/orphaned-documents").map(as[List[OrphanedDocumentView]])
        byOwner  <- get(officer, s"/api/v1/compliance/orphaned-documents?owner=${ownerB.value}")
          .map(as[List[OrphanedDocumentView]])
        byUnit <- get(officer, "/api/v1/compliance/orphaned-documents?unit=Finance")
          .map(as[List[OrphanedDocumentView]])
        scoped <- get(officerHr, "/api/v1/compliance/orphaned-documents").map(as[List[OrphanedDocumentView]])
        denied <- get(readerAll, "/api/v1/compliance/orphaned-documents")
        csv    <- exportOf(officer, "orphaned-documents", "?unit=HR&lang=de")
        trail  <- audited(ComplianceApi.ListExported)
        _      <- inTenant(
          endRoles(ownerB) *>
            RoleAssignments.Postgres.grant(ownerA, RoleAssignment(Role.Owner), admin) *>
            RoleAssignments.Postgres.grant(ownerB, RoleAssignment(Role.Owner), admin)
        )
        after <- get(officer, "/api/v1/compliance/overview").map(as[ComplianceOverviewView])
      yield
        val rows = csv._2.split("\r\n").toList
        assertTrue(
          // the secret version of ownerA stays out: no officer is cleared for it
          overview.orphanedDocuments == 2,
          list.map(r => (r.versionId, r.owner)).toSet ==
            Set((f.hrPolicy.id, ownerA.value), (f.hrHandbook.id, ownerB.value)),
          byOwner.map(_.versionId) == List(f.hrHandbook.id),
          byUnit.isEmpty,
          scoped.size == 2,
          denied._1 == Status.Forbidden,
          csv._1 == Status.Ok,
          rows.size == 3,
          rows.head.contains("Eigentümer (Benutzer-ID);Eigentümer (Name)"),
          rows.exists(_.contains("'=Owner A")),
          trail.lastOption.exists(_._3.contains("orphaned-documents")),
          after.orphanedDocuments == 0
        )
    },
    test("the verification of the chain: never ran, intact, stale, violated; for officers only (ADR 0080)") {
      def state(user: UserId = officer) =
        get(user, "/api/v1/compliance/audit-verification").map(as[AuditVerificationView])
      def run(age: java.time.Duration, kind: Option[String], detail: String = "the secret entry") =
        Clock.instant.flatMap(now =>
          val at = java.time.OffsetDateTime.ofInstant(now.minus(age), java.time.ZoneOffset.UTC)
          asOwner {
            val _ = sql"""insert into dms.audit_verification_run
                            (tenant_id, verified_at, ok, entries_checked, anchors_checked, head_seq,
                             violation_seq, violation_kind, violation_detail)
                          values (${tenantId.value}, $at, ${kind.isEmpty}, 12, 0, 12,
                                  ${kind.map(_ => 7L)}, $kind, ${kind.map(_ => detail)})""".update.run()
          }
        )
      val hours = java.time.Duration.ofHours(_)
      for
        never  <- state()
        _      <- run(hours(1), None)
        intact <- state()
        scoped <- state(officerHr)
        reader <- get(readerAll, "/api/v1/compliance/audit-verification")
        admins <- get(admin, "/api/v1/compliance/audit-verification")
        _      <- run(hours(71), None)
        fresh  <- state()
        _      <- run(hours(73), None)
        stale  <- state()
        _      <- run(hours(1), Some("HashMismatch"))
        _      <- run(hours(0), Some("HashMismatch"))
        raw    <- get(officer, "/api/v1/compliance/audit-verification")
        violated = as[AuditVerificationView](raw)
      yield assertTrue(
        never.lastRunAt.isEmpty,
        never.result.isEmpty,
        !never.stale,
        never.staleAfterDays == 3L,
        intact.result.contains("intact"),
        !intact.stale,
        intact.entriesChecked.contains(12L),
        intact.checkpointsCompared.contains(true),
        scoped.result.contains("intact"),
        reader._1 == Status.Forbidden,
        admins._1 == Status.Forbidden,
        !fresh.stale,
        stale.stale,
        stale.result.contains("intact"),
        violated.result.contains("violated"),
        violated.violationKind.contains("HashMismatch"),
        violated.violationPosition.contains(7L),
        violated.violatedSince.exists(since => violated.lastRunAt.exists(since.isBefore)),
        !raw._2.contains("secret entry")
      )
    },
    test("a violation of the chain reaches every active officer and the administrators, nothing of it") {
      for
        s         <- setup.await
        documents <- ZIO.service[DocumentService]
        roles     <- ZIO.service[RoleAssignments]
        audit     <- ZIO.service[dms.audit.AuditTrail]
        events = PlatformNotificationEvents(documents, roles, dms.archive.core.LegalHoldService(audit))
        event  = dms.db.queue.OutboxEvent(
          1,
          tenantId,
          dms.audit.AuditViolation.AggregateType,
          tenantId.value,
          dms.audit.AuditViolation.Event,
          dms.db.Jsonb("""{"foundAt":"2026-11-02T03:00:00Z","reminder":"1"}"""),
          None,
          0
        )
        notices <- s.app
          .transact(tenantId)(events.describe(event))
          .mapError(e => RuntimeException(e.toString))
      yield assertTrue(
        events.concerns(event),
        notices.forall(_.kind == NotificationTemplates.ChainViolation),
        notices.forall(_.category == NotificationCategory.AuditIntegrity),
        notices.forall(_.document.isEmpty),
        notices.map(n => n.params("audience") -> n.recipients.toSet).toMap == Map(
          "compliance" -> Set(officer, officerHr),
          "admin"      -> Set(admin)
        ),
        notices.forall(_.params.keySet == Set("audience", "foundAt", "reminder"))
      )
    },
    test("exports carry the rights and filters of the screen and are audited") {
      for
        f       <- fixture
        before  <- audited(ComplianceApi.ListExported).map(_.size)
        screen  <- get(officer, "/api/v1/compliance/overdue-reviews?unit=HR").map(as[List[OverdueReviewView]])
        all     <- exportOf(officer, "overdue-reviews", "?unit=HR&lang=de")
        scoped  <- exportOf(officerHr, "acknowledgements")
        wide    <- exportOf(officer, "acknowledgements")
        unowned <- exportOf(officer, "documents-without-owner")
        deviating <- exportOf(officer, "deviations")
        reader    <- exportOf(readerAll, "overdue-reviews")
        owner     <- exportOf(ownerA, "acknowledgements")
        unknown   <- exportOf(officer, "everything")
        trail     <- audited(ComplianceApi.ListExported)
      yield
        val rows = all._2.split("\r\n").toList
        assertTrue(
          all._1 == Status.Ok,
          rows.head.contains("Dokument-ID;Titel;"),
          rows.size == screen.size + 1,
          screen.forall(r => all._2.contains(r.versionId.toString) || all._2.contains(r.title)),
          !all._2.contains(f.secret.id.toString),
          !scoped._2.contains("Finance"),
          wide._2.contains("Finance"),
          unowned._1 == Status.Ok,
          deviating._1 == Status.Ok,
          reader._1 == Status.Forbidden,
          owner._1 == Status.Forbidden,
          unknown._1 == Status.NotFound,
          trail.size == before + 5,
          trail.exists((actor, _, details) =>
            actor == officer.value && details.contains("overdue-reviews") && details.contains(
              "\"unit\": \"HR\""
            ) &&
              details.contains(s"\"rows\": ${screen.size}")
          ),
          trail.forall((actor, _, _) => actor == officer.value || actor == officerHr.value)
        )
    },
    test("the four-eyes findings leave as CSV with the rights of the screen, audited and formula-safe") {
      for
        f <- fixture
        version  = f.hrPolicy.id
        document = f.hrPolicy.documentId.value
        title <- asOwner(sql"select title from dms.document where id = $document".query[String].run().head)
        _     <- asOwner(
          sql"""insert into dms.document_version_editor (tenant_id, version_id, user_id)
                values (${tenantId.value}, $version, ${approver.value}) on conflict do nothing""".update.run()
        )
        _ <- asOwner(
          sql"""update dms.document set title = '=HYPERLINK("http://evil")' where id = $document""".update
            .run()
        )
        viewedBefore <- audited(FourEyesReportApi.FindingsViewed).map(_.size)
        csv          <- exportOf(officer, "four-eyes-findings", "?lang=de")
        inScope      <- exportOf(officerHr, "four-eyes-findings")
        denied       <- exportOf(readerAll, "four-eyes-findings")
        trail        <- audited(ComplianceApi.ListExported)
        viewedAfter  <- audited(FourEyesReportApi.FindingsViewed).map(_.size)
        _            <- asOwner(sql"update dms.document set title = $title where id = $document".update.run())
      yield
        val rows = csv._2.split("\r\n").toList
        assertTrue(
          csv._1 == Status.Ok,
          rows.head.contains("Dokument-ID;Titel;"),
          rows.exists(r => r.contains(document.toString) && r.contains(";Approval;")),
          csv._2.contains("'=HYPERLINK"),
          !csv._2.contains(";=HYPERLINK"),
          csv._2.contains(approver.value),
          inScope._1 == Status.Ok,
          inScope._2.contains(document.toString),
          denied._1 == Status.Forbidden,
          trail.exists((actor, _, details) =>
            actor == officer.value && details.contains("four-eyes-findings") && details.contains("\"format\"")
          ),
          viewedAfter == viewedBefore
        )
    },
    test("a title or a name in an export cannot run as a formula") {
      for
        f <- fixture
        document = f.hrPolicy.documentId.value
        title <- asOwner(sql"select title from dms.document where id = $document".query[String].run().head)
        _     <- asOwner(
          sql"""update dms.document set title = '=HYPERLINK("http://evil")' where id = $document""".update
            .run()
        )
        csv <- exportOf(officer, "overdue-reviews")
        _   <- asOwner(sql"update dms.document set title = $title where id = $document".update.run())
      yield assertTrue(
        csv._2.contains("\"'=HYPERLINK(\"\"http://evil\"\")\""),
        csv._2.contains("'=Owner A"),
        !csv._2.contains(";=")
      )
    },
    test("the name list as a file needs the same purpose and is audited like the list") {
      for
        f <- fixture
        path =
          s"/api/v1/compliance/acknowledgements/${f.hrPolicy.documentId.value}/${f.hrPolicy.id}/open/export"
        none    <- post(officer, path, "{}")
        foreign <- post(ownerB, path, purpose)
        before  <- personAudit.map(_.size)
        csv     <- post(officer, s"$path?lang=en", purpose)
        trail   <- personAudit
      yield assertTrue(
        none._1 == Status.Forbidden,
        code(none).contains("purpose_required"),
        foreign._1 == Status.Forbidden,
        csv._1 == Status.Ok,
        csv._2.contains("User id;Name"),
        csv._2.contains(readerHr2.value),
        trail.size == before + 1,
        trail.last._1 == officer.value,
        trail.last._2.contains("Rate below target"),
        trail.last._3.contains("csv")
      )
    },
    test("a notice about an overdue review goes out once per kind and day, for all officers (ADR 0077)") {
      for
        f      <- fixture
        before <- audited(ComplianceNotices.Action).map(_.size)
        reader <- notice(readerAll, f.hrPolicy, "reminder")
        owner  <- notice(ownerA, f.hrPolicy, "reminder")
        secret <- notice(officer, f.secret, "reminder")
        notDue <- notice(officer, f.financeV2, "reminder")
        old    <- notice(officer, f.financeV1, "reminder")
        noKind <- post(
          officer,
          s"/api/v1/compliance/overdue-reviews/${f.hrPolicy.documentId.value}/${f.hrPolicy.id}/notices",
          """{"reason":"x"}"""
        )
        noReason <- post(
          officer,
          s"/api/v1/compliance/overdue-reviews/${f.hrPolicy.documentId.value}/${f.hrPolicy.id}/notices",
          """{"kind":"reminder","reason":"  "}"""
        )
        nobody <- notice(officerHr, f.hrPolicy, "escalation")
        first  <- notice(officer, f.hrPolicy, "reminder")
        again  <- notice(officer, f.hrPolicy, "reminder")
        other  <- notice(officerHr, f.hrPolicy, "reminder")
        up     <- notice(officer, f.hrPolicy, "escalation")
        race   <- ZIO.collectAllPar(List.fill(4)(notice(officer, f.hrHandbook, "reminder")))
        listed <- get(officer, "/api/v1/compliance/overdue-reviews").map(as[List[OverdueReviewView]])
        trail  <- audited(ComplianceNotices.Action)
        events <- asOwner(
          sql"""select payload::text from dms.outbox_event where event_type = ${ComplianceNotices.Event}
                and aggregate_id = ${f.hrPolicy.documentId.value} order by id""".query[String].run().toList
        )
        foreign <- setup.await.flatMap(
          _.app.transact(TenantId.random())(
            Db.sql(sql"select count(*) from dms.compliance_notice".query[Long].run().head)
          )
        )
        deleted <- setup.await.flatMap(
          _.app
            .transact(tenantId)(Db.sql(sql"delete from dms.compliance_notice".update.run()))
            .either
        )
      yield
        val sent = as[ComplianceNoticeView](first)
        val row  = listed.find(_.versionId == f.hrPolicy.id).get
        assertTrue(
          reader._1 == Status.Forbidden,
          owner._1 == Status.Forbidden,
          secret._1 == Status.Forbidden,
          notDue._1 == Status.UnprocessableEntity,
          code(notDue).contains("not_overdue"),
          old._1 == Status.NotFound,
          noKind._1 == Status.UnprocessableEntity,
          noReason._1 == Status.UnprocessableEntity,
          code(nobody).contains("no_recipient"),
          first._1 == Status.Created,
          sent.recipients == 1,
          sent.nextNoticeAt == sent.sentAt.plus(java.time.Duration.ofHours(24)),
          again._1 == Status.TooManyRequests,
          code(again).contains("notice_too_soon"),
          other._1 == Status.TooManyRequests,
          up._1 == Status.Created,
          as[ComplianceNoticeView](up).recipients == 2,
          race.count(_._1 == Status.Created) == 1,
          race.count(_._1 == Status.TooManyRequests) == 3,
          row.nextReminderAt.contains(sent.nextNoticeAt),
          row.nextEscalationAt.isDefined,
          trail.size == before + 3,
          trail.exists((actor, reason, details) =>
            actor == officer.value && reason.contains("Owner did not react") && details.contains("reminder")
          ),
          events.size == 2,
          events.head.contains(ownerA.value),
          // the reason stays in the audit trail
          !events.exists(_.contains("Owner did not react")),
          foreign == 0L,
          deleted.isLeft
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
        none     <- open(officer, f.hrPolicy, "{}")
        noReason <- open(officer, f.hrPolicy, """{"purpose":"escalation"}""")
        unknown  <- open(officer, f.hrPolicy, """{"purpose":"performance","reason":"Check"}""")
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
    test("purpose and reason in the query string are no longer accepted (ADR 0060)") {
      for
        f      <- fixture
        before <- personAudit
        url = s"/api/v1/compliance/acknowledgements/${f.hrPolicy.documentId.value}/${f.hrPolicy.id}/open"
        query   <- post(officer, s"$url?purpose=escalation&reason=Rate%20below%20target", "{}")
        noBody  <- post(officer, s"$url?purpose=escalation&reason=Rate%20below%20target", "")
        asGet   <- get(officer, s"$url?purpose=escalation&reason=Rate%20below%20target")
        after   <- personAudit
        omitted <- openAt(officer, f.hrPolicy.documentId.value, f.hrPolicy.id, """{"purpose":"escalation"}""")
      yield assertTrue(
        query._1 == Status.Forbidden,
        code(query) == Some("purpose_required"),
        noBody._1 != Status.Ok,
        asGet._1 != Status.Ok,
        omitted._1 == Status.Forbidden,
        // none of them showed names or left an audit entry
        before.size == after.size
      )
    },
    test("the owner sees names for their own documents only; others and superseded versions are denied") {
      for
        f          <- fixture
        own        <- open(ownerA, f.hrPolicy, """{"purpose":"reminder","reason":"Remind the team"}""")
        foreign    <- open(ownerB, f.hrPolicy, purpose)
        reader     <- open(readerAll, f.hrPolicy, purpose)
        otherUnit  <- open(officerHr, f.financeV2, purpose)
        superseded <- open(officer, f.financeV1, purpose)
        secret     <- open(officer, f.secret, purpose)
        missing    <- openAt(officer, UUID.randomUUID(), UUID.randomUUID(), purpose)
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
