package dms.documents.core

import dms.db.queue.{ClaimedJob, JobQueue}
import dms.db.{Db, DbError, Jsonb}
import dms.kernel.TenantId
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.util.UUID

/** @param checkInterval
  *   how often approved versions are looked up and their publication jobs enqueued; a version approved with a
  *   valid-from date that has already come is published at most this long after its approval
  */
final case class PublicationScheduleConfig(checkInterval: Duration = 1.minute)

/** Publishes approved versions at 00:00 of their valid-from date in the tenant's time zone (US-06.3, DMS-45;
  * ADR 0026) through the job queue (ADR 0010).
  *
  * Every instance calls [[tick]]: for every tenant and approved version it enqueues a job `document.publish`
  * that runs at the moment the version becomes valid. The idempotency key contains the version and that
  * moment, so each job exists once; if the tenant's time zone changes, a job for the new moment is added and
  * the old one finds the version not due and does nothing. The job publishes the version and supersedes the
  * valid one of the same scope in one transaction ([[DocumentService.publishScheduled]]); it is idempotent,
  * so at-least-once delivery is safe.
  */
final class PublicationJobs(db: Db, service: DocumentService, config: PublicationScheduleConfig):
  import PublicationJobs.*

  /** Handlers to register with the job runner. */
  val handlers: Map[String, ClaimedJob => IO[String, Unit]] = Map(PublishKind -> publish)

  /** Enqueues the publication jobs of all tenants. Returns how many jobs were newly enqueued. */
  def tick: IO[DbError | DocumentError, Int] =
    JobQueue.tenantIds(db).flatMap(tenants => ZIO.foreach(tenants)(enqueue).map(_.sum))

  /** Enqueues the publication jobs of one tenant. */
  def enqueue(tenant: TenantId): IO[DbError | DocumentError, Int] =
    db.transact(tenant)(
      service.scheduledPublications.flatMap(scheduled =>
        ZIO
          .foreach(scheduled)(p =>
            JobQueue
              .enqueue(
                PublishKind,
                Jsonb(Json.Obj("versionId" -> Json.Str(p.versionId.toString)).toJson),
                idempotencyKey = Some(s"$PublishKind:${p.versionId}:${p.dueAt.getEpochSecond}"),
                runAt = Some(p.dueAt),
                maxAttempts = 10
              )
              .mapError(DocumentError.Database(_))
          )
          .map(_.flatten.size)
      )
    )

  /** Calls [[tick]] every check interval; failures are logged and retried on the next tick. */
  def schedule: UIO[Nothing] =
    (tick.catchAll(e => ZIO.logWarning(s"Scheduling publications failed: $e").as(0)) *>
      ZIO.sleep(config.checkInterval)).forever

  private def publish(job: ClaimedJob): IO[String, Unit] =
    for
      tenant  <- ZIO.fromOption(job.tenant).orElseFail(s"job ${job.id} ($PublishKind) has no tenant")
      version <- ZIO.fromEither(versionOf(job.payload))
      _       <- db.transact(tenant)(service.publishScheduled(version)).mapError {
        case e: DocumentError => e.describe
        case e: DbError       => e.toString
      }
    yield ()

object PublicationJobs:
  val PublishKind = "document.publish"

  private def versionOf(payload: Jsonb): Either[String, UUID] =
    payload.value
      .fromJson[Json.Obj]
      .flatMap(_.get("versionId").collect { case Json.Str(id) => id }.toRight("versionId missing"))
      .flatMap(id => scala.util.Try(UUID.fromString(id)).toEither.left.map(_.getMessage))
