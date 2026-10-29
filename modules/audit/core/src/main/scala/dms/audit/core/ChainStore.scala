package dms.audit.core

import com.augustnagro.magnum.DbTx

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

  /** Streams the tenant's chain in order to `f` until it returns false. */
  def foreachEntry(tenant: UUID)(f: StoredEntry => Boolean)(using tx: DbTx): Unit =
    Using.resource(tx.connection.prepareStatement(selectEntries)) { ps =>
      ps.setFetchSize(500)
      ps.setObject(1, tenant)
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

  def anchors(tenant: UUID)(using DbTx): Vector[StoredAnchor] =
    query(
      """select anchored_until_seq, chain_head_hash, tsa_url, tsa_token from dms.audit_anchor
        |where tenant_id = ? order by anchored_until_seq""".stripMargin
    )(_.setObject(1, tenant))(rs =>
      StoredAnchor(rs.getLong(1), rs.getBytes(2), rs.getString(3), rs.getBytes(4))
    )

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
