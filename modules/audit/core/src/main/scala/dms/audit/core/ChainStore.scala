package dms.audit.core

import com.augustnagro.magnum.DbTx
import dms.audit.{VerificationSummary, Violation}

import java.sql.{Connection, PreparedStatement, ResultSet}
import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.UUID
import scala.util.Using

/** An entry as read from `dms.audit_event`. */
final case class StoredEntry(seq: Long, format: Int, entry: ChainEntry, hash: Array[Byte])

/** A timestamp anchor as read from `dms.audit_anchor`. */
final case class StoredAnchor(
    anchoredUntilSeq: Long,
    chainHeadHash: Array[Byte],
    tsaUrl: String,
    token: Array[Byte]
)

/** A full verification of a chain, as recorded in `dms.audit_verification_run` (ADR 0077).
  *
  * @param prefix
  *   position and hash of the anchor up to which a report need not read the chain again
  */
final case class VerificationRun(
    verifiedAt: Instant,
    report: dms.audit.VerificationReport,
    checkpointProblem: Option[String],
    prefix: Option[(Long, Array[Byte])]
)

/** A violation the newest full verification still found (ADR 0080).
  *
  * @param firstRun
  *   the run that found it first; it names the violation
  * @param foundAt
  *   when that run was
  * @param told
  *   when the first notice went out and the newest round so far; `None` before the first notice
  */
final case class OpenViolation(firstRun: Long, foundAt: Instant, told: Option[(Instant, Int)])

