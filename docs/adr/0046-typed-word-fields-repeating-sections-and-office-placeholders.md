# 0046: Typed Word fields, repeating sections and placeholders in Excel and PowerPoint: field types from the content controls, rows as canonical JSON in the existing string values

| Field | Value |
|---|---|
| Status | Proposed |
| Date | 2026-10-29 |
| Decided by | Philippe |

## Context

DMS-122 implements follow-ups of ADR 0042 (file documents and Word templates, DMS-115): authors use Word's typed content controls (check boxes, date pickers, drop-down lists and combo boxes) and repeating sections (e.g. a table row per item) in templates, Word splits `{{key}}` placeholders into several runs whenever formatting, spelling marks or revision ids change in the middle, and file documents are also Excel workbooks and PowerPoint presentations whose placeholders should be filled.

Given (ADR 0042): field definitions (`WordField`: key, label, type, required, source) are stored as JSON with the template's Word file (`template_word_file.fields`) and with each version's original (`document_version_file.word_fields`); the manual values are a map key → string (`document_version_file.word_values`, the API's `WordValues`), included sorted in the canonical content whose SHA-256 votes and approvals bind (`WorkflowDocuments.contentSha256`), frozen by the guard trigger of `document_version_file`, carried over to new versions, audited (`document.word-values-changed`) and compared (ADR 0044); the filler (`WordDocx`) works on the DOM of the package parts and is deterministic; the conversion worker converts DOCX, XLSX and PPTX into PDF/A; the `templates-api` module has no JSON library.

## Decision

1. **Field types from the content controls.** `WordFieldType` gets `Checkbox`, `Choice` and `Rows`; `WordField` gets `options` (allowed values of a Choice), `columns` (the fields of a Rows field) and `warning` (what the platform cannot fill as the file shows it). The scan types manual controls by their kind: `w14:checkbox` → Checkbox; `w:date` → Date; `w:dropDownList` and `w:comboBox` → Choice with the items' display texts as options (Word's empty "choose an item" entry is left out); a combo box is restricted to its items as well. The stored JSON keeps the old shape (`options`, `columns`, `warning` only when present), so earlier definitions read and write unchanged. On a new upload the previous definition of a key still wins for label, required and source; what the file decides structurally wins for the type of check boxes, drop-down lists and repeating sections, the options of drop-down lists, the column keys and the warnings (`WordFields.merge`). Designers may make any field (also a placeholder) a Choice with their own options, change column labels, types (Text, Date, Number, Checkbox, Choice) and required flags; a repeating section stays Rows and no other field becomes Rows (`WordFields.edit`, checked by `WordTemplateService.setFields`).
2. **Values stay strings.** Checkbox values are `true`/`false`, Choice values must be one of the options, dates stay ISO dates. A Rows value is a JSON array of objects (column key → string), stored **canonically** by `DocumentService.setWordValues` (`DocumentJson.writeRows`: values trimmed, empty values and empty rows left out, columns in the order of the definition, compact JSON; no rows = no value), at most 200 rows, every cell checked like a field of its column type, required columns checked on submission (`WordFields.rowProblems`). Since the rows are a value in the existing map, the approval checksum, the freeze guard, the carry-over to new versions, the audit of changed values and the comparison cover them without change, and no migration is needed. `WordFields.check` takes the row reader as a parameter, so `templates-api` stays free of JSON. A new upload keeps a value only if it is still valid for the field's (new) type.
3. **Filling typed controls** (`FieldValue`: Text, Date, Check, Rows; `WordFilling.values` builds them from the definitions; automatic metadata dates and the approval date are Date values as well):
   - Check box: `w14:checked` is set to 1/0 and the control's text becomes the symbol of `w14:checkedState`/`w14:uncheckedState` (default ☒/☐) with its font; the run keeps its other formatting. Elsewhere (placeholders, plain controls) ☒/☐ is printed.
   - Date picker: printed in the control's `w:dateFormat` and language (`w:lid`; Word's letters translated to `java.time` patterns, `dddd`/`ddd` as week days, other letters literal) and stored as `w:fullDate` (`yyyy-MM-ddT00:00:00Z`), so Word shows the picked date; without a format the date in the document language.
   - Drop-down list / combo box: the chosen text replaces the control's text, `w:lastValue` is set to the item's value.
