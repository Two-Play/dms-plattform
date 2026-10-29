# 0042: File documents and Word templates: uploaded originals per version, Word fields as content controls filled on the JVM, LibreOffice as separate conversion worker

| Field | Value |
|---|---|
| Status | Proposed (2026-10-29, implemented in DMS-115) |
| Date | 2026-10-29 |
| Decided by | Philippe |

## Context

DMS-115: besides documents generated from structured templates (ADR 0007), authors want to manage finished files, above all Word documents: upload, version, review, approve, publish and download them again as Word. During the story the goal was sharpened: **Word documents with metadata fields** are the primary case. A Word document carries fields that the platform fills (document number, version/index, title, author, owner, issuing unit, valid from/until, status, approval date and approver, metadata such as partner data) and fields the author fills in a form generated from the fields found in the document. The result is (a) a PDF from the filled document (PDF/A for approved versions as archived rendition, draft PDFs marked as today) and (b) the filled Word document, which stays the editable artifact. **Word templates** ("Vorlagen"): a designer uploads a DOCX with fields as template, versions it, adapts it (replace the DOCX, edit the field definitions: label, required, type, automatic or manual); authors create documents from it. Structured templates stay the standard for policies (e.g. "Zentralanweisung") and are not changed, except that the optional change history and the list of attachments ("Anlagenverzeichnis", follow-up of ADR 0041) are available in the PDFs of both kinds. A Word export of structured documents was asked for originally and became the lowest priority.

Given: versions are frozen packages guarded by triggers (ADR 0021); attachments are stored once per document and SHA-256, scanned, covered by legal holds and the evidence report, uploaded through a streamed multipart parser (ADR 0041); votes and approvals bind the SHA-256 of the canonical content (DMS-41); the archived rendition is produced between the last vote and the approval by the rendering job, validated with veraPDF (ADR 0025); draft and copy PDFs are printed on demand (ADR 0039). Templates have a lifecycle with test and four-eyes release (ADR 0007, business concept 4.1) and bind a document class version, whose profile defines the metadata form. Users know Word fields from SharePoint/Office as content controls ("Inhaltssteuerelemente").

## Decision

1. **Document kind** (migration V27): `dms.document.kind` is `Structured` (default) or `File`, set at creation, never changed (no update grant). A file document is created from a template like every document: the template supplies class, profile (the metadata form), computed fields and number range; its structured content is kept with the version but neither shown nor rendered. Creating from a released **Word template** (`POST /api/v1/documents/word`) copies the template's DOCX into the document's storage (one copy per document, so retention and legal hold follow the document) as the draft's original with the template's field definitions. `POST /api/v1/documents` with `kind: File` creates a file document without original; the author uploads one.
2. **Original per version**: `dms.document_version_file` links a stored file (the attachment storage of ADR 0041: `dms.document_attachment_file`, content-addressed per document, scanned, legal hold) to the version with its file name, the **field definitions** found in it and the **values of the manual fields** (`word_values`). The bytes are never changed; the SHA-256 is that of the upload. A guard trigger allows insert and update only while the version is a draft of a file document (version row locked `FOR SHARE`), checks that the file belongs to the document and forbids deletion. `PUT /api/v1/documents/{id}/file` (multipart, same streaming parser, size limit, blocklist and scan port as attachments) replaces the original of the draft as its next working state; only extensions the conversion worker converts into PDF/A are accepted (`file_not_convertible`), so every version can be approved. A new version (`DocumentService.newVersion`) takes the file, the definitions and the values over (`based_on`), without copying bytes; uploading a revised DOCX keeps the definitions of keys known before and the values of fields that stay manual.
3. **Field convention** (documented in the frontend help):
   - **Content controls** (Word: Developer tab → Controls; plain text or rich text control): the **tag** is the field key, the **title** (alias) its label. Tag `dms:<key>` → filled by the platform (automatic); any other tag → manual field. Keys: a letter, then letters, digits, `_`, `-`, `.`, at most 64 characters.
   - **Placeholders** `{{key}}` / `{{dms:key}}` as fallback, only if the whole placeholder is in one run of text (Word splits runs on formatting or spell-check changes; content controls are the robust form). Since DMS-122 (ADR 0046) placeholders split over several runs are found as well.
   - Automatic keys: `documentId`, `documentNumber`, `title`, `version` (index: `1.1` once approved, `1.1-draft.3` while open), `status`, `author`, `owner`, `issuingUnit` (= `organizationalUnit`, ADR 0039), `class`, `approvedAt`, `approvedBy` (with "i. V. für" for deputies), and every metadata field of the class by its key (e.g. `validFrom`, `validUntil`, partner fields). Dates in the document language; persons by display name (ADR 0037).
   - Field definitions: label, type (Text, Date, Number), required, source (Automatic, Manual). Found fields get defaults (manual keys ending in `date`/`datum` are dates); designers edit them in the Word template; the keys always come from the file.
