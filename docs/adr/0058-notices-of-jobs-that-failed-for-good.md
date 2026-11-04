# 0058: Notices of jobs that failed for good: the database announces a dead job, each audience gets the link it can reach, publications get a retry

| Field | Value |
|---|---|
| Status | Proposed |
| Date | 2026-11-04 |
| Decided by | Philippe |

## Context

DMS-142 (release blocker of the MVP acceptance, part of DMS-92 and US-06.1): when a rendition or a publication job fails for good, nobody is told, so a policy can silently not take effect. Owner and responsible role must get a notice in the app and by email with document, version and reason, leading to the place of the retry. Only jobs that failed for good count, not single retries, and each failure is told once, also across restarts.

What the code showed:

- **How a job dies (ADR 0010).** `dms.job_fail` sets `dead` after the last attempt, also after the timeout of `Workers.guarded`, which interrupts the handler. `dms.job_claim` sets `dead` when the lease of the last attempt expired: the worker crashed and no handler code ever learns of it. A handler can therefore not be the detector.
- **`document.rendition-failed`** (ADR 0055) is written by the handler on the first and on the last attempt, carries the recipients from the time of the approval and has no subscriber. It misses both paths above.
- **The people notified could not reach the retry.** Records managers cannot read a version in approval or approved (`ReadDocument`), so the document page refuses them and ADR 0052's read check would hide the title from them: they would be told that "a document" failed. They reach the failure list on the legal holds page, which needs a tenant-wide assignment. Tenant administrators do not reach that page and cannot read the version either; their list sits in a tab of the administration that could not be linked to. The person named owner can retry only if they hold the owner role (ADR 0055; `RenditionApiSpec`: "may see, not retry").
- **A failed publication had no retry at all.** ADR 0055 covers renditions only. The periodic enqueueing (ADR 0026) adds nothing for a dead job because its idempotency key exists, and no endpoint publishes a version by hand. The version stayed approved for good; the failure was visible nowhere.

## Decision

1. **The database announces a dead job (migration V40).** A trigger on `dms.job` fires on every transition to `dead` of the kinds `document.render-rendition` and `document.publish` and writes one outbox event, `document.rendition-dead` or `document.publication-dead`, in the transaction that sets the status. It covers all paths. Retries write nothing. `dms.job_retry` brings a job back; dying again is a new failure and a new event.
2. **Once, also across restarts.** The event is durable with the status change; ADR 0052's unique delivery key (event, kind, recipient, channel) makes a repeated hand-over add nothing.
3. **Payload.** Ids, attempts and a failure code (`dms.job_failure_code`: the bracket code of the job error, `timeout` for timeouts and expired leases). The error text stays in `dms.job`: it can quote document content, so neither events nor notifications carry it. Reason texts per code in German and English are in `NotificationTemplates`; the technical text is on the retry page.
4. **Publication jobs get codes.** Their error now starts with `[conflict]`, `[legal_hold]`, `[invalid]`, `[not_found]` or `[database]`, like renditions (ADR 0055).
5. **Recipients, decided when the event is delivered** (the owner may have changed since the approval), by `PlatformNotificationEvents`:

   | Audience | Who | Link | Why this link |
   |---|---|---|---|
   | `records` | tenant-wide records managers | `/legal-holds` | cannot read the version; the list has title and retry |
   | `admin` | tenant administrators, only if there is no tenant-wide records manager | `/admin?tab=renditions` | neither the document page nor the legal holds page is open to them; the tab can now be linked to |
   | `owner` | the document's owner, without owner the version's author | `/documents/{id}?version={versionId}` | the workflow panel (rendition) or a new notice (publication) has the retry |

   Someone in two audiences gets the list. If the owner may not retry (`Policy` says so at delivery of the event), the text points to records management instead of promising a retry. Nothing is sent if the version no longer waits (rejected, withdrawn, published in the meantime).
