# Module `archive`

Retention, legal hold, destruction and archive packages (business concept 7.4).

Layout: `api` holds public types and interfaces, `core` the implementation. Other modules may only depend on `api`.

## Legal holds (US-08.1, DMS-56, ADR 0031)

A legal hold suspends the destruction of the documents it covers until it is released explicitly. A hold names the matter (Vorgang) it was set for, with an optional reference such as a case number, and covers any number of documents; a document can be covered by several holds and is under legal hold while at least one active hold covers it. A hold covers the whole document, all versions including later ones.

- `api`: `LegalHold` (id, name, matter reference, reason, creator, release, document count), `HeldDocument` (document, `HoldSource` Document|Search, reason, who, when), `NewLegalHold`, `LegalHoldError`; `LegalHoldLookup` for the destruction proposals and the destruction path (DMS-82): `isUnderLegalHold(document)`, `heldAmong(documents)`, `activeHolds(document)`; `LegalHoldEvents` (outbox events, job kind).
- `core`, `LegalHoldService` (inside `Db.transact(tenant)`): `create(hold, documents, source, actor)`, `addDocuments(id, documents, reason, source, actor)` (skips documents the hold covers already; returns the new ones), `release(id, reason, actor)`, `get`, `list(active)`, `documents(id)`. Name and reason are required (`Invalid`); a released hold cannot change (`Released`). Audit entries `legal-hold.created`, `legal-hold.documents-added`, `legal-hold.released` on the hold and `document.legal-hold-set` / `document.legal-hold-released` on each document, all with the reason; outbox events `legal-hold.created`, `legal-hold.documents-added`, `legal-hold.released`; one job `archive.legal-hold-sync` per affected document. `LegalHoldStore` implements `LegalHoldLookup`.
- `core`, `LegalHoldSync`: handler of `archive.legal-hold-sync`. Sets the S3 object legal hold (ADR 0016) on every rendition object version referenced by the document's versions while the document is held, and removes it afterwards. Reads the current state and repeats if it changed meanwhile, so duplicate and reordered jobs are safe. Best effort: a missing object, a store without legal hold support or missing permissions are logged as warnings and skipped; an unreachable store fails the job (retried by the job runner). Registered in `dms.app.Background`.
- Migration V19: `dms.legal_hold` (released once with time, releaser and reason; never deleted; only the release columns are updatable) and `dms.legal_hold_document` (insert-only, only into active holds), both with RLS on `dms.current_tenant()`. `dms.document_under_legal_hold(document)`. Trigger `document_version_legal_hold_guard`: no version of a held document becomes `Destroyed` (locks the document row against a concurrent hold). Trigger `document_version_rendition_legal_hold`: a new rendition of a held document enqueues the synchronization.

### API (`dms.app.LegalHoldApi`)

All endpoints require `ManageRetention` (records manager) for the whole tenant; every document put under a hold also requires `ManageRetention` on the document (scope, confidentiality), otherwise nothing is stored (403).

| Method | Path | Behaviour |
|---|---|---|
| POST | `/api/v1/legal-holds` | `{name, reason, matterReference?, documentIds?, searchFilter?}` → 201 hold with `addedDocumentIds`. 422 without name or reason. |
| GET | `/api/v1/legal-holds?status=active\|released\|all` | Holds, latest first (default active). |
| GET | `/api/v1/legal-holds/{id}` | Hold with its documents (`documentId`, `title` if the caller may manage the document, `addedVia`, `reason`, `addedBy`, `addedAt`). |
| POST | `/api/v1/legal-holds/{id}/documents` | `{reason, documentIds?, searchFilter?}`; documents already covered are skipped. 422 without reason or documents, 409 `released`. |
| POST | `/api/v1/legal-holds/{id}/release` | `{reason}`; 422 without reason, 409 `released` if released before. |

`searchFilter` = `{q?, class?, status?, lang?, expectedCount}`: the parameters of `GET /api/v1/search` plus the hit count (`total`) the caller confirms. The server re-runs the search as the caller and puts the documents of all hits under the hold; `409 result_changed` if it finds a different number now, `422 too_many_documents` above 1000 hits. The document view (`GET /api/v1/documents/{id}`) shows `legalHold` and, for callers with `ManageRetention` on the document, `legalHolds` (`id`, `name`, `matterReference`, `createdAt`).

Tests: `LegalHoldServiceSpec` (PostgreSQL and SeaweedFS: service, RLS, history guards, destruction guard, object legal hold), `LegalHoldApiSpec` in `app` (HTTP, authorization, reasons, search-result hold with OpenSearch, several holds and release).
