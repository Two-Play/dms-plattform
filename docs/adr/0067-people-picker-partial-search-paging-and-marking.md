# 0067: People picker: search inside words without typo tolerance, load more up to 200 hits, mark people who cannot be picked

| Field | Value |
|---|---|
| Status | Proposed (2026-11-05, implemented in DMS-125) |
| Date | 2026-11-05 |
| Decided by | Philippe |

## Context

DMS-125 collects the follow-ups of the people picker (DMS-111, addendum of ADR 0037):

1. Search not only by the start of a word, for example by a part of a word or with typo tolerance, as far as ZITADEL allows.
2. More than 20 hits need paging or "load more".
3. People already in a group are hidden or marked.
4. Production: document the service user with read rights per tenant organization.

What ZITADEL's user search can do decides point 1. It was checked against the proto files of the version in `compose.yaml` (v4.19.5) and against a throwaway instance of the same version with two organizations:

| Question | Result on v4.19.5 |
|---|---|
| Text match methods | `EQUALS`, `STARTS_WITH`, `CONTAINS`, `ENDS_WITH`, each also `_IGNORE_CASE`. There is no fuzzy, similarity or phonetic method |
| `CONTAINS_IGNORE_CASE` on all five fields | Works: `bermei` finds "Obermeier", `ÜLL` finds "Müller", `firma` finds an email address |
| Typos and spellings | `maier` does not find "Meier". `mueller` finds "Müller" only if the user name is spelled that way |
| `%` and `_` in a word | Matched literally; `%%` finds nobody, so a word cannot list the organization |
| Paging | `offset` and `limit` in `query`, `details.totalResult` is the number of all hits, as a string. `limit` above 1,000 answers 400 |
| A page past the end, or no hits | No rows and no `totalResult` at all |
| Sorting by display name | Ignores case, so it stays stable across pages. Ties have no second sort key |
| Organization filter | Holds: a search in one organization never returned a person of the other |
| Service user without read rights in the organization | **HTTP 200 with an empty list**, not 403 |
| Service user of organization A as member of organization B | Possible; `ORG_OWNER_VIEWER` there makes B's users readable |

Constraints from ADR 0037: identity stays in ZITADEL, the platform stores no user master data, and the browser must not receive the whole directory. Its comparison ruled out loading all users for that reason.

## Decision

1. **Search inside words.** The adapter uses `TEXT_QUERY_METHOD_CONTAINS_IGNORE_CASE` on first name, last name, display name, email and user name; every word of the query must match. The minimum of two characters and the limit of four words stay. **There is no typo tolerance**, and the API and the texts do not claim any: ZITADEL cannot do it, and doing it in the platform needs a copy of the directory (see the alternatives).
2. **"Load more", not numbered pages.** `GET /api/v1/users/search` takes `offset` (default 0) and answers `{items, total, nextOffset?}` (`UserMatchPage`) instead of an array, like `NotificationPageView`. The picker loads 20 people per page. The last row of the list loads the next page, by mouse or with Enter; the focus stays in the text field and moves on to the first new hit. A footer of the open list says "20 of 45 people shown", because the list covers the status line. `total` and `nextOffset` come from ZITADEL's rows, not from the people the adapter keeps, so a row it drops cannot end the paging early. Clients must use `nextOffset` and must not count `items`.
3. **Paging ends after 200 hits of one search** (`UserApi.MaxReach`). An `offset` of 200 or more is answered with 400 `invalid_offset`, and the last reachable page has no `nextOffset`. `total` still tells the real number, and the picker asks to refine the search. A picker finds people; it is not a way to list the directory, and more than 200 hits cannot be read through anyway. This limit is a conservative choice, nothing in the business concept sets it.
4. **People who cannot be picked are marked, not hidden.** `PersonPicker` takes `excluded: {ids, label}`. The people stay in the list with `aria-disabled` and the label ("Already a member", "Already selected"); the arrow keys reach them, so screen readers read the reason, but neither Enter nor the mouse picks them. Group members come from the list the panel has loaded; `PeoplePicker` marks the people it holds. The marking happens in the browser, the search endpoint does not change for it.
5. **Service user.** One service user for the whole platform, a member with `ORG_OWNER_VIEWER` in every tenant's organization; `IAM_OWNER_VIEWER` on the instance stays possible. The steps are in [docs/operations/production.md](../operations/production.md#service-user-of-the-user-directory). Membership is part of adding a tenant, and because missing rights look like an empty result, the steps end with a check.

The cache key of `CachedUserDirectory` gets the offset. Tenant isolation does not change: the organization still comes from `dms.tenant` under row-level security, ZITADEL filters by it, and the adapter drops rows of other organizations itself.

## Alternatives considered

**Point 1: how to match.**

