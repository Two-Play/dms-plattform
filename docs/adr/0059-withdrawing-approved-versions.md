# 0059: Withdrawing an approved version before it takes effect: the row lock decides the race with the publication, the version stays a record

| Field | Value |
|---|---|
| Status | Proposed (2026-11-04, implemented in DMS-143) |
| Date | 2026-11-04 |
| Decided by | Philippe |

## Context

DMS-143 (part of DMS-92, release blocker): an approved version with a future valid-from date cannot be stopped. It is published at 00:00 of that date (ADR 0026) even if it was approved by mistake. ADR 0026 listed this as a follow-up.

Requirements of the story:

- Authorized people withdraw such a version before its valid-from date, with a required reason, in the UI and the API.
- The scheduled publication does not run afterwards. A race with the job must not leave a half-withdrawn version: exactly one of the two wins, and the loser gets a clear result.
- It is in the audit trail; the previous valid version stays valid.
- A decision for the retention entry, Object Lock data, the rendition and open acknowledgements. The archive PDF stays immutable.
- Withdrawn versions are marked in the version history and do not appear in the library, search or "valid on" results. Readers are not notified of something that never took effect, but the approvers and the owner are.

Given: `Withdrawn` exists as "out of force" for valid versions (US-07.1, ADR 0030) with `withdrawn_at`, and for drafts. Everything that lists versions in force already asks for `published_at` (validity history, current version, webhooks) or for the valid statuses (library, cockpit, acknowledgements). The search index holds only valid and superseded versions. The lifecycle exists twice, in `Lifecycle` and in the guard trigger, and a test compares them. `publishScheduled` locks the version row and does nothing unless it is approved and due. Approval starts the Object Lock job, and a trigger runs it again when a version goes to `Superseded` or `Withdrawn` (ADR 0055). The acknowledgement audience is recorded at publication (ADR 0048). At most one version of a document is approved at a time: `newVersion` refuses while one waits.

## Decision

1. **Same status, new transition.** `Lifecycle` and the guard (migration V41) get `Approved → Withdrawn`. `DocumentService.withdrawApproved` is the only way there: `changeStatus` refuses it, and the guard refuses it without a reason, so that no caller can take the short way.
2. **The withdrawal is part of the version row.** V41 adds `withdrawn_by` and `withdrawal_reason` next to `withdrawn_at`. Status, actor and reason are one `UPDATE`, so a half-withdrawn version cannot exist. A set reason means "approved, never in force" (`DocumentVersion.withdrawal`); versions withdrawn while valid keep their reason in the review task and have neither column set. The guard allows the columns to change only on this transition.
3. **Who may withdraw (ADR 0018).** `Withdraw` on an approved version: holders of the owner role, and holders of the approver role who approved this version (any approving vote of an approval step of its case, or the final approval). This mirrors `Publish`: whoever could put the version into force may stop it. Authors, reviewers, records managers and tenant administrators may not. Scope and confidentiality apply as to every action. An author who holds the owner role may withdraw: stopping a version gives nothing into force, so segregation of duties (which protects approval) does not apply. No step-up sign-in: withdrawing a valid version has none either, and a step-up would slow down the one action that prevents harm.
4. **Until when.** Strictly before 00:00 of the valid-from date in the tenant's time zone (`Publication.isDue`). From then on the publication job has the version, and a withdrawal gets 409 `version_due`. A version without a valid-from date is due at once and cannot be withdrawn.
5. **The race is decided by the row lock of the version.** Both sides lock the row first and read the clock afterwards. Whoever gets the lock changes the version and commits; the other waits and then finds the result. A withdrawal that loses gets 409 `version_published`, a second withdrawal 409 `version_withdrawn`, a version that was never approved 409 `version_not_approved`, all with the code in the API error so the dialog can say what happened. A publication job that loses does nothing and ends successfully.
6. **The job does not run afterwards.** `dms.job_cancel` sets the queued `document.publish` jobs of the version to the new status `cancelled` in the withdrawing transaction, with who withdrew it as `last_error`. A job already claimed cannot be cancelled: it waits for the lock and finds the version withdrawn. `PublicationJobs.tick` only looks at approved versions, and the idempotency key stays taken.
7. **What happens to the data.**
   - *Rendition and archive PDF:* unchanged. The version is frozen from approval on, and the guard refuses any change of the rendition reference. The PDF stays in object storage as it is.
   - *Object Lock and `dms.object_retention`:* kept, never shortened (the guard of V36 refuses it anyway). The existing trigger queues `archive.object-retention` for the withdrawn version, so rules counted from the end of validity count from the withdrawal.
   - *Retention entry:* the version stays a record and appears on the destruction list like every version out of force: from `withdrawn_at`, with the rule of its class, under legal holds as usual. A mistake that was approved is evidence of the process, so it is not removed early.
   - *Acknowledgements:* none can be open. The audience is recorded at publication and readers can acknowledge valid versions only, so nothing has to be closed; a test shows no audience row exists. Acknowledgements of the valid version are untouched.
   - *Review date:* none, it is set at publication.
