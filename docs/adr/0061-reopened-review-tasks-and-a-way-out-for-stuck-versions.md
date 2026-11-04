# 0061: A review task whose revision never took effect opens again as a new row; a version whose publication job is dead can be withdrawn after its date

| Field | Value |
|---|---|
| Status | Proposed (2026-11-04, implemented in DMS-145) |
| Date | 2026-11-04 |
| Decided by | Philippe |

## Context

DMS-145 collects the follow-ups of ADR 0059. Two of them are must-do.

**The review ends silently.** A review task decided as "revise" (ADR 0030) points to the new version in `new_version_id`. If that version is withdrawn before it takes effect (ADR 0059) or discarded as a draft (ADR 0050), the task stays decided, and the valid version has no open task for its review date. `ReviewJobs` does not help: `tasksToOpen` skips a version that has any task for its review date, so no notice follows and the periodic review of that version is over. A test confirmed the discard path before the change; until then only the code had been read.

Given: `dms.document_review` has `unique (version_id, due_on)` and an index of at most one open task per version. Its guard forbids deletes and every change of a decided row. The deadline check of ADR 0057 works on open tasks, keeps its notices per task and computes the stages from the review date and the task's creation.

**The stuck version.** A due version whose publication can never succeed blocks the document for good. Example: a class can name any statuses in `requiredIn`, so a field required in `Published` but not in `Approved` can be empty in the frozen values. `transition` to `Published` then fails with `[invalid]` on every attempt, also after a retry (ADR 0058). After the valid-from date the withdrawal answers 409 `version_due` (ADR 0059, decision 4), and `newVersion` refuses while a version is approved.

Given: the job queue keeps the state of the job in `dms.job`. A job dies by `dms.job_fail` or `dms.job_claim` (ADR 0058), and only `dms.job_retry` brings it back. The retry endpoint read the version and the job without any lock. `transition` validates the values against the target status for every event, and `requiredIn` may name `Withdrawn`, too (`Requiredness.from` says: statuses outside the regular path require a field only when named explicitly).

## Decision

### Review tasks

1. **A new row, not a transition.** A review task that opens again is a new row: same document, version and `due_on`, status `Open`, `reopens_id` naming the decided task. The decided row stays as it was recorded, with who decided what and when, and with the link to the revision. Migration V42 adds `reopens_id` and replaces `unique (version_id, due_on)` by a unique index for rows without `reopens_id`, plus `unique (reopens_id)`.
2. **The guard carries the rule.** It accepts `reopens_id` only for a task decided as `Revised`, of the same version and date, whose new version is `Withdrawn` or `Discarded` and has no `published_at`. `reopens_id` is part of the identity of a task and has no update grant. A version can therefore have several rows for a review date only as a chain of decided tasks, and the index of at most one open task per version stays.
3. **When.** `DocumentService.withdrawApproved` and `discardDraft` call `ReviewService.reopenFor` in their own transaction, so the outcome is immediate and atomic with the event. The review check (`ReviewService.pendingWork`, `reopen`) looks for the same condition, for what happened before this change and for the path `Draft → Withdrawn`, which has no such call.
4. **The condition.** The task is `Revised`, its new version never took effect (`Withdrawn` or `Discarded`, no `published_at`), its version is valid and still has this review date, and nothing reopened the task yet. A task whose version stopped being valid stays closed. A revision published as usual changes nothing. A revision can be linked to tasks of several versions (one per validity scope): each opens again.
5. **Order and repetition.** The version row is locked first and the condition is read again after the lock, like every other decision on a valid version. `insert ... on conflict do nothing` with `unique (reopens_id)` makes a repeated event add nothing. Only the row that was really inserted writes audit and outbox.
6. **Same date, new deadline state.** The new task has the review date of the first one and the moment of reopening as `created_at`. Notices are per task (ADR 0057), so the stages count anew: the check sends the latest stage reached. A date already past gives the overdue or escalated notice within one check interval, a date ahead gives the reminder as usual.
7. **Audit and notice.** Audit action and outbox event `document.review-task-reopened` as `system:review`, with the decided task, the date, the label and the status of the revision. The new kind `review.task-reopened` (category `workflow`, German and English) tells the document's owner why the review is open again. The API shows `reopens` on the task, and the inbox says "open again: the revision never took effect".

