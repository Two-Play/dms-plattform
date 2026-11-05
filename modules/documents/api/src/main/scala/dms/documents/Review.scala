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
  * @param inherited
  *   the class version has no settings of its own and takes those of the nearest ancestor that has (business
  *   concept 3.1, ADR 0068); the two other values play no part then
  */
final case class ReviewPolicy(
    intervalMonths: Option[Int] = None,
    leadDays: Int = ReviewPolicy.DefaultLeadDays,
    inherited: Boolean = false
):
  def problems: List[String] =
    Option
      .when(inherited && (intervalMonths.isDefined || leadDays != ReviewPolicy.DefaultLeadDays))(
        "inherited review settings have no interval or lead time of their own"
      )
      .toList ++ intervalMonths
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
  val inherit: ReviewPolicy  = ReviewPolicy(inherited = true)

/** Outcome of a review task. `Open` until the owner decides; `Obsolete` if the version stopped being valid
  * without a decision (e.g. a new version was published) or a new interval moved the date earlier (ADR 0068).
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
    comment: Option[String] = None,
    reopens: Option[UUID] = None
):
  def isOpen: Boolean = status == ReviewStatus.Open

/** What is known about the review date of a valid version when it is computed again (ADR 0068).
  *
  * @param current
  *   the review date; `None` if the version has none yet
  * @param validFrom
  *   the day the version became valid
  * @param nextReview
  *   the `nextReview` value the version was approved with
  * @param recertifiedOn
  *   the day of the latest recertification, if any: the cycle counts from there
  * @param openTask
  *   the version has an open review task
  * @param decided
  *   a task for the current date is decided, e.g. a revision is under way
  */
final case class ReviewDateState(
    current: Option[LocalDate],
    validFrom: LocalDate,
    nextReview: Option[LocalDate] = None,
    recertifiedOn: Option[LocalDate] = None,
    openTask: Boolean = false,
    decided: Boolean = false
)

/** Why a review date stays although the class's interval says otherwise (ADR 0068). */
enum ReviewDateKept:
  /** The date has been reached and nobody decided: only a decision ends an overdue review. */
  case Overdue

  /** A task for this date is decided; moving the date would detach the decision from it. */
  case Decided

  /** The owner fixed the date by recertifying, and the class's cycle does not ask for an earlier one. */
  case Recertified

  /** The owner has been asked already; a longer cycle takes effect with the decision. */
  case OpenTask

  /** The class has no interval any more: nothing says what the date should be, so the review stays. */
  case NoInterval

/** The result of computing a review date again. */
enum ReviewDateChange:
  case Unchanged

  /** @param to
    *   the new date
    * @param computed
    *   the date by the rules; `to` is later if that day has passed
    */
  case Moved(to: LocalDate, computed: LocalDate)
  case Kept(reason: ReviewDateKept, computed: Option[LocalDate])

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

  /** The review date of a version under `policy`, for a version that has none yet and when a class's interval
    * changed (ADR 0068). Same cycle as on publication, counted from the latest recertification if there is
    * one. A reached date never moves, and neither does a date a task was decided for; a date the owner chose
    * or was asked about only moves earlier. A date is never set before `today`: nobody could have acted on
    * it, and a date of today is reached at once.
    */
  def recalculated(state: ReviewDateState, policy: ReviewPolicy, today: LocalDate): ReviewDateChange =
    import ReviewDateChange.*
    def moved(date: LocalDate) = Moved(if date.isBefore(today) then today else date, date)
    val computed               = policy.intervalMonths.flatMap(months =>
      state.recertifiedOn.fold(dueOn(state.validFrom, state.nextReview, policy))(day =>
        Some(day.plusMonths(months.toLong))
      )
    )
    (state.current, computed) match
      case (None, date)                                   => date.fold[ReviewDateChange](Unchanged)(moved)
      case (Some(_), None)                                => Kept(ReviewDateKept.NoInterval, None)
      case (Some(current), Some(date)) if current == date => Unchanged
      case (Some(current), _) if !current.isAfter(today)  => Kept(ReviewDateKept.Overdue, computed)
      case _ if state.decided                             => Kept(ReviewDateKept.Decided, computed)
      case (Some(current), Some(date)) if date.isAfter(current) && state.recertifiedOn.isDefined =>
        Kept(ReviewDateKept.Recertified, computed)
      case (Some(current), Some(date)) if date.isAfter(current) && state.openTask =>
        Kept(ReviewDateKept.OpenTask, computed)
      case (_, Some(date)) => moved(date)
