package dms.audit

import dms.db.DbError
import dms.kernel.TenantId
import zio.{IO, ZIO}

import java.time.Instant
import java.util.UUID
import scala.annotation.unused

/** Append-only, hash-chained audit trail with one chain per tenant (ADR 0017). */
trait AuditTrail:

  /** Appends `event` to the chain of the current transaction's tenant.
    *
    * Must run inside `Db.transact(tenant)`: the entry joins the caller's transaction, so the business change
    * and its audit entry commit or roll back together. Fails with [[DbError.NoTransaction]] outside a
    * transaction and with [[DbError.TenantContextMissing]] without tenant context.
    */
  def record(event: AuditEvent): IO[DbError, AuditRecordId]

  /** Recomputes the chain of `tenant` and checks its timestamp anchors, in a transaction of its own. */
  def verify(tenant: TenantId): IO[DbError, VerificationReport]

  /** Verifies only what follows `prefix`, in a transaction of its own, so the cost does not grow with the
    * length of the chain (ADR 0077). The prefix is trusted only if the anchor that ends it still stands: the
    * token is valid, anchor and entry have the recorded hash, every older anchor has the hash of its entry,
    * and the entries about `objectId` below the prefix still match their hashes and their successors.
    *
    * @return
    *   `None` if the prefix cannot be trusted, which is the answer of a trail that cannot tell; the caller
    *   must then verify the whole chain
    */
  def verifyAfter(
      @unused tenant: TenantId,
      @unused prefix: VerifiedPrefix,
      @unused objectId: UUID
  ): IO[DbError, Option[VerificationReport]] = ZIO.none

/** The part of a chain the last full verification found intact, up to a timestamp anchor (ADR 0077).
  *
  * @param untilSeq
  *   chain position of the anchored entry that ends the prefix
  * @param hash
  *   hash of that entry, lowercase hex
  * @param verifiedAt
  *   when the full verification ran
  */
final case class VerifiedPrefix(untilSeq: Long, hash: String, verifiedAt: Instant)

/** Result of a chain verification.
  *
  * @param entriesChecked
  *   entries read before the first violation (all entries if the chain is intact); after a `trustedPrefix`
  *   only those that follow it
  * @param headSeq
  *   chain position of the last intact entry
  * @param headHash
  *   hash of the last intact entry, lowercase hex
  * @param anchorsChecked
  *   timestamp anchors found valid
  * @param violation
  *   the first violation found, if any
  * @param trustedPrefix
  *   what this verification took from the last full verification instead of reading it (ADR 0077)
  */
final case class VerificationReport(
    tenant: TenantId,
    entriesChecked: Long,
    headSeq: Option[Long],
    headHash: Option[String],
    anchorsChecked: Int,
    violation: Option[Violation],
    trustedPrefix: Option[VerifiedPrefix] = None
):
  def ok: Boolean = violation.isEmpty

/** A detected manipulation at chain position `chainSeq`. */
final case class Violation(chainSeq: Long, kind: Violation.Kind, detail: String)

object Violation:
  enum Kind:
    /** An entry is missing: the chain positions have a gap, or an anchored entry no longer exists. */
    case MissingEntry

    /** `prev_hash` does not equal the hash of the predecessor. */
    case BrokenLink

    /** The stored hash does not match the recomputed hash of the entry's content. */
    case HashMismatch

    /** The entry uses a hash format this version does not know. */
    case UnsupportedFormat

    /** A timestamp anchor does not match the chain, or its RFC 3161 token is invalid. */
    case AnchorMismatch

    /** A checkpoint in write-once storage does not match the chain, e.g. because the chain was truncated. */
    case CheckpointMismatch
