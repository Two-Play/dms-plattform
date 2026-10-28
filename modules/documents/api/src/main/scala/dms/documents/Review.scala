package dms.documents

import dms.kernel.{DocumentId, UserId}

import java.time.{Instant, LocalDate}
import java.util.UUID

/** Periodic review settings of a document class (business concept 6.2, US-07.1).
  *
  * @param intervalMonths
  *   review cycle: a version is reviewed this many months after it became valid; `None`: no periodic review
  *   unless the document sets a date in its `nextReview` field
  * @param leadDays
  *   how many days before the review date the owner gets the review task (default 60)
  */
final case class ReviewPolicy(
    intervalMonths: Option[Int] = None,
    leadDays: Int = ReviewPolicy.DefaultLeadDays
):
  def problems: List[String] =
    intervalMonths
      .filterNot(m => m >= 1 && m <= ReviewPolicy.MaxIntervalMonths)
      .map(m => s"the review interval must be 1 to ${ReviewPolicy.MaxIntervalMonths} months, not $m")
      .toList ++
      Option
        .unless(leadDays >= 0 && leadDays <= ReviewPolicy.MaxLeadDays)(
          s"the review lead time must be 0 to ${ReviewPolicy.MaxLeadDays} days, not $leadDays"
        )
        .toList

object ReviewPolicy:
  val DefaultLeadDays: Int   = 60
  val MaxLeadDays: Int       = 365
  val MaxIntervalMonths: Int = 120
  val default: ReviewPolicy  = ReviewPolicy()

/** Outcome of a review task. `Open` until the owner decides; `Obsolete` if the version stopped being valid
  * without a decision (e.g. a new version was published).
  */
enum ReviewStatus:
  case Open, Recertified, Revised, Withdrawn, Obsolete

/** A review task of a valid version (US-07.1): created when the class's lead time before the review date is
  * reached, decided once by the document owner.
  *
  * @param dueOn
  *   the review date the task is about
  * @param nextDueOn
  *   for `Recertified`: the new review date
  * @param newVersionId
  *   for `Revised`: the draft started for the revision
  * @param comment
  *   the owner's note; for `Withdrawn` the reason (required)
  */
final case class ReviewTask(
    id: UUID,
    documentId: DocumentId,
    versionId: UUID,
    dueOn: LocalDate,
    createdAt: Instant,
    status: ReviewStatus,
    decidedAt: Option[Instant] = None,
    decidedBy: Option[UserId] = None,
    nextDueOn: Option[LocalDate] = None,
    newVersionId: Option[UUID] = None,
    comment: Option[String] = None
):
  def isOpen: Boolean = status == ReviewStatus.Open

/** Pure rules of the periodic review (business concept 6.2, US-07.1; ADR 0030). */
object Review:

  /** The review date of a version that becomes valid on `validFrom`: `validFrom` plus the class's interval;
    * the document's `nextReview` date can only shorten the cycle (business concept 6.2: "verkürzbar je
    * Dokument") and counts only if it lies after `validFrom` (a date copied from the previous version is
    * stale). Without interval the `nextReview` date alone; without both, no periodic review.
    */
  def dueOn(validFrom: LocalDate, nextReview: Option[LocalDate], policy: ReviewPolicy): Option[LocalDate] =
    val byInterval = policy.intervalMonths.map(m => validFrom.plusMonths(m.toLong))
    val byDocument = nextReview.filter(_.isAfter(validFrom))
    (byInterval.toList ++ byDocument.toList).minOption

  /** The day the review task is created: `leadDays` before the review date. */
  def opensOn(dueOn: LocalDate, policy: ReviewPolicy): LocalDate = dueOn.minusDays(policy.leadDays.toLong)

  /** Whether the review task of a version with review date `dueOn` is due on `today`. */
  def taskDue(dueOn: LocalDate, policy: ReviewPolicy, today: LocalDate): Boolean =
    !opensOn(dueOn, policy).isAfter(today)

  /** The new review date on recertification: the chosen date, or `today` plus the class's interval. It must
    * lie after `today`.
    */
  def recertifiedDueOn(
      chosen: Option[LocalDate],
      policy: ReviewPolicy,
      today: LocalDate
  ): Either[String, LocalDate] =
    chosen.orElse(policy.intervalMonths.map(m => today.plusMonths(m.toLong))) match
      case None =>
        Left("the class has no review interval; choose the next review date")
      case Some(date) if !date.isAfter(today) =>
        Left(s"the next review date must lie after today ($today), not $date")
      case Some(date) => Right(date)
