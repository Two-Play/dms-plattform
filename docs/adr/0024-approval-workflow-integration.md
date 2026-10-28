# 0024: Approval workflows: Flowable routes the steps, our tables hold the business truth

| Field | Value |
|---|---|
| Status | Accepted (2026-10-28, implemented in DMS-40, DMS-41, DMS-42) |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

ADR 0006 chose Flowable 8 as embedded engine and validated a shared transaction in spike DMS-22. Sprint 6 needs the first real workflows (business concept 2.4, 6.1): per document class configured (not programmed) and versioned workflows with sequential steps and parallel lanes, participants resolved from roles, votes with reasons, approval bound to an exact version and its content (any content change after a vote invalidates it), approval only after re-authentication, and the four-eyes rule (author never a participant; configurable per class, disabling audited). If nobody is eligible, the tenant administrators are notified and the step waits. A conditional extra review step is needed for deviations from locked standard text (DMS-79). Tenants are isolated by row-level security (0014), audit and outbox join the business transaction (0010, 0017), and modules may only depend on other modules' `*-api` (0001).

## Decision

1. **Division of labour.** Flowable only routes: a BPMN model is generated from a JSON `WorkflowDefinition` (steps → exclusive "enter" gateway, parallel fork, one user task per lane, join; a `resume` gateway at the start jumps to the step in variable `resumeAt`; a conditional step is skipped unless `extraReview`). Everything with business meaning lives in `ApprovalWorkflow` (`workflow-core`) and in our own tenant-scoped tables (migration V11, RLS + grants): `workflow_definition` (versions per class key, insert-only), `workflow_case` (one per document version, pins definition JSON and Flowable process definition id), `workflow_task` (mirror of each user task with resolved candidates and status Open/Waiting/Completed/Cancelled), `workflow_vote` (decision, reason, content SHA-256, `auth_time`; only invalidation may be set later) and `workflow_approval` (version label, content SHA-256, approver, `auth_time`; insert-only, approver ≠ author).
2. **Rounds.** Each submission is a round with its own Flowable process instance, started by the pinned process definition id, so all rounds of a case stay on the workflow version of the first round. A vote "request changes" or "reject" deletes the instance, returns the version to `Draft` (`RequestChanges` from review, `Reject` from approval) and records the returning step. On resubmission the stored checksum is compared with the current one: unchanged → continue at the returning step, votes stay valid; changed → all earlier votes are invalidated and the round starts at the definition's `resetTo` step (default: first step).
3. **Content checksum.** SHA-256 over the canonical JSON (sorted keys, no whitespace) of the version's content and field values with their origin, computed by the application adapter (`WorkflowDocuments.contentSha256`).
4. **Participants and four-eyes.** Candidates of a lane are the holders of the lane's role whose assignment is active and whose scope covers the document (`Policy.rolesInScope`), minus the author while the class's four-eyes rule is on; any candidate completes the task. No candidate → task `Waiting`, outbox event `workflow.participant-missing` with the tenant administrators as recipients, audit entry; a tenant administrator re-resolves after assigning the role. Disabling the rule needs a reason and writes `workflow.four-eyes-disabled` to the audit trail. The final approval stays segregated in any case (Lifecycle + DB check of ADR 0021).
5. **Step-up.** Approval votes require the token's `auth_time` to be at most `dms.workflow.reauthenticationSeconds` (default 300) old; otherwise 401 `reauthentication_required`, and the client signs in again with OIDC `max_age=0` or `prompt=login`. Tokens without `auth_time` fail closed. The `auth_time` is stored with the vote and the approval.
6. **Engine wiring (deviations from the spike, see addendum in ADR 0006).** Flowable's tables live in schema `flowable`, created/upgraded by `dms.app.Migrate` as schema owner on own connections (`FlowableEngine.migrateSchema`, `databaseSchemaUpdate=true` there, `false` at runtime); migration V11 creates the schema and default privileges for `dms_app`. At runtime the engine uses the application pool and joins the `Db.transact` connection; its SQL is qualified via table prefix `flowable.` (`tablePrefixIsSchema`), so our connections keep their search path. No RLS on `flowable.*`; tenants are separated by Flowable `tenantId` on deployments. Async executor is off (no timers yet).
7. **Ports.** `workflow-core` depends on `documents-api` and `audit-api` only. The application implements `DocumentGateway` (version, checksum, lifecycle events via `DocumentService`), `ParticipantDirectory` (role assignments) and `ExtraReviewCheck` (`DocumentVersion.requiresExtraReview` from DMS-79).

