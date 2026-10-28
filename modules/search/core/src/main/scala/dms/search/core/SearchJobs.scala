package dms.search.core

import dms.db.queue.{ClaimedJob, JobQueue, OutboxEvent}
import dms.db.{Db, DbError, Jsonb}
import dms.documents.{DocumentStatus, Versioning}
import dms.kernel.{DocumentId, TenantId}
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.util.UUID
import scala.util.Try

/** Keeps the search index current through the job queue (ADR 0010, 0029):
  *
  *   - Outbox: [[deliver]] turns every status change of a document version that concerns the index into a job
  *     `search.index-document` (idempotency key: the event id), which reindexes the document. Published,
  *     superseded and withdrawn or archived versions reach the index seconds after the change.
  *   - Synchronization: every instance calls [[tick]] every `syncInterval`; it enqueues one job `search.sync`
  *     per tenant and interval (idempotency key: tenant and interval number), which reindexes every document
  *     changed since the previous run. It also catches changes without an outbox event (e.g. a new owner set
  *     with a draft) and events a failed job lost, so the index follows the database within about one
  *     interval.
  *
  * Both jobs are idempotent; at-least-once delivery is safe.
  */
final class SearchJobs(db: Db, index: SearchIndex, settings: SearchSettings):
  import SearchJobs.*

  /** Handlers to register with the job runner. */
  val handlers: Map[String, ClaimedJob => IO[String, Unit]] = Map(IndexKind -> indexJob, SyncKind -> syncJob)

  /** Outbox subscriber: enqueues the reindexing of the document of a relevant event in the event's tenant. */
  def deliver(event: OutboxEvent): IO[String, Unit] =
    ZIO
      .when(concernsIndex(event))(
        db.transact(event.tenant)(
          JobQueue.enqueue(
            IndexKind,
            Jsonb(Json.Obj("documentId" -> Json.Str(event.aggregateId.toString)).toJson),
            idempotencyKey = Some(s"$IndexKind:${event.id}")
          )
        ).mapError(_.toString)
      )
      .unit

  /** Enqueues the synchronization of every tenant for the current interval. Returns the number of new jobs.
    */
  def tick: IO[DbError, Int] =
    for
      now     <- Clock.instant
      tenants <- JobQueue.tenantIds(db)
      slot = now.toEpochMilli / settings.syncInterval.toMillis.max(1)
      added <- ZIO.foreach(tenants)(tenant =>
        db.transact(tenant)(
          JobQueue
            .enqueue(SyncKind, idempotencyKey = Some(s"$SyncKind:${tenant.value}:$slot"), maxAttempts = 3)
        )
      )
    yield added.flatten.size

  /** Calls [[tick]] every sync interval; failures are logged and retried on the next tick. */
  def schedule: UIO[Nothing] =
    (tick.catchAll(e => ZIO.logWarning(s"Scheduling the search synchronization failed: $e").as(0)) *>
      ZIO.sleep(settings.syncInterval)).forever

  private def indexJob(job: ClaimedJob): IO[String, Unit] =
    for
      tenant   <- tenantOf(job)
      document <- ZIO.fromEither(documentOf(job.payload))
      _        <- index.indexDocument(tenant, document).mapError(_.describe)
    yield ()

  private def syncJob(job: ClaimedJob): IO[String, Unit] =
    tenantOf(job).flatMap(tenant => index.sync(tenant).mapError(_.describe)).unit

  private def tenantOf(job: ClaimedJob): IO[String, TenantId] =
    ZIO.fromOption(job.tenant).orElseFail(s"job ${job.id} (${job.kind}) has no tenant")

object SearchJobs:
  val IndexKind = "search.index-document"
  val SyncKind  = "search.sync"

  /** Status changes of a version from approval on: publication, review due, supersession, withdrawal,
    * archiving and destruction change what the index holds; changes of open versions do not.
    */
  def concernsIndex(event: OutboxEvent): Boolean =
    event.aggregateType == "document" && event.eventType == "document.status-changed" &&
      event.payload.value
        .fromJson[Json.Obj]
        .toOption
        .flatMap(_.get("status"))
        .collect { case Json.Str(s) => s }
        .flatMap(s => Try(DocumentStatus.valueOf(s)).toOption)
        .forall(s => !Versioning.isOpen(s) && s != DocumentStatus.Approved)

  private def documentOf(payload: Jsonb): Either[String, DocumentId] =
    payload.value
      .fromJson[Json.Obj]
      .flatMap(_.get("documentId").collect { case Json.Str(id) => id }.toRight("documentId missing"))
      .flatMap(id => Try(DocumentId(UUID.fromString(id))).toEither.left.map(_.getMessage))
