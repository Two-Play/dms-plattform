# 0049: Upload flow for documents and templates: upload templates of classes created on demand, field values kept in filled Word documents as custom XML part and custom properties

| Field | Value |
|---|---|
| Status | Proposed |
| Date | 2026-10-30 |
| Decided by | Philippe |

## Context

DMS-127: authors want to upload a Word document, let the platform fill the automatic fields, fill the remaining fields in a form, look at a PDF preview and decide **at the end** whether it becomes a document (e.g. a policy, a central instruction) or a template (e.g. for contracts). Product owner decisions: a form for the open fields is enough (no editing of the Word text in the browser); templates need only the four-eyes release that exists; one shared flow. In addition: "use as template" on existing Word documents; filled values must never overwrite the keys, and a filled document uploaded again must bring its keys and values back.

Given (ADR 0042, 0044, 0046): a file document is always created from a released template version, which supplies class version, metadata form (profile), computed fields and number range; its content is the uploaded original (`dms.document_version_file`, unchanged, SHA-256 bound by the approval). A Word template is a template version with a Word file (`dms.template_word_file`) and goes through test and the four-eyes release; designers (`EditTemplates`) create templates, authors and owners (`CreateFromTemplate`) create documents. The filler (`WordDocx`) fills content controls (tag = key, title = label) and `{{key}}` placeholders deterministically; before DMS-127 a filled placeholder was replaced by its value (the key was gone) and filling removed data bindings.

## Decision

1. **One wizard, nothing stored before the last step.** `POST /api/v1/uploads/fields` reads the fields of the file (as an upload of an original does: blocklist, convertible types, content signature, hardened parsing) with the values the file carries and the automatic values known before creation (author, title from the file name, version and status of a new draft); `POST /api/v1/uploads/preview.pdf` (multipart: `values` before `file`) fills the file with the entered values, virus-scans it, converts it with the conversion worker and stamps "preview – not valid" on every page. Neither stores anything nor writes audit entries (like reading). Both require the author, owner or template designer role. The frontend wizard (`/documents/upload`, "Dokument hochladen" on the documents list) has four steps: file → fields → preview → purpose.
2. **As document: an upload template per class, created on demand** (migration V32). `POST /api/v1/uploads/classes/{classId}/template` returns the class's upload template for the class's latest version: a template marked with `dms.template.file_upload_class_id` (one per class and tenant, unique index), whose versions have empty structured content and no Word file and are **inserted as released** by the platform (`released_by = system:file-upload`, creator = the first uploader; the check "releaser ≠ creator" of V8 holds). A newer class version gets a new released version and the previous one is deprecated. Upload templates are hidden from the catalog and the designers' list (`TemplateStore.released/latestVersions`). The wizard then uses the existing paths unchanged: `POST /api/v1/documents` (kind `File`, title and metadata in the usual form), `PUT …/file`, `PUT …/word-fields`; review, approval and publication follow as for every document. Creating a document is authorized as always (`CreateFromTemplate` with the entered unit and class); listing classes and resolving the upload template require an author or owner role covering the class. `GET /api/v1/uploads/classes` lists those classes.
3. **As template**: the existing endpoints (create template with empty content, upload the DOCX as its Word file); only designers; the four-eyes release is unchanged; afterwards it is selectable under "New document".
4. **Use as template**: `POST /api/v1/documents/{id}/versions/{versionId}/word-template` (reading the version and `EditTemplates`) creates a template draft for the latest version of the document's class with a **copy** of the version's original (new object under `templates/…`, same SHA-256) and its field definitions; the entered values (`word_values`) are not copied.
5. **Keys and values in filled Word documents** (`WordFieldStore`, `WordDocx`):
   - Content controls keep tag and title (as before). A **filled placeholder becomes a plain text content control** tagged with its name (`{{customer}}` → `w:sdt` with `w:tag="customer"`, `w:text`) in the formatting of the run where the placeholder started; text around it stays in runs of that formatting. Placeholders without value stay text.
   - Every filled value is written into a **custom XML part** `customXml/itemN.xml` in namespace `urn:dms:fields:1` (`<fields xmlns="urn:dms:fields:1"><customer>ACME</customer>…</fields>`, one element per key, with `itemPropsN.xml`, a fixed store item id and the relationship from the main document part) and into **custom document properties** `docProps/custom.xml`, one per key named `dms.<key>` (`vt:lpwstr`). Values are raw: text as printed, ISO dates, `true`/`false`, rows as JSON. Both are rewritten on every fill (keys without value removed, other custom properties and other custom XML parts kept); nothing is added to a document without values.
   - **Data binding**: plain text controls with a single-line text value get `w:dataBinding` to their element (`/dms:fields[1]/dms:<key>[1]`), so Word keeps the visible text and the element in sync when the text is edited in Word. Rich text controls, date pickers, check boxes, drop-down lists, multi-line values and controls inside repeating sections are not bound (Word would show the raw element value there; see alternatives).
   - Filling stays deterministic (fixed ids, sorted keys, fixed entry times); formatting is kept.
