# 0029: Search index: one OpenSearch index per tenant, permission filter in the query, outbox plus periodic synchronization

| Field | Value |
|---|---|
| Status | Accepted (addendum DMS-101) |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

Sprint 9 brings full-text search over published documents (S-09.a / DMS-51: published versions indexed via the outbox, the index rebuildable from the database, authorized groups indexed; US-09.2 / DMS-52: hit list, hit count, facet counts and snippets contain nothing from documents the user may not read; permission changes take effect within 60 seconds).

Given:

- ADR 0009: OpenSearch is the search index, derived from PostgreSQL and object storage, never a source of business decisions, rebuildable at any time.
- ADR 0014: tenants share one database with row-level security; OpenSearch documents need a tenant prefix or field.
- ADR 0018: authorization is the pure function `Policy.decide`; role assignments are scoped to an organizational unit and a document class subtree, valid in a time window; confidential and strictly confidential documents are visible to author, owner and allowed groups only (groups = ZITADEL project roles of the token). The ADR names the cost: lists and search must apply the same rules separately.
- ADR 0019: field values are typed (text, number, amount, date, selection, yes/no, person, document reference) and should be indexed with their type.
- ADR 0010: transactional outbox and job queue in PostgreSQL; the outbox had no relay running yet.
- ADR 0021, 0026: a document has versions; at most one valid version (Published or ReviewDue) per scope; publication supersedes the valid version of the same scope; frozen versions never change.
- DMS-53 (same sprint) searches "valid at a date" on PostgreSQL, so the index does not need the full validity history.

## Decision

1. **One index per tenant behind an alias.** Alias `<prefix>-<tenant id>` (prefix `dms-documents`), physical indices `<alias>-initial` (created on first write) and `<alias>-<timestamp>` (created by a rebuild). Every entry also carries `tenantId`, and the permission filter requires it (defense in depth). One entry per document version, id = version id.
2. **What is indexed:** versions in status Published, ReviewDue (valid) and Superseded (published history). Drafts, review, approval and approved-but-not-yet-valid versions are not indexed; withdrawn, archived and destroyed versions are removed. Searches cover the valid versions unless `status` asks for others.
3. **Fields and mapping:** `title` and `text` analyzed three times (standard, `german`, `english`; queries search all, title boosted); `text` is the content resolved with the version's values (templates `Resolver`, plain text; clause references stay references) plus the field values as `label: value` lines. Keywords: class key and class path (all ancestors, so a class filter covers its subtree), status, scope, language, organizational unit, confidentiality, author, owner, allowed groups, version label. Dates: valid from/until, published at. Field values typed under `metadata.<kind>.<key>` (`keyword`, `number`, `amount.value/currency`, `date`, `flag`) with dynamic templates. Top-level mapping is strict.
4. **Permission filter in the query, never post-filtering.** `AccessFilter.forSubject(subject, now)` builds a bool filter from the caller's role assignments read from the database at query time and the groups of the token; it is part of `query.bool.filter`, so total, aggregations, highlights and pages only see readable versions. Mapping of `Policy` for reading an indexed version (`ReadPublished` for valid versions, `ReadDocument` for valid and superseded ones, which grant the same callers):

   | Policy rule | Filter |
   |---|---|
   | resource in the subject's tenant | `tenantId = subject.tenant` |
   | an assignment is active now (`validFrom <= now < validUntil`) and its scope covers the resource (`ou` equal if set, class key on the class path if set) | OR over the active assignments: `organizationalUnit = a.ou` AND `classPath = a.class` (missing parts omitted; an unscoped assignment matches all) |
   | `ReadPublished`/`ReadDocument` (Superseded) need a role other than `TenantAdmin` | `TenantAdmin` assignments are left out; no assignment left: `match_none` |
   | Confidential / StrictlyConfidential: author, owner or an allowed group | `confidentiality in (Public, Internal)` OR `authorId = user` OR `ownerId = user` OR `allowedGroups in groups` |

   The attributes come from one function for both sides: `dms.authz.DocumentResource` builds the `Resource` for the document API and the indexer, `AccessFilter.attributes` takes the indexed fields from it. Parity is tested property-style (`AccessFilterSpec`: 10,000 generated subject/version pairs with roles, scopes, validity boundaries, groups, tenants, confidentiality and statuses, compared with `Policy.decide`) and against OpenSearch (`SearchIndexSpec`: 90 generated versions × 60 generated subjects, the hits of OpenSearch equal the versions the policy permits).
