# 0083: A lower confidentiality is asked for on the draft, decided in an additional approval step and takes effect with the publication of the version

| Field | Value |
|---|---|
| Status | Proposed (2026-11-06, implemented in DMS-162); amends decision 2 of [0074](0074-clearance-for-new-versions-and-owner-and-title-at-publication.md) |
| Date | 2026-11-06 |
| Decided by | Philippe (that the approval is built); the design is proposed |

## Context

ADR 0074 refused every later draft below the level of its basis, for everybody, because the approval the business concept asks for did not exist. The user decided on 2026-11-06 to build it (DMS-162, first point).

What the concept says, all of it. The table of core metadata in 3.2 has one line on the field: source "Klassen-Standard", note "Nur erhöhbar ohne zusätzliche Freigabe". 2.3 defines the levels and says that from `Confidential` on access is limited to named groups. 2.4 says author and approver of a version differ. 8 says notices carry no confidential content. So the concept names neither who asks, nor who approves, nor a mechanism. It does not mention access groups at this point. It does not limit the note to later versions, but names a class default as the source, which the platform does not have.

Given:

- A version package is frozen from submission on (guard of V9, last changed in V55). Votes and the approval are bound to a checksum over content and values (`WorkflowDocuments.contentSha256`), and the evidence report compares that of the approval with the version as stored.
- The metadata snapshot and the rendition are made at approval, before publication, and an approved version can wait for months.
- `Policy` reads the level from the values of each version, so a level in one version never reaches another. A draft with a lower level would open to every author in scope at once: reading a draft asks for a role in scope, and below `Confidential` for nothing else.
- Deviations have an additional review step (ADR 0022, 0071), but a class has it only if it defines it, and a review step follows the class's four-eyes setting. It is also the wrong kind for this: ADR 0078 confirms "four eyes off" concerns review steps only.
- Approval steps ask for a recent sign-in, leave out authors and editors whatever the setting says (ADR 0075), and their participants are holders of `Approver` who are cleared for the version (`RoleDirectory.holders`), with deputies per ADR 0028. `WorkflowApi` requires `Approver` for approval lanes.

## Decision

### Asking

1. **An author or owner asks on the draft, with a target level and a reason.** The right is `EditDraft`, the right of every change of a draft (ADR 0069, decision 6): an author or owner in scope who is cleared for the draft. A row in `dms.document_confidentiality_lowering` (V60), with RLS and grants, never deleted. At most one request of a version is open or approved. A request can be withdrawn while the version is a draft.
2. **The draft stays at its level.** Nothing about it changes; it can be worked on. Saving below the basis stays refused for everybody, with or without an open request (422 `confidentiality_lowered`), so ADR 0074 decision 2 stands for saving. The only way down is decision 5.
3. **Asking counts as a change of the version.** Whoever asks is recorded as an editor (ADR 0075, decision 2), so they cannot decide, whoever they are. It also costs a standing vote, like any other change, which the user confirmed for ADR 0075.
4. **Only a later version asks, and only for a level below its basis.** A first version stays free (ADR 0074, decision 4), see the open question below. Access groups are unchanged: the concept says nothing about them here, so ADR 0074 decision 3 stands. A lowering touches the level only. The groups stay in the values, so a version that goes back up would still have them.

### Deciding

5. **The decision is an additional approval step of the workflow, added by the platform.** `WorkflowDefinition.loweringStep`, of kind `Approval`, with one lane for `Approver`. While a version has an open request, `ApprovalWorkflow` adds the step at the start of a case and at every new round, and takes it away if the request is gone, whatever the class defines, also on the editorial path of a minor version. The BPMN generator needs no change, as the step is an ordinary one. Its key is reserved. Everything else comes with the step: participants cleared for the old level (the values still state it), without authors and editors whatever the four-eyes setting says, deputies per ADR 0028 who are cleared themselves, step-up, deadlines, `workflow.participant-missing` if nobody is left, the audit of the vote.
6. **The step comes last.** Approving changes what the version states, so no vote may follow that was cast on something else, and a return after it would have to undo it. Reviewers and approvers see the open request while the version still stands at the old level.
7. **Approving writes the level into the version, in the transaction of the vote.** It is the one change of field values outside a draft. The guard of V60 allows exactly this: nothing but the level, to the level of a request that is approved, while the version is in approval. The case takes over the new checksum, so the approval, the rendition and the metadata snapshot all cover what will be published. No working state is written, as that would make the approver an editor.
8. **Refusing returns the version to its author** (`Reject`, reason required as for every rejection), and the request is `Refused`. No value changes, because none had. The next submission goes on from the last step before the additional one if nothing else changed: the step is gone, and the other votes stand. Asking again is a new request with a new reason, and it resumes at the step.
9. **The database has the last word on who decides.** The guard of the table refuses a decision outside approval, and one by or for the person who asked, the author or an editor.

