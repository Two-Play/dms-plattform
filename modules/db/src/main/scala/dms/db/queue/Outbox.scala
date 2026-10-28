package dms.db.queue

import com.augustnagro.magnum.*
import dms.db.{Db, DbError, Jsonb}
import dms.kernel.TenantId
import zio.*

import java.util.UUID

/** An outbox event handed to a relay. */
final case class OutboxEvent(
    id: Long,
    tenant: TenantId,
    aggregateType: String,
    aggregateId: UUID,
    eventType: String,
    payload: Jsonb,
    correlationId: Option[UUID],
    attempts: Int
)

/** Transactional outbox (ADR 0010): events are written in the same transaction as the business change and
  * delivered afterwards by a relay, at least once and in id order per claim batch. The event id is the
  * idempotency key for receivers.
  */
object Outbox:

  /** Writes an event for the tenant of the current transaction. Returns its id. */
  def publish(
      aggregateType: String,
      aggregateId: UUID,
      eventType: String,
      payload: Jsonb,
      correlationId: Option[UUID] = None
  ): IO[DbError, Long] =
    Db.sql(
      sql"""insert into dms.outbox_event (tenant_id, aggregate_type, aggregate_id, event_type, payload, correlation_id)
            values (dms.current_tenant(), $aggregateType, $aggregateId, $eventType, $payload, $correlationId)
            returning id""".query[Long].run().head
    )

  private type EventRow = (Long, UUID, String, UUID, String, Jsonb, Option[UUID], Int)

  def claim(db: Db, worker: String, max: Int, lease: Duration): IO[DbError, List[OutboxEvent]] =
    db.transactSystem(
      Db.sql(
        sql"""select id, tenant_id, aggregate_type, aggregate_id, event_type, payload, correlation_id, attempts
              from dms.outbox_claim($worker, $max, make_interval(secs => ${lease.toMillis / 1000.0}))
              order by id"""
          .query[EventRow]
          .run()
          .toList
      )
    ).map(
      _.map((id, tenant, aggType, aggId, eventType, payload, correlation, attempts) =>
        OutboxEvent(id, TenantId(tenant), aggType, aggId, eventType, payload, correlation, attempts)
      )
    )

  def complete(db: Db, id: Long, worker: String): IO[DbError, Boolean] =
    db.transactSystem(
      Db.sql(sql"select dms.outbox_complete($id, $worker)".query[Option[Boolean]].run().head.contains(true))
    )

  def fail(db: Db, id: Long, worker: String, error: String, retryAfter: Duration): IO[DbError, Boolean] =
    db.transactSystem(
      Db.sql(
        sql"""select dms.outbox_fail($id, $worker, $error, make_interval(secs => ${retryAfter.toMillis / 1000.0}))"""
          .query[Option[Boolean]]
          .run()
          .head
          .contains(true)
      )
    )
