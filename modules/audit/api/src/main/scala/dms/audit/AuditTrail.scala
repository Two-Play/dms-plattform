package dms.audit

import dms.db.DbError
import dms.kernel.TenantId
import zio.IO

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

/** Result of a chain verification.
  *
  * @param entriesChecked
  *   entries read before the first violation (all entries if the chain is intact)
  * @param headSeq
  *   chain position of the last intact entry
  * @param headHash
  *   hash of the last intact entry, lowercase hex
  * @param anchorsChecked
  *   timestamp anchors found valid
  * @param violation
  *   the first violation found, if any
  */
final case class VerificationReport(
    tenant: TenantId,
    entriesChecked: Long,
    headSeq: Option[Long],
    headHash: Option[String],
    anchorsChecked: Int,
    violation: Option[Violation]
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
