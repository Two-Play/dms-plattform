# 0070: Remaining follow-ups of withdrawn versions: the author is told, a deputy who approved may withdraw while the deputyship stands, paper says "never valid"

| Field | Value |
|---|---|
| Status | Proposed (2026-11-05, implemented in DMS-145) |
| Date | 2026-11-05 |
| Decided by | Philippe |

## Context

DMS-145 collects the follow-ups of ADR 0059. ADR 0061 did the two that had to be done. Four remained, each checked against the code after ADR 0064:

1. **The author is not told.** `withdrawApproved` sends `publication.withdrawn` to approvers and owner. `workflow.approved` goes to author and owner, so the author hears of the approval but not of its end, although they are the one who must correct the version (ADR 0059, decision 10). Still true.
2. **A deputy with a vote cannot withdraw.** `Withdraw` on an approved version asks for the owner role, or the approver role and an approving vote, both in the document's scope. A deputy acts in the principal's scope only on an open task (`Delegation.subject`, ADR 0028, decision 2). The task is done after the vote, so a deputy from another scope has no role for the document any more: `Policy` refuses, and `GET /documents/{id}` as well, so the deputy who approved by mistake cannot even see the version. Their `approvedBy` entry only helped someone who holds the role in that scope anyway. Still true.
3. **Paper does not say "never valid".** The status label is "Außer Kraft" / "Withdrawn" in the change history. The copy's header and footer look like those of any approved version, with the valid-from date that never came. A filled Word file is worse than ADR 0059 says: `WordFilling` prints "Approved" whenever an approval is known, and on-demand fills always know it, so the field `status` says "Freigegeben". The evidence report has "never valid" in the version chain, but who withdrew it and why only in the audit section.
4. **No test end to end.** Search and library exclude these versions by the rules they had, but only `SearchDocument.isIndexed` was asserted. Still true; the new tests found no defect.

Given: ADR 0028 says a deputy acts only while the deputyship is active and they still hold the role, that nobody stands in for an author, and that the record states who acted for whom in vote, approval, audit trail, outbox and PDF. ADR 0030 and 0064 keep owner decisions away from deputies. `Authorizer.onBehalfOf` extends a subject with the principal's assignments of given roles, and `Policy` still checks them. Votes store `acting_for`; the audit trail has `on_behalf_of`. The withdrawal lives in the version row because readers of the history may not see the audit trail (ADR 0059, decision 2).

## Decision

### The author

1. **Told like the approvers.** The recipients are now the approvers, whoever released the version, the owner and the version's author, without whoever withdrew it. An author who may read the version sees title and reason (ADR 0059, decision 10); otherwise the notice is generic as for everyone (ADR 0052). The dialog names the author among those told.

### The deputy

2. **What gives the right.** A valid approving vote the caller cast as deputy, in an approval step of this version (`Rules.deputyApprovals`). Reviews, rejections and requested changes, invalidated votes and votes on another version give nothing. Nor does being a deputy of the same approver without having voted: the right follows the act, as ADR 0059 ties it to "having approved this version".
3. **It holds only while the deputyship stands.** At the moment of the withdrawal the rules of ADR 0028 apply again (`Rules.standsIn`, `ApprovalWorkflow.standingApprovals`): the deputyship is active and covers the lane's role and the document's class, the deputy still holds the role and is cleared for the version, and neither of the two is the author. After the period the principal, the owner and the other approvers can act. This was the conservative choice: ADR 0028 has no lasting right of a deputy.
4. **`Policy` does not change.** For this one decision the caller's subject gets the principal's assignments of the lane's role, as `Delegation.subject` does for an open task. `Policy` then decides `Withdraw` as before and checks validity, scope and confidentiality of each assignment. A principal who lost the role leaves the deputy with nothing. The extended subject is not kept and is used for nothing else, so no other action on the document changes.
5. **Reading follows, for that version only.** `Delegation.approvedAsDeputy` lets the deputy read a version they could withdraw, or one they stay responsible for after it was withdrawn, under the same conditions. It adds the single version to what the caller sees, in `DocumentApi` and `WorkflowApi`. Other versions of the document stay as they were, including the valid one. Without this the dialog would be out of reach, and the page would fail right after the withdrawal.
6. **Whose name.** `Withdraw` is decided in this order: on the caller's own right, without counting a vote cast for someone else; else on a vote cast as deputy, latest vote first, as the approval names the principal of the last vote (ADR 0028, decision 5). In the second case the version records `actingFor`, also for a deputy who meanwhile holds the role in the scope themselves and whose deputyship is over: the vote they rest on was the principal's. A caller who holds the owner role acts on their own right, and nobody is named.
7. **The record.** Column `withdrawn_acting_for` (V47) next to `withdrawn_by`, set in the same statement, immutable afterwards like the rest of the withdrawal, never equal to `withdrawn_by`. Audit `document.withdrawn-before-valid` with `actor` = deputy, `on_behalf_of` = principal and `actingFor` in the details; the outbox event and the notice carry it too ("Ben Becker i. V. für Anna Albers hat ..."). The version history, the print and the evidence report show both. `WithdrawableVersionView.actingFor` tells the dialog beforehand.

