package dms.documents

import dms.documents.metadata.CoreFields
import dms.kernel.UserId

import java.time.Instant
import java.util.UUID

/** State of a request to lower the confidentiality of a later version (business concept 3.2, ADR 0083). */
enum LoweringStatus:
  /** Asked for on the draft; decided in the additional approval step of the workflow. */
  case Requested

  /** Approved in that step: the version states the new level, access keeps the old one until publication. */
  case Approved

  /** Refused in that step: the version went back to its author at the old level. */
  case Refused

  /** Withdrawn while the version was a draft. */
  case Withdrawn

/** A request to lower the confidentiality of a later version, kept for good.
  *
  * @param from
  *   the level the version stands at; for an approved request the level that governs access until publication
  * @param to
  *   the level asked for
  * @param decidedBy
  *   who approved or refused it; for a withdrawn request who withdrew it
  * @param actingFor
  *   the candidate a deputy decided for (ADR 0028)
  */
final case class ConfidentialityLowering(
    id: UUID,
    versionId: UUID,
    from: String,
    to: String,
    reason: String,
    requestedBy: UserId,
    requestedAt: Instant,
    status: LoweringStatus = LoweringStatus.Requested,
    decidedBy: Option[UserId] = None,
    decidedAt: Option[Instant] = None,
    decisionReason: Option[String] = None,
    actingFor: Option[UserId] = None
)

object ConfidentialityLowering:

  /** Why `to` cannot be asked for on a draft that stands at `current` and started from a version at `basis`;
    * empty if it can. `None` for `basis`: a first version chooses its level freely (ADR 0074). A level the
    * basis has or a higher one needs no approval either: the draft is simply saved with it.
    */
  def problems(to: String, current: String, basis: Option[String]): List[String] =
    def rank(level: String) = CoreFields.ConfidentialityLevels.indexOf(level)
    if !CoreFields.ConfidentialityLevels.contains(to) then
      List(s"unknown confidentiality level '$to'; one of ${CoreFields.ConfidentialityLevels.mkString(", ")}")
    else
      basis match
        case None =>
          List("a first version chooses its confidentiality freely; save the draft with the level")
        case Some(level) if rank(to) >= rank(level) =>
          List(s"only a level below $level needs an approval; save the draft with the level")
        case Some(_) if rank(to) >= rank(current) =>
          List(s"the draft already stands at $current")
        case _ => Nil
