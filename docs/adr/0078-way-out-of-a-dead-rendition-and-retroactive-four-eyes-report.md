# 0078: A version whose rendition is dead can be withdrawn in approval; past breaches of the four-eyes rule are reported, not undone

| Field | Value |
|---|---|
| Status | Proposed (2026-11-06, implemented in DMS-163) |
| Date | 2026-11-06 |
| Decided by | Philippe |

## Context

DMS-163 collects the follow-ups of ADR 0075, which widened the four-eyes rule from the author to everyone who changed a version.

**The stuck case.** A case in `Rendering` whose last approver is an editor cannot finish. `completeApproval` is refused inside the rendering job, in every attempt, until the job is dead. The version stays `InApproval`, the case `Rendering`. No task is open, so nobody can reject. `withdrawApproved` only takes an approved version. A retry cannot help, because the list of editors is append-only. ADR 0061 (decision 13) and ADR 0062 described the same dead end for other refusals, and ADR 0063 (decision 8) accepted it because there was no production data. The rollout of ADR 0075 is the first time it can happen for a reason nobody can correct.

Given: ADR 0061 opens the withdrawal when the newest publication job of an approved version is dead, with who, reason, audit and lock of ADR 0059. ADR 0058 and 0064 tell owner, approvers (publication only), deputies and records managers of a dead job, remind them, and every failure code has a reason. A rendering job has one row per version (`rendition:<versionId>`). `Versioning.startingPoint` already falls back to a version withdrawn before it took effect if the document has no valid one. Only `dms.job_retry` revives a job.

**The report.** Business concept 2.4: "Verletzungen werden nicht nur verhindert, sondern bei Regeländerungen rückwirkend geprüft und berichtet." ADR 0075 left approvals by editors valid and gave a query. Review votes of editors in a running round stand until the version is returned.

**What "four-eyes off" means.** Business concept 2.4 calls the rule configurable per class ("konfigurierbar je Klasse, Standard: aktiv"). V9, V14, V53 and `Policy` have never let an author approve, whatever the class says. See decision 12.

## Decision

### The way out

1. **The dead job opens the withdrawal, as in ADR 0061.** `POST …/versions/{id}/withdraw` also takes a version in approval whose rendering job is dead (`PublicationJobs.isRenditionDead`). Same endpoint, same rights (`Withdraw`, decided as if the version were approved: the owner role, or having approved it, also as deputy), same reason, audit action, event and notice. The audit entry and the event say `renditionFailed`. Every other version in approval keeps 409 `version_not_approved`.
2. **It holds for every dead rendition, not only a refusal.** The condition is the state of the job, as in ADR 0061. A rendition that is `not_archivable` for good had no way out either, and a separate rule per code would only move the question.
3. **A new lifecycle transition.** `Lifecycle` gets `(InApproval, Withdraw) -> Withdrawn`. `changeStatus` refuses it, like the withdrawal of an approved version: only `withdrawApproved` takes it, with a reason.
4. **The database agrees (V55).** `document_version_guard` allows `InApproval -> Withdrawn` only with a dead rendering job, `document_version_review_guard` asks for the reason. `withdrawal_reason` keeps its meaning "never in force", so reading rights, the marking on paper and the starting point of a new version work unchanged (ADR 0059, 0070, 0072).
5. **The case ends.** `ApprovalWorkflow.closeWithdrawn` moves the case from `Rendering` to the new status `Withdrawn` in the same transaction, audited as `workflow.withdrawn`. The votes stay as a record; there is no approval and no rendition. Order of locks: version, then case, as in the rendering job.
6. **The race with a retry.** The retry of a rendition now takes the row lock of the version first and reads status and job after it (ADR 0061, decision 10). If the retry wins, the withdrawal gets 409 `version_not_approved`. If the withdrawal wins, the retry gets 409 `rendition_not_failed`, and the job stays dead as a record. A zombie handler finds the version withdrawn and fails on `attachRendition`.
7. **The refusal has its own failure code.** `finishApproval` checks the four-eyes rule itself, for the approver and the person they stood in for, and fails with `WorkflowError.FourEyes`. `RenderingError.Refused` carries it as `[segregation_of_duties]`, the code of the vote (ADR 0075, decision 11). The reason table has a text (ADR 0064, decision 8).
8. **A retry that cannot help is not offered.** With that code `canRetry` is false in the status and the list, and the retry answers 409 `rendition_not_failed`.
9. **Who is told.** The notice of ADR 0058 and 0064 stays: owner, deputies and records managers (or the administrators), then the reminders on days 3 and 7. Dead renditions now say what the way out is: `canWithdraw` is computed for them too, and with the code `segregation_of_duties` the text names only the withdrawal. Records managers cannot withdraw and are told who can (ADR 0061, decision 12). The notice `publication.withdrawn` says the archive PDF could not be produced.

