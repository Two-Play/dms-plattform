package dms.workflow.core

import dms.documents.{DocumentStatus, LifecycleEvent}
import dms.kernel.UserId
import dms.workflow.*

import java.time.{Duration, Instant}

/** Pure rules of the approval workflow (business concept 2.4, 6.1; DMS-40 to DMS-42). */
object Rules:

  /** Participants of a lane: the holders of its role, without the author while the four-eyes rule is on (the
    * next holders take the task). Empty means nobody is eligible and the task waits.
    */
  def eligible(holders: List[UserId], author: UserId, fourEyes: Boolean): List[UserId] =
    holders.distinct.filterNot(u => fourEyes && u == author).sortBy(_.value)

  /** Who confirms an editorial version (DMS-46): its owner, unless the owner is the author or there is none;
    * then nobody, and the holders of [[WorkflowDefinition.OwnerDeputyRole]] take the task. The author never
    * confirms their own version, whatever the four-eyes setting (the approval is segregated in any case).
    */
  def ownerConfirmation(owner: Option[UserId], author: UserId): List[UserId] =
    owner.filterNot(_ == author).toList

  /** Where a resubmitted version continues and whether earlier votes became invalid: after a content change
    * at the reset step of the definition (DMS-41), otherwise at the step that returned it.
    */
  def resume(
      definition: WorkflowDefinition,
      returnedStep: Option[String],
      votedChecksum: String,
      currentChecksum: String
  ): Resume =
    if votedChecksum != currentChecksum then Resume(definition.resetStep, contentChanged = true)
    else Resume(returnedStep.filter(definition.step(_).isDefined).getOrElse(definition.firstStep), false)

  final case class Resume(step: String, contentChanged: Boolean)

  /** Step-up for approval votes: the sign-in must not be older than `window`. A token without `auth_time`
    * fails closed.
    */
  def recentSignIn(authenticatedAt: Option[Instant], now: Instant, window: Duration): Boolean =
    authenticatedAt.exists(at => !at.plus(window).isBefore(now) && !at.isAfter(now.plusSeconds(60)))

  /** Checks a vote before it is recorded. */
  def checkVote(
      kind: StepKind,
      decision: VoteDecision,
      reason: Option[String],
      authenticatedAt: Option[Instant],
      now: Instant,
      window: Duration
  ): Either[WorkflowError, Unit] =
    if decision.needsReason && reason.forall(_.trim.isEmpty) then
      Left(WorkflowError.Invalid(List(s"a reason is required for $decision")))
    else if kind == StepKind.Approval && !recentSignIn(authenticatedAt, now, window) then
      Left(WorkflowError.ReauthenticationRequired)
    else Right(())

  /** The lifecycle event that returns a version to its author: from review a change request, from approval a
    * rejection (both lead back to `Draft`).
    */
  def returnEvent(status: DocumentStatus): Either[WorkflowError, LifecycleEvent] = status match
    case DocumentStatus.InReview   => Right(LifecycleEvent.RequestChanges)
    case DocumentStatus.InApproval => Right(LifecycleEvent.Reject)
    case other => Left(WorkflowError.Conflict(s"a version in status $other cannot be returned"))

  /** BPMN element id of the user task of a lane. */
  def taskKey(step: String, lane: String): String = s"task.$step.$lane"

  def parseTaskKey(key: String): Option[(String, String)] = key.split('.').toList match
    case "task" :: step :: lane :: Nil => Some((step, lane))
    case _                             => None