### Stuck versions

8. **The dead job opens the withdrawal.** `withdrawApproved` accepts a version after its valid-from date if the newest publication job of the version is `dead` (`PublicationJobs.isDead`; older jobs belong to an earlier due moment, ADR 0026). Every other state keeps the strict rule of ADR 0059 and 409 `version_due`: no job yet, queued, retrying with attempts left, running, or a dead old job next to a new one. Who may withdraw, the reason, the transition, the audit action, the cancelled jobs and the data of ADR 0059 stay the same. The audit entry and the event say `publicationFailed`. The dead job stays dead as a record.
9. **The values play no part.** A withdrawal of an approved version does not validate the values against `Withdrawn`. They are frozen, they may be why the publication failed, and a field required in `Withdrawn` would otherwise block the way out too.
10. **The race with a retry.** The retry endpoint now locks the version row first and reads the status and the job after it, as the withdrawal does (ADR 0059, decision 5). Only `dms.job_retry` revives a job, and it runs under that lock, so the answer of `isDead` holds for whoever has the lock. If the retry wins, the job is queued and the withdrawal gets 409 `version_due`. If the withdrawal wins, the retry finds the version withdrawn and gets 409 `publication_not_failed`, and the job stays dead. A job that dies at the same time is not locked out, and it does not need to be: reading a job that is still running refuses the withdrawal, which errs on the strict side. A zombie handler whose lease expired meets the row lock like any publication.
11. **Afterwards.** The version is `Withdrawn`, `newVersion` works as after any withdrawal (ADR 0059, decision 11), and a review task decided as revised with this version opens again.
12. **API and texts.** `DocumentView.withdrawable` also names a version with a dead job, and `publicationFailed` tells the dialog what to say. The notice on the document page, the list of failed publications and the mail `failure.publication` name the way out, by who can take it: the recipient if `Policy` allows, otherwise holders of the owner role or those who approved the version. `publication.withdrawn` no longer says "before its valid-from date" in this case.

### Can such a version be prevented at approval?

13. **Not at approval, and not in this change.** The check itself would be exact: values are frozen and the class version is pinned, so validating against `Published` at approval gives what the publication will find. But approval finishes in the rendering job (`ApprovalWorkflow.completeApproval`, ADR 0025), after the last vote. A refusal there makes that job fail for good: the version stays `InApproval`, the case `Rendering`, the mail says the archive PDF failed, and neither a retry nor this withdrawal helps. That is the same problem in a worse place, so it is not safe. The right place is submission, where values freeze and the author can still correct them. That changes when `requiredIn` takes effect for every class (ADR 0019), so it needs a decision of its own, and it would not cover other causes (`[conflict]`, `[legal_hold]`) or versions already approved. See the follow-ups.

Flyway V42. No new dependency.

## Alternatives considered

| Criterion (opening the task again) | Chosen: new row with `reopens_id` | Transition `Revised → Open` on the same row | Drop the unique key, let `tasksToOpen` create a plain new row | Nudge the date by a day to pass the unique key |
|---|---|---|---|---|
| First decision stays readable in the table | Yes | No: `decided_*` and `new_version_id` must be emptied (checks of V18) | Yes | Yes |
| Guard "decided once" | Kept | Weakened | Kept | Kept |
| Same due date | Yes | Yes | Yes | No: the requirement fails |
| Task history (evidence report, decided list) | Both tasks, linked | One task, the decision only in the audit trail | Both tasks, not linked | Both, one with a false date |
| Notices of ADR 0057 | New stages, as a new task | Stages already sent are not repeated: no overdue notice after a late reopening | New stages | New stages |
| Protection against a double task | `unique (reopens_id)` | Status | Only the open index: a decided task of the date would reopen forever | Date |
| Cost | One column, guard, index change | Guard change, a weaker table | One index, a condition in the query | None, but false |

| Criterion (when it opens) | Chosen: in the transaction of the event, and in the review check | Only in the review check | Only in the transaction | Outbox subscriber |
|---|---|---|---|---|
| Valid version has its task right after the event | Yes | Up to one hour later | Yes | After the relay |
| Atomic with the event | Yes | No | Yes | No |
| Tasks lost before this change | Yes, no backfill needed | Yes | No: a backfill in SQL could write neither audit nor notice | No |
| Path `Draft → Withdrawn` | Check | Check | No | Would need the event |
| Cost | One call per event, one query per check | One query | One call per event | New consumer inside `documents` |

