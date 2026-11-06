# 0079: The reminder check runs as a chain of pages with one transaction per version; renditions and reminder checks get lanes of their own, renditions with a configurable number of jobs; the rendering queue is published at `GET /metrics`

| Field | Value |
|---|---|
| Status | Accepted (2026-11-06) |
| Date | 2026-11-06 |
| Decided by | Philippe |

Takes up three follow-ups of [ADR 0056](0056-production-hardening-and-library-snapshot.md) and the check of defaults asked for by [ADR 0073](0073-limits-and-plain-variant-of-on-demand-pdfs.md). Follows [ADR 0010](0010-outbox-und-jobs-in-postgres.md) (job queue) and [ADR 0048](0048-acknowledgement-recipients-overview-and-reminders.md) (reminders).

## Context

The load test of DMS-60 ran at 1:100 and left three points (DMS-137):

1. **The hourly reminder check.** It read all valid versions (with their field values), all audiences, all acknowledgements and all requests of a tenant, then wrote, all in one transaction. Besides its length that transaction held the lock of the tenant's audit chain from its first reminder to its commit, so every audited action of the tenant waited for it; it ran under one job with a limit of 4.5 minutes, and a failure lost everything it had done. Each reader cost one statement per request and per reminder, and the event with all readers was fanned out by the notifications module in a single transaction as well.
2. **Renditions.** One at a time per instance, in the regular lane, in batches of ten under one lease. The business concept asks for p95 ≤ 10 s for a document of up to 50 pages (chapter 13). Nothing had measured that with real documents.
3. **The rendering queue.** Chapter 13 asks for business figures such as the rendering queue next to the technical ones. There is **no metric mechanism in the application** to build on: no library, no endpoint, only `/ready`, the logs and SQL statements in the operations guide.

A worker prints one page at a time (`RENDER_LOCK`), and a render whose caller gave up keeps that lock to its end (ADR 0073, DMS-161).

## Decision

**1. Reminder check.**

- A job handles one page of `acknowledgementReminderPageSize` versions (default 200), in id order. The first job comes from the hourly tick as before; the short transaction that reads a page also enqueues the job of the next page, with the id of the first job in its idempotency key. So a retry enqueues no second chain, the check goes on whatever becomes of this page, and tenants take turns.
- Each version has one transaction of its own, which reads that version only, by primary key (`DocumentService.awaitingAcknowledgement`, `audienceOf`, `acknowledgersOf`, `requestsOf`, `hasRequestBaseline`), and records its requests, reminders, audit entries and outbox events together. A crash leaves a version done or untouched; the unique index per version, reader and round makes a retry or a second instance skip what is recorded. Who may read is computed once per page, in a transaction that writes nothing.
- Requests and reminders of a version are one statement each, however many readers there are. The outbox gets one event per 1,000 readers (`NoticeRecipients`), which bounds the transaction of the fan-out; a version stays one transaction because a first request must stay all or nothing: the later ones count from their own date.
- A version that fails does not stop the others of its page. The job fails afterwards and its retry repeats the page, which is cheap, as what is recorded is skipped.
- The check after a publication uses the same path for its one version. It no longer reads the audiences of all versions.
- The jobs of the check have a lane of their own in `Background`, one job per claim. At the measured 30 ms per version, 50 tenants of 2,000 versions keep one lane busy for most of every hour: in the regular lane that would be the publications' waiting time. Several instances share the pages.

**2. Renditions.**

- Measured (below): the target holds for one document at a time, and fails as soon as two long documents meet at a worker. Rendering in parallel is therefore needed, and comes from **replicas**, not from more than one render per container: WeasyPrint is not documented as thread-safe, the lock stays.
- Renditions get a lane of their own in `Background`, one job per claim. `dms.rendering.parallelJobs` (`DMS_RENDERING_PARALLEL_JOBS`, default 1) says how many run at a time per instance. **The default stays 1**, the conservative choice: more jobs than replicas gain nothing and pile up behind abandoned renders (DMS-161), and one worker is what the stack starts. veraPDF is checked for parallel use by a test.
- `WORKER_VERSION` and the bytes of every PDF stay the same.

**3. Metric.** `GET /metrics` in the text format of Prometheus, written by hand, about twenty lines: `dms_rendering_queue_depth{state="due|delayed|running"}` and `dms_rendering_queue_oldest_seconds`. The numbers come from the database when asked, through the security definer function `dms.job_queue_depth(kind)` (V56), because the queue spans tenants and `dms.job` is under row-level security. Every instance gives the same numbers. No sign-in, like `/ready`; totals only, nothing about tenants, documents or users. This is the conservative option where nothing was decided before: no new library, and nothing to run besides what the operations guide already asks for. Only the rendering queue is published, as asked.

**Decisions taken without a written requirement**, all open to change: page size 200; 1,000 readers per event; `parallelJobs` default 1; `/metrics` without sign-in, kept from the outside by the reverse proxy like `/docs`; the alert value of 60 s in the operations guide, which is a starting value.

## Measurements

