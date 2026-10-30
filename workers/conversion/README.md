# Conversion worker

Office files → PDF/A-2b and HTML → DOCX with LibreOffice (headless) behind a small HTTP API (DMS-115, [ADR 0042](../../docs/adr/0042-file-documents-and-word-templates.md)).
The worker is stateless: no database, no object storage, no credentials. The application sends the file and gets the converted
bytes back; it fills the fields of Word documents itself (JVM, before conversion), validates every PDF/A with veraPDF and stores it.

Used for:

- the archived rendition of an approved file document version (job `document.render-rendition`, `dms.rendering.core.RenditionJobs`):
  the original with its Word fields filled, converted into PDF/A-2b;
- previews and draft/copy PDFs of file document versions (on request, not stored);
- the Word export of structured documents (`GET …/versions/{versionId}/document.docx`): the HTML page of the version → DOCX.

## Run

```bash
docker compose up -d conversion       # http://localhost:9181, see compose.yaml
curl -s localhost:9181/health         # {"status": "ok", "converter": "LibreOffice 25.2.3.2 …; worker 1"}
curl -s --data-binary @Vertrag.docx -o Vertrag.pdf "localhost:9181/convert?target=pdf/a-2b&name=Vertrag.docx"
```

The application finds it at `dms.conversion.url` (`DMS_CONVERSION_URL`, default `http://localhost:9181`; timeout
`DMS_CONVERSION_TIMEOUT_SECONDS`, default 120). Tests build the image from this directory with Testcontainers
(`ConversionWorkerSpec`); the API tests of the application use a stand-in.

## API

`POST /convert?target=<target>&name=<file name>` with the file as body (`Content-Length` required, at most 64 MB,
`DMS_CONVERSION_MAX_BYTES`):

| Target | Accepted extensions | Result |
|---|---|---|
| `pdf/a-2b` | doc, docx, docm, dot, dotx, dotm, odt, ott, rtf, txt, html, htm, xls, xlsx, xlsm, xlt, xltx, ods, ots, csv, ppt, pptx, pps, ppsx, pot, potx, odp, otp, odg, otg, vsd, vsdx, pdf | PDF/A-2b (`SelectPdfVersion` 2 of LibreOffice's PDF export) |
| `docx` | html, htm, odt, rtf, doc | Word document (`MS Word 2007 XML`); HTML is opened in Writer |

The extension of `name` selects the import filter and the PDF export filter (Writer, Calc, Impress, Draw).
Responses: `200` with the bytes and headers `X-Converter` (LibreOffice and worker version), `X-Worker-Image` and
`X-Worker-Build` (image reference and build fingerprint as for the rendering worker, DMS-93; `DMS_CONVERSION_IMAGE` in
`compose.yaml`); `400` malformed request; `413` too
large; `415` unsupported type; `422` LibreOffice could not convert the file (or took longer than
`DMS_CONVERSION_TIMEOUT_SECONDS`, default 110); `500` unexpected failure. `GET /health` answers `200`.

Note: LibreOffice opens a file whose content does not match its extension as text rather than failing; the application
checks DOCX files itself when they are uploaded (fields are read from them), and every PDF/A is validated before it is archived.

## Isolation

Every conversion runs in its own temporary directory with its own LibreOffice profile (`-env:UserInstallation`), deleted
afterwards; one conversion at a time per container, scale with replicas. LibreOffice runs as an unprivileged user on a read-only
file system with `/tmp` as tmpfs. The command line is fixed; no user input reaches it except the file and its extension. Run the
container without outbound network in production (macros are not executed in headless conversion, but linked content could be
fetched otherwise).

## Pinning

Base image by digest (the same as the rendering worker), LibreOffice and the fonts by Debian package version. Fonts with the
metrics of the common Office fonts keep line and page breaks close to Word: Liberation (Arial, Times New Roman, Courier New),
Carlito (Calibri), Caladea (Cambria), plus Noto. Conversion output is not byte-reproducible (LibreOffice writes the creation time
into the PDF); the archived rendition is stored once and referenced by its SHA-256, so this does not affect integrity. Bump
`WORKER_VERSION` in `server.py` when the output changes.
