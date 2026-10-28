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

## Status-dependent required fields (DMS-33)

Each `FieldBinding` names the statuses in which the field is required (`Requiredness.from(InReview)` = from submission on). Core fields carry built-in requiredness (e.g. title always, owner and confidentiality from review, rendition checksum from approval).

- `Validation.validate(profile, values, targetStatus)` reports missing required fields (`missing_required`) and invalid values with stable field key and label, in profile order.
- `Validation.checkTransition(profile, values, from, event)` applies a lifecycle event only when the metadata is complete for the target status; a submission (`Submit`) is blocked while fields required in `InReview` are missing.
- Changing requiredness is a new class profile version (`POST /api/v1/admin/document-classes/{id}/versions`); documents keep the version they use.
- `POST /api/v1/document-classes/{id}/validate` with `{"values": {...}, "targetStatus": "InReview", "version": 1}` returns `{complete, issues:[{field,label,code,message}]}` for editors. System fields are skipped unless `includeSystemFields` is `true`. Open to every signed-in user of the tenant.

Metadata values in JSON: `{"type":"Text","value":"ACME"}`, `{"type":"Amount","value":1200.5,"currency":"EUR"}`, `{"type":"Selection","code":"3m"}`, `{"type":"Many","values":[...]}`.

## Version model (DMS-38, ADR 0021)

Business concept 5.1, 5.2. `api`, package `dms.documents` (pure):

- `Document`: stable identity (class and profile version, title, owner, author, template and template version).
- `DocumentVersion`: one complete version package: number (`VersionNo`, the number it gets on approval), working state, status (`DocumentStatus`), change kind (`ChangeKind.Major`/`Minor` with reason), validity scope, class and template version, structured content (`TemplateContent`, a copy of the template content incl. locked flags, pinning clause versions), field values (`FieldEntry`: `MetadataValue` + `ValueOrigin` `Manual`, `System(source)` or `Computed`), metadata snapshot (taken on approval), rendition (object key, object version, SHA-256), author and approver.
- `Versioning`: open statuses (Draft, InReview, InApproval), valid statuses (Published, ReviewDue), frozen statuses (all others); `next` (first version 1.0, major for content changes, minor for editorial ones, counted from the highest approved number), `nextWorkingState` (counted continuously), `label` (`1.1-draft.3` while open, `1.1` afterwards), `scopeKey` (sorted values of the `scope` field, `""` = unrestricted).

Tables (migration V9):

- `dms.document`: identity, pinned class and template version (foreign keys incl. tenant), title and owner (the only updatable columns). Insert only from a `Released` template version (trigger).
- `dms.document_version`: one row per version package (content and values as `jsonb`). Guard trigger `document_version_guard`: versions start as drafts; only drafts change content, values, working state, scope and pinned versions; working states never decrease; status changes only along `Lifecycle` (a test compares both); metadata snapshot and approver are recorded once, on InApproval → Approved; the rendition is frozen from approval on; no deletes. Check constraints: approved versions have snapshot, approver and rendition checksum; the approver is not the author; minor versions have a reason. Partial unique indexes: one open version per document (`document_version_one_open`), one valid version per document and scope (`document_version_one_valid`), a version number once per document (withdrawn drafts give it back).
- `dms.document_working_state`: one snapshot per saved working state, insert-only.
- RLS with `dms.current_tenant()` on all three; `dms_app` may select and insert, and update only the columns listed in the migration.