| Criterion | Chosen: `CONTAINS` in ZITADEL | Keep `STARTS_WITH` | Fetch all users of the organization, match fuzzily in the backend | Own index (table with `pg_trgm`, or OpenSearch) synchronized from ZITADEL |
|---|---|---|---|---|
| "bermei" finds "Obermeier" | Yes | No | Yes | Yes |
| "Maier" finds "Meier" | No | No | Yes | Yes |
| Tenant isolation | ZITADEL's filter and the adapter's check | Same | Same, but the backend holds whole organizations in memory | RLS or a tenant filter, a second place to get right |
| Master data in the platform | None | None | A cached copy | A stored copy: names and emails to keep, correct and delete, which ADR 0037 ruled out |
| Load on ZITADEL | One request per typed text and page | Same | Paging through the organization (1,000 per request) on every cache miss | Sync only |
| Effort | One constant | None | Paging, matching, ranking, memory bounds | Migration, sync job, deletion handling |
| Noise | Short queries match more people | Least | Depends on the threshold | Depends on the threshold |

**Point 2: how to page.**

| Criterion | Chosen: load more with a new response object | Numbered pages | Keep the array, ask for one more person to see if more follow | Raise the limit to e.g. 100 |
|---|---|---|---|---|
| Fits a combobox | Yes, the list grows and the keyboard stays in it | No: page controls in a popup, focus has to leave the text field | Yes | Yes |
| Can say "20 of 45" | Yes | Yes | No | No |
| Rows dropped by the adapter | `nextOffset` handles them | Pages come out short | Next offset cannot be derived | Not relevant |
| API change | Breaking for this endpoint; the picker is its only client | Same | None | None |
| Data sent per request | At most 20 people | At most 20 | At most 21 | 100 people per typed text |

**Point 3: what to do with people who cannot be picked.**

| Criterion | Chosen: mark in the browser | Hide in the browser | Hide in the backend (`exclude` parameter) |
|---|---|---|---|
| Says why somebody is not offered | Yes | No: the person seems not to exist | No |
| Paging | Unchanged | A page can come out empty, while "20 of 45" no longer fits | Offsets stay right, but ZITADEL's `notQuery` needs the ids in every request; large groups make long requests |
| Cache | Unchanged | Unchanged | One entry per group state, so the cache hardly hits |
| Works in other pickers | Same prop | Same prop | Each caller passes ids |

**Point 4: how to grant read rights.**

| Criterion | Chosen: one service user, member per organization | One service user with `IAM_OWNER_VIEWER` | One service user and token per tenant |
|---|---|---|---|
| Reach of a stolen token | Tenant organizations it was added to | Every organization and instance settings | One tenant |
| Work per new tenant | One member entry | None | New user, new token, new secret |
| Code change | None | None | Token per organization in configuration and adapter, reload at runtime |
| Forgotten step | Tenant sees no names (silent, so the steps end with a check) | Cannot happen | Tenant sees no names |

## Consequences

### Positive

- People are found by any part of a name or email address, for example the second part of a double name.
- Nobody is out of reach only because of the old limit of 20 hits; a search with more than 200 hits is told to be narrower instead of being cut silently.
- Nobody wonders why a colleague is missing from the list, and nobody adds a group member twice by accident.
- No stored data, migration or new secret; the operations steps are written down and were tried out.

### Negative / Cost

- Still no typo tolerance: "Maier" does not find "Meier", and "Mueller" finds "Müller" only through a user name spelled that way. Users must try a shorter part ("mei").
- Short queries match more people than before, and the order stays alphabetical: for "an", "Anna" no longer stands apart from "Hermann". The best hit may need "Load more" or one more letter.
- The response of `GET /api/v1/users/search` changes from an array to an object. The web client changes in the same commit; any other client of this endpoint would break, none is known.
- ZITADEL sorts by display name without a second key. If several people have the same name around a page border, one can be listed twice (the picker shows them once) or be left out of "load more". Typing more of the name or the email finds that person.
- A role holder can still read up to 200 people per query. Many different queries reach more; the limit makes listing laborious, it does not prevent it. All of them are people of the caller's own tenant.
- Marking depends on the group's member list. While it loads or if it failed, nobody is marked; adding a member twice is then handled by the backend as before. In the fallback for an unavailable directory, a typed id is not marked either.
- A forgotten membership looks like an empty directory, not like an error: names stay ids and the picker finds nobody for that tenant, with HTTP 200 from ZITADEL.

### Follow-ups

- Should be done: tell an administrator when the directory has no read rights in a tenant's organization, for example a readiness check that lists one user per organization, since ZITADEL answers 200 with no rows.
- Later, if users ask for it: hits that start with the query first, which needs a second query per page or a second sort key ZITADEL does not have.
- Later, if users ask for it: umlaut spellings (`ue` for `ü`), which could be sent as a second word variant at the cost of more `orQuery` branches.
- Ruled out for now: typo tolerance. It needs a synchronized copy of the directory, a decision against ADR 0037 that needs its own ADR.

## Reversibility

**high**: No stored data changes. Going back to prefix search is one constant, the limit of 200 is one constant, and marking is one optional prop. Only the response shape is an API change, and it can be undone together with its one client.