8. **Audit and events.** Audit action `document.withdrawn-before-valid` with reason, from, to and version. Outbox: the usual `document.status-changed` (the search index syncs; webhooks announce only withdrawals of valid versions, so nothing reaches connected systems) and `document.approved-version-withdrawn` with the recipients.
9. **Notifications.** Kind `publication.withdrawn` (category publication) to the approvers and the document's owner, without whoever withdrew it. Readers get nothing. The API collects the approvers from the workflow, because `documents` must not depend on it, and passes them to the service. The reason is shown only to recipients who may read the document, like the title (ADR 0052).
10. **Who still reads the version.** `ReadDocument` on a version with a withdrawal: those who could read it while it was approved (author, owner role, assigned reviewers and approvers, whoever approved it), plus records managers and auditors. Without this the approvers would be notified without a title, and the author could not see what to correct. Readers never see it. Other withdrawn versions stay as they were. The attribute is `Resource.withdrawnBeforeValid`; the search filter needs no rule because these versions are not indexed.
11. **What the document looks like afterwards.** The current version of `GET /documents/{id}` and of the list is the open one, else the latest one that was not withdrawn before taking effect. New versions start from the valid version. If there is none (a first version approved by mistake), they start from the withdrawn version (`Versioning.startingPoint`), otherwise the document would be stuck. Numbers are not given again: after a withdrawn 2.0 follow 3.0 or 2.1 (`DocumentView.latestReleasedVersion` lets the UI say so). The editorial comparison ignores the withdrawn version.
   Without a valid version only a major version can be started: a minor version needs just the owner's confirmation, and nothing may first take effect that way.
12. **API and UI.** `POST /api/v1/documents/{id}/versions/{versionId}/withdraw` `{reason}` (required, at most 4000 characters). `DocumentView.withdrawable` names the version the caller may withdraw now, with what stays valid. The document page has a dialog with a required reason, the version history shows "Withdrawn, never valid" with date, person and reason.

## Alternatives considered

| Criterion (how the version ends) | Chosen: `Approved → Withdrawn` | New status, e.g. `Revoked` | Back to `Draft` | Move the valid-from date far ahead |
|---|---|---|---|---|
| Readers and lists exclude it | Yes, they all ask for `published_at` or valid statuses | Yes | Yes | Yes, but it stays scheduled |
| Change to status consumers (archive, destruction, retention, webhooks, search, UI) | None: `Withdrawn` is handled everywhere | Every list of statuses in Scala, SQL and TypeScript | None | None |
| Frozen package, approval, rendition stay | Yes | Yes | No: the guards forbid it, and a draft must be editable | Yes |
| Tells "never in force" from "out of force" | By `withdrawal_reason` and `published_at` | By status | n/a | n/a |
| Honest record | Yes | Yes | No: the approval disappears | No: nothing was withdrawn |

| Criterion (where the reason lives) | Chosen: columns of `dms.document_version` | Own table `dms.version_withdrawal` | Audit trail only |
|---|---|---|---|
| One atomic statement, guard can demand it | Yes | Needs a trigger looking at the other table | No |
| Version history can show it | With the row, no query added | One more join or query in every version read | Needs a query of the audit trail, which readers of the history may not see |
| RLS and grants | Inherited, one column grant | New table, policy, grants | None |
| Withdrawals of valid versions included | No (review task has them) | Could be | Yes |

