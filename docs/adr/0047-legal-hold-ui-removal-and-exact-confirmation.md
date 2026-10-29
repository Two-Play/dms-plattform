# 0047: Legal hold UI: own page for tenant-wide records managers, a marker without hold details, removal of single documents, exact confirmation of search results

| Field | Value |
|---|---|
| Status | Proposed (2026-10-29, implemented in DMS-106) |
| Date | 2026-10-29 |
| Decided by | Philippe |

## Context

ADR 0031 (DMS-56) built legal holds in the backend: holds per matter, documents added by name or from a search the records manager confirms by its hit count, release with reason, a database guard against destruction and the object legal hold on renditions. Its follow-ups asked for the frontend (a "Legal holds" section for records managers, creation from the search page with the confirmation, release, and the marker on the document page). DMS-106 adds that UI and closes gaps found on the way:

- The confirmation compares counts, not the set of documents: if one hit disappears from the index and another appears, the count matches although other documents are held (ADR 0031, negative consequences).
- A document added by mistake can only be released with the whole hold (ADR 0031: "Later: removing a single document from a hold with reason").
- Lists (document list, search hits, library) do not say that a document is held; only the document view has `legalHold`.

Given: every hold endpoint requires `ManageRetention` on the tenant resource, i.e. a records manager assignment without unit or class restriction; `legalHolds` (names) in the document view is only sent to callers with `ManageRetention` on the document; `GET /api/v1/me` lists the caller's assignments with their restrictions; there is no destruction path and no destruction action in the UI yet (DMS-82). Withdrawing a version (periodic review, ADR 0030) takes it out of force but keeps it; a legal hold does not forbid that (business concept 7.4: a hold suspends retention expiry and destruction).

## Decision

1. **Place.** A page `/legal-holds` with its own navigation entry, shown only to users with a tenant-wide records manager assignment (the same rule as the backend: `canManageLegalHolds` checks `me.assignments` for `RecordsManager` without unit and class). Not under Administration, which is for tenant administrators; records managers usually are not. The page lists active, released or all holds; a hold opens its detail (matter, reason, who set and released it, documents with who added or removed them how, when and why). Actions while active: add documents, remove a single document, release. A user reaching the page without the role gets an explanation, not the page.
2. **Adding documents.** From a search: the records manager enters the search (text, class, language) or uses "Put under legal hold" on the search page (current search, into an active hold or a new one), checks it, reviews the documents of the whole result (all pages, server side) and confirms exactly these. Named documents: pasted links or ids (also for documents the search does not find: drafts, withdrawn or archived versions). The document page gets no hold action; its change stays the marker.
3. **Exact confirmation.** New `POST /api/v1/legal-holds/search-preview` (body: the search filter) runs the search as the caller and returns the hit count, the distinct documents (title and number from the hits, whether the caller may manage each, whether a hold covers it already) and a `fingerprint`: SHA-256 (hex) over the sorted document ids. `searchFilter.expectedFingerprint` (optional, the UI always sends it) makes create and add refuse with `409 result_changed` unless the search finds exactly these documents; `expectedCount` stays required as before. Same 422 above 1000 hits.
4. **Removing a single document** (migration V30). `POST /api/v1/legal-holds/{id}/documents/{documentId}/remove` `{reason}` while the hold is active; tenant-wide records manager and `ManageRetention` on the document, as for adding. The row of `dms.legal_hold_document` stays as history with `removed_at`, `removed_by`, `removal_reason`; it no longer counts (`dms.document_under_legal_hold`, `LegalHoldLookup`, `documentCount`, release). Rows get a surrogate key; a unique index on `(hold_id, document_id) where removed_at is null` keeps a document at most once in a hold at a time, so it can be added again later as a new row. A trigger allows only setting the removal columns once while the hold is active; deletes stay forbidden. Audit `legal-hold.document-removed` (hold) and `document.legal-hold-removed` (document, with `underLegalHold`), outbox event `legal-hold.document-removed`, and the object legal hold synchronization if no other hold covers the document. The hold detail lists removed documents with the removal.
5. **Marker without details.** `legalHold` (boolean) is added to `DocumentSummaryView`, `SearchHitView` and `LibraryItemView` for every reader, computed with `LegalHoldLookup.heldAmong` over the listed documents. The UI shows "Legal Hold" there and on the document page. Hold names are only in the document view's `legalHolds`, which the backend sends to callers with `ManageRetention` on the document; for them the marker opens a popover with the names, matter references and links to the holds. Everybody else gets the marker with the explanation that the document cannot be destroyed, never a name. A popover instead of a hover tooltip: it works with keyboard and touch and can hold links.
6. **Release.** The backend requires a reason and no second person. The UI additionally asks for an explicit confirmation that the matter is closed, since a release cannot be undone. Four-eyes or step-up authentication stay a follow-up (ADR 0031).
7. **Destruction and withdrawal.** There is no destruction action in the UI; when DMS-82 adds one, it must hide or disable it for documents with `legalHold` (the database guard refuses it anyway). Withdrawing stays possible under a hold, as in the backend: the document and all versions are kept.

