# 0084: Records managers see and correct owner and title that differ from the version last in force, and see open versions below their basis; only the document's owner moves a later draft to another unit, and only to one where they can edit it

| Field | Value |
|---|---|
| Status | Proposed (2026-11-06, implemented in DMS-162) |
| Date | 2026-11-06 |
| Decided by | The product owner, to confirm; decisions 14 to 17 go beyond the business concept |

## Context

DMS-162 collects the follow-ups of ADR 0074 and 0083 that were left open: the report of past owner changes (ADR 0074, decision 10), the list of open drafts below their basis, and the move of a draft to another organizational unit.

**Past takeovers.** Until ADR 0074, saving a draft wrote owner and title to `dms.document`. A draft that was discarded or withdrawn left them there. ADR 0074 refused a migration: which owner is right cannot always be told, a draft was the only way to change an owner, also for honest changes, and a migration cannot write an audit entry. The reading of the code found:

- `dms.document` is written in three places: a first version's draft, publication (`applyToDocument`), and now the correction. Values a version does not have keep what the document has, so these say nothing.
- A takeover by a draft is the usual cause of a mismatch, not the only one. The version compared with is not always the one that set the values: if that version was withdrawn while another stayed valid, or was destroyed, the document keeps an honest value that differs. The records do not tell the two apart.
- A document can have several valid versions, one per scope. The version published last set the values, not the one with the highest number.
- A running workflow took the owner when it started: candidates of an owner lane are stored on the task, and `ReviewSubject` is read again only for the next round. A version in approval will set its own values at publication.
- A returned case is not over. The version is a draft again, but `ApprovalWorkflow.resubmit` and `Rules.resume` go on at the returning step, the earlier votes stand, and `contentSha256` does not cover `document.owner_id`. So the confirmation of an owner who is corrected away would stand.
- Review tasks of the periodic review name no person: they read `document.owner_id`, so a corrected owner gets them at once. The search index carries the owner in its access attributes and finds the document through `updated_at`.
- Records managers do not read drafts: `ReadDocument` on a `Draft` asks for the author or owner role. This ADR does not change that.

**Drafts below their basis.** ADR 0074 listed what it could not repair: drafts that lowered their level before that rule stay exposed until someone raises the level or discards them, and they cannot be submitted. ADR 0083 left the list open. Versions that were in review or approval at the rollout of ADR 0074 are in the same position, so "open" means `Versioning.open`. Since ADR 0083, an approved lowering puts a version below its basis on paper, while access stays at the old level until publication.

**Moving a draft.** `organizationalUnit` is an ordinary field of the draft. `DocumentApi.updateDraft` decides `EditDraft` on the draft as it stands, before the change. `Policy.rolesInScope` compares a scoped assignment with the unit of the version, so saving a later draft with another unit:

- opens the content copied from the basis at once to every author and owner role of the new unit, for `Public` and `Internal`, and for members of the access groups who hold a role only there, for `Confidential` and above. A `Reader` of the new unit gets nothing until publication;
- takes the draft away from those of the old unit;
- can be done by anyone who may edit the draft, for a unit where they have no right themselves.

This is the same hole as lowering the level (ADR 0074, decision 2) and adding a group (decision 3). The business concept does not cover it: 3.2 says only that the unit "steuert Sichtbarkeit", and 2.3 lists it among the attributes rules look at. But a document moving between units is normal work: units are reorganized, and ADR 0072 already counts on documents that moved.

Given: the first version copied nothing and stays free (ADR 0074, decision 4). `Versioning.basis` tells what a draft started from. No other path changes the unit of an open version: the other writers of a draft keep its values, and `PartnerApi` goes through `updateDraft`.

## Decision

### Who

1. **Records managers.** The concept gives them the records (2.3), ADR 0074 asked for a report "for records management", and they already assign document numbers with a reason (ADR 0040), which is the same kind of act. A new action `CorrectDocumentRecord`, not a new role, because `ManageRetention` says something else and `AssignDocumentNumber` also lets owners in, who are the people concerned here.
2. **Decided per document, on the governing versions and every version a row shows** (ADR 0072, decision 3): a row shows the values of those versions. So scope and clearance apply, and the action has no status condition. An active records manager assignment is asked for first, as in the cockpit.
3. **What stays out is counted.** `notCleared` is the number of documents in the caller's scope they are not cleared for. It is a number only, and it keeps the report from passing for complete. A cleared person has to look at those. Documents outside the scope are not counted.
4. **Reading is recorded,** `records.document-differences-viewed` and `records.drafts-below-basis-viewed` with the number of rows, as for the four-eyes report (ADR 0078): the reports name people.

### Owner and title

