# 0026: Scheduled publication by a job at tenant midnight; editorial versions through an owner-confirmation path

| Field | Value |
|---|---|
| Status | Accepted (2026-10-28, implemented in DMS-45, DMS-46) |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

US-06.3 (DMS-45): an owner sets a valid-from date; the new version becomes valid automatically at 00:00 of that date in the tenant's time zone, and the old one ends at the same moment. There must never be a moment with two or with no valid version, and readers of the old version must be pointed to the new one. US-05.2 (DMS-46): an owner publishes an editorial correction as a minor version without the full approval process; the classification "editorial" needs a reason and is confirmed by the owner (business concept 5.2), an automatic comparison warns when more than a configurable share of the text changed, and minor versions do not ask readers for a new acknowledgement by default (business concept 6.3, configurable).

Given: `validFrom` is a core field required from review on (ADR 0019); versions are frozen packages with a partial unique index "one valid version per document and scope" and a guard trigger (ADR 0021); publication supersedes the valid version of the same scope inside `DocumentService`; the job queue with idempotency keys and `run_at` exists (ADR 0010) and is used for periodic audit work (DMS-75); workflows are generated from JSON definitions, the business truth lives in our tables, the final approval is segregated from the author by a database check (ADR 0024, 0021). Tenants had no time zone. Acknowledgement itself is not built yet. Another change (DMS-44, migration V13, ADR 0025) works on the final approval and renditions at the same time, so this change must not replace the V9/V10 guard function and must keep its changes to `ApprovalWorkflow` small.

## Decision

1. **Time zone.** `dms.tenant.time_zone` (IANA name, default `Europe/Berlin`, validated against `pg_timezone_names`). A version becomes valid at `validFrom.atStartOfDay(zone)` (the first moment of the date where 00:00 does not exist).
2. **Scheduling.** On approval the version's valid-from date is frozen in `document_version.valid_from`. Approved versions are published only from that moment on (`DocumentService` rejects an earlier `Publish`). `PublicationJobs.tick` runs on every instance every `dms.documents.publicationCheckSeconds` (60 s) and enqueues per approved version a job `document.publish` with `run_at` = due moment and idempotency key `document.publish:<version>:<epoch second of the moment>`; the job runner claims it when it is due. The handler `DocumentService.publishScheduled` locks the version and publishes it only if it is still `Approved` and due, so it is idempotent (at-least-once delivery, time zone changes, manual publication).
3. **Atomic switch, enforced by the database.** Publishing supersedes the valid version of the same scope and publishes the new one in one transaction. Migration V12 adds `superseded_by` and a separate before-trigger (not a replacement of the V9/V10 guard): a valid version can only be superseded by naming its successor, which is recorded once. A deferred constraint trigger checks on commit that the successor is valid in the same document and scope. Together with the existing unique index this makes both "two valid versions" and "no valid version" impossible for every writer; readers in other transactions see either the old or the new state.
4. **Readers' note.** The read side derives the successor (`Publication.successor`): for a superseded version the version in `superseded_by`; for a valid version the approved version of the same scope waiting for its date. Version views carry `successor`, `validFrom` and `publicationDue`.
5. **Editorial path.** Minor versions take the built-in `WorkflowDefinition.editorial` instead of the class workflow: one approval step `editorial-confirmation` with a lane of the pseudo role `DocumentOwner`, resolved by `ApprovalWorkflow` to the document owner. If the owner wrote the version (or there is none), the holders of `Approver` confirm instead, because the approver must differ from the author (ADR 0021). Step-up re-authentication applies as for any approval; the owner's vote is authorized with `ownerMayApprove` for owner lanes. The minor version's reason was already required (DMS-38); DMS-46 adds the shortened path, which also covers the "owner confirmation of minor versions" part of DMS-81.
6. **Text comparison.** `TextChange` (templates-api) compares the words of two contents section by section with an LCS in linear memory; the share is `1 - kept / max(words before, words after)`. `EditorialCheck` compares a minor version with the latest released version below its number; it warns (view field `editorialCheck`, audit `document.editorial-check` on submission), it does not block. Threshold `dms.documents.editorialChangeThresholdPercent` (10).
7. **Acknowledgement flag.** `document_version.requires_acknowledgement`, decided on creation: true for major versions, `dms.documents.minorVersionsRequireAcknowledgement` (false) for minor ones, overridable per new version; frozen afterwards; in the outbox payload of document events for the later acknowledgement story.

