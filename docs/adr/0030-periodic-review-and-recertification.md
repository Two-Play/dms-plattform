# 0030: Periodic review: review date outside the frozen version, review tasks as rows, a daily check through the job queue

| Field | Value |
|---|---|
| Status | Accepted (2026-10-28, implemented in DMS-55) |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

US-07.1 (DMS-55): as owner I want a task in good time before the review date so I can plan the review. The task is created according to the class's lead time (default 60 days). I can choose "Recertify", "Revise" or "Withdraw (put out of force)". Recertification sets a new review date and is recorded, without a new version.

Business concept 6.2: review cycle per class (e.g. yearly), shorter per document; the next date is computed on every approval; the owner gets the task 30 to 60 days ahead; outcomes (a) recertified without change → new review date, the version stays, the recertification is recorded, (b) revision → new draft, (c) not needed any more → put out of force. Overdue reviews stay valid and are marked; withdrawing automatically is not the default. Lifecycle 5.1: Published → ReviewDue (review date reached) → Published (recertified) and Published → out of force.

Given:

- ADR 0021: a version from approval on is a frozen package (content, values, metadata snapshot, rendition); database guards reject every change except status transitions along the lifecycle. `nextReview` is an optional core date field (ADR 0019), so its value is frozen in the snapshot.
- ADR 0026: the valid-from date is frozen on approval; scheduled publication runs through the job queue; the database guarantees one valid version per scope and names the successor when a valid version is superseded (`Superseded` requires a successor).
- ADR 0010: job queue with idempotency keys; periodic work is enqueued per tenant and period (`AuditJobs`, `SearchJobs`).
- The lifecycle knows `Withdraw` only for drafts (Draft → Withdrawn, "Verworfen"); withdrawn drafts give their number back.
- Class versions are append-only rows (ADR 0019); documents pin a class version for their profile.
- The task inbox (DMS-48) has a typed placeholder kind `ReviewDate`.
- Other changes of the same sprint touch the version comparison (DMS-54), legal hold and archive (DMS-56) and access groups (DMS-101, migration V17, merged before this change, so this change uses V18).

## Decision

1. **Class settings.** `document_class_version` gets `review_interval_months` (1 to 120, optional) and `review_lead_days` (0 to 365, default 60), part of `DocumentClassVersion.review` (`ReviewPolicy`). The settings of the class's latest version apply to all its documents (governance follows the current configuration; no profile change is needed). A class without interval has no periodic review unless a document sets `nextReview`. Not inherited from parent classes (follow-up).
2. **Review date outside the frozen package.** `document_version.review_due_on` (migration V18) is set when a version becomes valid (Approved → Published, in `DocumentService` in the same transaction): `validFrom` plus the interval; the document's `nextReview` value from the snapshot only shortens the cycle and counts only if it lies after `validFrom` (`Review.dueOn`). A separate guard trigger allows changing it only while the version is valid (Published, ReviewDue); the content guard of V9/V10 does not watch it. Recertification changes only this column.
3. **Review tasks as rows.** `dms.document_review`: one row per valid version and review date (`unique (version_id, due_on)`), at most one open per version, decided once (Open → Recertified with `next_due_on`, Revised with `new_version_id`, Withdrawn with the required reason, or Obsolete when the version stopped being valid without a decision). Guard trigger: starts open, decided once, identity never changes, no deletes; RLS and column grants for `dms_app`.
4. **Daily check through the job queue.** `ReviewJobs.tick` (every `dms.documents.reviewCheckSeconds`, 3600) enqueues `document.review-check` per tenant and interval (idempotency key). The job (`ReviewJobs.check`) reads what is due on the tenant's local date and handles every item in its own transaction: closes obsolete tasks, creates the tasks whose lead time is reached (`review_due_on - lead_days <= today`, insert `on conflict do nothing`), and moves published versions whose review date has come to ReviewDue (they stay valid). Each step re-checks under a row lock and is idempotent. The platform acts as `system:review` (technical actor, automation channel).
5. **Decisions (owner only, no deputies).** `ReviewService.recertify` sets the new review date (chosen, after today; default today plus the interval), returns a ReviewDue version to Published (`Recertify`), records `document.recertified` with the previous and next date; no new version. `revise` starts the next version with `DocumentService.newVersion` (Major by default) or links the open version; the valid version stays in force. `withdraw` applies the new lifecycle transition Published/ReviewDue → Withdrawn ("out of force"); other versions are not touched, an approved version waiting for its valid-from date is still published on its date (it supersedes nothing). The API (`/api/v1/reviews`) requires the caller to be the document's owner plus `Recertify` (new action: owner role and owner of the document), `EditDraft` (as for a new version) or `Withdraw`.
6. **Withdrawal of a valid version.** V18 replaces the V10 lifecycle guard function with the two added transitions; the database records `withdrawn_at` (trigger), a withdrawn released version keeps its number (the number index now exempts only withdrawn drafts), the validity history (DMS-53) counts a withdrawn version until `withdrawn_at`, and the search index removes it through the existing `document.status-changed` event.
7. **Events.** Review events are recorded in the audit trail and published to the outbox with the audit action as event type: `document.review-task-created`, `document.recertified`, `document.review-revised`, `document.review-withdrawn`, `document.review-task-closed` (payload: task, document, version, due date, outcome). No subscriber yet; notifications can use them.

