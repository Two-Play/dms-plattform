# 0040: Number ranges with a locked counter row in the creating transaction; the document number as frozen core field

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-10-29 |
| Decided by | Philippe |

## Context

DMS-113: business administrators define number ranges per document class, so that documents get a unique, readable number automatically (e.g. `POL-2026-0042`); in justified cases a number is assigned manually.

Decisive requirements and constraints:

- Patterns with prefix, year, organizational unit and a counter with a minimum number of digits; a start value; an optional yearly reset. Classes inherit the range of their parent unless they override it (class hierarchy, ADR 0019).
- Numbers are unique per tenant and **gap-free and collision-free also under concurrent creation**. Numbering is an audit topic: a gap must be explainable.
- The number is metadata (ADR 0019): it belongs to the core profile, is frozen with the metadata snapshot of an approved version (ADR 0021) and is the same in every version of a document. It is searchable (ADR 0029), printable in templates and the rendition (ADR 0008, 0025) and usable as the key of a stable address (ADR 0035).
- Manual assignment needs a permission, a reason and an audit entry (ADR 0017, 0018).
- All data access runs in `Db.transact(tenant)` with row-level security (ADR 0012, 0014); no forked fibers inside a transaction.
- Changes of a range must not change numbers already assigned.

## Decision

**Model.** `documents-api` holds the pure model (`Numbering.scala`): `NumberPattern` (parser and formatter of `{YYYY}`, `{YY}`, `{OU}` and exactly one counter `{N}`…`{NNNNNNNNNN}` between literal text of letters, digits, `.`, `_`, `-`), `DocumentNumber` (at most 64 such characters, starting with a letter or digit, so it fits a URL path) and `NumberRange` (key, name, pattern, start value, yearly reset; counter period = year or 0; next counter = max(stored, start value)). Migration V25: `dms.number_range`, `dms.number_range_counter` (next value per range and period), `dms.document_class_number_range` (own assignment per class; `range_id` null = explicitly none) and `dms.document.document_number` with a partial unique index per tenant; all with RLS and explicit column grants. Inheritance is resolved at creation along the class tree (nearest class with an own assignment).

**Automatic assignment.** `DocumentService.create` takes the number inside the creating transaction: `insert … on conflict do nothing` creates the counter row of the period, `select … for update` locks it until the transaction ends, numbers already taken (by manual assignment) are skipped, and the next value is written back. Concurrent creations of the same range wait on the row lock and get distinct consecutive numbers; a creation that rolls back rolls back its counter increment as well, so **no gap arises from failed creations**. The year (and the yearly reset) follows the tenant's time zone (ADR 0026). `{OU}` takes the entered organizational unit (runs of other characters become `-`); a pattern with `{OU}` makes the unit required on creation.

**Core field.** `documentNumber` is a system field of the core profile (`CoreFields`), set by the platform (`System("dms")`, not enterable) into every version's values and therefore into the metadata snapshot on approval; `newVersion` and draft changes take it from `dms.document`. Templates may print `{documentNumber}`; previews show the system field hint until a document has one. Search finds a number exactly through a `term` clause on `metadata.keyword.documentNumber`, which the existing dynamic template maps as keyword: **no mapping change and no reindex are needed for new documents**; documents numbered later are reindexed by the regular sync (their row's `updated_at` changes).

**Manual assignment.** `POST /api/v1/documents/{id}/number` with number and reason; new action `AssignDocumentNumber` for records managers (in scope) and the document's owner who holds the owner role, not for tenant administrators (they have no access to document content). A document without number gets one at any time except while a version is in review or approval (its values are frozen); an existing number can only be replaced while every version is a draft. A trigger (`dms.document_number_guard`) enforces the second rule in the database. The open draft gets the number as a new working state; the audit entry `document.number-assigned` records previous and new number with the reason (also in the audit `reason` column).

**Stable key.** `GET /api/v1/documents/by-number/{number}/current` answers like `/documents/{id}/current` (same authorization, ETag, 404 without revealing existence); the current-version view carries `documentNumber`.

**Administration.** Tenant administrators (`Configure` on the new area `NumberRanges`) manage ranges and assignments at `/api/v1/admin/number-ranges` and `/api/v1/admin/number-range-assignments`; changes are audited (`number-range.created|changed|assigned|unassigned`) and only affect numbers assigned afterwards. A higher start value raises the next number of the current period; a lower one never lowers it.

## Alternatives considered

| Criterion | Chosen: counter row locked in the creating transaction | PostgreSQL sequence per range and period | Number assigned asynchronously after commit (job) | Number on approval instead of creation |
|---|---|---|---|---|
| Gap-free | Yes for failed creations (rollback restores the counter) | No: `nextval` is never rolled back, every failed insert leaves a gap | Yes, if the job is the only writer | Yes for creations; drafts have no number |
| Collision-free under concurrency | Yes: row lock serializes, unique index as last line | Yes | Yes (single writer) | Yes, same mechanism needed |
| Throughput | Creations of the same range serialize until commit (short transactions; ranges per class) | No contention | No contention on create | Contention moves to approval |
| Number available at creation (drafts, PDF draft, references in e-mails) | Yes | Yes | No, only shortly after | No |
| Yearly reset, start value change | Rows per period; start value applied as minimum | New sequence per year, DDL at runtime, `setval` | As chosen | As chosen |
| RLS / tenant isolation | Ordinary tenant rows | Sequences are not row-level secured; names per tenant needed | Ordinary rows | Ordinary rows |
| Complexity | Low | Medium (runtime DDL, grants) | Medium (job, retries, UI without number) | Low, but conflicts with the requirement "on creation" |

Authorization of manual assignment: a new action for records managers and owners was chosen over "tenant administrator only" (administrators deliberately do not read document content, ADR 0018) and over reusing `EditDraft` (would let every author renumber).

## Consequences

### Positive

- Numbers are unique per tenant (index), consecutive and gap-free with respect to failed creations, also under parallel load (tested with 12 parallel creations and a rolled-back creation).
- The number is ordinary, frozen metadata: in the snapshot, in every version, in search, in templates and renditions, and a second stable key for integrations.
- Ranges and assignments change at runtime without deployment; every change and every manual number is traceable in the audit trail.

### Negative / Cost

- Creations of documents of the same range are serialized from taking the number until commit. Creation transactions are short; if a range ever becomes a bottleneck, it can be split per class.
- Gaps remain possible by design in two cases, both visible in the audit trail: a manual number replaces an automatic one (the automatic number is not reused), and numbers taken manually are skipped by the counter.
- A concurrent manual assignment of exactly the number the counter is about to hand out makes one of the two transactions fail with a conflict (unique index); it can be retried.
- Documents created before their class had a range keep no number until one is assigned manually (no automatic backfill yet).
- A tenant field definition with the key `documentNumber` created before this change would clash with the new core field (tenants could not create core keys afterwards); none exist in the known installations.

## Follow-ups

- Should be done: backfill command that numbers existing documents of a class in creation order (in one transaction per document, through `DocumentNumbering`), followed by a search sync.
- Should be done: show the document number in search hits and the document list (`DocumentSummaryView.documentNumber` exists already).
- Optional: `/by-number/{number}/current.pdf` and `.html` like the id-based addresses.
- Ruled out: PostgreSQL sequences (not gap-free, not tenant-isolated).

## Reversibility

**medium**: Ranges, counters and assignments are three small tables; the counter mechanism is local to `DocumentNumbering` and could be replaced (e.g. by sequences) without touching documents. Numbers already assigned are frozen in approved versions and must stay valid: removing the feature would keep the column and the core field read-only.
