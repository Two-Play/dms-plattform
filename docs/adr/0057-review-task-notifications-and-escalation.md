# 0057: Review task notifications: the review date as a deadline in the check of ADR 0053, times from the policy in force, escalation beyond the owner

| Field | Value |
|---|---|
| Status | Proposed |
| Date | 2026-11-04 |
| Decided by | Philippe |

## Context

Review tasks (US-07.1, ADR 0030) appear in the inbox, but nobody is told: ADR 0030 lists "notify the owner when a task is created and escalate overdue reviews" as must-do and the MVP acceptance names it as release blocker 1 (DMS-104; this change is DMS-141). Asked for: an in-app and email notification to the responsible person when a review task is created and before it is due; escalation when overdue, with recipients and intervals following the workflow deadlines (ADR 0053) and without a parallel mechanism; each message exactly once, also across restarts and several instances; German and English texts; delivery through the notifications module (ADR 0052).

Given:

- `document.review-task-created` is already written to the outbox in the transaction that creates the task, and only if the row was inserted (ADR 0030).
- ADR 0053 has the machinery: `DeadlineStage`, `Deadlines` (working days, stage at a time), `DeadlinePolicy`, the job `workflow.deadline-check` with an idempotency key per tenant and interval, one notice row per task and stage in the transaction of the outbox event and the audit entry. It lives in `workflow-core`.
- Module rule (ADR 0001): `documents-core` must not depend on `workflow-core` or the reverse; only `app` sees both.
- A workflow task gets its deadline from "N working days after creation"; a review task has a fixed date, the review date, known long before the task exists.
- Review decisions are the owner's alone, no deputies (ADR 0030); owner deputies are DMS-88 (ADR 0028).
- The default policy escalates to `Owner`, which for a workflow task is someone new, but for a review task is the person who was late.
- `dms.document_review` has a guard and column grants; open tasks exist already and have no deadline data.

## Decision

