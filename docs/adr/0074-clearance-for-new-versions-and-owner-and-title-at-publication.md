# 0074: A new version needs clearance for the version it starts from and cannot lower its protection; owner and title of a document change when a version takes effect

| Field | Value |
|---|---|
| Status | Accepted (2026-11-06; proposed 2026-11-06, implemented in DMS-158); decision 2 is amended by [0083](0083-lowering-confidentiality-with-an-additional-approval.md): the way down is the additional approval, saving stays refused |
| Date | 2026-11-06 |
| Decided by | Philippe |

## Context

A security review of DMS-155 found two defects.

1. **Starting a version skipped the confidentiality check.** `newVersion` asked for `EditDraft` on the base version with the caller as its author, because "the caller becomes the author of a draft". `Policy.clearedFor` lets an author pass, so the check could not fail on confidentiality. The response carries content and values of the base version, and the caller, now `createdBy` of the draft, reads its file, attachments and rendering. Any holder of the author role in scope could do this. The override served nothing else: `EditDraft` looks at the role, not at the author.
2. **Saving a draft changed the document.** `updateDraft` wrote owner and title to `dms.document` for every version, and nothing restored them. `Policy` and `ReviewApi` read `document.ownerId` for all versions, so one save moved clearance, review tasks and the editorial confirmation to a person the draft named. Lists showed the draft's title.

The check of the pattern found:

- `startVersion` (the offer in the document view) had the same override and mirrored the hole.
- `ReviewApi.revise` had the same override. It was safe, as reported: `ownersVersion` asks for the document's owner first, and the owner passes `clearedFor` anyway. It was safe only as long as the owner is honest, which defect 2 undid.
- Every other `.copy` of a resource before a check keeps author, owner and groups: `AttachmentApi`, `FileDocumentApi` (status, to answer 409 instead of 403), `CurrentDocumentApi`, `EvidenceReportApi`, `RetentionApi`, `PublicationStatusApi`, `RenditionStatusApi`. `RenditionStatusApi.covers` lowers the confidentiality on purpose, but only decides whether a row without title is listed (ADR 0064). `creationResource` makes the caller the author of a document that has no content yet.
- **A draft could lower its own confidentiality.** Nothing compared a draft with what it started from. Someone cleared could save a draft of a confidential document as `Internal`: every author in scope could then read the copied content, and after publication every reader. Leaving the field out had the same effect, because a missing value counts as `Internal`. Adding an access group had the same effect for that group. The business concept (3.2) says of the field: "Nur erhöhbar ohne zusätzliche Freigabe". Such an additional approval does not exist in the platform.

Given: a document has one owner and one title, and the versions have their own values `owner` and `title`. `dms.document` is written in one place. Rendered output takes the title from the version (`DocumentRenditions.title`). A workflow takes the owner from `ReviewSubject.owner`, read from the document. At most one version is open, and no new version starts while an approved one waits, so the set of valid versions can only shrink while a draft is open. The audit chain is computed by the application (ADR 0017), so a migration cannot write to it.

## Decision

### Starting a version

1. **The check is decided on the base version as it is.** `DocumentApi.versionStart`: the resource of the base version with status `Draft`, because that is what the action creates. Author, owner, groups, unit and class stay those of the base version. A caller who is not cleared for it gets 403, for `Confidential` and `StrictlyConfidential` alike, also while a version is open (403, not 409). `newVersion`, `startVersion` and `ReviewApi.revise` all use it. `Policy` does not change: it was right, and the override was wrong.

### What a later version may change about its protection

