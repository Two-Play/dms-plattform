# 0043: Library of valid documents: served from PostgreSQL by a dedicated endpoint, filtered with the reading policy, facets computed per request

| Field | Value |
|---|---|
| Status | Proposed |
| Date | 2026-10-29 |
| Decided by | Philippe |

## Context

DMS-119 ("Bibliothek"): employees want to browse the instructions, policies and other published documents that are valid now (e.g. all "Zentralanweisungen") without knowing a title. Acceptance criteria:

- a separate "Library" area that lists only currently valid versions the user may read;
- filters by document class, issuing unit, tags and confidentiality, a free-text filter, sorting by title, document number, valid from and last change;
- cards or a list with title, document number (DMS-113), index, issuing unit, valid from and a short description, linking to the document view (ADR 0039);
- markers "new" and "changed since …" and the user's open acknowledgements;
- shareable filters in the URL, German and English, accessible.

Given:

- Validity is decided in PostgreSQL (ADR 0021, 0026): a version is valid while it is `Published` or `ReviewDue`, at most one per document and validity scope; scheduled publications switch at 00:00 of the valid-from date in the tenant's time zone. `DocumentService.currentlyValid` already reads every valid version with its document, class path, field values and acknowledgement requirement in one query (compliance cockpit, ADR 0036).
- Reading is decided by `Policy.decide` with `ReadDocument` (for valid versions the same as `ReadPublished`): a business role scoped to unit and class, confidential versions only for author, owner and members of an allowed group (ADR 0018, 0032).
- The search index (ADR 0029) holds valid and superseded versions with a permission filter inside the OpenSearch query, facets for class, status and language, and is updated within seconds through the outbox and a 30-second synchronization. It is derived data and may be unavailable (the search page then shows "not available").
- The "valid at" list (DMS-53) and the stable addresses (DMS-58) deliberately read PostgreSQL, not the index.
- ADR 0039 defines the issuing unit ("herausgebende Stelle") as the core field `organizationalUnit` and the index as the version number `major.minor`.
- There is no core field for a short description; tenants define their fields per class (ADR 0019). `tags` is a core field (multiple text).
- Acknowledgements (ADR 0027): a valid version that requires acknowledgement asks every reader once; `AcknowledgementService` reads a user's acknowledgements.
- Tenants hold hundreds to a few thousand valid documents; the library shows one entry per document and validity scope.

## Decision

