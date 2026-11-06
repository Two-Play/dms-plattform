# 0081: Every render runs in a child process that is killed at a time limit; documents over a page estimate are refused before rendering; both failures end the job at once with a reason of their own; workers restart by themselves

| Field | Value |
|---|---|
| Status | Proposed |
| Date | 2026-11-06 |
| Decided by | Philippe |

Takes up the must point of DMS-171, the time limit of DMS-161 and the second follow-up of [ADR 0079](0079-background-jobs-at-full-volume-and-rendering-queue-metric.md). Builds on the failure notices of [ADR 0058](0058-notices-of-jobs-that-failed-for-good.md) and [ADR 0064](0064-closing-the-gaps-of-failure-notices.md) and the worker of [ADR 0025](0025-archive-renditions.md). ADR 0073 said that a render whose caller gave up runs to its end; with this ADR that end is at most the time limit.

## Context

A document of about 500 pages ran for more than ten minutes at the 512 MB of the rendering worker, then the kernel killed the worker (ADR 0079). Four things went wrong together:

1. **Nothing ended a render.** `RENDER_LOCK` is held until WeasyPrint returns, and WeasyPrint cannot be interrupted in a thread. The application gave up after 60 s, the worker did not, and every other document waited for the lock.
2. **Nothing refused a document.** The only limit was 32 MB per request, far above what the memory holds.
3. **The queue sent the same document again.** Every failure is retried up to ten times, the delays doubling up to an hour, and people are only told when the job is dead (ADR 0058). A long document kept taking the worker down for the better part of a day, under the reason `unavailable`.
4. **Compose does not restart a killed worker.** After the first kill every rendition failed until someone noticed.

### Measured

Workers built from `workers/rendering`, throwaway containers, requests from `scripts/loadtest/render_bench.py`, one render at a time, on a machine that was busy with other builds at the same time. Peak is `memory.peak` of the container. Tags and characters are counted in the body of the page.

| Document | Pages | Time | Peak (old worker) | Characters per page | Tags per page |
|---|---:|---:|---:|---:|---:|
| Contract | 56 | 5.3 s | 122 MiB | 1,850 | 103 |
| Contract | 199 | 22.7 s | 280 MiB | 1,870 | 104 |
| Contract | 276 | 30.6 s | 373 MiB | 1,870 | 104 |
| Contract | 353 | 38.2 s | 461 MiB | 1,880 | 104 |
| Contract | 463 | 59.3 s | 589 MiB | 1,880 | 105 |
| Policy | 213 | 17.6 s | 202 MiB | 2,010 | 56 |
| Policy | 638 | 56.8 s | 451 MiB | 2,010 | 56 |
| Single table | 75 | 11.3 s | 153 MiB | 1,250 | 134 |
| Single table | 298 | 65.0 s | 445 MiB | 1,260 | 134 |
| Single table | 595 | 138.9 s | 850 MiB | 1,260 | 135 |

Memory follows the number of tags, about 11 KiB each on top of 46 MiB, and hardly the characters. These runs had 3 GB, so the times are without memory pressure. The peak of 369 MiB at 56 pages of ADR 0079 is not reproduced by a single render (122 MiB). It was measured over 30 renders in one long-lived process, which may be the reason; that was not checked. With a process per render the memory is back at about 90 MiB after each one.

## Decision