2. **The level cannot go down.** `DocumentService` compares a draft of a later version with its basis (`Versioning.basis`: the version it started from, or if that ended meanwhile the latest released version). A lower level gets 422 `invalid_values` with the issue `confidentiality_lowered`, on saving and on submission. A missing value counts as `Internal`, as for `Policy`. **This holds for everybody, the owner too**, because the approval the concept asks for does not exist. It holds on all levels, also from `Internal` to `Public`: the concept makes no difference.
3. **Only the document's owner adds access groups.** On a restricted basis, a group the basis does not have gets 422 with the issue `access_groups_widened`, unless the caller is the owner on `dms.document` or the draft has it already, so the author can go on working after the owner added one. The concept makes the owner answerable for who reaches a confidential document (US on the half-yearly confirmation of access). Refusing everyone would make the list of a confidential document unable to grow for good. Removing groups and raising the level are free. Submission does not repeat this check: the owner may have added the group and someone else submits.
4. **A first version is free.** It has no basis and copied nothing.

### Owner and title of the document

5. **Later versions leave `dms.document` alone until they take effect.** A draft keeps its `owner` and `title` in its own values. `Approved → Published` writes them to the document in the same transaction, for scheduled and immediate publications alike. A value missing in the version keeps what the document has, so a version from before the field was required cannot empty it. Recertification (`ReviewDue → Published`) changes nothing. A discarded or withdrawn version never reaches that step, so nothing has to be restored.
6. **A first version's draft keeps setting them.** "First" is `Versioning.isFirst`: no other version that was not discarded. So a version started after a first version was withdrawn before it took effect counts as later (conservative: that document has been through a workflow, and its owner may have taken part).
7. **The publication records the change.** Audit entry `document.owner-and-title-applied` with before and after, only if something changed.
8. **The owner of an open later version, for its own workflow, is the owner in force.** Owner lanes, the editorial confirmation (`Rules.ownerConfirmation`), deadlines, participants' clearance (`RoleDirectory`), review tasks, notifications, the search index and `Policy` all keep reading `document.ownerId`, and need no change for that. The person a draft names has no right before publication. So the person who loses the document is the one who confirms a Minor version that hands it over, and the author cannot pick who confirms.
9. **What a version prints is what it names.** The automatic Word field `owner` comes from the version, then the document (`DocumentVersion.namedOwner`). Without this the rendition of a version that names a new owner would freeze the old one, since the document changes only after approval.
10. **No migration.** Documents already changed by a draft are not repaired here. Which owner is right cannot always be told: until now a draft was the only way to change an owner, also for honest changes. And a migration cannot write an audit entry, so it would change the owner of confidential documents without a trace. See the follow-ups.

## Alternatives considered

| Criterion (clearance on starting a version) | Chosen: decide on the base version as it is | New action `StartVersion` in `Policy` | Check `ReadDocument` on the base as well | Only hide content in the response |
|---|---|---|---|---|
| Closes the hole | Yes | Yes | Yes | No: the draft is still the caller's |
| Changes to `Policy`, search filter and matrix | None | One more action everywhere | None | None |
| Readers of a version who hold only the author role can still start one | Yes | Yes | Yes, but two checks to keep in step | Yes |
| Withdrawn base stays usable for those who could correct it (ADR 0059) | Yes | Needs its own rule | No: read rules differ by status | Yes |

| Criterion (lowering the level) | Chosen: refuse for everybody until an approval exists | Allow the owner | Allow, keep the draft at the level of its basis until publication | Allow, add the extra review step |
|---|---|---|---|---|
| Matches "only raised without additional approval" | Yes | No: one person, possibly the author | No: nobody approves it | Partly: the reviewers are not asked this question, and the draft is exposed before they are |
| Copied content is exposed while the version is open | No | Yes | No | Yes |
| A level set too high can be corrected | No, see the follow-ups | Yes | Yes | Yes |
| Effort | One comparison | One comparison | Two levels per version in `Policy`, search and every list | Workflow change |

| Criterion (adding access groups) | Chosen: the owner | Nobody | Everyone cleared |
|---|---|---|---|
| Someone answerable decides | Yes | Not relevant | No |
| The list of a confidential document can grow | Yes | No | Yes |
| An author with a draft opens the document to a group | No | No | Yes |