## Alternatives considered

| Criterion | Chosen: generated BPMN + own tables | Hand-written BPMN per class with service tasks / listeners | Own state machine without Flowable |
|---|---|---|---|
| Configured, not programmed (6.1) | JSON definition, validated, versioned | Tenants would edit BPMN XML; needs a modeler | JSON definition |
| Logic location | Scala service, pure rules tested without engine | Spread over BPMN expressions and Java delegates | Scala |
| Votes, checksums, approvals queryable with RLS | Yes, own tables | Process variables in `flowable.*` without RLS | Yes |
| Timers, escalation, later BPMN features | Available (async executor to be enabled) | Available | Must be built |
| Running cases keep their version | Flowable versioning + pinned definition id | Flowable versioning | Must be built |
| Coupling to Flowable internals | Transaction glue only (one file) | Glue + delegates | None |

| Criterion (returning a version) | Chosen: one process instance per round | Keep one instance, model a "rework" wait state and loop |
|---|---|---|
| Model complexity | Linear model, `resume` gateway | Loops and messages per step |
| Invalidation on content change | Comparing two checksums at resubmission | Same, plus jumping inside a running instance |
| History in Flowable | Several instances per case (business key `documentId:versionId`) | One instance |

## Consequences

### Positive

- Workflow steps, document status changes, votes, approvals, audit entries and outbox events commit or roll back together.
- The business record of who voted what on which content is in RLS-protected tables, independent of engine internals; the engine can be replaced within the module.
- Rules (four-eyes, resume/reset, vote checks, step-up) are pure and tested without a database.
- Votes on one case are serialized by a row lock on the case, so parallel lanes do not run into Flowable's optimistic locking at the join.

### Negative / Cost

- Flowable listeners fire at the end of each engine call, before the outer commit; acceptable only while the async executor is off.
- A Flowable exception comes back as a typed error; if a caller swallows it, the transaction commits without the engine change (a failed command flushes nothing). Only `ApprovalWorkflow` calls the engine.
- Deployments happen lazily inside the case-start transaction; after a rollback the engine's deployment cache may hold an entry that never reaches the database (harmless, never looked up).
- Candidates are a snapshot at task creation; role changes need an explicit re-resolution.
- Disabling four-eyes only affects participation; the final approver must still differ from the author (DB check, ADR 0021).

## Follow-ups

- Must be done: deadlines, reminders and escalation (enable the async executor; move listener firing to a commit hook in `Db`).
- Must be done: notifications module consumes `workflow.participant-missing`, `workflow.returned`, `workflow.approved` from the outbox.
- Must be done: verify that ZITADEL JWT access tokens carry `auth_time` after `prompt=login`; otherwise pass the ID token for step-up.
- Must be done: rendering fills the rendition before the last approval vote (the DB requires it for `Approved`, ADR 0021).
- Later: majority / one-of lanes, conditional steps on metadata (amount thresholds), owner of the parent document as participant, delegation, confidentiality checks when resolving candidates, a configuration UI.
- Ruled out: storing votes or approvals only as Flowable variables.

## Reversibility

**medium**: Flowable is confined to `FlowableEngine`, `SharedTransaction`, `Bpmn` and a few calls in `ApprovalWorkflow`; the business truth is in our tables. Replacing the engine means re-implementing routing and migrating running cases (open tasks of the current round).