## Alternatives considered

| Criterion (when to switch) | Chosen: job per version with `run_at` = due moment + periodic enqueue | One sweep job per tenant at tenant midnight | Effective validity computed at read time (no status change at midnight) |
|---|---|---|---|
| Precision of the switch | Job runner poll interval (2 s) after 00:00 | Same, but all versions of the tenant in one run | Exact, no job |
| Valid-from dates in the past when approved | Next tick (≤ 60 s) | Next midnight unless a second trigger is added | Immediately |
| Time zone changes | New key per due moment; stale jobs do nothing | Re-plan the sweep | Automatic |
| Failure isolation | One transaction per version; a failing version does not block others | One failure blocks or needs per-version handling inside the sweep | n/a |
| Status, audit trail, outbox and `superseded_by` show the real switch | Yes | Yes | No: stored status and the valid version differ; every reader and index must evaluate time; the unique index cannot express it |
| Complexity | Low (pattern of DMS-75) | Low | High (all queries, RLS views, search index, integrations) |

| Criterion (never none) | Chosen: successor named + deferred constraint trigger | Service only (supersede and publish in one transaction) | Single `UPDATE` statement switching both rows |
|---|---|---|---|
| Holds for direct SQL of the application role | Yes | No | Only if every writer uses that statement |
| Order of the two updates | Free (unique index requires superseding first) | Free | n/a |
| Readers' note | `superseded_by` is the note's data | Must be derived heuristically | Must be derived heuristically |
| Cost | One trigger, one column | None | Statement does not fit the per-version transition logic (audit, outbox per version) |

| Criterion (editorial path) | Chosen: built-in one-step definition with owner lane | Per-class editorial definition (new column / definition field) | No workflow: owner publishes directly |
|---|---|---|---|
| Owner confirmation recorded like other approvals (vote, checksum, step-up, approval row) | Yes | Yes | Needs its own records |
| Segregation of duties (approver ≠ author) | Kept; deputy approvers when the owner is the author | Kept | Violated when the owner wrote the correction |
| Configuration effort | None | Tenant configures per class | None |
| Change to stored definitions and V11 | None | Migration and API change | None |

## Consequences

### Positive

- The guarantee "exactly one valid version per scope" holds in the database for every writer and for concurrent readers; tested with plain SQL and two connections.
- Scheduled publication reuses the job queue (idempotency, retries, leases, several instances) and records the switch in the audit trail as a technical actor (`system:publication`, channel automation).
- Readers of the valid version see the coming one and its date; readers of a superseded version are pointed to its successor.
- Editorial corrections need one confirmation by the owner instead of the class's review and approval chain, with the same traceability (vote, content checksum, step-up, approval record) and a warning when the change looks bigger than editorial.

### Negative / Cost

- The switch happens up to a few seconds after 00:00 (job runner poll); a version approved with a past valid-from date is published at the next tick (≤ 60 s).
- Versions must not be published manually before their date; publishing early needs a new valid-from date and therefore a new version.
- No new version can be started while an approved version waits for its date (it would be based on the old valid version).
- Overlapping scopes (`DE` and `AT|DE`) are still not detected (ADR 0021).
- The editorial path is the same for all classes; custom definitions cannot use the `DocumentOwner` lane yet (role validation in `WorkflowApi`).
- A failing publication job ends dead after its attempts; nobody is notified yet.
- Migration V12 lands before DMS-44 (migration V13), so the order of migrations matches the order of merges.

## Follow-ups

- Must be done: notify the owner when a scheduled publication fails or the job is dead; show the dead job in an operations view.
- Must be done: administration endpoint for the tenant time zone (today only by SQL as schema owner).
- Must be done: acknowledgement story reads `requiresAcknowledgement` (outbox `document.status-changed` with status `Published`).
- Should: per-class editorial path (opt out, other confirmers), owner lanes in custom definitions, owner deputies instead of the `Approver` role.
- Should: withdrawing an approved, not yet valid version (lifecycle change).
- Ruled out: computing validity at read time.

## Reversibility

**medium**: The scheduling is one job kind and a periodic tick; replacing it (e.g. by a sweep at midnight) changes `PublicationJobs` only. The database guarantee is an additive trigger and column; dropping it is a migration. The editorial path is a built-in definition chosen in one place of `ApprovalWorkflow`; cases already started keep their pinned definition.
