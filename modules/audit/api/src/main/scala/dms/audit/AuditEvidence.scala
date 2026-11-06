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

/** The newest full verification of a chain as the compliance cockpit shows it (ADR 0080): nothing of the
  * audit entries, and the technical detail of a violation stays in the run.
  *
  * @param violationKind
  *   the name of the [[Violation.Kind]] found; `None`: the chain was intact
  * @param violationSeq
  *   the chain position of what was found
  * @param violatedSince
  *   when the first run of the series that found this violation ran
  * @param checkpointsCompared
  *   `false` if the checkpoints could not be read, so the run covers links, hashes and anchors only
  */
final case class VerificationSummary(
    verifiedAt: Instant,
    entriesChecked: Long,
    violationKind: Option[String],
    violationSeq: Option[Long],
    violatedSince: Option[Instant],
    checkpointsCompared: Boolean
)

/** The outbox event of a violation of the audit chain that people should hear of (ADR 0080). Aggregate id:
  * the tenant. Payload: `foundAt` (when a run found it first) and, for a reminder, `reminder` (the round). It
  * names nothing of what was found.
  */
object AuditViolation:
  val AggregateType = "audit-chain"
  val Event         = "audit.chain-violated"

/** Read access to the audit trail for evidence reports (US-12.1, DMS-57, ADR 0034). */
trait AuditEvidence:

  /** The entries about `objectId` (`object_id`), in chain order. Must run inside `Db.transact(tenant)`. */
  def entriesOf(objectId: UUID): IO[DbError, List[RecordedEntry]]

  /** The state of the chain of `tenant` for a report about `objectId`, in transactions of its own: what
    * follows the verified prefix of the last full verification, including the newest checkpoint (ADR 0077).
    * Whenever the prefix cannot be used or something is wrong, this is the result of [[verifyFully]].
    */
  def integrity(tenant: TenantId, objectId: UUID): IO[DbError, ChainIntegrity]

  /** Verifies the whole chain of `tenant` including its newest checkpoint and records the run, whatever it
    * finds; the regular integrity check (ADR 0017, 0077).
    */
  def verifyFully(tenant: TenantId): IO[DbError, ChainIntegrity]

  /** The newest full verification of the tenant, if there was one. Must run inside `Db.transact(tenant)`. */
  def lastVerification: IO[DbError, Option[VerificationSummary]]

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

/** A registry entry without its payload, for lists (ADR 0077).
  *
  * @param integrityStatus
  *   the status the report states; gone with the payload
  * @param removedAt
  *   when the payload was removed after its retention ended; the checksum stays
  */
final case class EvidenceReportEntry(
    id: UUID,
    documentId: UUID,
    format: Int,
    createdAt: Instant,
    createdBy: String,
    sha256: String,
    integrityStatus: Option[String],
    removedAt: Option[Instant]
)

/** The registry of generated evidence reports (ADR 0034): a report can be downloaded and verified again until
  * its retention ended (ADR 0077); only then its payload goes, the entry stays. Every method must run inside
  * `Db.transact(tenant)`.
  */
trait EvidenceReportRegistry:
  def register(report: StoredEvidenceReport): IO[DbError, Unit]

  /** A report with its payload; `None` also once the payload is removed, see [[entry]]. */
  def find(id: UUID): IO[DbError, Option[StoredEvidenceReport]]

  def entry(id: UUID): IO[DbError, Option[EvidenceReportEntry]]

  /** The entries of a document, the latest first. */
  def entries(document: UUID, limit: Int, offset: Int): IO[DbError, List[EvidenceReportEntry]]

  def count(document: UUID): IO[DbError, Int]
