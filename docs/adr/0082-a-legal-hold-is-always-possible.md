# 0082: A legal hold is always possible and needs no clearance, what the caller sees of the document still does; the original's file name and the default of the retention view follow what stands

| Field | Value |
|---|---|
| Status | Proposed (2026-11-06, implemented in DMS-156) |
| Date | 2026-11-06 |
| Decided by | Philippe |

## Context

ADR 0072 decides legal holds on the governing versions, and every governing version must permit it. An open draft is one of them, so an author can make a draft `Confidential` and delay a hold until someone cleared acts. ADR 0072 named this as a negative consequence and left it to records management.

The user decided on 2026-11-06: a legal hold is always possible. Records managers may set it without seeing a stricter draft, because a hold reveals no content and protects at once. This replaces "an open draft keeps a say" for holds.

The check of the code found:

- **Only clearance is left to take away.** Every hold endpoint first asks `checkedSubject`, `ManageRetention` on the tenant resource. Only an assignment without unit and class passes, and such an assignment covers every document. So `ManageRetention` on a document, for someone who passed, can only fail on `Policy.clearedFor`.
- **The title was shown by the right to manage.** `GET /legal-holds/{id}` showed `document.title` to whoever passed every governing version, and nothing to anyone else. With a hold past a draft, the caller should still see the title they know.
- **`document.title` is not safe as such.** Since ADR 0074 a later draft leaves it alone, but a draft saved before that may have changed it for good (ADR 0074, decision 10), and a first draft sets it. It says nothing about which version it came from.
- **The preview shows hits.** The search only finds versions the caller may read, and its title is the title of that version (`SearchDocuments`). Drafts are not indexed. So the preview shows no title of a stricter draft. Its field `manageable` blocked the request, in the API by a 403 and in the dialog by `confirmable`.
- **The hold page offers removal by the title.** `d.title !== undefined` stood for "may remove". This no longer holds once a title is shown past a version the caller is not cleared for.
- **Other places are safe.** Create, add, remove and release answer with ids and hold data only. The document view shows the version the caller may read and the hold names. Audit entries, events and notices carry ids, the hold's name and the reason. An evidence report asks for clearance for every version (ADR 0072).

Two could points of ADR 0072 go along: `GET .../file` has no marking of a version out of force (decision 12 left it), and `GET .../retention` without `version` takes the latest readable version, also one that never took effect.

## Decision

### Setting a hold

1. **Setting a hold and adding to it need the records manager role for the whole tenant, and nothing else.** No clearance for any version: not for a draft, and not for a valid version either. Unknown documents stay 404 and nothing is stored, and the limit of 1000 documents stays. This applies to named documents and to the hits of a confirmed search.
2. **"Always" is taken as it is said.** The decision names the stricter draft as the case that hurts, but its reason (a hold reveals nothing and protects at once) holds for every version, and a refusal for a confidential valid version would leave a matter without protection until someone cleared is found. Where the wording leaves this open, the choice goes to protecting the record, because a hold that was not set cannot be made up for, while one set by mistake can be released. See the alternatives and the follow-ups: the user should confirm this reading.
3. **A search still only reaches what the caller may read.** It runs as the caller, as before. A document they cannot find is put under a hold by its id.

### What the caller sees

4. **The title in a hold.** Someone cleared for every version sees the document's title. Anyone else sees what a version they are cleared for states in its own `title` field, never `document.title`: the latest valid one, else the latest that was in force, else the latest (`Versioning.naming`). A discarded draft and a destroyed version name nothing.
5. **The neutral text.** Without such a version the API leaves `title` out, as before, and the page shows "Dokument für Sie nicht sichtbar", without a link. The caller then knows the id and that they put it under a hold.
6. **Preview.** The title stays that of the hit. `manageable` keeps its meaning (the caller may manage the document) and stops being a refusal: it now means that only someone cleared can take the document out again. The dialog shows this as a note and can be confirmed.

### Undoing a hold

7. **Taking a single document out stays as strict as it was**: `ManageRetention` on every governing version (ADR 0072). It ends a protection, and that is the action that must not happen by someone who cannot judge the document.
8. **`removable` in `HeldDocumentView`** says so, and the page offers removal by it, not by the title.
9. **Releasing a hold as a whole does not change**: role for the whole tenant, as before.

### What stays

10. **Audit as today.** The same entries with the same reason on the hold and on each document. They record no hint that a version was passed: the entries of a confidential document would otherwise tell more than they do now.
11. **Other rights stay with ADR 0072.** The retention rule of a document, destruction, and evidence reports are decided as there. Decision 5 of ADR 0072 stands for them, and for holds only as far as removal goes.

### The two smaller points

