# 0063: Remaining gaps after the check at submission: system fields, withdrawal, revisions in another scope, approvers, evidence

| Field | Value |
|---|---|
| Status | Proposed (2026-11-05, implemented in DMS-148) |
| Date | 2026-11-05 |
| Decided by | Philippe |

## Context

DMS-148 collects what ADR 0061 and ADR 0062 left open. Each point is small, and all of them come from one fact: after submission values are frozen, and a version can only move on or stop.

- ADR 0062 skips `System` fields. That also skips a field the platform never sets, such as `documentNumber` in a class without a number range that requires it.
- A class can name `Withdrawn` in `requiredIn`. `checkTransition` then refuses to withdraw a valid version whose field is empty, and nobody can fill it in. ADR 0059 removed this only for approved versions.
- Publication replaces the valid version of the same scope only. A revision moved to another scope takes effect, the reviewed version stays valid, and its task stays decided as `Revised`. No open task exists for its review date, so its periodic review ends without notice. ADR 0061 reopens a task only if the revision never took effect.
- Whoever approved a version may withdraw it (ADR 0059), but the notice of a dead publication goes to owner and records management only (ADR 0058).
- `reopens_id` links a reopened task to the decided one, but the evidence report lists both tasks without the link.

## Decision

1. **Submission skips only what the platform still sets.** `CoreFields.SetAfterReview` names those fields (`renditionChecksum`). Any other system field needed later and empty blocks the submission. The document number can be set by hand, so the author has a way forward. The form marks stay as they are: authors do not enter these fields in the form.
2. **Stopping a submitted version never depends on its values.** Every `Withdraw` outside `Draft` skips the check, not only the withdrawal of an approved version. A draft is still checked: its author can add the value.
3. **A revision that did not replace the version opens the task again.** `lostToRevision` now also finds a revision that took effect, as long as the reviewed version is still valid with the same review date. A revision published as usual supersedes the version, so it stays excluded without a rule of its own. The publication calls `reopenFor` in its transaction, and the periodic check picks up tasks from before this change. Migration V43 widens the guard of V42 in the same way.
4. **The mail says why.** `review.task-reopened` names the other scope when the revision took effect.
5. **Approvers hear of a dead publication.** Failure notices of a publication also go to everyone who approved the version, as `approversOf` finds them. Each person gets a notice of the audience `owner` with their own `canRetry` and `canWithdraw`, so the text promises nothing they cannot do. Someone also in records management gets the list, as before. Rendition failures are unchanged: nobody has approved yet.
6. **The evidence report shows the chain.** `EvidenceReview.reopens` carries the link, and the table names the date of the decided task.
7. **No class editor text.** The interface has no class editor. The rule from ADR 0062 goes into the API description of `requiredIn`.
8. **No way out for versions already in approval at rollout.** There is no production data. Such a version can only exist in test environments, where it can be rejected before the last vote.

## Alternatives considered

| Topic | Option | Difference | Positive | Negative |
|---|---|---|---|---|
| Revision in another scope | **Reopen the task (chosen)** | New row, as in ADR 0061 | Same mechanism, the first decision stays, the owner is told | Guard gets wider, one more mail |
| | Refuse a scope change in a revision | Blocks it in the draft | Simple | Moving a document to another scope is legitimate; the owner would have to start over |
| | Set a new review date | Date moves on without a task | No new task | Skips the owner's decision on a valid version |
| | Supersede the reviewed version too | Publication replaces across scopes | Ends the question | Removes a valid version from a scope nobody decided on (business concept 5.2) |
| `Withdrawn` in `requiredIn` | **Ignore it for submitted versions (chosen)** | Withdrawal never fails on values | A way out always works | The setting has no effect after submission |
| | Check it at submission (as ADR 0062) | Requires the value early | Setting stays meaningful | Asks a reason-like field of every draft; most never get withdrawn |
| | Refuse the setting | Class validation | Clear | Existing class versions stay |
| Approvers | **Personal notice with own rights (chosen)** | Per person | Text is accurate | One notice per person |
| | One shared notice | One text | Simple | Would promise a retry approvers may not have |
| | Final approver only | `releasedBy` | No workflow lookup | Leaves out others who may withdraw |

## Consequences

Positive:

- A valid version keeps its periodic review whatever happens to the revision.
- A withdrawal cannot be blocked by a class setting.
- Everyone who can act on a dead publication learns of it.
- The evidence report explains why a version has two tasks for one date.

Negative:

- The owner decides again after a revision for another scope, even if the old scope was meant to stay as it is. That is one more decision, not a lost one.
- `Withdrawn` in `requiredIn` no longer has any effect after submission. The API description says so.
- Failure notices of a publication read the workflow cases of the document, one more query per dead job.
- A revision that took effect and was later withdrawn is reported by the periodic check with the text for a withdrawn revision ("never took effect"). This only affects tasks decided before this change.

## Follow-ups

- Must be done: user confirms this ADR (status → Accepted).

## Reversibility

Medium. Points 1, 2, 5, 6 and 7 are code only. Point 3 adds rows to an append-only table: the guard of V42 can be restored with a new migration, and rows already written stay as a record of a decision that was asked for again.
