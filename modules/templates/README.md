# Module `templates`

Templates, clause library, field definitions, CEL expressions (business concept ch. 4).

Layout: `api` holds public types and interfaces, `core` the implementation. Other modules may only depend on `api`.

## Content model (ADR 0007)

`api`, package `dms.templates` (pure):

- `TemplateContent`: sections (editable or locked) of blocks (`Heading`, `Paragraph`, `BulletList`, `OrderedList`, `Table`, `ClauseRef`, `Conditional`, `Repeat`, `PageBreak`) with inline text and `Placeholder(path, format)`, plus computed fields (`ComputedField(key, expression)`, business concept 4.2).
- `TemplateVersion`, `TemplateStatus`, `TemplateLifecycle`: lifecycle Draft → Testing → Released → Deprecated → Archived, Testing → Draft; only drafts are editable; release needs a person who neither created nor changed the version (`contributors`).
- `TemplateSchema`/`Variable`/`ValueType`: the fields a template may use, `FieldValue`: values; `ResolvedContent`/`Resolution`: the result of resolving; `Location`/`ContentIssue`: positions of problems; `Limits`: evaluation limits.

## Expressions and resolution (DMS-34, ADR 0020)

`core`, package `dms.templates.core`:

- `TemplateCompiler.compile(content, schema, limits)` type-checks every CEL expression once (conditions must be `bool`, repeat collections lists, computed fields in order) and checks placeholders (path in scope, known format fitting the type), item names, heading levels and nesting depth. Problems are `ContentIssue`s with section id, block path (e.g. `/blocks/1/otherwise/0`, `/computed/0`), part (`condition`, `collection`, `itemName`, `content/2`, ...), line and column.
- `Resolver.resolve(compiled, values, language)` evaluates computed fields and conditions, expands repeats and replaces placeholders with formatted values. Missing optional values become `ResolvedInline.Missing(path, required = false)`, missing required values are listed in `Resolution.missingRequired`. `Resolution.branches` records the outcome of every conditional. `Resolver.computedValues(compiled, values)` evaluates only the computed fields, e.g. to store them with a document (DMS-37).
- `Formatting`: `plain` (default), `number`, `amount`, `date:long`, `date:short`; locale from the document language (`en`, `de`): `1.234,50 €` / `€1,234.50`, `31. Oktober 2027` / `October 31, 2027`, `28.10.2026` / `10/28/2026`. Selection codes print their label, yes/no as `ja`/`nein` or `yes`/`no`.
- `TestCases.generate(compiled)`: typical values, empty optional fields, long values, and for every conditional values that make it true and false. Candidates come from the field types and from literals in the expressions; branches no data can reach are reported as uncovered.
- `ProfileSchema`: document class profile → `TemplateSchema` (a field is required if it is required for `Approved`), `MetadataValue` ↔ `FieldValue`.

### Expression language

CEL with the standard library and macros (`has`, `all`, `exists`, `exists_one`, `map`, `filter`), no custom functions, no I/O. Variables are the profile's fields (by key), earlier computed fields and repeat items. Types: text, selection, person and document references → `string`; number → `double`; amount → `map` with `amount` (double) and `currency`; date → `timestamp` (midnight UTC); yes/no → `bool`; multiple → `list`. Missing optional values evaluate as empty values (`""`, `0.0`, `false`, `[]`, epoch); `'endDate' in present_fields` tells whether a field has a value. Numbers are doubles: write `termMonths == 12.0` (CEL has no implicit `==` between `double` and `int`; `<`, `>` work across numeric types).

Examples: `termType == 'fixed'`, `monthlyFee.amount * termMonths > 250000.0`, `endDate < timestamp('2030-01-01T00:00:00Z')`, `services.exists(s, s == 'Hosting')`.

Limits (`Limits`, defaults): 2,000 characters per expression and 24 nesting levels (checked on save); per evaluation 10,000 evaluation steps and 1,000 macro iterations; per resolution 2,000 repeat iterations, 2,000,000 characters of output and 2 s.

## Persistence (migration V8)

- `dms.template` (identity) and `dms.template_version` (PK template, version): content as `jsonb` in a versioned format (`{"format":1,"sections":[...],"computed":[...]}`, `ContentJson`), the document class version it was designed against (foreign keys to V7), status, creator, editors (`edited_by`), releaser.
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

Releasing a version does not deprecate older released versions; deprecating is an explicit status change.

## Locked sections and deviations (DMS-36)

US-01.1, business concept 4.1. Pure domain logic in `templates-api`; persistence and the API follow with documents (sprint 5).

