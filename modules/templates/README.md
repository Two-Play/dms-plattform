# Module `templates`

Templates, clause library, field definitions, CEL expressions (business concept ch. 4).

Layout: `api` holds public types and interfaces, `core` the implementation. Other modules may only depend on `api`.

## Content model (ADR 0007)

`api`, package `dms.templates` (pure):

- `TemplateContent`: sections (editable or locked) of blocks (`Heading`, `Paragraph`, `BulletList`, `OrderedList`, `Table`, `ClauseRef`, `Conditional`, `Repeat`, `PageBreak`) with inline text and `Placeholder(path, format)`, plus computed fields (`ComputedField(key, expression)`, business concept 4.2) and reference fields (`ReferenceField(key, label, source, fields)`, DMS-43, ADR 0023): the author picks an entry of a master data source (`ReferenceSource.PartnerMaster`, key `partner-master`) and the mapped attributes (`ReferenceMapping(attribute, field)`) are taken over into profile fields.
- `TemplateVersion`, `TemplateStatus`, `TemplateLifecycle`: lifecycle Draft → Testing → Released → Deprecated → Archived, Testing → Draft; only drafts are editable; release needs a person who neither created nor changed the version (`contributors`).
- `TemplateSchema`/`Variable`/`ValueType`: the fields a template may use, `FieldValue`: values; `ResolvedContent`/`Resolution`: the result of resolving; `Location`/`ContentIssue`: positions of problems; `Limits`: evaluation limits.

## Expressions and resolution (DMS-34, ADR 0020)

`core`, package `dms.templates.core`:

- `TemplateCompiler.compile(content, schema, limits)` type-checks every CEL expression once (conditions must be `bool`, repeat collections lists, computed fields in order) and checks placeholders (path in scope, known format fitting the type), item names, heading levels and nesting depth, and reference fields (valid unique keys, known attributes, the identifier `id` mapped as source reference, each into a single text field of the profile that no other reference fills; location `/references/N`). Problems are `ContentIssue`s with section id, block path (e.g. `/blocks/1/otherwise/0`, `/computed/0`), part (`condition`, `collection`, `itemName`, `content/2`, ...), line and column.
- `Resolver.resolve(compiled, values, language)` evaluates computed fields and conditions, expands repeats and replaces placeholders with formatted values. Missing optional values become `ResolvedInline.Missing(path, required = false, Nil)`, missing required values are listed in `Resolution.missingRequired`. A computed field that reads a field without value has no value itself (DMS-83): its placeholders become `Missing(path, required = true, missingInputs)` naming the fields without value it read (also through other computed fields), and it is listed in `Resolution.missingComputed`. `Resolution.branches` records the outcome of every conditional. `Resolver.computedValues(compiled, values)` evaluates only the computed fields, e.g. to store them with a document (DMS-37); computed fields without value are left out. `Resolver.missingComputed(compiled, values)` lists them with their missing inputs; a document cannot be approved while there are any.
- `Formatting`: `plain` (default), `number`, `amount`, `date:long`, `date:short`; locale from the document language (`en`, `de`): `1.234,50 €` / `€1,234.50`, `31. Oktober 2027` / `October 31, 2027`, `28.10.2026` / `10/28/2026`. Selection codes print their label, yes/no as `ja`/`nein` or `yes`/`no`.
- `TestCases.generate(compiled)`: typical values, empty optional fields, long values, and for every conditional values that make it true and false. Candidates come from the field types and from literals in the expressions; branches no data can reach are reported as uncovered.
- `ProfileSchema`: document class profile → `TemplateSchema` (a field is required if it is required for `Approved`), `MetadataValue` ↔ `FieldValue`.

### Expression language

CEL with the standard library and macros (`has`, `all`, `exists`, `exists_one`, `map`, `filter`), no custom functions, no I/O. Variables are the profile's fields (by key), earlier computed fields and repeat items. Types: text, selection, person and document references → `string`; number → `double`; amount → `map` with `amount` (double) and `currency`; date → `timestamp` (midnight UTC); yes/no → `bool`; multiple → `list`. `'endDate' in present_fields` tells whether a field has a value. What a missing field reads as depends on where it is used (DMS-83, ADR 0020 addendum):

