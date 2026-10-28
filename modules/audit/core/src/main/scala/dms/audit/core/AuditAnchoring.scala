package dms.audit.core

import dms.db.{Db, DbError}
import dms.kernel.TenantId
import zio.*

import java.time.Instant

/** A stored timestamp anchor over a tenant's chain head. */
final case class Anchor(tenant: TenantId, anchoredUntilSeq: Long, chainHeadHash: String, tsaTime: Instant)

/** Anchors the chain heads with RFC 3161 timestamps (business concept 7.3, ADR 0017).
  *
  * A timestamp proves that the chain up to the anchored entry existed unchanged at the TSA's time; an
  * operator who rewrites the whole chain afterwards cannot produce matching tokens.
  */
final class AuditAnchoring(db: Db, tsa: TimestampAuthority, trust: TsaTrust):

  /** Timestamps the current chain head of `tenant` and stores the token. Returns `None` if the chain is empty
    * or its head is already anchored.
    *
    * The TSA is called between two short transactions so that no connection is held during the network call.
    */
  def anchor(tenant: TenantId): IO[DbError | TimestampError, Option[Anchor]] =
    db.transact(tenant)(Db.sql {
      val head     = ChainStore.head(tenant.value)
      val anchored = ChainStore.anchors(tenant.value).lastOption.map(_.anchoredUntilSeq)
      head.filterNot((seq, _) => anchored.contains(seq))
    }).flatMap {
      case None              => ZIO.none
      case Some((seq, hash)) =>
        for
          token <- tsa.timestamp(hash)
          time  <- ZIO.fromEither(Rfc3161.verify(token, hash, trust)).mapError(TimestampError(_))
          _     <- db.transact(tenant)(
            Db.sql(ChainStore.insertAnchor(tenant.value, StoredAnchor(seq, hash, tsa.url, token), time))
          )
        yield Some(Anchor(tenant, seq, AuditHash.hex(hash), time))
    }

  /** Anchors all `tenants` once a day at `hourOfDay` (local time of the clock). Failures are logged and
    * retried the next day. Meant to be forked by the application.
    */
  def daily(tenants: IO[DbError, List[TenantId]], hourOfDay: Int = 2): UIO[Unit] =
    val run = tenants.flatMap(
      ZIO.foreachDiscard(_)(t =>
        anchor(t).foldZIO(
          e => ZIO.logWarning(s"Audit anchoring for tenant ${t.value} failed: $e"),
          a => ZIO.logInfo(s"Audit chain of tenant ${t.value} anchored: $a")
        )
      )
    )
    run
      .catchAll(e => ZIO.logWarning(s"Audit anchoring could not list tenants: $e"))
      .schedule(Schedule.hourOfDay(hourOfDay))
      .unit

object AuditAnchoring:
  val layer: ZLayer[Db & TimestampAuthority & TsaTrust, Nothing, AuditAnchoring] =
    ZLayer.fromFunction(AuditAnchoring(_, _, _))