6. **Read check.** Failure notices use the new `NotificationDocuments.visibleForRetry`: the facts for whoever may read the version or retry the job. That is what the failure list already shows them (ADR 0055). Someone not cleared for the document's confidentiality still gets a notice without title.
7. **New category `processing-failure`**, email on by default, so that switching off "publications of my documents" does not silence failures. In-app cannot be switched off.
8. **Retry of publications**, the counterpart of ADR 0055: action `RetryPublication` (same people as `RetryRendition`, on a version in status `Approved`; on the tenant for the list), `GET …/versions/{versionId}/publication/status`, `POST …/publication/retry` (audited as `document.publication-retry-requested`, `409 publication_not_failed` otherwise), `GET /api/v1/publications/failures`. It requeues the newest job of the version through `dms.job_retry`. The list is shown next to the failed renditions on the legal holds page and in the administration, the notice on the document page of an approved version.
9. **Backfill.** V40 writes an event once for dead jobs from before the migration whose version still waits.

Flyway V40. No new dependency.

## Alternatives considered

| Criterion (detecting a dead job) | Chosen: trigger on `dms.job` writes an outbox event | Handler publishes on its last attempt | Periodic sweep over dead jobs with a "noticed" marker | The job runner reports after `JobQueue.fail` |
|---|---|---|---|---|
| Timeout of the last attempt | Yes | No: the handler is interrupted | Yes | Yes |
| Crash on the last attempt | Yes | No | Yes | No: `job_claim` sets the status, no runner sees it |
| Atomic with the status | Yes | No: its transaction ends before `job_fail` | No, but eventually | No: a crash in between loses it |
| New failure after a retry | New event by itself | Yes | Marker must be reset | Yes |
| Delay | Next relay poll | Same | Sweep interval | Same |
| Cost | One trigger; kinds named in SQL | None | New job, new column or table | Change in `integration` for every job kind |

| Criterion (category) | Chosen: own category `processing-failure` | Reuse `publication` | Reuse `legal-hold` (records managers) |
|---|---|---|---|
| Choosing off "now valid" mails silences failures | No | Yes | No |
| Default email | On | On | Off: would miss the requirement |
| Cost | One category, two texts each | None | None |

| Criterion (retry of a publication) | Chosen: own action and endpoints beside the rendition's | Generic job retry for all kinds | Say in the notice that nobody can retry |
|---|---|---|---|
| The requirement "leads to the retry" | Met | Met | Not met: only database access helps |
| Permission per job kind and status | Yes | Needs a mapping | – |
| Cost | One action, three endpoints, one panel | Generic admin view, not needed yet (ADR 0010 follow-up) | None |

| Criterion (how records managers learn the title) | Chosen: `visibleForRetry`, read or retry permission | Keep `ReadDocument` only | Grant records managers `ReadDocument` on versions in approval |
|---|---|---|---|
| Notice names document and version | Yes | No: "a document" | Yes |
| New exposure | None: the failure list shows the same | None | Content of unapproved versions |

## Consequences

### Positive

- A policy no longer fails silently: whoever can act is told once, in their language, whatever way the job died.
- Everyone notified lands where the retry is, or is told who has it.
- A dead publication can be retried at all; it is visible with its cause.
- The handlers stay as they are; a further job kind needs one line in the trigger's condition and function plus a notice.

### Negative / Cost

- The job kinds and event names are written in SQL; a renamed kind needs a migration.
- The notice shows a reason by code. The exact technical text is only on the retry page, and a job error without code reads "reason unknown".
- The owner without the owner role gets a notice they cannot act on themselves. It says so, and records management gets it too; the rule of ADR 0055 stays.
- `document.rendition-failed` stays as a technical event per ADR 0055, next to the new one, without subscriber.
- No reminder follows a notice that goes unread.
- A retry that fails all its attempts again tells everybody again. That is meant: it is a new failure.

## Follow-ups

- Must be done: user confirms this ADR (status → Accepted).
- Should: a reminder when a failed job stays unhandled for days (ADR 0055 follow-up "reminder for dead renditions").
- Should: notices for the other job kinds that can leave a promise unkept (retention locks, webhooks) once they matter; a generic admin view of dead jobs (ADR 0010 follow-up).
- Later: audit entry for a dead publication job (renditions record each failed attempt, publications do not).
- Ruled out: error texts in outbox payloads or notifications.

## Reversibility

**high**: The trigger, the index and the two functions can be dropped without touching business data; deliveries and notifications are ADR 0052's. The endpoints, the action and the category are additive. Dropping the category leaves preference entries that are ignored.
