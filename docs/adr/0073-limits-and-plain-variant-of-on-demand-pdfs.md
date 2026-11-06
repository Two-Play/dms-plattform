# 0073: PDFs printed on demand: rates per user and tenant shared in the database, in-flight limits per instance, 429 with `Retry-After`; drafts as plain PDFs, copies unchanged

| Field | Value |
|---|---|
| Status | Proposed |
| Date | 2026-11-06 |
| Decided by | Philippe |

Changes decision 2 of [ADR 0039](0039-document-view-and-on-demand-pdfs.md) (format of the draft PDF) and takes up its two follow-ups. Everything else of ADR 0039 stands.

## Context

`draft.pdf` and `copy.pdf` (ADR 0039) call the rendering worker on every request. The worker prints one page at a time per container; further requests wait for its lock, in no fair order, next to the archive renditions of the job queue (ADR 0025). The application runs as several identical instances behind a load balancer (ADR 0044) that share the worker. Nothing stopped one caller, or one tenant, from filling the worker's time. A render whose caller gave up after `dms.rendering.timeoutSeconds` still runs to its end.

Draft PDFs also carried a PDF/A-2b identification without being validated or archived (ADR 0039, negative consequence). For file documents the same holds for the converted original, with the additional flaw that the stamp breaks the PDF/A the file still claims to be.

Requirements (DMS-118): a limit for on-demand PDFs per tenant or user so that one caller cannot starve others; correct with several instances, or plainly per instance; a refused request answers 429 with `Retry-After` and the UI says so in German and English; configurable with sensible defaults; a plain PDF variant for drafts; published and archive renditions stay PDF/A and unaffected. Tenant quotas in general are a separate issue (DMS-136) and not decided here.

## Decision

1. **Two rates, shared by all instances.** A token bucket per user (`perUserPerMinute`, default 10) and one per tenant (`perTenantPerMinute`, default 30) in `dms.on_demand_pdf_allowance` (V50, row-level security). A request takes one token from both in one short transaction of its own, before printing and never around it: `insert … on conflict do update … where level >= 1`, so parallel requests on any instance cannot take more than there is, and a refusal by the tenant's bucket rolls the user's token back. The rate is also the size of a burst. The level follows from the time passed, computed by `dms.on_demand_pdf_tokens` on the database clock: no refilling job, no clock differences between instances. `draft.pdf` and `copy.pdf` draw on the same buckets.
2. **Two in-flight limits, per instance, in memory.** A rate does not bound what waits at the worker, and a few long documents can fill it within the rate. At most `inFlightPerTenant` (default 2) PDFs of a tenant and `inFlightPerInstance` (default 4) PDFs of all tenants are in progress **per application instance**; with n instances the worker can get n times as many. So a tenant's share of the queue at the worker stays small and another tenant waits for a few renders, not for all of them. The slot ends with the request, also when it is interrupted or the instance dies, which is why it is not in the database.
3. **Where the check sits.** After the checks of the request (authentication, `ReadDocument`, the status of the version), before any printing. So 403, 404 and 409 stay what they were and spend nothing, a 429 tells nobody anything about a document they may not read, and the in-flight check comes first so that a request refused for that reason keeps its token. A printed PDF counts whatever becomes of it, also when the worker fails: it may have done the work.
4. **Answer.** `429` with `Retry-After` in seconds and `ApiError` code `pdf_limit_reached`. For a rate the header says when the next token is there (at least 1), for an in-flight limit it is `busyRetrySeconds` (default 5). One code for all four limits: the caller's action is the same, and the message names the limit for API clients. The UI shows `docs.pdf.limited` (de, en) with the seconds and does not retry by itself. Refusals are logged with the tenant and the limit; they are not audited, since nothing was handed out.
5. **Configuration.** `dms.onDemandPdf` with an environment variable each (`DMS_ON_DEMAND_PDF_*`); 0 turns a limit off. The limits are the same for every tenant. The load test turns them off, since it measures the worker.
6. **Plain variant.** The worker accepts `pdfVariant: "plain"`: WeasyPrint without a PDF variant and without our XMP packet, so no PDF/A identification, no output intent, no custom metadata; the file identifier and the dates stay deterministic. `draft.pdf` prints with it (`PdfVariant.Plain`), for file documents including the appendices. The stamp of a file draft drops the XMP packet and the output intents of the converted original (`PdfComposer.stamp(plain = true)`); the preview cache keeps the original conversion.
7. **What stays.** Archive renditions and evidence reports go through `PdfVariant.Archive` and the validation as before; `PdfJob` holds a `PdfVariant` and `RenditionJobs` can only form the archive case. They do not count for any limit. `WORKER_VERSION` is **not** bumped: the bytes of every PDF/A stay the same, and the version is part of them. `copy.pdf` keeps the PDF/A-2b variant of ADR 0039: DMS-118 asks for a plain variant for drafts, and leaving the copy alone is the smaller change.

Deployment: the worker image must be rolled out before or with the application, since an older worker answers 400 to `plain` and draft PDFs then fail with 422 (`rendering_failed`). Archive renditions work with either.

Defaults were chosen without measurements from production: a person rarely downloads ten PDFs a minute, and 30 per tenant and minute leaves a single worker time for archive renditions at about a second per render. They need checking against real use.

## Alternatives considered

