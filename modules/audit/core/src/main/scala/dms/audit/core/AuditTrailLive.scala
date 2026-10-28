package dms.audit.core

import com.augustnagro.magnum.DbTx
import dms.audit.*
import dms.db.{Db, DbError}
import dms.kernel.TenantId
import zio.*

import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit

/** [[AuditTrail]] on `dms.audit_event` with one hash chain per tenant (ADR 0017). */
final class AuditTrailLive(db: Db) extends AuditTrail:

  def record(event: AuditEvent): IO[DbError, AuditRecordId] =
    for
      // PostgreSQL stores microseconds; hash exactly what is stored.
      now <- Clock.instant.map(_.truncatedTo(ChronoUnit.MICROS))
      seq <- Db.sql(AuditTrailLive.append(event, now))
    yield AuditRecordId(seq)

  def verify(tenant: TenantId): IO[DbError, VerificationReport] =
    db.transact(tenant)(Db.sql(ChainVerifier.verify(tenant)))

object AuditTrailLive:
  val layer: ZLayer[Db, Nothing, AuditTrail] = ZLayer.fromFunction(AuditTrailLive(_))

  private def append(event: AuditEvent, occurredAt: Instant)(using DbTx): Long =
    val tenant = ChainStore.currentTenant
    // Serialize appends of this tenant; the head is read after the lock with a fresh snapshot.
    ChainStore.lockChain(tenant)
    val head  = ChainStore.head(tenant)
    val entry = ChainEntry(
      tenantId = tenant,
      chainSeq = head.fold(1L)(_._1 + 1),
      prevHash = head.map(_._2),
      occurredAt = occurredAt,
      actorId = event.actor.id,
      actorKind = event.actor.kind.code,
      onBehalfOf = event.actor.onBehalfOf,
      action = event.action,
      objectType = event.target.objectType,
      objectId = event.target.id,
      objectVersion = event.target.version,
      details = ChainStore.normalizeJson(event.details.value),
      reason = event.reason,
      channel = event.channel.code,
      correlationId = event.correlationId
    )
    ChainStore.insert(entry, AuditHash.CurrentFormat, AuditHash.hash(entry))

/** Walks a tenant's chain in order and stops at the first violation. */
private[core] object ChainVerifier:

  def verify(tenant: TenantId)(using DbTx): VerificationReport =
    val anchors   = ChainStore.anchors(tenant.value)
    val bySeq     = anchors.groupBy(_.anchoredUntilSeq)
    var checked   = 0L
    var anchorsOk = 0
    var last      = Option.empty[(Long, Array[Byte])]
    var violation = Option.empty[Violation]

    def fail(seq: Long, kind: Violation.Kind, detail: String): Boolean =
      violation = Some(Violation(seq, kind, detail))
      false

    ChainStore.foreachEntry(tenant.value) { stored =>
      val e        = stored.entry
      val expected = last.fold(1L)(_._1 + 1)
      if e.chainSeq != expected then
        fail(expected, Violation.Kind.MissingEntry, s"expected position $expected, found ${e.chainSeq}")
      else if !sameHash(e.prevHash, last.map(_._2)) then
        fail(e.chainSeq, Violation.Kind.BrokenLink, "prev_hash does not match the predecessor's hash")
      else if stored.format != AuditHash.CurrentFormat then
        fail(e.chainSeq, Violation.Kind.UnsupportedFormat, s"hash format ${stored.format}")
      else if !MessageDigest.isEqual(AuditHash.hash(e), stored.hash) then
        fail(e.chainSeq, Violation.Kind.HashMismatch, "stored hash does not match the entry's content")
      else
        val anchorProblem =
          bySeq.getOrElse(e.chainSeq, Vector.empty).iterator.map(checkAnchor(_, stored.hash))
        anchorProblem.collectFirst { case Some(problem) => problem } match
          case Some(problem) => fail(e.chainSeq, Violation.Kind.AnchorMismatch, problem)
          case None          =>
            checked += 1
            anchorsOk += bySeq.get(e.chainSeq).fold(0)(_.size)
            last = Some((e.chainSeq, stored.hash))
            true
    }

    // An anchor beyond the end of the chain means anchored entries were removed (truncated tail).
    if violation.isEmpty then
      val headSeq = last.fold(0L)(_._1)
      anchors.find(_.anchoredUntilSeq > headSeq).foreach { a =>
        fail(
          a.anchoredUntilSeq,
          Violation.Kind.MissingEntry,
          s"anchored entry missing, chain ends at $headSeq"
        ): Unit
      }

    VerificationReport(
      tenant = tenant,
      entriesChecked = checked,
      headSeq = last.map(_._1),
      headHash = last.map(l => AuditHash.hex(l._2)),
      anchorsChecked = anchorsOk,
      violation = violation
    )

  private def checkAnchor(anchor: StoredAnchor, entryHash: Array[Byte]): Option[String] =
    if !MessageDigest.isEqual(anchor.chainHeadHash, entryHash) then
      Some("anchored chain head hash does not match the entry's hash")
    else Rfc3161.verify(anchor.token, anchor.chainHeadHash).left.toOption

  private def sameHash(a: Option[Array[Byte]], b: Option[Array[Byte]]): Boolean = (a, b) match
    case (None, None)       => true
    case (Some(x), Some(y)) => MessageDigest.isEqual(x, y)
    case _                  => false
