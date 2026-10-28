package dms.db.queue

import com.augustnagro.magnum.*
import dms.db.{Db, DbError, Jsonb}
import dms.kernel.TenantId
import zio.*

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.UUID

/** A job handed to a worker. `tenant` is empty for system jobs. */
final case class ClaimedJob(
    id: Long,
    tenant: Option[TenantId],
    kind: String,
    payload: Jsonb,
    attempts: Int,
    maxAttempts: Int
)

/** Durable job queue in PostgreSQL (ADR 0010). Jobs are claimed with a lease (`FOR UPDATE SKIP LOCKED` inside
  * `dms.job_claim`), so several workers on several instances never get the same job at the same time.
  * Delivery is at-least-once: a job whose worker crashed is claimed again after its lease, so handlers must
  * be idempotent.
  */
object JobQueue:

  /** Enqueues a job for the tenant of the current transaction. Returns `None` if a job with the same
    * `idempotencyKey` already exists, which makes enqueueing from several instances safe.
    */
  def enqueue(
      kind: String,
      payload: Jsonb = Jsonb.empty,
      idempotencyKey: Option[String] = None,
      runAt: Option[Instant] = None,
      maxAttempts: Int = 10
  ): IO[DbError, Option[Long]] =
    Db.sql(
      sql"""select dms.job_enqueue(dms.current_tenant(), $kind, $payload, $idempotencyKey,
                                   ${runAt.map(utc)}, $maxAttempts)""".query[Option[Long]].run().head
    )

  /** Enqueues a system job without tenant; runs inside `Db.transactSystem`. */
  def enqueueSystem(
      kind: String,
      payload: Jsonb = Jsonb.empty,
      idempotencyKey: Option[String] = None,
      runAt: Option[Instant] = None,
      maxAttempts: Int = 10
  ): IO[DbError, Option[Long]] =
    Db.sql(
      sql"""select dms.job_enqueue(null, $kind, $payload, $idempotencyKey, ${runAt.map(utc)}, $maxAttempts)"""
        .query[Option[Long]]
        .run()
        .head
    )

  private type JobRow = (Long, Option[UUID], String, Jsonb, Int, Int)

  /** Claims up to `max` due jobs of `kinds` for `worker` with a lease of `lease`. */
  def claim(
      db: Db,
      worker: String,
      kinds: Set[String],
      max: Int,
      lease: Duration
  ): IO[DbError, List[ClaimedJob]] =
    db.transactSystem(
      Db.sql(
        sql"""select id, tenant_id, kind, payload, attempts, max_attempts
              from dms.job_claim($worker, $max, make_interval(secs => ${lease.toMillis / 1000.0}), ${kinds.toArray.sorted})"""
          .query[JobRow]
          .run()
          .toList
      )
    ).map(
      _.map((id, tenant, kind, payload, attempts, maxAttempts) =>
        ClaimedJob(id, tenant.map(TenantId(_)), kind, payload, attempts, maxAttempts)
      )
    )

  def complete(db: Db, id: Long, worker: String): IO[DbError, Boolean] =
    db.transactSystem(
      Db.sql(sql"select dms.job_complete($id, $worker)".query[Option[Boolean]].run().head.contains(true))
    )

  /** Records a failure. Returns the new status: `queued` (retried after `retryAfter`) or `dead`. */
  def fail(
      db: Db,
      id: Long,
      worker: String,
      error: String,
      retryAfter: Duration
  ): IO[DbError, Option[String]] =
    db.transactSystem(
      Db.sql(
        sql"""select dms.job_fail($id, $worker, $error, make_interval(secs => ${retryAfter.toMillis / 1000.0}))"""
          .query[Option[String]]
          .run()
          .head
      )
    )

  /** Ids of all tenants, for system jobs that fan out per tenant. */
  def tenantIds(db: Db): IO[DbError, List[TenantId]] =
    db.transactSystem(Db.sql(sql"select dms.tenant_ids()".query[UUID].run().toList)).map(_.map(TenantId(_)))

  private def utc(i: Instant): OffsetDateTime = OffsetDateTime.ofInstant(i, ZoneOffset.UTC)
