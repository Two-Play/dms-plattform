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
  *   who is notified if the rendering fails: the document owner, otherwise the author
  */
final case class RenditionRequest(
    documentId: DocumentId,
    versionId: UUID,
    caseId: UUID,
    approver: UserId,
    approvedAt: Instant,
    authenticatedAt: Instant,
    recipients: List[UserId]
)

object RenditionRequest:
  val JobKind: String = "document.render-rendition"

  /** One rendering job per version: requesting it again (e.g. a repeated vote request) is a no-op. */
  def idempotencyKey(versionId: UUID): String = s"rendition:$versionId"

/** Outbox event types of rendering (ADR 0010). */
object RenditionEvents:
  /** The rendition of a version could not be produced; the version stays in approval (DMS-44). Payload:
    * `documentId`, `versionId`, `caseId`, `reason`, `attempt`, `maxAttempts`, `final` (no retry follows) and
    * `recipients` (user ids).
    */
  val Failed: String = "document.rendition-failed"