1. **Dedicated endpoint on PostgreSQL**: `GET /api/v1/library` reads the valid versions with `DocumentService.currentlyValid` (extended by validity scope, document number and the first publication of the document), keeps those the caller may read with `ReadDocument` (the same decision as opening the document, `DocumentApi.versionResource` like the "valid at" list), and filters, counts, sorts and pages them in the application, inside one `Db.transact(tenant)`. The search index is not involved. No migration.
2. **Filters** (repeat or comma-separate list parameters; values within a filter are alternatives, filters combine with AND): `class` (a class includes its subclasses), `unit` (organizational unit), `tag`, `confidentiality`, `q` (every word must occur, case-insensitive, in title, document number, description, class key or label, unit, tags or scope), `changed=true` (only new or changed since `since`), `pending=true` (only open acknowledgements of the caller). **Facets** (`class` with class labels, `unit`, `tag`, `confidentiality`, plus the counts `changed` and `pending`) are disjunctive: each applies every filter except its own, so further values of the same facet stay selectable. Totals and facets count only readable versions.
3. **Sorting**: `sort=title` (German collation, default), `number` (natural order, documents without number last), `validFrom` (valid-from date, else the day it became valid) and `lastChanged` (publication of the valid version); `order=asc|desc` with ascending default for title and number and descending for dates; ties by title. Pages of up to 200 (default 24).
4. **Entries**: title (the version's `title` field as approved, else the document title), document number, version label (index), status, class path and label, issuing unit (`organizationalUnit`, ADR 0039), confidentiality, tags, scope, short description, valid from, valid since, published at, `isNew`, `changed`, `requiresAcknowledgement`, `acknowledgementPending`.
5. **Short description**: the optional tenant field with the conventional key `summary` (text), shown on one line and cut to 300 characters at a word boundary; empty if the class has no such field. No new core field.
6. **"New" and "changed since"**: `since` (date, tenant time zone; default 30 days ago) sets the boundary 00:00 of that day. A document is *new* if its first version was published at or after the boundary; a version is *changed* if the document is not new and the version became valid (00:00 of its valid-from date, or its publication if later) at or after the boundary. The UI lets the user pick the date; it is part of the URL.
7. **Open acknowledgements**: `acknowledgementPending` if the version requires acknowledgement and the caller has not acknowledged it; being listed means the caller may read it, i.e. belongs to its readers (ADR 0027). The card links to the document view, where the acknowledgement panel records the opening and the confirmation.
8. **Frontend**: navigation entry "Library" / "Bibliothek" for everyone (after "Documents"), route `/library` with all filters, sort, page, view mode (cards or list) and `since` in the URL; shared page components of DMS-109 for loading, empty and error states.

## Alternatives considered

| Criterion | Chosen: PostgreSQL endpoint, policy filter, facets in memory | Search index (OpenSearch query with permission filter and aggregations) | Client-side: frontend combines "valid at today" and the search | SQL with permission predicates and `GROUP BY` facets |
|---|---|---|---|---|
| Correct validity at 00:00 switches and right after publication | Yes, authoritative store at request time | Lags by seconds to one sync interval; entries of withdrawn versions linger until reindexed | Yes for "valid at", search lags | Yes |
| Permission parity with opening the document | Same `Policy.decide` call | Mirror of the policy (`AccessFilter`, parity-tested) | Partly: two sources | Policy would have to be duplicated in SQL (unit/class scopes, groups, time windows) |
| Availability | Needs only PostgreSQL | Library down when OpenSearch is down | Needs both | Needs only PostgreSQL |
| Full-text over content | No: free text over title, number, description, class, unit, tags | Yes, with stemming and snippets | Yes | No |
| Facets for unit, tags, confidentiality | Computed in memory, disjunctive | Need new aggregations and mapped keywords (tags, unit are indexed; would need index mapping work) | Have to be computed in the browser over all pages | Possible, complex queries |
| Acknowledgement state and "new/changed" | Same transaction (acknowledgements, first publication) | Need a second query to PostgreSQL per page | Extra requests | Possible |
| Cost with many documents | Reads all valid versions per request (O(n), a few thousand rows and policy checks: milliseconds to low hundreds of ms) | Scales to millions | Transfers everything to the browser | Scales best |
| Effort | Low, reuses `currentlyValid` and the policy | Medium: mapping, aggregations, second query | Medium, duplicated logic in the UI | High |

Search for content stays on the search page (ADR 0029); the library is for browsing what is valid, and the two complement each other.

## Consequences

### Positive

- The library always matches what the document page and the stable addresses show: same validity source, same reading decision, no lag.
- No new infrastructure, table, migration or index mapping; works when OpenSearch is unavailable.
- Facets, totals, markers and acknowledgement state come from one transaction and contain nothing the caller may not read.
- Filters in the URL make library views shareable (e.g. "all central instructions of HR changed since 1 October").

### Negative / Cost

- Each request reads all valid versions of the tenant and decides the policy for each (no pagination in SQL). Fine for thousands of valid documents; for much larger tenants caching or moving to the index is needed.
- The free-text filter does not search the content; users have to switch to the search for that.
- The short description depends on a tenant field with the conventional key `summary`; classes without it show no description.
- `currentlyValid` reads the field values of all valid versions, more data than the compliance cockpit strictly needs.

## Follow-ups

- Done (DMS-120, see addendum): demo data — the seed publishes four central instructions ("Zentralanweisung") through the approval workflow; the demo class has the tenant field `summary`.
- Later: optional "valid on" date for the library (reuse `validAt`); a per-user "last visit" as default for `since`; serving the library from the index (`status` = valid, new aggregations for unit, tags and confidentiality) if tenants grow beyond tens of thousands of valid documents; a core field for a short description if tenants need it everywhere.
- Ruled out: a separate library table or materialized view (would duplicate validity and need invalidation at scheduled publications).

## Reversibility

**high**: One additive read endpoint and an extended internal query; no stored state. Moving the endpoint to the search index keeps the API contract (`LibraryView`) and only changes the app module.

## Addendum (DMS-120, 2026-10-29): demo data through the real workflow; the short description stays a class field

**Demo data.** `scripts/dev_setup.py seed` creates the document class `central-instruction` (label "Zentralanweisung") with the tenant fields `summary` (label "Kurzbeschreibung", text up to 500 characters, required from review) and `appliesTo`, a one-step approval workflow (role Approver), the number range `central-instructions` (`ZA-{YYYY}-{NNN}`, yearly reset), the released template "Zentralanweisung" (locked identification with number, issuing unit, valid-from date and short description; editable scope, rules and responsibilities; locked entry into force) and four instructions with German content, tags and different issuing units (IT-Sicherheit, Finanzen, Datenschutz, Personal) whose valid-from dates lie in the past. Publishing uses only the regular API and no dev-only path: `author` creates, fills and submits; `approver` signs in afresh through ZITADEL (session with password check, authorization code flow) and sends the ID token in `X-DMS-Id-Token`, which satisfies the step-up check of approval votes (ADR 0024, addendum DMS-86) exactly as the web UI does; the rendering job seals the rendition and the publication job publishes the versions. The script waits up to three minutes for the publication and continues an interrupted run from each document's status (idempotent by title). Production checks are unchanged, so no configuration switch is needed. It also uploads a Word template (a minimal DOCX with `dms:` content controls, DMS-115) and creates a draft file document from it.

**Short description: class field `summary` (kept) or a core field.**

| Criterion | Kept: tenant field with the conventional key `summary` | New core field `summary` |
|---|---|---|
| Classes that need no description (contracts, records) | Do not bind it; no empty field in their forms | Every class gets it (core fields are in every profile) |
| Existing tenant fields named `summary` | Work as they are; the library reads them | Clash with the core key: tenants could no longer create or keep their own definition (the problem ADR 0040 notes for `documentNumber`); needs a data migration of their values |
| Label, length, requiredness per class | Chosen by the tenant per class | Fixed by the platform; classes may only tighten requiredness |
| Library and templates | Read by key, no change | Same |
| Effort | None | CoreFields, profiles of all tenants, migration of existing values, tests |

Decision: keep the class field. It is not clearly better to make it a core field: the library already works with the conventional key, most document types other than instructions and policies do not need a description, and a core key would collide with the tenant fields that already use it. Reconsider if tenants ask for a description on every document type; then a migration has to move existing `summary` values into the core field.
