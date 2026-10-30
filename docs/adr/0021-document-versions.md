# 0021: Document versions as full version packages, frozen by database guards

| Field | Value |
|---|---|
| Status | Accepted (2026-10-28, implemented in DMS-38, DMS-37) |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

The business concept (5.1, 5.2) requires that every approved version is frozen: content, field values, metadata snapshot, template version, clause versions, rendition and checksum. A document has at most one open draft and at most one valid version per validity scope; working states of a draft (`1.1-draft.3`) are counted continuously and may be compacted after approval, while approved versions are always kept. Versions must be readable and renderable for decades (retention, audits), independent of later changes to templates, clauses or class profiles. Content is the structured template format (ADR 0007), metadata values are JSONB validated against versioned profiles (ADR 0019), and tenants are isolated with RLS (ADR 0014). The application is the only regular writer, but the guarantees must also hold against bugs and direct SQL by the application role.

## Decision

Each document version is one row in `dms.document_version` holding the complete version package: structured content (a copy of the template content as edited, in the template content format), field values with their origin, the profile and template version, the metadata snapshot taken on approval, rendition reference and SHA-256. Working states are full snapshots in an insert-only side table. Immutability is enforced twice: by `DocumentService` (only drafts change, transitions via the pure `Lifecycle`) and by the database: a `before insert/update/delete` guard trigger (only drafts change content, status changes only along the lifecycle, snapshot and approval recorded once on approval, rendition frozen from approval on, no deletes), column-level update grants, check constraints (approval needs snapshot, rendition and a person other than the author) and partial unique indexes (one open version per document, one valid version per document and scope).

## Alternatives considered

| Criterion | Chosen: full snapshot per version | Delta per version (diff to the previous version) | Event sourcing (versions derived from change events) |
|---|---|---|---|
| Reading one version | One row, no reconstruction | Replay of all deltas from the first version (or periodic full snapshots) | Replay of the event stream up to the version |
| Independence of a version (retention, legal hold, destruction of single versions) | Each version stands alone; destroying one does not affect others | Versions depend on their predecessors; destroying an old version breaks the chain | Events of several versions are interleaved; destruction needs crypto-shredding or rewriting |
| Storage | Content and values repeated per version (structured JSON, typically kilobytes; renditions live in object storage) | Smallest | Small events, but many of them |
| Integrity proof | Checksum per version over the rendition; row frozen by guards | A corrupted delta corrupts all later versions | Hash chain over events possible, but reconstruction is part of the trust base |
| Diffs between versions (deviation report, review) | Computed on demand from two full versions (pure functions in `templates-api`) | Stored, but against the predecessor only | Derived from events |
| Complexity | Low | Medium (diff/patch format for nested content, compaction) | High (projections, schema evolution of events) |

| Criterion (immutability enforcement) | Chosen: application + guard trigger + grants | Application only | Separate append-only table per status (copy on approval) |
|---|---|---|---|
| Protection against bugs and direct SQL of the application role | Yes | No | Yes, but the copy step itself can be wrong |
| Status transitions consistent with `Lifecycle` | Checked in both places; a test compares the trigger's list with `Lifecycle` | Only in code | Only in code |
| Complexity | Medium (trigger kept in sync with the lifecycle) | Low | Medium (two tables, moving rows) |

## Consequences

### Positive

- A version package can be read, rendered, exported or destroyed on its own; no reconstruction logic sits between storage and an auditor.
- The database rejects changes to frozen versions, competing drafts and two valid versions in one scope for every writer, including the application role (tested with plain SQL).
- Documents are pinned to template, class profile and clause versions; later releases of templates or profiles do not change existing documents.
- Working states are kept as snapshots, so a draft's history can be shown and compacted later without touching approved versions.

### Negative / Cost

- Content and values are stored again for every version and every working state; for large documents with many saves this grows quickly until working states are compacted.
- The lifecycle exists twice (Scala and the trigger); a lifecycle change needs a migration. A test fails when the two differ.
- The validity scope is a derived key (sorted values of the `scope` field); overlapping scopes (`DE` and `AT|DE`) are not detected as conflicts.
- Destruction (business concept 7.4) must remove content from frozen rows, which the guard forbids; it needs a dedicated, privileged path.

## Follow-ups

- Done (DMS-82, ADR 0054): compaction of working states (periodic job; the guard of V35 keeps the current, reviewed and held states).
- Must be done: rendering fills rendition key, object version and SHA-256 before approval (sprint 7). Done (DMS-82, ADR 0054): destruction path that clears content but keeps the deletion record.
- Done (DMS-46, ADR 0026): owner confirmation of minor (editorial) versions in the workflow. Must be done: language variants as a second validity dimension.
- Ruled out: storing versions as deltas.

## Reversibility

**medium**: The version package is a self-contained row, so moving to another storage layout (e.g. content in object storage with a reference) is a data migration per version without reconstruction. Relaxing the guards is a migration; tightening them later would require checking existing rows.