1. **Created.** `PlatformNotificationEvents` maps the existing `document.review-task-created` to the kind `review.task-created` for the document's owner, category `workflow`. No new event: the task row's unique key already makes it one event per task.
2. **One check, a second source.** `DeadlineJobs` gets the port `DeadlineSource` (`dueNotices`, `notice`); workflow tasks are one source, `ReviewDeadlines` (in `app`, the only place that sees `documents-core` and `workflow-core`) another. Same job, same interval (`dms.workflow.deadlineCheckSeconds`), same stages, same "latest stage reached, skipped stages are not caught up", one transaction per notice. A failing source does not stop the others; the job then fails and is retried.
3. **A review task is due at the end of its review date.** `Deadlines.onDate(policy, dueOn, createdAt, zone)` shares its rules with `Deadlines.deadline`: reminder `reminderWorkingDays` before (dropped if not after the task's creation: the notice of the new task is the reminder then), escalation `escalationWorkingDays` after. `dueWorkingDays` plays no part, so `0` ("no deadlines for workflow tasks") does not silence reviews; `reminderWorkingDays` and `escalationWorkingDays` of 0 switch those stages off. A version is ReviewDue from 00:00 of the review date and "overdue" as a task from the following day, like a workflow task that is due at the end of its day.
4. **Policy: the tenant's default, else the installation's.** Workflow and step settings do not apply: a review belongs to no workflow.
5. **Times are computed, not stored.** They follow from the review date, the task's creation and the policy in force at each check. Unlike ADR 0053 ("later changes apply to new tasks only"), a policy change reaches open review tasks. A stage that would come after a later one already published (the policy changed) is not sent.
6. **Once.** New table `dms.document_review_deadline_notice` (migration V39, primary key task + stage, RLS, `dms_app` select and insert only). `ReviewDeadlines.notice` locks the task row (the lock a decision takes, too), re-checks that the task is open, its version valid and the stage still due, inserts the notice and writes audit entry and outbox event in that transaction, as `system:review`. Events and audit actions: `document.review-task-due-soon`, `document.review-task-overdue`, `document.review-task-escalated` (event type = audit action, ADR 0030). Delivery is the unchanged path of ADR 0052, in the category `deadline`; kinds `review.task-due-soon`, `review.task-overdue`, `review.task-escalated`.
7. **Recipients by the stages of ADR 0053.** Reminder: the owner. Overdue: the owner and the owner's active deputies. Escalated: also the targets of `escalateTo`. A deputy here is someone with an active deputyship for the role `Owner` and the document's class who still holds the role and may see the version (`Rules.ownerDeputies`). They are told, nothing more: ADR 0030 stands, and the text says that only the owner can decide.
8. **Escalation reaches someone other than the owner.** If `escalateTo` without the owner names nobody (the default `Owner`), it goes to the compliance officers whose scope covers the document, and without any to the tenant administrators. Business concept 6.2 escalates overdue reviews in the compliance cockpit, so compliance is the natural next person.

## Alternatives considered

| Criterion (where the check lives) | Chosen: source of `DeadlineJobs`, adapter in `app` | Stages inside `ReviewJobs` in `documents-core` | Move `Deadlines` to `workflow-api`, new job in `documents-core` |
|---|---|---|---|
| "No parallel mechanism" | One job, one set of rules | Second job and copied stage logic | Shared rules, second job and schedule |
| Module rule | Kept: `DeadlineSource` in `workflow-core`, adapter in `app` | Needs `workflow-core`, or a copy | Kept, but `documents-core` gains a dependency on `workflow-api` |
| Escalation targets, deputies | Reused from `ApprovalWorkflow` | Ports to re-create | Ports to re-create |
| Interval | 15 minutes, as for workflow tasks | 1 hour | Own setting |
| Change to shared code | `DeadlineJobs.check` takes a list | None | Moves a file every importer sees |

| Criterion (the times of a review task) | Chosen: computed from the policy in force | Stored on `dms.document_review` when created (as ADR 0053) | Own class setting for reviews |
|---|---|---|---|
| Open tasks from before this change | Covered | No times unless a backfill computes them; the policy of the installation is not in the database | Covered |
| Touches the guarded table | No | Columns, written by `documents-core`, which cannot compute them | No |
| Policy change | Reaches open tasks, which live for weeks, as a tenant would expect | New tasks only | – |
| Query | Open tasks up to a horizon, filtered in code | Indexed predicate | As chosen |
| Consistency with ADR 0053 | Differs in this one point | Same | New settings, UI and API; intervals no longer "follow the pattern" |

| Criterion (escalation with the default `Owner`) | Chosen: compliance officers, else tenant administrators | Keep to `escalateTo`: only the owner again | Change the installation's default to `ComplianceOfficer` |
|---|---|---|---|
| Someone other than the late person hears | Yes | No: "escalation" would be a third reminder | Yes |
| Workflow tasks | Unchanged | Unchanged | Changed for everyone |
| Surprise | A configured `Owner` is widened for reviews only | None | Existing tenants change |

| Criterion (deputies of the owner) | Chosen: told when overdue and escalated, no rights | Nobody, as owner lanes are not forwarded | Deputies may decide |
|---|---|---|---|
| Owner on leave | Someone hears and can act on it | Only the escalation target | Solved |
| ADR 0030, ADR 0028 | Unchanged | Unchanged | Changes who decides; that is DMS-88 |

## Consequences

### Positive

- The owner hears of a review when it comes up, shortly before the date and when it is late; others hear when nobody reacts. The promise of the periodic review no longer depends on someone opening the inbox.
- Every stage is recorded in the audit trail and published once: several instances and restarts cannot repeat one, a crash before the commit leaves nothing, and a decision excludes a notice through the row lock.
- Workflow and review deadlines are configured in one place, with one job and one set of rules.
- `DeadlineSource` is open to further deadlines (e.g. contract notice periods, US-10.2).

### Negative / Cost

- Exactly once holds for the decision to notify, the deliveries and the in-app notification. Email stays at least once (ADR 0052): if the instance dies between the SMTP server's acceptance and the recorded delivery, the retry sends it again with the same `Message-ID`. SMTP offers nothing stronger.
- Open tasks from before this change get no "created" notice (their events were long marked delivered), only the stage they reach next. At rollout, tasks already overdue get their latest stage at once: one burst, which is what the blocker asks for, but it should be announced.
- A task created after its date (a late review date, or the check did not run) gets the created notice and the overdue or escalated notice within one check interval.
- A reminder 1 working day before (the default) is short next to a lead time of 60 days. It is the tenant's setting, shared with workflow tasks.
- `escalateTo = Owner` means something else for reviews than for workflow tasks; the admin tab does not say so.
- One notice stage per step: no repeated escalation for reviews overdue for months (as in ADR 0053).
- A document without an owner gets no created notice, reminder or overdue notice, only the escalation. The owner is required from review on, so only old data can be affected. An owner who left the company is not detected (ADR 0030 follow-up).
- Users can switch email off for `deadline`, although business concept 8.3 says deadlines and escalations cannot be unsubscribed (already so for workflow deadlines; in-app stays on).

## Follow-ups

- Must be done: announce the rollout burst of already overdue reviews to the tenants.
- Should: mandatory notifications that cannot be unsubscribed (business concept 8.3), for all deadlines.
- Should: reassign open review tasks of orphaned documents (ADR 0030, F-07.5).
- Should: escalation to the unit's management once the platform knows it (the business concept's process for the periodic review names "Eskalation an Bereichsleitung").
- Later: repeated escalation of long-overdue reviews; a hint in the admin tab what `Owner` means for reviews; owner deputies who may decide (DMS-88).
- Ruled out: withdrawing overdue versions automatically (ADR 0030).

## Reversibility

**high**: `ReviewDeadlines` is one list entry in `Background` and one adapter; the notice table can be dropped without touching tasks or decisions, and the events have no other subscriber. Storing the times later is additive. Audit entries already written stay.
