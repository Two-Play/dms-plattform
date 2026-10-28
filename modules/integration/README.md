# Module `integration`

Background processing (ADR 0010):

- `JobRunner` processes jobs from `dms.job` with a lease: `dms.job_claim` reserves due jobs of the kinds the runner has handlers for (`FOR UPDATE SKIP LOCKED`), the handler runs in its own transactions, then the job is completed or failed. Failed jobs are retried with exponential backoff (`WorkerConfig.retryBase` … `retryMax`) until `max_attempts`, then they are `dead`. A crashed worker's jobs are taken over when the lease expires. Delivery is at-least-once; handlers must be idempotent.
- `OutboxRelay` delivers `dms.outbox_event` rows the same way. Events are written with `dms.db.queue.Outbox.publish` in the business transaction, so a rolled-back change never emits an event.
- Enqueue with `dms.db.queue.JobQueue.enqueue` (tenant of the current transaction) or `enqueueSystem` (no tenant). An `idempotencyKey` makes enqueueing from several instances safe, e.g. for periodic jobs keyed by period.

Workers run without tenant context and use the security definer functions from migration V6; a handler for a tenant job opens `Db.transact(job.tenant)` itself.

Single module without an `api`/`core` split.
