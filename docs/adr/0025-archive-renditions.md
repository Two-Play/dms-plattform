# 0025: Archive renditions: WeasyPrint as HTTP sidecar, PDF/A-2b checked in-process with veraPDF, rendered between the last vote and the approval

| Field | Value |
|---|---|
| Status | Accepted (2026-10-28, implemented in DMS-44) |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

US-06.1 (business concept 5.1, EP-06): every approved version gets a rendition in an archivable PDF format with a checksum, so that the wording can be proven unchanged later. The rendition must pass validation against the chosen archive standard; document ID, version, valid-from date and approval note must be visible on every page; if rendering fails, the version stays "In approval" (`InApproval`) and the owner is notified ("on error no status change").

Starting point:

- ADR 0008 (spike DMS-23) chose WeasyPrint for HTML → PDF/A and veraPDF for validation, planned as a separate Python worker that claims jobs from the job table itself. The spike proved valid PDF/A-2b/3b and byte-identical output when every date comes from the job.
- ADR 0010: job queue and outbox in PostgreSQL; `JobRunner` (module `integration`) claims jobs with a lease and retries with exponential backoff until the job is dead.
- ADR 0016: renditions are stored in S3 object storage with Object Lock (SeaweedFS locally and in tests).
- ADR 0021: the database requires a rendition with SHA-256 for `Approved`; `DocumentService.attachRendition` records it while the version is open; from approval on it is frozen.
- ADR 0024: the last approving vote approves the version directly, which fails without a rendition (tests attached a fake one).
- `HtmlRenderer.renderDocument` (module `templates`) already produces a stand-alone HTML page with the print stylesheet `document.css` (`@page`, `@media print`).
- Module rule (ADR 0001): `rendering-core` may use only the `*-api` of other modules; all database access goes through `Db.transact`, tenant tables are protected by RLS (ADR 0014). GitHub CI runs the sbt build on `ubuntu-latest` with Docker available (Testcontainers).

Three questions had to be answered: how the renderer runs, which archive standard, and when in the approval flow the rendition is produced.

## Decision

