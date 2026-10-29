# 0044: File documents: comparison and search by the filled Word text, in-memory preview cache, content signature check, class list for designers

| Field | Value |
|---|---|
| Status | Proposed |
| Date | 2026-10-29 |
| Decided by | Philippe |

## Context

DMS-121 implements the follow-ups of ADR 0042 (file documents and Word templates, DMS-115):

- The version comparison (ADR 0033) compared the structured content of file versions, which is not used: it showed no changes even when the Word document changed.
- The search index (ADR 0029) held only the file name of a file document.
- `preview.pdf` (and the draft and copy PDFs of file versions) converted the original with the conversion worker on every request; a conversion takes seconds and the worker converts one file at a time.
- Uploads were accepted by extension only; a PDF named `.xlsx` reached LibreOffice's spreadsheet filter and failed late (at preview or, worse, in the approval's rendering job).
- Designers (role `TemplateDesigner`) cannot read the document class configuration (`Configure` on `DocumentClasses`), so the "New Word template" form picked the class through an existing template.
- The Word export of structured documents (`GET …/document.docx`) had no button.

Given: the filler `WordDocx` is deterministic (equal original and values give equal bytes, ADR 0042); `FileVersions.filled` fills a version's original as the filled DOCX download gives it (automatic values, manual values, the approval of an approved version); `ContentDiff` compares `TemplateContent` section by section and block by block with a word-level diff; the search indexer loads versions in a transaction of the tenant (`SearchSource.load`), like the rendering job loads its input; the application runs as several identical instances behind a load balancer.

## Decision

1. **Comparison of file versions.** For a file document, `GET /documents/{id}/compare` fills both originals (`FileVersions.filled`, names from the user directory, the approval with deputy from the workflow) and reads their paragraphs (`WordDocx.paragraphs`: the main document part in document order, tables and text boxes included, tabs as spaces, breaks as line breaks, without deleted text of tracked changes, field codes and the fallback copy of drawings; paragraphs in heading styles `Heading n` / `Überschrift n` / `Title` become headings). They are passed to `VersionComparison.compare` as one section `original` of paragraph and heading blocks, so `ContentDiff` and the existing compare UI show the text changes unchanged. `VersionComparison` (documents-api) adds the manual Word field values that differ to `fields` (keys `word:<key>`, labelled by the field definitions, dates typed) and the original's name and SHA-256 to `metadata` (`originalName`, `originalChecksum`). The response gets the optional `original` (name, size, SHA-256 of both, `changed`, `textCompared`). The text is compared only if both versions have Word originals (or one has none yet); if either original is in another format (PDF, spreadsheet, presentation), `textCompared` is false and only the file is compared. Headers, footers and notes are not compared as text (their fields are automatic values, which the metadata comparison covers).
2. **Search text of file documents.** `SearchDocuments` indexes the file name and, for Word originals, `WordDocx.text` of the original filled as released (automatic values, manual values, release date and approver; without the deputy note, which only the workflow knows): all paragraphs of the body, then headers, footers, footnotes and endnotes. It is read in the indexer's transaction through `SearchDocuments.Originals` (as the rendering job reads originals and names); a failure is logged and the version is indexed with its name only. Main and `SearchRebuild` use `SearchDocuments.withOriginals` (the rebuild CLI now also reads storage and the user directory configuration). PDF and other formats stay name-only; their text would need extraction or OCR (ADR 0041 follow-up). Changes are confined to the app adapters; the `search` module is unchanged.
3. **Preview cache in memory, bounded.** `FileVersions.previewPdf` keeps the converted PDF of a version's (filled) original in a `PreviewCache`: key = version id + SHA-256 of the bytes handed to the converter. Since the filler is deterministic, those bytes are a function of the original's SHA-256 and all filled values (manual, automatic, approval), so a new upload, a changed value or field, a metadata change printed by an automatic field or an approval gives a new key; filler changes do as well. The cache holds the unstamped PDF; draft marking and identification are stamped per request. Least recently used entries are evicted beyond a byte budget (`dms.conversion.previewCacheMegabytes`, default 64, `DMS_CONVERSION_PREVIEW_CACHE_MB`; 0 disables it); a PDF above a quarter of the budget is not kept. `preview.pdf`, `draft.pdf` and `copy.pdf` (for versions without archived rendition) use it; the rendering job does not (the archived rendition is always converted from the bound input). PDF originals are never converted and need no cache.
4. **Content signature check.** Before an original other than a DOCX is accepted, its first 8 KiB must fit its extension (`DocumentConverter.contentMatches`): ZIP for Office Open XML and OpenDocument, OLE compound document for the binary Office formats (`.doc` may also be RTF, which Word writes under that name), `%PDF-` within the first KiB for PDF, `{\rtf` for RTF; text formats (`txt`, `csv`, `html`, `htm`) must not start like a binary format nor contain NUL bytes (unless UTF-16 with byte order mark). Mismatches are refused with 422 `file_type_mismatch` naming what was expected. A DOCX is parsed completely by the field scan anyway (422 `invalid_docx`). Attachments (ADR 0041) are not converted and are not checked.
5. **Class list for designers.** `GET /api/v1/admin/template-classes` (`EditTemplates`) lists the latest version of every document class with key, label, description and the label path of its ancestors, read-only and sorted by path. The "New Word template" form picks the class from it.
6. **Word export button.** Structured documents get "Als Word herunterladen" next to the PDF download, with the same options (change history, list of attachments), calling the existing `GET …/document.docx`. Hidden for file documents (their Word file is the filled original) and for versions without PDF (e.g. withdrawn drafts).