### Taking effect

10. **The level takes effect with the publication of this version, and only for it.** Until then `DocumentVersion.accessFloor` holds the old level, and `accessMetadata` is what access is decided on: `DocumentResource`, `ReviewSubject.accessGroups` and the comparison with a basis read it, and the listing of open versions of a template asks the row. It ends when `publishedAt` is set, so it needs no write and cannot be forgotten. Valid versions have their own values, so they do not change; a version superseded by this one keeps its level for good.
11. **A version that is never published never had the lower level.** After a withdrawal it stays at the old level for access, and a new version started from it starts at the old level, with the values corrected. The next request is a new one.
12. **Every step is audited** with person, old and new level and reason: `document.confidentiality-lowering-requested`, `-withdrawn`, `-approved`, `-refused`, and `document.confidentiality-lowered` at publication, with who asked and who decided. A deputy is recorded as `on_behalf_of`.
13. **People are told through the notifications module** (`lowering.requested`, `lowering.approved`, `lowering.refused`, `lowering.in-force`), without level and reason, as the concept asks. Approvers hear of it through the existing `workflow.task-assigned`. The notice of the refusal replaces `workflow.rejected` for this step, so nobody gets two.
14. **The reason stays at the old level.** It was written by someone cleared for that level and may say why it was set. `GET …/versions/{versionId}/confidentiality-lowering` gives the requests only to those cleared for the highest level of a request, also after the lower level took effect. The version view carries no reason. A notice carries none either.

### Smaller points of the same issue

15. **The owner who loses a document is told at publication** (`document.owner-replaced`, notice `publication.owner-replaced`). The notice names the new owner and, as for everyone, no title if they may not read the document any more.
16. **The document page shows the title and owner a version on its way proposes**, where they differ from what is in force, with the note that the person has no right from it yet (ADR 0074, decision 8).

## Alternatives considered

The mechanism:

| | Additional approval step added by the platform (chosen) | Condition on a class-defined step, like `ExtraReviewRequired` | Separate approval on the draft, before submission | The owner decides alone |
|---|---|---|---|---|
| Difference | The workflow gets the step while a request is open | Classes add a step, the condition switches it on | A task of its own, outside the workflow | No second person beyond the owner |
| Positive | Works for every class and minor versions; step-up, four-eyes, deputies, deadlines, inbox and audit come along | Nearest to deviations; a class picks the department | Decided early, nobody works on a version that will be refused | Very little to build |
| Negative | The final approval is by the person who decides this; a case can change its process between rounds | Fails open: a class without the step would lower with no approval, or block for good. `Approval` steps cannot be conditional, and a review step follows the four-eyes setting | A new kind of task for inbox, deadlines, deputies and notices. The approval would cover a draft that can change afterwards, unless every save voids it | Not what the user decided; the owner may be the author |

Where the step stands:

| | Last (chosen) | First of the approval steps | At the start, before review |
|---|---|---|---|
| Difference | After the regular approval | Before the regular approval | `Review` kind is the only one possible there |
| Positive | Nothing follows that could return the version; the regular approvers see the level before it changes | The approver in the regular step approves the final text and is named on it | Reviewers work with the settled level |
| Negative | Whoever decides this is the approver of record | A return after it needs the level undone and the decision reopened | Follows the four-eyes setting, no step-up; the level changes before the review, and the version needs a second freeze |

How the level becomes that of the version, and access keeps the old one:

