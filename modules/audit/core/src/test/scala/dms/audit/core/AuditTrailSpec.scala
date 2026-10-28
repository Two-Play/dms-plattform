package dms.audit.core

import com.augustnagro.magnum.DbTx
import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.audit.*
import dms.db.{Db, DbConfig, DbError, Jsonb, Migrations}
import dms.kernel.TenantId
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.test.*

import java.util.UUID
import javax.sql.DataSource
import scala.util.Using

/** Integration tests against a real PostgreSQL started by Testcontainers (pattern of `dms.db.DbSpec`). */
object AuditTrailSpec extends ZIOSpecDefault:

  /** Application connections (role dms_app, subject to RLS), owner connections and the services under test.
    */
  final case class Env(app: Db, owner: Db, trail: AuditTrail, anchoring: AuditAnchoring)

  private val appUserSql =
    """do $$ begin
      |  if not exists (select 1 from pg_roles where rolname = 'dms_app') then
      |    create role dms_app nologin nobypassrls;
      |  end if;
      |  create role dms_app_user login nobypassrls password 'dms-app-test' in role dms_app;
      |end $$""".stripMargin

  private val container: ZLayer[Any, Throwable, PostgreSQLContainer] = ZLayer.scoped(
    ZIO.acquireRelease(ZIO.attemptBlocking {
      val c = PostgreSQLContainer(DockerImageName.parse("postgres:17"))
      c.start()
      c
    })(c => ZIO.attemptBlocking(c.stop()).orDie)
  )

  private val env: ZLayer[PostgreSQLContainer & TimestampAuthority, Throwable, Env] = ZLayer.scoped(
    for
      c <- ZIO.service[PostgreSQLContainer]
      _ <- ZIO.attemptBlocking {
        val connection = java.sql.DriverManager.getConnection(c.jdbcUrl, c.username, c.password)
        try connection.createStatement().execute(appUserSql)
        finally connection.close()
      }
      _     <- Migrations.migrate(c.jdbcUrl, c.username, c.password)
      app   <- dataSource(DbConfig(c.jdbcUrl, "dms_app_user", "dms-app-test", poolSize = 8))
      owner <- dataSource(DbConfig(c.jdbcUrl, c.username, c.password, poolSize = 2))
      tsa   <- ZIO.service[TimestampAuthority]
    yield Env(Db(app), Db(owner), AuditTrailLive(Db(app)), AuditAnchoring(Db(app), tsa))
  )

  private def dataSource(config: DbConfig): ZIO[Scope, Throwable, DataSource] =
    Db.dataSource(config).build.map(_.get[DataSource])

  private def newTenant(owner: Db): IO[DbError, TenantId] =
    val id = TenantId.random()
    owner
      .transactSystem(Db.sql(execute(s"insert into dms.tenant (id, name) values ('${id.value}', 'Tenant')")))
      .as(id)

  private def execute(statement: String)(using tx: DbTx): Unit =
    Using.resource(tx.connection.createStatement())(_.execute(statement)): Unit

  private def event(n: Int): AuditEvent =
    AuditEvent(
      actor = Actor("user-1", ActorKind.Person, onBehalfOf = Some("user-2")),
      action = "document.changed",
      target = AuditObject("document", Some(UUID.randomUUID()), Some("1.0")),
      channel = Channel.Ui,
      details = Jsonb(s"""{"fields": {"title": {"before": "A$n", "after": "B$n"}}, "n": $n}"""),
      reason = Some("typo"),
      correlationId = Some(UUID.randomUUID())
    )

  private def record(n: Int, tenant: TenantId): ZIO[Env, DbError, AuditRecordId] =
    ZIO.serviceWithZIO[Env](e => e.app.transact(tenant)(e.trail.record(event(n))))

  private def recordMany(count: Int, tenant: TenantId): ZIO[Env, DbError, Unit] =
    ZIO.foreachDiscard(1 to count)(record(_, tenant))

  private def verify(tenant: TenantId): ZIO[Env, DbError, VerificationReport] =
    ZIO.serviceWithZIO[Env](_.trail.verify(tenant))

  /** Reads the chain as table owner, bypassing row-level security. */
  private def chain(tenant: TenantId): ZIO[Env, DbError, Vector[StoredEntry]] =
    ZIO.serviceWithZIO[Env](_.owner.transactSystem(Db.sql(ChainStore.entries(tenant.value))))

  /** Runs `statements` as table owner with the append-only guard of `table` disabled (simulated attack). */
  private def tamper(table: String, guard: String)(statements: DbTx ?=> Unit): ZIO[Env, DbError, Unit] =
    ZIO.serviceWithZIO[Env](
      _.owner.transactSystem(Db.sql {
        execute(s"alter table dms.$table disable trigger $guard")
        statements
        execute(s"alter table dms.$table enable trigger $guard")
      })
    )

  private def tamperEvents(statements: DbTx ?=> Unit) = tamper("audit_event", "audit_event_guard")(statements)

  private def newTenantZ: ZIO[Env, DbError, TenantId] = ZIO.serviceWithZIO[Env](e => newTenant(e.owner))

  def spec = suite("AuditTrail")(
    test("every entry links to its predecessor and the chain verifies") {
      for
        t <- newTenantZ
        _ <- recordMany(3, t)
        _ <- ZIO.serviceWithZIO[Env](e =>
          e.app.transact(t)(e.trail.record(event(4)) *> e.trail.record(event(5)))
        )
        entries <- chain(t)
        report  <- verify(t)
      yield
        val links =
          entries.zip(entries.drop(1)).forall((a, b) => b.entry.prevHash.exists(_.sameElements(a.hash)))
        assertTrue(
          entries.map(_.entry.chainSeq) == (1L to 5L).toVector,
          entries.head.entry.prevHash.isEmpty,
          links,
          entries.forall(s => AuditHash.hash(s.entry).sameElements(s.hash)),
          entries.head.entry.onBehalfOf == Some("user-2"),
          report.ok,
          report.entriesChecked == 5L,
          report.headSeq == Some(5L),
          report.headHash == Some(AuditHash.hex(entries.last.hash))
        )
    },
    test("verification detects a manipulated entry") {
      for
        t <- newTenantZ
        _ <- recordMany(3, t)
        _ <- tamperEvents(
          execute(
            s"update dms.audit_event set reason = 'forged' where tenant_id = '${t.value}' and chain_seq = 2"
          )
        )
        report <- verify(t)
      yield assertTrue(
        report.violation.map(v => (v.chainSeq, v.kind)) == Some((2L, Violation.Kind.HashMismatch)),
        report.entriesChecked == 1L
      )
    },
    test("verification detects a deleted entry") {
      for
        t <- newTenantZ
        _ <- recordMany(3, t)
        _ <- tamperEvents(
          execute(s"delete from dms.audit_event where tenant_id = '${t.value}' and chain_seq = 2")
        )
        report <- verify(t)
      yield assertTrue(
        report.violation.map(v => (v.chainSeq, v.kind)) == Some((2L, Violation.Kind.MissingEntry))
      )
    },
    test("verification detects a replaced predecessor link") {
      for
        t <- newTenantZ
        _ <- recordMany(3, t)
        _ <- tamperEvents(
          execute(
            s"update dms.audit_event set prev_hash = sha256('x'::bytea) where tenant_id = '${t.value}' and chain_seq = 3"
          )
        )
        report <- verify(t)
      yield assertTrue(
        report.violation.map(v => (v.chainSeq, v.kind)) == Some((3L, Violation.Kind.BrokenLink))
      )
    },
    test("concurrent appends for one tenant do not fork the chain") {
      for
        t <- newTenantZ
        // Separate transactions on separate connections; each holds the chain lock for a moment.
        _ <- ZIO
          .foreachParDiscard(1 to 24)(n =>
            ZIO.serviceWithZIO[Env](e => e.app.transact(t)(e.trail.record(event(n)) <* ZIO.sleep(5.millis)))
          )
          .withParallelism(8)
        entries <- chain(t)
        report  <- verify(t)
      yield assertTrue(
        entries.map(_.entry.chainSeq) == (1L to 24L).toVector,
        entries.map(_.entry.prevHash.map(AuditHash.hex)).distinct.size == 24,
        report.ok,
        report.entriesChecked == 24L
      )
    },
    test("the chains of two tenants are independent") {
      for
        a        <- newTenantZ
        b        <- newTenantZ
        _        <- record(1, a) *> record(1, b) *> record(2, a) *> record(2, b) *> record(3, a)
        reportA  <- verify(a)
        reportB  <- verify(b)
        entriesA <- chain(a)
        entriesB <- chain(b)
      yield assertTrue(
        reportA.ok,
        reportB.ok,
        reportA.entriesChecked == 3L,
        reportB.entriesChecked == 2L,
        entriesA.map(_.entry.chainSeq) == Vector(1L, 2L, 3L),
        entriesB.map(_.entry.chainSeq) == Vector(1L, 2L),
        entriesB.forall(_.entry.tenantId == b.value)
      )
    },
    test("the audit entry rolls back with the business transaction") {
      for
        t      <- newTenantZ
        result <- ZIO
          .serviceWithZIO[Env](e =>
            e.app.transact(t)(e.trail.record(event(1)) *> ZIO.fail("business rule violated"))
          )
          .either
        before <- chain(t)
        _      <- record(2, t)
        after  <- chain(t)
      yield assertTrue(
        result == Left("business rule violated"),
        before.isEmpty,
        after.map(_.entry.chainSeq) == Vector(1L)
      )
    },
    test("recording requires a transaction with tenant context") {
      for
        env           <- ZIO.service[Env]
        outside       <- env.trail.record(event(1)).either
        withoutTenant <- env.app.transactSystem(env.trail.record(event(1))).either
      yield assertTrue(
        outside == Left(DbError.NoTransaction),
        withoutTenant == Left(DbError.TenantContextMissing)
      )
    },
    test("anchoring timestamps the chain head and verification checks the token") {
      for
        env     <- ZIO.service[Env]
        t       <- newTenantZ
        _       <- recordMany(3, t)
        anchor  <- env.anchoring.anchor(t)
        again   <- env.anchoring.anchor(t)
        stored  <- env.owner.transactSystem(Db.sql(ChainStore.anchors(t.value)))
        entries <- chain(t)
        _       <- record(4, t)
        report  <- verify(t)
      yield
        val head = entries.last.hash
        assertTrue(
          anchor.map(_.anchoredUntilSeq) == Some(3L),
          anchor.map(_.chainHeadHash) == Some(AuditHash.hex(head)),
          again.isEmpty,
          stored.size == 1,
          stored.head.chainHeadHash.sameElements(head),
          Rfc3161.verify(stored.head.token, head).isRight,
          report.ok,
          report.anchorsChecked == 1,
          report.entriesChecked == 4L
        )
    },
    test("an anchor detects a consistently rewritten chain") {
      for
        env <- ZIO.service[Env]
        t   <- newTenantZ
        _   <- recordMany(3, t)
        _   <- env.anchoring.anchor(t)
        // The attacker changes entry 1 and recomputes every hash, so all links are consistent again.
        _ <- tamperEvents {
          ChainStore.entries(t.value).foldLeft(Option.empty[Array[Byte]]) { (prev, stored) =>
            val e    = stored.entry
            val next = e.copy(prevHash = prev, reason = if e.chainSeq == 1 then Some("forged") else e.reason)
            val hash = AuditHash.hash(next)
            execute(
              s"""update dms.audit_event set reason = ${next.reason.fold("null")(r => s"'$r'")},
                 |  prev_hash = ${prev.fold("null")(p => s"'\\x${AuditHash.hex(p)}'::bytea")},
                 |  hash = '\\x${AuditHash.hex(hash)}'::bytea
                 |where tenant_id = '${t.value}' and chain_seq = ${e.chainSeq}""".stripMargin
            )
            Some(hash)
          }: Unit
        }
        report <- verify(t)
      yield assertTrue(
        report.violation.map(v => (v.chainSeq, v.kind)) == Some((3L, Violation.Kind.AnchorMismatch))
      )
    },
    test("an anchor detects a truncated chain and a forged token") {
      for
        env <- ZIO.service[Env]
        a   <- newTenantZ
        b   <- newTenantZ
        _   <- recordMany(3, a) *> recordMany(2, b)
        _   <- env.anchoring.anchor(a) *> env.anchoring.anchor(b)
        _   <- tamperEvents(
          execute(s"delete from dms.audit_event where tenant_id = '${a.value}' and chain_seq = 3")
        )
        _ <- tamper("audit_anchor", "audit_anchor_guard")(
          execute(
            // Flips the last byte, which belongs to the signature value.
            s"""update dms.audit_anchor
               |set tsa_token = set_byte(tsa_token, length(tsa_token) - 1, 255 - get_byte(tsa_token, length(tsa_token) - 1))
               |where tenant_id = '${b.value}'""".stripMargin
          )
        )
        truncated <- verify(a)
        forged    <- verify(b)
      yield assertTrue(
        truncated.violation.map(v => (v.chainSeq, v.kind)) == Some((3L, Violation.Kind.MissingEntry)),
        forged.violation.map(v => (v.chainSeq, v.kind)) == Some((2L, Violation.Kind.AnchorMismatch))
      )
    }
  ).provideShared((container ++ TestTsa.layer) >>> env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