- In **conditions and repeat collections** it reads as an empty value (`""`, `0.0`, `false`, `[]`, epoch); a computed field without value reads as the result of its expression with empty values.
- In **computed fields** it is unknown: a computed field that reads it has no value and is shown as missing ("Total contract value (missing: Term in months)") instead of `0`. Only reading counts: `'termMonths' in present_fields ? monthlyFee.amount * termMonths : 0.0` and `!('endDate' in present_fields) || endDate > now` keep their value without the field, because the unknown does not decide the result. Numbers are doubles: write `termMonths == 12.0` (CEL has no implicit `==` between `double` and `int`; `<`, `>` work across numeric types).

Examples: `termType == 'fixed'`, `monthlyFee.amount * termMonths > 250000.0`, `endDate < timestamp('2030-01-01T00:00:00Z')`, `services.exists(s, s == 'Hosting')`.

Limits (`Limits`, defaults): 2,000 characters per expression and 24 nesting levels (checked on save); per evaluation 10,000 evaluation steps and 1,000 macro iterations; per resolution 2,000 repeat iterations, 2,000,000 characters of output and 2 s.

## Persistence (migration V8)

- `dms.template` (identity) and `dms.template_version` (PK template, version): content as `jsonb` in a versioned format (`{"format":1,"sections":[...],"computed":[...],"references":[...]}`, `ContentJson`; missing `computed`/`references` read as empty), the document class version it was designed against (foreign keys to V7), status, creator, editors (`edited_by`), releaser.
- Trigger `template_version_guard`: no deletes; only drafts may change content; only allowed status transitions; release data only at Testing → Released. A check constraint rejects a releaser who created or edited the version. At most one version per template in Draft or Testing. RLS with `dms.current_tenant()`, column-level update grants for `dms_app`.
- `TemplateService` (inside `Db.transact(tenant)`): create, new version, update draft, versions, list, catalog, status changes, check and test mode; every change is recorded in the audit trail (`template.created`, `template.version-created`, `template.draft-changed`, `template.status-changed`, `template.released`).

## HTTP (module `app`)