| Criterion (the way out) | Chosen: withdrawal also after the date if the job is dead | Allow `newVersion` next to the stuck version | Discard or re-open the frozen version | Fix the values of the frozen version |
|---|---|---|---|---|
| Keeps "at most one approved version" (ADR 0059) | Yes | No: a later retry could publish it over the new one | Yes | Yes |
| Frozen package (ADR 0021) | Kept | Kept | Broken | Broken |
| Reuses who, reason, audit, data rules | Yes | No: nothing records why it was given up | No | No |
| Cost | One condition and the lock in the retry | Changes in publication and retry | New transitions | New privileged path |

| Criterion (what opens the rule) | Chosen: newest job is dead | Any version due longer than N days | A new permission to force a withdrawal | Newest job has failed once |
|---|---|---|---|---|
| Strict rule stays for working publications | Yes | No: a delayed queue would open it | Yes, but only by trust | No: attempts are left |
| Exactly one winner with the retry | Yes, same lock | Needs the same lock | Needs the same lock | Needs the same lock |
| Who can use it | Same people as ADR 0059 | Same | New role decision | Same |

| Criterion (the race with the retry) | Chosen: row lock of the version first | Compare the job's status on update | Lock the job row | Advisory lock |
|---|---|---|---|---|
| Same rule as ADR 0059 | Yes | No | No | No |
| Also excludes a running publication | Yes | No | No | Needs a change there |
| Loser learns the actual outcome | Yes | Partly | Yes | Yes |

## Consequences

### Positive

- The periodic review no longer ends by accident: after a withdrawn or discarded revision the owner has an open task with the same date, in the inbox, with notices, and overdue notices follow.
- The audit history of the first decision stays: the row is untouched, and the audit trail and the task list show what happened in order.
- No approved version can block a document for good once its job is dead, and the document can go on.
- The rule of ADR 0059 stays strict for every publication that can still succeed, and the outcome of a race is the same in the database, the API and the dialog.

### Negative / Cost

- A review date can now have several rows for a version. Everything that reads tasks by version and date has to expect that; today nothing does.
- The first review check after this change opens tasks lost earlier and tells their owners at once: a small burst, possibly with overdue notices.
- A task reopened long after its date can reach the latest stage at once, so the owner gets the notice of the reopening and an escalation can follow within one check interval, without the reminders in between. That is what an overdue review deserves, but the escalation may surprise.
- A task reopened by the check for `Draft → Withdrawn` appears up to one hour late.
- A version with a dead job can be withdrawn although a retry could have worked, for example after a passing database failure (`[database]`). The dialog warns that this cannot be undone, and the reason is on record.
- Records managers and tenant administrators see the failure but cannot withdraw; they are told who can.
- While attempts are left (up to ten with backoff) the withdrawal stays refused: the way out opens only when the job is dead.
- The audit action keeps its name `document.withdrawn-before-valid`, although the date may have passed: the version was never valid, which is what the name is about, and consumers need no change. `publicationFailed` tells the cases apart.
- The retry endpoint holds the version row for its short transaction: a publication job on the same version waits for it.

## Follow-ups

- Must be done: user confirms this ADR (status → Accepted).
- Done in DMS-147 (ADR 0062): a field required in `Published` or `Approved` is checked at submission, where the author can still correct it.
- Done in DMS-147 (ADR 0062): a field required in `Approved` but empty failed inside `completeApproval` without a way out; submission now prevents it.
- Should: a revision moved to another validity scope is published without superseding the reviewed version. That version stays valid, its task stays decided, and its review ends too. Not covered here: the revision took effect.
- Should: tell the approvers of a dead publication; they are among the people who can withdraw, and the notice goes to owner and records management only.
- Later: show the chain of tasks in the evidence report; it lists both tasks but not the link.
- Ruled out: reopening by changing the decided row.

## Reversibility

**medium**: `reopenFor`, the check and the condition in `withdrawApproved` can be removed without touching data, and the retry keeps working with the lock. Rows written with `reopens_id` stay as records; going back to `unique (version_id, due_on)` would need them to be merged, which the guard does not allow. Withdrawn versions stay withdrawn (ADR 0059).
