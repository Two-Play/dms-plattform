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

## Creating documents from templates (DMS-37)

Business concept 4.3 (manual and hybrid creation), US-02.1. `core`, package `dms.documents.core`:

- `DocumentService` (inside `Db.transact(tenant)`):
  - `create(NewDocument(templateId, templateVersion, title, values), actor)`: only from a `Released` template version (409 otherwise; the database checks it again). Creates the document and version 1.0 as a draft (`1.0-draft.1`) with the template content (sections incl. locked flags, clause references) and binds it to the template version and the class profile version of the template.
  - Field values: entered values (`ValueOrigin.Manual`, or `System(source)` from a business system) are validated against the class profile for status Draft (`Validation.validate`); the platform adds `documentId`, `documentClass`, `version`, `template`, `templateVersion` (`System("dms")`); computed fields of the template are evaluated (`Resolver.computedValues`) and stored as `Computed`. Platform fields, status, checksum and computed fields cannot be entered (`not_editable`). The retention rule can be entered until it is derived from the class.
  - `updateDraft(id, DraftChange(title, values, content), actor)`: only drafts; entered values are replaced, computed values recomputed, the working state increases and is stored in `dms.document_working_state`. A changed content must keep locked sections and their order and title (`EditRules` from DMS-36 with the active deviation requests of the version: locked text only changes in sections with a request, see DMS-79 below) and the template's computed fields, and must fit the class profile (template check). The deviation report is recomputed and stored.
  - `submit(id, actor)`: `Validation.checkTransition(profile, values, Draft, Submit)`; blocked with `Incomplete(InReview, issues)` while fields required in review are missing, and with `Invalid` while the content has unauthorized deviations (`EditRules` violations).
  - `changeStatus(versionId, event, actor)`: further lifecycle events for the workflow: approval by a person other than the author (`Lifecycle.approve`) takes the metadata snapshot and needs the rendition checksum; it is also blocked (`Incomplete(Approved, MissingRequired)`) for every field without value that a computed field of the version reads, because such a computed field has no value (`TemplateSource.missingComputed`, DMS-83); publication supersedes the valid version of the same scope. `attachRendition` records the rendition of an open version. `newVersion(id, Major | Minor, reason, actor)` starts the next version from the valid one (one open version at a time; a minor version needs a reason).
  - `templateUsage(templateId, version)` (DMS-50, US-01.2): how documents use the versions of a template before `version`: distinct documents, document versions and documents per status (in total and per template version, grouping sets over `dms.document_version`), and the open versions with document title, author, label, status, template version and last change, never content. Read-only; the caller authorizes (the template impact analysis in `app`, see modules/templates/README.md).
  - Every change is recorded in the audit trail (`document.created`, `document.draft-changed`, `document.submitted`, `document.approved`, `document.published`, `document.status-changed`, `document.version-created`, `document.rendition-attached`, `document.deviation-requested`, `document.deviation-withdrawn`) and creation, status changes and new versions are published to the outbox (`document.created`, `document.submitted`, `document.status-changed`, `document.version-created`; the payload includes `requiresExtraReview`), in the same transaction.
- `TemplateSource`: the documents module's view of templates (versions, content check, computed fields and the computed fields without value, content format), implemented in `app` (`DocumentTemplates`) with `TemplateService`, `TemplateCompiler`, `Resolver` and `ContentJson`.

HTTP (module `app`, `DocumentApi`):

| Method | Path | Purpose | Authorization |
|---|---|---|---|
| POST | `/api/v1/documents` | Create from a released template version (`{"templateId","templateVersion","title","values":{...}}`) | `CreateFromTemplate` in the class scope |
| GET | `/api/v1/documents?limit=` | Most recently changed documents (default 50, at most 200 looked at) with the version the caller sees (open if readable, else latest readable), `mine` for the caller's own; documents without a readable version are left out | `ReadDocument` per version |
| GET | `/api/v1/documents/{id}` | Document with its open (or latest readable) version | `ReadDocument` on a version |
| GET | `/api/v1/documents/{id}/versions` | Versions the caller may read | `ReadDocument` per version |
| PUT | `/api/v1/documents/{id}/draft` | Change values, title and content of the draft | `EditDraft` |
| POST | `/api/v1/documents/{id}/submit` | Submit for review | `EditDraft` |
| GET | `/api/v1/documents/{id}/versions/{versionId}/rendition` | Archive rendition (PDF/A-2b) of an approved version with `ETag`/`Repr-Digest` = SHA-256; 404 without rendition (DMS-44, module `rendering`) | `ReadDocument` on that version |

