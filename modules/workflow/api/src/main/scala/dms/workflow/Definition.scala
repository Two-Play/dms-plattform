package dms.workflow

/** Phase of a step: reviews run while the version is `InReview`, approvals while it is `InApproval`. Votes in
  * approval steps need a recent sign-in (step-up, business concept 6.1).
  */
enum StepKind:
  case Review, Approval

/** When a step runs. */
enum StepCondition:
  case Always

  /** Only if the version needs the extra review step (business concept 4.1: deviations from locked standard
    * text), e.g. by the legal department. Read through the `ExtraReviewCheck` port of `workflow-core`.
    */
  case ExtraReviewRequired

/** One parallel branch of a step: a task for the holders of `role` in the scope of the document
  * (organizational unit, document class). Any one of them completes it.
  */
final case class Lane(key: String, role: String)

/** A step of the workflow. Steps run one after the other; the lanes of a step run in parallel and all must
  * approve.
  *
  * @param dueWorkingDays
  *   working days a task of this step has (DMS-87); overrides the workflow's or the tenant's deadline, 0: no
  *   deadline for this step
  */
final case class Step(
    key: String,
    name: String,
    kind: StepKind,
    lanes: List[Lane],
    when: StepCondition = StepCondition.Always,
    dueWorkingDays: Option[Int] = None
)

/** Deadlines of review and approval tasks (DMS-87, ADR 0053), counted in working days (Monday to Friday in
  * the tenant's time zone; public holidays are not known).
  *
  * @param dueWorkingDays
  *   a task is due at the end of this many working days after the day it was created; 0: no deadlines
  * @param reminderWorkingDays
  *   the participants are reminded this many working days before the deadline; 0: no reminder
  * @param escalationWorkingDays
  *   the task is escalated to [[escalateTo]] this many working days after the deadline; 0: no escalation (at
  *   the deadline itself the participants and their deputies are told that it is overdue)
  * @param escalateTo
  *   who is told on escalation besides the participants: `Owner` (the document's owner), `ComplianceOfficer`
  *   (compliance officers whose scope covers the document), `TenantAdmin`
  */
final case class DeadlinePolicy(
    dueWorkingDays: Int,
    reminderWorkingDays: Int = 1,
    escalationWorkingDays: Int = 2,
    escalateTo: List[String] = List(DeadlinePolicy.Owner)
):
  def hasDeadlines: Boolean = dueWorkingDays > 0

object DeadlinePolicy:
  val Owner             = "Owner"
  val ComplianceOfficer = "ComplianceOfficer"
  val TenantAdmin       = "TenantAdmin"

  /** Who can be escalated to. */
  val EscalationTargets: List[String] = List(Owner, ComplianceOfficer, TenantAdmin)

  /** Upper bounds of the day counts. */
  val MaxDays = 60

  /** The installation's default when neither the workflow nor the tenant sets one. */
  val default: DeadlinePolicy = DeadlinePolicy(5)

  def problems(p: DeadlinePolicy): List[String] =
    List(
      Option.unless(p.dueWorkingDays >= 0 && p.dueWorkingDays <= MaxDays)(
        s"dueWorkingDays must be between 0 and $MaxDays"
      ),
      Option.unless(p.reminderWorkingDays >= 0 && p.reminderWorkingDays <= MaxDays)(
        s"reminderWorkingDays must be between 0 and $MaxDays"
      ),
      Option.when(p.dueWorkingDays > 0 && p.reminderWorkingDays >= p.dueWorkingDays)(
        "the reminder must come before the deadline (reminderWorkingDays < dueWorkingDays)"
      ),
      Option.unless(p.escalationWorkingDays >= 0 && p.escalationWorkingDays <= MaxDays)(
        s"escalationWorkingDays must be between 0 and $MaxDays"
      )
    ).flatten ++ p.escalateTo
      .filterNot(EscalationTargets.contains)
      .map(t => s"unknown escalation target $t; allowed: ${EscalationTargets.mkString(", ")}")

/** The review and approval workflow of a document class (business concept 6.1): configured, not programmed.
  *
  * @param steps
  *   review steps first, then approval steps; at least one approval step
  * @param resetTo
  *   the step a resubmitted version restarts at when its content changed after a vote (DMS-41); default: the
  *   first step. Steps before it are not repeated.
  * @param deadlines
  *   deadlines, reminder and escalation of its tasks (DMS-87); default: the tenant's, else the installation's
  */