### The report

10. **`GET /api/v1/compliance/four-eyes-findings`, listed in the cockpit.** One row per approval or approving vote that stands although the person is on the list of editors of the version: `Approval` (from `released_by`, or a principal in `workflow_approval.acting_for`), `ApprovalVote`, `ReviewVote`. A vote that led to the approval of the same person counts once, as the approval. Invalidated votes are no findings. A review vote only counts if the case had the four-eyes rule on: review steps follow the setting, approval steps do not (ADR 0075, decision 6). The row says when the person first changed the version and whether the case is still open.
11. **Report, do not touch.** `Policy`: an active compliance officer assignment for the cockpit, then `MonitorCompliance` per version, so scope and clearance decide as in ADR 0036. Reading it is recorded as `compliance.four-eyes-findings-viewed` with the number of findings, because it names people (business concept 10.2). The cockpit loads it only when the officer opens it. Nothing is marked or stored: the report reads recorded facts on request, and it stops growing by itself, because workflow and database refuse new cases.

    **Review votes of editors are reported, not invalidated at deployment.** This is the conservative option named in DMS-163: invalidating votes in a migration changes running rounds without a person deciding it, cannot notify anyone, and could reset cases nobody asked to reset. The report shows them with `caseOpen`, the compliance officer decides, and a return ends them as ADR 0075 says.

    **The list leaves as CSV through the export of the cockpit (ADR 0077).** `POST /api/v1/compliance/exports/four-eyes-findings` takes the same findings with the same rights, builds the file with the formula-safe cells of `ComplianceCsv`, adds display names next to the user ids, and is recorded as `compliance.list-exported`. It has no filters, as the list has none.

### What stays as it is

12. **Four-eyes off stays as it was, and is a question for the owner of the concept.** Nothing changes in this ADR. The question: does "konfigurierbar je Klasse" mean an author may approve their own version if a class says so, or only that review steps may include them? Today a class with the rule off still cannot let an author or editor approve, and the task waits (ADR 0075, decision 6). The answer is yours to give, see follow-ups.

### Interface

13. **Warning before the first change.** `DocumentView.changeCostsVote` is true for someone who may change the open draft, does not count as an author yet, and voted in a step that excludes authors, or holds an open task of such a step, as candidate or deputy (`Rules.changeCostsVote`). The document page says what the first change costs. A notice on the page, not a dialog at saving: a draft changes in many places (text, values, change summary, attachments, deviation requests, file), and the notice is there before any of them. A draft has no open task in the regular flow (ADR 0075), so the case that matters is the voter after a return.
14. **Editors are shown.** `DocumentVersionView.editors` lists them, and the version history names everyone besides the person who started the version.
15. **Evidence report: optional field, same format.** `EvidenceVersion.editors` is present only if someone besides the creator changed the version, and the PDF shows it in the facts of the version. The editors are in `personIds`, so they appear by name and in the table of persons (ADR 0077). `dms.evidence-report/2` stays: optional fields were added the same way for attachments, the original, destruction and withdrawal, older reports parse, and a report stores its own bytes and checksum.
16. **The vote endpoint documents 403 `segregation_of_duties`.**

Flyway V55. New dependency between modules: `documents-core` on `rendering-api`, for the job key, which the module rule allows.

## Alternatives considered