12. **`GET .../file` carries the marking of filled files in its name** (`WordFilling.fileName`), for every version out of force. This changes decision 12 of ADR 0072. The bytes, the checksum and `ETag` stay: it is still what was uploaded.
13. **`GET .../retention` without `version`** shows the open version the caller may read, else the last of `Versioning.governing` among the versions they may read. So a latest version that never took effect is left out, and a document with nothing else shows it still. With `version` nothing changes. `PUT` answers the same way.

No migration; V59 stays free. No new dependency.

## Alternatives considered

| Criterion (who may set a hold) | Chosen: the role, no clearance | Only versions not in force lose their say | Leave ADR 0072 | Hold request that goes to someone cleared |
|---|---|---|---|---|
| An author can delay a hold | No | No | Yes | Yes, until it is acted on |
| A confidential valid document can be held at once | Yes | No, only by someone cleared | No | No |
| "A legal hold is always possible" | Yes | Only for drafts | No | No |
| A records manager outside the group learns a document exists | As before: 403 and 404 differ today | As before | As before | As before |
| A person not cleared affects a confidential document | Yes: it cannot be destroyed until released | Only past a draft | No | No |
| Reverses ADR 0072 tests beyond the stricter draft | Yes | No | No | No |
| Effort | Less code than before | One more rule on status | None | A new step and its notices |

| Criterion (title past a version) | Chosen: own title of a cleared version | The document's title | Neutral text for everything past a version |
|---|---|---|---|
| Can show a title from a version the caller is not cleared for | No | Yes: first drafts, drafts before ADR 0074 | No |
| Records manager recognises what they put under a hold | Yes, if a version is known to them | Yes | No |
| Stable when a draft changes | Yes | Yes, since ADR 0074 | Yes |
| Effort | One function | None | None |

| Criterion (`manageable` in the preview) | Chosen: keep, as a note | Remove the field | Always true |
|---|---|---|---|
| Caller knows before setting that they cannot undo it | Yes | No | No |
| API shape stays | Yes | No | Yes |
| Tells that some version is out of reach | Yes, as a 403 did and a removal would | No | No |
| Field means what its name says | Yes | Not relevant | No |

| Criterion (marking of `GET .../file`) | Chosen: file name | Leave it | Stamp the bytes |
|---|---|---|---|
| A saved original says it is out of force | Yes, until renamed | No | Yes |
| Same as filled files | Yes | No | No |
| Bytes and checksum stay what was uploaded | Yes | Yes | No |

| Criterion (default of the retention view) | Chosen: open, else what stands | Latest readable (before) | What stands, also while a version is open |
|---|---|---|---|
| A never-valid version is shown as the document's rule | No | Yes | No |
| The rule set for a draft is visible while it is open | Yes | Yes | No |
| Works on what the caller may read | Yes | Yes | Yes |

## Consequences

### Positive

- No author, and no missing group membership, stands between a matter and its protection.
- The rule is simpler than the one it replaces: one role check, for any document.
- A caller past a version sees the title they know or nothing, and it no longer depends on where `document.title` came from.
- A removal is only offered to those who can do it.
- A saved original of a version out of force says so, and the retention view no longer shows the rule of a version that never stood.

### Negative / Cost

- **A records manager not cleared for a document can stop its destruction.** They see nothing of it, and the audit trail names them, but a confidential document stays until a hold is released. This goes further than the stricter draft the decision names.
- **A mistake is harder to undo.** Someone who added the wrong document past a version needs a cleared records manager to take it out, or must release the whole hold and set it again. If no records manager is in the access group, someone has to add one.
- `removable` and `manageable` tell the caller that some version is out of their reach. A 403 told them the same before; the title and the level of that version stay hidden.
- A hold list can contain entries that say nothing but their id, reason and author. The reason text is the records manager's own.
- Reading a hold looks at all versions of each document, as it did.
- A file name is lost by renaming, as for filled files.
- A consumer that took `manageable: false` as a refusal now gets a hold where it expected a 403.

## Follow-ups

- Must be done: user confirms this ADR (status → Accepted), in particular decision 2: no clearance for valid versions either. If the answer is no, `LegalHoldApi.existing` gets a check on the versions in force, see Reversibility.
- Should: tell the owner of a document that it came under a hold, if records management wants that; no such notice exists today.
- Still open from ADR 0072, not touched here: an evidence report with sections left out for callers not cleared.

## Reversibility

**high**: No schema or stored data changes. The checks are in `LegalHoldApi`, `Versioning.naming` is an addition, and `removable` is an added field. Going back, or to the narrower rule, is a check on the governing versions or the versions in force in `existing`. Holds set in the meantime stay until they are released, and rightly so.