final case class WorkflowDefinition(
    steps: List[Step],
    resetTo: Option[String] = None,
    deadlines: Option[DeadlinePolicy] = None
):
  def step(key: String): Option[Step] = steps.find(_.key == key)

  def firstStep: String = steps.head.key

  def resetStep: String = resetTo.getOrElse(firstStep)

object WorkflowDefinition:
  private val Key = "[a-z][a-z0-9-]{0,40}".r

  /** Pseudo role of a lane whose participant is the owner of the document (the `owner` field), not the
    * holders of a business role. Used by the editorial workflow path.
    */
  val DocumentOwner = "DocumentOwner"

  /** Who confirms an editorial version when its owner cannot: the owner is its author (segregation of duties,
    * business concept 2.4) or the document has no owner.
    */
  val OwnerDeputyRole = "Approver"

  /** The shortened path of editorial (minor) versions (business concept 5.2, US-05.2, DMS-46): no review, one
    * approval step in which the owner confirms the editorial classification and approves the version. Used
    * for minor versions of every class instead of the class's workflow; the four-eyes rule and step-up apply
    * as for any approval.
    */
  val editorial: WorkflowDefinition = WorkflowDefinition(
    List(
      Step(
        "editorial-confirmation",
        "Owner confirmation of the editorial change",
        StepKind.Approval,
        List(Lane("owner", DocumentOwner))
      )
    )
  )

  /** Used for classes without a workflow of their own: one review by a reviewer, one approval by an approver.
    */
  val default: WorkflowDefinition = WorkflowDefinition(
    List(
      Step("review", "Review", StepKind.Review, List(Lane("reviewer", "Reviewer"))),
      Step("approval", "Approval", StepKind.Approval, List(Lane("approver", "Approver")))
    )
  )

  /** Structural problems of a definition; empty if it is valid. Role names are checked by the caller. */
  def problems(d: WorkflowDefinition): List[String] =
    val keys = d.steps.map(_.key)
    List(
      Option.when(d.steps.isEmpty)("a workflow needs at least one step"),
      Option.when(d.steps.nonEmpty && !d.steps.exists(_.kind == StepKind.Approval))(
        "a workflow needs at least one approval step"
      ),
      Option.when(
        d.steps.dropWhile(_.kind == StepKind.Review).exists(_.kind == StepKind.Review)
      )("review steps must come before the approval steps"),
      Option.when(keys.distinct.size != keys.size)("step keys must be unique"),
      d.resetTo.filterNot(keys.contains).map(k => s"resetTo names an unknown step: $k")
    ).flatten ++ d.deadlines.toList.flatMap(DeadlinePolicy.problems) ++
      d.steps.flatMap { s =>
        val lanes = s.lanes.map(_.key)
        List(
          Option.unless(Key.matches(s.key))(s"invalid step key '${s.key}': lowercase letters, digits, '-'"),
          Option.when(s.name.trim.isEmpty)(s"step ${s.key} needs a name"),
          Option.when(s.lanes.isEmpty)(s"step ${s.key} needs at least one lane"),
          Option.when(lanes.distinct.size != lanes.size)(s"lane keys of step ${s.key} must be unique"),
          Option.when(s.kind == StepKind.Approval && s.when != StepCondition.Always)(
            s"approval step ${s.key} cannot be conditional"
          ),
          s.dueWorkingDays
            .filterNot(d => d >= 0 && d <= DeadlinePolicy.MaxDays)
            .map(_ => s"dueWorkingDays of step ${s.key} must be between 0 and ${DeadlinePolicy.MaxDays}")
        ).flatten ++ s.lanes.flatMap(l =>
          List(
            Option.unless(Key.matches(l.key))(s"invalid lane key '${l.key}' in step ${s.key}"),
            Option.when(l.role.trim.isEmpty)(s"lane ${l.key} of step ${s.key} needs a role")
          ).flatten
        )
      }

/** Review vote (business concept 6.1). A reason is required for everything except a plain approval. */
enum VoteDecision:
  case Approve, ApproveWithConditions, RequestChanges, Reject

  def needsReason: Boolean = this != Approve

  /** Approving votes complete the task; the others return the version to its author. */
  def approves: Boolean = this == Approve || this == ApproveWithConditions
