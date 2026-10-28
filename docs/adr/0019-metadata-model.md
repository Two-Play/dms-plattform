# 0019: Metadata as JSONB values validated against versioned profiles

| Field | Value |
|---|---|
| Status | Accepted (2026-10-28, implemented in DMS-32, DMS-33) |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

The business concept (3.1, 3.2) requires hierarchical document classes that inherit metadata profiles, central and typed field definitions (text, number, amount with currency, date, selection, person, document reference) that classes reference instead of copying, status-dependent requiredness, versioned selection lists whose removed values stay readable, and profile versions: a schema change must never silently change existing documents. Tenant administrators define classes and fields at runtime, without a deployment. Fields must be filterable later (search via OpenSearch, ADR 0009) and tenants are isolated with row-level security (ADR 0014).

## Decision

Field definitions and class profiles are immutable, versioned rows (`dms.field_definition`, `dms.document_class`, `dms.document_class_version`, migration V7). The type and rules of a field and the field bindings of a class version (field version + statuses in which the field is required) are stored as `jsonb`. A class version pins its parent's version; the effective profile is computed by a pure function (`Profiles.resolve`) from the chain. Document metadata values will be stored as one `jsonb` object per document version keyed by stable field keys, together with the profile version they were validated against (`Validation.validate`, pure). Core and system metadata are a built-in root profile in code, identical for all tenants.

## Alternatives considered

| Criterion | Chosen: JSONB values + versioned profiles | EAV tables (one row per value) | Typed columns per class (DDL at runtime) |
|---|---|---|---|
| New field without deployment | Yes | Yes | Only with runtime DDL (`alter table`) per tenant/class |
| Validation | In the application against the pinned profile version; DB only checks JSON shape | In the application; value columns per type or text casting | Partly by the database (types, `not null`), but status-dependent requiredness still in the application |
| Profile versions / old documents unchanged | Natural: values carry their profile version; no schema change | Possible, but definitions and values need version joins | Hard: a dropped/changed column affects all rows; old versions need extra columns |
| Read a document's metadata | One row, one column | N rows, pivot | One row, but table per class (or sparse wide table) |
| Query / filter | GIN/expression indexes; complex filters go to OpenSearch | Joins per filter criterion; slow for multi-field filters | Best for SQL filters |
| RLS and grants (0014) | One table, one policy | One table, one policy | Policies and grants per generated table |
| Integrity in the database | Weak (no FK from value to option) | Medium (FK to definitions possible) | Strong per column |
| Complexity | Low | Medium (pivoting, typed value columns) | High (migrations at runtime, tenant-specific schema) |

Storage of the definitions themselves (rules as `jsonb` vs. a relational table per rule kind) follows the same reasoning: the rule set per type differs and is read as a whole.

## Consequences

### Positive

- Tenant administrators change profiles at runtime; every change is a new version and fully traceable (rows are append-only, changes are audited).
- Documents keep the profile and field versions they were created with; removed options remain in the definition as inactive and stay readable.
- Validation and inheritance are pure functions in `documents-api`, exhaustively unit-tested and usable by every module and by the frontend via the validation endpoint.
- One table per concept keeps RLS and grants simple.

### Negative / Cost

- The database does not enforce value types; correctness depends on writing values only through the validating service.
- SQL filtering on metadata needs JSON operators and expression indexes; larger filter scenarios depend on the search index.
- Profile resolution reads the class chain and the referenced field versions per request (cacheable, since versions are immutable).

## Follow-ups

- Must be done: store document metadata values as `jsonb` with `(class_id, profile_version)` when documents are persisted; validate on every write and on lifecycle transitions (`Validation.checkTransition`).
- Must be done: logged migration run that moves documents to a newer profile version (business concept 3.2).
- Must be done: index the values in OpenSearch with the field type from the profile.
- Ruled out: runtime DDL per class.

## Reversibility

**medium**: Definitions and profiles are small and could be migrated to another layout with a one-off script. Once many documents store their values as `jsonb`, moving to EAV or typed columns requires a data migration of all document versions, but the pure validation and profile model in `documents-api` stay unchanged.