4. **Filling on the JVM with the JDK's XML parser** (`dms.app.WordDocx`): the DOCX package is unzipped, `word/document.xml`, headers, footers, footnotes and endnotes are changed as DOM, all other parts are copied unchanged. A filled content control keeps its first run's formatting (only the gray placeholder style and the placeholder flag are removed), multi-line values become line breaks, a data binding is removed, and the control stays, so the filled document can be filled again. Fields without value keep their content. Parsing is hardened (no DTDs, no external entities, limits on parts and unpacked size); output is deterministic (fixed entry times). The filled document is computed on request from the stored original and values (`GET …/versions/{versionId}/filled.docx`, audited like a draft PDF with the SHA-256 handed out); for an approved version with the frozen values and the approval.
5. **Approval binds original and values**: `WorkflowDocuments.contentSha256` adds `file` (name, SHA-256 and, if any, the manual values) to the canonical content. Automatic values come from the field values, which are bound already, and from the approval itself. Submission requires an original and all required manual fields.
6. **Rendition**: the rendering job loads a `RenditionOriginal` for file versions: the original read back against its SHA-256, filled with the field values and the approval (date, approver, deputy) of the job, converted by the conversion worker into PDF/A-2b, validated with veraPDF, stored like every rendition. A PDF original that already conforms to PDF/A-2b is archived unchanged. The audit entry records the original's name and SHA-256. The converted rendition has no running header/footer of its own (the Word document carries what its fields show).
7. **Conversion worker** `workers/conversion`: LibreOffice 25.2 (headless, Debian packages pinned, fonts metric-compatible with Arial/Times/Calibri/Cambria) behind a small HTTP API like the rendering worker (`POST /convert?target=pdf/a-2b|docx&name=…`), one conversion at a time, own profile and temp directory per request, read-only container, compose service `conversion` on port 9181, `dms.conversion.url`. Separate from the rendering worker (see alternatives).
8. **Reading and printing file versions**: `GET …/versions/{versionId}/preview.pdf` (inline, not audited, like reading): the archived rendition of an approved version, otherwise the filled original converted on request (a PDF original as it is) with the draft marking stamped on every page (PDFBox). `draft.pdf` and `copy.pdf` (ADR 0039) work for file versions: converted original (draft) or archived rendition (copy), stamped with marking, identification and footer facts, followed by the appendices printed by the rendering worker and appended with PDFBox. `…/html` and `current.html` answer 409 for file documents. The original is downloaded unchanged with `GET …/versions/{versionId}/file` (headers as attachments: UTF-8 file name, `nosniff`, `ETag`, `Repr-Digest`).
9. **List of attachments** ("Anlagenverzeichnis", ADR 0041 follow-up): `attachments=true` on `draft.pdf` and `copy.pdf` appends name, size and SHA-256 of the version's attachments after the change history, for both kinds; audited with the flag.
10. **Word templates**: a template version with a row in `dms.template_word_file` (DOCX object `templates/<tenant>/<template>/<file>.docx`, SHA-256, scan status, field definitions) is a Word template; its structured content is empty. The row changes only while the template version is a draft (guard), whoever uploads or edits definitions is recorded as editor (`edited_by`), so the four-eyes release of templates holds. Endpoints: `PUT/GET /api/v1/admin/templates/{id}/versions/{v}/word` (+ `/fields`, `/file`), `POST /api/v1/admin/templates/{id}/word-versions` (next draft with the Word file taken over), `GET /api/v1/admin/word-templates` (designers) and `GET /api/v1/word-templates` (released ones for authors). Testing, release, deprecation and the template permissions are those of every template.
11. **Evidence report**: each file version lists its original as optional field `original`, which is read back against its SHA-256 (check `original checksum (<name>)`).
12. **Search**: the text of a file document is its file name (no text extraction yet); metadata are indexed as before.
13. **Word export of structured documents** (lowest priority): `GET …/versions/{versionId}/document.docx?changelog=&attachments=` converts the version's HTML page (marking as first paragraph, appendices) with the conversion worker into DOCX; audited as `document.docx-downloaded`. Backend only; no button yet.

## Alternatives considered

Where the content of a file document lives:

| Criterion | Chosen: link table per version to the attachment storage | Columns on `document_version` | Separate storage for originals |
|---|---|---|---|
| Freeze | Own small guard; the version guard (V9/V18) stays untouched | Version guard and update grants must be rewritten | Own guard |
| Scan, content addressing, legal hold, evidence | Inherited from ADR 0041 | Same if it references the attachment storage | Duplicated |
| Take-over to the next version | One row copied | Copied with the row | One row copied |

Template of a file document:

| Criterion | Chosen: always created from a template | Template optional (class only) |
|---|---|---|
| Class, metadata form, number range, computed fields | From the template as for structured documents | Separate path through the class |
| Model changes | `kind` column only | `template_id` nullable in documents and versions; every consumer (template impact, comparison, search, renditions) must handle "no template" |
| Cost for plain uploads | A template per class is needed (its content is not used) | None |

Field marking:

