# Module `workflow`

Review and approval workflows (business concept 2.4, 6.1; ADR 0006, 0024; DMS-40, DMS-41, DMS-42).

Layout: `api` holds public types, `core` the implementation. Other modules may only depend on `api`.

## `api` (package `dms.workflow`)

- `WorkflowDefinition`: steps (`Review` steps first, then `Approval` steps), each with parallel `Lane`s naming a business role; a step may run only `ExtraReviewRequired`; `resetTo` names the step a resubmitted version restarts at after a content change (default: first step). `WorkflowDefinition.problems` validates the structure; `WorkflowDefinition.default` (one review by a reviewer, one approval by an approver) is used for classes without a workflow of their own or of an ancestor.
- `VoteDecision`: `Approve`, `ApproveWithConditions`, `RequestChanges`, `Reject`; a reason is required for everything except `Approve`.
- Views: `StoredDefinition`, `WorkflowCase`, `WorkflowTask`, `Vote`, `Approval`, `CaseDetails`.

## `core` (package `dms.workflow.core`)

- `ApprovalWorkflow`: `define`, `definitions`, `start` (after submission, or the next round of a returned case), `vote`, `completeApproval` (called by the rendering job), `myTasks`, `task`, `cases`, `caseOfVersion`, `resolveParticipants`. Every method runs inside `Db.transact(tenant)`; engine calls, status changes (through the `DocumentGateway` port), votes, audit entries and outbox events commit together. Votes lock the case row.
- `Rules`: pure rules: participants with the four-eyes rule, resume vs. reset after a return (content checksum), vote checks (reason, step-up window), return events.
- `Bpmn`: generates the BPMN model of a definition (resume gateway, conditional steps, parallel lanes).
- `FlowableEngine`: the embedded Flowable 8 engine (schema `flowable`, table prefix, async executor off) and `migrateSchema` for `dms.app.Migrate`. `SharedTransaction`: the only code using Flowable `impl` packages; joins the `Db.transact` connection. `SharedTransactionSpec` must pass on every Flowable upgrade.
- Ports implemented by `app`: `DocumentGateway` (version under review with content SHA-256, lifecycle events), `ParticipantDirectory` (role holders in scope, tenant administrators, a person's role assignments for deputies), `ExtraReviewCheck` (`DocumentVersion.requiresExtraReview`). `WorkflowConfig.reauthenticationWindow` is the step-up window.

## Behaviour

- Submission (`POST /api/v1/documents/{id}/submit`) moves the draft to `InReview` and starts a case on the latest workflow of the nearest class in the class path. The case keeps its definition and Flowable process definition for all rounds.
- Each lane task gets the holders of its role whose assignment covers the document (organizational unit, class subtree), without the author if the four-eyes rule is on. Nobody eligible: the task is `Waiting`, outbox event `workflow.participant-missing` (recipients: tenant administrators), audit entry; after assigning the role, a tenant administrator re-resolves the participants. Otherwise outbox event `workflow.task-assigned` (DMS-130) with the candidates as `recipients` and the deputies who may complete it now (`deputies`: deputy and principal), also for candidates added when participants are resolved again; the notifications module delivers it (ADR 0052).
- Entering the first approval step applies `ReviewPassed`. The last approving vote requests the archive rendition through the `ApprovalRendition` port (rendering job, DMS-44, ADR 0025) and moves the case to `Rendering`; the version stays `InApproval`. Once the rendering job has attached the rendition it calls `completeApproval`, which applies `Approve` as the voter and stores the approval with version label, content SHA-256 and `auth_time` (outbox `workflow.approved`). If rendering fails, the case stays in `Rendering`.
- `RequestChanges` / `Reject` cancel the other tasks of the round and return the version to `Draft` (outbox `workflow.returned` to author and owner). On resubmission with unchanged content the round resumes at the returning step; with changed content all earlier votes are invalidated and the round starts at `resetTo`.
- Discarding the draft (DMS-129, ADR 0050): `ApprovalWorkflow.discard(versionId, actor)` cancels open and waiting tasks of the version's case, deletes a running process instance and sets the case to `Discarded` (migration V31), audited as `workflow.discarded`.
- Approval votes need a sign-in (`auth_time`) within `dms.workflow.reauthenticationSeconds` (default 300 s); otherwise 401 `reauthentication_required`, and the client re-authenticates with OIDC `max_age=0` or `prompt=login` and votes again. The `auth_time` comes from the OIDC ID token in header `X-DMS-Id-Token` (ZITADEL access tokens have none, DMS-86) or from the access token.
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

Tests: `RulesSpec` (pure), `SharedTransactionSpec` (engine, Testcontainers), `app/WorkflowApiSpec` (end to end over HTTP); deputies see below.

## Editorial path of minor versions (DMS-46, ADR 0026)

A submitted minor version (`ReviewSubject.changeKind == Minor`) does not run the class's workflow but `WorkflowDefinition.editorial` (process key `approval.editorial`): one approval step `editorial-confirmation` with a lane of the pseudo role `WorkflowDefinition.DocumentOwner`. `ApprovalWorkflow` resolves that lane to the document owner (`Rules.ownerConfirmation`); if the owner is the author or there is none, the holders of `Approver` in scope confirm instead (the approver must differ from the author). The vote needs step-up like any approval; `WorkflowApi` authorizes it with `ownerMayApprove` for owner lanes. The class's four-eyes setting applies; the editorial definition is pinned in the case like any other.

## Deputies (DMS-49, ADR 0028)

An approver (or reviewer) sets a deputy for an absence: `Deputyships` (`create`, `end`, `get`, `involving`, `ofPrincipal`, `all`) stores time-limited deputyships in `dms.deputyship` (migration V14; RLS, grants, history guard: only the early end may be set, once). Same authority means the same role: the deputy must hold each covered lane role (`Author`, `Owner`, `Reviewer`, `Approver`; default: those the principal holds) themselves, in any scope (`Rules.checkDeputyship`, 422 otherwise); the period ends at most 366 days after it starts; an optional document class restricts it to that class and its subclasses. Created and ended deputyships are audited (`deputyship.created`, `deputyship.ended`) and published to the outbox (`workflow.deputyship-created`, `workflow.deputyship-ended`).

- Forwarding at read time (`ApprovalWorkflow.views`, `Rules.taskDeputies`): every open task lists in `deputies` the deputies of its candidates whose deputyship is active and covers the lane's role and the document's class and who still hold the role. Principal and deputy both see the task; whoever votes first completes it. Tasks become available when the period starts and are the candidates' alone again when it ends or is ended early; no job, nothing stored on the task. `myTasks` returns the deputy's forwarded tasks with `actingFor` = principal.
- Four-eyes: a deputy never acts on a version they authored, and nobody stands in for the author, whatever the four-eyes setting. No chains (deputies of deputies).
- Recording: votes (`acting_for`) and the approval (`acting_for`, from the last approving vote) name the principal; audit entries of the deputy's vote, return, rendition request and approval have `on_behalf_of` = principal; `workflow.returned` / `workflow.approved` carry `actingFor`; the PDF approval note says "by deputy on behalf of principal" ("von … i. V. für …").
- Authorization (module `app`): for the document of a forwarded task the deputy's subject is extended by the principal's assignments of the task's role (`Authorizer.onBehalfOf`), so the deputy reads and votes within the principal's scope; step-up applies as usual.
- Not forwarded: owner confirmations of the editorial path (`DocumentOwner` lanes; owner deputies are DMS-88).
- Notices (DMS-99, ADR 0028 addendum): `DeputyshipJobs` runs `workflow.deputyship-check` per tenant every `dms.workflow.deputyshipCheckSeconds` (300) and publishes `workflow.deputyship-started` (a scheduled period has begun) and `workflow.deputyship-ended-by-period` (a period ran out without an early end) to principal and deputy, once per deputyship (`dms.deputyship_notice`, migration V24). Delivering them is the notifications module's job.

| Method | Path | Purpose | Authorization |
|---|---|---|---|
| POST | `/api/v1/deputyships` | Create `{principal?, deputy, validFrom?, validUntil, roles?, documentClass?, reason?}`; 422 invalid, 409 overlapping | own; others: `Configure` Workflows |
| GET | `/api/v1/deputyships` | The caller's deputyships (principal or deputy); `?principal=` / `?all=true` | own; others: `Configure` Workflows |
| DELETE | `/api/v1/deputyships/{id}` | End now (kept for the history); 409 if already ended | principal, deputy or `Configure` Workflows |

Task views (`GET /api/v1/workflow/tasks`, cases of a document) carry `deputies` and, in the inbox, `actingFor`; vote and approval views carry `actingFor`.

Tests: `DeputyRulesSpec` (pure), `DeputyshipsSpec` (Testcontainers: RLS, guard, periods, service, notices under a clock moved ahead), `app/WorkflowApiSpec` (create, invalid deputy, deputy votes with step-up on behalf of the approver, return after the end; the period is entered with a clock running ahead, no guard is bypassed; deputies on confidential versions only as group members).

Access groups (DMS-101, DMS-105): `RoleDirectory.cleared` limits the participants of a confidential version to author, owner and current members of its groups. When a member joins or leaves a group, the group API calls `ApprovalWorkflow.resolveParticipantsOfGroup` in the same transaction: open and waiting tasks of versions naming the group get their candidates resolved again (unchanged tasks are skipped; changed ones are audited as `workflow.participants-resolved`, empty ones wait and notify the administrators).