1. **A child process per render.** `render_limited` holds the lock, starts a child and waits for its answer on a pipe for `DMS_RENDER_TIME_LIMIT_SECONDS` (default 120). At the limit the child gets SIGKILL and the lock is free. The children come from Python's `forkserver`: one single-threaded process imports `server.py` and loads the fonts once, then forks. Forking the threaded HTTP server itself would copy its locks, and a fresh interpreter per render would cost the imports each time. Loading the fonts in the fork server matters: without it every render took more than a second longer; with it the difference to the old worker is 0.1 to 0.2 s.
2. **A limit before rendering.** `estimated_pages` estimates from the body of the page, without layout, as the larger of characters / 2,000 and tags / 105. On the samples that gives 352 for 353 pages, 650 for 638, and 95 for 75 for the single table, which errs in the right direction as tables also need more memory per page. Above `DMS_RENDER_MAX_PAGES` (default 300) the worker answers within milliseconds. An image's data URI is part of its tag, so images do not count as text. The estimate runs in the server, not in the child, so it is built to stay linear on malformed input.
3. **The defaults.** 300 pages is six times the 50 pages of the business concept and leaves 210 pages, the longest real case at hand, well inside. At 300 pages the contract peaks at about 475 MiB of the 512 MiB, the extra processes included (decision 7). 120 s is about three and a half times what 300 pages take on a free core.
4. **Running out of memory is `too_long` too.** A child killed with SIGKILL by anyone but the worker was killed by the kernel. One render runs at a time, so it was this document. The limit of the container now hits the child, the largest process, and the worker stays up. Tried with 256 MiB and no swap: the render fails with `too_long` after 2 s, the next page prints.
5. **Two new codes, no retry.** The worker answers 422 with `"code": "too_long"` or `"time_limit"`, so an older application still sees a refusal. They become `RenderingError.TooLong` and `TimeLimit`. V58 adds `dms.job_failure_is_final`, and `dms.job_fail` makes such a job dead on the spot. Everything that follows a dead job is unchanged: the trigger of V40 writes the event, owner and records managers get the notice, the reminders run (ADR 0064), the version can be withdrawn (ADR 0078). The reasons are in `FailureReasons` and the rendition status in both languages. `canRetry` stays true: after a raised limit, or for a time limit that a busy machine caused, a person can still retry. On-demand PDFs answer 422 `document_too_long` or `rendering_time_limit`.
6. **The application waits longer than the worker.** `dms.rendering.timeoutSeconds` goes from 60 to 150, or the application gives up first and retries as `unavailable`. With the check by veraPDF that stays below the 270 s of a job and the 300 s of the relay in `worker-gateway`.
7. **The price is memory.** The fork server, the resource tracker and the server hold about 85 MiB when idle, and the peak rises by about 70 MiB. `mem_limit` stays at 512m: the limit of 300 pages is what fits.
8. **`restart: unless-stopped`** for the rendering and the conversion worker. It acts on a process that ended, not on an unhealthy one; the server no longer depends on a render, so a hanging render cannot make it hang.
9. **The bytes stay the same.** `WORKER_VERSION` stays 1. `scripts/loadtest/render_compare.py` compares the worker of `main` with this one: 5 documents as PDF/A-2b and as plain PDFs, each twice per worker, all byte for byte equal. The build fingerprint changes because `server.py` changes, as it should.

Conservative choices, as nothing in the code, the ADRs or the business concept decides them:

- A render at the time limit is **not retried automatically**, although a busy machine can cause it, because the task asks for the same treatment as a document that is too long. A person can retry.
- The worker **refuses** rather than prints long documents with more memory. A larger `mem_limit` plus a larger `DMS_RENDER_MAX_PAGES` is a setting, not a change of code.
- The limit applies to **every** render, on-demand PDFs and evidence reports included.

## Alternatives

| Criterion | Chosen: child from a fork server | `os.fork` in the handler | New interpreter per render | Long-lived child, replaced at the limit | Time limit in the thread (`signal`, flag) | Let the container die and restart |
|---|---|---|---|---|---|---|
| Really ends a render | Yes | Yes | Yes | Yes | No: native code and no signals in threads | Yes |
| Frees the lock | Yes | Yes | Yes | Yes | No | By restart |
| Time per render | + 0.1 to 0.2 s | About the same | + imports and fonts, over 1 s | None | None | None |
| Memory after a render | Given back | Given back | Given back | Grows as today | Grows as today | Grows as today |
| Risk | Standard library, needs `/tmp` | Copies locks of a threaded server | Low | Protocol and restart to write ourselves | None | All waiting requests fail |
| Other renders during a failure | Unaffected | Unaffected | Unaffected | Unaffected | Blocked | Fail |

