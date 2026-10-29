package dms.integration.webhooks

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.db.queue.{Outbox, OutboxEvent}
import dms.db.{Db, DbConfig, DbError, Jsonb, Migrations}
import dms.integration.{JobRunner, OutboxRelay, WorkerConfig}
import dms.kernel.TenantId
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Webhook delivery through outbox and job queue against PostgreSQL (DMS-58, ADR 0035), with a fake receiver:
  * signature, retries, dead letter, redelivery, SSRF block, filters and no event loss while the receiver is
  * down.
  */
object WebhookJobsSpec extends ZIOSpecDefault:

  final case class Env(app: Db, owner: Db)

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  private val env: ZLayer[Any, Throwable, Env] = ZLayer.scoped(
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
      app   <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", 4))
      owner <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, 2))
    yield Env(Db(app), Db(owner))
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  private def newTenant: ZIO[Env, DbError, TenantId] =
    ZIO.serviceWithZIO[Env] { e =>
      val id = TenantId.random()
      e.owner
        .transactSystem(
          Db.sql(sql"insert into dms.tenant (id, name) values (${id.value}, ${"Tenant"})".update.run())
        )
        .as(id)
    }

  /** Stand-in for the documents module: `test.status-changed` events with `type` and `classPath` in the
    * payload.
    */
  private object TestEvents extends WebhookEvents:
    def concerns(event: OutboxEvent): Boolean = event.eventType == "test.status-changed"

    def describe(event: OutboxEvent): IO[String, Option[WebhookNotification]] =
      ZIO.fromEither(event.payload.value.fromJson[Json.Obj]).map { payload =>
        val eventType = payload.get("type").collect { case Json.Str(s) => s }
        val path      =
          payload.get("classPath").collect { case Json.Arr(a) => a.collect { case Json.Str(s) => s } }
        eventType.map(t =>
          WebhookNotification(
            t,
            event.aggregateId,
            path.fold(Nil)(_.toList),
            Instant.parse("2026-10-29T00:00:00Z"),
            Json.Obj("documentId" -> Json.Str(event.aggregateId.toString))
          )
        )
      }

  private val secret = WebhookSignature.newSecret()

  private def settings(maxAttempts: Int) =
    WebhookSettings(allowedHosts = Set("127.0.0.1"), timeout = 2.seconds, maxAttempts = maxAttempts)

  private def worker(name: String) =
    WorkerConfig(
      name,
      batchSize = 20,
      lease = 30.seconds,
      pollInterval = 50.millis,
      retryBase = Duration.Zero
    )

  private def jobs(maxAttempts: Int = 5): ZIO[Env, Nothing, WebhookJobs] =
    ZIO.serviceWith[Env](e =>
      WebhookJobs(e.app, TestEvents, HttpWebhookSender(settings(maxAttempts)), settings(maxAttempts))
    )

  private def subscribe(
      tenant: TenantId,
      url: String,
      types: Set[String] = WebhookEventType.all,
      classes: Set[String] = Set.empty
  ): ZIO[Env, DbError, WebhookSubscription] =
    ZIO.serviceWithZIO[Env](
      _.app.transact(tenant)(WebhookStore.create(url, secret, types, classes, None, "admin"))
    )

  private def emit(tenant: TenantId, eventType: String, classPath: List[String] = List("policy")) =
    ZIO.serviceWithZIO[Env](
      _.app.transact(tenant)(
        Outbox.publish(
          "document",
          UUID.randomUUID(),
          "test.status-changed",
          Jsonb(
            Json
              .Obj("type" -> Json.Str(eventType), "classPath" -> Json.Arr(classPath.map(Json.Str(_))*))
              .toJson
          )
        )
      )
    )

  /** The outbox relay with the webhook subscriber, until it claims nothing. */
  private def relay(w: WebhookJobs): ZIO[Env, DbError, Unit] =
    ZIO.serviceWithZIO[Env](e =>
      OutboxRelay(e.app, w.deliver, worker("relay")).runOnce.repeatUntil(_ == 0).unit
    )

  /** One run of the job runner over the due webhook jobs. */
  private def runJobs(w: WebhookJobs): ZIO[Env, DbError, Int] =
    ZIO.serviceWithZIO[Env](e => JobRunner(e.app, w.handlers, worker("webhooks")).runOnce)

  private def deliveries(tenant: TenantId, s: WebhookSubscription) =
    ZIO.serviceWithZIO[Env](_.app.transact(tenant)(WebhookStore.deliveries(s.id, None, 50)))

  private def published(eventId: Long): ZIO[Env, DbError, Boolean] =
    ZIO.serviceWithZIO[Env](
      _.owner.transactSystem(
        Db.sql(
          sql"select published_at is not null from dms.outbox_event where id = $eventId"
            .query[Boolean]
            .run()
            .head
        )
      )
    )

  def spec = suite("Webhook delivery")(
    test("a matching event is delivered once, signed, with the same id on every attempt") {
      ZIO.scoped(
        for
          receiver <- FakeReceiver.make
          t        <- newTenant
          w        <- jobs()
          s        <- subscribe(t, receiver.url)
          event    <- emit(t, WebhookEventType.Published)
          _        <- relay(w)
          _        <- relay(w) // a second relay run adds nothing
          _        <- runJobs(w)
          got      <- receiver.requests
          logged   <- deliveries(t, s)
          request = got.head
          id      = request.header(WebhookSignature.IdHeader)
          ts      = request.header(WebhookSignature.TimestampHeader).toLong
          body    = request.body.fromJson[Json.Obj].toOption.get
        yield assertTrue(
          got.size == 1,
          id == logged.head.id.toString,
          WebhookSignature.verify(
            secret,
            id,
            ts,
            request.body,
            request.header(WebhookSignature.SignatureHeader)
          ),
          !WebhookSignature.verify(
            secret,
            id,
            ts + 1,
            request.body,
            request.header(WebhookSignature.SignatureHeader)
          ),
          !WebhookSignature.verify(
            WebhookSignature.newSecret(),
            id,
            ts,
            request.body,
            request.header(WebhookSignature.SignatureHeader)
          ),
          request.header("content-type") == "application/json",
          request.header("user-agent") == WebhookJobs.UserAgent,
          body.get("type").contains(Json.Str(WebhookEventType.Published)),
          body.get("data").exists(_.toJson.contains(s""""eventId":$event""")),
          logged.map(d => (d.status, d.attempts, d.lastStatusCode)) == List(("delivered", 1, Some(204)))
        )
      )
    },
    test("event types and the class filter select the subscriptions") {
      ZIO.scoped(
        for
          receiver  <- FakeReceiver.make
          t         <- newTenant
          w         <- jobs()
          policies  <- subscribe(t, receiver.url, classes = Set("policy"))
          contracts <- subscribe(t, receiver.url, classes = Set("contract"))
          withdrawn <- subscribe(t, receiver.url, types = Set(WebhookEventType.Withdrawn))
          _         <- emit(t, WebhookEventType.Published, List("policy", "it-policy"))
          _         <- emit(t, "document.other")
          _         <- relay(w)
          _         <- runJobs(w)
          p         <- deliveries(t, policies)
          c         <- deliveries(t, contracts)
          wd        <- deliveries(t, withdrawn)
          got       <- receiver.requests
        yield assertTrue(p.map(_.status) == List("delivered"), c.isEmpty, wd.isEmpty, got.size == 1)
      )
    },
    test("no event is lost while the receiver is down: the outbox completes and the delivery is retried") {
      ZIO.scoped(
        for
          receiver <- FakeReceiver.make
          _        <- receiver.down
          t        <- newTenant
          w        <- jobs()
          s        <- subscribe(t, receiver.url)
          event    <- emit(t, WebhookEventType.Withdrawn)
          _        <- relay(w)
          relayed  <- published(event)
          _        <- runJobs(w)
          _        <- runJobs(w)
          failing  <- deliveries(t, s)
          _        <- receiver.up
          _        <- runJobs(w)
          after    <- deliveries(t, s)
          got      <- receiver.requests
        yield assertTrue(
          relayed,
          failing.map(d => (d.status, d.attempts, d.lastStatusCode)) == List(("retrying", 2, Some(503))),
          failing.head.lastError.exists(_.contains("503")),
          after.map(d => (d.status, d.attempts)) == List(("delivered", 3)),
          got.size == 3,
          got.map(_.header(WebhookSignature.IdHeader)).distinct.size == 1
        )
      )
    },
    test("after the last attempt a delivery is dead; an administrator sends it again") {
      ZIO.scoped(
        for
          receiver <- FakeReceiver.make
          t        <- newTenant
          w        <- jobs(maxAttempts = 3)
          // Nothing listens on the port any more: connection refused.
          gone <- ZIO.scoped(FakeReceiver.make.map(_.url))
          s    <- subscribe(t, gone)
          _    <- emit(t, WebhookEventType.Superseded)
          _    <- relay(w)
          _    <- runJobs(w).repeatN(3)
          dead <- deliveries(t, s)
          env  <- ZIO.service[Env]
          // The receiver is reachable again under the subscription's new URL.
          _ <- env.app.transact(t)(
            WebhookStore.update(s.id, receiver.url, s.eventTypes, s.documentClasses, None, active = true) *>
              w.redeliver(dead.head.id)
          )
          reopened <- deliveries(t, s)
          _        <- runJobs(w)
          after    <- deliveries(t, s)
        yield assertTrue(
          dead.map(d => (d.status, d.attempts)) == List(("dead", 3)),
          dead.head.lastStatusCode.isEmpty,
          dead.head.lastError.exists(_.contains("not reachable")),
          reopened.head.status == "pending",
          after.map(d => (d.status, d.attempts)) == List(("delivered", 4))
        )
      )
    },
    test("a target that resolves to a blocked address is not called and the delivery is dead at once") {
      ZIO.scoped(
        for
          receiver <- FakeReceiver.make
          t        <- newTenant
          env      <- ZIO.service[Env]
          // Strict settings: 127.0.0.1 is not allowed. The subscription was saved before the rule applied.
          strict = WebhookJobs(env.app, TestEvents, HttpWebhookSender(WebhookSettings()), WebhookSettings())
          s   <- subscribe(t, receiver.url.replace("http://", "https://"))
          _   <- emit(t, WebhookEventType.Published)
          _   <- relay(strict)
          _   <- runJobs(strict)
          d   <- deliveries(t, s)
          got <- receiver.requests
        yield assertTrue(
          got.isEmpty,
          d.map(x => (x.status, x.attempts)) == List(("dead", 1)),
          d.head.lastError.exists(_.contains("non-public address"))
        )
      )
    },
    test("deliveries of a deactivated subscription are skipped") {
      ZIO.scoped(
        for
          receiver <- FakeReceiver.make
          t        <- newTenant
          w        <- jobs()
          env      <- ZIO.service[Env]
          s        <- subscribe(t, receiver.url)
          _        <- emit(t, WebhookEventType.Published)
          _        <- relay(w)
          _        <- env.app.transact(t)(
            WebhookStore.update(s.id, s.url, s.eventTypes, s.documentClasses, None, active = false)
          )
          _   <- runJobs(w)
          d   <- deliveries(t, s)
          got <- receiver.requests
        yield assertTrue(got.isEmpty, d.map(_.status) == List("skipped"))
      )
    },
    test("tenants do not see each other's subscriptions and deliveries") {
      for
        a    <- newTenant
        b    <- newTenant
        env  <- ZIO.service[Env]
        _    <- subscribe(a, "https://intranet.example.com/hook")
        mine <- env.app.transact(a)(WebhookStore.list)
        seen <- env.app.transact(b)(WebhookStore.list)
        rows <- env.app.transact(b)(
          Db.sql(sql"select count(*) from dms.webhook_delivery".query[Long].run().head)
        )
      yield assertTrue(mine.size == 1, seen.isEmpty, rows == 0L)
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