## Alternatives considered

Text compared for file versions:

| Criterion | Chosen: paragraphs of the filled DOCX on the JVM | Text of converted PDFs (PDFBox) | Raw XML / original bytes |
|---|---|---|---|
| Works for drafts | Yes, no conversion | Needs a conversion per version (seconds) | Yes |
| Shows the filled values in context | Yes | Yes | No (placeholders) |
| Structure for the diff | Paragraphs and headings, as blocks | Lines broken by the layout: noise on every reflow | None |
| Other formats (xlsx, pptx, pdf) | Not compared (file only) | All convertible formats | — |
| Cost | Unzip + DOM, milliseconds | Conversion + extraction | — |

Search text of file documents:

| Criterion | Chosen: filled DOCX text at indexing time | Text extracted from the archived PDF/A rendition | Unfilled original text |
|---|---|---|---|
| Matches what readers see | Yes (release values; deputy note missing) | Exactly | No values |
| Formats | DOCX only | All approved originals | DOCX only |
| Cost per indexing | Read original + fill | Read rendition + PDF text extraction (PDFBox), layout noise | Read original |
| Reuse | `FileVersions.filled`, as the download and comparison | New extraction code | — |

PDF text extraction of renditions would also cover PDF and spreadsheet originals; it stays an option (see follow-ups).

Preview cache:

| Criterion | Chosen: bounded in-memory LRU per instance | Object storage (derived objects per version and state) | No cache |
|---|---|---|---|
| Hit rate with several instances | Per instance: up to one conversion per instance and state | Shared across instances | None |
| Survives restarts | No | Yes | — |
| Storage and lifecycle | None; memory bounded by configuration | Objects outside the document's retention and legal hold rules (ADR 0031, 0041): need their own cleanup (drafts change often), versioned buckets keep every state | None |
| Reproducibility / evidence | Not part of the record (like on-demand PDFs, ADR 0039) | Derived objects would look like records but are not | — |
| Load on the conversion worker | Once per state and instance | Once per state | Every request (seconds each, one at a time) |
| Complexity | ≈ 40 lines, no schema | Keys, cleanup job, permissions, migration of storage layout | None |

Content check:

| Criterion | Chosen: magic bytes per extension | Content type of the multipart part | Content sniffing library (Apache Tika) |
|---|---|---|---|
| Trust | Bytes of the file | Declared by the client, often `application/octet-stream` | Bytes |
| Dependency | None | None | ≈ 3 MB (core) up to tens of MB (parsers) |
| Precision | Container level (ZIP vs. OLE vs. PDF vs. text); a ZIP with the wrong inner type (xlsx named .docx) passes | — | Inner types |

Class list for designers: granting designers `Configure` on document classes (too broad: they could change profiles) or reading classes through the templates they can see (the previous workaround: a class without template could not be chosen).

## Consequences

### Positive

- Readers see what changed in a Word document: the filled text, the manual values and the original's checksum, in the existing comparison UI; non-Word originals at least show that and how the file changed.
- File documents are found by their text, values and names.
- An unchanged draft is converted once per instance; the conversion worker is no longer called on every page view.
- Wrongly named files are refused at upload with a clear message instead of failing in the conversion or the approval's rendering job.
- Designers create Word templates for any class directly.

### Negative / Cost

- The comparison of file documents reads and fills two originals per request (object storage reads; milliseconds for typical documents).
- Indexing a file document reads its original from storage inside the indexer's transaction and resolves names (a second short transaction), like the rendering job; a storage outage leaves the text out until the document is indexed again.
- The cache is per instance and lost on restart; with n instances an unchanged draft may be converted up to n times. Memory use up to the configured budget.
- The content check works on the container level: an `.xlsx` that is really a `.docx` (both ZIP) passes and fails in the conversion.
- Text in headers and footers is not compared; text of PDF, spreadsheet and presentation originals is neither compared nor indexed.

## Follow-ups

- Should be done: text of PDF and other originals for search and comparison (PDF text extraction of the archived rendition or OCR worker, ADR 0041).
- Possible: shared preview cache (object storage with lifecycle rules) if many instances serve the same drafts; single-flight for concurrent conversions of the same state.
- Possible: check the inner type of ZIP packages (`[Content_Types].xml` main part) against the extension.
- Still open from ADR 0042: approval note and page numbers stamped into the archived rendition of file versions (stamping with PDFBox must keep PDF/A conformance: embedded fonts, revalidation with veraPDF).

## Reversibility

**high**: No schema change. The comparison of file versions is an optional parameter of `VersionComparison.compare` and an optional field of the response; the search text an injected `Originals` (`Originals.none` restores name-only indexing, a rebuild applies it); the cache can be disabled by configuration; the content check is one function; the class list one read-only endpoint.
