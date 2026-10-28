# Module `documents`

Documents, versions, metadata, relations and lifecycle (business concept ch. 3 and 5).

Layout: `api` holds public types and interfaces, `core` the implementation. Other modules may only depend on `api`.

## Document classes and metadata (ADR 0019)

`api`, package `dms.documents.metadata` (pure, no effects):

- `FieldDefinition`: central, typed, versioned field (`FieldType`: Text, Number, Amount, Date, Selection, YesNo, PersonRef, DocumentRef with rules such as min/max, pattern, options, currencies). `Profiles.nextVersion` builds the next version; selection options removed in it remain as inactive options.
- `CoreFields`: the built-in root profile with the core and system metadata of business concept 3.2 (document id, title, class, version, status, owner, organizational unit, scope, validity, next review, confidentiality, language, retention rule, personal data, tags, template and version, rendition checksum). Not stored per tenant; tenants cannot reuse these keys.
- `DocumentClassVersion`: one immutable profile version of a class with its own `FieldBinding`s (field key, field version, statuses in which the field is required) and the pinned parent version.
- `Profiles.resolve`: computes the effective `MetadataProfile` from the class chain. Children inherit all fields and rules, may add fields and may make inherited fields required in more statuses, but cannot drop inherited requiredness or re-bind an inherited field to another version.
- `Validation.checkValues`: checks `MetadataValue`s against a profile (type, range, pattern, unknown or retired option, currency, unknown field) and reports `FieldIssue`s with stable field keys and labels.

`core`, package `dms.documents.core`:

- `DocumentClassService`: list/get/create/new version for field definitions and classes, effective profile per class version. Must run inside `Db.transact(tenant)`; each change is recorded in the audit trail in the same transaction.
- Tables (migration V7): `dms.field_definition` (PK tenant, key, version), `dms.document_class` (identity, key, fixed parent), `dms.document_class_version` (PK class, version; bindings as `jsonb`). Append-only (trigger + insert-only grants), RLS with `dms.current_tenant()`.

HTTP (module `app`, `DocumentClassApi`), all requiring `Configure` on `DocumentClasses` (tenant administrator):

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/admin/field-definitions` | Latest version of every field, incl. core fields |
| GET | `/api/v1/admin/field-definitions/{key}` | All versions of a field |
| POST | `/api/v1/admin/field-definitions` | Create version 1 |
| POST | `/api/v1/admin/field-definitions/{key}/versions` | Create the next version |
| GET | `/api/v1/admin/document-classes` | Latest version of every class |
| GET | `/api/v1/admin/document-classes/{id}` | All profile versions of a class |
| POST | `/api/v1/admin/document-classes` | Create a class (optionally below a parent) |
| POST | `/api/v1/admin/document-classes/{id}/versions` | Create the next profile version |
| GET | `/api/v1/admin/document-classes/{id}/profile?version=` | Effective profile |

JSON sum types carry a `type` discriminator, e.g. `{"type":"Selection","options":[{"code":"3m","label":"Three months","active":true}]}`.