6. **Recovering values on upload** (`WordDocx.recover`, `OfficeFields.pick`): for each key the candidates in order: state of a check box (unchecked only in a document the platform filled) or date picker (`w:fullDate`), text of a filled control (not showing its placeholder), element of the custom XML part, custom property; the first candidate valid for the field's type wins (e.g. a date printed as "31.01.2027" in a plain text control is skipped for the ISO date of the part). `PUT /api/v1/documents/{id}/file` applies them to the manual fields: if the platform filled the file (it carries the part or `dms.` properties) they replace the previous values, otherwise they only fill fields without value. The audit entry `document.file-uploaded` lists the keys taken over (`carriedValues`). The original is stored unchanged with its SHA-256, as before.

## Alternatives considered

Document from an upload without a template chosen first:

| Criterion | Chosen: upload template per class, created on demand | File documents without template (`template_id` nullable) | Author chooses any released template of the class | Designer creates an "upload template" per class by hand |
|---|---|---|---|---|
| Model and consumers | One column on `dms.template`; documents unchanged | Documents and versions without template: template impact, comparison, search, renditions, number range lookup, preview fields must handle "none" | None | None |
| Metadata form, number range, computed fields | From the class version as for every document | Separate path through the class | Yes | Yes |
| Misleading links | None: the template is the class's upload template | — | The document appears in the impact analysis of an unrelated (e.g. structured) template | None |
| Four-eyes on the template | Not needed: empty content, platform-released, hidden | — | Yes | Yes, but busywork per class and class version |
| Works without setup | Yes | Yes | Only if the class has a template | No |

Where the values live in the filled Word document:

| Criterion | Chosen: custom XML part (bound to plain text controls) + custom properties | Custom properties only | Custom XML part only | Only the controls' text |
|---|---|---|---|---|
| Key visible in the background | Yes (part and properties) | Yes | Yes | Tag only (placeholders lost) |
| Word keeps value and text in sync | Plain text controls (data binding) | No | Plain text controls | — |
| Usable in Word fields (`DOCPROPERTY`) | Yes | Yes | No | No |
| Robust recovery (types, rows) | Raw values in the part, properties as fallback | Word limits property text to 255 characters | Yes | Dates and check boxes in printed form only |

Filled placeholders:

| Criterion | Chosen: become plain text controls with the key as tag | Replaced by the value (before) | Kept as text next to the value (e.g. hidden text) |
|---|---|---|---|
| Key stays in the document | Yes | No | Yes, but fragile (hidden text is printed by some converters) |
| Re-fillable, re-scannable | Yes | No | Partly |
| Formatting | Run where the placeholder started | Same | Same |

Binding typed controls: binding date pickers (`w:storeMappedDataAs`), check boxes and drop-down lists would let Word sync them too, but what the conversion worker (LibreOffice) prints for bound typed controls is not verified; their values are recovered from the controls' state instead.

## Consequences

### Positive

- One flow for both purposes; the author sees the filled document as PDF before anything is stored; existing lifecycles (document approval, template four-eyes release) are reused unchanged.
- Uploading as document needs no template setup; the class decides form and number range.
- Filled documents keep their keys: re-uploading a filled or Word-edited document loses no values; Word users see the values as custom properties and can use them in `DOCPROPERTY` fields.
- "Use as template" turns a proven document into a template in one step without its values.

### Negative / Cost

- Upload templates are released without a second person; they carry no content and are hidden, but the template table has rows that are not designers' templates (filtered in two queries; other readers by id still see them, e.g. the document's template link).
- The wizard sends the file up to three times (analysis, preview, creation); fine for office documents within the attachment size limit.
- Filled Word documents get three more parts and a changed relationship and content types part; a placeholder filled once is a content control from then on (the text around it is split into runs).
- Only plain text controls are bound; edits in Word to other controls are recovered from the controls' state or text, rich text and date values in printed form are not parsed (the stored value is used).
- Custom properties are limited to 255 characters in Word's dialog; long values (rows) are complete in the custom XML part only.
- The preview before creation lacks the number and the class's values (assigned on creation).
- Values carried by a re-uploaded file replace entered values when the platform filled the file; an author who wants the platform's values back re-enters them in the form.

## Follow-ups

- Could be done: bind date pickers, check boxes and drop-down lists once the conversion worker's output for bound typed controls is verified.
- Could be done: a title proposed from the file name in the metadata form of the wizard; the class's metadata in the preview (choose the class before the preview).
- Could be done: text extraction of PDF uploads in the wizard (fields only for Office files today).
- Could be done: XLSX/PPTX values as custom properties (placeholders there are still replaced by text).
- Ruled out: editing the Word text in the browser (product owner decision).

## Reversibility

**medium**: The upload templates are ordinary template rows marked by one nullable column; dropping the feature leaves them as released templates of their classes (documents keep their link). The stored values are additive parts of the filled (never stored) documents; removing `WordFieldStore` only stops writing them. Placeholders that became content controls in documents downloaded meanwhile stay controls, which the filler handles as before.
