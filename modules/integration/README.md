# Module `integration`

Background processing (ADR 0010):

- `JobRunner` processes jobs from `dms.job` with a lease: `dms.job_claim` reserves due jobs of the kinds the runner has handlers for (`FOR UPDATE SKIP LOCKED`), the handler runs in its own transactions, then the job is completed or failed. Failed jobs are retried with exponential backoff (`WorkerConfig.retryBase` … `retryMax`) until `max_attempts`, then they are `dead`. A crashed worker's jobs are taken over when the lease expires. Delivery is at-least-once; handlers must be idempotent.
- `OutboxRelay` delivers `dms.outbox_event` rows the same way. Events are written with `dms.db.queue.Outbox.publish` in the business transaction, so a rolled-back change never emits an event.
- Enqueue with `dms.db.queue.JobQueue.enqueue` (tenant of the current transaction) or `enqueueSystem` (no tenant). An `idempotencyKey` makes enqueueing from several instances safe, e.g. for periodic jobs keyed by period.

Workers run without tenant context and use the security definer functions from migration V6; a handler for a tenant job opens `Db.transact(job.tenant)` itself.

Layout: the ports to business systems are in `integration-api` (`modules/integration/api`, depends on `kernel` only), the adapters and the job runner in `integration`. Other modules use the ports; only `app` wires an adapter.

## Partner master (DMS-43, ADR 0023)

`integration-api`, package `dms.integration.partners`:

- `PartnerDirectory`: `search(tenant, query, limit)` (name or identifier contains the query, case-insensitive, by name) and `get(tenant, id)` (`None` if unknown). Failures are typed: `PartnerDirectoryError.Unavailable` (not reachable, timeout, error status, circuit open) and `InvalidResponse` (payload does not fit).
- `Partner` (id = partner number, name, `PostalAddress`, VAT id) with `attribute(name)` for the attributes a template reference field can take over: `id`, `name`, `street`, `postalCode`, `city`, `country`, `address` (one line), `vatId`.

`integration`, same package:

- `HttpPartnerDirectory`: the partner master's HTTP API. Contract: `GET <baseUrl>/partners?q=<text>&limit=<n>` → `{"items":[<partner>]}`, `GET <baseUrl>/partners/<id>` → `<partner>` or 404; a partner is `{"id","name","street"?,"postalCode"?,"city"?,"country"?,"vatId"?}`. Headers: `Accept: application/json`, `X-Dms-Tenant: <tenant id>`, `Authorization: Bearer <apiToken>` if configured. Each request has a timeout (connect + exchange); redirects are not followed. A `CircuitBreaker` rejects calls at once after `failureThreshold` failures in a row for `openSeconds`, then lets one trial call through.
- `InMemoryPartnerDirectory`: fictitious demo partners (`P-10001` … `P-20002`) for development and tests.
- `PartnerDirectories.layer(config)`: `dms.partners.directory = demo | http`.

Configuration (`dms.partners`, environment variables): `directory` (`DMS_PARTNERS_DIRECTORY`, default `demo`), `baseUrl` (`DMS_PARTNERS_BASE_URL`), `apiToken` (`DMS_PARTNERS_API_TOKEN`), `timeoutMillis` (`DMS_PARTNERS_TIMEOUT_MILLIS`, default 3000), `failureThreshold` (3), `openSeconds` (30). Nothing is cached; documents keep a snapshot of the taken-over values (see `modules/documents/README.md`).

HTTP (module `app`, `PartnerApi`):

| Method | Path | Purpose | Authorization |
|---|---|---|---|
| GET | `/api/v1/partners?q=&limit=` | Search by name and identifier (`q` at least 2 characters, `limit` 1–50, default 20) | `ReadTemplateCatalog` |
| PUT | `/api/v1/documents/{id}/references/{key}` | Take the picked entry (`{"entryId":"P-10001"}`) over into the draft's reference field `key` | `EditDraft` |
| DELETE | `/api/v1/documents/{id}/references/{key}` | Remove the taken-over values | `EditDraft` |

