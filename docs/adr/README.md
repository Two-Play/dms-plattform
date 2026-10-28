# Architecture Decision Records

This directory documents the architecture decisions of the DMS platform. Each ADR describes context, decision, a side-by-side comparison of alternatives, consequences, follow-ups and reversibility.

## Maintenance

- **New ADR**: copy [0000-template.md](0000-template.md), assign the next free number, name the file `NNNN-short-title.md`, add an entry to the index.
- **Status**: `Proposed` → `Accepted`; with open questions `Open – spike required`. Status changes are made in the ADR itself, with a date.
- **Do not delete**: Superseded ADRs are kept. Their status is set to `Superseded by NNNN`; the new ADR refers back with "Supersedes NNNN".
- **Do not rewrite content**: Accepted ADRs are only changed for status changes, spike results or factual corrections. A changed decision is a new ADR.
- **Reversibility**: low, medium or high, each with a rationale for the effort of a later revision.

## Index

| No. | Title | Status | Reversibility |
|---|---|---|---|
| [0001](0001-modularer-monolith.md) | Modular monolith with asynchronous workers | Accepted | medium |
| [0002](0002-scala-3-lts.md) | Scala 3 LTS (3.9.x) as backend language | Accepted | high |
| [0003](0003-zio-2.md) | ZIO 2 as effect and runtime system | Accepted | high |
| [0004](0004-build-sbt-1.md) | Build with sbt 1.12 | Accepted | low to medium |
| [0005](0005-zitadel-identitaet.md) | ZITADEL as identity provider | Accepted | medium |
| [0006](0006-flowable-workflow.md) | Flowable as embedded workflow engine | Accepted (validated by spike) | medium |
| [0007](0007-strukturiertes-inhaltsformat.md) | Structured content format is primary, DOCX for import/export only | Accepted | high |
| [0008](0008-rendering-weasyprint.md) | Rendering HTML → PDF/A with WeasyPrint, validation with veraPDF | Accepted (spike DMS-23) | low |
| [0009](0009-persistenz.md) | PostgreSQL, S3 with Object Lock, OpenSearch | Accepted | medium |
| [0010](0010-outbox-und-jobs-in-postgres.md) | Transactional outbox and job queue in PostgreSQL | Accepted | low |
| [0011](0011-java-21-lts.md) | Java 21 LTS as runtime | Accepted | low |
| [0012](0012-datenbankzugriff.md) | Database access: Magnum, ZIO Quill or plain JDBC | Accepted (spike) | medium |
| [0013](0013-api-first-tapir.md) | API-first with Tapir on ZIO HTTP | Accepted | medium |
| [0014](0014-mandantentrennung-rls.md) | Tenant isolation with tenant_id and row-level security | Accepted | high |
| [0015](0015-frontend-react-tailwind-shadcn.md) | Frontend with React, Tailwind CSS and shadcn/ui | Accepted | medium |
