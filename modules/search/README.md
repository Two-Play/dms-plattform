# Module `search`

Indexing and permission-filtered full-text search over published documents (business concept 8.1; DMS-51, DMS-52; ADR 0009, 0029).

Layout: `api` holds public types and interfaces, `core` the implementation. Other modules may only depend on `api`.

## `search-api` (`dms.search`)

- `SearchDocument`: one indexed document version (title, plain text, class path, status, dates, scope, language, typed field values, `AccessAttributes`, `changedAt`). Indexed statuses: Published, ReviewDue, Superseded; searches default to Published and ReviewDue.
- `SearchSource`: the port to the database; implemented by the application (`dms.app.SearchDocuments`) with the documents and templates modules. Runs inside `Db.transact(tenant)`.
- `SearchQuery`, `SearchResult`, `SearchHit`, `Facets`, `SearchError`, `RebuildResult`.

## `search-core` (`dms.search.core`)

- `OpenSearch`: minimal REST client on the JDK HTTP client (`OpenSearchConfig`: URL, index prefix, replicas, timeout, optional basic auth).
- `IndexLayout`: alias `<prefix>-<tenant id>` per tenant, settings and strict mapping (title and text with standard, German and English analyzers; typed field values under `metadata.<kind>.<key>`), entries, snippets.
- `AccessFilter`: the permission filter built from a `dms.authz.Subject` (role assignments active now, scopes, groups), mirroring `Policy` for reading indexed versions; renders as OpenSearch query and evaluates in memory (tests).
- `SearchRequest`: the query (simple query string over title and text, filters, `post_filter` for status, facets, highlighting) and the reading of the answer.
- `SearchIndex`: `search`, `indexDocuments` (external version = last change, removal of other entries), `sync` (documents changed since the previous run, state in `dms.search_sync_state`, migration V16), `rebuild` (new index, alias moved atomically).
- `SearchJobs`: outbox subscriber (`document.status-changed` → job `search.index-document`), job `search.sync` per tenant every `syncInterval`.

HTTP (module `app`, `SearchApi`):

| Method | Path | Purpose | Authorization |
|---|---|---|---|
| GET | `/api/v1/search?q=&class=&status=&lang=&page=&size=` | Search; hits, total, facets and snippets only over readable versions | signed in; filter per caller |
| POST | `/api/v1/admin/search/rebuild` | Rebuild the tenant's index from the database | `Configure` on `Search` (tenant administrators) |

`snippet` is plain text with matches in `<mark>`/`</mark>`, nothing escaped. Errors: `400 invalid` (page, size, status), `503 search_unavailable` (OpenSearch not reachable). Command line: `sbt "app/runMain dms.app.SearchRebuild [tenant id ...]"`.

Tests start OpenSearch (same image as `compose.yaml`) and PostgreSQL with Testcontainers; `OpenSearchContainer` is shared with the application tests.