Validation errors return 422 with `issues` (`[{field,label,code,message}]`): `invalid_values` for entered values, `incomplete` for a blocked submission. Values in responses carry their origin: `{"value":{...},"origin":"System","source":"dms"}`.

## Deviation requests and deviation report (DMS-79, ADR 0022)

Business concept 4.1, US-01.1. The standard text of a locked template section may only change in a document if a deviation request (section, reason, requester, time) is recorded for that section on the draft. A request permits the change; the additional review step of the document class approves it.

**For the workflow (DMS-40)**: read `DocumentVersion.requiresExtraReview` (`documents-api`), which is `DeviationReport.requiresExtraReview(version.deviations)`: true if the submitted content has at least one deviation from a locked section with a request. A version in review never has unauthorized deviations (submission rejects them). The flag is also in the payload of every document outbox event (`document.submitted`, `document.status-changed`, ...). The full report is `DocumentVersion.deviations` (`dms.templates.DeviationReport`: location, change and the requests of the section per deviation); it is frozen with the version once it leaves Draft.

`api`, package `dms.documents`:

- `RecordedDeviationRequest`: a stored request (id, version, section, reason, requester, time, `basedOn` for carried-over requests, withdrawal). `request` / `RecordedDeviationRequest.active(list)` give the `DeviationRequest`s that `EditRules` and `DeviationReport` take.
- `DocumentVersion.deviations: DeviationReport` (default empty) and `DocumentVersion.requiresExtraReview`.

`core`, `DocumentService` (inside `Db.transact(tenant)`):

- `requestDeviation(documentId, sectionId, reason, actor)`: only on a draft (409); the section must be a locked section of the template and the reason non-blank, at most 4000 characters (422); one active request per section (409). Recomputes and stores the report; audit `document.deviation-requested`.
- `withdrawDeviation(documentId, requestId, actor)`: only on a draft; the row is kept with who and when. A section that still deviates becomes an unauthorized deviation: content changes must restore its text and submission is blocked until then or until it is requested again. Audit `document.deviation-withdrawn`.
- `deviationRequests(versionId)`: all requests of a version, active and withdrawn.
- `updateDraft` passes the active requests to `EditRules` and stores the recomputed report; `submit` (and `changeStatus` with `Submit`) checks `EditRules` again and stores the final report.
- `newVersion` carries the active requests of the valid version over to the new draft (`basedOn` = original request), so its deviations stay permitted and need the extra review again.
- No outbox event per request: like draft changes, requests are internal to the draft; the workflow learns the outcome from `requiresExtraReview` on submission.

Tables (migration V10):

- `dms.document_deviation_request`: one row per request (`section_id`, `reason`, `requested_by`, `requested_at`, `based_on`, `withdrawn_by`, `withdrawn_at`). Partial unique index `document_deviation_request_active` (one active request per version and section). Guard trigger: inserts and withdrawals only while the version is `Draft`; only the withdrawal columns change, once; no deletes. RLS with `dms.current_tenant()`; `dms_app` may select and insert, and update only `withdrawn_by`, `withdrawn_at`.
- `dms.document_version.deviation_report` (`jsonb`, stored format of `templates-core` `DeviationJson`, `{"format":1,"deviations":[...]}`): changes only while the version is a draft (V10 extends the V9 guard).

HTTP (module `app`, `DocumentApi`):

| Method | Path | Purpose | Authorization |
|---|---|---|---|
| POST | `/api/v1/documents/{id}/deviation-requests` | Request a deviation (`{"sectionId","reason"}`); 201 with the draft's report | `EditDraft` |
| POST | `/api/v1/documents/{id}/deviation-requests/{requestId}/withdraw` | Withdraw a request of the draft; the draft's report | `EditDraft` |
| GET | `/api/v1/documents/{id}/versions/{versionId}/deviations` | Report and requests of a version, e.g. for reviewers | `ReadDocument` on that version |

The report view: `{versionId, version, status, requiresExtraReview, requests:[{id,sectionId,reason,requestedBy,requestedAt,basedOn,active,withdrawnBy,withdrawnAt}], deviations:[{section,path,token,location,change,authorized,reasons}]}`; `change` carries a `type` discriminator, e.g. `{"type":"TextReplaced","from":"30 days","to":"60 days"}`. Version views carry `requiresExtraReview` too.