/** JDBC access to the audit tables. Runs on the connection of the current transaction (via `Db.sql`). */
private[core] object ChainStore:

  /** The tenant of the current transaction; fails closed (DMS01) without tenant context. */
  def currentTenant(using tx: DbTx): UUID =
    query("select dms.current_tenant()")(_ => ())(rs => rs.getObject(1, classOf[UUID])).head

  /** Serializes appends per tenant until the end of the transaction. Hash collisions of the lock key between
    * tenants only cost throughput, never correctness.
    */
  def lockChain(tenant: UUID)(using DbTx): Unit =
    query("select pg_advisory_xact_lock(hashtextextended('dms.audit_event:' || ?, 0))")(
      _.setString(1, tenant.toString)
    )(_ => ()): Unit

  /** Position and hash of the newest entry of the tenant's chain. */
  def head(tenant: UUID)(using DbTx): Option[(Long, Array[Byte])] =
    query(
      "select chain_seq, hash from dms.audit_event where tenant_id = ? order by chain_seq desc limit 1"
    )(_.setObject(1, tenant))(rs => (rs.getLong(1), rs.getBytes(2))).headOption

  /** Hash of the entry at `seq`, if it exists. */
  def hashAt(tenant: UUID, seq: Long)(using DbTx): Option[Array[Byte]] =
    query("select hash from dms.audit_event where tenant_id = ? and chain_seq = ?") { ps =>
      ps.setObject(1, tenant)
      ps.setLong(2, seq)
    }(_.getBytes(1)).headOption

  /** The PostgreSQL text form of `json` as `jsonb`; this is what is stored and therefore what is hashed. */
  def normalizeJson(json: String)(using DbTx): String =
    query("select cast(? as jsonb)::text")(_.setString(1, json))(_.getString(1)).head

  def insert(e: ChainEntry, format: Int, hash: Array[Byte])(using DbTx): Long =
    query(
      """insert into dms.audit_event (tenant_id, chain_seq, hash_format, prev_hash, hash, occurred_at, actor_id,
        |  actor_kind, on_behalf_of, action, object_type, object_id, object_version, details, reason, channel,
        |  correlation_id)
        |values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?)
        |returning seq""".stripMargin
    ) { ps =>
      ps.setObject(1, e.tenantId)
      ps.setLong(2, e.chainSeq)
      ps.setShort(3, format.toShort)
      ps.setBytes(4, e.prevHash.orNull)
      ps.setBytes(5, hash)
      ps.setObject(6, OffsetDateTime.ofInstant(e.occurredAt, ZoneOffset.UTC))
      ps.setString(7, e.actorId)
      ps.setString(8, e.actorKind)
      ps.setString(9, e.onBehalfOf.orNull)
      ps.setString(10, e.action)
      ps.setString(11, e.objectType)
      ps.setObject(12, e.objectId.orNull)
      ps.setString(13, e.objectVersion.orNull)
      ps.setString(14, e.details)
      ps.setString(15, e.reason.orNull)
      ps.setString(16, e.channel)
      ps.setObject(17, e.correlationId.orNull)
    }(_.getLong(1)).head

  /** Streams the tenant's chain after position `after` in order to `f` until it returns false. */
  def foreachEntry(tenant: UUID, after: Long = 0L)(f: StoredEntry => Boolean)(using tx: DbTx): Unit =
    Using.resource(tx.connection.prepareStatement(selectEntriesAfter)) { ps =>
      ps.setFetchSize(500)
      ps.setObject(1, tenant)
      ps.setLong(2, after)
      Using.resource(ps.executeQuery()) { rs =>
        var continue = true
        while continue && rs.next() do continue = f(readEntry(rs))
      }
    }

  /** Reads the whole chain; for tests and small chains. */
  def entries(tenant: UUID)(using DbTx): Vector[StoredEntry] =
    query(selectEntries)(_.setObject(1, tenant))(readEntry)

  /** The entries about `objectId` in chain order; row-level security limits them to the current tenant. */
  def entriesOf(objectId: UUID)(using DbTx): Vector[StoredEntry] =
    query(selectColumns + " where object_id = ? order by chain_seq")(_.setObject(1, objectId))(readEntry)

  /** The newest anchor of the tenant with its TSA time. */
  def newestAnchor(tenant: UUID)(using DbTx): Option[(Long, java.time.Instant, String)] =
    query(
      """select anchored_until_seq, tsa_time, tsa_url from dms.audit_anchor
        |where tenant_id = ? order by anchored_until_seq desc limit 1""".stripMargin
    )(_.setObject(1, tenant))(rs =>
      (rs.getLong(1), rs.getObject(2, classOf[OffsetDateTime]).toInstant, rs.getString(3))
    ).headOption

  /** The anchors of the tenant from position `from` on. */
  def anchors(tenant: UUID, from: Long = 0L)(using DbTx): Vector[StoredAnchor] =
    query(
      """select anchored_until_seq, chain_head_hash, tsa_url, tsa_token from dms.audit_anchor
        |where tenant_id = ? and anchored_until_seq >= ? order by anchored_until_seq""".stripMargin
    ) { ps =>
      ps.setObject(1, tenant)
      ps.setLong(2, from)
    }(rs => StoredAnchor(rs.getLong(1), rs.getBytes(2), rs.getString(3), rs.getBytes(4)))

  /** The newest anchor up to position `until`: its position and hash. */
  def newestAnchorUpTo(tenant: UUID, until: Long)(using DbTx): Option[(Long, Array[Byte])] =
    query(
      """select anchored_until_seq, chain_head_hash from dms.audit_anchor
        |where tenant_id = ? and anchored_until_seq <= ?
        |order by anchored_until_seq desc limit 1""".stripMargin
    ) { ps =>
      ps.setObject(1, tenant)
      ps.setLong(2, until)
    }(rs => (rs.getLong(1), rs.getBytes(2))).headOption

  /** Whether every anchor up to position `until` still has the hash of its entry. */
  def anchorsMatchEntries(tenant: UUID, until: Long)(using DbTx): Boolean =
    query(
      """select count(*) from dms.audit_anchor a
        |left join dms.audit_event e on e.tenant_id = a.tenant_id and e.chain_seq = a.anchored_until_seq
        |where a.tenant_id = ? and a.anchored_until_seq <= ? and e.hash is distinct from a.chain_head_hash""".stripMargin
    ) { ps =>
      ps.setObject(1, tenant)
      ps.setLong(2, until)
    }(_.getLong(1)).head == 0L

  /** The `prev_hash` of the entries at `positions`. */
  def prevHashes(tenant: UUID, positions: Vector[Long])(using tx: DbTx): Map[Long, Array[Byte]] =
    query("select chain_seq, prev_hash from dms.audit_event where tenant_id = ? and chain_seq = any(?)") {
      ps =>
        ps.setObject(1, tenant)
        ps.setArray(2, tx.connection.createArrayOf("bigint", positions.map(Long.box).toArray[AnyRef]))
    }(rs => rs.getLong(1) -> rs.getBytes(2)).toMap

  /** Records a full verification and returns its id. */
  def insertRun(tenant: UUID, run: VerificationRun)(using DbTx): Long =
    query(
      """insert into dms.audit_verification_run (tenant_id, verified_at, ok, entries_checked, anchors_checked,
        |  head_seq, violation_seq, violation_kind, violation_detail, checkpoint_problem, prefix_seq, prefix_hash)
        |values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        |returning id""".stripMargin
    ) { ps =>
      ps.setObject(1, tenant)
      ps.setObject(2, OffsetDateTime.ofInstant(run.verifiedAt, ZoneOffset.UTC))
      ps.setBoolean(3, run.report.ok)
      ps.setLong(4, run.report.entriesChecked)
      ps.setInt(5, run.report.anchorsChecked)
      ps.setObject(6, run.report.headSeq.map(Long.box).orNull, java.sql.Types.BIGINT)
      ps.setObject(7, run.report.violation.map(v => Long.box(v.chainSeq)).orNull, java.sql.Types.BIGINT)
      ps.setString(8, run.report.violation.map(_.kind.toString).orNull)
      ps.setString(9, run.report.violation.map(_.detail).orNull)
      ps.setString(10, run.checkpointProblem.orNull)
      ps.setObject(11, run.prefix.map(p => Long.box(p._1)).orNull, java.sql.Types.BIGINT)
      ps.setBytes(12, run.prefix.map(_._2).orNull)
    }(_.getLong(1)).head

  /** The newest full verification of the tenant: when it ran, whether it was intact, and its prefix. */
  def newestRun(tenant: UUID)(using DbTx): Option[(Instant, Boolean, Option[(Long, Array[Byte])])] =
    query(
      """select verified_at, ok, prefix_seq, prefix_hash from dms.audit_verification_run
        |where tenant_id = ? order by id desc limit 1""".stripMargin
    )(_.setObject(1, tenant)) { rs =>
      val at   = rs.getObject(1, classOf[OffsetDateTime]).toInstant
      val ok   = rs.getBoolean(2)
      val seq  = rs.getLong(3)
      val none = rs.wasNull()
      (at, ok, Option.unless(none)((seq, rs.getBytes(4))))
    }.headOption

  /** What the cockpit shows of the newest full verification; the detail stays in the run. */
  def lastRun(tenant: UUID)(using DbTx): Option[VerificationSummary] =
    query(
      """select verified_at, entries_checked, violation_seq, violation_kind, checkpoint_problem is null
        |from dms.audit_verification_run where tenant_id = ? order by id desc limit 1""".stripMargin
    )(_.setObject(1, tenant)) { rs =>
      val at      = rs.getObject(1, classOf[OffsetDateTime]).toInstant
      val entries = rs.getLong(2)
      val seq     = rs.getLong(3)
      val none    = rs.wasNull()
      val kind    = Option(rs.getString(4))
      VerificationSummary(at, entries, kind, Option.unless(none)(seq), None, rs.getBoolean(5))
    }.headOption

  /** Serializes the runs and notices about violations of a tenant until the end of the transaction, so that
    * two runs that find one violation at once agree on which of them found it first.
    */
  def lockViolations(tenant: UUID)(using DbTx): Unit =
    query("select pg_advisory_xact_lock(hashtextextended('dms.audit_violation_notice:' || ?, 0))")(
      _.setString(1, tenant.toString)
    )(_ => ()): Unit

  /** The violation the newest run stands on, if that run found one: the first run of the unbroken series of
    * runs that found the same violation, and the notices about it so far.
    *
    * The same violation is the same kind at the same position, except for a checkpoint: only the newest one
    * is compared, and an unreadable one is reported at the head of the chain, so its position moves with the
    * chain and says nothing about the violation.
    */
  def openViolation(tenant: UUID)(using DbTx): Option[OpenViolation] =
    query(
      """with newest as (
        |  select violation_seq, violation_kind from dms.audit_verification_run
        |  where tenant_id = ? order by id desc limit 1
        |), first_run as (
        |  select min(r.id) as id from dms.audit_verification_run r, newest n
        |  where r.tenant_id = ? and n.violation_kind is not null and r.id > coalesce((
        |    select max(o.id) from dms.audit_verification_run o
        |    where o.tenant_id = ?
        |      and (o.violation_kind is distinct from n.violation_kind
        |           or (n.violation_kind <> ? and o.violation_seq is distinct from n.violation_seq))
        |  ), 0)
        |)
        |select r.id, r.verified_at,
        |       (select min(a.sent_at) from dms.audit_violation_notice a where a.run_id = r.id),
        |       (select max(a.round) from dms.audit_violation_notice a where a.run_id = r.id)
        |from dms.audit_verification_run r join first_run f on r.id = f.id""".stripMargin
    ) { ps =>
      ps.setObject(1, tenant)
      ps.setObject(2, tenant)
      ps.setObject(3, tenant)
      ps.setString(4, Violation.Kind.CheckpointMismatch.toString)
    } { rs =>
      val id    = rs.getLong(1)
      val found = rs.getObject(2, classOf[OffsetDateTime]).toInstant
      val told  = Option(rs.getObject(3, classOf[OffsetDateTime])).map(_.toInstant)
      val round = rs.getInt(4)
      OpenViolation(id, found, told.map(_ -> round))
    }.headOption

  /** Records a notice; returns false if this round of the violation was already sent. */
  def insertViolationNotice(tenant: UUID, runId: Long, round: Int, at: Instant)(using DbTx): Boolean =
    query(
      """insert into dms.audit_violation_notice (tenant_id, run_id, round, sent_at) values (?, ?, ?, ?)
        |on conflict do nothing returning run_id""".stripMargin
    ) { ps =>
      ps.setObject(1, tenant)
      ps.setLong(2, runId)
      ps.setInt(3, round)
      ps.setObject(4, OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
    }(_.getLong(1)).nonEmpty

  /** Stores an anchor; returns false if the position was already anchored (concurrent anchoring run). */
  def insertAnchor(tenant: UUID, anchor: StoredAnchor, tsaTime: Instant)(using DbTx): Boolean =
    query(
      """insert into dms.audit_anchor (tenant_id, anchored_until_seq, chain_head_hash, tsa_url, tsa_token, tsa_time)
        |values (?, ?, ?, ?, ?, ?)
        |on conflict (tenant_id, anchored_until_seq) do nothing
        |returning id""".stripMargin
    ) { ps =>
      ps.setObject(1, tenant)
      ps.setLong(2, anchor.anchoredUntilSeq)
      ps.setBytes(3, anchor.chainHeadHash)
      ps.setString(4, anchor.tsaUrl)
      ps.setBytes(5, anchor.token)
      ps.setObject(6, OffsetDateTime.ofInstant(tsaTime, ZoneOffset.UTC))
    }(_.getLong(1)).nonEmpty

  private val selectColumns =
    """select seq, hash_format, hash, tenant_id, chain_seq, prev_hash, occurred_at, actor_id, actor_kind,
      |  on_behalf_of, action, object_type, object_id, object_version, details::text, reason, channel,
      |  correlation_id
      |from dms.audit_event""".stripMargin

  private val selectEntries = selectColumns + " where tenant_id = ? order by chain_seq"

  private val selectEntriesAfter = selectColumns + " where tenant_id = ? and chain_seq > ? order by chain_seq"

  private def readEntry(rs: ResultSet): StoredEntry =
    StoredEntry(
      seq = rs.getLong(1),
      format = rs.getInt(2),
      hash = rs.getBytes(3),
      entry = ChainEntry(
        tenantId = rs.getObject(4, classOf[UUID]),
        chainSeq = rs.getLong(5),
        prevHash = Option(rs.getBytes(6)),
        occurredAt = rs.getObject(7, classOf[OffsetDateTime]).toInstant,
        actorId = rs.getString(8),
        actorKind = rs.getString(9),
        onBehalfOf = Option(rs.getString(10)),
        action = rs.getString(11),
        objectType = rs.getString(12),
        objectId = Option(rs.getObject(13, classOf[UUID])),
        objectVersion = Option(rs.getString(14)),
        details = rs.getString(15),
        reason = Option(rs.getString(16)),
        channel = rs.getString(17),
        correlationId = Option(rs.getObject(18, classOf[UUID]))
      )
    )

  private def query[A](sql: String)(bind: PreparedStatement => Unit)(read: ResultSet => A)(using
      tx: DbTx
  ): Vector[A] =
    val connection: Connection = tx.connection
    Using.resource(connection.prepareStatement(sql)) { ps =>
      bind(ps)
      Using.resource(ps.executeQuery()) { rs =>
        val result = Vector.newBuilder[A]
        while rs.next() do result += read(rs)
        result.result()
      }
    }