5. **Allowed groups** come from the optional field `allowedGroups` (tenant field, text or selection, usually multiple) of the version; the document API now honours it as well (it was never filled before).
6. **Keeping the index current:**
   - Outbox: the `OutboxRelay` now runs in every instance; its subscriber `SearchJobs.deliver` turns `document.status-changed` events from approval on (Published, ReviewDue, Superseded, Withdrawn, Archived, Destroyed) into a job `search.index-document` (idempotency key = event id). The job loads the document's indexable versions, writes them with `version_type=external_gte` and version = the version's `updated_at` in microseconds (an older state never replaces a newer one), refreshes, and deletes the document's other entries.
   - Synchronization: every `dms.search.syncIntervalSeconds` (30 s) each instance enqueues `search.sync` per tenant (idempotency key per interval, so one job per tenant and interval cluster-wide). It reindexes every document whose row or versions changed since the previous run minus an overlap of 2 minutes (for late commits), and stores the time in `dms.search_sync_state` (migration V16, RLS). This catches changes without an outbox event (e.g. a new owner set through a draft) and lost jobs; without a row it indexes everything (first start).
   - Role assignment and group changes need no reindexing: the filter is computed per request. Document-side changes reach the index within seconds (relay and job runner poll every 2 s) and at the latest after one sync interval: well within the 60 s of DMS-52.
7. **Rebuild:** `POST /api/v1/admin/search/rebuild` (`Configure` on the new configuration area `Search`, i.e. tenant administrators) and `sbt "app/runMain dms.app.SearchRebuild [tenant ...]"` (`bin/search-rebuild` of the staged app). A rebuild creates a new index, fills it from the database in batches, refreshes, moves the alias atomically (`_aliases` remove + add), deletes the previous index and sets the sync time to the start of the rebuild, so changes made meanwhile (written to the old index) are caught up by the next synchronization. `scripts/dev-setup.sh` rebuilds after seeding.
8. **API contract** `GET /api/v1/search?q=&class=&status=&lang=&page=1&size=20` → `{ total, page, size, hits: [{ documentId, versionId, title, versionLabel, classPath, status, validFrom, snippet, validUntil?, language?, organizationalUnit?, score? }], facets: { class: [{ value, count }], status: [...], language: [...] } }`. `q` uses the simple query syntax (AND by default, "phrases", prefix\*, -excluded), empty `q` lists by filter sorted by title. List parameters repeat or take commas. `snippet` is plain text with highlights enclosed in `<mark>`/`</mark>` and nothing escaped: clients split at the markers and render the parts as text (the indexer removes literal markers and the private-use highlight characters from the text, so markers always enclose matches). The status facet ignores the status filter (`post_filter`), class and language facets follow all filters. Page × size is limited to 10,000 (OpenSearch result window), size to 100.
9. **Operations:** OpenSearch 3.8.0 pinned by digest in `compose.yaml` (port 9200, single node, security plugin disabled, health check) and in the tests (Testcontainers, same image). Configuration `dms.search.*` (`DMS_SEARCH_URL`, prefix, replicas, timeout, sync interval, optional basic authentication). OpenSearch is never called inside a database transaction.

## Alternatives considered

