# 0055: Rendition robustness: visible failures with an audited retry, Object Lock retention from the retention rule, worker identity recorded per rendition

| Field | Value |
|---|---|
| Status | Proposed |
| Date | 2026-10-30 |
| Decided by | Philippe |

## Context

DMS-93 (US-06.1, follow-ups of ADR 0025, 0031 and 0041): the archive rendition of an approved version (ADR 0025) is produced by a job between the last vote and the approval. Three gaps were left open:

1. **Failures.** The job queue retries a failed rendering with exponential backoff (10 attempts, 10 s doubling up to 1 h, ADR 0010), the owner gets the outbox event `document.rendition-failed`, and the version stays `InApproval` with the case in `Rendering`. Once every attempt failed, the job is `dead` and nothing can revive it: there is no visible state on the document page, no overview for administrators or records management and no retry.
2. **WORM.** Renditions, attachment files and originals of file documents are stored in a bucket with Object Lock (ADR 0016), but without retention: only the database guards (ADR 0021) and the object legal hold (ADR 0031) protect them. Business concept 7.4 requires approved versions to be unchangeable for their retention period. A version carries its retention rule as the key in the core field `retentionRule`; DMS-82 (ADR 0054, migration V33) made the rules tenant-scoped objects in `dms.retention_rule` (period in months, trigger `Approval` | `Creation` | `EndOfValidity`, start at year end), maintained by records managers on the "Aufbewahrung" page, and uses them for destruction. The destruction path and the clean-up of orphaned objects (DMS-82) respect the lock state through `ObjectStore.delete` (`StorageError.Locked`) and `ObjectStore.head`.
3. **Reproducibility.** Renditions record the renderer versions (`X-Renderer`) in the audit trail, but not which worker image produced them. The workers are built locally from their Dockerfiles (base image pinned by digest, packages by version, ADR 0008); no image is published to a registry, so there is no registry digest yet.

SeaweedFS 4.51, the local and CI object store, implements S3 Object Lock retention in both modes, the legal hold and the governance bypass (ADR 0016, `ObjectStoreSpec`), so the lock can be tested against the real store; no MinIO container or fake is needed.

## Decision

1. **Failure state and retry.**
   - The job error starts with a stable failure code in brackets (`[unavailable]`, `[rejected]`, `[not_archivable]`, `[invalid]`, `[integrity]`, `[database]`, from `RenderingError.code`), followed by the technical description. No new table: the state lives in `dms.job`.
   - `GET /api/v1/documents/{id}/versions/{versionId}/rendition/status` (requires `ReadDocument`): `state` `Done` | `Pending` | `Retrying` (with `nextAttemptAt`) | `Failed` | `None`, attempts, the last error as `{code, message}`, `canRetry`, and the provenance of a produced rendition. The workflow panel shows it while the case is `Rendering`, explaining the code in the user's language with the technical detail folded away.
   - `POST …/rendition/retry` requeues the job through the security-definer function `dms.job_retry(kind, key)`: a dead job starts over with all attempts, a job waiting for its next attempt runs now; any other state is `409 rendition_not_failed`. Audited as `document.rendition-retry-requested` (previous state, attempts, error).
   - New action `RetryRendition` (ADR 0018): on a version in approval, the document's owner (holding the owner role; without owner: the author), records managers and tenant administrators; on the tenant (the list) records managers and tenant administrators. Confidentiality applies as everywhere.
   - `GET /api/v1/renditions/failures`: failed and retrying renditions of versions still in approval, newest first (max. 200), with title only where the caller may retry. Shown in the administration ("Archiv-PDFs") and on the legal holds page of records management.
   - Automatic retries stay as they are (ADR 0010/0025).