4. **Repeating sections.** A `w15:repeatingSection` control with a manual tag is a Rows field; its columns are the manual fields (controls with a tag, `{{key}}` placeholders) of its first item (`w15:repeatingSectionItem`). Filling replaces the items by one copy of the first item per row, each filled with the row's values (automatic fields inside get the document's values), removes the section's placeholder flag and data binding, numbers the `w:id` of the copied controls after the highest id of the part (deterministic) and drops bookmarks from the copies; without rows the section stays as it is. What is not supported is reported as the field's warning instead of failing silently: a section without item, nested repeating sections (copied unchanged into every row), a section without manual fields, and fields inside a section without tag (that section is not repeated; its fields are filled like others). The form and the template page show the warnings.
5. **Placeholders split over runs.** The text elements of a paragraph (Word `w:t` of a paragraph or inline control without nested ones, PowerPoint `a:t`, Excel `t` of a shared or inline string) are read as one text (`Placeholders`), so `{{key}}` is found across runs; filling writes the value into the text element where the placeholder starts (it takes that run's formatting) and removes the rest of the placeholder from the following ones. Text around placeholders stays in its run; runs are only changed when a placeholder in them has a value.
6. **Excel and PowerPoint** (`OfficeFields`, same pipeline as Word: scan on upload, definitions merged, form, filling for preview, draft and copy PDFs, the archived PDF/A via the conversion worker, the filled download): XLSX placeholders in `xl/sharedStrings.xml` (rich text runs included, phonetic runs skipped) and in inline strings of the sheets; PPTX placeholders in `ppt/slides/slideN.xml`. Values are text (a filled cell stays a text cell; line breaks become spaces on slides). An unreadable XLSX/PPTX is refused with 422 `invalid_office_file`. `GET …/versions/{versionId}/filled` returns the filled file in its own format and name (audited as `document.filled-file-downloaded`); `filled.docx` stays for Word files.

## Alternatives considered

Where row values live:

| Criterion | Chosen: canonical JSON string in the existing value map | Typed JSON values (`Map[String, Json]`) | Separate table of rows |
|---|---|---|---|
| Migration, guard, carry-over | None | Column semantics change, every reader of `word_values` changes | New table with its own guard, grants, RLS, carry-over |
| Approval checksum | Covered as before (value string) | Canonical JSON of values must be redefined (checksums of approved versions would change unless versioned) | Must be added |
| API | `values` stays `Map[String, String]`; the client serializes rows | Union type in the API and the generated client | Separate endpoint |
| Cost | Rows are a string the client parses; the server canonicalizes | Cleaner type | Most code |

Splitting placeholders:

| Criterion | Chosen: read the paragraph's text elements as one text, write into the first | Merge the runs of the paragraph (normalize) | Only content controls |
|---|---|---|---|
| Formatting of untouched text | Kept in its runs | Lost where runs are merged | Kept |
| Placeholder formatting | That of the run where it starts | Same | n/a |
| Excel/PowerPoint | Same code for `t`/`a:t` | Would differ per format | Not available there |

Excel and PowerPoint:

| Criterion | Chosen: placeholders in shared strings/inline strings and slides | Typed cells (numbers, dates as cell values) | Not supported |
|---|---|---|---|
| Effort | Small, same pipeline | Cell types, styles and shared-string reference counting | None |
| Result | Text cells | Calculable cells | Authors fill by hand |

## Consequences

### Positive

- Templates can use the controls authors know from Word; the form shows a check box, a date input, a select or a table of rows with add/remove.
- Rows, check boxes and choices are bound by the approval and frozen like every manual value; no schema change.
- Placeholders work regardless of how Word split them; Excel and PowerPoint files get the same field pipeline.
- Unsupported constructs are visible as warnings in the form and on the template page.

### Negative / Cost

- Row values are JSON inside a string: clients parse and serialize them; the comparison of versions (ADR 0044) shows a changed Rows value as its JSON text.
- Filled Excel cells are text, not numbers or dates; formulas, charts, comments, sheet headers and footers, slide layouts, masters and notes are not filled.
- A combo box is restricted to its items, although Word allows free text there.
- Nested repeating sections and repeating sections whose items are not the direct children of the section's content are not filled (reported).
- Word templates stay DOCX; XLSX/PPTX are filled as originals of file documents only.

## Follow-ups

- Could be done: a readable diff of Rows values in the version comparison (rows and columns instead of JSON).
- Could be done: typed Excel cells (numbers, dates) and Excel/PowerPoint templates (`template_word_file` for XLSX/PPTX).
- Could be done: free text for combo boxes (a `Choice` with `allowOther`), nested repeating sections.
- Could be done: headers/footers of sheets and slide masters, notes pages.

## Reversibility

**high**: No migration; the new types are additive in the stored definitions (old definitions read unchanged). Removing the types would turn existing Checkbox/Choice/Rows values into text values (rows as JSON text); the bound checksums stay valid since the values are unchanged strings.