| Criterion | Chosen: index per tenant + query filter from DB assignments | One shared index with tenant field and routing | Permission lists in the index (user ids / "readers" field per entry) | Post-filtering hits with `Policy` |
|---|---|---|---|---|
| Tenant isolation | Separate index per tenant plus tenant term | Only the tenant term (a missing filter leaks) | as chosen | as chosen |
| Rebuild / export / delete of a tenant | One alias, one index | Delete-by-query in a shared index | as chosen | as chosen |
| Role assignment change takes effect | Immediately (next query) | Immediately | Reindex every affected entry (many entries per assignment change) | Immediately |
| Counts, facets, snippets free of unreadable documents | Yes, filter is part of the query | Yes | Yes | No: totals and facets would count unreadable versions, or need over-fetching and recounting |
| Duplicated authorization logic | Filter mirrors `Policy`, parity tests | as chosen | Expansion of assignments to users, harder to test | None |
| Shards with many small tenants | One shard per tenant; does not scale to thousands of tenants | Best | as chosen | as chosen |
| Complexity | Medium | Medium | High | Low, but incorrect |

Other choices considered: indexing only the current valid versions (history not searchable; DMS-53 covers "valid at" on PostgreSQL, but superseded versions stay useful for readers and cost little); the outbox relay calling OpenSearch directly (an OpenSearch outage would block delivery for all future subscribers; jobs keep failures per subscriber); per-language indices or a language-specific analyzer chosen by the version's language (more indices, and mixed-language documents get lost; multi-fields are simpler and search both); a version from a database sequence instead of `updated_at` (needs a schema change of `document_version`).

## Consequences

### Positive

- Search results never contain, count or quote versions the caller may not read; the parity with `Policy` is tested for generated cases, also against the real engine.
- Role assignment changes act immediately; document changes in seconds, bounded by the sync interval.
- The index can be deleted and rebuilt at any time without downtime of the search.
- A tenant's index is self-contained (export, deletion, rebuild per tenant).

### Negative / Cost

- The filter duplicates the read rules of `Policy`; a change of those rules must change `AccessFilter` too (the parity tests fail otherwise; `AccessFilterSpec` generates over all roles and statuses).
- Owner changes of a document leave the version's `updated_at` unchanged, so two concurrent jobs of the same document can write in the wrong order; the synchronization repairs it within one interval.
- The outbox relay now marks every event delivered after offering it to the subscribers; future consumers (notifications) must register as subscribers and will not see events delivered before they existed.
- One index (shard) per tenant; with many small tenants a shared index with routing becomes necessary (reversible: only `IndexLayout` and the alias names change).
- The initial synchronization and a rebuild load and resolve every document version; fine for the volumes of the MVP, to be measured in the load test (T-00.13).
- Clause references are indexed as `[clause key vN]`, not with the clause text.

## Follow-ups

- Must be done: index the text of referenced clauses; OCR / text of uploaded PDFs (business concept 8.1) once uploads exist.
- Must be done: notifications register as outbox subscribers (ADR 0024 follow-up).
- Must be done: production OpenSearch with security plugin (TLS, basic auth or IAM), replicas ≥ 1, snapshots not needed (rebuildable).
- Should: a search UI (DMS-53 frontend) and "valid at" search in the index if PostgreSQL becomes too slow.
- Ruled out: post-filtering of hits; the index as a source of business decisions (ADR 0009).

## Reversibility

**low**: The index is derived. Changing layout, analyzers or the tenant model means a code change and a rebuild per tenant. Replacing OpenSearch touches only module `search` (`OpenSearch`, `IndexLayout`, `SearchRequest`); the API contract and the permission model stay.

## Addendum (DMS-101, 2026-10-28): allowed groups from the core field and platform groups

Item 5 of the decision changes: allowed groups come from the core field `allowedGroups` (ADR 0032), and the caller's groups are their current memberships in the tenant's access groups instead of ZITADEL project roles. The index layout and the filter are unchanged: the indexer takes the groups of a version through `DocumentResource` as before, so new groups only reach the index with a new version (the field is frozen with the version), while membership changes take effect at query time.