## Values from business systems (DMS-43, ADR 0023)

US-03.1. Values a business system sets carry `ValueOrigin.System(source)` (e.g. `partner-master`); the platform's own values are `System("dms")`.

- `DocumentService.updateDraft` keeps them: a manual value for such a field is rejected as `not_editable` unless it equals the system value (then the system value and its origin are kept). Removing or replacing them is the job of the integration (`PartnerApi` passes the new values with their system origin).
- Fields filled by a reference field of the template (`TemplateContent.references`) only accept values with the reference's source, on create and on change; the references of a document's content cannot be changed (like computed fields).
- Taken-over values are a snapshot: they stay as picked, also when the partner master changes, and are frozen with the version on approval. The identifier is stored in a field as source reference.

## Scheduled publication (DMS-45, ADR 0026)

US-06.3. An approved version becomes valid at 00:00 of its valid-from date (core field `validFrom`) in the tenant's time zone; the valid version of the same scope ends at the same moment.

- `api`, `Publication` (pure): `validFrom(values)`, `dueAt(date, zone)` (first moment of the date in the zone), `isDue`, `successor(version, versions)` (the version a superseded version was replaced by, or the approved version scheduled to replace a valid one). `DocumentVersion` gains `validFrom` (frozen on approval), `supersededBy` and `requiresAcknowledgement`.
- `core`, `DocumentService`: approval records the valid-from date; `Publish` is rejected (409) before the due moment; publication supersedes the valid version of the same scope and names it as successor, in the same transaction. `scheduledPublications` lists approved versions with their due moment; `publishScheduled(versionId)` publishes a due approved version and does nothing otherwise (idempotent); actor `system:publication`, recorded as technical actor on channel automation. `newVersion` is rejected (409) while an approved version waits for its date. `DocumentDetails` carries the tenant's `zone`, `successor(v)` and `publicationDue(v)`.
- `core`, `PublicationJobs`: `tick` enqueues per approved version a job `document.publish` with `run_at` = due moment and idempotency key `document.publish:<version>:<epoch second>`; `handlers` publishes. `app` (`Background`) runs the tick every `dms.documents.publicationCheckSeconds` and registers the handler with the job runner.
- Migration V12: `dms.tenant.time_zone` (default `Europe/Berlin`, validated against `pg_timezone_names`); `dms.document_version.valid_from`, `superseded_by`, `requires_acknowledgement`; guard `document_version_publication_guard` (valid-from only on approval, successor only when superseding a valid version and required then, acknowledgement flag only on creation); deferred constraint trigger `document_version_successor_valid` (on commit the successor is valid in the same document and scope). With the unique index of V9 there is never a moment with two or with no valid version. `dms_app` may update `valid_from` and `superseded_by`.

## Editorial (minor) versions (DMS-46, ADR 0026)

US-05.2. `newVersion(id, Minor, reason, actor, requiresAcknowledgement)`: the reason is required (422). On submission the workflow takes the shortened editorial path (module `workflow`): the owner confirms the editorial classification in one approval step.

- `api`, `EditorialCheck`: compares a minor version with the latest released version below its number using `dms.templates.TextChange` (word LCS per section; share = `1 - kept / max(words)`) and warns when the share exceeds the threshold. It does not block. On submission it is recorded in the audit trail (`document.editorial-check`).
- `DocumentSettings(editorialChangeThreshold, minorVersionsRequireAcknowledgement)`, from `dms.documents.editorialChangeThresholdPercent` (10) and `dms.documents.minorVersionsRequireAcknowledgement` (false); `DocumentService.configured(settings)`.
- `requiresAcknowledgement` per version: true for major versions, the setting for minor ones, overridable on creation; frozen afterwards. Document outbox events carry `changeKind`, `requiresAcknowledgement` and, once approved, `validFrom`.

HTTP (module `app`, `DocumentApi`):

| Method | Path | Purpose | Authorization |
|---|---|---|---|
| POST | `/api/v1/documents/{id}/versions` | Start the next version `{"changeKind":"Major"\|"Minor","reason","requiresAcknowledgement"}`; 201 | `EditDraft` on a draft of the caller |

Version views additionally carry `validFrom`, `publicationDue` (approved versions), `successor` (`{versionId, version, status, validFrom, publicationDue}`), `requiresAcknowledgement` and `editorialCheck` (`{basedOn, changedRatio, threshold, exceedsThreshold}`, minor versions).

