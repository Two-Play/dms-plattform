# 0039: Document view and on-demand PDFs: every readable version rendered as HTML, draft PDFs and copies with change history printed on request and never stored

| Field | Value |
|---|---|
| Status | Accepted (2026-10-29, implemented in DMS-112) |
| Date | 2026-10-29 |
| Decided by | Philippe |

## Context

Users want to read a document on the web, not only its metadata, and to download it as PDF while it is still a draft (DMS-112). Until now:

- The document page showed the live preview only while the caller edited a draft (DMS-39); every other status (in review, approved, published, superseded) showed metadata only. The policy demo "IT security policy" showed nothing of its wording.
- PDFs existed only for approved versions: the archive rendition (PDF/A-2b, ADR 0025), produced once between the last vote and the approval and stored in WORM storage.
- `HtmlRenderer` (templates) renders resolved content as fragment (live preview, test mode) or stand-alone page (archive rendition, `current.html` of DMS-58). `DocumentRenditions.html` (app) resolves a frozen version for the archive rendition.
- Business concept 5.1 and US-03.2: drafts must be recognizable as "Draft – not valid"; the archived rendition of an approved version must stay unchanged and provable by its checksum.
- Users also asked for metadata on the document page: status, author, issuing body ("herausgebende Stelle") and "index", and for an optional change history appended to the PDF.

Questions: how the page gets the content of any version, what a draft PDF is (format, marking, storage, audit), how a change history reaches an approved version without touching the archived file, and what "index" and "issuing body" mean in our model.

## Decision

1. **HTML of any readable version**: `GET /api/v1/documents/{id}/versions/{versionId}/html` (`ReadDocument` on the version, like reading it) returns the content resolved with the version's stored values as `HtmlRenderer` fragment, in the document language, plus `draftMarking`. Versions that were never approved (`releasedAt` empty: draft, in review, in approval, a withdrawn draft) are rendered like the live preview: values that do not fit their field definition and blank values are left out (shown as missing), system fields without value are marked neutrally, and the draft marking "Entwurf – nicht gültig" / "Draft – not valid" appears as banner and watermark. Approved versions (approved, published, review due, superseded, withdrawn after publication) are rendered from their frozen version package (content and values) without marking. Rendering happens per request (`DocumentRenditions.view`); nothing is cached or stored. The web page shows the fragment in a scrollable box styled by the shared `document.css` (`@dms-document`).
2. **Draft PDF**: `GET …/versions/{versionId}/draft.pdf?changelog=` for open versions (draft, in review, in approval; 409 otherwise) prints the same page with the rendering worker on demand. The header names "Entwurf – nicht gültig" next to document id (and document number, if any) and version; the watermark repeats on every page (`document.css`, `@media print`); the footer says when (tenant time zone) and by whom it was generated and "nicht freigegeben, nicht archiviert". The PDF is **not validated, not stored and not archived**; it is printed with the worker's PDF/A-2b variant (no worker change), but it is not an archival object. Every download is audited as `document.draft-pdf-downloaded` with version, status, `changelog` flag and the **SHA-256 of the bytes handed out**, so a circulating draft can be traced back.
3. **Approved versions**: "PDF herunterladen" gives the archived rendition as before (`…/rendition`). The archived file is never altered. With "Änderungshistorie anhängen" the page downloads `GET …/versions/{versionId}/copy.pdf?changelog=true` (approved versions only, 409 otherwise): a copy printed on demand with header and footer of the rendition (valid-from date, approval note with deputy) plus "Kopie – maßgeblich ist die archivierte Fassung" and the SHA-256 of the archived rendition. Not stored; audited as `document.copy-pdf-downloaded`.
4. **Change history appendix** (`changelog=true`, both PDFs): after a page break, one table row per version up to and including the printed one, **limited to the versions the caller may read**: version label and kind (major/editorial), change summary (`change_reason`), status with created (date, by), approved, valid from and published dates, and the approver (with "i. V. für" for deputies, from the workflow approval record). Persons are printed with display names from the user directory (ADR 0037), falling back to the user id; dates in the tenant's time zone.
5. **"Index" = revision index = the version number major.minor** (ADR 0021), shown as "Index 1.1"; for an open version the label's working state is shown separately ("Index 1.1 (Entwurf, Arbeitsstand 3)"). No new field.
6. **"Herausgebende Stelle" = the core field `organizationalUnit`**: it already is the unit a document belongs to and that is responsible for it (business concept 3.2 "Mandant / Organisationseinheit", authorization scope, compliance cockpit per unit). The metadata panel labels it "Herausgebende Stelle"; no new core field `issuingUnit`, no migration (V26 stays unused).
7. **Metadata panel** on the document page (all statuses): status, version/index, document number (core field `documentNumber` of DMS-113, shown when the version has it), author of the version, owner, issuing body, document class, valid from/until, confidentiality, approval (who, when, on behalf of whom; from the workflow approval, else `releasedBy`/`releasedAt`), published, template with version; then the remaining template fields under "Weitere Angaben". Persons through `UserName`. The version history gets "Anzeigen" per version (`/documents/{id}?version=…`), so every readable version can be read on the page.