**Edit rules** (`EditRules.check(template, document, requests, policy)` returns typed `EditViolation`s with section id and `reason`). A document has the template's structure; sections are matched by id.

- Editable sections: content and title change freely; they may be removed and reordered among each other.
- Locked sections: blocks may only change if a `DeviationRequest(sectionId, reason, requestedBy, requestedAt)` for the section exists. A request permits the edit; it does not approve it (that is the extra review step). Structural changes are never allowed, not even with a request: a locked section cannot be removed, renamed (title), unlocked or moved relative to any other retained template section (so an editable section cannot be moved across a locked one either).
- New sections (ids not in the template): only with `EditPolicy.allowNewSections` (default true), never locked, and only where the nearest template sections before and after are editable; the start and end of the document count as editable. Runs of locked sections therefore stay contiguous.
- Section ids are unique.

**Deviation report** (`DeviationReport.compare(template, document, requests)`): every difference in locked sections, each with a `DeviationLocation` (section id, block path such as `block 2 › item 1 › block 0`, and the index of the first changed word token), the change and the requests of that section. Changes without a request are `unauthorized`. Reported changes: inserted/deleted/replaced text, formatting-only changes, inserted/removed/changed placeholders, changed conditions, repeats, clause references, heading levels and table header rows, inserted/deleted blocks, list items and table rows, and removed/moved/renamed/unlocked sections. Editable and new sections are not reported.

**Diff approach:** an LCS edit script (O(n·m), no dependency) on word tokens (whitespace is not significant; a placeholder or line break is one token) gives one text change per run of changed words, plus placeholder changes for the placeholders in that run. Blocks, list items and table rows are aligned with the same LCS; within a changed run, blocks of the same kind are paired (second LCS) and compared recursively, table cells by column. Locations use document positions; deletions point to where the content was removed.

**Workflow integration (sprint 6):** on submission the document workflow first rejects documents with `EditRules` violations (so unauthorized deviations never reach review), then calls `DeviationReport.requiresExtraReview(report)`; if true, it adds the additional review step configured for the document class (e.g. the legal department for "contract with clause deviation", business concept 6). The report is stored with the document version and shown to reviewers.

## HTML rendering and preview (DMS-39)

`core`, package `dms.templates.core`:

- `HtmlRenderer.render(resolved, options)` turns `ResolvedContent` into a semantic HTML fragment (one `article.dms-document`); `renderDocument` wraps it into a stand-alone document with the stylesheet embedded, which is what the PDF worker (ADR 0008) prints. All text and attribute values are escaped; the content model has no raw HTML, so field values cannot inject markup. No scripts, ids, inline styles or external references.
- `HtmlOptions`: document language (`lang`), field labels for missing-value markers, optional draft marking (`HtmlOptions.draft(language)`: "Draft – not valid" / "Entwurf – nicht gültig" as banner and watermark), title.
- Markup contract (stable class names; the stylesheet and the frontend rely on them): `section.dms-section[data-section][data-locked]` with `h2.dms-section-title`; `h1`–`h6.dms-heading.dms-heading-N`; `p.dms-paragraph`; `ul|ol.dms-list`; `table.dms-table` with `thead` (`th scope=col`) for header rows; `div.dms-clause-ref[data-clause][data-clause-version]` (until the clause library resolves references); `div.dms-page-break`; `span.dms-value[data-field]` for field values; `span.dms-missing[data-field][data-required]` (plus `dms-missing-required`) showing the field label for missing values; `div.dms-draft-banner` and `article.dms-draft[data-watermark]` for drafts.
- **Document stylesheet**: `core/src/main/resources/dms/templates/document.css` is the only copy. `HtmlRenderer.stylesheet` reads it for `renderDocument`; the frontend imports the same file through the Vite alias `@dms-document`. Every rule is scoped to `.dms-document`; screen-only rules (paper look, clickable missing values) are under `@media screen`, page size and margins under `@page`, the per-page watermark under `@media print`. The print-specific parts of the rendering spike (running header/footer, page x of y, clause counters) are added by the worker per job.
- `Preview.run(template, values, language)` (US-03.2): checks the values against the class profile (`Validation.checkValues`), leaves out values that do not fit and blank values, resolves and renders with draft marking. Returns the HTML, the missing required fields (required for `Approved`, without fields the platform sets) with labels, and issues (`out_of_range`, `wrong_type`, `unknown_option`, `system_field`, ... per field; `evaluation_failed`, `limit_exceeded` for the whole template, then the HTML is empty).
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