Administration requires `EditTemplates` (template designer); `TemplateApi`, `TemplateLifecycleApi`:

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/admin/templates` | Latest version of every template |
| POST | `/api/v1/admin/templates` | Create a template (version 1, Draft); 422 with positions for invalid content |
| POST | `/api/v1/admin/templates/check` | Check content against a class profile version without saving |
| GET | `/api/v1/admin/templates/{id}` | All versions |
| GET | `/api/v1/admin/templates/{id}/versions/{version}` | One version |
| POST | `/api/v1/admin/templates/{id}/versions` | Next version as a draft (409 while another one is open) |
| PUT | `/api/v1/admin/templates/{id}/versions/{version}` | Change a draft (409 otherwise) |
| POST | `/api/v1/admin/templates/{id}/versions/{version}/test` | Test mode with given values or generated sample data, with branch coverage |
| POST | `/api/v1/admin/templates/{id}/versions/{version}/status` | Status change (DMS-35); `Released` requires `ReleaseTemplate`: a template designer other than the creator and editors |
| GET | `/api/v1/templates` | Catalog: released versions only; every business role (`ReadTemplateCatalog`) |
| GET | `/api/v1/templates/{id}/versions/{version}/impact` | Impact analysis before release (DMS-50, see below): documents per status on earlier versions, open drafts by title and author; `EditTemplates` |

Releasing a version does not deprecate older released versions; deprecating is an explicit status change.

## Impact analysis before release (DMS-50)

US-01.2: before releasing a new version, a template designer sees which documents use the earlier versions of the template.

**Where it lives.** The analysis reads documents, and `templates-core` must not depend on `documents-core` (module rule). `DocumentService.templateUsage(templateId, version)` (module `documents`, types `TemplateUsage`, `StatusUsage`, `VersionUsage`, `OpenTemplateUse` in `documents-api`) answers the document side; `app` (`TemplateImpactApi`) combines it with `TemplateService.version` in one transaction of the caller's tenant. Not an architecture decision: it follows the existing pattern of `app` wiring the modules together (like `DocumentTemplates`).

**What it shows** (`GET /api/v1/templates/{id}/versions/{version}/impact`, any status of the version):

- `documents`: distinct documents with a version on an earlier version of the template;
- `byStatus`: per document status, the number of document versions and of distinct documents on earlier template versions; `byVersion`: the same per earlier template version (`templateVersion`, `documents`, `byStatus`), oldest first. A document with a published version and a new draft counts once under `Published` and once under `Draft`;
- `openDrafts`: open document versions (Draft, InReview, InApproval) on earlier template versions, most recently changed first, each with `documentId`, `versionId`, `title`, `author` (author of the open version), `version` label (`2.0-draft.1`), `status`, `templateVersion`, `updatedAt`;
- `existingDocumentsUnchanged: true` and `explanation`: documents stay bound to the template version they were created from, and new versions of a document keep the template version of the version they are based on (`DocumentService.newVersion`). Releasing a version therefore changes no existing document; approved and valid versions are frozen anyway (V9 guard), and open drafts continue on their template version. Only documents created from the catalog afterwards use the new version. `TemplateImpactApiSpec` proves this: every `dms.document` and `dms.document_version` row is identical before and after a release, a new version of a published document stays on the old template version, and a new document gets the released one.

**Who may see it.** `EditTemplates` (template designer): every possible releaser holds it, and so does the creator of the version, who prepares the release. Authors, readers and tenant administrators get 403.

**Drafts of other users.** Open drafts are normally visible only to their authors and owners (`ReadDocument`). The analysis lists them for template designers anyway, because judging a release needs to know who is working on what; it shows only title, author, version label, status, template version and time of the last change, never content or field values. The counts and the list are limited to the tenant by row-level security, not by the caller's document permissions (a template designer needs the whole picture of the template's use).

**Release stays independent of the analysis.** The status change to `Released` neither requires an acknowledgement (`acknowledgedImpact`) nor returns the analysis. Because a release changes no existing document, an acknowledgement would add a mandatory step without protecting anything, and it would change the existing status endpoint for every client. The frontend shows the analysis before it offers the release.

## Locked sections and deviations (DMS-36)

US-01.1, business concept 4.1. Pure domain logic in `templates-api`; requests are stored per document version and the report with it since DMS-79 (see modules/documents/README.md, ADR 0022). `templates-core` `DeviationJson` holds the JSON format of the report (stored form `{"format":1,"deviations":[...]}`).

**Edit rules** (`EditRules.check(template, document, requests, policy)` returns typed `EditViolation`s with section id and `reason`). A document has the template's structure; sections are matched by id.

- Editable sections: content and title change freely; they may be removed and reordered among each other.
- Locked sections: blocks may only change if a `DeviationRequest(sectionId, reason, requestedBy, requestedAt)` for the section exists. A request permits the edit; it does not approve it (that is the extra review step). Structural changes are never allowed, not even with a request: a locked section cannot be removed, renamed (title), unlocked or moved relative to any other retained template section (so an editable section cannot be moved across a locked one either).
- New sections (ids not in the template): only with `EditPolicy.allowNewSections` (default true), never locked, and only where the nearest template sections before and after are editable; the start and end of the document count as editable. Runs of locked sections therefore stay contiguous.
- Section ids are unique.

**Deviation report** (`DeviationReport.compare(template, document, requests)`): every difference in locked sections, each with a `DeviationLocation` (section id, block path such as `block 2 › item 1 › block 0`, and the index of the first changed word token), the change and the requests of that section. Changes without a request are `unauthorized`. Reported changes: inserted/deleted/replaced text, formatting-only changes, inserted/removed/changed placeholders, changed conditions, repeats, clause references, heading levels and table header rows, inserted/deleted blocks, list items and table rows, and removed/moved/renamed/unlocked sections. Editable and new sections are not reported.

**Diff approach:** an LCS edit script (O(n·m), no dependency) on word tokens (whitespace is not significant; a placeholder or line break is one token) gives one text change per run of changed words, plus placeholder changes for the placeholders in that run. Blocks, list items and table rows are aligned with the same LCS; within a changed run, blocks of the same kind are paired (second LCS) and compared recursively, table cells by column. Locations use document positions; deletions point to where the content was removed.

**Workflow integration (sprint 6):** on submission the document workflow first rejects documents with `EditRules` violations (so unauthorized deviations never reach review), then calls `DeviationReport.requiresExtraReview(report)`; if true, it adds the additional review step configured for the document class (e.g. the legal department for "contract with clause deviation", business concept 6). The report is stored with the document version (`DocumentVersion.deviations`, `DocumentVersion.requiresExtraReview` in `documents-api`) and shown to reviewers (`GET /api/v1/documents/{id}/versions/{versionId}/deviations`).

## HTML rendering and preview (DMS-39)

`core`, package `dms.templates.core`:

- `HtmlRenderer.render(resolved, options)` turns `ResolvedContent` into a semantic HTML fragment (one `article.dms-document`); `renderDocument` wraps it into a stand-alone document with the stylesheet embedded, which is what the PDF worker (ADR 0008) prints. All text and attribute values are escaped; the content model has no raw HTML, so field values cannot inject markup. No scripts, ids, inline styles or external references.
- `HtmlOptions`: document language (`lang`), field labels for missing-value markers, optional draft marking (`HtmlOptions.draft(language)`: "Draft – not valid" / "Entwurf – nicht gültig" as banner and watermark), title.
- Markup contract (stable class names; the stylesheet and the frontend rely on them): `section.dms-section[data-section][data-locked]` with `h2.dms-section-title`; `h1`–`h6.dms-heading.dms-heading-N`; `p.dms-paragraph`; `ul|ol.dms-list`; `table.dms-table` with `thead` (`th scope=col`) for header rows; `div.dms-clause-ref[data-clause][data-clause-version]` (until the clause library resolves references); `div.dms-page-break`; `span.dms-value[data-field]` for field values; `span.dms-missing[data-field][data-required]` (plus `dms-missing-required`) showing the field label for missing values; a computed field without value additionally has `dms-missing-computed`, `data-missing-inputs` (field keys, space-separated) and `span.dms-missing-hint` naming the missing fields by label ("missing: Term in months" / "fehlt: …", DMS-83); `span.dms-system[data-field]` with the field label and `span.dms-system-hint` ("assigned on save" / "wird beim Speichern vergeben") for fields the platform sets that have no value yet (DMS-80; `HtmlOptions.systemFields`, the preview passes the profile's system fields), styled neutrally instead of as missing; `div.dms-draft-banner` and `article.dms-draft[data-watermark]` for drafts.
- **Document stylesheet**: `core/src/main/resources/dms/templates/document.css` is the only copy. `HtmlRenderer.stylesheet` reads it for `renderDocument`; the frontend imports the same file through the Vite alias `@dms-document`. Every rule is scoped to `.dms-document`; screen-only rules (paper look, clickable missing values, sticky draft watermark, tables that never get wider than the page) are under `@media screen`; the paper padding shrinks with the available width, and a `@container (max-width: 520px)` query (ragged text, smaller headings and watermark) applies when the host element is a size container (the frontend's preview box is one, `@container` in Tailwind); page size and margins under `@page`, the per-page watermark under `@media print`. The print-specific parts of the rendering spike (running header/footer, page x of y, clause counters) are added by the worker per job.
- `Preview.run(template, values, language)` (US-03.2): checks the values against the class profile (`Validation.checkValues`), leaves out values that do not fit and blank values, resolves and renders with draft marking. Returns the HTML, the missing required fields (required for `Approved`, without fields the platform sets) with labels, followed by the fields without value that computed fields read (an approval needs them too), and issues (`out_of_range`, `wrong_type`, `unknown_option`, `system_field`, ... per field; `evaluation_failed`, `limit_exceeded` for the whole template, then the HTML is empty).
- `TemplateService.released(id, version)` loads a released version (other statuses: not found) and compiles it against its class profile; compiled versions are cached (LRU, 256 entries) by template id and version, which is safe because released versions and class profile versions never change and the version is always read through row-level security first. `PreviewTemplate.fields` lists the editable fields (no system fields), those the template uses (placeholders, conditions, repeats, computed fields) first.

HTTP (module `app`, `TemplatePreviewApi`), both with `ReadTemplateCatalog` (every business role) and only for released versions:

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/templates/{id}/versions/{version}/fields` | Form fields: key, label, description, type with rules, multiple, required, used in template |
| POST | `/api/v1/templates/{id}/versions/{version}/preview` | `{values, language}` → `{html, missingRequired: [{field, label}], issues: [{field, label, code, message}]}`; resolution and rendering run outside the database transaction |

### Performance

Acceptance criterion: the preview updates within 2 s (p95) after a field change. `PreviewSpec` resolves and renders a realistic contract template 50 times (`PreviewFixtures`: about twelve printed pages, 6,500 words, 16 numbered clauses with conditions, a repeated service list, two annex tables of 30 and 5 rows, computed fields; 62 KB of HTML). Measured on a developer laptop (2026-10-28), server time without HTTP:

| Path | Median | p95 |
|---|---|---|
| Compile + resolve + render (first preview of a version, cache miss) | 23.6 ms | 36.0 ms |
| Resolve + render (compiled version cached) | 2.2 ms | 2.7 ms |

During the full parallel test run the p95 rose to 76 ms and 4.4 ms. The test asserts p95 below 1 s and 250 ms respectively. In the browser the form waits 300 ms after the last change before it requests a preview, so the end-to-end delay is dominated by the debounce and the network.