| Criterion (deciding the race) | Chosen: row lock, clock read after it | Optimistic: compare status on update only | Advisory lock per version | Delete the job and rely on that |
|---|---|---|---|---|
| Exactly one winner | Yes | Yes, but the loser fails only at the end, after writing audit and outbox in the rolled back transaction | Yes | No: a claimed job still runs |
| Works with the existing job handler | Yes, it locks already | Yes | Needs a change on both sides | No |
| Loser learns the actual outcome | Yes, reads the committed row | Only "changed concurrently" | Yes | No |

| Criterion (the queued job) | Chosen: status `cancelled` in the same transaction, handler stays guarded | Leave it, the handler finds nothing to do | Delete the row |
|---|---|---|---|
| "Does not run afterwards" visible in the queue | Yes | No: it runs and does nothing | Yes |
| Trace why | `last_error` | None | None |
| A tick in flight cannot enqueue again | Yes, the idempotency key stays taken | Yes | No |
| Change | One status, one function | None | One function |

| Criterion (who may withdraw) | Chosen: owner role, approvers of this version | Owner and approver roles, as for valid versions | Document's owner only | Also records managers |
|---|---|---|---|---|
| Matches who may read an approved version | Yes | No: an approver who did not approve it cannot read it | Yes | No |
| Matches `Publish` | Yes | No | No | No |
| Someone is available when the owner is away | Yes, the approvers | Yes | No | Yes |
| Business decision stays with those responsible for the content | Yes | Yes | Yes | No: records management looks after records, not content |

## Consequences

### Positive

- A version approved by mistake can be stopped until the last moment, and the outcome of a race is the same in the database, the API and the dialog.
- No consumer of statuses changes: library, "valid on", current version, compliance cockpit, acknowledgements, webhooks and search keep excluding the version, by the rules they had.
- The approval stays visible: audit trail, evidence report, version history and the archived PDF show what was approved and that it was stopped.

### Negative / Cost

- After 00:00 of the valid-from date a version can only be withdrawn as a valid version, through a review task. If the publication job is dead, a due version stays approved and cannot be withdrawn until the job runs again.
- A withdrawn version keeps its objects for the whole retention. Object Lock leaves no other way anyway.
- The withdrawal is final. Correcting the mistake needs a new version and, for a valid document, its approval.
- Version numbers get gaps: 2.0 was approved but never valid, and a minor correction of 1.0 afterwards is called 2.1. Reusing the number would give two approved packages the same label.
- When a valid version exists, the next version starts from it, so changes made in the withdrawn version have to be made again. The comparison with the withdrawn version helps.
- A review task decided as "revise" points to the new version. If that version is withdrawn, the task stays decided, and the valid version has no open review task for its review date. Discarded drafts have the same gap (ADR 0050).
- The workflow case stays `Approved`: it says what happened in the workflow, the version says what happened since.
- On-demand copies and filled Word documents name the status "Withdrawn"/"Außer Kraft" like for a version taken out of force.

## Follow-ups

- Must be done (DMS-145): a review task whose revision was withdrawn or discarded opens again for the valid version.
- Must be done (DMS-145): a way out for a due version whose publication can never succeed, for example because a field required from `Published` is empty in the frozen values: it can neither be withdrawn nor replaced, and a retry fails the same way (DMS-142 tells the owner about the failure).
- Should (DMS-145): decide rights on the valid version, not on the latest one, in `RetentionApi`, `LegalHoldApi` and `EvidenceReportApi`. A never-valid version with wrong metadata stays the latest one, which can lock out records managers scoped to the valid version.
- Should: tell the author as well; the story names approvers and owner, but the author was told about the approval.
- Should: the withdrawal in the evidence report's version section (it is in the report's audit trail today).
- Should: "never in force" wording for on-demand copies.

## Reversibility

**medium**: The transition, the columns and `dms.job_cancel` are additive; removing the action needs one check in the service and leaves withdrawn versions as records. The data of withdrawn versions cannot be turned back into approved versions: the guard does not allow leaving `Withdrawn`, and that is intended.
