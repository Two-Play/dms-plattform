# Module `rendering`

Archive renditions of approved versions (DMS-44, ADR 0008, ADR 0025): HTML → PDF/A-2b through the rendering worker
(`workers/rendering`), validation with veraPDF, storage in object storage with SHA-256.

Layout: `api` holds public types and interfaces, `core` the implementation. Other modules may only depend on `api`.

- `api`: `ArchiveStandard`, `RenditionRequest` (job payload, job kind `document.render-rendition`), `RenditionEvents`
  (outbox event `document.rendition-failed`).
- `core`:
  - `RenditionJobs`: the job handler (load page → render → validate → store → attach and complete the approval) and
    `enqueue`; failure notification through audit and outbox.
  - `RenditionLayout`: running header and footer (document ID, version, valid-from, approval note, page x of y), metadata,
    file identifier; pure. A deputy's approval note names the approver they stood in for ("on behalf of" / "i. V. für",
    `RenditionRequest.onBehalfOf`, DMS-49).
  - `PdfRenderer` / `WeasyPrintRenderer`: HTTP client of the worker. `PdfValidator` / `VeraPdfValidator`: veraPDF in-process.
  - `RenditionArchive`: object storage (`renditions/<tenant>/<document>/<version>.pdf`) with checksum check on read.
  - `RenditionSource`: port implemented by the application (`dms.app.DocumentRenditions`) for the page of a version (or,
    for a file document, its original with a `prepare` step that fills the Word fields, DMS-115) and the completion
    (attach rendition, complete approval).
  - `DocumentConverter` / `LibreOfficeConverter`: HTTP client of the conversion worker (`workers/conversion`, ADR 0042):
    office files → PDF/A-2b, HTML → DOCX. A PDF original that conforms to PDF/A-2b is archived unchanged.
    `DocumentConverter.contentMatches` checks that the first bytes of an upload fit its extension (DMS-121, ADR 0044).

Tests: `RenditionLayoutSpec` (pure), `PdfRenditionSpec` (worker image built by Testcontainers, veraPDF, PDFBox text checks),
`ConversionWorkerSpec` (conversion worker image built by Testcontainers: DOCX → valid PDF/A-2b, HTML → DOCX),
`ContentSignatureSpec` (pure);
end to end with workflow, database, SeaweedFS and download in `modules/app` (`RenditionApiSpec`).
