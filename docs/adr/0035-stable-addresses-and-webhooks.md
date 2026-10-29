# 0035: Stable addresses of the current version resolved per request; webhooks as signed deliveries through the outbox and the job queue

| Field | Value |
|---|---|
| Status | Accepted (addendum DMS-107: administration UI, test event, secrets encrypted at rest, document number in payloads) |
| Date | 2026-10-29 |
| Decided by | Philippe |

## Context

US-14.1 (DMS-58): the intranet must always show the valid version of a policy, never an outdated one. Acceptance criteria: a stable address always returns the currently valid version; on publication and withdrawal subscribed systems get an event; access is through a technical user with read rights on exactly the released classes.

Decisive facts and constraints:

- Validity is per document and validity scope (ADR 0021): at most one version per scope is Published or ReviewDue. Scheduled publications switch versions at 00:00 of the valid-from date in the tenant's time zone through a job (ADR 0026); the switch is one transaction.
- Withdrawal of a valid version (US-07.1, DMS-55) leaves the scope without a valid version.
- Every valid version has a PDF/A rendition with SHA-256 (ADR 0025) and its HTML page can be produced deterministically from the frozen version package.
- Events leave the platform through the transactional outbox and the job queue (ADR 0010). Since DMS-51 the outbox relay marks an event delivered only after every subscriber accepted it; one slow or failing subscriber must not hold up the others, and notifications must not miss events (DMS-102).
- Authorization (ADR 0018) already scopes roles to document classes and restricts confidential documents to access groups (ADR 0032). ZITADEL (ADR 0005) issues client-credential tokens for machine users.
- No document number exists yet; the document id (UUID) is the only stable key.
- Webhook targets are entered by tenant administrators: the platform must not become a tool to reach internal services (SSRF).

## Decision

**Stable address.** `GET /api/v1/documents/{id}/current` (JSON metadata), `/current.pdf` (archive rendition) and `/current.html` (HTML page), each with an optional `?scope=`. The version is resolved on every request from PostgreSQL with the pure rule `CurrentVersion.resolve` (documents-api): the valid version of the scope (exact scope key, else the only scope key containing the value); without scope the unrestricted version, else the only valid one. Answers: 404 if nothing is valid or the caller may not read it (existence is not revealed), 410 `withdrawn` if the last valid version was withdrawn (or archived after that) without successor, 409 `scope_required` if several scopes are valid and none was asked for. Responses carry a strong `ETag` (SHA-256 of the representation; for the PDF its stored checksum) and `Cache-Control: private, no-cache`, and answer `If-None-Match` with 304. The PDF names the version-specific rendition in `Content-Location`; the HTML page is served with a restrictive `Content-Security-Policy`. Access is `ReadDocument` like every read, so the technical user needs nothing special.

**Technical user.** The intranet uses a ZITADEL machine user (client credentials) with the platform role `Reader` assigned with `document_class` scope for each released class (`dms.role_assignment`). Class scope covers subclasses; confidential documents stay hidden unless the machine user is made a member of an allowed group. `scripts/dev_setup.py intranet` sets one up locally (Reader on `policy`).

**Webhooks.** Tenant administrators (`Configure` on the new configuration area `Webhooks`) manage subscriptions at `/api/v1/admin/webhooks`: URL, event types (`document.published`, `document.superseded`, `document.withdrawn`), class filter (class or ancestor), description, active; a generated secret is shown on creation and rotation only. Delivery has two steps (migration V22, tables with RLS and explicit grants):

1. Outbox subscriber (`WebhookJobs.deliver`, beside the search subscriber): for a status change it asks the port `WebhookEvents` (implemented in `app` from `DocumentService`) whether it is a webhook event, then writes in one tenant transaction one `webhook_delivery` row per matching subscription with the exact body, and a `webhook.deliver` job per row. It only writes rows, so a receiver that is down never holds up the relay; a retried outbox event finds the rows (unique per subscription and event) and adds nothing.
2. The job sends the body as `POST` following the **Standard Webhooks** specification: `webhook-id` (the delivery id, the same on every attempt; idempotency key for receivers), `webhook-timestamp`, `webhook-signature: v1,<base64 HMAC-SHA256 of "id.timestamp.body">`. A 2xx answer delivers it. Anything else fails the job, which the job runner retries with exponential backoff (10 s doubling up to 1 h) until `maxAttempts` (12, about 4 hours), then the delivery is `dead`. The delivery row is the log (status, attempts, last HTTP status, last error); administrators list it and re-send dead deliveries.

Recertification (ReviewDue → Published) is no publication; the outbox payload of status changes now carries `previousStatus` for that. Withdrawn drafts are not announced. Events of confidential and strictly confidential documents are not sent: a subscription has no access groups. Order is not guaranteed; receivers use the stable address for the current state.

