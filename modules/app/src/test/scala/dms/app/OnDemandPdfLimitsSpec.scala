package dms.app

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.db.{Db, DbConfig, DbError, Migrations}
import dms.kernel.{TenantId, UserId}
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

import javax.sql.DataSource

/** The limits of PDFs printed on demand (DMS-118, ADR 0073) against a real database: what the buckets hold,
  * that application instances share them, that they are separate per tenant, and what stays per instance.
  */
object OnDemandPdfLimitsSpec extends ZIOSpecDefault:

  import OnDemandPdfRefused.Limit

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  final case class Databases(app: Db, owner: Db)

  private val databases: ZLayer[Any, Throwable, Databases] = ZLayer.scoped(
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
      owner <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, poolSize = 1)).map(Db(_))
      app   <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", poolSize = 8)).map(Db(_))
    yield Databases(app, owner)
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  private val noInFlight = OnDemandPdfConfig(0, 0, 0, 0, 7)

  private def newTenant: ZIO[Databases, DbError, TenantId] =
    for
      owner <- ZIO.serviceWith[Databases](_.owner)
      tenant = TenantId.random()
      _ <- owner.transactSystem(
        Db.sql(sql"insert into dms.tenant (id, name) values (${tenant.value}, ${"Tenant"})".update.run())
      )
    yield tenant

  private def instance(config: OnDemandPdfConfig): URIO[Databases, OnDemandPdfLimits] =
    for
      app      <- ZIO.serviceWith[Databases](_.app)
      inFlight <- Ref.make(Map.empty[TenantId, Int])
    yield OnDemandPdfLimits(app, config, inFlight)

  /** The refusal, if any, of one print that ends at once. */
  private def ask(limits: OnDemandPdfLimiter, tenant: TenantId, user: String) =
    ZIO.scoped(limits.admit(tenant, UserId(user))).either.flatMap {
      case Right(())                   => ZIO.none
      case Left(r: OnDemandPdfRefused) => ZIO.some(r)
      case Left(e)                     => ZIO.die(RuntimeException(e.toString))
    }

  /** As if `seconds` had passed for the buckets of the tenant. */
  private def elapse(tenant: TenantId, seconds: Int): ZIO[Databases, DbError, Unit] =
    ZIO
      .serviceWithZIO[Databases](
        _.owner.transactSystem(
          Db.sql(
            sql"""update dms.on_demand_pdf_allowance set updated_at = updated_at - make_interval(secs => $seconds)
                  where tenant_id = ${tenant.value}""".update.run()
          )
        )
      )
      .unit

  private def rows(tenant: TenantId, viewedBy: TenantId): ZIO[Databases, DbError, Long] =
    ZIO.serviceWithZIO[Databases](
      _.app.transact(viewedBy)(
        Db.sql(
          sql"select count(*) from dms.on_demand_pdf_allowance where tenant_id = ${tenant.value}"
            .query[Long]
            .run()
            .head
        )
      )
    )

  def spec = suite("Limits of PDFs printed on demand")(
    test("a user gets a burst, then is refused with the time until the next token; others go on") {
      for
        tenant <- newTenant
        limits <- instance(noInFlight.copy(perUserPerMinute = 3))
        burst  <- ZIO.foreach(List(1, 2, 3))(_ => ask(limits, tenant, "ada"))
        over   <- ask(limits, tenant, "ada")
        other  <- ask(limits, tenant, "bob")
      yield assertTrue(
        burst.forall(_.isEmpty),
        over.map(_.limit).contains(Limit.User),
        // 3 per minute: a token every 20 seconds
        over.exists(r => r.retryAfterSeconds >= 1 && r.retryAfterSeconds <= 20),
        other.isEmpty
      )
    },
    test("tokens come back with time, up to the size of the burst") {
      for
        tenant <- newTenant
        limits <- instance(noInFlight.copy(perUserPerMinute = 2))
        _      <- ask(limits, tenant, "ada").repeatN(1)
        empty  <- ask(limits, tenant, "ada")
        _      <- elapse(tenant, 30)
        one    <- ask(limits, tenant, "ada")
        none   <- ask(limits, tenant, "ada")
        _      <- elapse(tenant, 3600)
        full   <- ZIO.foreach(List(1, 2, 3))(_ => ask(limits, tenant, "ada"))
      yield assertTrue(
        empty.isDefined,
        one.isEmpty,
        none.isDefined,
        full.map(_.isEmpty) == List(true, true, false)
      )
    },
    test("application instances share the rates") {
      for
        tenant <- newTenant
        first  <- instance(noInFlight.copy(perUserPerMinute = 2))
        second <- instance(noInFlight.copy(perUserPerMinute = 2))
        a      <- ask(first, tenant, "ada")
        b      <- ask(second, tenant, "ada")
        c      <- ask(first, tenant, "ada")
        d      <- ask(second, tenant, "ada")
      yield assertTrue(a.isEmpty, b.isEmpty, c.isDefined, d.isDefined)
    },
    test("parallel requests get exactly the tokens there are") {
      for
        tenant  <- newTenant
        first   <- instance(noInFlight.copy(perUserPerMinute = 5))
        second  <- instance(noInFlight.copy(perUserPerMinute = 5))
        results <- ZIO.foreachPar((1 to 20).toList)(i =>
          ask(if i % 2 == 0 then first else second, tenant, "ada")
        )
      yield assertTrue(results.count(_.isEmpty) == 5)
    },
    test("the tenant's rate holds over its users, and a refusal there leaves the user's token alone") {
      for
        tenant <- newTenant
        limits <- instance(noInFlight.copy(perUserPerMinute = 1, perTenantPerMinute = 2))
        a      <- ask(limits, tenant, "ada")
        b      <- ask(limits, tenant, "bob")
        c      <- ask(limits, tenant, "cem")
        // a token of the tenant comes back after 30 seconds; a token of a user only after 60
        _     <- elapse(tenant, 30)
        again <- ask(limits, tenant, "cem")
      yield assertTrue(a.isEmpty, b.isEmpty, c.map(_.limit).contains(Limit.Tenant), again.isEmpty)
    },
    test("one tenant at its limit does not touch another, and neither sees the other's rows") {
      for
        busy    <- newTenant
        quiet   <- newTenant
        limits  <- instance(noInFlight.copy(perTenantPerMinute = 1))
        _       <- ask(limits, busy, "ada")
        over    <- ask(limits, busy, "bob")
        other   <- ask(limits, quiet, "ada")
        own     <- rows(busy, busy)
        foreign <- rows(busy, quiet)
      yield assertTrue(over.isDefined, other.isEmpty, own > 0L, foreign == 0L)
    },
    test("a tenant's PDFs in flight are limited, a refusal spends no token, the slot ends with the print") {
      for
        tenant <- newTenant
        other  <- newTenant
        limits <- instance(noInFlight.copy(perUserPerMinute = 2, inFlightPerTenant = 1))
        held   <- Scope.make
        _      <- held.extend(limits.admit(tenant, UserId("ada")))
        busy   <- ask(limits, tenant, "bob")
        free   <- ask(limits, other, "ada")
        _      <- held.close(Exit.unit)
        after  <- ask(limits, tenant, "bob")
        spent  <- ask(limits, tenant, "bob")
        last   <- ask(limits, tenant, "bob")
      yield assertTrue(
        busy.map(_.limit).contains(Limit.TenantInFlight),
        busy.map(_.retryAfterSeconds).contains(7),
        free.isEmpty,
        after.isEmpty,
        spent.isEmpty,
        last.map(_.limit).contains(Limit.User)
      )
    },
    test("an instance has a limit over all tenants; another instance has its own") {
      for
        first     <- newTenant
        second    <- newTenant
        limits    <- instance(noInFlight.copy(inFlightPerInstance = 1))
        elsewhere <- instance(noInFlight.copy(inFlightPerInstance = 1))
        held      <- Scope.make
        _         <- held.extend(limits.admit(first, UserId("ada")))
        busy      <- ask(limits, second, "bob")
        free      <- ask(elsewhere, second, "bob")
        _         <- held.close(Exit.unit)
      yield assertTrue(busy.map(_.limit).contains(Limit.InstanceInFlight), free.isEmpty)
    },
    test("a print that is interrupted gives its slot back") {
      for
        tenant  <- newTenant
        limits  <- instance(noInFlight.copy(inFlightPerTenant = 1))
        started <- Promise.make[Nothing, Unit]
        fiber   <- ZIO
          .scoped(limits.admit(tenant, UserId("ada")) *> started.succeed(()) *> ZIO.never)
          .fork
        _     <- started.await
        busy  <- ask(limits, tenant, "bob")
        _     <- fiber.interrupt
        after <- ask(limits, tenant, "bob")
      yield assertTrue(busy.isDefined, after.isEmpty)
    },
    test("0 turns the limits off and writes nothing") {
      for
        tenant <- newTenant
        limits <- instance(noInFlight)
        all    <- ZIO.foreach((1 to 50).toList)(_ => ask(limits, tenant, "ada"))
        stored <- rows(tenant, tenant)
      yield assertTrue(all.forall(_.isEmpty), stored == 0L)
    }
  ).provideShared(databases) @@ TestAspect.sequential @@ TestAspect.withLiveClock
