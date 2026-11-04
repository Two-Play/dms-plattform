# 0056: Production hardening: what the production profile refuses, overload answered with 503, a tenant copy of the valid versions for the library and a separate worker for bulk jobs

| Field | Value |
|---|---|
| Status | Proposed |
| Date | 2026-11-04 |
| Decided by | Philippe |

## Context

DMS-60 (T-00.13) asks for a load test with the volumes of section 14.2 of the business concept (reduced), the fixing of what it finds, and a hardened production setup.

The load test at 1:100 (20,000 documents, 98,000 versions, 970,000 acknowledgements, 50 sessions; `scripts/loadtest/README.md`) brought the application down completely: every request failed.

1. **Missing indexes.** All indexes of `dms.document_version` on `document_id` are partial, so `where document_id = ?` scanned the table, once per document in correlated subqueries. Ten such statements took all ten connections for more than five minutes, and nothing ended them.
2. **Queries per row.** The document list ran about 20 queries per document and version. `GET /acknowledgements?status=open` loaded every document on its own, 48,000 loads in 90 seconds.
3. **The library** reads, decodes and authorizes all valid versions of the tenant for every request (ADR 0043). Facets and filters need all of them, and reading permissions are decided by the policy in the application (ADR 0018), so paging in SQL is not possible without duplicating the policy.
4. **The job queue** is one FIFO line with one worker. A publication creates a delivery per reader: 73,000 queued deliveries stood before 223 renditions, so versions stayed in approval for minutes. Section 14.2 asks for prioritized queues.
5. **Failures showed internals:** the driver's message reached clients, and a request waited 30 seconds for a connection before it failed with 500.

The profile `production` (DMS-117) so far only refused uploads without a virus scanner, and DMS-60 asks to decide for the other development settings. There were no readiness check, no graceful shutdown, no security headers and no structured logs.

## Decision

**1. The production profile** refuses what would silently break a promise of the platform, and warns about what an installation may leave out on purpose. All problems are reported at once, by the name of the setting, never with its value.

| Setting | In production | Why |
|---|---|---|
| Webhook development key (ADR 0035) | refused | Publicly known, it protects nothing |
| `dms.webhooks.allowedHosts` set | refused | Switches off the SSRF protection; `allowPrivateNetworks` is the production option |
| Issuer not https, no OIDC client id | refused | Keys would come over plain text; approvals could not prove a recent sign-in |
| Development passwords and keys of database and object store | refused | Known values |
| Object Lock off | refused | Immutability of approved versions is a promise (ADR 0016) |
| No trusted TSA certificates, or the development TSA (ADR 0017) | refused, unless `allowUnanchoredInProduction` | As with the virus scanner: anchoring is required (S-12.a), switching it off must be a visible decision |
| Virus scanner `none` (ADR 0041) | refused, unless `allowUnscannedInProduction` | Unchanged |
| OpenSearch without https or credentials | refused | The index holds the text of confidential documents |
| SMTP `security = none`; with email on, an application address that is not https or local | refused | Plain text mail; links that lead nowhere |
| SMTP not set | warned | In-app notifications work; an installation may want only those |
| Anchoring off deliberately | warned | |
| Partner directory `demo` | warned | Stage 1 has no partner system (business concept 12.1), but fictitious partners must not reach real documents |
| No user directory token | warned | The application works, with ids instead of names |
| Search replicas 0, object store without https | warned | Legitimate for a single node or inside a protected network |

**2. Overload ends quickly and tells clients to retry.** A statement runs at most `dms.database.statementTimeoutSeconds` (60), a transaction waits at most `connectionTimeoutSeconds` (10) for a connection. Both become `DbError.Unavailable` and `503 unavailable`. Other database failures answer with a reference; the details are only logged, and the driver leaves out the DETAIL line that quotes row values (`logServerErrorDetail=false`).

**3. Operations.** `GET /ready` next to `GET /health`: database and object store are required (503 `unavailable`), search, rendering, conversion and the virus scanner optional (200 `degraded`). On SIGTERM it answers `draining` for `dms.http.drainSeconds`, then requests in progress get `shutdownGraceSeconds`. Security headers on every response unless the response sets its own. `DMS_LOG_APPENDER=JSON` writes JSON with logback's own encoder. Request size limits stay as they are (1 MiB aggregated, uploads streamed with their own limit, DMS-114). The checklist, backup and restore are in `docs/operations/production.md`.

**4. Reads are batched.** The list and the acknowledgements read versions, attachments, files, profiles and workflow cases for all their documents with a fixed number of queries (`DocumentStore.versionsOf`, `ApprovalWorkflow.casesOf`). `recentDocuments` reads the latest changes from the `updated_at` indexes of documents and versions, in a window that widens until the result is provably complete, instead of sorting all documents of the tenant. Flyway V38 adds the missing indexes.

**5. The library works on a copy of the valid versions per tenant** (`LibrarySnapshots`), which holds what is the same for all readers: item, authorization resource, sorted by title.