**SSRF protection** (`UrlGuard`): only absolute `https` URLs without user info; every address the host resolves to must be public (loopback, RFC 1918, link-local incl. 169.254.169.254, CGNAT, unique local IPv6, multicast, documentation, NAT64, 6to4 and reserved ranges are blocked). Checked when a subscription is saved and before every attempt; a blocked target makes the delivery dead at once. Redirects are not followed. `dms.webhooks.allowedHosts` exempts development hosts (http and private addresses, e.g. `localhost`); `allowPrivateNetworks` opens RFC 1918 / ULA (https only) for receivers in the company network.

## Alternatives considered

Stable address:

| Criterion | Chosen: resolve per request, ETag + no-cache | A: redirect (302) to the version URL | B: materialized "current" pointer updated on publication |
|---|---|---|---|
| Never outdated | Yes: every use revalidates, the switch is one transaction | Yes, but caches may keep the redirect | Only if every path updates the pointer |
| Bookmarkable, embeddable in the intranet | Yes, one URL per representation | Clients and proxies handle redirects with auth headers differently | Yes |
| Traffic | 304 for unchanged copies | Two requests each time | Like chosen |
| Additional state | None | None | Table and consistency rules |
| Fits scheduled publication | Yes, no invalidation | Yes | Needs the job to update it |

Webhook delivery:

| Criterion | Chosen: delivery rows + job per subscription | C: send inside the outbox relay | D: external broker (Kafka, NATS) |
|---|---|---|---|
| Receiver down | Others unaffected; retries per delivery | Blocks the relay for every subscriber and the search index | Broker buffers; still needs HTTP push to the intranet |
| Event loss | None: rows written before the event is marked delivered | None, but head-of-line blocking | None, if configured durably |
| Delivery log, dead letter, re-send | Rows in the tenant's schema with RLS | Would need the same rows | Broker tooling, not per tenant |
| Operations | Existing job queue (ADR 0010) | Existing | New infrastructure |

Signature scheme: Standard Webhooks (chosen) versus a custom `X-Dms-Signature` header: the standard has receiver libraries in common languages and fixes replay protection (timestamp in the signed content); a custom header would need its own documentation and verification code.

Document key: the document id (chosen) versus a new human-readable document number: a number would need numbering rules per tenant and class, which no story asks for yet; it can be added as a second way to address the same resource.

## Consequences

### Positive

- The intranet keeps one link per document; scheduled publications switch it at 00:00 without cache invalidation, and conditional requests keep the traffic small.
- No new authorization concept: class-scoped Reader roles and access groups apply to technical users as to persons.
- No event is lost while a receiver is down; each subscription retries independently; the relay and the search index are never held up.
- Receivers can verify origin and integrity with standard libraries and deduplicate with `webhook-id`.
- Webhook targets cannot reach loopback, internal networks or cloud metadata by default.

### Negative / Cost

- Every request to a stable address reads the document with all versions; fine for intranet traffic, a lookup by version status would be needed at large scale.
- The HTML page is rendered per request (cheap, but not cached server-side).
- Secrets are stored in clear text in the tenant's table (needed to sign); protected by RLS and grants only.
- DNS rebinding between the check and the connection is not prevented in the application; an egress firewall or proxy is the complementary control.
- No ordering guarantee between events; receivers must not rely on order.
- A job lost by lease expiry after its last attempt leaves its delivery shown as `retrying` while the job is dead.

## Follow-ups

- Must be done: administration UI for webhooks and their delivery log.
- Must be done: production setup of the intranet machine user per tenant (ZITADEL machine user, role assignments per released class) as part of tenant onboarding.
- Later: encrypt webhook secrets at rest with a platform key; pin the resolved address for the connection (custom resolver) to close DNS rebinding; a human-readable document number as alternative key; a test-ping endpoint.
- Ruled out: sending events of confidential documents to subscriptions without access groups.

## Reversibility

**high**: The stable addresses are three read-only endpoints over existing data. Webhooks are an additional outbox subscriber with their own tables and jobs; removing or replacing them (e.g. with a broker) touches `WebhookJobs`, `WebhookApi` and migration V22 only.

## Addendum (DMS-107, 2026-10-29): administration UI, test event, secrets encrypted at rest, document number in payloads

**Context.** Follow-ups of this ADR: the administration UI for webhooks and their delivery log ("must be done"), encrypting the signing secrets at rest with a platform key, a test-ping endpoint and the human-readable document number as alternative key ("later"). Since DMS-113 documents can have a unique number per tenant, resolvable at `/api/v1/documents/by-number/{number}/current`. The secrets are needed in clear text to sign (HMAC), so they cannot be hashed; until now they were stored in plain text, protected only by RLS and grants, i.e. readable in every database dump and backup.

**Decision.**