| Criterion | Chosen: content controls, `{{key}}` as fallback | Only `{{key}}` placeholders | Word merge fields (`MERGEFIELD`) / bookmarks |
|---|---|---|---|
| Known to users (SharePoint/Office) | Yes | No | Merge fields partly |
| Robust against Word's run splitting | Yes | No (only within one run) | Merge fields yes, bookmarks fragile |
| Label for the form | Title (alias) | Key only | No |
| Re-fillable after filling | Yes (control stays) | No (text replaced) | Merge fields yes |

Filling library:

| Criterion | Chosen: JDK XML (DOM) on the package | Apache POI (XWPF) | docx4j |
|---|---|---|---|
| Content controls in body, headers, footers | Direct, by element | Partial (`XWPFSDT` read-only, no header controls) | Full (JAXB model) |
| Formatting and unknown parts kept | Yes: only changed parts are rewritten | Rewrites the package model | Rewrites via JAXB |
| Dependencies | None | poi-ooxml (+ xmlbeans, commons) ≈ 15 MB | docx4j + JAXB ≈ 30 MB |
| Code to maintain | ≈ 250 lines, tested | Less for simple placeholders, workarounds for controls | Least, heavy runtime |

Conversion:

| Criterion | Chosen: separate LibreOffice worker | LibreOffice in the rendering worker image | Gotenberg | Conversion in the JVM (docx4j/POI → PDF) |
|---|---|---|---|---|
| Fidelity for Word/Excel/PowerPoint | Good (LibreOffice) | Same | Same (LibreOffice inside) | Poor (FO/limited layout) |
| PDF/A-2b | LibreOffice export, validated with veraPDF | Same | Same | Extra work |
| Effect on the rendering worker | None: its image stays small (~200 MB) and its output reproducible | Image ~900 MB, LibreOffice's attack surface and start-up in the archive print path | None | None |
| HTML → DOCX (Word export) | Yes | Yes | No (converts to PDF only) | Partial |
| Operation | One more small service like the existing one | One service | Third-party service with its own API and versions | None |

Filled Word document:

| Criterion | Chosen: filled on request from original + values | Stored per working state / at approval |
|---|---|---|
| Storage | None | One object per change |
| Reproducible | Deterministic filler; approval values frozen | Trivially |
| Changes of the filler | Show in later downloads of old versions | Frozen |

## Consequences

### Positive

- Word documents are first-class: uploaded originals stay unchanged and provable, fields are filled by the platform and the author, the filled Word document is the editable artifact, the approved PDF/A comes from exactly the bound original and values.
- Word templates reuse the template lifecycle and permissions; designers adapt them by replacing the DOCX or editing definitions.
- Everything of ADR 0041 (scan, size limit, content addressing, legal hold, evidence report) applies to originals without new infrastructure.
- The rendering worker is untouched; LibreOffice is isolated in its own container.
- Change history and list of attachments are available in the PDFs of both kinds.

### Negative / Cost

- One more container (≈ 900 MB image, 1 GB memory limit); conversions take seconds; previews of drafts convert on every request (no cache).
- Converted PDFs are not byte-reproducible; a repeated rendering job after a failure between storing and completing stores a second object version (the referenced one is the one attached).
- The filler supports content controls and single-run placeholders; nested controls, repeating sections, checkbox/date-picker controls are filled as text; data-bound controls lose their binding when filled. (Addendum DMS-122, ADR 0046: typed controls, repeating sections and split placeholders are supported.)
- Draft and copy PDFs of file versions are stamped with a standard font (not embedded) and are not PDF/A; they are not archived (as ADR 0039).
- Every file document needs a template (for class and form), even for plain uploads.
- Version comparison (ADR 0033) compares the (unused) structured content of file versions: it shows no changes; the checksum of the original differs.
- File documents have no full text in search beyond the file name.

## Follow-ups

- Done in DMS-121 (ADR 0044): frontend button for the Word export of structured documents; version comparison for file documents (original SHA-256, changed manual values, text diff of the filled Word documents); text of Word originals in search; in-memory cache of converted previews per version and state; content signature check of uploads; class list for designers creating Word templates.
- Should be done: text extraction of PDF and other originals for search (OCR worker, ADR 0041 follow-up); rate limits for conversions.
- Should be done: approval note (and page numbers) stamped into the archived rendition of file versions, if auditors require it beyond the fields of the document.
- Done in DMS-122 (ADR 0046): check boxes, date pickers and drop-down lists as typed fields; repeating sections as lists of rows; placeholders split over several runs; placeholders in Excel and PowerPoint files.
- Later: Word templates with structured sections (hybrid).
- Ruled out: altering the uploaded original; storing filled documents per working state.

## Reversibility

**medium**: The tables are additive (`kind` defaults to `Structured`); file documents can be removed with their tables. The filler and the conversion worker sit behind small interfaces (`WordDocx`, `DocumentConverter`); replacing the filler by docx4j or the worker by Gotenberg changes only those. Removing the file and values from the bound checksum would invalidate the comparison for approved file versions.
