package dms.workflow

import dms.kernel.UserId

import java.time.Instant
import java.util.UUID

/** A time-limited deputyship (business concept 6.1, US-10.1, DMS-49, ADR 0028): while it is active, `deputy`
  * may complete the open workflow tasks of `principal` in the lanes of `roles`, recorded as "deputy on behalf
  * of principal". The principal keeps their tasks as well.
  *
  * @param roles
  *   business roles of workflow lanes the deputyship covers (`Reviewer`, `Approver`, `Author`, `Owner`)
  * @param documentClass
  *   restricts the deputyship to documents of this class and its subclasses; empty: all classes
  * @param endedAt
  *   set when the deputyship was ended early; it is not active from then on
  */
final case class Deputyship(
    id: UUID,
    principal: UserId,
    deputy: UserId,
    validFrom: Instant,
    validUntil: Instant,
    roles: List[String],
    documentClass: Option[String],
    reason: Option[String],
    createdBy: UserId,
    createdAt: Instant,
    endedAt: Option[Instant],
    endedBy: Option[UserId]
):
  /** End of the period: `validUntil`, or the early end. */
  def effectiveUntil: Instant = endedAt.filter(_.isBefore(validUntil)).getOrElse(validUntil)

  def isActiveAt(now: Instant): Boolean = !validFrom.isAfter(now) && now.isBefore(effectiveUntil)

  def status(now: Instant): DeputyshipStatus =
    if !now.isBefore(effectiveUntil) then DeputyshipStatus.Ended
    else if validFrom.isAfter(now) then DeputyshipStatus.Scheduled
    else DeputyshipStatus.Active

enum DeputyshipStatus:
  /** The period has not started yet. */
  case Scheduled

  /** Tasks of the principal are forwarded to the deputy. */
  case Active

  /** The period is over or the deputyship was ended early; unfinished tasks are the principal's alone again.
    */
  case Ended

/** A new deputyship as requested. `validFrom` defaults to now. */
final case class NewDeputyship(
    principal: UserId,
    deputy: UserId,
    validFrom: Option[Instant],
    validUntil: Instant,
    roles: List[String],
    documentClass: Option[String],
    reason: Option[String]
)
