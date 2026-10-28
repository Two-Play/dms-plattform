package dms.documents

import dms.kernel.UserId

/** Status of a document version (business concept 5.1). */
enum DocumentStatus:
  case Draft, InReview, InApproval, Approved, Published, ReviewDue, Superseded, Withdrawn, Archived, Destroyed

/** Domain events that trigger a status change. */
enum LifecycleEvent:
  case Submit, RequestChanges, ReviewPassed, Reject, Approve, Publish, ReviewDueReached, Recertify, Supersede,
    Withdraw, Archive, Destroy

enum LifecycleError:
  case TransitionNotAllowed(from: DocumentStatus, event: LifecycleEvent)
  case LegalHoldActive
  case SegregationOfDutiesViolated(user: UserId)

/** Pure state machine of the document lifecycle, free of effects. */
object Lifecycle:
  import DocumentStatus.*
  import LifecycleEvent.*

  private val transitions: Map[(DocumentStatus, LifecycleEvent), DocumentStatus] = Map(
    (Draft, Submit)               -> InReview,
    (Draft, Withdraw)             -> Withdrawn,
    (InReview, RequestChanges)    -> Draft,
    (InReview, ReviewPassed)      -> InApproval,
    (InApproval, Reject)          -> Draft,
    (InApproval, Approve)         -> Approved,
    (Approved, Publish)           -> Published,
    (Published, ReviewDueReached) -> ReviewDue,
    (ReviewDue, Recertify)        -> Published,
    (Published, Supersede)        -> Superseded,
    (ReviewDue, Supersede)        -> Superseded,
    (Superseded, Archive)         -> Archived,
    (Withdrawn, Archive)          -> Archived,
    (Archived, Destroy)           -> Destroyed
  )

  def allowedEvents(from: DocumentStatus): Set[LifecycleEvent] =
    transitions.keySet.collect { case (`from`, e) => e }

  def transition(from: DocumentStatus, event: LifecycleEvent): Either[LifecycleError, DocumentStatus] =
    transitions.get((from, event)).toRight(LifecycleError.TransitionNotAllowed(from, event))

  /** Destruction is only allowed without a legal hold (business concept 7.4). */
  def destroy(from: DocumentStatus, legalHold: Boolean): Either[LifecycleError, DocumentStatus] =
    if legalHold then Left(LifecycleError.LegalHoldActive) else transition(from, Destroy)

  /** Approval with segregation of duties: author and approver must differ (business concept 2.4). */
  def approve(
      from: DocumentStatus,
      author: UserId,
      approver: UserId
  ): Either[LifecycleError, DocumentStatus] =
    if author == approver then Left(LifecycleError.SegregationOfDutiesViolated(approver))
    else transition(from, Approve)
