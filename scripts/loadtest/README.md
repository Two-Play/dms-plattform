# Load test

A repeatable load test with a reduced volume model of section 14.2 of the business concept (DMS-60, [ADR 0056](../../docs/adr/0056-production-hardening-and-library-snapshot.md)).

It never touches the development stack: `compose.yaml` here is a compose project of its own (`dms-loadtest`), with its own volumes and ports (PostgreSQL 15433, S3 19100, OpenSearch 19201, rendering 19280, conversion 19281), and the backend runs on 18290. It does not need ZITADEL: `loadtest.py` holds an RSA key, serves its public part on 18295 as JWKS and mints the tokens of the load users, so the backend verifies real signatures. All state stays in `.local/loadtest/`.

## Running it

Needs Docker, Python 3, `openssl`, Java 21 and the images of the workers (`docker compose build rendering conversion`).

```bash
sbt app/stage                                       # the build under test
L="python3 scripts/loadtest/loadtest.py"
$L up                                               # services and migrations
$L start                                            # key server and backend
$L seed --reindex                                   # demo content, the volume, the search index (5 minutes)
$L snapshot                                         # keep the seeded database

$L run --label my-run --duration 300                # results in scripts/loadtest/results/my-run.json
$L queries                                          # where the database spent its time in that run

$L stop && $L restore && $L start                   # the same data again, for the next run
$L down --volumes                                   # remove everything
```

`restore` resets the database only. The search index then still knows documents of earlier runs and search hits lead to 404: run `seed --reindex` after `start` to compare runs exactly.

## Volume model

Factor 1:100 (`--factor`) of the large-group example:

| | Business concept 14.2 | This test | Note |
|---|---:|---:|---|
| Tenants | 50 | 1 | All documents in one tenant: half of an average tenant's 40,000 documents. Row-level security is active |
| Users | 100,000 | 1,000 | Role assignments in the database, plus 50 writers and approvers |
| Concurrent sessions | 5,000 | 50 | 40 readers, 8 authors, 2 approvers. See below |
| Documents | 2,000,000 | 20,000 | 85 % with a valid last version, 10 % also with an open draft, 5 % never released |
| Versions | 10,000,000 | 98,000 | Five per document, with their workflow cases, tasks, votes and approvals |
| Contents | 50 TB | not reproduced | The copies share the renditions of the demo documents: object store volume is not tested |
| Acknowledgements | 100,000 in 48 h at the peak | 970,000 stored | 5 % of the documents ask for one; every reader has confirmed them except for 30 |

Each virtual user acts about once per second (`--think`), far more than a person does, so 50 of them stand for several hundred real sessions. A tenant of the large-group example has 100 concurrent sessions on average. The volume is written by SQL as copies of real published documents, with triggers off (`seed`); everything during a run goes through the API.

**The mix.** Readers: library (plain and filtered), document list, search, document with HTML view, PDF rendition, open acknowledgements, opening and confirming them. Authors: create, preview and save a draft three times, draft PDF, attachment upload, submit; create a Word document, upload the file, PDF preview. Approvers: inbox and vote with step-up. Every approval leads to a rendition and a publication that notifies all of about 1,100 readers; `--publication-interval` paces them (30 s by default, which is still thousands of times the peak of 14.2; 0 removes the limit).

The first 60 seconds are not measured (`--warm-up`): after a start the JVM compiles the hot paths and single requests take up to 8 seconds.

## Results

Measured on one machine with everything on it (16 cores, 62 GB; other local projects were running): load generator, backend with 3 GB heap and 10 database connections, and all services.

| Run | Build | Requests | Errors | Requests/s |
|---|---|---:|---:|---:|
| 01-baseline | main at 46347af, without V38 | 240 | 240 | 0.9 |
| 02-indexes-only | main at 46347af, with the indexes of V38 | 1,685 | 4 | 12.4 |
| 03-after | this branch, one publication per 30 s | 19,724 | 0 | 63.9 |
| 04-stress-publications | this branch, publications without limit | 11,713 | 0 | 61.6 |

Latencies in milliseconds:

| Operation | Baseline p95 | Indexes only p95 | After p50 | After p95 | After p99 | Stress p95 |
|---|---:|---:|---:|---:|---:|---:|
| Library | failed | 12,288 | 57 | 95 | 137 | 167 |
| Library, filtered | failed | 13,906 | 54 | 92 | 136 | 188 |
| Document list | failed | 11,332 | 27 | 131 | 198 | 183 |
| Search with facets | failed | 6,502 | 8 | 30 | 45 | 52 |
| Document view | – | 4,436 | 9 | 25 | 56 | 63 |
| Document content (HTML) | – | 5,502 | 10 | 27 | 51 | 68 |
| Live preview | – | – | 4 | 9 | 19 | 34 |
| Draft save | – | 7,525 | 21 | 53 | 95 | 143 |
| Submit | – | 6,049 | 54 | 136 | 183 | 292 |
| Approver inbox | failed | 11,387 | 450 | 780 | 982 | 41 |
| Approve | – | 6,726 | 58 | 80 | 80 | 212 |
| Open acknowledgements | failed | 7,401 | 21 | 100 | 140 | 257 |
| Acknowledge | – | 4,720 | 17 | 40 | 73 | 110 |
| Upload attachment | – | 7,381 | 37 | 83 | 140 | 173 |
| Upload Word | – | 8,063 | 27 | 68 | 123 | 174 |
| PDF rendition | – | 5,503 | 11 | 31 | 54 | 73 |
| Draft PDF (on demand) | – | – | 212 | 341 | 535 | 578 |
| PDF preview of a Word file (conversion) | – | 9,661 | 1,256 | 2,543 | 3,125 | 3,159 |
| Approval to published (asynchronous) | – | 120,975 | 6,350 | 8,617 | 8,617 | 46,038 |

The baseline did not fail slowly, it collapsed: ten statements held all ten connections for more than five minutes, so every other request failed after waiting 30 seconds, and only 240 requests were made. Baseline and indexes-only ran 120 s, the others 300 and 180 s after 60 s of warm-up. Nothing in `03-after` or `04-stress-publications` failed. `02-indexes-only` shows that indexes alone were not enough: versions stayed in approval, as renditions queued behind deliveries.

**What was wrong and what fixed it** (ADR 0056):

| Finding | Effect | Fix |
|---|---|---|
| No usable index on `document_id` of versions and workflow cases | Each document read scanned the tables, in correlated subqueries once per document. Library: over 3 minutes for one request | V38 |
| No end to slow statements, 30 s wait for a connection | A few slow requests took the whole application down, for minutes | Statement timeout 60 s, connection timeout 10 s, 503 |
| Document list: about 20 queries per document and version | 1,000 queries per list | Batched reads, a fixed number of queries |
| Open acknowledgements: one load per document | 48,000 document loads in 90 s | Batched reads |
| Document list sorted all documents of the tenant | 140 ms and growing with the tenant | Windows over the `updated_at` indexes |
| Library decoded and authorized 19,000 versions per request | 1.8 s of SQL per request under load | Copy per tenant, checked against a stamp; 95 ms p95 |
| One FIFO queue for everything | 73,000 deliveries before 223 renditions: nothing was published within 2 minutes | Own worker for bulk jobs; 8.6 s p95 |
| Reminder check per publication read all acknowledgements | About 1 million rows per publication | Checks the published version only; since DMS-137 the hourly check too goes version by version (see below) |
| Finding the open acknowledgements visited all valid versions | 30 ms per request | Partial index; 21 ms p50 including the loads |

**Limits seen:**

- Deliveries run at about 100 per second on one worker. One publication to 1,100 readers takes about 11 s to deliver, one to 100,000 readers about 17 minutes, which the 48 hours of 14.2 allow. In these runs a backlog remains, because the first reminder check also asks readers for the bulk-loaded versions (no request records exist for them).
- Renditions ran one at a time per instance in these runs. Without a limit on publications (0.7 per second, thousands of times the peak of 14.2) the time from approval to publication grows to 46 s at p95, while reading stays unaffected. Since DMS-137 they have a lane of their own, and `DMS_RENDERING_PARALLEL_JOBS` with worker replicas runs several at a time (see below). These runs have not been repeated since.
- The approver's inbox loaded one document per task: 450 ms p50 with 240 open tasks in the run above. It has read in batches since DMS-138 (ADR 0066); the load test has not been repeated since.
- A new library copy takes about 0.6 s at 19,000 versions, and more at full volume.

## What the numbers do not say