- Permissions, acknowledgements and legal holds are decided per request on top of the copy, so a change of rights takes effect at once.
- A copy is used while the tenant's *validity stamp* is unchanged: the latest `status_changed_at` of the versions that are or were valid, one lookup in a partial index. A version that becomes or stops being valid therefore shows up at once, on every instance.
- One request reads the new copy and gets it; requests arriving during that second still get the previous one.
- `cacheMaxAgeSeconds` (30) bounds what the stamp cannot see: an owner change, and a transaction that commits later than a newer status change.
- `cacheMaxVersions` (200,000 over all tenants, about 2 KB each) bounds the memory; 0 turns the copy off.

**6. Bulk jobs have their own worker.** `notifications.deliver` and `webhook.deliver` are claimed by a second `JobRunner`, using the kinds `job_claim` already filters by, with an index on `(kind, run_at, id)`. The check after a publication looks at that version only instead of reading every acknowledgement of the tenant.

Flyway V38. No new dependency.

## Alternatives considered

| Criterion (library) | Chosen: copy per tenant, checked against a stamp | Page and filter in SQL | Library from the search index | Short time-to-live cache |
|---|---|---|---|---|
| Permissions | Policy in the application, unchanged | Policy duplicated in SQL (as for search, ADR 0029, but including facets) | Filter of ADR 0029 | Policy unchanged |
| A new version shows up | At once | At once | After indexing | After the time to live |
| Memory | About 2 KB per valid version, bounded | None | None | As the copy |
| Cost per request | Filter and facets in memory | Query per facet | One query | As the copy |
| Effort and risk | Small, one class | Large, a second implementation of the rules | Medium, the library then depends on OpenSearch | Smallest |

| Criterion (bulk jobs) | Chosen: separate worker by kind | Priority column in the queue | Parallel workers |
|---|---|---|---|
| Rendition behind 70,000 deliveries | Not affected | Not affected | Still behind them, only sooner |
| Deliveries while other jobs run | Go on | Wait (starve under load) | Go on |
| Schema change | One index | Column, function, every enqueue | None |
| Scales further | Separate instances per lane possible | Only in order | Yes |

| Criterion (document list) | Chosen: windows over `updated_at` | Sort by a subquery with the index | Column on the document kept by a trigger |
|---|---|---|---|
| Cost | Constant | Linear with the documents of the tenant (140 ms at 20,000) | Constant |
| Locks | None | None | Every version update also locks the document: deadlock risk with `lockDocument` |
| Code | About 30 lines, a proof in the comment | One query | Trigger and backfill, which re-indexes everything |

| Criterion (production settings) | Chosen: refuse the dangerous, warn about the optional | Refuse everything | Warn about everything |
|---|---|---|---|
| A forgotten setting | Start fails with its name | Start fails | Goes unnoticed in the log |
| Installation without SMTP or partner system | Possible | Impossible | Possible |
| Consistent with DMS-117 | Yes, with explicit opt-outs where a requirement is waived | Partly | No |

## Consequences

### Positive

- At the reduced volume the system went from complete failure to answering everything within its targets, with a load of half an average tenant of the large-group example (`scripts/loadtest/README.md`).
- A forgotten production setting stops the start with its name instead of surfacing in an audit.
- Overload is bounded and visible: 503 within seconds, instead of a pool that stays blocked.
- A mass notification can no longer delay publications.

### Negative / Cost

- The library holds data in memory per instance, and a copy is read anew after every publication (about one second at 19,000 versions, on one request per tenant and instance).
- For about a second after a publication, requests can still see the previous state, and in rare interleavings up to `cacheMaxAgeSeconds`. The document itself always shows the current state.
- `statementTimeoutSeconds` also applies to background jobs; a legitimately long statement must be split or get its own connection settings.
- The refusals make the first production start longer: everything on the checklist has to be there, or be waived in writing.
- `recentDocuments` is more code than one query.

## Follow-ups

- Must be done: tenant quotas and throttling (section 14.2): queue fairness between tenants and rate limits are missing; the bulk worker is shared by all tenants (DMS-136).
- Must be done: the hourly reminder check still reads all acknowledgements of valid versions of a tenant, in one transaction. At full volume it needs paging or a query per version (DMS-137).
- Must be done: rendition throughput is one job at a time per instance; measure with production documents and allow several in parallel if needed (DMS-137).
- Should: the approver's inbox loads one document per task (450 ms at 240 open tasks); batch it like the document list (DMS-138).
- Must be done: expose the depth of the rendering queue as a metric (chapter 13, DMS-137).
- Should: V38 creates its indexes without `concurrently`, which is right on an empty production; on a database with data, use a non-transactional migration (DMS-138).
- Should: per-tenant limits on on-demand PDFs (DMS-118) if the workers become a bottleneck.
- Ruled out: caching per user; authorization stays per request.

## Reversibility

**high**: The copy can be switched off with `cacheMaxVersions = 0`, and the job lanes merged by one line in `Background`. Indexes can be dropped. The refusals are a single function; tightening or loosening one is a line and a test. Clients rely on 503 and `/ready` once load balancers are configured.
