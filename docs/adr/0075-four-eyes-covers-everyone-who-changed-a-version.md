# 0075: The four-eyes rule covers everyone who changed a version, read from a recorded list per version

| Field | Value |
|---|---|
| Status | Proposed (2026-11-06, implemented in DMS-159) |
| Date | 2026-11-06 |
| Decided by | Philippe |

## Context

A security review found that the four-eyes rule could be bypassed. `EditDraft` is open to every author in scope, but the rule only compared a voter with `created_by` of the version, and `updateDraft` keeps `created_by`. Someone holding Author and Approver in one scope could rewrite a colleague's draft, have it submitted and approve their own text. The same worked for review lanes and for the owner's confirmation of a minor version. Business concept US-04.2 asks for technical enforcement ("damit das Vier-Augen-Prinzip technisch erzwungen wird"), and 2.4 says author and approver of a version must differ. Templates already solve this with `contributors` in `Policy`.

Who changed a version could not be read from the data as it stood:

- `dms.document_working_state.saved_by` names the person of each state, but compaction (V33, `RetentionMaintenance`) removes states of open versions too. The person who wrote the text can be gone from there while the version is still under review.
- Change summaries, deviation requests and attachments change a draft without a working state.
- The audit trail names everyone, but finding someone there means reading JSON details of every entry of a document, on every task list.

A version only changes as a draft, and a draft has no open task: a return cancels the tasks of the round. So "someone edits after being assigned" happens in one way only: tasks that were assigned before the list existed.

## Decision

1. **`dms.document_version_editor` records everyone who changed a version**, one row per version and person, append-only (`reject_modification`), with RLS and `select, insert` for `dms_app` (V53). The list belongs to the version and grows over all rounds, so edits after a return in an earlier round count. `DocumentVersion.editors` carries it, `authors` is `editors + createdBy`; `ReviewSubject` has the same.
2. **Every change of a draft counts.** The concept does not limit the rule to the text, so this is the conservative reading: a value decides what a document says as well as a sentence does, and metadata names the owner, the validity and the confidentiality. It covers: creating the version, saving the draft (text, values, title, owner), a section edit with a deviation request (DMS-154), recording or withdrawing a deviation request, the change summary, attachments, the uploaded file and its Word values, a number assigned to the draft, and the retention rule refreshed into the draft. `DocumentStore.insertWorkingState` records the person itself, so a new kind of working state cannot forget it.
3. **Not counted:** comments, votes, submitting without a change, and the deviation report recomputed at submission. They change nothing a person wrote. An unchanged change summary is no change either; a save with unchanged values is, because it is a working state.
4. **The list is per version, not per document.** Whoever wrote 1.0 may review 2.0: they did not write the change. Template versions keep their `editedBy` per version the same way. A carried-over request, attachment or file does not make its author an editor of the new version.
5. **Editors count like the author everywhere the rule is checked:** participants (`Rules.eligible`), the vote (`participation`), deputies (`Rules.taskDeputies`, `Rules.standsIn`: neither deputy nor principal), the owner's confirmation of a minor version (`Rules.ownerConfirmation`: the holders of the deputy role take it), `Policy` for `Approve` through `Resource.contributors`, `Lifecycle.approve` and the database.
6. **The four-eyes setting of a class works as it did for the author:** review steps follow it, approval steps exclude authors and editors whatever it says, because policy, lifecycle and database refuse such an approval in any case (ADR 0021). `Rules.excludesAuthors` is that one rule. New is that an approval task is no longer given to someone who could never complete it: with the rule off, such a task now waits and tells the administrators, also for the author, where it stayed open with nobody able to act before.
7. **A standing vote does not survive its voter changing the version.** After a return, a change that leaves the checksum as it is (a change summary, a save of the same values) would resume at the returning step and keep the vote. If an approving vote that stands was cast by or for someone who is now an editor, the round starts at the reset step and all votes of the case become invalid, like after a content change (`Rules.voterEdited`, audit `workflow.reset` with `voterEdited`).
8. **Tasks assigned before the list existed move on.** The vote refuses such a person at once. The deadline check (every 15 minutes per tenant) calls `ApprovalWorkflow.excludeEditors`, which resolves such tasks again, as the platform. The next eligible person takes the task; with nobody left it is `Waiting` and the tenant administrators get `workflow.participant-missing`, as US-04.2 asks ("der Workflow bleibt stehen"). The check reads task rows and the editor list only, not versions.
9. **The database refuses it too**, next to the checks of V9 and V14: triggers on `document_version` (`released_by`) and on `workflow_approval` (`approved_by`, `acting_for`). Like those checks they do not look at the four-eyes setting.
10. **V53 fills the list** from `created_by`, the working states that are left, deviation requests, attachments, files, and the audit trail for what compaction removed or never was a working state. Entries taken over from a previous version (`based_on`) do not count.
11. **The refusal has its own code.** `WorkflowError.FourEyes` gives 403 `segregation_of_duties`, the code templates and the lifecycle already use, and the vote dialog says why in German and English, in the words of the template message. The API asks the workflow before the policy, because the policy's general refusal would hide the reason from someone who is no candidate.