## Alternatives considered

| Criterion (where the review date lives) | Chosen: column on the version, own guard | Only in the metadata (`nextReview` in the snapshot) | Separate schedule table per version |
|---|---|---|---|
| Recertification without a new version | Yes, the column changes | No: the snapshot is frozen; a new date would need a new version or a mutable snapshot | Yes |
| Frozen package unchanged | Yes, the column is not part of the package; guard limits changes to valid versions | Would need to weaken the snapshot guard | Yes |
| Query for the daily check | One indexed predicate on `document_version` | JSON path per row, plus class interval at query time | Join |
| Effort | One column, one trigger | None, but does not meet the story | Table, RLS, grants, guard, backfill |

| Criterion (withdrawing a valid version) | Chosen: Published/ReviewDue → Withdrawn | → Superseded without successor | → Archived directly |
|---|---|---|---|
| Fits the business concept ("Außer Kraft gesetzt") | Yes: out of force, no successor | Mixes "replaced" and "withdrawn"; readers would be pointed to a successor that does not exist | Skips "out of force"; archiving is records management (DMS-56) |
| Database guarantee "superseded names its successor" (ADR 0026) | Kept | Must be weakened | Kept |
| Lifecycle change | Two transitions, guard replaced once | Two transitions plus V12 guard change | Two transitions; archive rules of DMS-56 affected |
| Validity history | `withdrawn_at` ends the period | Needs another end marker | Needs another end marker |

| Criterion (running the check) | Chosen: one job per tenant and interval, items in own transactions | One job per version with `run_at` (like publication) | Computed at read time (no tasks stored) |
|---|---|---|---|
| Rows in the job queue | One per tenant and hour | Two per valid version and review date, re-enqueued every tick | None |
| Failure isolation | Per item; the job fails and retries if any item failed | Per version | n/a |
| Audit trail and inbox state | Task rows with creation and decision | Same | No record of when the owner was asked; decisions need their own records anyway |
| Precision | Up to one interval after 00:00 of the lead day | Seconds | Exact |

## Consequences

### Positive

- The owner sees review tasks in the inbox at the configured lead time; the decision is recorded once with who, when and what, in the audit trail and the outbox.
- Recertification never touches the frozen package; the database rejects review date changes of versions that are not valid.
- Overdue versions stay valid (ReviewDue) as the business concept requires; nothing goes out of force automatically.
- Withdrawing leaves approved and other-scope versions alone and keeps the validity history, the search index and acknowledgements consistent through existing mechanisms.

### Negative / Cost

- The review date of versions published before V18 comes only from their `nextReview` value; classes get no interval by default, so existing documents without that value are not reviewed until a class sets an interval and they are published again (or the date is set by a follow-up backfill).
- A class's new interval applies to versions published afterwards; existing review dates are not recomputed.
- Tasks appear up to one check interval (1 h) after 00:00 of the lead day.
- The V10 lifecycle guard function is replaced; later migrations replacing it (e.g. DMS-56) must start from V18.
- No notification (e-mail) yet; the task is visible in the inbox only.

## Follow-ups

- Must be done: notify the owner when a task is created and escalate overdue reviews (compliance cockpit, business concept 6.2).
- Must be done: orphaned documents (owner left, F-07.5): reassign open review tasks.
- Should: inherit review settings from parent classes; recompute review dates when a class interval changes; a view of a version's next review date in the document page.
- Should: review context in the task (changes of parent documents, open comments; F-07.2) and event-driven reviews (US-07.2, DMS-65).
- Ruled out: withdrawing overdue versions automatically.

## Reversibility

**medium**: The check is one job kind and one service; replacing it changes `ReviewJobs` only. The review date column, the task table and the class columns are additive (dropping them is a migration). The lifecycle transitions Published/ReviewDue → Withdrawn are used by stored data once versions are withdrawn, so removing them would need a data migration.
