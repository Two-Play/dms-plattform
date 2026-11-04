# 0060: Purpose and reason for viewing named people travel in the request body of a POST, not in the URL

| Field | Value |
|---|---|
| Status | Proposed |
| Date | 2026-11-04 |
| Decided by | Philippe |

## Context

DMS-144 (part of DMS-108, a release blocker of the MVP acceptance, DMS-60). Two endpoints show the names of readers who have not acknowledged a version, purpose-limited by business concept 10.2 ([ADR 0036](0036-compliance-cockpit.md), [ADR 0048](0048-acknowledgement-recipients-overview-and-reminders.md)): `GET /api/v1/compliance/acknowledgements/{documentId}/{versionId}/open` and `GET /api/v1/documents/{id}/versions/{versionId}/acknowledgement-recipients`. Both took `purpose` (`escalation` or `reminder`) and `reason` (free text, up to 500 characters, "why the names are needed") as query parameters.

A URL is written down in many places that nobody treats as personal data storage: the access log of the reverse proxy (the production checklist requires one in front of the application), browser history, proxy and monitoring tools, `Referer` headers. A free-text reason like "performance talk with the team of X" in such a log defeats the purpose limitation: the log would hold the stated purpose next to the identity of the caller and the document, outside the audit trail's protection and retention.

What the code does today:

- The application itself writes no access log and no request log (no request-logging middleware in `Main`; `logback.xml` carries application messages only, plain or JSON with `DMS_LOG_APPENDER=JSON` from DMS-60). Neither the JSON log nor any `ZIO.log*` call prints a URL, a query string or these values; the production checklist says logs hold ids, not names. The leak is therefore in what sits in front of the application and in the client, which only a change of the request can close.
- The evidence report endpoints (ADR 0034) have no purpose or reason parameter: the report is created with `POST` and fetched by id with plain `GET` links (JSON and PDF), nothing personal travels in their URLs. They are unchanged, and so is every download that relies on a plain link. The CSV export of the recipient list is built in the browser from the data already loaded.
- Other query parameters of the two endpoints: `status` (`open` or `done`) on the recipient list, `unit`, `owner` and `document` on the cockpit lists. They are filters without personal content and stay.

A `GET` with a body is not an option: RFC 9110 gives it no defined meaning, caches, proxies and clients (browsers' `fetch` among them) drop or reject it.

## Decision

1. **`POST` with a JSON body** `{"purpose": ..., "reason": ...}` on the same paths. Both fields are optional in the JSON so that a missing one still answers 403 `purpose_required` (as before) instead of a decoding error; an unreadable or missing body answers 400.
2. **The query parameters `purpose` and `reason` are gone.** They are not read; sent in a URL they are ignored, and the request is then refused like one without purpose (403 `purpose_required`). Tests prove it for both endpoints. `status` stays a query parameter.
3. **`POST` is the honest verb here:** the request is not safe in the HTTP sense, every successful call writes an audit entry (`compliance.acknowledgement-persons-viewed`) in the same transaction as the read. It is also never cached, which the frontend wants for names anyway.
4. The audit entry is unchanged: actor, document, version, purpose and number of names in `details`, the reason in `reason`. The purpose is still in the audit trail, in no URL.
5. The OpenAPI document and the generated `frontend/src/api/schema.d.ts` carry the new shape; the frontend calls `client.POST(...)` with `body`.

## Alternatives considered

| Criterion | Chosen: POST on the same path, JSON body | Purpose and reason in headers (`X-Purpose`) | A separate "request access" resource: `POST .../access-requests` returns a token, `GET ...?token=` shows the names | `QUERY` method (RFC 9110 successor draft) | `GET` with body |
|---|---|---|---|---|---|
| Purpose and reason out of URLs and access logs | Yes | Yes, but proxies often log chosen headers; free text in a header is awkward | Yes for the content, but the token is in the URL (the access is still linkable) | Yes | Yes |
| Works with the toolchain (tapir, zio-http, `fetch`, openapi-typescript, reverse proxies) | Yes, nothing new | Yes, but limited to ASCII and about 8 KB, hides the reason in a header | Yes | No: not supported by `fetch` in all browsers, nor by the generators or many proxies | No: undefined semantics, dropped by clients and proxies |
| HTTP semantics | A non-safe request that writes an audit entry: correct | `GET` that writes: wrong | Correct, but two round trips | Safe with body: correct | Not defined |
| Frontend and API effort | Small: verb and body of two calls | Small | Larger: two steps, token lifetime, replay rules | Not feasible | Not feasible |
| Audit and purpose limitation | One transaction: check, read, audit entry | As chosen | The token can be replayed or forwarded, so purpose and reader may drift apart | As chosen | As chosen |
| Cacheable by mistake | No | Possibly (a shared cache could serve names without audit) | Token URLs may be cached | Not by default | Unclear |

## Consequences

### Positive

- The stated purpose and reason appear in no URL, so no access log, browser history or referrer holds them. The application's own logs never held them; this is now true for the whole path.
- The wire contract says what happens: a `POST` that audits, not a `GET` that pretends to be a read.
- No change for downloads: plain `GET` links (evidence report JSON and PDF) have no personal parameters.

### Negative / Cost

- A breaking change to two endpoints. Within this repository only the frontend calls them, and it is changed in the same pull request. There are no external clients yet; a later one must use `POST`.
- Names are no longer bookmarkable or linkable by URL. That is intended: every look has to state its purpose again.
- A reverse proxy that logs request bodies would still see them. The production checklist now says so next to the reverse proxy item; the application cannot enforce it.

## Follow-ups

- Should: add a check to the production checklist that the proxy does not log request bodies (done in `docs/operations/production.md`).
- Ruled out: keeping the query parameters as a deprecated alternative, because a still accepted parameter would keep being written to logs by old clients without anybody noticing.

## Reversibility

High while there is no external client. Going back means two endpoint definitions and two frontend calls; the audit trail is unchanged either way, so no data migration is needed.