The partner master is called outside database transactions. Errors: `503 partner_directory_unavailable` and `502 partner_directory_invalid_response` with a message telling the author that the draft can be saved without the partner; `422 unknown_entry` if the partner master does not know the id; `404` for an unknown reference field.

## Webhooks (DMS-58, ADR 0035)

Package `dms.integration.webhooks`:

- `WebhookJobs(db, events, sender, settings)`: `deliver(event)` is an outbox subscriber. For an event the port `WebhookEvents` describes as webhook event, it writes in one transaction of the event's tenant one `dms.webhook_delivery` row per matching `dms.webhook_subscription` (event type, class filter on the class path; exact JSON body `{"type","timestamp","data"}`) and a job `webhook.deliver` per row (idempotency key: the delivery id). Rows are unique per subscription and outbox event, so a retried event adds nothing. The job sends the body (`POST`, `Content-Type: application/json`, `User-Agent: DMS-Webhooks/1`) signed after Standard Webhooks (`WebhookSignature`: `webhook-id` = delivery id, `webhook-timestamp`, `webhook-signature: v1,<base64 HMAC-SHA256 of id.timestamp.body>`, secret `whsec_<base64>`). 2xx: `delivered`; otherwise the job fails and is retried with the job runner's backoff until `maxAttempts`, then the delivery is `dead`. Deliveries of deactivated or deleted subscriptions are `skipped`. `redeliver(id)` sets a delivery back to `pending` and enqueues a new job.
- `UrlGuard`: SSRF protection. `parse` (absolute `https`, no user info, no fragment; `http` only for allowed hosts) and `check` (every resolved address public: no loopback, RFC 1918, link-local/metadata, CGNAT, ULA, multicast, documentation or reserved ranges). Runs when a subscription is saved and before every attempt; a blocked target makes the delivery dead at once.
- `HttpWebhookSender`: JDK HTTP client, timeout per attempt, redirects not followed.
- `WebhookStore`: subscriptions and deliveries (inside `Db.transact(tenant)`, RLS).
- `WebhookSettings`: `allowedHosts` (development hosts exempt from the SSRF rules), `allowPrivateNetworks` (RFC 1918 / ULA with https), `timeout`, `maxAttempts`.

Configuration (`dms.webhooks`): `allowedHosts` (`DMS_WEBHOOKS_ALLOWED_HOSTS`, comma-separated, default empty), `allowPrivateNetworks` (`DMS_WEBHOOKS_ALLOW_PRIVATE_NETWORKS`, false), `timeoutSeconds` (10), `maxAttempts` (12, about four hours of retries).

HTTP (module `app`, `WebhookApi`; tenant administrators, `Configure` on `Webhooks`; changes are audited, secrets never):

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/admin/webhooks` | List subscriptions |
| POST | `/api/v1/admin/webhooks` | Create (`{"url","eventTypes","documentClasses","description","active"}`); returns the secret once; 422 for unknown event types or unsafe URLs |
| GET / PUT / DELETE | `/api/v1/admin/webhooks/{id}` | Read, change (`active: false` pauses), delete (log kept) |
| POST | `/api/v1/admin/webhooks/{id}/secret` | Rotate the secret |
| GET | `/api/v1/admin/webhooks/{id}/deliveries?status=&limit=` | Delivery log, newest first |
| POST | `/api/v1/admin/webhooks/{id}/deliveries/{deliveryId}/redeliver` | Send again (409 while pending or retrying) |

Event types (app, `DocumentWebhookEvents`): `document.published` (Approved → Published; not a recertification), `document.superseded`, `document.withdrawn` (only of versions that were valid). `data`: `documentId`, `versionId`, `version`, `title`, `classKey`, `classPath`, `scope`, `validFrom`, `renditionSha256`, `supersededBy`, `eventId` and `links` to the stable addresses (`current`, `currentPdf`, `currentHtml`) and the version's rendition. Confidential and strictly confidential documents are not announced. No order is guaranteed.
