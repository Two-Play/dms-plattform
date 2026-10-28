# 0009: Persistence with PostgreSQL, S3-compatible object storage with Object Lock, and OpenSearch

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

Data to be stored: metadata, versions, workflow and audit data (transactional), binary files (source files, renderings, PDF/A) with retention periods and immutability (audit-proof archiving), and a full-text and facet index with permission filtering.

## Decision

PostgreSQL is the primary store for all structured data. Binary files are stored in S3-compatible object storage; archived objects are protected with Object Lock (compliance mode, retention period). OpenSearch serves as the search index; it can be rebuilt at any time from PostgreSQL and object storage and is never the primary source.

## Alternatives considered

| Criterion | Chosen: Postgres + S3 + OpenSearch | Everything in PostgreSQL (BLOBs, `tsvector`) | Postgres + file system + Postgres full-text | Document database (e.g. MongoDB) |
|---|---|---|---|---|
| Immutability of binary data | Object Lock (WORM) | Only via permissions/triggers | Only via file system permissions | Only via permissions |
| Size of DB / backups | Small, binary data separate | Grows strongly | Small | Medium |
| Full-text search, facets, relevance | Very good | Sufficient, facets costly | Sufficient | Medium |
| OCR text, multiple languages | Analyzer per language | Limited | Limited | Limited |
| Transactions across business data | Yes (Postgres) | Yes | Yes | Limited |
| Number of components | 3 | 1 | 1 + storage | 1–2 |
| Cloud/on-prem portability | High (S3 API) | High | Medium | Medium |

## Consequences

### Positive

- Audit-proof storage enforced technically, not just organizationally.
- The database stays small; backups and migrations are fast.
- Powerful search with facets and language analysis.
- The search index can be discarded and rebuilt without loss.

### Negative / Cost

- Three stateful components to operate, back up and monitor.
- Consistency between DB and object storage is not transactional: write the object first, then the reference in the DB; clean up orphaned objects.
- Search results are eventually consistent; permissions must be carried in the index and updated when they change.
- Object Lock in compliance mode is irreversible; wrong retention periods cannot be corrected.

## Follow-ups

- Local S3 replacement: MinIO Community Edition has not been maintained as ready-made binaries/images since 2025. Choosing an alternative (e.g. Garage or SeaweedFS) with Object Lock support is a follow-up task.
- Indexing via outbox and workers (0010); reindex job for a full rebuild.
- Define retention periods per document type in business terms before Object Lock is activated in production.
- Job to clean up orphaned objects (only without lock).
- Ruled out: binary data in PostgreSQL; the search index as a data source for business decisions.

## Reversibility

**medium**: PostgreSQL is deeply embedded (high). Object storage is replaceable via the S3 API (low), but locked objects must remain in the old storage until their retention period ends. OpenSearch, as a derived index, can be replaced at any time (low).
