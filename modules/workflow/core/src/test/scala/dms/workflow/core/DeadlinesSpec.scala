package dms.workflow.core

import dms.workflow.*
import zio.test.*

import java.time.{Instant, LocalDateTime, ZoneId}

/** The pure deadline rules (DMS-87, ADR 0053): working days across weekends and time zones, the policy that
  * applies to a step, the deadline of a new task and the stage reached at a point in time.
  */
object DeadlinesSpec extends ZIOSpecDefault:

  private val berlin = ZoneId.of("Europe/Berlin")

  private def at(local: String): Instant = LocalDateTime.parse(local).atZone(berlin).toInstant

  private val review = Step("review", "Review", StepKind.Review, List(Lane("r", "Reviewer")))

  def spec = suite("Deadlines")(
    test("working days skip Saturday and Sunday, forward and back, keeping the local time") {
      assertTrue(
        // Thursday + 1 = Friday; Friday + 1 = Monday; Friday + 3 = Wednesday
        Deadlines.shift(at("2026-10-29T23:59:59"), 1, berlin) == at("2026-10-30T23:59:59"),
        Deadlines.shift(at("2026-10-30T23:59:59"), 1, berlin) == at("2026-11-02T23:59:59"),
        Deadlines.shift(at("2026-10-30T10:00:00"), 3, berlin) == at("2026-11-04T10:00:00"),
        // Monday - 1 = Friday; a Saturday start counts from Monday
        Deadlines.shift(at("2026-11-02T23:59:59"), -1, berlin) == at("2026-10-30T23:59:59"),
        Deadlines.shift(at("2026-10-31T12:00:00"), 1, berlin) == at("2026-11-02T12:00:00"),
        Deadlines.shift(at("2026-10-29T08:00:00"), 0, berlin) == at("2026-10-29T08:00:00"),
        // across the change from summer to winter time (25 October 2026) the local time stays
        Deadlines.shift(at("2026-10-23T23:59:59"), 1, berlin) == at("2026-10-26T23:59:59"),
        !Deadlines.isWorkingDay(java.time.LocalDate.parse("2026-10-31")),
        Deadlines.isWorkingDay(java.time.LocalDate.parse("2026-10-30"))
      )
    },
    test("a task created on Thursday with 3 working days is due Tuesday night, reminded Monday night") {
      val created  = at("2026-10-29T14:30:00")
      val deadline = Deadlines.deadline(DeadlinePolicy(3, 1, 2), created, berlin)
      assertTrue(
        deadline.map(_.dueAt).contains(at("2026-11-03T23:59:59")),
        deadline.flatMap(_.remindAt).contains(at("2026-11-02T23:59:59")),
        deadline.flatMap(_.escalateAt).contains(at("2026-11-05T23:59:59")),
        Deadlines.dueOn(deadline.get.dueAt, berlin) == java.time.LocalDate.parse("2026-11-03")
      )
    },
    test("no deadline with 0 days; no reminder before creation; no escalation with 0 days") {
      val created = at("2026-10-29T14:30:00")
      assertTrue(
        Deadlines.deadline(DeadlinePolicy(0), created, berlin).isEmpty,
        // 1 working day with a reminder 1 day before: the reminder would be at creation day's end → kept only
        // if after creation
        Deadlines
          .deadline(DeadlinePolicy(1, 1, 0), created, berlin)
          .flatMap(_.remindAt)
          .contains(at("2026-10-29T23:59:59")),
        Deadlines.deadline(DeadlinePolicy(1, 1, 0), created, berlin).flatMap(_.escalateAt).isEmpty,
        Deadlines.deadline(DeadlinePolicy(2, 0, 1), created, berlin).flatMap(_.remindAt).isEmpty
      )
    },
    test("the step's days override the workflow's, which override the tenant's and the installation's") {
      val workflow     = WorkflowDefinition(List(review), deadlines = Some(DeadlinePolicy(10, 2, 3, Nil)))
      val plain        = WorkflowDefinition(List(review))
      val tenant       = Some(DeadlinePolicy(4, 1, 1, List("TenantAdmin")))
      val installation = DeadlinePolicy.default
      assertTrue(
        Deadlines.policyFor(review, workflow, tenant, installation) == DeadlinePolicy(10, 2, 3, Nil),
        Deadlines.policyFor(review.copy(dueWorkingDays = Some(1)), workflow, tenant, installation) ==
          DeadlinePolicy(1, 2, 3, Nil),
        Deadlines.policyFor(review, plain, tenant, installation) == DeadlinePolicy(
          4,
          1,
          1,
          List("TenantAdmin")
        ),
        Deadlines.policyFor(review, plain, None, installation) == installation,
        Deadlines
          .policyFor(review.copy(dueWorkingDays = Some(0)), plain, None, installation)
          .dueWorkingDays == 0
      )
    },
    test("the stage is the latest one reached") {
      val d = TaskDeadline(
        at("2026-11-03T23:59:59"),
        Some(at("2026-11-02T23:59:59")),
        Some(at("2026-11-05T23:59:59"))
      )
      assertTrue(
        Deadlines.stage(d, at("2026-11-02T12:00:00")).isEmpty,
        Deadlines.stage(d, at("2026-11-03T08:00:00")).contains(DeadlineStage.Reminder),
        Deadlines.stage(d, at("2026-11-04T00:00:00")).contains(DeadlineStage.Overdue),
        Deadlines.stage(d, at("2026-11-06T00:00:00")).contains(DeadlineStage.Escalated),
        Deadlines
          .stage(d.copy(remindAt = None, escalateAt = None), at("2026-11-30T00:00:00"))
          .contains(DeadlineStage.Overdue)
      )
    },
    test("policies are validated") {
      assertTrue(
        DeadlinePolicy.problems(DeadlinePolicy.default).isEmpty,
        DeadlinePolicy.problems(DeadlinePolicy(3, 3, 1)).exists(_.contains("before the deadline")),
        DeadlinePolicy.problems(DeadlinePolicy(-1)).nonEmpty,
        DeadlinePolicy.problems(DeadlinePolicy(61)).nonEmpty,
        DeadlinePolicy
          .problems(DeadlinePolicy(5, 1, 2, List("Boss")))
          .exists(_.contains("unknown escalation target")),
        WorkflowDefinition
          .problems(
            WorkflowDefinition(
              List(
                review.copy(dueWorkingDays = Some(-2)),
                Step("approval", "Approval", StepKind.Approval, List(Lane("a", "Approver")))
              )
            )
          )
          .exists(_.contains("dueWorkingDays of step review"))
      )
    }
  )