### Paper

8. **One label.** The key `WithdrawnBeforeValid`, known to the frontend already, gets the same text in print: "Zurückgezogen, nie gültig" / "Withdrawn, never valid". `VersionPrintLayout.statusKey` uses it for every version with a withdrawal, also after `Archived`: what a reader of a paper must learn does not change later. The evidence report keeps the plain status.
9. **Copies.** PDF and Word copies carry "Zurückgezogen, nie gültig · Kopie – ..." in the marking colour of a draft, the footer names that instead of the valid-from date, and the approval note stays, since the approval happened. In the change history the row says when, by whom and for whom, and drops the valid-from date; rows of versions out of force keep "Außer Kraft". Layout version `print-layout-3`.
10. **Filled files.** The field `status` of a filled Word, Excel or PowerPoint file says the same. Other released versions keep the wording they have.
11. **Evidence report.** `EvidenceVersion.withdrawal` with time, person, principal and reason, and two rows in the version's section. The field is absent for other versions, so reports stay as they were and the format stays `dms.evidence-report/1`, as with `attachments` and `destruction`.
12. **The archive stays.** The archive PDF of the version is not touched (ADR 0059, decision 7) and says what was approved. A download of the rendition itself, and the preview of a file document, which shows it, carry no marking; the document page around it does. (Since DMS-155, ADR 0072: the preview is stamped and the download names it in the file name; the archived bytes stay.)

### Tests

13. `SearchApiSpec` and `LibraryApiSpec` take a successor and a first version through approval and withdrawal, over the real outbox, job queue and index, and check that the valid version is untouched, that a rebuild of the index brings nothing back, and that the kept library copy changes nothing.

Flyway V47. No new dependency.

## Alternatives considered

| Criterion (who of the deputies may withdraw) | Chosen: the deputy who voted, while the deputyship stands | Every active deputy of an approver | The deputy who voted, for good | Nobody: principal, owner and other approvers only |
|---|---|---|---|---|
| Stops a mistake while the approver is away | Yes | Yes | Yes | Only if an owner is there |
| Others gain nothing | Yes | No: someone who never saw the version | Yes | Yes |
| Rule of ADR 0028 "the deputyship must be active" | Kept | Kept | Broken | Not touched |
| After the period | Back to the principal | Back to the principal | A right without a role in the scope, without an end | Not relevant |
| Cost | One query for those with such a vote | One query per reader of a version | None | None |

| Criterion (how the right reaches the decision) | Chosen: extend the subject for this decision | New attribute and rule in `Policy` | Copy the principal's role into a task-like list | Check the vote in `DocumentApi`, skip `Policy` |
|---|---|---|---|---|
| `Policy` stays the only place of the rule | Yes | Yes | Yes | No |
| Scope, validity, confidentiality checked | Yes, on the principal's assignments | Must be written again | Yes | Must be written again |
| Same way as deputies of open tasks | Yes | No | Yes, but it pretends a task is open | No |
| Danger of a wider right | Only with a mistake in the single caller | A rule every caller must feed correctly | `ReadDocument` of all versions would follow | Bypasses the check |

