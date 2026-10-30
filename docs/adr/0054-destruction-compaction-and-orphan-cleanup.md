# 0054: Destruction after retention as a four-eyes path that leaves a tombstone, compaction of working states, removal of discarded drafts' content and a clean-up of orphaned objects

| Field | Value |
|---|---|
| Status | Proposed (2026-10-30, implemented in DMS-82) |
| Date | 2026-10-30 |
| Decided by | Philippe |

## Context

DMS-82 (from DMS-38, planning T-02.2) closes follow-ups of several decisions:

- ADR 0021: versions are frozen by a guard trigger ("no deletes"); "destruction must remove content from frozen rows, which the guard forbids; it needs a dedicated, privileged path", and working states "may be compacted after approval".
- ADR 0031 / 0047: a legal hold blocks destruction; the guard of V19 refuses `Destroyed` for a held document, `LegalHoldLookup` was prepared for "the destruction proposals and the destruction path".
- ADR 0016 / 0041 / 0042: objects are stored with Object Lock; the clean-up of orphaned, unlocked objects was left open. ADR 0050: files that became unreferenced earlier (an attachment removed from a draft, a replaced original) are not cleaned up, and the content of a discarded draft stays in the database.
- Business concept 7.4 and the worked example in 9: "Nach Fristablauf und ohne Legal Hold wird die Vernichtung vorgeschlagen, doppelt freigegeben und protokolliert"; risk table: "Vier-Augen-Vernichtung, Karenzzeit, Simulation vor Aktivierung"; data protection: deletion after the retention ends.

Given: the status `Destroyed` and the transition `Archived → Destroyed` exist in `Lifecycle` and in the guard; the action `Destroy` of the permission matrix requires the records manager role, status `Archived` and a second person. Nothing archived or destroyed so far, and there were no retention rules: the core field `retentionRule` is a free key set on approval. The audit trail is a hash chain (ADR 0017) and cannot lose entries; evidence reports (ADR 0034) re-read renditions and files and compare checksums.

## Decision

