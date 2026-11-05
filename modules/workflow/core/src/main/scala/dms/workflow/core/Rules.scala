package dms.workflow.core

import dms.documents.{DocumentStatus, LifecycleEvent}
import dms.kernel.UserId
import dms.workflow.*

import java.time.{Duration, Instant}
import java.util.UUID

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

  // ---- deputyships (DMS-49, ADR 0028) -------------------------------------------------------------------

  /** Business roles of workflow lanes a deputyship can cover. */
  val DeputyRoles: List[String] = List("Author", "Owner", "Reviewer", "Approver")

  /** The longest period of a deputyship: it stands in for an absence, it is not a permanent delegation. */
  val MaxDeputyship: Duration = Duration.ofDays(366)

  /** Checks a new deputyship (same authority, time limit) and returns the roles it covers: the requested
    * ones, or all workflow roles the principal holds.
    *
    * Same authority means the same role: the deputy must hold each covered role themselves (in any scope,
    * active when the period starts), so only an approver stands in for an approver. During the period the
    * deputy acts within the principal's scope on the principal's tasks. The deputy's own role is checked
    * again whenever they act ([[taskDeputies]]).
    */
  def checkDeputyship(
      request: NewDeputyship,
      principalRoles: List[HeldRole],
      deputyRoles: List[HeldRole],
      now: Instant
  ): Either[List[String], List[String]] =
    val from      = request.validFrom.getOrElse(now)
    val start     = if from.isAfter(now) then from else now
    val requested = request.roles.map(_.trim).filter(_.nonEmpty).distinct
    val principal = principalRoles.filter(_.isActiveAt(start)).map(_.role).toSet
    val deputy    = deputyRoles.filter(_.isActiveAt(start)).map(_.role).toSet
    val roles     = if requested.nonEmpty then requested else DeputyRoles.filter(principal.contains)
    val period    = List(
      Option.when(request.deputy.value.trim.isEmpty)("a deputy is required"),
      Option.when(request.deputy == request.principal)("a person cannot be their own deputy"),
      Option.when(!request.validUntil.isAfter(from))("the period must end after it starts"),
      Option.when(!request.validUntil.isAfter(now))("the period must end in the future"),
      Option.when(Duration.between(from, request.validUntil).compareTo(MaxDeputyship) > 0)(
        s"a deputyship lasts at most ${MaxDeputyship.toDays} days"
      ),
      Option.when(request.documentClass.exists(_.trim.isEmpty))("the document class must not be blank"),
      Option.when(roles.isEmpty)(
        s"${request.principal.value} holds no role taking part in workflows (${DeputyRoles.mkString(", ")})"
      )
    ).flatten
    val authority = roles.flatMap { role =>
      if !DeputyRoles.contains(role) then
        Some(s"role $role cannot be delegated; one of ${DeputyRoles.mkString(", ")}")
      else if !principal.contains(role) then Some(s"${request.principal.value} does not hold role $role")
      else if !deputy.contains(role) then
        Some(s"deputy ${request.deputy.value} does not hold role $role (same authority required)")
      else None
    }
    val problems = period ++ authority
    if problems.isEmpty then Right(roles) else Left(problems.distinct)

  /** Deputies who may complete an open task now (DMS-49): deputies of its candidates whose deputyship is
    * active and covers the lane's role and the document's class, and who still hold that role themselves
    * (`deputyRoles`: their active roles, any scope; same authority at use time). Neither the deputy nor the
    * principal may be the author, whatever the four-eyes setting: nobody approves their own version, and
    * nobody stands in for the author. Candidates act for themselves. Lanes of the document owner are not
    * forwarded (owner deputies are DMS-88). One entry per deputy.
    */
  def taskDeputies(
      role: String,
      candidates: List[UserId],
      author: UserId,
      classPath: List[String],
      deputyships: List[Deputyship],
      deputyRoles: Map[UserId, Set[String]],
      now: Instant
  ): List[TaskDeputy] =
    if role == WorkflowDefinition.DocumentOwner then Nil
    else
      deputyships
        .filter(d =>
          d.isActiveAt(now) &&
            candidates.contains(d.principal) &&
            d.principal != author &&
            d.deputy != author &&
            !candidates.contains(d.deputy) &&
            d.roles.contains(role) &&
            d.documentClass.forall(classPath.contains) &&
            deputyRoles.getOrElse(d.deputy, Set.empty).contains(role)
        )
        .sortBy(d => (d.deputy.value, d.principal.value))
        .map(d => TaskDeputy(d.deputy, d.principal))
        .distinctBy(_.deputy)

  /** The deputies to inform about a deadline of the document owner (DMS-141, ADR 0057): like [[taskDeputies]]
    * an active deputyship for `Owner` and the document's class, and the deputy still holds the role. They are
    * only told: owner decisions are not forwarded (ADR 0028, 0030). One entry per deputy.
    */
  def ownerDeputies(
      owner: UserId,
      classPath: List[String],
      deputyships: List[Deputyship],
      deputyRoles: Map[UserId, Set[String]],
      now: Instant
  ): List[TaskDeputy] =
    deputiesInRole(owner, OwnerRole, classPath, deputyships, deputyRoles, now)

  /** Like [[ownerDeputies]] for the role `principal` is responsible in: `Owner`, or `Author` for the author
    * of a document without owner (DMS-146, ADR 0064). Being told decides nothing, so the rule of ADR 0028
    * that nobody stands in for an author's vote is not touched.
    */
  def deputiesInRole(
      principal: UserId,
      role: String,
      classPath: List[String],
      deputyships: List[Deputyship],
      deputyRoles: Map[UserId, Set[String]],
      now: Instant
  ): List[TaskDeputy] =
    deputyships
      .filter(d =>
        d.isActiveAt(now) &&
          d.principal == principal &&
          d.deputy != principal &&
          d.roles.contains(role) &&
          d.documentClass.forall(classPath.contains) &&
          deputyRoles.getOrElse(d.deputy, Set.empty).contains(role)
      )
      .sortBy(_.deputy.value)
      .map(d => TaskDeputy(d.deputy, d.principal))
      .distinctBy(_.deputy)

  /** Whom `deputy` stood in for with a valid approving vote in an approval step of `version`, with the role
    * of the lane, latest vote first. Reviews, rejections, invalidated votes, votes of other versions and
    * votes of the deputy's own do not count.
    */
  def deputyApprovals(cases: List[CaseDetails], version: UUID, deputy: UserId): List[DeputyApproval] =
    cases
      .filter(_.workflowCase.versionId == version)
      .flatMap { c =>
        val approvalLanes = c.tasks.filter(_.kind == StepKind.Approval).map(t => t.id -> t.role).toMap
        c.votes.flatMap(v =>
          for
            principal <- v.actingFor.filter(_ => v.voter == deputy)
            role      <- approvalLanes.get(v.taskId)
            if v.decision.approves && v.invalidatedAt.isEmpty
          yield v.votedAt -> DeputyApproval(principal, role)
        )
      }
      .sortBy(_._1)
      .reverse
      .map(_._2)
      .distinct

  /** Whether `deputy` may still act for an approval they gave as deputy (DMS-145, ADR 0070): the rules of
    * [[taskDeputies]] at the moment they act. The deputyship is active and covers the lane's role and the
    * document's class, and the deputy still holds that role. Neither of them is the author.
    */
  def standsIn(
      approval: DeputyApproval,
      deputy: UserId,
      author: UserId,
      classPath: List[String],
      deputyships: List[Deputyship],
      deputyRoles: Map[UserId, Set[String]],
      now: Instant
  ): Boolean =
    approval.principal != author && deputy != author &&
      deputiesInRole(approval.principal, approval.role, classPath, deputyships, deputyRoles, now)
        .exists(_.deputy == deputy)

  val OwnerRole  = "Owner"
  val AuthorRole = "Author"

  /** BPMN element id of the user task of a lane. */
  def taskKey(step: String, lane: String): String = s"task.$step.$lane"

  def parseTaskKey(key: String): Option[(String, String)] = key.split('.').toList match
    case "task" :: step :: lane :: Nil => Some((step, lane))
    case _                             => None
