# Workers

Asynchronous processing outside the JVM. Workers claim jobs from the PostgreSQL queue (`FOR UPDATE SKIP LOCKED`, ADR 0010) and store results in object storage.

| Worker | Purpose | Status |
|---|---|---|
| `rendering/` | HTML → PDF/A with WeasyPrint, validation with veraPDF | Spike T-00.7 (sprint 1) |
| `ocr/` | Text recognition for legacy and third-party documents | after MVP |

WeasyPrint and font versions are pinned exactly so that identical inputs produce identical PDFs (ADR 0008).
