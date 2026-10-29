# 0037: User directory: display names from ZITADEL's user service, resolved by the backend per tenant and cached

| Field | Value |
|---|---|
| Status | Accepted (2026-10-29, implemented in DMS-110; addendum DMS-111: people picker) |
| Date | 2026-10-29 |
| Decided by | Philippe |

## Context

The API returns people as user ids (ZITADEL `sub`): authors, owners, assignees and voters of workflow tasks, deputies, members of access groups, creators and releasers of templates, approvers in the "valid on" list, readers without acknowledgement. Until DMS-110 the UI showed these ids, which nobody can read. DMS-110 asks for names everywhere a person is shown.

Given:

- Identity lives in ZITADEL (ADR 0005); the platform stores no user master data. One ZITADEL organization per tenant (`dms.tenant.zitadel_org_id`); tokens carry the organization, the authenticator maps it to the tenant.
- ZITADEL offers the user service v2 (`POST /v2/users`, ListUsers) with filters by ids (`inUserIdsQuery`) and organization (`organizationIdQuery`), combined with AND. Reading users needs a service user with a read role (e.g. `ORG_OWNER_VIEWER` on the organization, `IAM_OWNER_VIEWER` on the instance).
- A user must only learn names of their own tenant (deny by default, business concept 2.3). Pages list many people at once; the same names repeat across pages.
- Legal records (evidence reports, ADR 0034; audit trail, ADR 0017) identify people by user id; names change, ids do not.

## Decision

1. **The backend resolves ids.** A port `UserDirectory` in module `identity` with the adapter `ZitadelUserDirectory` (JDK HTTP client, zio-json): one `POST /v2/users` per batch of at most 100 ids, filtered by the ids and by the tenant's organization; answers of users of another organization are dropped as well. Display name: the profile's display name, else given and family name, else the machine user's name, else the user name; plus the email of human users.
2. **Endpoint** `GET /api/v1/users?ids=a,b,c` (at most 200 ids) for every signed-in user: `[{id, displayName?, email?}]` in the order asked, without duplicates; ids unknown in the caller's tenant have no `displayName`. The tenant's organization comes from `dms.tenant` inside the tenant's transaction (`TenantOrganizations`, row-level security applies). An unreachable ZITADEL answers 503 `user_directory_unavailable`.
3. **Cache** (`CachedUserDirectory`): resolved and unknown users per organization for `dms.users.cacheSeconds` (default 600), at most `dms.users.cacheSize` entries (default 10,000; the oldest go first); failures are not cached.
4. **Credentials**: the personal access token of a read-only service user, from `dms.users.tokenFile` (`DMS_USER_DIRECTORY_TOKEN_FILE`) or `dms.users.token`. Without a token the directory is off: every id stays unresolved and the UI shows ids as before. For development `scripts/zitadel-setup.sh` creates the machine user `dms-directory` with `ORG_OWNER_VIEWER` and writes its token to `.local/zitadel/directory.pat` (mode 600, not committed).
5. **Frontend**: a `UserName` component shows the name with the id as tooltip; `useUser` collects the ids asked for in one tick and fetches them with one request (DataLoader style), cached by TanStack Query for ten minutes. Ids are shown while names load and when the directory is unavailable.
6. **Legal records keep ids.** The evidence report PDF and its registered JSON, the audit trail and CSV exports keep user ids: they are the stable identifier, and a name looked up later might not be the name at the time of the action.

## Alternatives considered

| Criterion | Chosen: backend resolves via ZITADEL, cached | Browser asks ZITADEL directly | Own user table synchronized from ZITADEL | Names in the API responses |
|---|---|---|---|---|
| Tenant isolation | Backend filters by the caller's organization | Every user would need read rights in ZITADEL; hard to scope | Table with RLS | Per response |
| Credentials | One read-only service token in the backend | Users' tokens would need ZITADEL management scopes | Sync job needs the same token | Same as chosen |
| Freshness | At most ten minutes old | Live | Depends on the sync (events or polling) | Live per response |
| Effort | Port, adapter, cache, one endpoint, one component | CORS and scopes in ZITADEL, no cache across users | Migration, sync job, events, deletion handling | Every endpoint and DTO changes; legal records would mix names in |
| Load on ZITADEL | One request per page and ten minutes | One per user and page | Sync only | One per response |

## Consequences

### Positive

- People are readable everywhere without changing any existing endpoint or legal record.
- One generic endpoint serves every page; batching and the cache keep requests to ZITADEL low.
- Works without configuration (ids as before), so existing setups do not break.

### Negative / Cost

- A service token with read access to users must be provisioned and rotated per environment; in a deployment with one organization per tenant it needs read access to every tenant's organization (`IAM_OWNER_VIEWER` or a member entry per organization).
- Names may be up to ten minutes old; a renamed user shows the old name until the cache expires.
- Names are not searchable in the UI (inputs still take user ids, e.g. adding a group member or a deputy).

## Follow-ups