## Acknowledgements (DMS-47, ADR 0027)

US-06.2. Readers of a valid version that requires acknowledgement (`requiresAcknowledgement`, see DMS-46) confirm that they read it, after opening it.

- `api`: `Acknowledgement` (id, document, version id and label, reader, first opening, acknowledgement time); `Acknowledgements.requested(version)`: valid (Published, ReviewDue) and requiring acknowledgement. `DocumentVersion.publishedAt`: when the version became valid.
- `core`, `AcknowledgementService` (inside `Db.transact(tenant)`): `markOpened(version, reader)` records the first opening of a version that asks for acknowledgement (idempotent; audit `document.opened` once; nothing for other versions); `acknowledge(version, reader)` under a share lock on the version: `NotRequested` (not valid or not required), `AlreadyAcknowledged`, `NotOpened`; audit and outbox `document.acknowledged`. `pending(reader)`: valid versions asking for acknowledgement without an acknowledgement of the reader (the caller filters by `ReadPublished`); `openedValid`, `status`, `acknowledgementsOf`.
- Migration V15: `dms.document_version_opened` (PK version, user) and `dms.document_acknowledgement` (unique version, user); both insert-only (triggers, insert-only grants), RLS with `dms.current_tenant()`. Insert guards: the version is valid and requires acknowledgement, the label is its number, the acknowledgement copies the reader's recorded opening. `dms.document_version.published_at`: set by trigger on Approved → Published, never otherwise; backfilled from the audit trail.
- Superseded versions drop out of the open list; their successor asks on its own if it requires acknowledgement.

HTTP (module `app`, `AcknowledgementApi`):

| Method | Path | Purpose | Authorization |
|---|---|---|---|
| GET | `/api/v1/acknowledgements?status=open\|done` | The caller's open (readable valid versions not acknowledged) or done acknowledgements: `[{documentId, versionId, title, versionLabel, classPath, validFrom, publishedAt, status, opened, openedAt, acknowledgedAt}]`; 400 for another status | open: `ReadPublished` per version |
| POST | `/api/v1/documents/{id}/versions/{versionId}/opened` | Record that the caller opened the version; 204, idempotent | `ReadDocument` on that version |
| POST | `/api/v1/documents/{id}/versions/{versionId}/acknowledgement` | Acknowledge; 201 `{documentId, versionId, versionLabel, openedAt, acknowledgedAt}`; 409 `not_opened`, 409 `already_acknowledged`, 422 `acknowledgement_not_requested` | `ReadDocument` on that version |
| GET | `/api/v1/documents/{id}/versions/{versionId}/acknowledgement` | `{versionId, versionLabel, requested, opened, openedAt, acknowledgedAt}` for the caller | `ReadDocument` on that version |

Version views additionally carry `publishedAt`.

## Validity history: "valid on" (DMS-53, ADR 0026 addendum)

US-09.1. Auditors ask which version of each document was in force on a date. The history is read from PostgreSQL, the authoritative store of validity; the search index is not involved.

- `api`: `Validity` and `ValidityPeriod(from, until)`. A version is in force from `Validity.effectiveStart`: 00:00 of its valid-from date in the tenant's time zone, but never before its publication (`published_at`; not retroactive), until the effective start of the version in `superseded_by` (open while it is valid). A date means its last moment in the tenant's time zone (`Validity.endOfDay`), so a switch during the day shows the version that applied for the rest of the day. Statuses that count: Published, ReviewDue, Superseded, Withdrawn (a valid version withdrawn in the periodic review counts until `withdrawn_at`, DMS-55), Archived (`Validity.EverValid`); destroyed versions and versions never published are left out.
- `core`: `DocumentService.validAt(at, classKey)` returns per document and validity scope the version in force at `at` (`ValidVersion`: document, class path, number, current status, scope, field values, author, approver, valid-from date, period), by title; `classKey` keeps a class and its subclasses. SQL selects candidates with necessary conditions only (`DocumentStore.validityCandidates`); the exact period is decided with `Validity`, so the rule exists once. `tenantZone` gives the tenant's time zone. No migration.
- The caller authorizes: `ReadDocument` per version in its current status, without logging denials. Readers of the class see valid and superseded versions (as in the version history of a document), archived versions only owners, records managers and auditors; confidentiality applies.
- The approver comes from the workflow's approval record (`ApprovalWorkflow.approvals(versionIds)`: approver, the principal a deputy acted for, time), falling back to `released_by`/`released_at` for versions approved without a case.

