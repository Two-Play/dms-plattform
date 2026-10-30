# 0050: Discarding drafts: a terminal version status instead of deletion, files released unless held, numbers stay reserved

| Field | Value |
|---|---|
| Status | Proposed (2026-10-30, implemented in DMS-129) |
| Date | 2026-10-30 |
| Decided by | Philippe |

## Context

DMS-129: an author wants to get rid of a draft again, e.g. one created by accident. There was no way to delete or discard a draft: a document and its versions are append-only (ADR 0021: guard trigger, "no deletes"), every change is in the audit hash chain (ADR 0017), and other rows point at documents and versions by foreign key (working states, deviation requests, workflow cases and tasks, review tasks, attachment links and files, legal hold memberships, number range counters).

Requirements of the story:

- "Discard draft" on the document page with a confirmation (optional reason), for drafts that are not submitted; allowed for the draft's author, the owner and tenant administrators, as an explicit action of the permission matrix (ADR 0018).
- A first version (never approved): the document disappears from lists, search, library, task inbox and dashboard; the audit trail keeps who discarded it, when and why. Pending workflow tasks of the draft are cancelled.
- A new version of an approved document: only the draft goes; the valid version stays untouched and a new version can be started again.
- Attachment links (ADR 0041) and the original of a file document (ADR 0042) of the draft are removed; files no longer referenced are released for clean-up, with a decision for legal holds (ADR 0031); no bytes of other versions or documents are affected.
- A document number already assigned (ADR 0040, gap-free ranges) needs a decision.
- Submitted, approved and published versions cannot be discarded (409): withdrawal and destruction exist for them.

Given: the lifecycle exists twice, in `Lifecycle` and in the guard trigger, and a test compares the two. `Draft → Withdrawn` exists in both, but `Withdrawn` means "out of force" for versions that were valid (current-version resolution, webhooks `document.withdrawn`, readable for owners, records managers and auditors, archived and destroyed later). Drafts are never in the search index (it holds Published, ReviewDue and Superseded versions, ADR 0029) and never in the library (valid versions, ADR 0043). A draft has no open workflow tasks in the regular flow: it was either never submitted or returned, and a return cancels the other tasks and leaves the case `Returned`, waiting for a resubmission.

## Decision