| | Approval writes the value, a floor keeps access (chosen) | Value written at publication | Value written at submission, floor from there | Value written at approval, no floor |
|---|---|---|---|---|
| Difference | Value changes in the last step; `accessFloor` until `publishedAt` | The row holds the level until the version is published | The draft freezes with the lower level | Version is at the lower level from the step on |
| Package | Coherent: approval, snapshot and rendition state the published level | Rendition, snapshot and checksum state the old level for good, or the frozen package changes after approval, and the evidence report shows a mismatch | Coherent | Coherent |
| Draft stays at the old level until approved | Yes | Yes | No, and every return has to restore it | Yes |
| Effect only with publication | Yes | Yes | Yes | No: an approved version waiting for its date opens to authors in scope |
| If a reader of the level is missed | Only versions between the last vote and publication are concerned. Reading the value there is the wider answer, so a missed reader opens too much | A missed reader of a published version stays too strict | Same as chosen, for the whole workflow | Not applicable |
| Cost | One narrow exception in the guard; `accessMetadata` in every reader of open versions | The guard opens for an approved version, or two levels for good | Restore logic on every return | Nothing |

Who approves:

| | Holders of `Approver` cleared for the level (chosen) | Also an owner who did not change the version | A role of its own |
|---|---|---|---|
| Difference | Like every approval step | Like the editorial confirmation | New role, new assignments |
| Positive | `WorkflowApi` and `Policy` ask for it already; deputies work | Quick where the owner has the context | Exact |
| Negative | A tenant with no approver in the groups waits, with a notice | Owner lanes have no deputies (ADR 0028); the owner answers for the document and is rarely neutral | Nothing in the concept asks for it |

## Consequences

### Positive

- The level can go down, and it takes two people, in the workflow people know.
- The draft stays closed as it was until the decision, and the version until its publication.
- Every approval, rendition and snapshot states what is published; the evidence report stays a plain comparison.
- A refusal has nothing to undo, and a withdrawal needs no job.
- Saving is as strict as before, so no code path can lower a level by saving.

### Negative / Cost

- **Between the last vote and publication, `metadata` and access differ.** Anything new that reads the level of an open or approved version has to use `accessMetadata`. The window is small, and `DocumentResource` covers almost all readers, but a new reader that reads `metadata` opens too much rather than too little.
- The approval of record, and the note on the PDF, name whoever decided the lowering, not the approver of the regular step, if they differ. Their vote stays in the case.
- Earlier votes in the case carry the checksum from before the change. Only the approval is compared with the version, so nothing breaks, but a reader of the case sees two checksums. The audit entry explains it.
- `updateCase` now writes definition and process, and a case can change its process between rounds. Running processes are not touched.
- A requester who holds `Approver` loses their vote on the version.
- With a refusal and an unchanged version, the last regular step is repeated: its approver votes once more.
- A request stays `Requested` on a discarded draft: nothing reads it there.
- A template that prints the field `confidentiality` shows the new level in the rendition, as it should, and the rendition is made before publication.

## Open questions, decided conservatively

- **First versions.** The concept's note could be read to cover a first version that goes below the class default. The platform has no class default for this field, so there is nothing to compare with, and ADR 0074 decision 4 keeps it free. If a class default is built, a first version below it should take this path. Conservative here means changing nothing that was decided; the question goes to the user.
- **`ApproveWithConditions` is not offered in the UI for this step**, as a condition has nowhere to go. The API accepts it like any approving vote and records its reason.
- **Who approves** is the conservative reading of "second person": the role of an approval, not any cleared person.

## Follow-ups

- Must be done: user confirms this ADR (status → Accepted).
- Should: a list for records management of open drafts below their basis, from before ADR 0074 (a follow-up of that ADR).
- Could: let a class name the role or lane of this step, if a department wants to own it.
- Could: tell the owner and author when a request stays open at submission with no eligible approver, beyond the existing notice to administrators.
- Left open in this issue, as asked: the report of past owner changes (ADR 0074, decision 10) and moving a draft to another organizational unit.

## Reversibility

**medium**: The table, the step and the guard exception are additive; removing the endpoints and the step brings back the refusal of ADR 0074. Published versions keep the level they took on, rightly so, and their rows stay as a record. Lowerings that were approved but not published would have to be finished or withdrawn first, as `accessFloor` is what keeps them closed.