1. **Encryption at rest.** The application encrypts each signing secret with **AES-256-GCM** under a platform key ring from its configuration (`dms.webhooks.secretKeys` / `secretKeysFile`, `id:base64key,…`, the first entry active). Stored are the key id (`secret_key_id`) and base64 of nonce, ciphertext and tag (`secret_ciphertext`); the additional authenticated data `dms.webhook-secret.v1:<tenant>:<subscription>` binds a ciphertext to its row, so copying it to another subscription or tenant does not decrypt. The secret is decrypted only in the delivery job and the test event, right before signing, and never logged, audited or returned after the answer that created it (creation, rotation).
2. **Migration V28** adds the two columns (plain-text `secret` becomes nullable, a check requires either ciphertext or plain text) and grants them to `dms_app`; RLS of V22 covers them. SQL cannot encrypt without the key, so `WebhookSecretMigration` runs at startup of every instance: it encrypts plain-text secrets and re-encrypts secrets of older keys with the active key, one transaction per tenant, with an optimistic condition so that a concurrent rotation by an administrator wins. The same pass implements **key rotation**: put the new key first, keep the old one until every instance has restarted, then remove it.
3. **Development key.** Without configuration a publicly known key `dev` is active and a warning is logged; it keeps local setups and tests working without secrets. It stays readable when real keys are configured, so rows written with it are re-encrypted automatically.
4. **Test event.** `POST /api/v1/admin/webhooks/{id}/test` sends a signed `webhook.test` event at once (same SSRF check, headers and signature as a delivery, `webhook-id` `test_<uuid>`) outside any transaction and returns `delivered`, `statusCode`, `error`, `durationMillis`. It works for paused subscriptions (check before activating), is audited as `webhook.tested` (outcome only) and is not part of the delivery log and not retried.
5. **Payload version 2.** `data.payloadVersion` (2) is added to every body, and `documentNumber` plus `links.currentByNumber`/`currentPdfByNumber`/`currentHtmlByNumber` when the document has a number at the time the outbox event is processed. Only fields are added; `documentId` stays. A body without `payloadVersion` is version 1. The delivery log shows the number (`documentNumber`, read from the stored body).
6. **SSRF.** The existing guard (https only, every resolved address public, checked on save, before every attempt and before the test event; development hosts only through `allowedHosts`, empty by default) is kept; IPv6 ranges that embed or reach private IPv4 or are not routable were added: Teredo `2001::/32`, ORCHID `2001:10::/28`, discard `100::/64`, IPv4-mapped `::ffff:0:0/96`.
7. **UI.** A tab "Webhooks" on `/admin` (tenant administrators): list with masked secret, create, edit, pause/activate, delete (confirmation), rotate the secret (confirmation; the new secret is shown once with a copy button), test event with its result per row, and the latest 20 deliveries with status, attempts, last HTTP status or error, document number and "send again".

**Alternatives considered.**

| Criterion | Chosen: AES-GCM in the application, key ring from configuration | PostgreSQL `pgcrypto` with the key passed per query | External KMS / Vault transit (envelope encryption) | Keep plain text (RLS and grants only) |
|---|---|---|---|---|
| Secret readable from dump or backup | No | No | No | Yes |
| Key visible to the database | Never | In query text, statement logs and `pg_stat_activity` | Never | – |
| Rows bound to tenant and subscription | Yes (AAD) | Only by own convention | Yes (encryption context) | – |
| Rotation | Restart with new key first; startup pass re-encrypts | Re-encrypt in SQL with both keys in the query | KMS-managed | – |
| Operations | One configuration value or mounted file | Same, plus care with logging | New infrastructure, network dependency on every delivery | None |
| Migration of existing rows | At startup (needs the key) | In SQL migration, but the key would be in the migration | At startup | – |

Payload change: additive fields with `payloadVersion` (chosen) versus a new event type or a versioned header: additive fields keep existing receivers working without change and Standard Webhooks receivers ignore unknown fields; a new event type would double subscriptions; a header is not part of the signed body stored in the log.

Test event: synchronous request returning the outcome (chosen) versus a delivery row and job like a real event: the administrator wants an answer now; a row would mix test traffic into the delivery log and the outbox event ids.

**Consequences.**

- Positive: database dumps and backups no longer contain usable signing secrets; a stolen ciphertext cannot be moved to another row; secrets are never shown again after creation; administrators can check receivers before activating them and see failures in the UI; receivers get the document number as stable human-readable key without breaking.
- Negative / Cost: the key ring becomes critical operational state: it must be backed up separately and given to every instance; losing it requires rotating every secret and updating every receiver. Until every instance restarted once after V28, plain-text secrets remain (the startup pass removes them); the plain-text column stays until a later migration drops it. The development key protects nothing; a production instance without configured keys only logs a warning. The test event performs an outbound request on demand of an administrator (bounded by the timeout; no rate limit). DNS rebinding is still not prevented in the application (unchanged).
- Follow-ups: drop the column `secret` once every environment ran the startup pass (must be done before or with the next webhook migration); refuse to start without configured keys in production once there is a production profile; pin the resolved address for the connection (DNS rebinding); a rate limit for test events if needed.
- Reversibility: **high**. Decrypting all rows back to plain text is a small startup pass; the UI is one tab; the payload fields are additive.
