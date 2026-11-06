package dms.audit.core

import com.augustnagro.magnum.{DbTx, sql}
import com.dimafeng.testcontainers.PostgreSQLContainer
import dms.audit.*
import dms.db.queue.ClaimedJob
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
  final case class Env(app: Db, owner: Db, trail: AuditTrail, anchoring: AuditAnchoring, trust: TsaTrust)

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

  private val env: ZLayer[PostgreSQLContainer & TimestampAuthority & TsaTrust, Throwable, Env] =
    ZLayer.scoped(
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
        trust <- ZIO.service[TsaTrust]
      yield Env(
        Db(app),
        Db(owner),
        AuditTrailLive(Db(app), trust),
        AuditAnchoring(Db(app), tsa, trust),
        trust
      )
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

  private def evidence(e: Env, maxAge: Duration = AuditEvidenceLive.PrefixMaxAge): AuditEvidence =
    AuditEvidenceLive(e.app, e.trail, None, maxAge)

  private def about(objectId: UUID, tenant: TenantId): ZIO[Env, DbError, AuditRecordId] =
    ZIO.serviceWithZIO[Env](e =>
      e.app.transact(tenant)(
        e.trail.record(event(0).copy(target = AuditObject("document", Some(objectId), Some("1.0"))))
      )
    )

  /** A chain of 6 entries, anchored at 5 and fully verified: the prefix ends at 5. */
  private def verifiedChain: ZIO[Env, DbError | TimestampError, TenantId] =
    for
      e <- ZIO.service[Env]
      t <- newTenantZ
      _ <- recordMany(5, t)
      _ <- e.anchoring.anchor(t)
      _ <- record(6, t)
      _ <- evidence(e).verifyFully(t)
    yield t

  private def runs(tenant: TenantId): ZIO[Env, DbError, List[Boolean]] =
    ZIO.serviceWithZIO[Env](
      _.owner.transactSystem(
        Db.sql(
          sql"""select ok from dms.audit_verification_run where tenant_id = ${tenant.value} order by id"""
            .query[Boolean]
            .run()
            .toList
        )
      )
    )

  private val tenDays = java.time.Duration.ofDays(10)

  /** What the outbox says about violations of the chain of `tenant`, oldest first. */
  private def notices(tenant: TenantId): ZIO[Env, DbError, List[String]] =
    ZIO.serviceWithZIO[Env](
      _.owner.transactSystem(
        Db.sql(
          sql"""select payload::text from dms.outbox_event
                where tenant_id = ${tenant.value} and event_type = ${AuditViolation.Event} order by id"""
            .query[String]
            .run()
            .toList
        )
      )
    )

  private def forgeEntry(tenant: TenantId, seq: Int) =
    tamperEvents(
      execute(
        s"update dms.audit_event set reason = 'forged' where tenant_id = '${tenant.value}' and chain_seq = $seq"
      )
    )

  /** A chain whose entry 2 was changed. */
  private def violatedChain: ZIO[Env, DbError, TenantId] =
    for
      t <- newTenantZ
      _ <- recordMany(3, t)
      _ <- forgeEntry(t, 2)
    yield t

  /** Records a run that found `violation`, as `verifyFully` does. */
  private def found(tenant: TenantId, violation: Option[Violation]): ZIO[Env, DbError, Unit] =
    ZIO.serviceWithZIO[Env](e =>
      Clock.instant.flatMap(now =>
        e.app.transact(tenant)(
          Db.sql(
            ChainStore.insertRun(
              tenant.value,
              VerificationRun(now, VerificationReport(tenant, 0, None, None, 0, violation), None, None)
            )
          ) *> ViolationNotices.tellOfNew(now).when(violation.nonEmpty)
        )
      ) *> ZIO.unit
    )

  private def hashMismatch(seq: Long) = Some(Violation(seq, Violation.Kind.HashMismatch, "secret"))

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
          Rfc3161.verify(stored.head.token, head, env.trust).isRight,
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
    },
    test("a report reads only what follows the verified prefix, which ends at an anchor (ADR 0077)") {
      for
        e     <- ZIO.service[Env]
        t     <- verifiedChain
        _     <- recordMany(2, t)
        state <- evidence(e).integrity(t, UUID.randomUUID())
        all   <- runs(t)
      yield assertTrue(
        state.verification.ok,
        state.verification.trustedPrefix.map(_.untilSeq).contains(5L),
        state.verification.entriesChecked == 3L,
        state.verification.anchorsChecked == 1,
        state.verification.headSeq.contains(8L),
        // the shortcut is no full verification: it records no run
        all == List(true)
      )
    },
    test("tampering below the prefix is found by the regular full verification and closes the shortcut") {
      for
        e <- ZIO.service[Env]
        t <- verifiedChain
        _ <- tamperEvents(
          execute(
            s"update dms.audit_event set reason = 'forged' where tenant_id = '${t.value}' and chain_seq = 2"
          )
        )
        before <- evidence(e).integrity(t, UUID.randomUUID())
        full   <- evidence(e).verifyFully(t)
        after  <- evidence(e).integrity(t, UUID.randomUUID())
        all    <- runs(t)
      yield assertTrue(
        // the limit ADR 0077 accepts: until the next full verification, for entries of other objects
        before.verification.ok,
        full.verification.violation.map(v => (v.chainSeq, v.kind)) ==
          Some((2L, Violation.Kind.HashMismatch)),
        after.verification.trustedPrefix.isEmpty,
        after.verification.violation.map(_.chainSeq).contains(2L),
        all == List(true, false, false)
      )
    },
    test("the entries a report prints are read again below the prefix") {
      val document = UUID.randomUUID()
      for
        e     <- ZIO.service[Env]
        t     <- newTenantZ
        _     <- recordMany(1, t)
        _     <- about(document, t)
        _     <- recordMany(3, t)
        _     <- e.anchoring.anchor(t)
        _     <- evidence(e).verifyFully(t)
        clean <- evidence(e).integrity(t, document)
        _     <- tamperEvents(
          execute(
            s"update dms.audit_event set reason = 'forged' where tenant_id = '${t.value}' and chain_seq = 2"
          )
        )
        changed <- evidence(e).integrity(t, document)
        // A rewritten hash no longer matches the link of the next entry.
        other   <- verifiedChain
        _       <- about(document, other)
        chainOf <- chain(other)
        seq = chainOf.last.entry.chainSeq
      yield assertTrue(
        clean.verification.trustedPrefix.isDefined,
        changed.verification.trustedPrefix.isEmpty,
        changed.verification.violation.map(v => (v.chainSeq, v.kind)) ==
          Some((2L, Violation.Kind.HashMismatch)),
        seq == 7L
      )
    },
    test("the prefix is not trusted if the anchored entry, the anchor or its token changed") {
      for
        e       <- ZIO.service[Env]
        entry   <- verifiedChain
        token   <- verifiedChain
        removed <- verifiedChain
        _       <- tamperEvents(
          execute(
            s"""update dms.audit_event set hash = sha256(hash)
               |where tenant_id = '${entry.value}' and chain_seq = 5""".stripMargin
          )
        )
        _ <- tamper("audit_anchor", "audit_anchor_guard")(
          execute(
            s"""update dms.audit_anchor
               |set tsa_token = set_byte(tsa_token, length(tsa_token) - 1, 255 - get_byte(tsa_token, length(tsa_token) - 1))
               |where tenant_id = '${token.value}'""".stripMargin
          )
        )
        _ <- ZIO.serviceWithZIO[Env](
          _.owner.transactSystem(
            Db.sql {
              execute("set local session_replication_role = replica")
              execute(s"delete from dms.audit_anchor where tenant_id = '${removed.value}'")
            }
          )
        )
        changed <- evidence(e).integrity(entry, UUID.randomUUID())
        forged  <- evidence(e).integrity(token, UUID.randomUUID())
        gone    <- evidence(e).integrity(removed, UUID.randomUUID())
      yield assertTrue(
        changed.verification.trustedPrefix.isEmpty,
        !changed.verification.ok,
        forged.verification.trustedPrefix.isEmpty,
        forged.verification.violation.map(_.kind).contains(Violation.Kind.AnchorMismatch),
        // Without its anchor the prefix is not trusted; the whole chain is read, and it is intact.
        gone.verification.trustedPrefix.isEmpty,
        gone.verification.entriesChecked == 6L
      )
    },
    test("a violation after the prefix ends in a recorded full verification") {
      for
        e <- ZIO.service[Env]
        t <- verifiedChain
        _ <- tamperEvents(
          execute(
            s"update dms.audit_event set reason = 'forged' where tenant_id = '${t.value}' and chain_seq = 6"
          )
        )
        state <- evidence(e).integrity(t, UUID.randomUUID())
        all   <- runs(t)
      yield assertTrue(
        state.verification.trustedPrefix.isEmpty,
        state.verification.violation.map(v => (v.chainSeq, v.kind)) ==
          Some((6L, Violation.Kind.HashMismatch)),
        all == List(true, false)
      )
    },
    test("without an anchor or a recent full verification the whole chain is read") {
      for
        e        <- ZIO.service[Env]
        plain    <- newTenantZ
        _        <- recordMany(3, plain)
        _        <- evidence(e).verifyFully(plain)
        unsealed <- evidence(e).integrity(plain, UUID.randomUUID())
        t        <- verifiedChain
        stale    <- evidence(e, Duration.Zero).integrity(t, UUID.randomUUID())
        all      <- runs(t)
      yield assertTrue(
        unsealed.verification.trustedPrefix.isEmpty,
        unsealed.verification.entriesChecked == 3L,
        stale.verification.trustedPrefix.isEmpty,
        stale.verification.entriesChecked == 6L,
        all == List(true, true)
      )
    },
    test("verification runs are limited to the tenant and cannot be changed by the application") {
      for
        e   <- ZIO.service[Env]
        t   <- verifiedChain
        own <- e.app.transact(t)(
          Db.sql(sql"select count(*) from dms.audit_verification_run".query[Long].run().head)
        )
        foreign <- e.app.transact(TenantId.random())(
          Db.sql(sql"select count(*) from dms.audit_verification_run".query[Long].run().head)
        )
        deleted <- e.app.transact(t)(Db.sql(sql"delete from dms.audit_verification_run".update.run())).either
        changed <- e.app
          .transact(t)(Db.sql(sql"update dms.audit_verification_run set ok = true".update.run()))
          .either
      yield assertTrue(own == 1L, foreign == 0L, deleted.isLeft, changed.isLeft)
    },
    test("a violation is told once, however often runs find it, and the notice carries no content") {
      for
        e     <- ZIO.service[Env]
        t     <- violatedChain
        _     <- evidence(e).verifyFully(t)
        _     <- evidence(e).verifyFully(t)
        first <- notices(t)
        _     <- evidence(e).verifyFully(t)
        all   <- notices(t)
      yield assertTrue(
        first.size == 1,
        all == first,
        all.head.contains("foundAt"),
        !all.head.contains("reminder"),
        !all.head.toLowerCase.contains("hash"),
        !all.head.contains("forged")
      )
    },
    test("a report that falls back to a full verification tells of what it found") {
      for
        e    <- ZIO.service[Env]
        t    <- violatedChain
        _    <- evidence(e).integrity(t, UUID.randomUUID())
        told <- notices(t)
      yield assertTrue(told.size == 1)
    },
    test("two runs at once tell of a violation once") {
      for
        e    <- ZIO.service[Env]
        t    <- violatedChain
        _    <- ZIO.foreachParDiscard(1 to 4)(_ => evidence(e).verifyFully(t))
        told <- notices(t)
      yield assertTrue(told.size == 1)
    },
    test("an intact chain tells nobody, and the first run after an intact one is a new violation") {
      for
        t     <- newTenantZ
        _     <- found(t, None)
        quiet <- notices(t)
        _     <- found(t, hashMismatch(2))
        _     <- found(t, None)
        _     <- found(t, hashMismatch(2))
        _     <- found(t, hashMismatch(2))
        told  <- notices(t)
      yield assertTrue(quiet.isEmpty, told.size == 2)
    },
    test("another position is another violation, but a checkpoint is the same wherever it is reported") {
      for
        a                 <- newTenantZ
        _                 <- found(a, hashMismatch(2))
        _                 <- found(a, hashMismatch(5))
        b                 <- newTenantZ
        _                 <- found(b, Some(Violation(40, Violation.Kind.CheckpointMismatch, "unreadable")))
        _                 <- found(b, Some(Violation(41, Violation.Kind.CheckpointMismatch, "unreadable")))
        c                 <- newTenantZ
        _                 <- found(c, Some(Violation(40, Violation.Kind.CheckpointMismatch, "unreadable")))
        _                 <- found(c, Some(Violation(40, Violation.Kind.AnchorMismatch, "anchor")))
        differentPosition <- notices(a)
        movedCheckpoint   <- notices(b)
        differentKind     <- notices(c)
      yield assertTrue(differentPosition.size == 2, movedCheckpoint.size == 1, differentKind.size == 2)
    },
    test("a violation that stays open is reminded of on the reminder days, once each") {
      for
        e   <- ZIO.service[Env]
        t   <- violatedChain
        _   <- evidence(e).verifyFully(t)
        now <- Clock.instant
        remindAt = (days: Long) =>
          e.app.transact(t)(
            ViolationNotices.remind(now.plus(java.time.Duration.ofDays(days)), List(3, 7))
          )
        _       <- remindAt(0) *> remindAt(2)
        before  <- notices(t)
        _       <- remindAt(3) *> remindAt(3) *> remindAt(4)
        third   <- notices(t)
        _       <- remindAt(7) *> remindAt(8)
        seventh <- notices(t)
      yield assertTrue(
        before.size == 1,
        third.size == 2,
        third.last.contains("\"reminder\": \"1\""),
        seventh.size == 3,
        seventh.last.contains("\"reminder\": \"2\""),
        // the date of the first finding, in every round
        seventh.map(_.takeWhile(_ != ',').replace("}", "")).distinct.size == 1
      )
    },
    test("missed reminder days are not caught up, and no days means no reminders") {
      for
        e   <- ZIO.service[Env]
        t   <- violatedChain
        _   <- evidence(e).verifyFully(t)
        now <- Clock.instant
        _   <- e.app.transact(t)(ViolationNotices.remind(now.plus(tenDays), Nil))
        off <- notices(t)
        _   <- e.app.transact(t)(ViolationNotices.remind(now.plus(tenDays), List(3, 7)))
        one <- notices(t)
      yield assertTrue(off.size == 1, one.size == 2, one.last.contains("\"reminder\": \"2\""))
    },
    test("an intact run ends the reminders") {
      for
        e   <- ZIO.service[Env]
        t   <- newTenantZ
        _   <- found(t, hashMismatch(2))
        _   <- found(t, None)
        now <- Clock.instant
        _   <- e.app.transact(t)(ViolationNotices.remind(now.plus(tenDays), List(3, 7)))
        all <- notices(t)
      yield assertTrue(all.size == 1)
    },
    test("reminder days are calendar days, as the job does not run at the very second of the notice") {
      val notice        = java.time.Instant.parse("2026-11-02T03:00:05Z")
      val days          = List(3, 7)
      def at(s: String) = ViolationNotices.dueRound(notice, java.time.Instant.parse(s), days)
      assertTrue(
        at("2026-11-04T23:59:59Z").isEmpty,
        at("2026-11-05T03:00:01Z").contains(1),
        at("2026-11-09T03:00:01Z").contains(2),
        at("2026-12-01T03:00:01Z").contains(2),
        ViolationNotices.dueRound(notice, notice.plus(tenDays), Nil).isEmpty
      )
    },
    test("the job reminds of what stays open and logs nothing new for an intact chain") {
      for
        e <- ZIO.service[Env]
        t <- violatedChain
        job  = ClaimedJob(1, Some(t), AuditJobs.VerifyKind, Jsonb("{}"), 1, 3)
        jobs = AuditJobs(
          e.app,
          None,
          AuditCheckpoints(e.app, e.trail, null, CheckpointConfig()),
          evidence(e),
          AuditScheduleConfig()
        )
        _     <- jobs.handlers(AuditJobs.VerifyKind)(job)
        _     <- jobs.handlers(AuditJobs.VerifyKind)(job)
        first <- notices(t)
        _     <- tamper("audit_violation_notice", "audit_violation_notice_guard")(
          execute(
            s"""update dms.audit_violation_notice set sent_at = sent_at - interval '3 days'
                where tenant_id = '${t.value}'"""
          )
        )
        _     <- jobs.handlers(AuditJobs.VerifyKind)(job)
        after <- notices(t)
      yield assertTrue(first.size == 1, after.size == 2, after.last.contains("reminder"))
    },
    test("notices of violations are limited to the tenant and cannot be changed by the application") {
      for
        e   <- ZIO.service[Env]
        t   <- newTenantZ
        _   <- found(t, hashMismatch(2))
        own <- e.app.transact(t)(
          Db.sql(sql"select count(*) from dms.audit_violation_notice".query[Long].run().head)
        )
        foreign <- e.app.transact(TenantId.random())(
          Db.sql(sql"select count(*) from dms.audit_violation_notice".query[Long].run().head)
        )
        deleted <- e.app.transact(t)(Db.sql(sql"delete from dms.audit_violation_notice".update.run())).either
        changed <- e.app
          .transact(t)(Db.sql(sql"update dms.audit_violation_notice set round = 9".update.run()))
          .either
      yield assertTrue(own == 1L, foreign == 0L, deleted.isLeft, changed.isLeft)
    },
    test("the last verification says when, what and since when, without any detail") {
      for
        e      <- ZIO.service[Env]
        t      <- newTenantZ
        never  <- e.app.transact(t)(evidence(e).lastVerification)
        _      <- recordMany(3, t)
        _      <- evidence(e).verifyFully(t)
        intact <- e.app.transact(t)(evidence(e).lastVerification)
        _      <- forgeEntry(t, 2)
        _      <- evidence(e).verifyFully(t)
        first  <- e.app.transact(t)(evidence(e).lastVerification)
        _      <- evidence(e).verifyFully(t)
        later  <- e.app.transact(t)(evidence(e).lastVerification)
      yield assertTrue(
        never.isEmpty,
        intact.exists(r => r.violationKind.isEmpty && r.entriesChecked == 3L && r.checkpointsCompared),
        intact.exists(_.violatedSince.isEmpty),
        later.flatMap(_.violationKind).contains("HashMismatch"),
        later.flatMap(_.violationSeq).contains(2L),
        later.flatMap(_.violatedSince) == first.map(_.verifiedAt),
        later.map(_.verifiedAt) != first.map(_.verifiedAt)
      )
    },
    test("tokens from a TSA outside the trust anchors are rejected") {
      ZIO.scoped(
        for
          env      <- ZIO.service[Env]
          t        <- newTenantZ
          _        <- recordMany(2, t)
          foreign  <- TestTsa.make("Foreign CA")
          rejected <- AuditAnchoring(env.app, foreign.authority, env.trust).anchor(t).either
          accepted <- AuditAnchoring(env.app, foreign.authority, foreign.trust).anchor(t)
          stored   <- env.owner.transactSystem(Db.sql(ChainStore.anchors(t.value)))
          report   <- verify(t)
        yield assertTrue(
          rejected.left.exists(_.toString.contains("not trusted")),
          accepted.isDefined,
          Rfc3161
            .verify(stored.head.token, stored.head.chainHeadHash, TsaTrust(Nil))
            .left
            .exists(
              _.contains("no trusted TSA certificates")
            ),
          // The trail trusts only the test CA, so the anchor from the foreign TSA counts as a mismatch.
          report.violation.map(_.kind) == Some(Violation.Kind.AnchorMismatch)
        )
      )
    }
  ).provideShared((container ++ TestTsa.layer) >>> env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
