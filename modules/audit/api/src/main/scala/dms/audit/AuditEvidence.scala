package dms.audit

import dms.db.DbError
import dms.kernel.TenantId
import zio.IO

import java.time.Instant
import java.util.UUID

/** An entry of the audit chain as stored, for evidence (DMS-57).
  *
  * @param details
  *   the PostgreSQL text form of the `details` jsonb value, exactly as hashed
  * @param hash
  *   the stored hash of the entry, lowercase hex
  */
final case class RecordedEntry(
    chainSeq: Long,
    occurredAt: Instant,
    actorId: String,
    actorKind: String,
    onBehalfOf: Option[String],
    action: String,
    objectType: String,
    objectId: Option[UUID],
    objectVersion: Option[String],
    details: String,
    reason: Option[String],
    channel: String,
    correlationId: Option[UUID],
    hash: String
)

/** The newest timestamp anchor of a chain: the entries up to `anchoredUntilSeq` existed unchanged at
  * `tsaTime`.
  */
final case class AnchorState(anchoredUntilSeq: Long, tsaTime: Instant, tsaUrl: String)

/** The state of a tenant's chain for evidence (DMS-57): the full verification (links, hashes, anchors, newest
  * write-once checkpoint) plus how far the chain is anchored and checkpointed.
  *
  * @param anchor
  *   the newest stored timestamp anchor, if any
  * @param checkpointedUntilSeq
  *   chain position of the newest write-once checkpoint, if any
  * @param checkpointProblem
  *   why the checkpoints could not be compared (e.g. object storage unavailable); the verification then
  *   covers links, hashes and anchors only
  */
final case class ChainIntegrity(
    verification: VerificationReport,
    anchor: Option[AnchorState],
    checkpointedUntilSeq: Option[Long],
    checkpointProblem: Option[String]
)

/** Read access to the audit trail for evidence reports (US-12.1, DMS-57, ADR 0034). */
trait AuditEvidence:

  /** The entries about `objectId` (`object_id`), in chain order. Must run inside `Db.transact(tenant)`. */
  def entriesOf(objectId: UUID): IO[DbError, List[RecordedEntry]]

  /** Verifies the chain of `tenant` including its newest checkpoint, in transactions of its own. */
  def integrity(tenant: TenantId): IO[DbError, ChainIntegrity]

/** A generated evidence report as registered (migration V21).
  *
  * @param payload
  *   the canonical JSON of the report; `sha256` is the lowercase hex SHA-256 of its UTF-8 bytes
  */
final case class StoredEvidenceReport(
    id: UUID,
    documentId: UUID,
    format: Int,
    createdAt: Instant,
    createdBy: String,
    payload: String,
    sha256: String
)

/** The registry of generated evidence reports (ADR 0034): insert-only, so a report can be downloaded and
  * verified again later. Every method must run inside `Db.transact(tenant)`.
  */
trait EvidenceReportRegistry:
  def register(report: StoredEvidenceReport): IO[DbError, Unit]
  def find(id: UUID): IO[DbError, Option[StoredEvidenceReport]]