All on one machine with 16 cores, on 2026-11-06, while other agents ran builds and test suites: load average 7 to 17. **The numbers are not reliable as a p95 for production**; lows show a free core, p95 a busy machine. Tables with all figures: [scripts/loadtest/README.md](../../scripts/loadtest/README.md).

Renders: worker image built from `workers/rendering`, throwaway containers, memory limit of compose, `scripts/loadtest/render_bench.py`, 30 renders per row. Documents: the samples of spike DMS-23 in the document stylesheet with running header and footer, as PDF/A-2b. The 12-page contract repeated to 56 pages is the main one, because it has clauses, tables and annexes. They are realistic samples, not production documents, and contain no images.

| 50 pages or a little more | Min | p50 | p95 |
|---|---:|---:|---:|
| Contract, 56 pages, one at a time, busy machine | 5.06 s | 6.17 s | 11.42 s |
| Policy, 51 pages, one at a time, busy machine | 3.47 s | 4.31 s | 6.46 s |
| One table over 50 pages, one at a time, busy machine | 7.05 s | 9.43 s | 13.75 s |
| Contract, 2 requests at one worker | 6.28 s | 12.00 s | 12.95 s |
| Contract, 4 requests at one worker | 5.20 s | 21.98 s | 24.59 s |
| Contract, 4 requests at 4 replicas, lower load | 5.48 s | 6.52 s | 7.04 s |

veraPDF adds about 1 s (p95 1.5 s). One worker gives 8 to 11 renditions of this size a minute, whatever the number of requests; four replicas gave 36.8. A render uses one core and no other resource, so time grows with the competition for CPU. Peak memory was 369 MiB at 56 pages, against a limit of 512 MB: a contract of 210 pages took 24 s, one of about 500 pages ran for more than ten minutes at the limit and then the kernel killed the worker (OOM), so a request of that size takes the worker down.

Result: on a free core the target holds, at about 6 to 8 s with validation; for a single table over 50 pages it holds with little room. On this busy machine it was missed at p95 even one at a time. It is missed for sure as soon as two long documents share a worker, which on-demand PDFs (up to four in flight per instance) make likely. Hence replicas and more than one job.

Reminder check: a temporary test, not committed, through the real job runner against PostgreSQL 17 with default settings. 2,000 versions, 2,000 readers each, 3.96 million acknowledgements, 4 million requests, 1 % open, which is one average tenant of chapter 14.2.

| | Time | Longest transaction seen |
|---|---:|---:|
| Before: only the reads of the former check, in one transaction, 3.96 million rows in memory | 9.0 s | 9.0 s, plus all writes |
| First run, 11 jobs, 40,000 reminders in 2,000 events | 90.6 s | 0.40 s |
| Next hour, nothing due | 57.8 s | 0.05 s |
| One version with 100,000 readers: all asked, 100 events | 4.3 s | 3.7 s |
| The same: all reminded | 4.7 s | 4.3 s |
| The same: nothing due | 2.2 s | 1.9 s |

About 30 ms per version under this load, so 6 s per job of 200 versions. All 50 tenants of 2,000 versions would take about 50 minutes of one lane per hour on one instance. The notification fan-out of an event of 1,000 readers and a tenant with a real mix of versions (the page query there may use another plan) were not measured.

## Alternatives considered

| Criterion (shape of the check) | Chosen: chain of page jobs, a transaction per version | One job, a loop over pages | A job per version | One set-based SQL statement |
|---|---|---|---|---|
| Longest transaction | One version | One version | One version | The tenant |
| Job limit of 4.5 minutes | Per page | For the whole tenant: a large tenant never finishes, and each retry starts over | Per version | One statement, under the 60 s statement limit |
| Rows in `dms.job` per tenant and hour | About one per 200 versions | One | One per version: 100,000 an hour at 50 tenants | One |
| Tenants in the lane | Take turns per page | Wait for each other's whole check | Take turns per version | Wait for each other's whole check |
| Who reads is decided by | The policy in Scala | The policy in Scala | The policy in Scala, read per job | Would have to be rewritten in SQL, next to the policy |

| Criterion (next page) | Chosen: enqueued before the page runs | Enqueued after the page | All pages enqueued at the start |
|---|---|---|---|
| A page that times out for good | The rest of the tenant still runs | The chain ends there, and every hour at the same place | The rest still runs |
| Pages at the same time | One per instance at most | One | As many as there are runners |
| Reading of ids | A page at a time | A page at a time | All ids in one go |

| Criterion (reminders of many readers) | Chosen: one transaction per version, events of 1,000 | Transactions of 1,000 readers | One event per version |
|---|---|---|---|
| A first request stays all or nothing | Yes | No: after a crash the rest would count as later joiners and be reminded a whole interval too late | Yes |
| Longest transaction with 100,000 readers | See measurements | Shorter | The fan-out takes longer than its lease |
| Baseline mark of V34 | Unchanged | Must move to the end | Unchanged |