- One backend instance, no reverse proxy, no TLS, no virus scanner, no email (notifications stay in the application).
- Short runs of a few minutes, so slow leaks and table growth do not show.
- Full volume is 100 times larger. What grows with the tenant is the library copy and everything listed under follow-ups in ADR 0056. A test at full volume needs a production-like environment.

## Renditions and the reminder check at full volume (DMS-137)

Measured on 2026-11-06 on the machine above, with other agents' builds and test suites running: load average 7 to 17 on 16 cores. Treat the lows as what a free core does and the p95 as what a busy machine does; a p95 for production needs a repeat on free hardware. Details and decisions: [ADR 0079](../../docs/adr/0079-background-jobs-at-full-volume-and-rendering-queue-metric.md).

**Time of a render** with `render_bench.py`, a worker built from `workers/rendering` with the memory limit of compose (512 MB). 30 renders each, one after the other, in seconds. Target of the business concept (chapter 13): up to 50 pages, p95 at most 10 s.

| Document | Pages | Min | p50 | p95 | Max |
|---|---:|---:|---:|---:|---:|
| Contract (clauses, tables, annexes) | 12 | 2.00 | 2.43 | 3.29 | 3.53 |
| | 34 | 3.22 | 4.06 | 6.08 | 6.10 |
| | 56 | 5.86 | 7.37 | 13.13 | 13.71 |
| | 56, second series | 5.06 | 6.17 | 11.42 | 12.53 |
| Policy (mostly text) | 13 | 0.85 | 1.13 | 1.43 | 1.45 |
| | 26 | 1.76 | 2.54 | 3.38 | 3.67 |
| | 51 | 3.47 | 4.31 | 6.46 | 6.88 |
| One table across all pages | 12 | 1.38 | 1.62 | 2.06 | 2.11 |
| | 25 | 2.81 | 3.27 | 4.73 | 5.48 |
| | 50 | 7.05 | 9.43 | 13.75 | 13.89 |

veraPDF adds about 1 s for 56 pages (p50 0.9 to 1.1, p95 1.2 to 1.5). A render is pure CPU on one core: the container used 230 s of CPU for about 33 renders. So one worker prints 56 pages in about 5 s on a free core and 11 to 13 s at p95 on a busy machine. Peak memory was 369 MiB at 56 pages. A contract of 210 pages took 24 s, within the 60 s of `DMS_RENDERING_TIMEOUT_SECONDS`; one of about 500 pages ran for more than ten minutes at the memory limit, and then the kernel killed the worker.

The target holds for one document at a time on a free core: 5 to 7 s plus validation, and 7.04 s at p95 in the last row of the next table, measured while the load was lower. It holds with little room for one table across 50 pages (at least 7 s plus validation), and it was missed on this machine whenever the machine was busy. It does not hold when two long documents meet at one worker.

**Several at a time**, the 56-page contract, 4 parallel requests unless noted:

| Set-up | Min | p50 | p95 | Renditions per minute |
|---|---:|---:|---:|---:|
| One worker, one request at a time | 5.06 | 6.17 | 11.42 | 8.6 |
| One worker, 2 requests | 6.28 | 12.00 | 12.95 | 10.2 |
| One worker, 4 requests | 5.20 | 21.98 | 24.59 | 10.8 |
| Four replicas, 4 requests | 5.48 | 6.52 | 7.04 | 36.8 |

**Reminder check**, measured once with a temporary test against PostgreSQL 17 in a container (default settings), through the real job runner. Volume: a tenant of the size 14.2 gives on average, 2,000 valid versions that ask for acknowledgement, 2,000 readers each, 3.96 million acknowledgements, 4 million request records, 1 % open.

| What | Time | Longest transaction |
|---|---:|---:|
| Before: the reads of the former check alone, in one transaction | 9.0 s | 9.0 s, then the writes |
| First run, 11 jobs, 40,000 reminders due | 90.6 s | 0.40 s |
| Next hour, 11 jobs, nothing due | 57.8 s | 0.05 s |
| One version, 100,000 more readers: everyone asked | 4.3 s | 3.7 s |
| The same version: everyone reminded | 4.7 s | 4.3 s |
| The same version: nothing due | 2.2 s | 1.9 s |

That is about 30 ms per version under this load. The longest transaction is the largest sample of `pg_stat_activity` every 20 ms.
