package dms.workflow.core

import dms.workflow.*

import java.time.{DayOfWeek, Instant, LocalDate, LocalTime, ZoneId}

/** When a task is due, reminded and escalated (DMS-87, ADR 0053). */
final case class TaskDeadline(dueAt: Instant, remindAt: Option[Instant], escalateAt: Option[Instant])

/** The notice a task with a deadline gets at a point in time, in the order they come. */
enum DeadlineStage(val key: String, val eventType: String, val auditAction: String):
  /** Before the deadline: the participants are reminded. */
  case Reminder extends DeadlineStage("reminder", "workflow.task-due-soon", "workflow.task-reminded")

  /** The deadline passed: participants and their active deputies are told. */
  case Overdue extends DeadlineStage("overdue", "workflow.task-overdue", "workflow.task-overdue")

  /** The escalation time passed: the escalation targets (owner, compliance officers, administrators) too. */
  case Escalated extends DeadlineStage("escalated", "workflow.task-escalated", "workflow.task-escalated")

/** Pure deadline rules: working days, the policy that applies, the stage at a point in time. */
object Deadlines:

  /** Monday to Friday. Public holidays are not known (ADR 0053). */
  def isWorkingDay(d: LocalDate): Boolean =
    d.getDayOfWeek != DayOfWeek.SATURDAY && d.getDayOfWeek != DayOfWeek.SUNDAY

  /** `at` moved by `days` working days (negative: back), same local time of day in `zone`. A day that is not
    * a working day itself is not counted.
    */
  def shift(at: Instant, days: Int, zone: ZoneId): Instant =
    val local = at.atZone(zone)
    val step  = if days >= 0 then 1L else -1L
    var date  = local.toLocalDate
    var left  = math.abs(days)
    while left > 0 do
      date = date.plusDays(step)
      if isWorkingDay(date) then left -= 1
    date.atTime(local.toLocalTime).atZone(zone).toInstant

  /** The last moment of the day of `at` in `zone`, to the second (23:59:59). */
  def endOfDay(at: Instant, zone: ZoneId): Instant =
    at.atZone(zone).toLocalDate.atTime(LocalTime.of(23, 59, 59)).atZone(zone).toInstant

  /** The policy for a task of `step`: the step's due days override the workflow's or else the tenant's or
    * else the installation's due days; reminder and escalation come from the workflow, the tenant or the
    * installation, in this order.
    */
  def policyFor(
      step: Step,
      definition: WorkflowDefinition,
      tenant: Option[DeadlinePolicy],
      installation: DeadlinePolicy
  ): DeadlinePolicy =
    val base = definition.deadlines.orElse(tenant).getOrElse(installation)
    step.dueWorkingDays.fold(base)(days => base.copy(dueWorkingDays = days))

  /** The deadline of a task created at `createdAt`: the end of the `dueWorkingDays`-th working day after the
    * day of creation; reminder that many working days before (only if still after creation), escalation that
    * many after. `None` without deadline.
    */
  def deadline(policy: DeadlinePolicy, createdAt: Instant, zone: ZoneId): Option[TaskDeadline] =
    Option.when(policy.hasDeadlines) {
      val due    = shift(endOfDay(createdAt, zone), policy.dueWorkingDays, zone)
      val remind = Option
        .when(policy.reminderWorkingDays > 0)(shift(due, -policy.reminderWorkingDays, zone))
        .filter(_.isAfter(createdAt))
      val escalate =
        Option.when(policy.escalationWorkingDays > 0)(shift(due, policy.escalationWorkingDays, zone))
      TaskDeadline(due, remind, escalate)
    }

  /** The stage of a task's deadline at `now`, if any: the latest one reached. */
  def stage(deadline: TaskDeadline, now: Instant): Option[DeadlineStage] =
    if deadline.escalateAt.exists(!now.isBefore(_)) then Some(DeadlineStage.Escalated)
    else if !now.isBefore(deadline.dueAt) then Some(DeadlineStage.Overdue)
    else if deadline.remindAt.exists(!now.isBefore(_)) then Some(DeadlineStage.Reminder)
    else None

  /** The due day (local date) of a deadline, for texts. */
  def dueOn(dueAt: Instant, zone: ZoneId): LocalDate = dueAt.atZone(zone).toLocalDate
