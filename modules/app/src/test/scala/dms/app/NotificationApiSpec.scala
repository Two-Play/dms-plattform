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
import dms.authz.{Authorizer, Groups, Role, RoleAssignment, RoleAssignments}
import dms.db.queue.{JobQueue, OutboxEvent}
import dms.db.{Db, DbConfig, DbError, Jsonb, Migrations}
import dms.identity.*
import dms.kernel.{DocumentId, TenantId, UserId}
import dms.notifications.*
import dms.notifications.core.{DeliverySettings, NotificationJobs}
import org.testcontainers.utility.DockerImageName
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.*
import zio.http.{Body, Header, Headers, MediaType, Request, Status, URL}
import zio.json.*
import zio.test.*

import java.time.Instant
import java.util.{Date, UUID}
import javax.sql.DataSource

/** The notification endpoints over HTTP (DMS-130, ADR 0052) with PostgreSQL in Testcontainers: the bell's
  * list, unread count and marking read (only one's own notifications), preferences with validation, and the
  * dead letters for tenant administrators (list, send again). Notifications are delivered through the real
  * delivery job with test doubles for events, read check, directory and mail.
  */
object NotificationApiSpec extends ZIOSpecDefault:

  private val issuer   = "http://localhost:8085"
  private val audience = "project-1"
  private val orgId    = "org-1"
  private val tenantId = TenantId.random()
  private val admin    = UserId("admin")
  private val anna     = UserId("anna")
  private val ben      = UserId("ben")

  private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("key-1").generate()

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

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
      _           <- Migrations.migrate(c.jdbcUrl, c.username, c.password)
      schemaOwner <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, poolSize = 1)).map(Db(_))
      _           <- schemaOwner
        .transactSystem(
          Db.sql(
            sql"""insert into dms.tenant (id, name, zitadel_org_id) values (${tenantId.value}, ${"A"}, $orgId)""".update
              .run()
          )
        )
        .orDieWith(e => RuntimeException(e.toString))
      appDb <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", poolSize = 4)).map(Db(_))
      _     <- appDb
        .transact(tenantId)(
          RoleAssignments.Postgres.grant(admin, RoleAssignment(Role.TenantAdmin), admin) *>
            RoleAssignments.Postgres.grant(anna, RoleAssignment(Role.Reader), admin) *>
            RoleAssignments.Postgres.grant(ben, RoleAssignment(Role.Reader), admin)
        )
        .orDieWith(e => RuntimeException(e.toString))
    yield appDb
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  private val authenticator = Authenticator(
    TokenVerifier(
      AuthConfig(issuer, audience),
      ImmutableJWKSet[SecurityContext](JWKSet(signingKey.toPublicJWK))
    ),
    new TenantDirectory:
      def tenantFor(organizationId: String): IO[DbError, Option[TenantId]] =
        ZIO.succeed(Option.when(organizationId == orgId)(tenantId))
  )

  private val mailUp = Unsafe.unsafe(implicit u => Ref.unsafe.make(true))

  private object Events extends NotificationEvents:
    def concerns(event: OutboxEvent): Boolean                  = true
    def describe(event: OutboxEvent): IO[String, List[Notice]] =
      ZIO.succeed(
        List(
          Notice(
            "acknowledgement.reminder",
            NotificationCategory.Acknowledgement,
            List(anna),
            Some(DocumentRef(DocumentId(event.aggregateId), None)),
            Map("version" -> "1.0")
          )
        )
      )

  private val settings = DeliverySettings("https://dms.example.org", emailEnabled = true, maxAttempts = 1)

  private def jobs(db: Db) = NotificationJobs(
    db,
    Events,
    new NotificationDocuments:
      def visibleTo(t: TenantId, user: UserId, ref: DocumentRef) =
        ZIO.some(DocumentFacts("Code of conduct", Some("1.0"), None))
    ,
    new RecipientDirectory:
      def people(t: TenantId, users: Set[UserId]) =
        ZIO.succeed(
          users.map(u => u -> Person(Some(u.value.capitalize), Some(s"${u.value}@example.org"))).toMap
        )
    ,
    new MailSender:
      def send(mail: Mail) = mailUp.get.flatMap(up => ZIO.unless(up)(ZIO.fail("relay down")).unit)
    ,
    settings
  )

  private type Env = NotificationApi.Env

  private def env: ZLayer[Db, Nothing, Env] =
    ZLayer.makeSome[Db, Env](
      ZLayer.succeed(authenticator),
      RoleAssignments.postgres,
      Groups.postgres,
      Authorizer.layer,
      ZLayer.fromFunction(jobs),
      ZLayer.succeed(settings)
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

  private val routes = ZioHttpInterpreter().toHttp(NotificationApi.endpoints)

  private def call(user: UserId, method: String, path: String, body: Option[String] = None) =
    val url     = URL.decode(path).toOption.get
    val headers = Headers(
      Header.Authorization.Bearer(token(user)),
      Header.ContentType(MediaType.application.json)
    )
    val request = method match
      case "POST" => Request.post(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case "PUT"  => Request.put(url, Body.fromString(body.getOrElse("{}"))).addHeaders(headers)
      case _      => Request.get(url).addHeaders(headers)
    ZIO
      .serviceWithZIO[Db](db =>
        ZIO
          .scoped(routes.runZIO(request))
          .flatMap(r => r.body.asString.map(b => (r.status, b)).orDie)
          .provideLayer(ZLayer.succeed(db) >>> env)
      )

  private def as[A: JsonDecoder](response: (Status, String)): A =
    response._2.fromJson[A].fold(e => throw AssertionError(s"$e: ${response._1} ${response._2}"), identity)

  private val eventIds = Unsafe.unsafe(implicit u => Ref.unsafe.make(5000L))

  /** Delivers one notification to Anna through the outbox subscriber and the delivery jobs. */
  private def notifyAnna: ZIO[Db, Throwable, Unit] =
    for
      db <- ZIO.service[Db]
      id <- eventIds.updateAndGet(_ + 1)
      j = jobs(db)
      _ <- j
        .deliver(OutboxEvent(id, tenantId, "document", UUID.randomUUID(), "test", Jsonb.empty, None, 1))
        .mapError(RuntimeException(_))
      claimed <- JobQueue
        .claim(db, "w", Set(NotificationJobs.DeliverKind), 50, 1.minute)
        .mapError(e => RuntimeException(e.toString))
      _ <- ZIO.foreachDiscard(claimed)(job =>
        j.handlers(job.kind)(job)
          .either
          .flatMap {
            case Right(_) => JobQueue.complete(db, job.id, "w")
            case Left(e)  => JobQueue.fail(db, job.id, "w", e, Duration.Zero)
          }
          .mapError(e => RuntimeException(e.toString))
      )
    yield ()

  def spec = suite("Notification API")(
    test("API documentation contains the notification endpoints") {
      val docs = sttp.tapir.docs.openapi
        .OpenAPIDocsInterpreter()
        .toOpenAPI(NotificationApi.endpoints.map(_.endpoint), "DMS Platform", "test")
      assertTrue(
        docs.paths.pathItems.contains("/api/v1/notifications"),
        docs.paths.pathItems.contains("/api/v1/notifications/unread-count"),
        docs.paths.pathItems.contains("/api/v1/notifications/{id}/read"),
        docs.paths.pathItems.contains("/api/v1/notifications/read-all"),
        docs.paths.pathItems.contains("/api/v1/notifications/preferences"),
        docs.paths.pathItems.contains("/api/v1/admin/notifications/deliveries"),
        docs.paths.pathItems.contains("/api/v1/admin/notifications/deliveries/{id}/redeliver")
      )
    },
    test("the bell: list, unread count, mark one and all read; nobody else's notifications") {
      for
        _     <- ZIO.foreachDiscard(1 to 3)(_ => notifyAnna)
        count <- call(anna, "GET", "/api/v1/notifications/unread-count").map(as[UnreadCountView])
        page  <- call(anna, "GET", "/api/v1/notifications?limit=2").map(as[NotificationPageView])
        bens  <- call(ben, "GET", "/api/v1/notifications").map(as[NotificationPageView])
        first = page.items.head
        foreign <- call(ben, "POST", s"/api/v1/notifications/${first.id}/read")
        own     <- call(anna, "POST", s"/api/v1/notifications/${first.id}/read")
        after   <- call(anna, "GET", "/api/v1/notifications/unread-count").map(as[UnreadCountView])
        unread  <- call(anna, "GET", "/api/v1/notifications?unread=true").map(as[NotificationPageView])
        all     <- call(anna, "POST", "/api/v1/notifications/read-all").map(as[MarkedReadView])
        none    <- call(anna, "GET", "/api/v1/notifications/unread-count").map(as[UnreadCountView])
      yield assertTrue(
        count.unread == 3,
        page.items.size == 2,
        page.total == 3,
        first.subject == "Erinnerung: Kenntnisnahme offen – Code of conduct (1.0)",
        first.category == "acknowledgement",
        first.link.exists(_.startsWith("/documents/")),
        first.readAt.isEmpty,
        bens.total == 0,
        foreign._1 == Status.NotFound,
        own._1 == Status.NoContent,
        after.unread == 2,
        unread.items.size == 2 && !unread.items.exists(_.id == first.id),
        all.marked == 2,
        none.unread == 0
      )
    },
    test("preferences: defaults, change language and email per category, validation") {
      for
        initial <- call(ben, "GET", "/api/v1/notifications/preferences").map(as[NotificationPreferencesView])
        changed <- call(
          ben,
          "PUT",
          "/api/v1/notifications/preferences",
          Some(ChangeNotificationPreferences(Some("en"), Map("workflow" -> false)).toJson)
        ).map(as[NotificationPreferencesView])
        badLanguage <- call(
          ben,
          "PUT",
          "/api/v1/notifications/preferences",
          Some(ChangeNotificationPreferences(Some("fr"), Map.empty).toJson)
        )
        badCategory <- call(
          ben,
          "PUT",
          "/api/v1/notifications/preferences",
          Some(ChangeNotificationPreferences(None, Map("spam" -> true)).toJson)
        )
        reset <- call(
          ben,
          "PUT",
          "/api/v1/notifications/preferences",
          Some(ChangeNotificationPreferences(Some(""), Map.empty).toJson)
        ).map(as[NotificationPreferencesView])
      yield assertTrue(
        initial.language.isEmpty,
        initial.tenantLanguage == "de",
        initial.emailAvailable,
        initial.categories.map(_.category) ==
          List("workflow", "acknowledgement", "deputyship", "publication", "legal-hold"),
        initial.categories.forall(c => c.email == c.emailByDefault),
        changed.language.contains("en"),
        changed.categories.find(_.category == "workflow").exists(!_.email),
        badLanguage._1 == Status.UnprocessableEntity,
        badCategory._1 == Status.UnprocessableEntity,
        reset.language.isEmpty,
        reset.categories.find(_.category == "workflow").exists(!_.email)
      )
    },
    test("administrators see dead deliveries, send them again and set the tenant's language") {
      for
        _        <- mailUp.set(false)
        _        <- notifyAnna
        _        <- mailUp.set(true)
        denied   <- call(anna, "GET", "/api/v1/admin/notifications/deliveries")
        dead     <- call(admin, "GET", "/api/v1/admin/notifications/deliveries").map(as[List[DeliveryView]])
        again    <- call(admin, "POST", s"/api/v1/admin/notifications/deliveries/${dead.head.id}/redeliver")
        twice    <- call(admin, "POST", s"/api/v1/admin/notifications/deliveries/${dead.head.id}/redeliver")
        byReader <- call(anna, "POST", s"/api/v1/admin/notifications/deliveries/${dead.head.id}/redeliver")
        language <- call(admin, "PUT", "/api/v1/admin/notifications/language", Some("""{"language":"en"}"""))
        bad      <- call(admin, "PUT", "/api/v1/admin/notifications/language", Some("""{"language":"xx"}"""))
        prefs    <- call(ben, "GET", "/api/v1/notifications/preferences").map(as[NotificationPreferencesView])
      yield assertTrue(
        denied._1 == Status.Forbidden,
        dead.map(d => (d.recipient, d.channel, d.status)) == List(("anna", "email", "dead")),
        dead.head.lastError.contains("relay down"),
        again._1 == Status.Accepted,
        twice._1 == Status.Conflict,
        byReader._1 == Status.Forbidden,
        language._1 == Status.Ok,
        bad._1 == Status.UnprocessableEntity,
        prefs.tenantLanguage == "en"
      )
    }
  ).provideShared(database) @@ TestAspect.sequential @@ TestAspect.withLiveClock