## Alternatives considered

Place of the page:

| Criterion | Chosen: own entry for tenant-wide records managers | Tab under Administration | Entry for every records manager (any scope) |
|---|---|---|---|
| Who finds it | Exactly the users the endpoints accept | Only tenant administrators see Administration | Unit-scoped records managers see a page that answers 403 |
| Consistency | Like the compliance cockpit (own role, own entry) | Mixes configuration and records management | Inconsistent with the backend |
| Effort | Navigation entry with a predicate | Tab | Navigation entry by role |

Confirmation of a search result:

| Criterion | Chosen: preview with fingerprint over the document ids | Count only (ADR 0031) | Client sends the ids of the reviewed documents |
|---|---|---|---|
| What is confirmed | Exactly the reviewed documents | Only how many | Exactly the reviewed documents |
| Request size for 1000 documents | One hash | One number | 1000 ids, and then the search is redundant |
| What the records manager sees | All documents of the result (server pages through) | The first page of the search | Only pages the client loaded |
| Compatibility | Optional field; count still required | Unchanged | New semantics of `documentIds` |

Removing a document:

| Criterion | Chosen: removal columns on the row, row kept | Delete the row | Not possible (release the whole hold) |
|---|---|---|---|
| History and proof | Who removed it when and why, the addition stays visible | Lost | Kept, but the wrong document stays held |
| Adding it again | New row (surrogate key, partial unique index) | Possible | – |
| Effort | Migration, guard trigger, queries filter `removed_at` | Grant delete, loses the audit trail of the hold | None |

Marker in lists:

| Criterion | Chosen: boolean per listed document, names only in the document view for managers | Names in every list | Marker only on the document page |
|---|---|---|---|
| Leaking matters | No hold names in lists | Matter names visible to every reader | No leak |
| Usefulness | Held documents recognisable in search and library | Same | Only after opening each document |
| Cost | One query over the active holds per list request | Same plus names | None |

## Consequences

### Positive

- Records managers manage holds in the UI without the API; the documents of a search are reviewed in full and confirmed exactly.
- A wrongly added document can be taken out without releasing the matter, and the history shows it.
- Readers see in every list that a document is held, without learning which matter holds it.

### Negative / Cost

- `heldAmong` reads the documents of all active holds and filters in memory; with very many held documents per tenant a query with the listed ids would be cheaper.
- The fingerprint check runs the search twice (preview and confirmation) and pages through up to 1000 hits each time.
- The search covers published versions only; other documents must be named by link or id.
- Records managers of a unit still cannot work with holds at all (ADR 0031).
- The release has no four-eyes principle; the confirmation checkbox is no control, only a deliberate step.

## Follow-ups

- Must be done (DMS-82): the destruction action and the destruction proposals hide or refuse documents with `legalHold`; the UI shows the marker there as well.
- Should be done: a document picker (by title or number) for naming documents instead of pasting links; adding a document from its page for records managers once the document page has an actions menu.
- Later: four-eyes or step-up authentication for releasing a hold (ADR 0031); `heldAmong` with the listed ids instead of all held documents; holds for unit-scoped records managers.

## Reversibility

**high** for the UI and the list fields (additive). **medium** for the removal: the columns and the history they hold stay; disallowing removal again means revoking the update grant and keeping the rows as they are.