| Topic | Option | Difference | Positive | Negative |
|---|---|---|---|---|
| Way out | **Withdrawal, as ADR 0061 (chosen)** | Version ends as `Withdrawn`, the document can start a new version | Reuses who, reason, audit, notice, lock and dialog; nothing frozen moves; everyone involved knows the path | The new version starts from the valid one if there is one, so the text of the stuck version has to be taken over by hand; a person must decide |
| | Return it to the author (`Reject`) | Case `Returned`, version `Draft` | Text stays; `voterEdited` resets the round | The job's key is per version, so a second approval would find the dead job and run with the old approver: needs a new job per round, and a change to what `dms.job_retry` and both status endpoints read; a platform decision to undo votes |
| | Replace the approver: open the last step again | A new task for someone else | Nearest to what the case needs | The process instance is gone; needs a new way to re-enter Flowable and to re-request a rendition |
| | Let it pass, because the vote was valid when given | `completeApproval` ignores the list for such votes | No person needed | Breaks the rule the ADR 0075 is about, and needs an exception in `Lifecycle` and two triggers |
| | Fix it in V55 for all such cases | A migration ends them | No person needed | Cannot notify or audit (ADR 0017); nobody chooses |
| Scope | **Every dead rendition (chosen)** | State of the job | One rule, as ADR 0061; closes the gap of ADR 0061, decision 13 | A failure a retry could cure can be withdrawn too; the dialog warns that this cannot be undone |
| | Only `segregation_of_duties` | Code decides | Narrow | Other dead ends stay; a code decides a right |
| Status of the case | **New `Withdrawn` (chosen)** | Own value in the check of V55 | Says what happened | One more value for readers of the status |
| | Reuse `Discarded` | No migration of the check | Less | Says a draft was discarded (ADR 0050); wrong in the evidence report |
| | Leave `Rendering` | Nothing | Nothing to do | A case waits for something that never comes |
| Retry after a refusal | **Refused and hidden (chosen)** | `canRetry` false, 409 | Promises nothing that cannot work | One more condition |
| | Left as for other failures | Nothing | Less code | Ten more attempts and a new notice for nothing |
| Votes of editors | **Report them (chosen)** | Report of point 2 | Conservative; a person decides; nothing changes in running cases | Such a vote can still count towards an approval until someone acts |
| | Invalidate them in V55 | `invalidated_at` set by the migration | The rule holds at once | No notice or audit; resets rounds nobody asked to reset; the task would have to be reopened in the engine |
| Report | **Own endpoint on recorded facts (chosen)** | Reads list, versions and votes on request | Exact; no new table; same decisions as the cockpit | Reads all votes of the tenant at each opening |
| | A stored list filled by a job or V55 | Table of findings | Status per finding possible | Only needed once someone has to close findings; nobody asked for that |
| | Check on every rule change, generally | A framework for all rules | Answers 2.4 in full | Nothing else needs it yet: the other rules of 2.4 did not change |
| Who reads it | **Compliance officers (chosen)** | As the cockpit | Business concept 2.2; scope and clearance decided already | Auditors see it only by a compliance officer or the query |
| | Also auditors | One more role | Revision has it directly | Not asked for; ADR 0036 kept the cockpit from them |
| Warning | **Notice on the page, from the server (chosen)** | `changeCostsVote` | Covers every kind of change; the rule stays in `Rules` | A person who ignores it is not stopped |
| | Dialog before the first save | Asks to confirm | Hard to miss | Has to sit in every path that changes a draft; collides with the input safety of DMS-165 |
| Evidence format | **Optional field, same format (chosen)** | `editors` only if there are any | Precedent of format 1; old reports untouched | Readers of the format must tolerate one more field |
| | New format version | Format number up | Strict | Bumps for every addition; nothing about the checksum changes |

## Consequences

### Positive

- No version can stay in approval for good: a dead rendition has a way out, whatever killed it.
- The rollout of ADR 0075 needs no manual database work for cases in `Rendering`.
- The refusal is named as what it is, and nobody is sent to a retry that cannot work.
- The concept's retroactive check exists for this rule change, in the place compliance officers already use.
- People see what a change costs them before they make it, and who changed a version is visible on the page and in the evidence.

### Negative / Cost

- The way out only opens when the job is dead: up to ten attempts with backoff, about an hour and a half. Until then people see `Retrying` and the reason.
- The way out ends the version. If the document has a valid version, the next one starts from that, and the text of the stuck version has to be taken over from the withdrawn version by hand. The withdrawn version stays readable for those who could read it.
- Someone who is refused as last approver may still withdraw the version: they have the right from their vote. Withdrawing approves nothing and the reason is on record, so this is accepted.
- A withdrawal after a passing failure of a rendition is possible although a retry would have worked (as ADR 0061).
- A vote of an editor in a running round can still count until a person acts on the report. If that vote was the last one, the case ends as above; if not, the approval goes on with the editor's review vote behind it.
- The report reads all votes of the tenant at each opening. Normally that is cheap; a tenant with millions of votes would need an index.
- The report names people, and reading it leaves an audit entry per opening.
- A retry of a rendition now holds the version row for its short transaction.
- ADR 0063, decision 8 is out of date: such versions now have a way out.

## Follow-ups

- Must be done: user confirms this ADR (status → Accepted).
- Must decide: what "four-eyes off" means for an author who approves (decision 12). Either the setting never reaches approval steps, then the concept needs a sentence; or it does, then `Policy`, `Lifecycle`, V9, V14 and V53 need the setting.
- Should: a compliance officer may want to act on a finding (ask for a return, record that it was looked at). Today the report only shows.
- Later: a return from a dead rendition that keeps the text. It needs a rendering job per round.
- Later: fail the job at once for a refusal. A handler cannot end a job early today, and the attempts cost little: the rendition is deterministic.
- Ruled out: invalidating votes or approvals in a migration.

## Reversibility

**medium**: The report, the warning, the texts and the failure code are code and can go without touching data. The transition can be closed again by restoring the guard of V41 in a new migration and removing the condition in `withdrawApproved`. Versions already withdrawn stay withdrawn, and cases in status `Withdrawn` stay, so the value has to remain in the check. The optional field stays in reports already issued.