1. **Soft delete as terminal status `Discarded`** (migration V31). `Lifecycle` gets the event `Discard` with the single transition `Draft → Discarded`; the guard trigger allows exactly that pair in addition (the existing guard test now compares V31 with the lifecycle). `Discarded` is frozen like every status outside the open ones and terminal: never valid, never archived or destroyed. The version number is given back like a withdrawn draft's (`document_version_number` excludes `Discarded`), so the next draft of the same number can be started; working states continue counting after the discarded draft's (`2.0-draft.2` after `2.0-draft.1`). Nobody reads a discarded version (`ReadDocument` denies it like `Destroyed`). The document row is not marked: a document **is discarded when all its versions are** (`DocumentDetails.discarded`); such documents are left out of the document list, the API answers 404 for them (document, versions), and the number backfill (DMS-116) skips them. Search and library need no change: drafts are never indexed or listed there.
2. **Authorization:** new action `DiscardDraft`, only in status `Draft`: the version's author holding the author or owner role, the holder of the owner role who is the document's owner, and tenant administrators (clean-up without reading content; they use the API, the document page is not readable for them). Matrix row: Author ◐, Owner ◐, Tenant admin ●, everyone else ○. `DocumentView.canDiscardDraft` tells the UI.
3. **Endpoint** `POST /api/v1/documents/{id}/draft/discard` `{reason?}` (at most 4000 characters, 422 otherwise) → `DiscardedDraftView` (whether the document disappeared, the valid version to continue with, counts of removed links, released and kept files and cancelled tasks). 404 for a discarded document, 409 without an open draft or when the open version is submitted (callers who can read it learn that; others get 403).
4. **One transaction** (`DocumentService.discardDraft`, then `ApprovalWorkflow.discard`): document and open version locked; attachment links and the original link removed while the version is still a draft (the link guards allow it only then; V31 lets the original's guard allow a delete in `Draft`); status changed; audit entry `document.discarded` with the version label, the reason (also in the `reason` column), whether the document disappeared, the document number, the removed links, the released or kept files (SHA-256) and the legal hold flag; outbox event `document.discarded`. The workflow case of the version (a returned one) becomes `Discarded` (new case status), its open or waiting tasks are cancelled and a process instance, if any, is deleted (`workflow.discarded`).
5. **Files:** of the files the draft linked (attachments and original), those that no version references any more are deleted from `dms.document_attachment_file` and a job `documents.file-cleanup` per file removes the object version from object storage after the commit (`FileCleanupJobs`). The file table stays immutable otherwise: a new guard allows `DELETE` only for an unreferenced file of a document that is not under legal hold (it locks the document row like the destruction guard of V19) and refuses every `UPDATE`. **Under legal hold the files are kept** (row and object, object legal hold unchanged); discarding the draft itself is still allowed, because nothing is destroyed: the version row, its working states and the audit trail stay. The job checks again before deleting (hold added meanwhile, object referenced again) and keeps objects that Object Lock protects. Files of other versions are never touched: content addressing is per document (ADR 0041), and a file referenced by any other version stays.
6. **Document numbers stay reserved.** The number stays on the discarded document (`dms.document.document_number`, unique per tenant), the counter is not decremented, the number is never handed out again, and the audit entry names it. `GET /documents/by-number/{number}/current` answers 404 (nothing valid), and the gap in the range is explained by the `document.discarded` entry carrying the number.

## Alternatives considered

| Criterion | Chosen: terminal status `Discarded` | Hard delete of document and versions | Reuse `Draft → Withdrawn` |
|---|---|---|---|
| Guard trigger, append-only versions (ADR 0021) | One more allowed transition | Delete paths in the document and version guards and every dependent table (working states, deviation requests, workflow cases, tasks, votes, legal hold rows) | No change |
| Audit trail (ADR 0017) | Entries keep pointing at an existing version row | Entries survive (hash chain is independent of the rows) but name a document and version that no longer exist; evidence reports cannot show them | As chosen |
| Document numbers (ADR 0040) | The number stays on the row: reserved, explainable | The number disappears with the row; the gap is only explainable from the audit trail, and the number could be assigned again | As chosen |
| Meaning of the status | Unambiguous: never valid, never read | – | `Withdrawn` means "out of force after being valid": current-version resolution, webhooks, readability for records managers and auditors, archiving and destruction would all have to tell a discarded draft apart |
| Data protection (content of an accidental draft stays stored) | Content kept, unreadable through the API; destruction remains the records management path | Content gone | Content kept, readable for records managers and auditors |
| Effort | Small: one status, one action, one endpoint | Large and risky (cascade deletes through guarded tables) | Smallest, but every consumer of `Withdrawn` needs a "was it valid?" check |

| Criterion (files) | Chosen: delete unreferenced files unless held, object removed by a job | Keep every file (orphans until a general clean-up, DMS-82) | Delete objects synchronously in the request |
|---|---|---|---|
| Bytes of other versions | Untouched (reference check in SQL and in the trigger) | Untouched | Untouched |
| Storage released | Yes, seconds after the commit | Not until DMS-82 | Yes |
| Consistency with the transaction | Row deleted in the transaction, object after commit (at-least-once job, idempotent) | Trivial | Object deleted before the commit can be lost if the transaction rolls back |
| Legal hold | Kept, checked in the trigger and again by the job | Kept | Needs the same checks |

| Criterion (legal hold) | Chosen: discard allowed, files kept | Discard blocked while held |
|---|---|---|
| Evidence preserved | Yes: version row, working states, audit and all files stay | Yes |
| Author can clean up an accidental draft | Yes | No, until the hold is released (possibly years) |

## Consequences

### Positive

- An accidental draft can be removed in one step by its author, the owner or an administrator; a new version can be started again right away.
- The audit trail, the number range and every foreign key stay consistent: nothing is deleted except attachment links of the draft and files nobody references.
- Storage of files only the draft used is released automatically; legal holds keep everything.
- The lifecycle stays single-sourced: the guard test fails if `Lifecycle` and the trigger diverge.

### Negative / Cost

- The content of a discarded draft stays in the database (version row and working states), only unreadable through the API. Removing it for data protection needs the destruction path (DMS-82) or a compaction of working states.
- Files that became unreferenced earlier (an attachment removed from a draft, a replaced original) are not cleaned up by a discard; that stays the orphan clean-up of DMS-82.
- A periodic review decided as "revision required" (ADR 0030) links the review task to the new draft; if that draft is discarded, the task stays `Revised` and no new review task is opened for the same review date. The owner has to start a new version or the next periodic check must handle it.
- Tenant administrators can discard by API only: the document page does not show drafts to them (they do not read content, ADR 0018).
- The constraint `document_version_check4` of V9 (snapshot required outside open statuses) is replaced by a named constraint; a further status needs the same care.

## Follow-ups

- Should be done: reopen the periodic review task when the draft linked to a `Revised` decision is discarded.
- Should be done (DMS-82): orphan clean-up of files unreferenced for other reasons; destruction or compaction of discarded drafts' content after a retention period.
- Could be done: show discarded drafts with their reason in the audit or evidence views for records managers.
- Ruled out: hard deletion of documents or versions; reusing `Withdrawn` for drafts.

## Reversibility

**medium**: The status, the action and the endpoint can be removed by a migration that forbids the transition again; discarded rows would have to stay (or be mapped to `Withdrawn`). Deleted files and objects cannot be restored, but they were referenced by no version.