- Must be done: provision the service user and token in the deployment (secret store, `DMS_USER_DIRECTORY_TOKEN`).
- Later: a user picker (search by name) for deputyships and group members, using ZITADEL's user search with the same scoping.
- Ruled out: names in legal records (evidence reports, audit trail, exports).

## Reversibility

**high**: The directory is one port behind one endpoint and one UI component; replacing the adapter (e.g. by a synchronized table) or switching it off changes no stored data.

## Addendum (DMS-111, 2026-10-29): people picker, search by name

**Context.** The follow-up "a user picker for deputyships and group members" (see Negative / Cost: "inputs still take user ids"): admins added group members and everyone set deputies by typing ZITADEL user ids; person fields of documents (`PersonRef`, e.g. the owner) took ids too. Nobody knows these ids. ZITADEL's ListUsers (`POST /v2/users`) also takes text queries per field (`firstNameQuery`, `lastNameQuery`, `displayNameQuery`, `emailQuery`, `userNameQuery` with `TEXT_QUERY_METHOD_STARTS_WITH_IGNORE_CASE`), `typeQuery`, `notQuery`/`stateQuery`, `orQuery`, a limit and `sortingColumn` (verified against the local ZITADEL with the `dms-directory` token). Searching must not let anyone browse the people of another tenant, nor let a user without any role in the tenant list its people.

**Decision.**

1. **Port.** `UserDirectory.search(organization, query, limit)`: active human users of the organization whose first name, last name, display name, email or user name starts with every word of the query; sorted by display name, at most `limit`. `UserQuery.parse` normalizes and bounds the query: 2 to 100 characters, at most 4 words, lower-cased. A directory that cannot search fails with `Unavailable`; `UserDirectory.none` (no token) fails with "no user directory is configured", so the UI can say why it finds nobody.
2. **Adapter.** One `POST /v2/users` with the organization (`organizationIdQuery`), `TYPE_HUMAN`, not `USER_STATE_INACTIVE`, one `orQuery` per word over the five fields, the limit and `USER_FIELD_NAME_DISPLAY_NAME`. Like the lookup, the adapter drops answers of other organizations, machine users and inactive or deleted users itself and sorts and cuts again, should ZITADEL ignore a filter. No paging: the picker shows the best hits only.
3. **Endpoint** `GET /api/v1/users/search?q=…&limit=…` → `[{id, displayName, email?}]` (`UserMatch`), `limit` 1 to 20, default 10. New authorization action `SearchUsers`: any active role in the tenant, the tenant administrator included; denied without a role (403) and for documents. The check runs in the tenant's transaction; the organization comes from `dms.tenant` under row-level security as before; ZITADEL is asked outside the transaction. 400 `invalid_query` / `invalid_limit`, 503 `user_directory_unavailable`.
4. **Cache.** `CachedUserDirectory` keeps answers per organization, normalized query and limit for `dms.users.searchCacheSeconds` (default 60; short, so new colleagues show up soon), at most 1,000 answers (the oldest go first); failures are not cached. The people found go into the name cache, so their names show without another lookup.
5. **Frontend.** `PersonPicker` ("Person auswählen"): an accessible combobox (WAI-ARIA combobox with list box; arrow keys, Enter, Escape; loading, empty and error states in a live region; German and English) that shows name and email and stores the user id. Used for group members, principal and deputy of deputyships and person fields of the document form (single; `PeoplePicker` for multiple). When the directory is unavailable the typed text can be taken as user id, as before.

**Alternatives considered.**

| Criterion | Chosen: search through ZITADEL per request, short cache | Load all users of the organization once and filter in the browser | Own user table synchronized from ZITADEL with SQL search | Search only among people known to the platform (role assignments, group members) |
|---|---|---|---|---|
| Tenant isolation | Backend filters by the caller's organization (and checks the answer) | Same, but the whole directory goes to every browser | RLS on the table | RLS on existing tables |
| Who can be found | Every active person of the organization | Same | Same, after the next sync | Only people who already have a role or membership; new colleagues cannot be named as deputy |
| Data sent to the browser | At most 20 people per query | The whole organization (names and emails) | At most 20 | At most 20 |
| Effort | One port method, adapter, cache, endpoint, component | Paging through ZITADEL, large payloads | Migration, sync job, deletion handling (ADR 0037 ruled it out) | Joins over several modules, still needs names from ZITADEL |
| Load on ZITADEL | One request per typed prefix and minute | One large request per page view | Sync only | Lookup of names only |

**Consequences.**

- Positive: nobody types user ids any more; existing endpoints, stored values and legal records are unchanged (ids are stored as before); a tenant still learns only its own people, and only callers with a role may search.
- Negative: every search is a request to ZITADEL (debounced in the UI, cached a minute); the read-only service token now also serves searches, so it must be able to list users of every tenant's organization (unchanged requirement); results are limited to 20 without paging; prefix search only (no fuzzy matching, "Mayer" does not find "Meier"). Newly added people show up after at most a minute.
- Follow-ups: optional "exclude" of people already in the list (e.g. current group members); fuzzy search if users ask for it.
- Reversibility: **high**. The search is one port method behind one endpoint and one component; removing it brings back the id fields.