| Criterion (parallel rendering) | Chosen: replicas, jobs per instance configurable | Processes inside the worker | Threads inside the worker | Leave it as it is |
|---|---|---|---|---|
| Meets the target with two long documents at once | Yes, with replicas | Yes | No: Python's lock and the renderer's safety | No |
| Risk for the bytes of archive copies | None | Low, but a new worker build | Unknown: not documented as thread-safe | None |
| Memory | 512 MB per replica | Limit of the container must grow | Unknown | Unchanged |
| Effect of an abandoned render | Holds one replica | Holds one process; could be killed later (DMS-161) | Unknown | Holds the worker |
| Change | A setting and a lane | New process handling, a new `WORKER_VERSION` is not needed but the fingerprint changes | One line and a risk | None |

| Criterion (publishing the figure) | Chosen: hand-written text format at `/metrics` | Micrometer or `zio-metrics-connectors` | Log line per interval | In `/ready` |
|---|---|---|---|---|
| New dependency | None | Yes, with licence and audit work, for two figures | None | None |
| Read by a usual monitoring system | Yes | Yes | Only through log rules | Needs a custom check |
| JVM and HTTP figures for free | No | Yes | No | No |
| Load balancer probes touch the job table | No | No | No | Yes |

| Criterion (where the number comes from) | Chosen: read when asked | Gauge updated by a background fiber |
|---|---|---|
| Fresh | Always | By its interval |
| Cost without anyone asking | None | One query per interval and instance |
| Cost when asked | One query on a partial index | None |
| Asked anonymously in a loop | A database connection each time, like `/ready` | Free |

## Consequences

### Positive

- Nothing in the check grows with the tenant but the number of its short jobs. The audit chain of a tenant is held for one version's writes, not for the check.
- A crash midway loses and doubles nothing, by construction and by test: a failure after the last write of a version leaves no trace of it, and the versions around it are done.
- One version that cannot be processed no longer stops the reminders of the whole tenant.
- Renditions and publications no longer wait behind reminder checks or each other's lanes, and a claimed rendition no longer waits behind nine others under one lease.
- Operators can read the queue, alert on it and know when to add a replica.

### Negative / Cost

- More rows in `dms.job`: one per page and tenant and hour instead of one.
- The check is no longer one snapshot: a version is judged a few seconds after its page was read, and the readers once per page. Someone who loses a role during a page may still get one reminder.
- A check of a tenant ends later than its work alone would take, as its pages queue behind those of other tenants; at full volume on one instance the checks of all tenants may fill the hour. Reminders are due per day, so an hour's delay changes nothing, and runs that overlap are harmless. A check after a publication waits in the same lane, for some minutes at worst.
- A version is a single transaction even with 100,000 recipients, and that one holds the audit chain of the tenant for the time given below.
- A reminder to more than 1,000 readers is several events: consumers must not take an event for a whole reminder. The audit entry still counts all readers.
- Each lane costs one more polling query every 2 seconds per instance, and a lane of reminders that always has work takes one database connection at a time.
- Scaling renditions takes two settings that must fit each other, replicas and `parallelJobs`, and the application cannot check them. veraPDF runs in the application: `parallelJobs` also costs heap and CPU there.
- `/metrics` is one more anonymous endpoint that reads the database. It must be kept from the outside.
- A hand-written format has to be kept correct by hand, and there are no JVM or HTTP figures.

## Follow-ups

- Must be done: DMS-161, a worker that gives up a render when its caller is gone. Until then `parallelJobs` must not exceed the replicas.
- Must be done: a limit for very long documents. About 500 pages exhaust the 512 MB of a worker and kill it, and the job queue sends the same document again. Each attempt also runs on long after its caller gave up (DMS-161). Size the memory or refuse such pages, and restart the worker by itself.
- Must be done: repeat the measurements on production-like hardware without other load. This machine ran other work at the same time.
- Should: the compliance cockpit and the owners' overview still read `acknowledgersOfValid`, `audiencesOfValid` and `currentlyValid` for a whole tenant per request. Same growth, in a request.
- Should: `audiences` and `lastManualReminders` compare `version_id::text`, which no index serves, and `DocumentService.validVersion`, now used by the cockpit, groups all versions of the tenant for one row. `awaitingAcknowledgement(id)` shows the cheap form.
- Should: `dms.job` keeps every finished job. Nothing in this ADR depends on it, but the rows of the check add up.
- Possible: more figures at `/metrics`, for example the age of the oldest outbox event, the bulk lane and dead jobs (dead jobs need an index first).
- Possible: a lane or priority so that on-demand PDFs go before renditions (business concept 14.2: interactive before bulk).
- Ruled out: more than one render per container with threads; a metrics library for two figures.

## Reversibility

**high**: The shape of the check is internal to `AcknowledgementReminderJobs`, and jobs of the old and new form both run: a job without a cursor starts at the beginning, and one with a version checks that version. During a rolling update an older instance treats a job of a later page as a whole check, which is slow, not wrong. The lane is a few lines in `Background`. `/metrics` and `dms.job_queue_depth` have no dependents but the monitoring, and a library could serve the same names. No data changes.
