# 0031: Legal holds: hold records per matter covering documents, a database guard against destruction, object legal hold on renditions

| Field | Value |
|---|---|
| Status | Accepted (2026-10-28, implemented in DMS-56) |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

US-08.1 (DMS-56, business concept 7.4): records managers put documents under legal hold so they are not destroyed during litigation. A legal hold can be set on a document, a matter ("Vorgang") or a search result; documents under legal hold never appear in destruction proposals; setting and lifting require a reason and are audited. A hold suspends retention expiry and destruction until it is released explicitly.

Given: documents keep all versions, nothing is deleted (ADR 0021); `Destroyed` is a version status reached from `Archived` (status guard of V9), but there is no destruction path, retention rule evaluation or destruction proposal yet (DMS-82, US-08.2). Renditions are stored in S3 with Object Lock (ADR 0016, 0025); `ObjectStore.setLegalHold` exists and SeaweedFS supports `PutObjectLegalHold` (verified by `ObjectStoreSpec` and the tests of this change: a held version cannot be deleted). There is no case/matter entity in the platform: "Vorgang" elsewhere means a workflow instance (`workflow_case`), which is not what a litigation matter is. The search (ADR 0029) filters by the caller's permissions inside OpenSearch and is eventually consistent with the database. The permission matrix has one row "Retention / legal hold", granted to records managers only (`Action.ManageRetention`, ADR 0018). Every change is audited in the hash chain (ADR 0017); events go through the outbox (ADR 0010).

## Decision