1. **Retention rules** (`dms.retention_rule`, V35): per tenant a key (the value of `retentionRule`), a name, a period in months, a trigger (`EndOfValidity`: withdrawal or the successor's publication; `Approval`; `Creation`) and whether the period starts at the end of that year. Records managers maintain them (`PUT /api/v1/retention/rules/{key}`, audited with before and after). Rules are never deleted. Retention ends on `start + period` (tenant time zone); a version is due the day after.
2. **Destruction list**: approved versions out of force (Superseded, Withdrawn after being valid, Archived) whose retention ended; versions without a (known) rule and versions with a running retention are only counted. Held versions are listed but marked and cannot be proposed.
3. **Four eyes as proposal and decision** (`dms.destruction_proposal`, `dms.destruction_item`): a records manager proposes versions with a reason; another one approves or rejects with a reason. The database refuses a decision by the proposer (check constraint), lets a proposal be decided once, never deletes it, and allows a version in at most one open proposal. On approval, in the same transaction, every version is checked again (legal hold, retention with the rule as it is now, status) and either destroyed or `Blocked` with the cause; a rejection marks the versions `Rejected` and frees them.
4. **Destruction leaves a tombstone**: the version row stays (`Destroyed`, number, approval, rendition key and SHA-256, `content_removed_at/by`); content becomes empty structured content, values and deviation report empty, the metadata snapshot keeps only system fields (document id and number, class, version, template, rendition checksum, retention rule); working states, attachment links and the original link are deleted; stored files no other version references are deleted. A superseded or withdrawn version is archived first (`→ Archived → Destroyed`), so the lifecycle and the permission matrix stay unchanged. The proposal item records what was destroyed: rendition SHA-256, the approved content SHA-256 (the workflow case's checksum), and per file name, role, SHA-256 and whether its bytes were released. Audit entries `destruction.proposed/approved/rejected` on the proposal and `document.destruction-proposed`, `document.destroyed` (with checksums and both reasons), `document.destruction-blocked/rejected` on the document; outbox `document.status-changed` so the search index drops the version. Process records stay as evidence: workflow cases and votes, deviation requests, acknowledgements, reviews, legal holds, evidence reports, audit entries.
5. **The privileged path in the database** (V35, `dms.document_version_guard`): a version becomes `Destroyed` only together with the removal of its content (exact tombstone values, identity and approval unchanged, snapshot a subset) and only while it is part of an approved proposal decided by someone other than the proposer. The guards of attachment links and originals allow their deletion for destroyed versions. The legal hold guard of V19 still runs.
6. **Objects after the commit, Object Lock respected**: the rendition and the released files are removed by jobs `archive.object-cleanup` (one per object version, idempotent). A job checks again (held document, object referenced again) and deletes the object version; if S3 Object Lock retention protects it, it is kept and the job is scheduled again for the end of the retention; an object legal hold keeps it (the next clean-up run finds it again). Destruction itself never waits for object storage.
7. **The document disappears**: a document whose versions are all destroyed (or discarded) is left out of the document list and answers 404 like a discarded one (ADR 0050); search drops destroyed versions on reindexing; the library and the validity history never contained them. Single destroyed versions stay visible in the version list of a living document as status `Destroyed` (not readable). Evidence reports keep working: a destroyed version shows its destruction (proposal, approval, retention, checksums, files) and is checked against its destruction record instead of re-reading the rendition (`destruction record`, `destroyed rendition`), so a correctly destroyed document is reported `Intact`.
8. **Compaction of working states** (periodic maintenance, `archive.maintenance` per tenant and interval, default daily): kept are the current state, the latest N (default 10 while a version is worked on, 1 afterwards), every state saved within the recent period (default 7 days) and every reviewed state: the latest state saved at or before a vote or a deviation request (what the reviewers saw). Nothing is compacted for a document under legal hold. The guard of V35 refuses deleting the current state, a reviewed state and any state of a held document; compaction deletes in ascending order so this check sees the later states. Audited per version (`document.working-states-compacted`).
9. **Content of discarded drafts** (data protection, ADR 0050): the same maintenance removes the content of drafts discarded longer than the grace period (default 30 days): working states deleted, content and values emptied once (`content_removed_at`); not under legal hold; audited (`document.discarded-content-removed`, technical actor). The version row, the number and the audit trail stay.
10. **Clean-up of orphaned objects** (same maintenance, and on request `POST /api/v1/retention/storage-cleanup` for records managers and tenant administrators, dry run by default): stored files no version links and older than the grace period (default 7 days) are deleted; then every object version under the tenant's prefixes `attachments/`, `renditions/` and `templates/` that no row references (stored files, renditions of versions not destroyed, Word files of templates) is removed if it is older than the grace period, not of a held document and not protected by Object Lock. Each run is stored with its report (`dms.storage_cleanup_run`, insert-only) and audited (`storage.cleanup-run`). The audit checkpoints (own prefix, compliance retention) are never scanned.
11. **UI**: a page "Aufbewahrung / Retention" for tenant-wide records managers (like legal holds, ADR 0047) with the destruction list (filters class, rule, text; selection; propose with reason), the proposals (approve or reject with reason, results per version, the own proposals marked as "needs a second records manager"), the rules and the storage clean-up (dry run, run, latest reports).

## Alternatives considered

| Criterion | Chosen: tombstone row, content removed | Delete version and document rows | Crypto-shredding (content encrypted per version, key deleted) |
|---|---|---|---|
| Audit trail and hash chain (ADR 0017) | Untouched; entries keep pointing at a row | Untouched, but entries name rows that no longer exist | Untouched |
| Evidence report, number ranges, foreign keys (workflow, holds, acknowledgements) | Keep working with the tombstone | Cascades through every guarded table, the report cannot show the document | Keep working |
| What is removed | Content, values, working states, files, rendition | Everything | Content unreadable, bytes stay |
| Effort | Medium (guard, one path) | High and risky | High: encryption of every content column and object, key management |
| Object Lock | Objects removed when their retention ends | Same | Not needed for content, but objects stay |

| Criterion (four eyes) | Chosen: proposal decided by a second person, enforced by the database | Two approvals on each version | Single records manager with a confirmation dialog |
|---|---|---|---|
| Business concept ("doppelt freigegeben") | Met | Met | Not met |
| Batch work on a destruction list | One proposal for many versions | One decision per version and person | – |
| Protection against bugs and direct SQL | Check constraint and guard | Same, more tables | None |

| Criterion (objects) | Chosen: jobs after the commit, retried at the end of the retention | Delete synchronously in the transaction | Leave objects to the periodic clean-up only |
|---|---|---|---|
| Consistency | Rows removed in the transaction, objects at least once afterwards | Object gone even if the transaction rolls back | Consistent |
| Object Lock | Waits for the retention to end, then removes | Fails the destruction | Removed at some later run |
| Time until bytes are gone | Seconds (or the end of the retention) | Immediate | Up to one interval |

| Criterion (compaction) | Chosen: keep current, latest N, recent and reviewed states | Keep only the final state after approval | No compaction |
|---|---|---|---|
| Review history ("which state did the reviewers see") | Kept | Lost for returned rounds | Kept |
| Storage | Bounded | Minimal | Grows with every save |
| Undo for authors | Recent states kept | Lost after approval only | Kept |

| Criterion (orphan detection) | Chosen: list the tenant's prefixes and compare with the references | Reference counting on every write | Bucket lifecycle rules |
|---|---|---|---|
| Finds objects of failed uploads and older renditions | Yes | Only what goes through the counter | No (needs tags) |
| Cost | One listing per run, one HEAD per orphan | Every write path changes | None in the application |
| Safety | Grace period, holds, Object Lock, dry run | Counter errors delete live data | Cannot see database references |

## Consequences

### Positive

- Destruction after retention is possible and provable: who proposed and approved what and why, which retention applied, which checksums were destroyed; the evidence report stays `Intact`.
- The database enforces four eyes and the tombstone shape for every database user; a legal hold blocks at proposal, at approval and in the guard.
- Object Lock is respected without blocking destruction; unlocked orphans no longer accumulate; a dry run shows what a run would remove.
- Working states and discarded drafts no longer keep content forever; the grace periods are configurable (`dms.retention.*`).

### Negative / Cost

- Audit entries of draft edits contain field changes (before and after values, ADR 0017) and destruction cannot remove them without breaking the hash chain; the document title also stays on the document row and in the audit entries as identifier. Personal data in titles or field values stays in the audit trail.
- The clean-up lists every object version under three prefixes per tenant and reads the metadata of each orphan: fine for thousands of objects, slow for millions (needs paging or an inventory then).
- Retention rules are a minimal model (one trigger, months, year end); calendar rules per jurisdiction, contract end dates and rules inherited from classes (DMS-81) are not part of it. Versions without a rule are never proposed.
- Withdrawn drafts (never approved) cannot be destroyed along the lifecycle (`Withdrawn → Archived` needs an approval); they keep their content like discarded drafts did before.
- Destroyed single versions stay in the version list of a living document (status only); there is no separate view of tombstones yet.
- The guard of `dms.document_version` is replaced again (V35); a later status needs the same care, the guard test compares V35 with the lifecycle.

## Follow-ups

- Must be done: user confirms this ADR (status → Accepted).
- Should be done: retention rules from the document class and per jurisdiction (DMS-81, F-08.1), contract-end triggers, a simulation of a rule change before activating it.
- Should be done: remove personal data from audit details of draft edits (store only field keys, or crypto-shredding of audit payloads) so destruction also covers them.
- Should be done: destruction or content removal of withdrawn drafts after a grace period, like discarded drafts.
- Could be done: paged listing / S3 inventory for the orphan clean-up of large tenants; an export of the destruction protocol as PDF/A.
- Ruled out: deleting version or document rows; destroying without a second person; deleting objects inside the destruction transaction.

## Reversibility

**medium**: Proposals, rules and clean-up runs are additive tables and can be dropped with their endpoints. The relaxed guards (content removal, deletion of working states, links and files of destroyed versions) can be tightened again by a migration. Destroyed content, compacted working states and removed objects cannot be restored; that is the purpose.
