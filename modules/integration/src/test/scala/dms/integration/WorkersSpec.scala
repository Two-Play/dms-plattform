package dms.integration

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.db.queue.{ClaimedJob, JobQueue, Outbox, OutboxEvent}
import dms.db.{Db, DbConfig, DbError, Jsonb, Migrations}
import dms.kernel.TenantId
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

import java.util.UUID
import javax.sql.DataSource

/** Job queue and outbox delivery against PostgreSQL (DMS-31, ADR 0010). */
object WorkersSpec extends ZIOSpecDefault:

  final case class Env(app: Db, app2: Db, owner: Db)

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
      app2  <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", 4))
      owner <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, 2))
    yield Env(Db(app), Db(app2), Db(owner))
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

  private def config(name: String) =
    WorkerConfig(name, batchSize = 5, lease = 30.seconds, pollInterval = 50.millis, retryBase = Duration.Zero)

  private def jobStatus(id: Long): ZIO[Env, DbError, (String, Int)] =
    ZIO.serviceWithZIO[Env](
      _.owner.transactSystem(
        Db.sql(sql"select status, attempts from dms.job where id = $id".query[(String, Int)].run().head)
      )
    )

  /** Runs `runner.runOnce` until it claims nothing. */
  private def drain(runOnce: IO[DbError, Int]): IO[DbError, Unit] =
    runOnce.repeatUntil(_ == 0).unit

  def spec = suite("Job queue and outbox")(
    test("a tenant job is processed once and completed") {
      for
        env  <- ZIO.service[Env]
        t    <- newTenant
        seen <- Ref.make(List.empty[ClaimedJob])
        id   <- env.app.transact(t)(JobQueue.enqueue("test.echo", Jsonb("""{"n":1}""")))
        runner = JobRunner(env.app, Map("test.echo" -> (job => seen.update(job :: _))), config("w1"))
        _      <- drain(runner.runOnce)
        jobs   <- seen.get
        status <- jobStatus(id.get)
      yield assertTrue(jobs.map(_.tenant) == List(Some(t)), status == ("done", 1))
    },
    test("an idempotency key enqueues at most one job") {
      for
        env <- ZIO.service[Env]
        t   <- newTenant
        key = s"once:${UUID.randomUUID()}"
        first  <- env.app.transact(t)(JobQueue.enqueue("test.noop", idempotencyKey = Some(key)))
        second <- env.app.transact(t)(JobQueue.enqueue("test.noop", idempotencyKey = Some(key)))
      yield assertTrue(first.isDefined, second.isEmpty)
    },
    test("two workers on separate pools process every job exactly once") {
      for
        env <- ZIO.service[Env]
        t   <- newTenant
        _   <- env.app.transact(t)(
          ZIO.foreachDiscard(1 to 40)(n => JobQueue.enqueue("test.count", Jsonb(s"""{"n":$n}""")))
        )
        count <- Ref.make(Map.empty[Long, Int])
        handler: JobHandler = job => count.update(m => m.updated(job.id, m.getOrElse(job.id, 0) + 1))
        a                   = JobRunner(env.app, Map("test.count" -> handler), config("worker-a"))
        b                   = JobRunner(env.app2, Map("test.count" -> handler), config("worker-b"))
        _      <- drain(a.runOnce) <&> drain(b.runOnce)
        result <- count.get
      yield assertTrue(result.size == 40, result.values.forall(_ == 1))
    },
    test("a failing job is retried and becomes dead after its last attempt") {
      for
        env      <- ZIO.service[Env]
        t        <- newTenant
        attempts <- Ref.make(0)
        flaky    <- env.app.transact(t)(JobQueue.enqueue("test.flaky", maxAttempts = 5))
        broken   <- env.app.transact(t)(JobQueue.enqueue("test.broken", maxAttempts = 3))
        runner = JobRunner(
          env.app,
          Map(
            "test.flaky" -> (_ =>
              attempts.updateAndGet(_ + 1).flatMap(n => ZIO.fail("not yet").when(n < 3).unit)
            ),
            "test.broken" -> (_ => ZIO.fail("always"))
          ),
          config("w2")
        )
        _            <- drain(runner.runOnce)
        flakyStatus  <- jobStatus(flaky.get)
        brokenStatus <- jobStatus(broken.get)
      yield assertTrue(flakyStatus == ("done", 3), brokenStatus == ("dead", 3))
    },
    test("a job of a crashed worker is taken over after its lease expires") {
      for
        env     <- ZIO.service[Env]
        t       <- newTenant
        id      <- env.app.transact(t)(JobQueue.enqueue("test.lease"))
        claimed <- JobQueue.claim(
          env.app,
          "crashed",
          Set("test.lease"),
          10,
          1.second
        ) // claims and never completes
        before <- JobRunner(env.app, Map("test.lease" -> (_ => ZIO.unit)), config("rescuer")).runOnce
        _      <- ZIO.sleep(1500.millis)
        _      <- drain(JobRunner(env.app, Map("test.lease" -> (_ => ZIO.unit)), config("rescuer")).runOnce)
        status <- jobStatus(id.get)
      yield assertTrue(claimed.exists(_.id == id.get), before == 0, status == ("done", 2))
    },
    test("a worker only claims the kinds it handles") {
      for
        env     <- ZIO.service[Env]
        t       <- newTenant
        id      <- env.app.transact(t)(JobQueue.enqueue("test.unknown-to-old-workers"))
        claimed <- JobRunner(env.app, Map("test.other" -> (_ => ZIO.unit)), config("old")).runOnce
        status  <- jobStatus(id.get)
      yield assertTrue(claimed == 0, status == ("queued", 0))
    },
    test("tenants cannot see or enqueue each other's jobs") {
      for
        env      <- ZIO.service[Env]
        a        <- newTenant
        b        <- newTenant
        _        <- env.app.transact(a)(JobQueue.enqueue("test.private"))
        visibleB <- env.app.transact(b)(Db.sql(sql"select count(*) from dms.job".query[Long].run().head))
        foreign  <- env.app
          .transact(b)(
            Db.sql(
              sql"select dms.job_enqueue(${a.value}, 'x', '{}'::jsonb, null, null, 1)"
                .query[Option[Long]]
                .run()
            )
          )
          .either
      yield assertTrue(visibleB == 0L, foreign.left.exists(_.isInstanceOf[DbError.PermissionDenied]))
    },
    test("system jobs carry no tenant and tenants can be listed for fan-out") {
      for
        env <- ZIO.service[Env]
        t   <- newTenant
        key = s"system:${UUID.randomUUID()}"
        _    <- env.app.transactSystem(JobQueue.enqueueSystem("test.system", idempotencyKey = Some(key)))
        seen <- Ref.make(List.empty[ClaimedJob])
        _ <- drain(JobRunner(env.app, Map("test.system" -> (j => seen.update(j :: _))), config("w3")).runOnce)
        jobs    <- seen.get
        tenants <- JobQueue.tenantIds(env.app)
      yield assertTrue(jobs.map(_.tenant) == List(None), tenants.contains(t))
    },
    test("outbox events are delivered after commit, never after rollback, and retried on failure") {
      for
        env <- ZIO.service[Env]
        t   <- newTenant
        aggregate = UUID.randomUUID()
        committed <- env.app.transact(t)(Outbox.publish("document", aggregate, "published", Jsonb("{}")))
        _         <- env.app
          .transact(t)(Outbox.publish("document", aggregate, "rolled-back", Jsonb("{}")) *> ZIO.fail("abort"))
          .either
        failOnce  <- Ref.make(true)
        delivered <- Ref.make(List.empty[OutboxEvent])
        relay = OutboxRelay(
          env.app,
          event =>
            failOnce
              .getAndSet(false)
              .flatMap(first => if first then ZIO.fail("receiver down") else delivered.update(event :: _)),
          config("relay")
        )
        _      <- drain(relay.runOnce)
        events <- delivered.get
      yield assertTrue(
        events.map(_.id) == List(committed),
        events.map(_.eventType) == List("published"),
        events.head.attempts == 2
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
