# 0027: Acknowledgements: readers of a valid version acknowledge it after an explicit opening, insert-only

| Field | Value |
|---|---|
| Status | Accepted (2026-10-28, implemented in DMS-47) |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

US-06.2 (DMS-47): employees confirm new policies so that their acknowledgement is proven. Acknowledging is only possible after the document was opened; it is stored with version and timestamp; each employee sees which acknowledgements are open (the task inbox of DMS-48 shows them).

Given: whether a version asks for acknowledgement is decided per version on creation (`document_version.requires_acknowledgement`, true for major versions, configurable for minor ones; ADR 0026). Versions become valid (Published, ReviewDue) through the publication job and are superseded by their successor in the same transaction (ADR 0026). Reading is decided by the in-app policy (`ReadPublished`/`ReadDocument`, ADR 0018) from scoped role assignments (organizational unit, document class subtree) and confidentiality groups. Every change is audited in the hash chain (ADR 0017); events go through the outbox (ADR 0010). There is no distribution list or audience configuration per document class yet, and the frontend shows a document version on its own page (DMS-90); the archive rendition can be downloaded (DMS-44). Proof has to hold against later edits: the record must name the exact version and must not change.

## Decision

1. **Audience.** Who must acknowledge a valid version is everyone who may read it: `Policy.decide(ReadPublished)` for the caller, i.e. every active business role (not the tenant administrator alone) whose assignment covers the document's class path and organizational unit, with the confidentiality rules. It is evaluated when the caller lists their acknowledgements, not stored per version.
2. **Opened.** The client records the opening explicitly with `POST /api/v1/documents/{id}/versions/{versionId}/opened` when it displays the version (the document page does so on display). The first opening per reader and version is stored in `dms.document_version_opened` (insert-only, `on conflict do nothing`, so idempotent) and audited once (`document.opened`). Only valid versions that require acknowledgement are recorded; for others the call does nothing. GET requests (document, versions, rendition) stay free of side effects.
3. **Acknowledgement.** `POST .../acknowledgement` inserts one row per reader and version into `dms.document_acknowledgement` with document, version id, version label, reader, the first opening time and the acknowledgement time; audited (`document.acknowledged`) and published to the outbox (`document.acknowledged`). It needs `ReadDocument` on the version (403), an earlier opening (409 `not_opened`), no earlier acknowledgement (409 `already_acknowledged`), and a version that is valid and requires acknowledgement (422), checked under a share lock on the version against a concurrent supersession.
4. **Database guards (migration V14).** Both tables are insert-only (update/delete triggers and insert-only grants for `dms_app`) with RLS on `dms.current_tenant()`. Insert triggers check that the version is valid and requires acknowledgement, that the label is the version's number, and that the acknowledgement copies the reader's recorded opening (so it cannot precede it). `document_version.published_at` is set by a trigger on Approved → Published and cannot be set otherwise; existing rows are backfilled from the audit trail.
5. **Superseded versions.** Open acknowledgements are derived, not stored: valid versions that require acknowledgement and that the reader may read and has not acknowledged. A superseded version drops out of the open list at once; its successor asks on its own if it requires acknowledgement, and an opening of the old version does not count for the new one. Done acknowledgements stay listed with the version's current status.
6. **API.** `GET /api/v1/acknowledgements?status=open|done` returns `{documentId, versionId, title, versionLabel, classPath, validFrom, publishedAt, status, opened, openedAt, acknowledgedAt}` for the caller; `GET .../acknowledgement` returns the state of one version for the document page.

## Alternatives considered

| Criterion | Chosen: readers per policy, explicit opening call | Audience configured per document class (distribution list) | Opening recorded implicitly on GET of the version or rendition |
|---|---|---|---|
| Effort now | Small: reuses the policy and role scopes | Configuration model, admin UI, migration | Small |
| Who must acknowledge | Everyone who can read; cannot exclude readers who need not acknowledge | Exact per class, e.g. only one department | (independent of audience) |
| Consistency with access | Always consistent: who can read must acknowledge, nobody else | Lists can drift from read access | – |
| Reliability of "opened" | The UI states that it displayed the version; one deliberate request | – | Prefetching, caching, list views or scripts would create false proof; GET would get a side effect |
| Reporting cost | Open items computed per request (policy per version) | Can be precomputed | – |

Storing open acknowledgements as rows per reader (materialized at publication) was also considered: it makes reporting by owners cheap, but needs a fan-out job, has to follow role changes and supersessions, and duplicates the policy. Deferred until reporting needs it.

## Consequences

### Positive

- Proof is self-contained and immutable: version id and label, opening and acknowledgement time, reader; plus the hash-chained audit entries.
- No configuration needed: new readers (new role assignments) see open acknowledgements immediately; supersession needs no clean-up.
- The database rejects acknowledgements without opening, for invalid versions or with a wrong label, for every writer.

### Negative / Cost

- The open list evaluates the policy per valid version that requires acknowledgement in the tenant and loads each document (N+1). Fine for hundreds of policies, not for tens of thousands.
- Every business role in scope must acknowledge; a policy that only concerns one group needs a narrower class or organizational unit scope.
- "Opened" is what the client reports; it proves the reader displayed the page, not that they read the text.
- If a version requiring acknowledgement is superseded by a minor version that does not require it, readers who had not acknowledged yet are no longer asked (the minor version is an editorial correction of the same content).

## Follow-ups

- Must be done: reporting for owners (who has / has not acknowledged a version) and reminders/escalation through notifications (DMS-47 follow-up).
- Must be done: decide whether acknowledgements of a major version carry over to a following minor version that does not require acknowledgement (see negative consequence).
- Later: audience per document class (distribution lists) or materialized open items if the computed list becomes too slow; optional step-up for acknowledging.
- Ruled out: recording openings on GET requests.

## Reversibility

**medium**: The tables are append-only proof and stay; changing the audience rule (e.g. a class-level distribution list) only changes how the open list is computed. Recording openings differently (e.g. by rendition download) adds a second writer to the same table.