1. **Renderer: WeasyPrint as a stateless HTTP sidecar; the JVM orchestrates the job.** The worker (`workers/rendering`, image pinned as in the spike: base image by digest, Debian packages by version, Python packages by hash) offers `POST /render`: complete HTML page in, PDF bytes out. It never touches the database or object storage and fetches nothing over the network (any external reference fails the render with 422). The rendering job runs in the application's `JobRunner` (job kind `document.render-rendition`), so transactions, RLS, audit and outbox stay in one place. This deviates from ADR 0008 (worker claims jobs itself); see the addendum there.
2. **Archive standard: PDF/A-2b** (ISO 19005-2, conformance level B: visual reproducibility, embedded fonts, no external content). `ArchiveStandard` in `rendering-api` names it once for the worker (`pdf/a-2b`) and the validator (`2b`).
3. **Validation in-process with veraPDF 1.30.2** (Java library `validation-model-jakarta`, the same release as the CLI of the spike). Every PDF is validated before it is stored; a non-compliant PDF fails the job.
4. **Flow.** The last approving vote checks the content checksum as before, enqueues the rendering job (idempotency key `rendition:<versionId>`) through the workflow port `ApprovalRendition` and moves the case to the new status `Rendering`; the version stays `InApproval` (all in the vote's transaction). The job then:
   1. loads the version and builds the page: content resolved with the version's field values, `HtmlRenderer.renderDocument`, plus page rules and metadata from `RenditionLayout` (own transaction, read only);
   2. prints it with the worker, validates it with veraPDF;
   3. stores it in object storage under `renditions/<tenant>/<document>/<version>.pdf` with server-confirmed SHA-256 (an existing object version with the same SHA-256 is reused);
   4. in one transaction: `DocumentService.attachRendition`, `ApprovalWorkflow.completeApproval` (approval record, `Approved`, outbox `workflow.approved`, audit) and the audit entry `document.rendition-created` with renderer versions, page count and validation result.

   Nothing changes the version before step 4, so any failure leaves it `InApproval`. Failures are retried by the job queue (10 attempts, 10 s doubling up to 1 h). Each failed attempt writes the audit entry `document.rendition-failed`; the outbox event `document.rendition-failed` goes to the owner (without owner: the author) on the first failed attempt and again when no retry follows (`final: true`).
5. **Running header and footer.** `RenditionLayout` (pure, `rendering-core`) adds `@page` margin boxes: title (top left), "Document ID … · Version …" (top right), "Valid from …" and the approval note "Approved on <date> <time> UTC by <approver>" (bottom left), "Page x of y" (bottom right); labels and date format in the document language (German or English).
6. **Determinism (ADR 0008).** Creation and modification dates are the time of the last approving vote at second precision; the PDF file identifier is the first 32 hex characters of SHA-256(tenant, document, version); custom XMP properties `dms:documentId`, `dms:version`, `dms:validFrom`, `dms:renderer` are declared in a PDF/A extension schema and merged into WeasyPrint's single `rdf:RDF`.
7. **Download.** `GET /api/v1/documents/{id}/versions/{versionId}/rendition` requires `ReadDocument` on the version (same rule as reading it), reads the object version, checks its SHA-256 and returns `application/pdf` with `ETag` and `Repr-Digest` (RFC 9530).
8. **Tests.** The worker image is built from `workers/rendering/Dockerfile` by Testcontainers (`ImageFromDockerfile`); veraPDF and PDFBox run in the test JVM. The same works on GitHub's `ubuntu-latest` runners, so CI needs no extra job or registry.

## Alternatives considered

| Criterion (how the renderer runs) | Chosen: WeasyPrint HTTP sidecar, JVM runs the job | Python worker claims jobs from the job table (ADR 0008 as planned) | openhtmltopdf (PDFBox) in the JVM |
|---|---|---|---|
| PDF/A output | Proven by spike DMS-23 (2b, 3b, tagged 2a/3a + UA-1) | Same engine | PDF/A-1/2/3 a/b/u supported, not yet tested with our documents |
| Print CSS of `document.css` (`@page` margin boxes, page counters, `break-*`, CSS variables) | Very good | Very good | CSS 2.1 plus parts of paged media; no CSS variables, `@container`, `min()`; stylesheet would need a print variant |
| Determinism | Proven byte-identical across containers and rebuilds | Same | Unproven; PDFBox writes dates and IDs, controllable but needs its own spike |
| Where transactions, RLS, audit, outbox, lifecycle live | JVM only, as everywhere else | Python needs database access, RLS context, the job contract and an outbox write; or a callback API | JVM only |
| Second runtime | Yes (Python container), but without business logic | Yes, with business logic and a database driver | No |
| Operations | One more stateless container; scales by replicas | One more container with database credentials | None; but fonts, ICC profile and memory of large renders inside the API process |
| Failure isolation | A crash or memory blow-up hits the worker only | Same | Hits the application process |
| CI testability | Testcontainers builds the image (≈ 1–2 min uncached) | Same plus a database contract test | Fastest (no container) |
| Fit to ADR 0008 | Engine and validator kept; job orchestration moves to the JVM (addendum) | As decided | Would supersede 0008 |

| Criterion (archive standard) | Chosen: PDF/A-2b | PDF/A-1b | PDF/A-3b | PDF/A-2a/3a + PDF/UA-1 |
|---|---|---|---|---|
| Fits our output (transparency, OpenType, JPEG2000) | Yes | No transparency, PDF 1.4 only | Yes | Yes |
| Embedded files | Only PDF/A | No | Any (e.g. the structured source) | Depends on part |
| Validated in the spike | Yes | No | Yes | Machine checks only; needs manual Matterhorn review |
| Archive acceptance | Widely required/accepted | Accepted, oldest | Accepted, some archives reject arbitrary attachments | Accessibility on top |

| Criterion (when to render) | Chosen: after the last vote, before approval (case `Rendering`) | Before the last vote (on entering the approval step) | After approval |
|---|---|---|---|
| "On error no status change" | Kept: the version stays `InApproval` | Kept | Violated; also the database forbids `Approved` without rendition (ADR 0021) |
| What the approval note shows | The actual approver and time | Unknown at render time | Actual |
| Wasted renders | None | One per approval round, also for rejected versions | None |
| Change to `ApprovalWorkflow` | New status `Rendering`, approval split into request + `completeApproval` | Hook on entering the approval step | None, but impossible (see first row) |

| Criterion (validation) | Chosen: veraPDF library in the JVM | veraPDF CLI container | veraPDF inside the Python worker |
|---|---|---|---|
| Extra process / image | No | Yes (JVM start ≈ 1–2 s per call) | JRE in the Python image |
| Validator independent of the renderer | Yes | Yes | No (same container) |
| Classpath cost | veraPDF, Saxon, Rhino, JAXB in `rendering-core` | None | None |

## Consequences

### Positive

- Every approved version has a validated PDF/A-2b with SHA-256 in WORM storage before it is `Approved`; the database guard of ADR 0021 now holds without test fakes.
- A failed rendering never leaves a half-approved version: the approval completes in one transaction with attaching the rendition.
- All business logic stays in the JVM with the existing transaction, RLS, audit and outbox mechanisms; the Python worker is a pure function that can run without credentials and without outbound network.
- The renderer is behind the port `PdfRenderer`; switching engines (e.g. openhtmltopdf) does not touch the job or the workflow.
- Header, footer and metadata are pure Scala and unit-tested; PDF/A validity, the text on every page and byte-identical re-renders are tested against the real worker image in CI.

### Negative / Cost

- Approval becomes asynchronous: after the last vote the case is `Rendering` and the version `InApproval` until the job ran (seconds normally; up to about 1.5 h of retries if the worker is down). Clients must poll or show the state.
- A case whose job is dead stays `Rendering`; there is no retry action yet (follow-up).
- The application now needs the worker at runtime (`dms.rendering.url`); without it approvals stall (they do not fail).
- veraPDF brings a sizable set of dependencies into the application (Saxon, Rhino, JAXB, Jackson).
- The PDF shows the time of the last vote; `released_at` of the version is the time the job completed the approval (seconds later). Both are recorded; they differ slightly.
- The approval note names the approver by user id until a user directory with display names exists.
- CI builds the worker image on every run (no layer cache on fresh runners); Debian package pins break when Debian replaces them (inherited from ADR 0008).

## Follow-ups

- Must be done: retry for dead rendering jobs (tenant administrator action and endpoint) and a visible "rendering failed" state for the owner; the notifications module delivers `document.rendition-failed`.
- Must be done: show the approver's display name in the approval note (user directory or name claim stored with the vote).
- Must be done: record the worker image digest with each rendition (audit entry) and publish the worker image by digest; golden-checksum regression test on image or layout changes (ADR 0008 follow-up).
- Must be done: retention / legal hold on rendition objects from the retention rule (Object Lock, ADR 0016).
- Must be done: deployment of the worker beyond local compose (replicas, memory limit, no outbound network).
- Later: use the vote time as `released_at` (needs a `DocumentService` change, coordinate with the lifecycle work of DMS-45/46); watermark by confidentiality (F-06.2); PDF/A-3b with the structured source embedded; PDF/A-2a + PDF/UA-1 after a manual accessibility review; regenerate the frontend API types for the new endpoint and case status.
- Ruled out: approving without a rendition; storing renditions that fail validation.

## Reversibility

**medium**: The renderer is behind `PdfRenderer` and the validator behind `PdfValidator`, so another engine or an external validator is a new implementation plus tests. Moving the job loop into the Python worker (ADR 0008 as planned) would need a database contract for the job and the completion step. Rendering before the last vote instead of after it changes `ApprovalWorkflow` and requires handling cases left in `Rendering`. Renditions already archived stay valid.
