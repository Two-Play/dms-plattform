package dms.documents

import dms.kernel.UserId

/** Status of a document version (business concept 5.1). `Discarded` (DMS-129, ADR 0050) ends a draft that was
  * never submitted, e.g. one created by accident: the version is kept as a record (audit trail, number
  * ranges), but nobody reads it any more and it is never valid, archived or destroyed.
  */
enum DocumentStatus:
  case Draft, InReview, InApproval, Approved, Published, ReviewDue, Superseded, Withdrawn, Archived,
    Destroyed,
    Discarded

/** Domain events that trigger a status change. */
enum LifecycleEvent:
  case Submit, RequestChanges, ReviewPassed, Reject, Approve, Publish, ReviewDueReached, Recertify, Supersede,
    Withdraw, Archive, Destroy, Discard

enum LifecycleError:
  case TransitionNotAllowed(from: DocumentStatus, event: LifecycleEvent)
  case LegalHoldActive
  case SegregationOfDutiesViolated(user: UserId)

/** Pure state machine of the document lifecycle, free of effects. */
object Lifecycle:
  import DocumentStatus.*
  import LifecycleEvent.*

  private val transitions: Map[(DocumentStatus, LifecycleEvent), DocumentStatus] = Map(
    (Draft, Submit)   -> InReview,
    (Draft, Withdraw) -> Withdrawn,
    // A draft not submitted yet is discarded by its author, the owner or a tenant administrator (DMS-129).
    (Draft, Discard)           -> Discarded,
    (InReview, RequestChanges) -> Draft,
    (InReview, ReviewPassed)   -> InApproval,
    (InApproval, Reject)       -> Draft,
    (InApproval, Approve)      -> Approved,
    // Its rendition failed for good, so approval cannot finish (DMS-163, ADR 0078): it never took effect.
    (InApproval, Withdraw) -> Withdrawn,
    (Approved, Publish)    -> Published,
    // Stopped before its valid-from date (DMS-143, ADR 0059): it never took effect.
    (Approved, Withdraw)          -> Withdrawn,
    (Published, ReviewDueReached) -> ReviewDue,
    (ReviewDue, Recertify)        -> Published,
    (Published, Supersede)        -> Superseded,
    (ReviewDue, Supersede)        -> Superseded,
    // Out of force without a successor, e.g. after a periodic review (business concept 6.2, US-07.1).
    (Published, Withdraw) -> Withdrawn,
    (ReviewDue, Withdraw) -> Withdrawn,
    (Superseded, Archive) -> Archived,
    (Withdrawn, Archive)  -> Archived,
    (Archived, Destroy)   -> Destroyed
  )

  def allowedEvents(from: DocumentStatus): Set[LifecycleEvent] =
    transitions.keySet.collect { case (`from`, e) => e }

  def transition(from: DocumentStatus, event: LifecycleEvent): Either[LifecycleError, DocumentStatus] =
    transitions.get((from, event)).toRight(LifecycleError.TransitionNotAllowed(from, event))

  /** Destruction is only allowed without a legal hold (business concept 7.4). */
  def destroy(from: DocumentStatus, legalHold: Boolean): Either[LifecycleError, DocumentStatus] =
    if legalHold then Left(LifecycleError.LegalHoldActive) else transition(from, Destroy)

  /** Approval with segregation of duties: the approver is none of the version's authors, who are all who
    * started or changed it (business concept 2.4, ADR 0075).
    */
  def approve(
      from: DocumentStatus,
      authors: Set[UserId],
      approver: UserId
  ): Either[LifecycleError, DocumentStatus] =
    if authors.contains(approver) then Left(LifecycleError.SegregationOfDutiesViolated(approver))
    else transition(from, Approve)
