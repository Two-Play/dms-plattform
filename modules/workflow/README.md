# Module `workflow`

Review and approval workflows (business concept 2.4, 6.1; ADR 0006, 0024; DMS-40, DMS-41, DMS-42).

Layout: `api` holds public types, `core` the implementation. Other modules may only depend on `api`.

## `api` (package `dms.workflow`)

- `WorkflowDefinition`: steps (`Review` steps first, then `Approval` steps), each with parallel `Lane`s naming a business role; a step may run only `ExtraReviewRequired`; `resetTo` names the step a resubmitted version restarts at after a content change (default: first step). `WorkflowDefinition.problems` validates the structure; `WorkflowDefinition.default` (one review by a reviewer, one approval by an approver) is used for classes without a workflow of their own or of an ancestor.
- `VoteDecision`: `Approve`, `ApproveWithConditions`, `RequestChanges`, `Reject`; a reason is required for everything except `Approve`.
- Views: `StoredDefinition`, `WorkflowCase`, `WorkflowTask`, `Vote`, `Approval`, `CaseDetails`.

## `core` (package `dms.workflow.core`)

- `ApprovalWorkflow`: `define`, `definitions`, `start` (after submission, or the next round of a returned case), `vote`, `myTasks`, `task`, `cases`, `caseOfVersion`, `resolveParticipants`. Every method runs inside `Db.transact(tenant)`; engine calls, status changes (through the `DocumentGateway` port), votes, audit entries and outbox events commit together. Votes lock the case row.
- `Rules`: pure rules: participants with the four-eyes rule, resume vs. reset after a return (content checksum), vote checks (reason, step-up window), return events.
- `Bpmn`: generates the BPMN model of a definition (resume gateway, conditional steps, parallel lanes).
- `FlowableEngine`: the embedded Flowable 8 engine (schema `flowable`, table prefix, async executor off) and `migrateSchema` for `dms.app.Migrate`. `SharedTransaction`: the only code using Flowable `impl` packages; joins the `Db.transact` connection. `SharedTransactionSpec` must pass on every Flowable upgrade.
- Ports implemented by `app`: `DocumentGateway` (version under review with content SHA-256, lifecycle events), `ParticipantDirectory` (role holders in scope, tenant administrators), `ExtraReviewCheck` (`DocumentVersion.requiresExtraReview`). `WorkflowConfig.reauthenticationWindow` is the step-up window.

## Behaviour

- Submission (`POST /api/v1/documents/{id}/submit`) moves the draft to `InReview` and starts a case on the latest workflow of the nearest class in the class path. The case keeps its definition and Flowable process definition for all rounds.
- Each lane task gets the holders of its role whose assignment covers the document (organizational unit, class subtree), without the author if the four-eyes rule is on. Nobody eligible: the task is `Waiting`, outbox event `workflow.participant-missing` (recipients: tenant administrators), audit entry; after assigning the role, a tenant administrator re-resolves the participants.
- Entering the first approval step applies `ReviewPassed`; the last approving vote applies `Approve` as the voter and stores the approval with version label, content SHA-256 and `auth_time` (outbox `workflow.approved`).
- `RequestChanges` / `Reject` cancel the other tasks of the round and return the version to `Draft` (outbox `workflow.returned` to author and owner). On resubmission with unchanged content the round resumes at the returning step; with changed content all earlier votes are invalidated and the round starts at `resetTo`.
- Approval votes need a sign-in (`auth_time`) within `dms.workflow.reauthenticationSeconds` (default 300 s); otherwise 401 `reauthentication_required`, and the client re-authenticates with OIDC `max_age=0` or `prompt=login`.
- Disabling the four-eyes rule needs a reason and is audited (`workflow.four-eyes-disabled`). The final approver must still differ from the author (ADR 0021).

## Tables (migration V11)

`dms.workflow_definition`, `dms.workflow_case`, `dms.workflow_task`, `dms.workflow_vote`, `dms.workflow_approval`: tenant-scoped with RLS (`dms.current_tenant()`) and explicit grants to `dms_app`; definitions and approvals insert-only, votes only invalidated. Flowable's tables live in schema `flowable` without RLS.

## HTTP (module `app`, `WorkflowApi`)

| Method | Path | Purpose | Authorization |
|---|---|---|---|
| GET | `/api/v1/workflow/tasks` | Open tasks of the caller | signed in |
| POST | `/api/v1/workflow/tasks/{taskId}/votes` | Vote `{decision, reason}` | participant; `Review` or `Approve` (+ step-up) |
| POST | `/api/v1/workflow/tasks/{taskId}/participants` | Re-resolve participants | `Configure` Workflows |
| GET | `/api/v1/documents/{id}/workflow` | Cases with tasks, votes, approval | `ReadDocument` on the version |
| GET | `/api/v1/admin/workflows/{classKey}` | Versions of a class's workflow | `Configure` Workflows |
| POST | `/api/v1/admin/workflows/{classKey}` | Define the next version `{definition, fourEyes, reason}` | `Configure` Workflows |

Tests: `RulesSpec` (pure), `SharedTransactionSpec` (engine, Testcontainers), `app/WorkflowApiSpec` (end to end over HTTP).
