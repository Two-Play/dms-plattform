# 0033: Version comparison computed on request from the structured version packages; the change reason is the author's change summary

| Field | Value |
|---|---|
| Status | Accepted (2026-10-28, implemented in DMS-54; addenda 2026-10-29, DMS-103 and DMS-126) |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

US-05.1 (DMS-54): a reader compares two versions of a document to grasp the changes quickly. Text, field value and metadata changes are shown separately; only versions the reader may read can be compared; the author's change summary is shown above the comparison.

Given: every version is a complete, frozen package (ADR 0021) with structured content (sections, blocks, inline text with placeholders, ADR 0007), field values with their origin, the template and class version and, from approval on, the rendition and the metadata snapshot. `templates-api` already has a word-level LCS diff for the deviation report against the template (DMS-36, ADR 0022) and a word count comparison for editorial versions (`TextChange`, DMS-46). Each version stores `change_reason` (required for minor versions by a check constraint, optional for major ones) when it is started (`POST /documents/{id}/versions`). Reading is decided per version and status by the in-app policy (`ReadDocument`, ADR 0018), including workflow participants and their deputies (ADR 0028).

## Decision

1. **Server-side comparison of the structured packages, computed on request.** `ContentDiff` (templates-api) compares two contents section by section (matched by id) and block by block (LCS alignment by block kind, recursing into lists, tables, conditions and repeats) with a word-level LCS diff inside headings and paragraphs; it keeps unchanged text for context and reports structural changes (heading level, formatting, condition, repeat, clause version, header rows) and section changes (inserted, deleted, renamed, moved, lock). `VersionComparison` (documents-api) adds the field values of the document class (with origin) and the metadata (core and system fields plus version properties: status, change kind, class version, approval, publication, rendition checksum). Both are pure functions; nothing is stored.
2. **Endpoint.** `GET /api/v1/documents/{id}/compare?from=&to=` returns the two versions, the change summaries, the text comparison as spans (`Equal`/`Inserted`/`Deleted`, plain text) and the changed values. Both versions need `ReadDocument` in their current status, checked exactly as reading one version (with workflow participants and deputies); one unreadable version denies the whole comparison (403) instead of comparing with an empty side.
3. **Change summary = `change_reason`.** No new column or migration: the reason given when a version is started is its change summary. The comparison lists the summaries of all readable, not withdrawn versions after the earlier compared version up to the later one, so that comparing 1.0 with 3.0 also shows what 1.1 and 2.0 changed.

## Alternatives considered

| Criterion | Chosen: structured diff on the server, on request | Client-side text diff (e.g. jsdiff on plain text of both versions) | Diff of the PDF renditions | Precomputed diff stored per version |
|---|---|---|---|---|
| Separation of text, fields, metadata | Yes, from the package parts | Text only; fields and metadata need extra code in the client | Only what the PDF shows; no origin or system metadata | Yes |
| Structure (sections, lists, tables, clauses, conditions) | Kept: changes located by section and block path | Lost in the plain text | Lost; layout changes produce noise | Kept |
| Open versions (drafts) | Yes | Yes | No: drafts have no sealed rendition | Must be recomputed on every save |
| Any pair of versions | Yes | Yes | Yes | Only predecessor pairs, unless n² diffs are stored |
| Authorization | One place: the API checks both versions | API still has to deliver both full versions | Needs both renditions readable | Same as chosen |
| Reuse | LCS and tokenizer of the deviation report | New dependency in the frontend | PDF text extraction exists only in tests | Same code plus storage and migration |
| Cost per request | O(n·m) per block pair; documents are small enough | Moves the cost to the browser | High (text extraction) | Cheap to read |

For the change summary, a separate `change_summary` column captured on submit was considered: it would let authors describe the result after editing, but needs a migration, a second text with overlapping meaning next to the reason (which editorial versions already require) and a UI to edit it. It can be added later without changing the comparison API (`summaries[].summary`).

## Consequences

### Positive

- Readers see changes in context, separated into text, field values and metadata, for any two readable versions, drafts included.
- No storage, no migration, no background job; the comparison always matches the frozen packages.
- The word-level diff shares its tokenizer and LCS with the deviation report, so both show changes the same way (placeholders as `{{path}}`, line breaks as words).

### Negative / Cost

- Each request computes the diff (O(n·m) per paired block and per section list); very long paragraphs cost memory for the LCS table. Acceptable for policy and contract sizes; a cap or Myers' algorithm would be the remedy.
- Clause references are compared by key and version only; the changed clause text itself is not expanded.
- Placeholders are compared as placeholders; the values they render are compared in the field values part, not inside the text.
- The summary is the reason given when the version was started; since DMS-103 authors can change it until they submit the draft (see the addendum).

## Follow-ups

- Done (DMS-103, see the addendum): a frontend form to start a new version with its change summary; authors edit the summary while the version is a draft.
- Done (DMS-126, see the addendum): a summary is required for major versions too.
- Possible: expand clause references to their clause text; side-by-side view.
- Ruled out: comparing renditions; storing diffs.

## Addendum 2026-10-29: starting versions and editing the change summary in the UI (DMS-103)

Decisions:

1. **Still one text, `change_reason`; no migration.** The author maintains it from starting the version until submission through `PUT /api/v1/documents/{id}/draft/change-summary` (`{"reason": ...}`). The guard trigger (ADR 0021) already lets drafts change the column; the service checks the same rules as on start (required for Minor, trimmed, at most 4000 characters, now also applied on start) and the update condition `status = 'Draft'`. From submission on the summary is frozen with the version (409). It is not part of a working state (content and values are unchanged), so no working state is added; the change is audited as `document.change-reason-changed` with the text before and after (an unchanged text records nothing). Authorization: `EditDraft` on the draft; on a submitted version, where `EditDraft` means reviewers' suggestions, the caller only needs to read it to learn that it can no longer change.
2. **The backend tells the UI whether a version can be started.** `DocumentView.canStartVersion` (the caller may start the next version now) and `startVersionBlocked` (`OpenVersion` or `ScheduledVersion`, only for callers who could start one) are computed with the same policy question as `POST …/versions` (`EditDraft` on a draft of which the caller is the author) and the same state rules. The UI hides "New version" for callers without permission and disables it with the reason while the document's state blocks it (hidden when the caller looks at the version in progress itself). The endpoint still decides.
3. **Required as the backend demands** (superseded by DMS-126, see below). The dialog requires a summary for editorial (Minor) versions and recommends it for major ones; making it mandatory for major versions (fachkonzept 5.1: "jeweils mit Begründung und Änderungsübersicht") would change the check constraint and the API for all clients and is left as a follow-up decision.
4. **File and Word documents** keep the behavior of ADR 0042: the new draft takes the original, its field definitions and values over; the dialog and the confirmation tell the author to upload the revised file in the draft, where the file panel offers the upload.

| Criterion | Chosen: separate endpoint for the summary | Summary as part of `PUT …/draft` | Separate `change_summary` column captured on submit |
|---|---|---|---|
| Working state | None added (summary is version metadata) | Every summary change adds a working state with unchanged content | None |
| Clients of `PUT …/draft` (form, Word fields, partner take-over) | Unchanged | Must send the summary or a default meaning "keep" | Unchanged |
| Migration | No | No | Yes, plus a second text next to the reason |
| Audit | Own action with before/after | Mixed into `document.draft-changed` | Own action |

Consequences: positive: readers see the corrected summary in the history and the comparison at once; no migration. Negative: the summary of a submitted version cannot be corrected anymore (a return to draft makes it editable again); the UI computes the "becomes version" labels from the valid version's label, while the server counts from the highest approved number (they differ only after withdrawn approved versions). Reversibility: high; the endpoint and the two view fields can be removed without data changes.

### DMS-126 (2026-10-29): a change summary for every new version

Decision (product owner, following business concept 5.1: "jeweils mit Begründung und Änderungsübersicht"): every **new** version needs a change summary, major and minor alike. Only the first version 1.0 of a document, which has no predecessor, is exempt.

- **Where it is checked.** `DocumentService.checkChangeReason` requires a non-empty trimmed summary whenever the version has a predecessor; it is used on start (`POST /api/v1/documents/{id}/versions`, and the revision of a periodic review, `POST /api/v1/reviews/{taskId}/revise`, which starts the version through the same service method) and on edit (`PUT …/draft/change-summary`). Otherwise 422 with "a new (major) version needs a change summary: what changed and why" (or "an editorial (minor) version …"). "Has a predecessor" is the version number above `Versioning.first` (1.0): `Versioning.next` returns 1.0 only while no version was ever approved, and a new version can only start from a valid one, so every started version is 1.1 or higher. No other code path inserts versions (document creation inserts only 1.0; renditions, withdrawal and file or Word uploads change existing versions). The seed script `scripts/dev_setup.py` only creates first versions.
- **UI.** The "New version" dialog marks the summary as required for both kinds of change; the change summary card on drafts requires it on every version except 1.0; "Revise" in the task inbox opens a dialog that asks for the summary of the new draft instead of starting it with one click.
- **The database check of V9 stays as it is** (`change_kind = 'Major' or length(trim(coalesce(change_reason, ''))) > 0`). Existing major versions without a summary, frozen or still drafts, stay valid; tightening the constraint would need a backfill with invented summaries for frozen versions, which the guard trigger (ADR 0021) rightly refuses. Consequence: the rule is enforced by the application only; submitting checks the same rule, so an existing major draft without a summary has to get one before it can be submitted (`DocumentService.submit`, 422). A later migration could add the constraint as `not valid` for new rows only, if a second writer ever appears.
- **Alternatives.** Required only in the UI (rejected: other API clients and the review revision would still create versions without a summary); a stricter check constraint (rejected for the existing data, see above); a default text such as "no summary" (rejected: it satisfies the rule without informing readers).
- **Consequences.** Positive: the version history and comparisons always explain new versions; one rule for both kinds of change, simpler to explain. Negative: one more required input when starting a major version, including the revision from the task inbox; API clients that started major versions without `reason` now get 422.
- **Reversibility: high.** Making the summary optional for major versions again is a change of `checkChangeReason` and the two dialogs; no data changes.

## Reversibility

**high**: Rationale: the comparison is a pure function behind one read-only endpoint; nothing is stored. Replacing the algorithm, adding a stored summary field or moving to precomputed diffs changes no data and keeps the endpoint shape.
