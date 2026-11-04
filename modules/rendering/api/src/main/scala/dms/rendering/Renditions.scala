package dms.rendering

import dms.kernel.{DocumentId, UserId}

import java.time.Instant
import java.util.UUID

/** The archive standard renditions are produced and validated against (ADR 0025).
  *
  * @param variant
  *   the `pdf_variant` of the rendering worker (WeasyPrint)
  * @param flavour
  *   the veraPDF validation profile
  */
enum ArchiveStandard(val variant: String, val flavour: String, val label: String):
  case PdfA2b extends ArchiveStandard("pdf/a-2b", "2b", "PDF/A-2b")

object ArchiveStandard:
  /** PDF/A-2b (ISO 19005-2, level B): visual reproducibility, embedded fonts, no external dependencies. */
  val default: ArchiveStandard = PdfA2b

/** Request to render the archive rendition of a version whose last workflow step was approved (DMS-44). It is
  * the payload of a job of kind [[RenditionRequest.JobKind]]; the job attaches the rendition and completes
  * the approval of `caseId` as `approver`.
  *
  * @param approvedAt
  *   when the last approving vote was cast; the single source of every date in the PDF (determinism, ADR
  *   0008)
  * @param authenticatedAt
  *   the approver's sign-in at the vote (`auth_time`), stored with the approval
  * @param recipients
  *   the document owner, otherwise the author, when the version was approved; recorded with failed attempts.
  *   Who is notified of a dead job is decided when it dies (DMS-142)
  * @param onBehalfOf
  *   the approver the deputy `approver` stood in for (DMS-49), shown in the approval note
  */
final case class RenditionRequest(
    documentId: DocumentId,
    versionId: UUID,
    caseId: UUID,
    approver: UserId,
    approvedAt: Instant,
    authenticatedAt: Instant,
    recipients: List[UserId],
    onBehalfOf: Option[UserId] = None
)

object RenditionRequest:
  val JobKind: String = "document.render-rendition"

  /** One rendering job per version: requesting it again (e.g. a repeated vote request) is a no-op. */
  def idempotencyKey(versionId: UUID): String = s"rendition:$versionId"

/** Outbox event types of rendering (ADR 0010). */
object RenditionEvents:
  /** An attempt to produce the rendition of a version failed; the version stays in approval (DMS-44).
    * Payload: `documentId`, `versionId`, `caseId`, `reason`, `attempt`, `maxAttempts`, `final` (no retry
    * follows) and `recipients` (user ids). Nobody is notified of it: people are told by [[Dead]].
    */
  val Failed: String = "document.rendition-failed"

  /** The rendering job of a version is dead: every attempt failed (DMS-142, ADR 0058). Written by the
    * database when the job's status changes (migration V40), so also for a worker that crashed on its last
    * attempt. Payload: `documentId`, `versionId`, `jobId`, `attempts` and `reason` (the failure code, if
    * there is one).
    */
  val Dead: String = "document.rendition-dead"
