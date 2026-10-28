# 0010: Transactional outbox and job queue in PostgreSQL instead of a message broker

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

Business changes trigger follow-up work: rendering, indexing, OCR, notifications, bulk generation. An event must neither be lost when the transaction succeeds nor be sent when it fails. Expected load: a few thousand jobs per day per tenant, no real-time streams.

## Decision

Events are written to an outbox table in the same transaction as the business change. Workers fetch jobs from a job table in PostgreSQL using `SELECT … FOR UPDATE SKIP LOCKED`. Kafka or RabbitMQ will only be introduced when a need has been demonstrated.

## Alternatives considered

| Criterion | Chosen: Outbox + jobs in Postgres | Kafka | RabbitMQ |
|---|---|---|---|
| Atomicity with business data | Same transaction | Only with outbox + relay | Only with outbox + relay |
| Additional component | None | Kafka cluster | Broker |
| Throughput | Thousands of jobs/minute, sufficient | Very high | High |
| Retry, delay, priority | Via columns and SQL | Must be built | Partly built in |
| Replay / event history | Limited (table) | Native | No |
| Visibility / debugging | SQL query | Dedicated tools | Management UI |
| Tenant isolation | Via `tenant_id` and RLS (0014) | Topics/headers | Queues/headers |

## Consequences

### Positive

- No lost or phantom events; one consistency model.
- No additional service; backup and monitoring via PostgreSQL.
- Jobs can be inspected via SQL and retried manually.
- Python workers (0008) only need a Postgres driver.

### Negative / Cost

- Polling creates a base load on the database; `LISTEN/NOTIFY` can reduce latency but does not replace polling.
- Job tables grow; partitioning or cleanup required.
- Retries, backoff, dead letters and lock timeouts must be implemented in-house.
- The scaling limit is the write load of the single database.

## Follow-ups

- Define tables `outbox` and `job` with status, attempt counter, `run_after`, `locked_until`, `tenant_id`.
- Make jobs idempotent (job ID as idempotency key).
- Dead-letter status and an admin view for failed jobs.
- Cleanup job for completed entries; align retention with audit (0001).
- Database access must support `SKIP LOCKED` (criterion in 0012).
- Criteria for introducing Kafka: sustained > 100 jobs/s, external event subscribers or a need for replay.
- Ruled out: sending directly to external systems within the business transaction.

## Reversibility

**low**: The outbox remains even with a broker; a relay would forward entries to Kafka. Workers would only need to switch their source, provided the job interface is abstracted.
