# Workers

Processing outside the JVM. The rendering worker is a stateless HTTP service called by the application's job runner (ADR 0025); later workers (OCR) may claim jobs from the PostgreSQL queue themselves (`FOR UPDATE SKIP LOCKED`, ADR 0010).

| Worker | Purpose | Status |
|---|---|---|
| `rendering/` | HTML → PDF/A-2b with WeasyPrint over HTTP; the application validates with veraPDF | In use since DMS-44 (sprint 7), see [rendering/README.md](rendering/README.md) |
| `conversion/` | Office files (DOCX, XLSX, PPTX, …) → PDF/A-2b and HTML → DOCX with LibreOffice over HTTP; the application validates with veraPDF | In use since DMS-115, see [conversion/README.md](conversion/README.md) |
| `ocr/` | Text recognition for legacy and third-party documents | after MVP |

WeasyPrint and font versions are pinned exactly so that identical inputs produce identical PDFs (ADR 0008).