| Criterion (when owner and title change) | Chosen: at publication | Second columns on `dms.document` for the pending values | At approval | Restore on discard and withdrawal (as a fix of the old way) |
|---|---|---|---|---|
| A discarded or withdrawn version leaves no trace | Yes, by construction | Yes | No: withdrawal needs a restore | Only if every way out restores, and the document is wrong meanwhile |
| Owner in force keeps clearance and tasks during the workflow | Yes | Yes | No: an approved version may wait for months | No |
| Migration | None | Yes | None | None |
| Several readers need to know which value counts | No | Yes | No | No |

| Criterion (owner of an open later version) | Chosen: the owner in force | The owner the draft names | Both |
|---|---|---|---|
| An author can choose who confirms their change | No | Yes, which is the defect | Partly |
| Who loses a document takes part | Yes, in an editorial version | No | Yes |
| The new owner sees what they take over before it takes effect | No, unless they are a participant | Yes, and a confidential document opens for a person nobody confirmed | Yes, the same |
| One meaning of "owner" for `Policy`, workflow, tasks and search | Yes | No | No |

| Criterion (documents changed already) | Chosen: leave them, follow-up with a report | Migration back to the last version in force | Job in the application with audit entries |
|---|---|---|---|
| Owner of a confidential document changes without audit entry | No | Yes | No |
| An honest change of owner is undone | No | Yes | Yes, unless someone decides |
| A past takeover ends at once | No | Yes | Yes |
| Effort | None now | One migration | A job, a report, a decision per case |

## Consequences

### Positive

- Confidentiality holds for new versions: starting one needs what reading does, and a version cannot widen the audience of what it copied.
- A draft is a proposal until it takes effect. Nothing it says about owner and title reaches clearance, tasks, lists or search.
- There is nothing to restore. The rule has no second half that could be forgotten on a future way out of a draft.
- A change of owner has an audit entry of its own that says when it happened and with which version.
- `ReviewApi.ownersVersion` and `Recertify` can trust `document.ownerId` again.

### Negative / Cost

- **A confidentiality level that is too high cannot be lowered at all** in a later version, not even by the owner. This is stricter than the concept, which asks for an approval. It lasts until that approval exists.
- A cleared author can no longer widen access; they have to ask the owner to save the draft with the group.
- A new owner gets no right, and no look at the document, before publication. In a Major version the owner in force is not asked either: approvers decide on the change, and the metadata comparison shows it.
- With several validity scopes the document still has one owner and one title: the version published last sets them, also for the valid versions of other scopes.
- The heading of the document page and lists show the title in force, while the form of an open draft shows the draft's. That is what the issue asks for, but an author may wonder. The version comparison shows the difference.
- The owner in force keeps being named on open versions, so a Minor version whose author is that owner goes to their deputies as before.
- Saving a draft reads the versions of the document once more.
- Open drafts that lowered their level before this change cannot be saved or submitted until the level is restored, and stay exposed until someone does that or discards them.
- Documents an earlier draft changed stay changed, until the follow-up. A version open at the time of deployment that is discarded leaves the document as the draft left it.

## Follow-ups

- Must be done: user confirms this ADR (status → Accepted).
- Must: the additional approval for lowering a confidentiality level (business concept 3.2), which lifts decision 2.
- Should: a report for records management of documents whose owner or title differ from the latest version in force, with an audited way to correct them (decision 10). Also a list of open drafts below their basis.
- Should: the owner who loses a document at publication is told; today only the new owner gets `publication.published`.
- Should: a draft can move to another organizational unit, which exposes copied content to authors of that unit the same way. `updateDraft` checks the unit before the change, not after it. Not part of this issue, and no decision of the concept covers it.
- Could: show the title and owner a draft proposes on the document page.
- Could: tell the proposed owner when a version names them.

## Reversibility

**high**: No schema or stored data changes. The checks are in `DocumentApi`, `ReviewApi` and `DocumentService`; two issue codes are added. Going back means writing to `dms.document` in `updateDraft` again, which reopens the defect. Owners applied at publication stay, and rightly so.

## Addendum 2026-11-06

The user decided on 2026-11-06 to build the additional approval of business concept 3.2 for lowering confidentiality. Until it exists the refusal of decision 2 stands; implementation in DMS-162.