1. **Model (migration V19).** A legal hold is a record `dms.legal_hold` (name, optional `matter_reference`, reason, creator; release time, releaser and release reason). It represents the matter: "a hold on a Vorgang" is a hold named after the matter with its reference, to which the matter's documents are added. Its documents are rows of `dms.legal_hold_document` (hold, document, `added_via` Document|Search, reason, who, when). A hold covers the whole document, all versions including later ones. A document can be covered by several holds; it is under legal hold while at least one active hold covers it. Holds are released once and never deleted; documents are never removed from a hold (history). Both tables have RLS on `dms.current_tenant()`, insert/select grants, and only the release columns are updatable.
2. **Destruction guard.** `dms.document_under_legal_hold(document)` (SQL, caller's rights) is the single definition used by queries. A trigger on `dms.document_version` refuses the change to `Destroyed` while the document is held, for every database user. It locks the document row `FOR UPDATE` first; adding a document to a hold takes `KEY SHARE` on the same row through the foreign key, so a concurrent hold and destruction serialize. `LegalHoldLookup` in `archive-api` (`isUnderLegalHold`, `heldAmong`, `activeHolds`) is what the destruction proposals of DMS-82 must use to leave held documents out.
3. **Object legal hold.** The object legal hold (S3 Object Lock) of the documents' renditions follows the database: a job `archive.legal-hold-sync` per affected document (enqueued in the same transaction when documents are added or a hold is released, and by a trigger when a held document gets a new rendition) sets the legal hold on every referenced rendition object version while the document is held and removes it afterwards. The job reads the current state, so duplicates and reordering are harmless. Errors a retry does not fix (missing object, no legal hold support, missing permission) are logged and skipped (best effort); an unreachable store fails the job for a retry.
4. **Search results.** `searchFilter` takes the parameters of `GET /api/v1/search` plus `expectedCount`, the hit count the records manager saw and confirms. The server re-runs the search as the caller (so only documents they may read), refuses with `409 result_changed` if the count differs and with `422 too_many_documents` above 1000 hits, and puts the distinct documents of all hits under the hold (`added_via = Search`). Explicit `documentIds` are accepted too, for documents named by the client (also from a result list it shows).
5. **Authorization.** `ManageRetention` (records manager), as in the matrix row; no new action. Hold-level operations (create, list, read, add, release) require it on the tenant resource, i.e. a records manager assignment for the whole tenant: a hold may span organizational units and releasing it affects all its documents. Every document put under a hold additionally needs `ManageRetention` on the document (class/unit scope, confidentiality); if one is denied, nothing is stored (403).
6. **Reasons, audit, events.** Name and reason are required on creation, a reason on adding and on release (422 otherwise; also checked by the database). Audit entries: `legal-hold.created`, `legal-hold.documents-added`, `legal-hold.released` on the hold and `document.legal-hold-set` / `document.legal-hold-released` on every document, each with the reason. Outbox events `legal-hold.created`, `legal-hold.documents-added`, `legal-hold.released` (the latter with the documents no longer held).
7. **API.** `POST /api/v1/legal-holds` `{name, reason, matterReference?, documentIds?, searchFilter?}`, `GET /api/v1/legal-holds?status=active|released|all`, `GET /api/v1/legal-holds/{id}` (with documents), `POST /api/v1/legal-holds/{id}/documents` `{reason, documentIds?, searchFilter?}`, `POST /api/v1/legal-holds/{id}/release` `{reason}`. The document view gets `legalHold` (for every reader) and `legalHolds` (the active holds, for callers with `ManageRetention` on the document).

## Alternatives considered

Model:

| Criterion | Chosen: hold record per matter + covered documents | Flag/column on `dms.document` | Hold rows per document only (no grouping) |
|---|---|---|---|
| "Vorgang" / matter | The hold is the matter: name, reference, all its documents released together | Not expressible | Matter only as a free-text reference repeated per row |
| Several holds per document | Natural: held while any active hold covers it | Needs a counter, loses which matter holds it | Possible, but releasing a matter means finding all its rows |
| History and reasons | Per hold and per added document, immutable | Overwritten | Per row |
| Effort | Two tables, guards | Smallest | One table |

Search result:

| Criterion | Chosen: server re-runs the search, confirmed count | Client sends the document ids of the result | Store the search as a dynamic hold (applies to future hits) |
|---|---|---|---|
| What gets held | Exactly what the caller can find now; `409` if it changed since they looked | What the client loaded (only pages it fetched) | Changes over time; a hold must not silently grow or shrink |
| Large results | Server pages through up to 1000 hits | Client must page and send all ids | Unbounded |
| Permission | Search filter plus `ManageRetention` per document | `ManageRetention` per document | Re-evaluated continuously, hard to audit |
| Index lag | Index may lag the database by seconds (ADR 0029); the confirmation makes the records manager look at the same result | Same | Same, permanently |

Both the chosen variant and explicit ids are supported; the dynamic hold was ruled out.

Enforcement:

| Criterion | Chosen: DB trigger + lookup API + object legal hold | Application check only | Object legal hold only |
|---|---|---|---|
| Destruction path not built yet (DMS-82) | Guard holds whatever it will look like | Relies on DMS-82 remembering it | Database rows could still be set to Destroyed |
| Direct database or S3 access | Both protected | Neither | S3 only |
| Effort / coupling | Trigger on `document_version`; job and storage access in the archive module | Small | Job only |

## Consequences

### Positive

- The destruction path (DMS-82) cannot destroy a held document, regardless of how it is implemented; proposals have `LegalHoldLookup` and `dms.document_under_legal_hold`.
- Holds are self-contained proof: which matter, who set it when and why, which documents were added by whom, how and why, and the release.
- Rendition objects of held documents cannot be deleted in S3 even with governance bypass.
- Search-result holds are deliberate: the records manager confirms the hit count they reviewed.

### Negative / Cost

- Every document added means one audit entry, one insert and one job; a 1000-hit search makes 1000 of each in one transaction (acceptable for an occasional records management action).
- Hold-level operations need a tenant-wide records manager; a records manager scoped to a unit cannot set holds at all.
- The object legal hold lags the database by one job run, and failures that a retry does not fix are only logged (the database guard still protects the document). Renditions replaced while a version is open (re-rendering) keep the object legal hold after the release, because only the current rendition of a version is referenced.
- The `409 result_changed` check compares counts, not the exact set of hits.
- Releasing a hold does not remove documents from it; a document wrongly added stays listed (still releasable with the whole hold).

## Follow-ups

- Must be done (DMS-82): destruction proposals filter with `LegalHoldLookup.heldAmong` / `dms.document_under_legal_hold`; the destruction path relies on the guard and reports its error.
- Must be done (DMS-93): Object Lock retention on renditions from the retention rule; check that it composes with the object legal hold.
- Should be done: frontend: "Legal holds" section for records managers (list, create from a document or the search page with the confirmation count, release) and the legal hold badge on the document page (the API types in `frontend/src/api/schema.d.ts` are generated already).
- Later: removing a single document from a hold with reason, if the legal department needs it; step-up authentication for releasing.
- Ruled out: dynamic holds that follow a stored search.

## Reversibility

**medium**: The tables are proof and stay. The guard and the lookup are small and local; moving enforcement elsewhere means replacing one trigger. Changing the authorization (e.g. scoped holds) only changes the API layer.
