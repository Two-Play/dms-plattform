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
| [0008](0008-rendering-weasyprint.md) | Rendering HTML → PDF/A with WeasyPrint, validation with veraPDF | Accepted (spike DMS-23; addendum DMS-44) | low |
| [0009](0009-persistenz.md) | PostgreSQL, S3 with Object Lock, OpenSearch | Accepted | medium |
| [0010](0010-outbox-und-jobs-in-postgres.md) | Transactional outbox and job queue in PostgreSQL | Accepted | low |
| [0011](0011-java-21-lts.md) | Java 21 LTS as runtime | Accepted | low |
| [0012](0012-datenbankzugriff.md) | Database access: Magnum, ZIO Quill or plain JDBC | Accepted (spike) | medium |
| [0013](0013-api-first-tapir.md) | API-first with Tapir on ZIO HTTP | Accepted | medium |
| [0014](0014-mandantentrennung-rls.md) | Tenant isolation with tenant_id and row-level security | Accepted | high |
| [0015](0015-frontend-react-tailwind-shadcn.md) | Frontend with React, Tailwind CSS and shadcn/ui | Accepted | medium |
| [0016](0016-object-storage.md) | Object storage: S3 API with Object Lock, SeaweedFS for local development and CI | Accepted | high |
| [0017](0017-audit-hash-chain.md) | Audit trail as hash chain per tenant with RFC 3161 anchoring | Accepted | medium |
| [0018](0018-authorization-policy.md) | Authorization as an in-app policy (RBAC + ABAC) | Accepted (addenda DMS-49, DMS-51, DMS-101, DMS-56, DMS-59) | medium |
| [0019](0019-metadata-model.md) | Metadata as JSONB values validated against versioned profiles | Accepted | medium |
| [0020](0020-template-expressions-cel.md) | Template expressions in CEL (Common Expression Language) | Accepted | medium |
| [0021](0021-document-versions.md) | Document versions as full version packages, frozen by database guards | Accepted | medium |
| [0022](0022-deviation-requests.md) | Deviation requests as rows per version, deviation report stored with the version | Accepted | high |
| [0023](0023-partner-master-integration.md) | Partner master: live lookup through a port, snapshot into field values | Accepted | high |
| [0024](0024-approval-workflow-integration.md) | Approval workflows: Flowable routes the steps, our tables hold the business truth | Accepted (addenda DMS-44, DMS-86, DMS-49) | medium |
| [0025](0025-archive-renditions.md) | Archive renditions: WeasyPrint as HTTP sidecar, PDF/A-2b checked with veraPDF, rendered between the last vote and the approval | Accepted | medium |
| [0026](0026-scheduled-publication-and-editorial-versions.md) | Scheduled publication by a job at tenant midnight; editorial versions through an owner-confirmation path | Accepted (addendum DMS-53) | medium |
| [0027](0027-acknowledgements.md) | Acknowledgements: readers of a valid version acknowledge it after an explicit opening, insert-only | Accepted | medium |
| [0028](0028-deputies-for-workflow-participants.md) | Deputies for workflow participants: time-limited deputyships resolved when tasks are read | Accepted | medium |
| [0029](0029-search-index.md) | Search index: one OpenSearch index per tenant, permission filter in the query, outbox plus periodic synchronization | Accepted (addendum DMS-101) | low |
| [0030](0030-periodic-review-and-recertification.md) | Periodic review: review date outside the frozen version, review tasks as rows, a daily check through the job queue | Accepted | medium |
| [0031](0031-legal-holds.md) | Legal holds: hold records per matter covering documents, a database guard against destruction, object legal hold on renditions | Accepted | medium |
| [0032](0032-access-groups-for-confidential-documents.md) | Access groups for confidential documents: managed per tenant in the platform, named in a core field | Accepted | medium |
| [0033](0033-version-comparison.md) | Version comparison computed on request from the structured version packages; the change reason is the author's change summary | Accepted | high |
| [0034](0034-evidence-reports.md) | Evidence reports: assembled on request, registered as canonical JSON with its SHA-256, PDF/A printed from the registered JSON | Accepted | medium |
| [0035](0035-stable-addresses-and-webhooks.md) | Stable addresses of the current version resolved per request; webhooks as signed deliveries through the outbox and the job queue (addendum DMS-107: admin UI, test event, secrets encrypted at rest, document number in payloads) | Accepted | high |
| [0036](0036-compliance-cockpit.md) | Compliance cockpit: a compliance officer role, figures computed on request, names only with a stated and audited purpose | Accepted | high |
| [0037](0037-user-directory.md) | User directory: display names from ZITADEL's user service, resolved by the backend per tenant and cached | Accepted | high |
| [0038](0038-ui-localization.md) | UI localization: German default with English, own typed message catalogs, Intl for formats | Accepted | high |
| [0039](0039-document-view-and-on-demand-pdfs.md) | Document view and on-demand PDFs: every readable version rendered as HTML, draft PDFs and copies with change history printed on request and never stored | Accepted | high |
| [0040](0040-number-ranges.md) | Number ranges: counter row locked in the creating transaction (gap-free on rollback), the document number as frozen core field and stable key | Accepted | medium |
| [0041](0041-document-attachments.md) | Document attachments: files linked to versions, content-addressed per document, streamed uploads with a scan port, frozen with the version | Proposed | medium |
| [0042](0042-file-documents-and-word-templates.md) | File documents and Word templates: uploaded originals per version, Word fields as content controls filled on the JVM, LibreOffice as separate conversion worker | Proposed | medium |
| [0043](0043-library-of-valid-documents.md) | Library of valid documents: served from PostgreSQL by a dedicated endpoint, filtered with the reading policy, facets computed per request | Proposed | high |
| [0044](0044-file-document-comparison-search-and-preview-cache.md) | File documents: comparison and search by the filled Word text, in-memory preview cache, content signature check, class list for designers | Proposed | high |
| [0046](0046-typed-word-fields-repeating-sections-and-office-placeholders.md) | Typed Word fields, repeating sections and placeholders in Excel and PowerPoint: field types from the content controls, rows as canonical JSON in the existing string values | Proposed | high |