2. **Object Lock retention.**
   - When a version is approved (`released_at` set), a trigger enqueues one job `archive.object-retention` per version; migration V36 enqueues it once for versions approved before. The job (`ObjectRetentionSync`, module `archive`) sets the retention on the version's rendition, original and attachment files.
   - **One source of the period: `dms.retention_rule` (ADR 0054).** The lock ends after the last day of the retention the version's rule gives (`RetentionPeriod.endsOn` in the tenant's time zone, the same computation as the destruction list): counted from the rule's trigger where it is known (approval; creation of the document), and from the approval while the end of validity is not known yet. The version goes out of force at or after its approval, so that is a lower bound. The job runs again when the version goes out of force (trigger on `Superseded`/`Withdrawn`) and when its rule is created or changed (trigger on `dms.retention_rule`), and only ever extends a lock (S3 never shortens one): a shortened rule leaves longer locks in place. A version whose rule key has no row is locked `dms.retention.objectLock.defaultYears` (10) after its approval; a retention that already ended (e.g. counted from a creation long ago) sets no lock.
   - **Mode: governance by default, compliance configurable** (`dms.retention.objectLock.mode`). Governance keeps a documented escape (the `s3:BypassGovernanceRetention` permission, given to no application credential) while retention rules are not legally confirmed and may still be corrected; compliance is the target once the rules are confirmed by the records manager (business concept 7.4). An object keeps the mode of its first lock: compliance is never weakened, and governance is not turned into compliance by the job (that needs the bypass permission).
   - The lock is recorded in `dms.object_retention` (key, version, mode, `retain_until`, document, version, rule; RLS; never shortened or deleted, guarded by a trigger) after the store confirmed it, and audited as `document.objects-retained`. **Lock-state API for DMS-82**: `dms.archive.ObjectRetentions.lockOf / ofDocument / isLocked` or SQL on `dms.object_retention`; an object with `retain_until > now()` is not proposed for deletion. The store remains authoritative (`ObjectStore.head`), and a delete of a locked object fails with `StorageError.Locked`.
   - Legal holds stay independent (ADR 0031): a hold blocks deletion regardless of the retention, releasing a hold never touches the retention, and the retention never ends a hold. New objects of a held document keep getting the hold through the existing triggers.
   - Configuration `dms.retention.objectLock { enabled, mode, defaultYears }`, next to DMS-82's keys of `dms.retention`. Capability flag `enabled` (default `true`): `false` for a store without Object Lock; the job then sets nothing and logs it. Errors a retry does not fix (missing object, no lock support, missing permission) are reported as skipped in the audit entry, as for the legal hold; an unreachable store fails the job for a retry.
3. **Worker identity.**
   - Each worker computes a **build fingerprint** at start: SHA-256 over its installed Debian packages and Python distributions (name and version), the Python version and its `server.py`, and returns it as `X-Worker-Build`. It also returns the image reference from `DMS_WORKER_IMAGE` as `X-Worker-Image`.
   - `compose.yaml` takes the image of each worker from `DMS_RENDERING_IMAGE` / `DMS_CONVERSION_IMAGE` (default: the local build tag) and passes the same reference to the worker, so a deployment that publishes the images pins them by digest in one place.
   - The rendering job records renderer, worker image, build fingerprint, archive standard, validator and page count per version in `dms.rendition_provenance` (insert-only, RLS) and in the audit entry `document.rendition-created`; the evidence report shows them with the rendition's Object Lock state (optional fields of format 1, absent for older versions).

Flyway V36. No new dependency.

## Alternatives considered

| Criterion (retry and visibility) | Chosen: state from `dms.job`, retry endpoint, code prefix in the job error | Own table of rendition attempts | Only notifications, manual SQL for retries |
|---|---|---|---|
| Schema change | One security-definer function | New table, written by every attempt | None |
| Explains the cause to users | Yes, by code | Yes | No |
| Consistency with the job queue | The job is the single source | Two sources (job, attempts) to keep in sync | – |
| Audit of retries | Yes | Yes | No (database access) |
| History of every attempt | Audit trail (`document.rendition-failed` per attempt) | In the table | Audit trail |

| Criterion (retention end) | Chosen: the rule of `dms.retention_rule`, from its trigger or (until known) the approval | Approval + years from a separate configuration (first draft of this ADR) | One fixed period for everything |
|---|---|---|---|
| Available now | Yes | No | Yes |
| Never too short for the rule | Lower bound: every trigger is at or after the approval | Exact | Only if the period is the longest one |
| Never too long | Exact once the trigger is known; until the end of validity, a lower bound extended later | Can disagree with the destruction list (two sources) | Too long for short rules |
| Later correction | Extend only; governance allows a privileged correction | – | Extend only |

