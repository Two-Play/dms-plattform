# 0037: User directory: display names from ZITADEL's user service, resolved by the backend per tenant and cached

| Field | Value |
|---|---|
| Status | Accepted (2026-10-29, implemented in DMS-110) |
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
