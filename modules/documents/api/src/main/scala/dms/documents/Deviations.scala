package dms.documents

import dms.kernel.UserId
import dms.templates.{DeviationRequest, SectionId}

import java.time.Instant
import java.util.UUID

/** A deviation request (US-01.1) recorded on a document version: the author's reason for changing the
  * standard text of a locked template section. Active requests permit changing that section of the draft
  * ([[dms.templates.EditRules]]); the additional review step approves the change. A request can be withdrawn
  * while the version is a draft; once the version leaves Draft, its requests are frozen.
  *
  * @param basedOn
  *   the request of the previous version this one was carried over from when the version was started
  */
final case class RecordedDeviationRequest(
    id: UUID,
    versionId: UUID,
    sectionId: SectionId,
    reason: String,
    requestedBy: UserId,
    requestedAt: Instant,
    basedOn: Option[UUID],
    withdrawnBy: Option[UserId],
    withdrawnAt: Option[Instant]
):
  def active: Boolean = withdrawnAt.isEmpty

  /** The request as the edit rules and the deviation report see it. */
  def request: DeviationRequest = DeviationRequest(sectionId, reason, requestedBy, requestedAt)

object RecordedDeviationRequest:
  /** The active requests in the form the edit rules and the deviation report take them. */
  def active(requests: List[RecordedDeviationRequest]): List[DeviationRequest] =
    requests.filter(_.active).map(_.request)