HTTP (module `app`, `ValidAtApi`, registered before `/documents/{id}`):

| Method | Path | Purpose | Authorization |
|---|---|---|---|
| GET | `/api/v1/documents/valid-at?date=YYYY-MM-DD\|at=<instant>&class=<key>` | `{at, date, timeZone, documentClass, items:[{documentId, versionId, title, classPath, versionLabel, status, scope, validFrom, validSince, validUntil, approvedBy, actingFor, approvedAt}]}`; 422 unless exactly one of `date` and `at` | `ReadDocument` per version (filter) |
| GET | `/api/v1/documents/valid-at/export.csv?…` | The same list as CSV: UTF-8 with BOM, `;`-separated, CRLF, RFC 4180 quoting, times `yyyy-MM-dd HH:mm:ss` in the tenant's time zone; columns document id, title, class, scope, version, status, valid from, valid until, approver, on behalf of, approved at; text cells starting with `= + - @` get a leading `'` (CSV injection) | as above |

## Periodic review and recertification (DMS-55, ADR 0030)

Business concept 6.2, US-07.1. The document owner gets a review task at the class's lead time before the review date of a valid version and decides once: recertify, revise or withdraw.

- `api`: `ReviewPolicy(intervalMonths, leadDays = 60)` is part of `DocumentClassVersion` (`review`); `Review.dueOn(validFrom, nextReview, policy)` (valid-from date plus interval; the document's `nextReview` only shortens it and counts only after the valid-from date), `Review.taskDue` (lead time reached), `Review.recertifiedDueOn` (chosen date or today plus interval, after today). `ReviewTask`, `ReviewStatus` (Open, Recertified, Revised, Withdrawn, Obsolete). `Lifecycle`: Published/ReviewDue → Withdrawn (`Withdraw`, out of force without a successor).
- `core`: `DocumentClassService.reviewPolicy(classId)` (latest class version). `DocumentService` sets `review_due_on` when a version becomes valid. `ReviewService` (inside `Db.transact`): `pendingWork`, `openTask`, `markReviewDue` (Published → ReviewDue on the review date; it stays valid), `closeObsolete`, `tasksOf(owner, open)`, `recertify(task, nextDueOn, comment)`, `revise(task, kind, reason)` (new version, or links the open one), `withdraw(task, reason)`. `ReviewJobs`: `tick` enqueues `document.review-check` per tenant and interval (`dms.documents.reviewCheckSeconds`, 3600), `check(tenant)` handles each item in its own transaction. Platform actor `system:review`. Audit and outbox events: `document.review-task-created`, `document.recertified`, `document.review-revised`, `document.review-withdrawn`, `document.review-task-closed`.
- Tables (migration V18): `document_class_version.review_interval_months`/`review_lead_days`; `document_version.review_due_on` (changes only while the version is valid) and `withdrawn_at` (set by the database on withdrawal of a valid version); `dms.document_review` (one task per version and review date, at most one open, decided once; RLS, column grants). The lifecycle guard allows Published/ReviewDue → Withdrawn; a withdrawn released version keeps its number.

HTTP (module `app`, `ReviewApi`; only the document's owner decides, deputies do not):

| Method | Path | Purpose | Authorization |
|---|---|---|---|
| GET | `/api/v1/reviews?status=open\|done` | Review tasks of the caller's documents: `[{id, documentId, title, classPath, versionId, versionLabel, versionStatus, status, dueOn, createdAt, decidedAt, decidedBy, nextDueOn, newVersionId, comment, reviewIntervalMonths}]` | owner of the document, `ReadDocument` (filter) |
| POST | `/api/v1/reviews/{taskId}/recertify` | `{nextReviewOn?, comment?}`: new review date, no new version; 422 date not after today or missing without interval, 409 decided or not valid | owner of the document and `Recertify` |
| POST | `/api/v1/reviews/{taskId}/revise` | `{changeKind?: Major\|Minor, reason?}`: new draft (`newVersionId`) | owner of the document and `EditDraft` |
| POST | `/api/v1/reviews/{taskId}/withdraw` | `{reason}` (required): the version goes out of force | owner of the document and `Withdraw` |

Class settings: `review: {intervalMonths?, leadDays?}` in `POST /api/v1/admin/document-classes` and `…/{id}/versions` (omitted on a new version: kept), shown in `DocumentClassView.review`.