## Alternatives considered

Where the history comes from:

| | Recorded list per version (chosen) | `saved_by` of the working states | The audit trail | Only the owner of a draft edits it |
|---|---|---|---|---|
| Difference | Own append-only table, written with every change | No new table, read what is there | No new table, query the entries of a document | `EditDraft` limited to `created_by` plus an explicit hand-over |
| Positive | Cannot be lost; one indexed read; covers changes without a working state; the database can check against it | Nothing to migrate | Complete and tamper-evident | No list needed |
| Negative | One more write per change, a backfill | Compaction removes states of open versions; no change summary, no deviation request, no attachment | JSON details per entry on every read; the audit module is no source for business rules; the database cannot check it cheaply | Takes away a working mode the concept wants (several authors, the owner correcting a draft); the hand-over needs its own rules |

How the four-eyes setting treats editors:

| | Like the author (chosen) | Editors only count while the rule is on |
|---|---|---|
| Difference | Approval steps exclude editors whatever the setting | With the rule off an editor may approve |
| Positive | One rule; the database needs no knowledge of the workflow; conservative | A two-person team with the rule off keeps working as before if the second person edited |
| Negative | With the rule off, a team where everyone edited has nobody to approve: the task waits | Policy, lifecycle and database would each need the setting; the editor would be treated better than the author |

How tasks of editors are cleared:

| | Check in the deadline job (chosen) | In migration V53 | When the person tries to vote |
|---|---|---|---|
| Difference | Runs every 15 minutes per tenant | One update of the candidates in SQL | The refused vote re-resolves the task |
| Positive | Uses the existing path with notification and audit; also catches anything else that leaves a stale candidate | Done at once | No job |
| Negative | Up to 15 minutes delay; a small query per run for something that is normally empty | SQL would have to rebuild the notification; no audit entry | The refused vote rolls back its transaction; a person who never tries leaves the task hanging |

## Consequences

### Positive

- The bypass is closed in every layer that checks the author, down to the database.
- Who took part in writing a version is known for good, whatever compaction does.
- Admins see a waiting task instead of a task nobody can complete, also with the four-eyes rule off.

### Negative / Cost

- Conservative counting has a price: an approver who fixes a typo, or a records manager who sets a retention rule on an open draft, cannot vote on that version. The submitter becomes an editor if the submission refreshes the retention rule. A small team can reach a task nobody may take; the administrators are told and someone with the role has to be found.
- The list names people for as long as the version row exists, also after destruction, like votes and approvals do.
- Every read of versions takes one more query, a fixed number per list.
- With the rule off, a task that used to be open for the author now waits (decision 6).
- A case that was `Rendering` at deployment with an editor as last approver cannot finish: the lifecycle refuses, the rendering job fails and the usual failure notice goes out. This is the rule working, but it needs a person: withdraw or reject is not possible from there, see follow-ups.
- Approvals already given by someone who changed the version stay valid: V53 does not touch released versions. A review vote of such a person in a round running at deployment also stands until the version is returned.

## Follow-ups

- Done in DMS-163 (ADR 0078): the report of past violations, with the review votes of editors. Business concept 2.4 asks that rule changes are checked retroactively and reported. Until there is a report, this query lists them: `select v.document_id, v.id, v.released_by from dms.document_version v join dms.document_version_editor e on e.version_id = v.id and e.user_id = v.released_by where v.released_by is not null;`.
- Done in DMS-163 (ADR 0078): a way out for a `Rendering` case whose approver is refused (see Negative / Cost).
- Open, recorded in ADR 0078, decision 12: decide whether the four-eyes setting should allow an author to approve at all: business concept 2.4 calls the rule configurable, V9 and `Policy` have never let an author approve.
- Done in DMS-163 (ADR 0078): warn before the first change that it takes away the right to vote on this version, and show the editors in the version history and the evidence report.
- `Policy` grants `EditDraft` to assigned reviewers in review, but `updateDraft` refuses every status except `Draft`. If reviewers ever get to suggest changes, those changes have to be recorded here and the rule applies to them.

## Reversibility

Medium. The checks are plain code and two triggers, and can be removed in one change without touching data: back to comparing `created_by`. The table can stay unused or be dropped. Counting less than every change is a change in a few call sites, but it only takes effect for changes made from then on, because the list does not say what kind of change a person made. Keeping the list running from now on keeps both ways open, which is why it records people and not decisions.