5. **The document is compared with the last version in force.** `Versioning.lastInForce`: of the valid versions the one published last, without one the one published last among those that were in force, never a destroyed one. A document with no version that took effect has no difference: its draft sets owner and title by right. This is usually, not always, the version that set the values (see the context), and the report says so: a row is a question, not a finding of fault.
6. **A difference is a named value that is not the document's** (`DocumentRecord.difference`), compared as publication writes it, trimmed. `GET /api/v1/records/document-differences`.
7. **Nothing changes by itself.** Past takeovers stay until someone decides, per document and per value, as ADR 0074 asked: an honest change of owner stays in the report and is not undone. See the negative consequences.
8. **A correction takes the version's value, no other.** `POST /api/v1/documents/{id}/record-correction` with `{versionId, owner, title, reason}`. The caller chooses the owner, the title or both, and cannot enter a value, because the only value anyone approved is the version's. The reason is required, at most 4000 characters.
9. **It is recorded and told.** Audit entry `document.record-corrected` with the reason and owner and title before and after. If the owner changes, the outbox event `document.owner-corrected` tells the owner it had and the owner it has now, without the reason, and not the person who corrected it if they are one of the two.
10. **It waits for the workflow.** 409 while a version is in review, in approval or approved: the case read the owner when it started, and an approved version brings its own values. The same holds for a draft whose case is `Returned`: its next submission goes on from the votes that stand. Only a draft that has no running case does not hold it back. The report says which version it waits for. It is also 409 if the version is no longer the last in force, or the chosen value no longer differs, so a stale page decides nothing. The document and the open version are locked first, and the state of the case is read after that.

### Open versions below their basis

11. **A list, no action.** `GET /api/v1/records/drafts-below-basis`: open versions whose level is below their basis (`DocumentRecord.belowBasis`), with author, owner, both levels and when the version last changed. Title and unit are those of the basis: what the open version proposes stays with those who may read it (ADR 0074, ADR 0082), and records managers may not read drafts. They should not correct what they cannot read either. For a draft, the people named can raise the level, ask for a lowering (ADR 0083), or discard it. A version in review or approval cannot be changed, and the level is checked only at submission, so it can reach publication below its basis unless someone returns it; the list says so on the row.
12. **Levels count by `accessMetadata`**, on both sides, so an approved lowering is not reported as long as access stays at the old level, and a basis that was lowered but never published counts at its old level.
13. **Both reports sit on the retention page**, in a tab "Dokumentdaten", which is where records managers work. The page asks for a records manager for the whole tenant (ADR 0047), so a scoped records manager has the reports through the API only.

### Moving a later draft

14. **Only the document's owner moves a later version to another unit.** A draft saved with a unit that neither it nor its basis has gets 422 `invalid_values` with the issue `unit_moved`, unless the caller is the owner on `dms.document`, like adding a group (ADR 0074, decision 3). The owner answers for who reaches the document, and that does not end at the unit. It holds on every level: below `Confidential` the move opens more, and above it members of the groups who hold a role only in the new unit get in.
15. **Only to a unit where they can edit it.** After the change `EditDraft` is decided again on the draft as it would stand, on every way of saving a draft (`DocumentApi.checkUnitReach`, also when a partner reference fills the unit). If it fails, 422 with the issue `unit_out_of_reach`, and nothing is saved. Nobody hands copied content to a unit they cannot follow it into. A draft is not moved past its owner's scope; the way out is a role in the target unit, for as long as the move takes.
16. **What is decided stays, and the way back is free.** After the owner moved the draft, its author saves it with that unit, as with a group the owner added. Going back to the unit of the basis, and leaving the field empty, widens nothing and needs no one. Submission does not repeat the check, as for groups: the owner may have moved it and someone else submits.
17. **A first version stays free**, and the move takes effect when saved: the new unit's authors are meant to work on it, and its reviewers and approvers are found in that unit. The change is in the audit entry `document.draft-changed` with before and after, as for every field.

No migration: V61 is not used. There is no new table, and no new grant.

## Alternatives considered

Who sees and corrects:

| | Records manager, new action (chosen) | Records manager, `ManageRetention` | Compliance officer in the cockpit | The owner in force |
|---|---|---|---|---|
| Difference | One more row in the matrix | No change to `Policy` | Beside the four-eyes report | Each on their own documents |
| Positive | Says what it means; scope and clearance as everywhere | Nothing to add | Next to a similar report | Knows the document |
| Negative | One more action | Mixes a new power into retention; those holding it for one purpose get the other | The officer monitors and does not change records (ADR 0036, 0078) | The owner of a takeover decides about their own takeover |

What a correction can do:

| | Take the version's value, per value (chosen) | Enter any value | Both values or none | Also "confirm as it is", which ends the finding |
|---|---|---|---|---|
| Difference | Owner, title or both, from the version | Free owner and title | One decision per document | A stored decision per finding |
| Positive | Only what was approved can be set; no change of owner without a version | Also covers a version naming someone who left | Simplest | The list gets shorter for honest changes |
| Negative | An honest change stays listed until the next version | A new way to change an owner outside every workflow, by one person | An honest owner with a wrong title cannot be separated | A table, a state, and a decision nobody asked for; can be added later, see the follow-ups |

