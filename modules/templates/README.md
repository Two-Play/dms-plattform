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
- `Resolver.resolve(compiled, values, language)` evaluates computed fields and conditions, expands repeats and replaces placeholders with formatted values. Missing optional values become `ResolvedInline.Missing(path, required = false)`, missing required values are listed in `Resolution.missingRequired`. `Resolution.branches` records the outcome of every conditional.
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