| Criterion (what the deputy may read) | Chosen: that version only | The whole document, as during an open task | Nothing: API only |
|---|---|---|---|
| Dialog reachable | Yes | Yes | No |
| "Nobody gains anything" | Yes | No: valid and older versions of a scope they do not hold | Yes |
| After the withdrawal | Page still works | Page still works | Fails |
| Cost | One check per version with such a vote | None | None |

| Criterion (where "on behalf of" is kept) | Chosen: column on the version (V47) | Audit trail only | Derive it from the votes when reading |
|---|---|---|---|
| Version history can show it | Yes, like the reason (ADR 0059) | Needs the audit trail, which its readers may not see | Yes |
| A statement of what happened, not a guess | Yes | Yes | No: an owner who also voted acted on their own right, and the rule may change |
| Immutable | Guard | Chain | Votes are, the conclusion is not |
| Cost | A column, a guard change, a migration | None | One rule in each reader |

| Criterion (wording on paper) | Chosen: own label, marking and footer | Change the label only | A stamp on the archive PDF too | Refuse copies of such versions |
|---|---|---|---|---|
| A reader of one page learns it | Yes | Only with the change history | Yes | Not relevant |
| Valid-from date that never came | Gone from copies | Stays in the footer | Stays in the PDF | Not relevant |
| Archive immutable (ADR 0021, 0059) | Yes | Yes | No | Yes |
| Evidence stays available | Yes | Yes | Yes | No: the people concerned need the copy |

## Consequences

### Positive

- Who wrote a version hears when its approval ends, and can correct it.
- An approval given by mistake by a deputy can be stopped by that deputy, in the approver's absence, and the record says who acted for whom everywhere.
- `Policy` did not change, and nobody but the voter gains anything: no reader, no other deputy, no other action, no other version.
- A paper of a version that never took effect no longer looks like one that did. A filled file no longer says "Freigegeben".
- Library and search have tests of what ADR 0059 only argued.

### Negative / Cost

- More notices: the author gets one per withdrawal, unless they did it.
- A deputyship ended the day after the vote takes the right away. The principal may be away still, and only the owner or other approvers remain.
- A deputy of another scope now sees the version they approved as long as it waits and the deputyship lasts. That is what they already saw while voting.
- Every read of a document looks at the votes of its waiting versions. That is in memory; a query follows only for the caller who has such a vote.
- Several principals in several steps: one name is recorded, the latest. The votes keep all of them.
- V47 rewrites `document_version_review_guard` again, and `add constraint` reads the table once under its lock. For this table's size that is short, and V41 did the same.
- The label is also used for a version archived later, so the change history shows "Zurückgezogen, nie gültig" instead of "Archiviert" for it.
- `print-layout-3`: new copies of other versions are unchanged in content but name the new generator.

## Follow-ups

- Must be done: user confirms this ADR (status → Accepted).
- Done (DMS-155, ADR 0072): the failure notice of a dead publication decides `canWithdraw` on the recipient's own roles, so it does not tell a deputy with a standing vote that they can withdraw, although they can.
- Done (DMS-155, ADR 0072, by the file name): a filled file without the field `status` carries no marking at all, since filling only fills fields. A notice in the file needs a decision for each file type.
- Done (DMS-155, ADR 0072): filled files of versions out of force or superseded still say "Freigegeben", the status at approval. This is a question of its own: what a filled file records.
- Done (DMS-155, ADR 0072): `GET .../html` has no marking for such a version; the document page shows its own notice, other API clients do not.
- Done (DMS-155, ADR 0072), from ADR 0059: decide rights on the valid version, not on the latest one, in `RetentionApi`, `LegalHoldApi` and `EvidenceReportApi`.
- Ruled out: a lasting right of a deputy; deputies of the owner withdrawing as owner (ADR 0028, 0030, 0064).

## Reversibility

**high**: Author, wording and the report's field are code. The right of the deputy is `withdrawalRight` and `approvedAsDeputy`; removing them leaves ADR 0059 as it was. Stored `withdrawn_acting_for` values stay meaningful without the code; dropping the column would take the names from the version history, not from the audit trail. Reports already registered keep their field, and readers of format 1 ignore it when absent.
