package dms.workflow.core

import com.augustnagro.magnum.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.audit.*
import dms.db.{Db, DbConfig, DbError, Migrations}
import dms.kernel.{TenantId, UserId}
import dms.workflow.*
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

/** Deputyships against a Testcontainers database (DMS-49, ADR 0028): table `dms.deputyship` with row-level
  * security and grants, the history guard, the period queries the workflow forwards tasks with, and the
  * service with its authority check and audit entries.
  */
object DeputyshipsSpec extends ZIOSpecDefault:

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  private val anna  = UserId("anna")
  private val ben   = UserId("ben")
  private val clara = UserId("clara")
  private val admin = UserId("admin")

  /** Anna and Ben approve everywhere; Clara approves contracts of the legal department; Erik only reviews. */
  private object Directory extends ParticipantDirectory:
    def holders(role: String, subject: ReviewSubject): IO[WorkflowError, List[UserId]]      = ZIO.succeed(Nil)
    def cleared(users: Set[UserId], subject: ReviewSubject): IO[WorkflowError, Set[UserId]] =
      ZIO.succeed(users)
    def tenantAdmins: IO[WorkflowError, List[UserId]]              = ZIO.succeed(List(admin))
    def heldRoles(user: UserId): IO[WorkflowError, List[HeldRole]] =
      ZIO.succeed(user.value match
        case "anna" | "ben" => List(HeldRole("Approver", None, None, Instant.EPOCH, None))
        case "clara"        =>
          List(HeldRole("Approver", Some("Legal"), Some("contract"), Instant.EPOCH, None))
        case "erik" => List(HeldRole("Reviewer", None, None, Instant.EPOCH, None))
        case _      => Nil)

  final case class Env(app: Db, owner: Db, tenant: TenantId, other: TenantId, audit: Ref[List[AuditEvent]])

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
      _      <- Migrations.migrate(c.jdbcUrl, c.username, c.password)
      owner  <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, poolSize = 1)).map(Db(_))
      tenant <- ZIO.succeed(TenantId.random())
      other  <- ZIO.succeed(TenantId.random())
      _      <- owner
        .transactSystem(Db.sql {
          val _ = sql"insert into dms.tenant (id, name) values (${tenant.value}, ${"Tenant"})".update.run()
          val _ = sql"insert into dms.tenant (id, name) values (${other.value}, ${"Other"})".update.run()
        })
        .orDieWith(e => RuntimeException(e.toString))
      app   <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", poolSize = 2)).map(Db(_))
      audit <- Ref.make(List.empty[AuditEvent])
    yield Env(app, owner, tenant, other, audit)
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  private def service(audit: Ref[List[AuditEvent]]): Deputyships =
    val trail = new AuditTrail:
      def record(event: AuditEvent): IO[DbError, AuditRecordId] =
        audit.update(_ :+ event).as(AuditRecordId(1L))
      def verify(tenant: TenantId): IO[DbError, VerificationReport] =
        ZIO.succeed(VerificationReport(tenant, 0, None, None, 0, None))
    Deputyships(trail, Directory)

  private def run[A](e: Env, tenant: TenantId)(
      body: IO[WorkflowError | DbError, A]
  ): IO[WorkflowError | DbError, A] =
    e.app.transact(tenant)(body)

  private def request(deputy: UserId, from: Option[Instant], until: Instant) =
    NewDeputyship(anna, deputy, from, until, List("Approver"), None, Some("Vacation"))

  def spec = suite("Deputyships")(
    test("create, list and end; the period decides who is active; other tenants see nothing") {
      for
        e   <- ZIO.service[Env]
        now <- Clock.instant.map(_.truncatedTo(ChronoUnit.MILLIS))
        s = service(e.audit)
        active  <- run(e, e.tenant)(s.create(request(ben, None, now.plusSeconds(3600)), anna))
        noRoles <- run(e, e.tenant)(
          s.create(request(UserId("dora"), Some(now.plusSeconds(86400)), now.plusSeconds(172800)), admin)
        ).either
        forAnna <- run(e, e.tenant)(
          Db.sql(DeputyStore.activeForPrincipals(List(anna, clara), now.plusSeconds(1)))
        )
        forBen    <- run(e, e.tenant)(Db.sql(DeputyStore.activeForDeputy(ben, now.plusSeconds(1))))
        afterward <- run(e, e.tenant)(Db.sql(DeputyStore.activeForDeputy(ben, now.plusSeconds(3600))))
        involving <- run(e, e.tenant)(s.involving(ben))
        foreign   <- run(e, e.other)(s.involving(ben))
        ended     <- run(e, e.tenant)(s.end(active.id, anna))
        whenEnded <- run(e, e.tenant)(Db.sql(DeputyStore.activeForDeputy(ben, Instant.now().plusMillis(1))))
        twice     <- run(e, e.tenant)(s.end(active.id, anna)).either
        events    <- e.audit.get
      yield assertTrue(
        active.roles == List("Approver"),
        active.reason.contains("Vacation"),
        active.status(now.plusSeconds(1)) == DeputyshipStatus.Active,
        // Dora holds no role: the authority check rejects her
        noRoles.left.exists { case WorkflowError.Invalid(_) => true; case _ => false },
        forAnna.map(_.id) == List(active.id),
        forBen.map(_.principal) == List(anna),
        afterward.isEmpty,
        involving.map(_.id) == List(active.id),
        foreign.isEmpty,
        ended.endedBy.contains(anna),
        ended.status(Instant.now().plusMillis(1)) == DeputyshipStatus.Ended,
        whenEnded.isEmpty,
        twice.left.exists { case WorkflowError.Conflict(_) => true; case _ => false },
        events.map(_.action) == List("deputyship.created", "deputyship.ended"),
        events.forall(_.target.id.contains(active.id))
      )
    },
    test("a deputy without the role is rejected, one with another scope accepted; scheduled is not active") {
      for
        e   <- ZIO.service[Env]
        now <- Clock.instant
        s = service(e.audit)
        noRole <- run(e, e.tenant)(
          s.create(request(UserId("erik"), None, now.plusSeconds(3600)), anna)
        ).either
        otherUnit <- run(e, e.tenant)(s.create(request(clara, None, now.plusSeconds(3600)), anna))
        scheduled <- run(e, e.tenant)(
          s.create(request(ben, Some(now.plusSeconds(7200)), now.plusSeconds(10800)), anna)
        )
        overlap <- run(e, e.tenant)(
          s.create(request(ben, Some(now.plusSeconds(9000)), now.plusSeconds(20000)), anna)
        ).either
        notYet <- run(e, e.tenant)(Db.sql(DeputyStore.activeForDeputy(ben, now.plusSeconds(60))))
        inside <- run(e, e.tenant)(Db.sql(DeputyStore.activeForDeputy(ben, now.plusSeconds(7300))))
      yield assertTrue(
        noRole.left.exists {
          case WorkflowError.Invalid(p) =>
            p == List("deputy erik does not hold role Approver (same authority required)")
          case _ => false
        },
        otherUnit.deputy == clara,
        scheduled.status(now) == DeputyshipStatus.Scheduled,
        overlap.left.exists { case WorkflowError.Conflict(_) => true; case _ => false },
        notYet.isEmpty,
        inside.map(_.id) == List(scheduled.id)
      )
    },
    test("an unknown document class is a validation error") {
      for
        e   <- ZIO.service[Env]
        now <- Clock.instant
        s = service(e.audit)
        result <- run(e, e.tenant)(
          s.create(request(ben, None, now.plusSeconds(60)).copy(documentClass = Some("nope")), anna)
        ).either
      yield assertTrue(result.left.exists {
        case WorkflowError.Invalid(p) => p == List("document class nope does not exist")
        case _                        => false
      })
    },
    test("the application cannot delete or rewrite a deputyship; it can only end it once") {
      for
        e   <- ZIO.service[Env]
        now <- Clock.instant
        s = service(e.audit)
        d <- run(e, e.tenant)(
          s.create(request(ben, Some(now.plusSeconds(30000)), now.plusSeconds(40000)), anna)
        )
        delete <- e.app
          .transact(e.tenant)(Db.sql(sql"delete from dms.deputyship where id = ${d.id}".update.run()))
          .either
        rewrite <- e.app
          .transact(e.tenant)(
            Db.sql(sql"update dms.deputyship set deputy_id = 'mallory' where id = ${d.id}".update.run())
          )
          .either
        guarded <- e.owner
          .transactSystem(
            Db.sql(
              sql"update dms.deputyship set valid_until = valid_until + interval '1 day' where id = ${d.id}".update
                .run()
            )
          )
          .either
        noTenant <- e.app
          .transactSystem(Db.sql(sql"select count(*) from dms.deputyship".query[Long].run().head))
          .either
      yield assertTrue(
        delete.isLeft,
        rewrite.isLeft,
        guarded.isLeft,
        noTenant.isLeft || noTenant.contains(0L)
      )
    },
    test("the deputyship check notifies once when a scheduled period begins and when it runs out (DMS-99)") {
      // The job runs under a clock moved ahead instead of rewriting the guarded rows.
      def at(now: Instant): Clock  = Clock.ClockJava(java.time.Clock.fixed(now, java.time.ZoneOffset.UTC))
      def events(e: Env, id: UUID) =
        e.owner.transactSystem(
          Db.sql(
            sql"select event_type, payload::text from dms.outbox_event where aggregate_id = $id order by id"
              .query[(String, String)]
              .run()
              .toList
          )
        )
      for
        e   <- ZIO.service[Env]
        now <- Clock.instant
        s    = service(e.audit)
        jobs = DeputyshipJobs(e.app, s, DeputyshipScheduleConfig())
        scheduled <- run(e, e.tenant)(
          s.create(
            NewDeputyship(ben, anna, Some(now.plusSeconds(60)), now.plusSeconds(120), Nil, None, None),
            ben
          )
        )
        endedEarly <- run(e, e.tenant)(
          s.create(
            NewDeputyship(ben, clara, Some(now.plusSeconds(60)), now.plusSeconds(120), Nil, None, None),
            ben
          )
        )
        _          <- run(e, e.tenant)(s.end(endedEarly.id, ben))
        before     <- jobs.check(e.tenant)
        afterStart <- events(e, scheduled.id)
        started    <- ZIO.withClock(at(now.plusSeconds(90)))(jobs.check(e.tenant))
        again      <- ZIO.withClock(at(now.plusSeconds(95)))(jobs.check(e.tenant))
        ended      <- ZIO.withClock(at(now.plusSeconds(180)))(jobs.check(e.tenant))
        repeated   <- ZIO.withClock(at(now.plusSeconds(240)))(jobs.check(e.tenant))
        all        <- events(e, scheduled.id)
        early      <- events(e, endedEarly.id)
        foreign    <- ZIO.withClock(at(now.plusSeconds(180)))(jobs.check(e.other))
        enqueued   <- jobs.tick
        twice      <- jobs.tick
      yield assertTrue(
        afterStart.map(_._1) == List("workflow.deputyship-created"),
        started >= 1,
        again == 0,
        ended >= 1,
        repeated == 0,
        all.map(_._1) == List(
          "workflow.deputyship-created",
          "workflow.deputyship-started",
          "workflow.deputyship-ended-by-period"
        ),
        all.drop(1).forall((_, payload) => payload.contains(anna.value) && payload.contains(ben.value)),
        // ended before its period began: neither a start nor an end by period
        early.map(_._1) == List("workflow.deputyship-created", "workflow.deputyship-ended"),
        foreign == 0,
        enqueued == 2,
        twice == 0,
        before >= 0
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
