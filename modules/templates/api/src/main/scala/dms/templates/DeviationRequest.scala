package dms.templates

import dms.kernel.UserId

import java.time.Instant

/** An author's request to deviate from the standard text of a locked section (US-01.1). A recorded request
  * permits editing that section of the document; it does not approve the change. Approval happens in the
  * additional review step of the document class, which the workflow adds as soon as a request is active, even
  * before the text changes (see [[DeviationReport.requiresExtraReview]], ADR 0071).
  */
final case class DeviationRequest(
    sectionId: SectionId,
    reason: String,
    requestedBy: UserId,
    requestedAt: Instant
)