## Alternatives considered

| Criterion (HTML of a version) | Chosen: render per request, fragment as JSON | Store the HTML at approval | Use the client-side preview for all statuses |
|---|---|---|---|
| Approved versions show the frozen wording | Yes, from the frozen package (same renderer as the rendition) | Yes | No: preview resolves the template, not the frozen content |
| Drafts in review/approval (caller cannot edit) | Yes, with marking | Not covered | Needs the template preview endpoint and form values |
| Storage, migrations | None | New column or objects, backfill for existing versions | None |
| Cost | Resolve + render per view (≈ 2–25 ms, modules/templates README) | None after approval | Same as chosen |
| Byte-identical to the rendition | Same renderer and stylesheet; renderer changes show in old versions | Yes | No |

| Criterion (draft PDF) | Chosen: on demand, not stored, audited with SHA-256 | Store as transient object with expiry | Client-side print (browser "Print to PDF") |
|---|---|---|---|
| Marking on every page | Header + watermark via WeasyPrint | Same | Depends on the browser; header not controllable |
| Same layout as the archive rendition | Yes (worker, `document.css`) | Yes | No (browser engine) |
| Traceability of handed-out drafts | Audit entry with SHA-256 | Object + audit | None |
| Storage / clean-up | None | Lifecycle rule, retention questions for drafts | None |
| Load | One worker render per download | Same, cached per working state | None on the server |

| Criterion (PDF format of the draft) | Chosen: worker's PDF/A-2b variant, not validated | Plain PDF variant in the worker | Validate as PDF/A too |
|---|---|---|---|
| Worker change | None; the running worker image works | New variant, image rebuild, XMP handling without PDF/A | None |
| Risk of being mistaken for an archive copy | Low: header, watermark, footer "nicht archiviert"; not stored | Lower (no PDF/A identification) | Same as chosen |
| Cost per download | Render only | Render only | Render + veraPDF (≈ 1 s) |

| Criterion (change history on approved versions) | Chosen: separate on-demand copy | Append to the archived rendition | Second archived rendition per version |
|---|---|---|---|
| Archived file unchanged, checksum provable | Yes | No (violates ADR 0021/0025) | Yes |
| History up to date (later versions, publication dates) | Yes, at print time | Frozen at approval | Frozen at approval |
| Storage | None | – | One more WORM object per version |

| Criterion (issuing body) | Chosen: `organizationalUnit` | New optional core field `issuingUnit` |
|---|---|---|
| Matches existing data | Yes; demo data and authorization already use it | Empty for all existing documents |
| Model changes | None | Core field version bump, profile change, migration of class profiles, UI field |
| Expressiveness | Responsible and issuing unit are the same | Can differ (e.g. issued by the board for a unit) |

## Consequences

### Positive

- Every readable version shows its wording on the page, including published versions such as the policy demo; the frozen wording of approved versions is what readers see.
- Drafts can be circulated as PDF that cannot be mistaken for a valid version, and every copy handed out is traceable by its checksum.
- The archived rendition stays untouched; the change history is always current at print time.
- No migration, no new storage, no worker change; DMS-113 only has to fill `documentNumber` for the panel and the PDF header to show it.

### Negative / Cost

- Each view and each PDF download resolves and renders the version again; PDFs cost a worker render (seconds for long documents). Without a running worker the PDF download answers 503 (`rendering_unavailable`); the HTML view does not need the worker.
- A renderer or stylesheet change also changes how old approved versions look on screen (not their archived PDF). The archived rendition remains the authoritative form.
- Draft PDFs carry a PDF/A-2b identification although they are not archival objects; the marking and the footer make their status clear.
- The change history lists only versions the caller may read, so two people may get different appendices for the same version.
- "Herausgebende Stelle" cannot differ from the responsible unit.

## Follow-ups

- Must be done: Word export with change history (DMS-115).
- Must be done: rate limiting or a size limit for on-demand PDFs if the worker becomes a bottleneck.
- Later: a plain PDF variant in the rendering worker for drafts (drops the PDF/A identification); a separate `issuingUnit` core field if a tenant needs issuing and responsible unit to differ; watermark by confidentiality (F-06.2, ADR 0025 follow-up); showing the HTML of withdrawn or destroyed content only to records managers.
- Ruled out: altering or re-archiving the archived rendition to add a change history; storing draft PDFs.

## Reversibility

**high**: The endpoints are additive and stateless; nothing is stored except audit entries. Switching to stored HTML or a plain PDF variant changes only the app module (and the worker). Introducing `issuingUnit` later needs a core field version and a data migration from `organizationalUnit`.