| Criterion | Chosen: estimate of pages | Size of the request | Pages after layout | Memory limit alone |
|---|---|---|---|---|
| Fails fast | Yes, milliseconds | Yes | No: layout is the cost | No |
| Fair to images | Yes, data URIs do not count | No: one large image is refused | Yes | Yes |
| Tells people something | "about 352 pages" | "1.2 MB" | Exact | Nothing |
| Exactness | About 5 %, on the samples | None | Exact | None |

| Criterion | Chosen: the database decides by the code | A new result type for job handlers | The handler lowers `max_attempts` | Keep retrying, tell earlier |
|---|---|---|---|---|
| One place that ends a job | `dms.job_fail`, also for other callers | `JobRunner` | The handler | None |
| Change | One migration | Every handler | Another function, and a retry would keep the lowered value | None |
| Duplicate knowledge | A list in SQL and in Scala, compared by a test | None | None | None |
| Load on the worker | One attempt | One attempt | One attempt | Up to ten attempts |

## Consequences

### Positive

- A long document costs the worker milliseconds, not its life. Owner and records managers learn why within a minute, not after a day under `unavailable`.
- A render holds the lock for at most the time limit. Others wait, but they get their turn.
- Memory goes back after every render, and the memory limit of the container no longer ends the worker.
- The time a render may take is now one setting in the worker, and the application waits for the worker's answer.

### Negative

- Documents above about 300 pages have no archive PDF until they are split or the limits are raised. Up to about 370 pages this worked before, at the edge of the memory.
- The peak rises by about 70 MiB at the same `mem_limit`.
- An on-demand PDF can now keep a request open for 150 s instead of 60 s.
- Two settings must fit each other, and the application cannot see the worker's: `dms.rendering.timeoutSeconds` above `DMS_RENDER_TIME_LIMIT_SECONDS`. The production checklist says so.
- A list of codes exists in SQL and in Scala. A test compares them.
- The estimate is calibrated on the document stylesheet. A template with much smaller or larger type shifts it; the time limit and the memory catch what it misses, later.
- Where the orchestrator kills the whole container at its memory limit (`memory.oom.group`, as Kubernetes does by default on cgroup v2), decision 4 does not hold: the restart brings the worker back and the job counts as `unavailable`, so it is retried until it is dead.

## Follow-ups

- Must be done: the rest of DMS-161. A render whose caller is gone still runs up to the time limit, and a request whose caller left while it waited for the lock still starts. The wait in `render_limited` is the place to look at the connection.
- Must be done: the conversion worker has a time limit and frees its lock, and its compose service restarts now, but three things are left. `subprocess.run` kills `soffice`, while `soffice.bin` can live on as its child, so the worker needs a process group. It has no estimate before converting. And a timeout comes back as 422 `rejected` and is retried: it needs the same `"code"` and the mapping in `ConversionWorker`.
- Should be done: the parent process needs WeasyPrint only for its version. Reading that from the package metadata saves about 30 MiB.
- Should be done: `segregation_of_duties` is the other failure no retry can cure (ADR 0078), and it still uses up its attempts. Adding it to `dms.job_failure_is_final` is a small change.
- Should be done: measure the limits on real long documents with images; the samples have none. The estimate leaves images out, so only the time limit and memory stand in their way.
- Should be done: `dms.rendering.parallelJobs` must still not exceed the replicas (ADR 0079), but what piles up in a worker is now bounded.
- Check in the first weeks of operation: how many jobs end with `too_long` and `time_limit`.

## Reversibility

Easy. The limits are settings: a high `DMS_RENDER_MAX_PAGES` turns off the refusal, a high `DMS_RENDER_TIME_LIMIT_SECONDS` the time limit. Going back to the render in the thread is a change of one file without any effect on stored PDFs, since the bytes are equal. The two new codes can stay in texts and catalogs. Dead jobs of those codes can be retried by a person at any time. V58 is undone by replacing `dms.job_fail` with its text from V6 and dropping the function. `restart:` is one line per service.
