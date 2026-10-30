# 0053: Workflow deadlines in working days with reminder and escalation from the job queue; engine side effects after the commit

| Field | Value |
|---|---|
| Status | Proposed |
| Date | 2026-10-30 |
| Decided by | Philippe |

## Context

DMS-87 (S-04.a) asks for three things that ADR 0024 left as follow-ups:

1. **Deadlines** for review and approval tasks, configured per document class / workflow with a tenant default ("due in N working days"), shown in the task inbox and on the document page (the inbox already sorts and marks overdue tasks once the API sends `dueAt`).
2. **Reminder and escalation**: remind the participants before the deadline; after it notify the participants, then deputies, owner or compliance as configured; record everything in the audit trail; deliver through the notifications of DMS-130 (ADR 0052).
3. **Commit hook for Flowable**: ADR 0024 notes as negative consequence "Flowable listeners fire at the end of each engine call, before the outer commit; acceptable only while the async executor is off" and as follow-up "enable the async executor; move listener firing to a commit hook in `Db`". Engine side effects must only happen after the database transaction committed.

Given: Flowable only routes, our tables hold the business truth (ADR 0024); periodic per-tenant work runs as jobs with idempotency keys (ADR 0010, e.g. the deputyship check of DMS-99); outbox events reach people through the notifications module (ADR 0052); tenants have a time zone (`dms.tenant.time_zone`, ADR 0026); a workflow definition is JSON, versioned and pinned in the case.

## Decision

