# 0028: Deputies for workflow participants: time-limited deputyships resolved when tasks are read

| Field | Value |
|---|---|
| Status | Accepted (2026-10-28, implemented in DMS-49) |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

US-10.1 (DMS-49): an approver sets a deputy for their vacation so that approvals do not get stuck. The deputyship is time-limited and restricted to persons with the same authority; during the period the approver's tasks go to the deputy automatically, and the audit trail shows "X on behalf of Y" (UI text later: "X i. V. für Y").

Given (ADR 0024, 0018, 0017): candidates of a lane are resolved once, when the task is created, from all holders of the lane's role whose assignment covers the document (organizational unit, class subtree), minus the author under the four-eyes rule; any candidate completes the task. Votes are bound to the content checksum, approval votes need step-up, the final approver must differ from the author (database check). `Policy` is pure and decides on the subject's role assignments; the workflow only checks that the caller is a participant. The audit entry already has an `on_behalf_of` column (ADR 0017). The editorial path (DMS-46) has a pseudo role `DocumentOwner`; real owner deputies and delegation in general are backlog (DMS-88). ADR 0027 is reserved for DMS-47 (migration V15).

One observation shaped the decision: because candidates are *all* holders of the role in scope, a deputy whose own assignment covers the principal's scope is already a candidate of every task the principal gets. A deputyship only changes anything if the deputy is *not* a holder in the document's scope, for example the single approver of the legal department stands in for by an approver of the sales department.

## Decision

1. **Deputyship.** Table `dms.deputyship` (migration V14, tenant-scoped with RLS and grants to `dms_app`): principal, deputy, `valid_from`, `valid_until` (required, at most 366 days), the covered lane roles (`Author`, `Owner`, `Reviewer`, `Approver`; default: those the principal holds), an optional document class (the class and its subclasses), reason, creator, and an early end (`ended_at`, `ended_by`). Rows are never deleted or changed except for setting the end once (guard trigger, column grants). Several deputies per principal are allowed; the same pair with an overlapping period is a conflict. Creating and ending are audited (`deputyship.created`, `deputyship.ended`) and published to the outbox (`workflow.deputyship-created`, `workflow.deputyship-ended`, recipients principal and deputy).
2. **Same authority = same role.** The deputy must hold each covered role themselves (any scope, active when the period starts); otherwise 422. During the period the deputy acts within the principal's scope on the principal's tasks: for the document of such a task the application extends the deputy's subject with the principal's assignments of the task's role (`Authorizer.onBehalfOf`, used by `WorkflowApi` for the vote and by `DocumentApi`/`WorkflowApi` for reading). `Policy` is unchanged and still checks validity, scope and confidentiality of every assignment. When the deputy acts, the deputyship must be active and the deputy must still hold the role.
3. **Forwarding at read time, to both.** Tasks are not copied or reassigned. `ApprovalWorkflow` resolves, for every open task it returns or votes on, the deputies of its candidates (`Rules.taskDeputies`): active deputyship for the lane's role and the document's class, deputy still holds the role, deputy not already a candidate. The principal keeps the task; whoever acts first completes it. Existing open tasks become available when the period starts, and are the candidates' alone again when it ends or is ended early, without a job; the evaluation is idempotent by construction. `GET /api/v1/workflow/tasks` returns the caller's own tasks and those of their principals with `actingFor`; task views list `deputies`.
4. **Four-eyes.** A deputy never acts on a version they authored, and nobody stands in for the author, whatever the class's four-eyes setting (the author is excluded as principal as well). The existing checks (author cannot vote under four-eyes, approver ≠ author in the database) stay. No chains: only candidates' deputies are resolved, not deputies of deputies.
5. **Recording.** Votes store `acting_for` (immutable like the rest of the vote, guard trigger extended); the approval stores `acting_for` (the principal of the last approving vote). Audit entries of the deputy's vote, return, rendition request and approval have `actor` = deputy and `on_behalf_of` = principal and carry `actingFor` in their details; outbox events `workflow.returned` and `workflow.approved` carry `actingFor` (and `returnedBy`/`approvedBy`). The PDF approval note says "Approved on … by deputy on behalf of principal" / "Freigegeben am … von deputy i. V. für principal" (`RenditionRequest.onBehalfOf`, `RenditionLayout` version 2).
6. **Scope.** Lanes of business roles (reviews and approvals). Owner confirmations of the editorial path (`DocumentOwner` lanes) are not forwarded; owner deputies stay with DMS-88.
7. **API.** `POST/GET/DELETE /api/v1/deputyships`: everyone manages their own deputyships as principal, the deputy may end one, tenant administrators (`Configure` on `Workflows`) manage those of others and list all.

## Alternatives considered

| Criterion | Chosen: same role, act in the principal's scope | Deputy's own scope must cover the principal's | Any person may be deputy |
|---|---|---|---|
| Effect | Tasks reach someone who is not otherwise eligible | None in practice: the deputy is already a candidate of every task the principal gets | Tasks reach anyone |
| "Same authority" | Same role (an approver for an approver) | Same role and at least the same scope | Not given |
| Authorization change | Subject extended for the task's document only | None | Large; violates the requirement |

| Criterion | Chosen: resolve deputies when tasks are read | Rewrite the candidates of open tasks on period start and end (job) | Add the deputy when a task is created |
|---|---|---|---|
| Existing open tasks | Available immediately when the period starts | Need a scheduled job at start and end | Not covered |
| Return after the period | Automatic | Job must remove exactly what it added | Deputy stays a candidate |
| Idempotency, races | No stored state to keep consistent | Job and votes must coordinate; retries | Simple |
| Cost | One query per case read for active deputyships, the document and the deputies' roles when there are any | None when reading | None when reading |
| Snapshot semantics of candidates (ADR 0024) | Unchanged | Changed by a job | Changed |

| Criterion (who gets the task) | Chosen: principal and deputy | Deputy only |
|---|---|---|
| Principal returns early | Can act without ending the deputyship | Must end the deputyship first |
| Duplicate work | First vote completes the task, the other sees `Conflict` | None |

## Consequences

### Positive

- Approvals do not get stuck when the only eligible approver is absent; the period starts and ends without a job and without touching tasks.
- The record states who acted for whom in the vote, the approval, the audit trail (`on_behalf_of`), the outbox and the PDF.
- `Policy` stays pure and unchanged; delegation is limited to the document of a forwarded task.

### Negative / Cost

- Reading cases and task inboxes costs one more query; with active deputyships also the document subject and the deputies' role assignments per case.
- During the period the deputy sees and decides documents outside their own scope (that is the purpose); confidential documents still need the allowed group.
- The deputy is not notified when a period starts; only on creation and end (outbox).
- A principal whose own role assignment expires keeps being a candidate of existing tasks (snapshot, ADR 0024); so does the forwarding.

## Follow-ups

- Must be done: notifications module delivers `workflow.deputyship-created`/`-ended` and shows forwarded tasks; UI for deputyships and "X i. V. für Y" (task inbox DMS-48 can use `actingFor` and `deputies`).
- Later: deputies for document owners and the editorial confirmation (DMS-88); reminder when a period starts; deputies proposed from an absence calendar.
- Ruled out: chains of deputies; deputyships without an end.

## Reversibility

**medium**: Deputyships live in one table and are resolved in `ApprovalWorkflow.views`; votes and approvals only gain a nullable column. Dropping the feature removes the resolution and the API; recorded `acting_for` values stay meaningful. Switching to stored forwarding would need a job and a migration of open tasks.
