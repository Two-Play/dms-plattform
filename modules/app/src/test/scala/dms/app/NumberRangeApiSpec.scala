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
import dms.documents.core.*
import dms.identity.*
import dms.kernel.{TemplateId, TenantId, UserId}
import dms.templates.*
import dms.templates.core.{TemplateDraft, TemplateService}
import dms.workflow.core.{ApprovalWorkflow, FlowableEngine, WorkflowConfig}
import org.testcontainers.utility.DockerImageName
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.*
import zio.*
import zio.http.{Body, Header, Headers, MediaType, Request, Status, URL}
import zio.json.*
import zio.test.*

import java.time.{Instant, LocalDate, ZoneId}
import java.util.{Date, UUID}
import javax.sql.DataSource

/** HTTP tests of number ranges (DMS-113): the administration for tenant administrators, numbers on creation
  * and the manual assignment with its authorization, against a Testcontainers database with the real audit
  * trail.
  */
object NumberRangeApiSpec extends ZIOSpecDefault:

  private val issuer   = "http://localhost:8085"
  private val audience = "project-1"
  private val orgId    = "org-1"
  private val tenantId = TenantId.random()
  private val admin    = UserId("admin-1")
  private val author   = UserId("author-1")
  private val owner    = UserId("owner-1")
  private val records  = UserId("records-1")
  private val reader   = UserId("reader-1")

  private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("key-1").generate()

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  final case class Setup(owner: Db, released: TemplateId, classId: UUID)

  private val setup = Unsafe.unsafe(implicit u => Promise.unsafe.make[Nothing, Setup](FiberId.None))

  private val content = TemplateContent(
    List(
      Section(
        SectionId("terms"),
        Some("Terms"),
        locked = false,
        List(Block.Paragraph(List(Inline.Text("Contract "), Inline.Placeholder("documentNumber"))))
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
      _          <- Migrations.migrate(c.jdbcUrl, c.username, c.password)
      _          <- FlowableEngine.migrateSchema(c.jdbcUrl, c.username, c.password)
      schemaUser <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, poolSize = 1)).map(Db(_))
      _          <- schemaUser
        .transactSystem(
          Db.sql(sql"insert into dms.tenant (id, name) values (${tenantId.value}, ${"Tenant"})".update.run())
        )
        .orDieWith(e => RuntimeException(e.toString))
      appSource <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", poolSize = 2))
      app       = Db(appSource)
      audit     = AuditTrailLive(app, TsaTrust(Nil))
      classes   = DocumentClassService(audit)
      templates = TemplateService(audit, DocumentClassProfiles(classes))
      ids <- app
        .transact(tenantId)(
          for
            _   <- RoleAssignments.Postgres.grant(admin, RoleAssignment(Role.TenantAdmin), admin)
            _   <- RoleAssignments.Postgres.grant(author, RoleAssignment(Role.Author), admin)
            _   <- RoleAssignments.Postgres.grant(owner, RoleAssignment(Role.Owner), admin)
            _   <- RoleAssignments.Postgres.grant(records, RoleAssignment(Role.RecordsManager), admin)
            _   <- RoleAssignments.Postgres.grant(reader, RoleAssignment(Role.Reader), admin)
            cls <- classes.createClass("contract", None, ClassDraft("Contract", None, Nil), admin)
            draft = TemplateDraft("Contract", None, cls.classId.value, 1, content)
            released <- templates.create(draft, UserId("designer-1"))
            _ <- templates.changeStatus(released.templateId, 1, TemplateStatus.Testing, UserId("designer-1"))
            _ <- templates.changeStatus(released.templateId, 1, TemplateStatus.Released, UserId("designer-2"))
          yield (released.templateId, cls.classId.value)
        )
        .mapError(e => RuntimeException(e.toString))
        .orDie
      _ <- setup.succeed(Setup(schemaUser, ids._1, ids._2))
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

  private type Env = Authenticator & Db & Authorizer & DocumentService & ApprovalWorkflow & NumberRangeService

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
      NumberRangeService.layer,
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

  private val routes =
    ZioHttpInterpreter().toHttp(
      DocumentApi.endpoints.map(_.widen[Env]) ++ NumberRangeApi.endpoints.map(_.widen[Env])
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

  private def as[A: JsonDecoder](response: (Status, String)): A =
    response._2.fromJson[A].fold(e => throw AssertionError(s"$e: ${response._1} ${response._2}"), identity)

  private def error(response: (Status, String)): Option[ApiError] = response._2.fromJson[ApiError].toOption

  private val year = LocalDate.now(ZoneId.of("Europe/Berlin")).getYear

  private def createDocument(): ZIO[Env, Nothing, DocumentView] =
    setup.await.flatMap(s =>
      call(
        author,
        "POST",
        "/api/v1/documents",
        Some(
          s"""{"templateId":"${s.released.value}","templateVersion":1,"title":"Contract",
             |"values":{"owner":{"type":"PersonRef","userId":"${owner.value}"}}}""".stripMargin
        )
      ).map(as[DocumentView])
    )

  private def assign(user: UserId, doc: UUID, number: String, reason: String) =
    call(user, "POST", s"/api/v1/documents/$doc/number", Some(AssignDocumentNumber(number, reason).toJson))

  private def auditCount(action: String, document: UUID): ZIO[Any, DbError, Long] =
    setup.await.flatMap(s =>
      s.owner.transactSystem(
        Db.sql(
          sql"""select count(*) from dms.audit_event where action = $action and object_id = $document
                and reason is not null""".query[Long].run().head
        )
      )
    )

  def spec = suite("NumberRangeApi")(
    test("API documentation contains the number range administration and the manual assignment") {
      val docs = sttp.tapir.docs.openapi
        .OpenAPIDocsInterpreter()
        .toOpenAPI((NumberRangeApi.endpoints ++ DocumentApi.endpoints).map(_.endpoint), "DMS", "test")
      assertTrue(
        docs.paths.pathItems.contains("/api/v1/admin/number-ranges"),
        docs.paths.pathItems.contains("/api/v1/admin/number-ranges/{id}"),
        docs.paths.pathItems.contains("/api/v1/admin/number-ranges/preview"),
        docs.paths.pathItems.contains("/api/v1/admin/number-range-assignments"),
        docs.paths.pathItems.contains("/api/v1/admin/number-range-assignments/{classId}"),
        docs.paths.pathItems.contains("/api/v1/admin/number-range-assignments/{classId}/backfill"),
        docs.paths.pathItems.contains("/api/v1/documents/{id}/number")
      )
    },
    test("tenant administrators manage number ranges and assign them to classes; others cannot") {
      for
        s        <- setup.await
        byAuthor <- call(author, "GET", "/api/v1/admin/number-ranges")
        byRecord <- call(
          records,
          "POST",
          "/api/v1/admin/number-ranges/preview",
          Some("""{"pattern":"A{N}"}""")
        )
        preview <- call(
          admin,
          "POST",
          "/api/v1/admin/number-ranges/preview",
          Some("""{"pattern":"VTR-{YYYY}-{NNNN}","startValue":1,"yearlyReset":true}""")
        ).map(as[NumberRangePreview])
        invalidPreview <- call(
          admin,
          "POST",
          "/api/v1/admin/number-ranges/preview",
          Some("""{"pattern":"VTR-{NNNN}","yearlyReset":true}""")
        ).map(as[NumberRangePreview])
        invalid <- call(
          admin,
          "POST",
          "/api/v1/admin/number-ranges",
          Some("""{"key":"contracts","name":"Contracts","pattern":"VTR {NNNN}"}""")
        )
        created <- call(
          admin,
          "POST",
          "/api/v1/admin/number-ranges",
          Some("""{"key":"contracts","name":"Contracts","pattern":"VTR-{YYYY}-{NNNN}","yearlyReset":true}""")
        )
        duplicate <- call(
          admin,
          "POST",
          "/api/v1/admin/number-ranges",
          Some("""{"key":"contracts","name":"Again","pattern":"X-{NN}"}""")
        )
        range = as[NumberRangeView](created)
        before <- call(admin, "GET", "/api/v1/admin/number-range-assignments").map(
          as[List[ClassNumberRangeView]]
        )
        assigned <- call(
          admin,
          "PUT",
          s"/api/v1/admin/number-range-assignments/${s.classId}",
          Some(s"""{"numberRangeId":"${range.id}"}""")
        ).map(as[ClassNumberRangeView])
        listed  <- call(admin, "GET", "/api/v1/admin/number-ranges").map(as[List[NumberRangeView]])
        doc     <- createDocument()
        after   <- call(admin, "GET", s"/api/v1/admin/number-ranges/${range.id}").map(as[NumberRangeView])
        changed <- call(
          admin,
          "PUT",
          s"/api/v1/admin/number-ranges/${range.id}",
          Some("""{"name":"Contracts","pattern":"VTR-{YYYY}-{NNNNN}","startValue":100,"yearlyReset":true}""")
        ).map(as[NumberRangeView])
        unknownClass <- call(
          admin,
          "PUT",
          s"/api/v1/admin/number-range-assignments/${UUID.randomUUID()}",
          Some("{}")
        )
      yield assertTrue(
        byAuthor._1 == Status.Forbidden,
        byRecord._1 == Status.Forbidden,
        preview.valid && preview.example.contains(s"VTR-$year-0001"),
        !invalidPreview.valid && invalidPreview.problems.exists(_.contains("yearly reset")),
        invalid._1 == Status.UnprocessableEntity,
        created._1 == Status.Created,
        range.nextNumber == s"VTR-$year-0001",
        duplicate._1 == Status.Conflict,
        before.exists(c => c.classKey == "contract" && c.mode == "inherit" && c.effectiveRangeId.isEmpty),
        assigned.mode == "range" && assigned.effectiveRangeId.contains(range.id),
        listed.exists(r => r.id == range.id && r.classIds == List(s.classId)),
        doc.documentNumber.contains(s"VTR-$year-0001"),
        doc.current.values
          .get("documentNumber")
          .exists(v => v.origin == "System" && v.source.contains("dms")),
        after.nextNumber == s"VTR-$year-0002",
        changed.nextNumber == s"VTR-$year-00100",
        unknownClass._1 == Status.NotFound
      )
    },
    test("records managers and the document's owner assign numbers manually with a reason; authors cannot") {
      for
        doc      <- createDocument()
        other    <- createDocument()
        byAuthor <- assign(author, doc.id, "ALT-1", "legacy number")
        byReader <- assign(reader, doc.id, "ALT-1", "legacy number")
        noReason <- assign(records, doc.id, "ALT-1", " ")
        byRecord <- assign(records, doc.id, "ALT-1", "legacy number")
        taken    <- assign(owner, other.id, "ALT-1", "duplicate")
        byOwner  <- assign(owner, other.id, "ALT-2", "legacy number")
        asAuthor <- call(author, "GET", s"/api/v1/documents/${doc.id}").map(as[DocumentView])
        asOwner  <- call(owner, "GET", s"/api/v1/documents/${doc.id}").map(as[DocumentView])
        audited  <- auditCount("document.number-assigned", doc.id)
      yield assertTrue(
        byAuthor._1 == Status.Forbidden,
        byReader._1 == Status.Forbidden,
        noReason._1 == Status.UnprocessableEntity,
        byRecord._1 == Status.Ok,
        as[DocumentNumberView](byRecord).documentNumber == "ALT-1",
        as[DocumentNumberView](byRecord).previous == doc.documentNumber,
        taken._1 == Status.Conflict,
        error(taken).exists(_.code == "conflict"),
        byOwner._1 == Status.Ok,
        asAuthor.documentNumber.contains("ALT-1"),
        asAuthor.current.values
          .get("documentNumber")
          .map(_.value)
          .contains(
            dms.documents.metadata.MetadataValue.Text("ALT-1")
          ),
        !asAuthor.canAssignNumber,
        asOwner.canAssignNumber,
        audited == 1L
      )
    },
    test("tenant administrators number existing documents of a class; audited and reindexed (DMS-116)") {
      for
        s      <- setup.await
        ranges <- call(admin, "GET", "/api/v1/admin/number-ranges").map(as[List[NumberRangeView]])
        range      = ranges.find(_.key == "contracts").get
        assignment = s"/api/v1/admin/number-range-assignments/${s.classId}"
        _         <- call(admin, "PUT", assignment, Some("{}"))
        first     <- createDocument()
        second    <- createDocument()
        noRange   <- call(admin, "GET", s"$assignment/backfill")
        _         <- call(admin, "PUT", assignment, Some(s"""{"numberRangeId":"${range.id}"}"""))
        byAuthor  <- call(author, "POST", s"$assignment/backfill")
        byRecords <- call(records, "GET", s"$assignment/backfill")
        preview   <- call(admin, "GET", s"$assignment/backfill").map(as[NumberBackfillPreview])
        result    <- call(admin, "POST", s"$assignment/backfill").map(as[NumberBackfillResult])
        again     <- call(admin, "POST", s"$assignment/backfill").map(as[NumberBackfillResult])
        reloaded  <- call(author, "GET", s"/api/v1/documents/${first.id}").map(as[DocumentView])
        counts    <- s.owner.transactSystem(
          Db.sql(
            (
              sql"""select count(*) from dms.audit_event where action = 'document.number-assigned'
                    and object_id = ${first.id}""".query[Long].run().head,
              sql"""select count(*) from dms.audit_event where action = 'number-range.backfilled'
                    and object_id = ${s.classId}""".query[Long].run().head,
              sql"""select count(*) from dms.job where kind = 'search.index-document'
                    and payload ->> 'documentId' in (${first.id.toString}, ${second.id.toString})"""
                .query[Long]
                .run()
                .head
            )
          )
        )
        (assigned, runs, jobs) = counts
        numbers                = result.entries.flatMap(_.number)
      yield assertTrue(
        first.documentNumber.isEmpty,
        second.documentNumber.isEmpty,
        noRange._1 == Status.Conflict,
        byAuthor._1 == Status.Forbidden,
        byRecords._1 == Status.Forbidden,
        preview.documents == 2,
        preview.numberRangeId == range.id,
        preview.classIds == List(s.classId),
        result.numbered == 2 && result.skipped == 0 && result.failed == 0 && result.remaining == 0,
        result.entries.map(_.documentId) == List(first.id, second.id),
        numbers.headOption.contains(preview.nextNumber),
        numbers.distinct.size == 2,
        numbers == numbers.sorted,
        again.numbered == 0 && again.entries.isEmpty,
        reloaded.documentNumber == numbers.headOption,
        assigned == 1L,
        runs == 2L,
        jobs == 2L
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