During a workflow:

| | Refuse until it is through (chosen) | Allow, leave the running case | Allow and reassign open owner tasks |
|---|---|---|---|
| Difference | 409, the report names the version | The document changes, the tasks do not | The case follows |
| Positive | Owner in force stays one thing for a case (ADR 0074, decision 8) | Never waits | Consistent |
| Negative | Waits, for months if a version waits for its date | The person confirming is no longer the owner | Changes running tasks, deputies and deadlines for a rare case |

Moving a later draft:

| | Owner, with a right in the target unit (chosen) | Check the target unit only | Refuse every move of a later version | Keep access at the old unit until publication | An additional approval step, as for a lower level |
|---|---|---|---|---|---|
| Difference | Decided as for adding a group, then decided again where it goes | Any author with a right in both units moves it | The unit is fixed once a version took effect | The unit is a proposal, as owner and title are, with a floor like `accessFloor` | Asked for on the draft, a second person decides |
| Closes the exposure | Yes, up to the owner's decision | No: every author with a tenant-wide role can still open it | Yes | Yes, fully | Yes |
| Someone answerable decides | Yes | No | Not relevant | Reviewers, in the end | Yes, a second person |
| Normal work | Goes on, one person acts, and the new unit can work on it at once | Goes on | **Blocked:** a reorganized unit has no way to take its documents along | **Blocked in part:** the new unit cannot write or review what it is to answer for, and participants come from the old unit | Slow for a routine act; a second round for each move |
| Cost | Two checks, two issue codes | One check | One check | A second unit per version in `Policy`, search, role directory and every list | A table, a step, a guard: most of ADR 0083 again |
| Reversibility | High | High | High | Low | Medium |

## Consequences

### Positive

- Past takeovers are visible, with a way to correct them that leaves a trace, and nothing changes without a decision.
- No new way to give a person a document: a correction can only state what a version already named and someone approved.
- Drafts exposed before ADR 0074 can be found and followed up.
- Unit, level and groups now follow one rule: a later draft cannot widen who reaches what it copied, except through the person or step that answers for it.
- No migration, so nothing is at risk at rollout.

### Negative / Cost

- **An honest change of owner stays in the report** until a later version names that owner, as long as the difference stands. Records managers see the same rows again. This includes values set by a version that was withdrawn or destroyed since. The report warns of it, but a correction there would undo something right, so the records manager has to check the history.
- A correction can wait long: a returned draft holds it back until the draft is through or discarded.
- A version in review or approval below its basis can be published as it is. The report only shows it.
- **The owner decides alone on a move**, and the new unit's authors read the draft from that moment. This is less than ADR 0083 asks for a level. It rests on the owner answering for the document, and on the concept not asking for more.
- **An owner scoped to one unit cannot hand a document on by themselves.** Someone has to give them a role in the target unit for the move, or a person with a wider role has to be the owner first. A document without an owner cannot move at all, as it cannot get a group, until a version names one.
- An author with rights in both units has to ask the owner, also for an honest correction of a wrong unit in a later draft.
- Drafts moved before this change stay where they are. The check looks at the change from now on: a draft already in another unit is not a new reach, so this ADR does not list or repair them.
- A correction can name an owner who left: the version says so, and the cockpit reports the document as orphaned. A records manager who is not cleared cannot correct, so a document whose groups hold no records manager stays until one is added.
- A correction of the title does not touch rendered files. They hold the title of the version, which is what the correction restores.
- The candidates of both reports are found in SQL, on the JSON values of the versions. The rule then runs again in Scala on the rows found, which is what decides, but a change of the JSON layout would hide rows. Tests of both reports run against PostgreSQL to catch that.
- Reading a report writes an audit entry each time the tab is opened.

## Open questions, decided conservatively

- **Whether a move needs a second person.** The concept says nothing. Conservative here means the person who already answers for access, and no new process. The product owner should confirm, or ask for the approval step.
- **Whether the rule should apply to a first version that was withdrawn.** It does: it has a basis, as with the other checks (ADR 0074, decision 6).
- **Whether honest changes should be confirmed away.** Left out until someone asks for it.

## Follow-ups

- Must be done: the product owner confirms this ADR (status → Accepted), decisions 14 to 17 in particular.
- Could: a decision "stays as it is" that ends a finding, if the report stays long.
- Could: show the reports to scoped records managers in the UI.
- Could: a list of open versions that left the unit of their basis, for drafts moved before this change.
- Could: tell the owner when a draft is below its basis.
- Should: decide whether a version below its basis may be approved at all; today `checkLevel` runs only at submission.

## Reversibility

**high**: One action, two endpoints for reading, one for correcting, two issue codes, no schema. Dropping the check of decisions 14 and 15 brings back the old behaviour of saving. Corrections stay, with their audit entries, and they can be undone only by a later version, as there is no way to set a value by hand.