1. **Deadlines are data of our task mirror, not Flowable timers.** When a task is created, `ApprovalWorkflow` computes `due_at`, `remind_at` and `escalate_at` and stores them on `dms.workflow_task` (migration V35). The async executor stays off.
2. **Policy and precedence.** `DeadlinePolicy(dueWorkingDays, reminderWorkingDays, escalationWorkingDays, escalateTo)`; `escalateTo` ⊆ {`Owner`, `ComplianceOfficer`, `TenantAdmin`}. A step may set `dueWorkingDays` (also 0 = no deadline for this step); the workflow definition may set `deadlines`; otherwise the tenant's default (`dms.workflow_deadline_settings`, admin endpoint `/api/v1/admin/workflow-deadlines`, admin tab "Fristen"), otherwise the installation's (`dms.workflow.dueWorkingDays` 5, `reminderWorkingDays` 1, `escalationWorkingDays` 2, `escalateTo` Owner). The values in force at task creation are stored with the task; later changes apply to new tasks only.
3. **Working days.** Monday to Friday in the tenant's time zone. A task is due at 23:59:59 local time of the N-th working day after the day it was created; the reminder is the same moment N working days earlier (dropped if not after creation); the escalation N working days later. Public holidays are not known.
4. **Notices once, through the outbox.** A job `workflow.deadline-check` per tenant and interval (`dms.workflow.deadlineCheckSeconds`, 900) publishes for each open task the notice of the latest stage reached: `workflow.task-due-soon` (reminder; candidates), `workflow.task-overdue` (candidates and their active deputies), `workflow.task-escalated` (candidates, deputies and the escalation targets: owner, compliance officers whose scope covers the document, tenant administrators). Each notice is recorded in `dms.workflow_task_deadline_notice` (primary key task + stage) in the same transaction as the outbox event and the audit entry (`workflow.task-reminded`, `workflow.task-overdue`, `workflow.task-escalated`, technical actor `system:workflow-deadlines`), under the case lock; a stage skipped while no instance ran is not caught up. The notifications module delivers them in the new category `deadline` (email on by default).
5. **Commit hook.** `Db.transact` keeps per transaction two lists of effects, `Db.afterCommit` and `Db.afterRollback`, run after the connection is released, in registration order; failures are logged and do not change the outcome. `SharedTransaction.call` runs an engine call and fires `COMMITTING` at its end (still on the connection, like Spring's "before commit"); `FlowableEngine.run` registers the call's `COMMITTED` listeners with `Db.afterCommit` and its `ROLLED_BACK` listeners with `Db.afterRollback`. A failed engine call still fires `ROLLED_BACK` at once. The engine's own transaction (boot) fires `COMMITTED` after its own commit.
6. **API and UI.** `TaskView.dueAt` (inbox, document page with "fällig am …" and an overdue badge), `StepView.dueWorkingDays`, `WorkflowDefinitionView.deadlines`, `GET|PUT|DELETE /api/v1/admin/workflow-deadlines` (`Configure` on `Workflows`, audited as `workflow.deadlines-changed` / `workflow.deadlines-reset`).

## Alternatives considered

| Criterion (where deadlines live) | Chosen: columns of our task mirror + periodic check job | Flowable boundary timer events with the async executor | Computed at read time from the policy |
|---|---|---|---|
| Fits "Flowable routes, our tables hold the truth" (ADR 0024) | Yes | Business timing inside BPMN; models regenerated per policy | Yes |
| Tenant isolation, RLS | Our tables | Flowable job tables without RLS; executor needs tenant context per job | Our tables |
| Multi-instance safety | Job queue with idempotency keys and leases (ADR 0010) | Flowable's own executor and locks, a second job system | – |
| Changing the policy later | New tasks only (stored) | Running timers keep old values, model versions multiply | Changes running tasks retroactively |
| Effort | Columns, a pure rule object, one job | Executor configuration, timer listeners into our tables, commit hook required anyway | Small, but needs history of policies |

| Criterion (escalation) | Chosen: three stages, notices once | Repeated reminders every N days | Reassign the task to the escalation target |
|---|---|---|---|
| Noise | Bounded (3 notices per task) | Unbounded | – |
| Business meaning | Information; responsibility stays | Information | Changes who may vote; four-eyes and scope rules would need re-checking |
| Effort | Small | Small | Large |

| Criterion (commit hook) | Chosen: hooks in `Db.transact`, listeners deferred by `FlowableEngine.run` | Keep firing at the end of the engine call | Outbox only for engine side effects |
|---|---|---|---|
| Side effects only after commit | Yes | No (fires before the outer commit) | Yes, but Flowable's listeners are internal |
| Generality | Any component can register (`Db.afterCommit`) | – | Only our own events |
| Risk | Hooks must not assume the connection; failures only logged | Phantom effects after a rollback once timers are on | – |

## Consequences

### Positive

- Deadlines appear in the inbox and on the document page; participants are reminded, overdue tasks reach deputies, escalation reaches owner, compliance or administrators as configured; every notice is audited and published once.
- Deadlines are tenant-isolated, configurable per tenant, workflow and step, and computed with the tenant's time zone.
- Engine side effects that Flowable defers to `COMMITTED` can no longer happen for a transaction that rolls back; enabling the async executor later is no longer blocked by the transaction glue.
- `Db.afterCommit` is available to other modules for "after commit" work (e.g. cache invalidation).

### Negative / Cost

- Public holidays are ignored: a deadline may fall on a holiday.
- Tasks created before V35 have no deadline; changing the policy does not move existing deadlines.
- The check runs every 15 minutes: notices come up to that late; a stage skipped (e.g. all instances down past the next stage) is not sent.
- Deputies are evaluated when the overdue notice is published, not later.
- Escalation does not reassign the task; it only informs.
- Commit hooks run after the transaction without the connection and are not retried; a failing hook is only logged.

## Follow-ups

- Should: a holiday calendar per tenant (or region) for working days.
- Should: deadline fields in a workflow configuration UI per class (today: API, JSON definition).
- Later: repeated reminders for long-overdue tasks; reassignment as an escalation option; Flowable timers if BPMN-level timing becomes necessary (the commit hook is ready).
- Ruled out: business deadlines inside Flowable while the business truth lives in our tables.

## Reversibility

**high**: The deadline columns, settings and notice tables can be dropped without touching votes, approvals or cases; the check job is one handler. The commit hook is internal to `Db` and `FlowableEngine`; reverting to immediate listener firing changes two methods.