| Criterion (where the limit lives) | Chosen: rates in PostgreSQL, in-flight in memory | Everything in memory per instance | Everything in PostgreSQL | Redis or the load balancer |
|---|---|---|---|---|
| Holds with several instances | Rates yes; in-flight per instance | No: n instances allow n times the rate | Yes | Yes |
| Crash of an instance | Nothing left behind | Nothing left behind | In-flight rows need leases and expiry | Depends on the expiry |
| Cost per request | One short transaction, next to the audit entry and against a render of seconds | None | Two more transactions, one after printing | One round trip |
| New infrastructure, migration | V50 | None | V50 | A new service, or rules that do not know users and tenants |
| Knows the tenant and the checks of the request | Yes | Yes | Yes | No: the balancer sees neither, and would also count 403 and 409 |

| Criterion (what is limited) | Chosen: rate and in-flight | Rate only | In-flight only | Size of the document |
|---|---|---|---|---|
| Bounds what waits at the worker | Yes | No: long documents fill it within the rate | Yes | No |
| Bounds what a user can do over time | Yes | Yes | No: one after the other without end | No |
| Protects against one very long document | No (timeout only, see follow-ups) | No | No | Yes, but the size is known only after resolving, and pages only after printing |
| Knobs | Four | Two | Two | One, with no obvious value |

| Criterion (kind of rate) | Chosen: token bucket | Fixed window per minute | Sliding log |
|---|---|---|---|
| Burst at the edge of a window | At most the bucket | Up to twice the limit | None |
| `Retry-After` | Exact and short (6 s at 10 per minute) | Up to a whole window | Exact |
| Rows | One per user and tenant, no clean-up | One per subject, or clean-up of old windows | One per request, clean-up needed |

| Criterion (over the limit) | Chosen: refuse with 429 | Queue in the application | Serve an older print from a cache |
|---|---|---|---|
| Caller knows at once | Yes, with the time | Waits, runs into timeouts | Yes |
| Memory and threads under load | Bounded | Grow with the queue | Bounded |
| Fairness between tenants | By the limits | Needs a fair queue | By the limits |
| Fits ADR 0039 | Yes | Yes | No: draft PDFs are not stored, and every download has its checksum audited |

| Criterion (variant of `copy.pdf`) | Chosen: PDF/A-2b as before | Plain like drafts |
|---|---|---|
| Asked for by DMS-118 | Not asked for | Not asked for |
| Identifies itself as PDF/A without validation | Yes, as under ADR 0039 | No |
| Change for readers of copies | None | Metadata of the copies change |

| Criterion (what a plain PDF carries) | Chosen: no XMP at all | XMP without the PDF/A parts |
|---|---|---|
| Risk of being read as archive copy | None | None |
| Document id in the metadata | No; it is in the header of every page, and the audit entry has the checksum | Yes |
| Code | One condition | A second packet, and WeasyPrint's `xmp_metadata` writes a second `rdf:RDF` (see `merge_xmp` in the worker), so another wrapper |

## Consequences

### Positive

- One caller or one tenant cannot fill the worker: the rates hold over all instances, and the queue at the worker is bounded for every tenant.
- A refused caller learns at once when to come back, and the UI says so in both languages.
- A draft can no longer be taken for PDF/A by a tool: neither the file nor its metadata claims it, for structured and for file documents.
- PDF/A output, validation and the archive path are untouched, down to the bytes.

### Negative / Cost

- The in-flight limits are per instance: with n instances the worker can get n times as many. They must be sized for the number of instances (`docs/operations/production.md`), and a scale-out raises them silently.
- `inFlightPerInstance` can refuse a tenant that has used nothing, while two others hold all slots. The slots are short and the others are rate-limited, so the wait is bounded, but it is not a fair share.
- A request that was printed counts even if the worker failed, so a failing worker uses up the rate of users who keep trying.
- A table with one row per user who ever printed a PDF on demand. A row is worthless after a minute or so; nothing removes it.
- A hot row per tenant: requests of a tenant serialize for the length of a small transaction. Harmless at these rates.
- A refused request has already cost the read of the document and the checks. The limits protect the worker, not the database.
- The worker and the application must be rolled out in order (see Deployment).
- `copy.pdf`, also the stamped copy of a file document, still claims PDF/A without being validated, as under ADR 0039.

## Follow-ups

- Must be done: check the defaults against production use and the load test (with the limits on), together with DMS-137 (rendition throughput).
- Possible: limits per tenant, as part of tenant quotas (DMS-136). The buckets are per tenant already; only the capacity would have to come from the tenant.
- Possible: the same limits for other on-demand work on the workers: `document.docx`, `preview.pdf`, evidence reports. Not asked for here; they call a worker per request too (the conversion worker for the first two).
- Possible: a worker that gives up a render when its caller is gone, or a time limit per render. Today a timed-out render keeps the lock, and a very long document blocks the worker for its whole length.
- Possible: a plain variant for `copy.pdf`, after asking whether anyone relies on the identification.
- Possible: a separate worker pool for on-demand PDFs, so that they cannot delay archive renditions at all.
- Possible: remove rows of `dms.on_demand_pdf_allowance` in the retention maintenance (ADR 0054), if the table ever matters.
- Ruled out: storing draft PDFs to serve repeated requests (ADR 0039); retrying automatically in the UI.

## Reversibility

**high**: The limits are one service behind one call, and 0 turns each off without a release. Dropping them leaves a table without dependents; its rows mean nothing beyond a minute. Moving the in-flight limits to the database or the rates elsewhere changes `OnDemandPdfLimits` only. Going back to PDF/A drafts is one line in `VersionViewApi` and needs no data change, since draft PDFs are not stored.