| Criterion (mode) | Chosen: governance by default, compliance configurable | Compliance always | Governance always |
|---|---|---|---|
| WORM strength | Strong against the application and normal credentials; bypass permission can delete | Nobody can delete before the end | Same as chosen, without upgrade path |
| Risk of a wrong rule | Correctable with the bypass permission (audited outside the platform) | Not correctable: storage cost and GDPR deletion duties until the end | Correctable |
| Fit to "rules confirmed by the records manager" (business concept) | Switch to compliance after confirmation | Before confirmation | Never reaches compliance |
| Local development | Volumes can be reset | Compliance locks in a developer volume cannot be undone (ADR 0016) | Resettable |

| Criterion (where the lock is set) | Chosen: job after the approval commit, triggered by the database | At upload/store time | Inside the approval transaction |
|---|---|---|---|
| Drafts and discarded uploads | Never locked (DMS-129 and DMS-82 can delete them) | Locked: orphans could not be cleaned up | – |
| Store not transactional | Job after commit, idempotent, retried | – | A rollback would leave locked objects behind |
| Approved before V36 | Backfilled by the migration | Not covered | Not covered |

| Criterion (worker identity) | Chosen: build fingerprint from the worker + configured image reference | Registry digest only | Image ID read by the application from Docker |
|---|---|---|---|
| Works without a registry (local, CI) | Yes | No | Only with Docker socket access (unsafe) |
| Identifies what determines the output | Packages, Python, code | The whole image | The whole image |
| Pinning in compose | `DMS_*_IMAGE` with `name@sha256:` where published | Same | – |

## Consequences

### Positive

- A failed rendition is visible where people look (document page, administration, records management) with a cause in their language, and can be retried without database access; every retry is audited.
- Approved versions are WORM in object storage for at least their retention period, in addition to the database guards and the legal hold; the lock state is queryable by DMS-82 without asking the store.
- Every rendition names the worker build that produced it; with published images also the registry digest. Evidence reports carry both, plus the lock of rendition and files.
- Tested against the real object store (SeaweedFS) including governance, compliance, bypass and the combination with the legal hold.

### Negative / Cost

- Until a version goes out of force, a rule triggered by the end of validity is locked only from the approval on (lower bound); the job extends it when the version goes out of force. A rule that is shortened later cannot shorten existing locks (governance: only with the bypass permission).
- Between storing a rendition and the retention job (seconds) the object has no retention; the database guards protect it meanwhile.
- Governance mode can be bypassed by whoever holds the bypass permission; operations must not give it to application credentials.
- The build fingerprint changes with every rebuild that changes a package version, also when the PDF bytes would not change.
- Without published images the worker image reference is only the local tag (`dms-rendering-worker:local`).

## Follow-ups

- Must be done: user confirms this ADR (status → Accepted).
- Must be done: publish the worker images to a registry and set `DMS_RENDERING_IMAGE` / `DMS_CONVERSION_IMAGE` with digests in deployments (CI build and push job).
- Must be done: switch to compliance after the legal confirmation of the retention rules.
- Should be done (DMS-82, ADR 0054): destruction proposals may read `ObjectRetentions` / `dms.object_retention` to show locked objects up front; the object clean-up already keeps locked objects (`StorageError.Locked`) and retries at the end of the retention.
- Later: contract-end triggers (ADR 0054 follow-up) extend the locks through the same job.
- Should be done: notifications module delivers `document.rendition-failed` and a reminder for dead renditions (DMS-130).
- Later: golden-checksum regression test of the rendering image (ADR 0008 follow-up); a retention extension when a newer version references an older attachment file is already handled by the job.
- Ruled out: locking objects at upload time; reading image digests through the Docker socket.

## Reversibility

**medium**: Status, retry and provenance are additive (one function, one table, three endpoints) and can be removed without data migration. Object Lock retention cannot be shortened or removed in compliance mode, and in governance mode only with the bypass permission: locks set until reversal stay in force until their end. Switching the mode or the periods applies to new locks only.
