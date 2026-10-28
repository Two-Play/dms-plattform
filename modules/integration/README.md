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
