package dms.documents.core

import com.augustnagro.magnum.sql
import dms.db.queue.{ClaimedJob, JobQueue}
import dms.db.{Db, DbError, Jsonb}
import dms.documents.LifecycleError
import dms.kernel.TenantId
import dms.rendering.RenditionRequest
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
  * so at-least-once delivery is safe. A withdrawn version loses its queued jobs ([[PublicationJobs.cancel]]);
  * one that was claimed already finds the version withdrawn and does nothing (ADR 0059).
  *
  * A failed job is retried with backoff. Its error starts with a failure code in brackets (like renditions,
  * ADR 0055), so its status and notices can explain it. Once it is dead, [[tick]] does not bring it back (its
  * key exists): the version stays approved until someone retries it (DMS-142, ADR 0058) or withdraws it
  * ([[DocumentService.withdrawApproved]], DMS-145, ADR 0061).
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
      tenant <- ZIO.fromOption(job.tenant).orElseFail(s"[invalid] job ${job.id} ($PublishKind) has no tenant")
      version <- ZIO.fromEither(versionOf(job.payload)).mapError(e => s"[invalid] $e")
      _       <- db.transact(tenant)(service.publishScheduled(version)).mapError(jobError)
    yield ()

object PublicationJobs:
  val PublishKind = "document.publish"

  /** The error text of a failed job: a stable failure code in brackets, then the description. */
  def jobError(error: DbError | DocumentError): String = error match
    case e: DbError       => s"[database] $e"
    case e: DocumentError =>
      val code = e match
        case DocumentError.Lifecycle(LifecycleError.LegalHoldActive) => "legal_hold"
        case _: DocumentError.Lifecycle | _: DocumentError.Conflict  => "conflict"
        case _: DocumentError.Database                               => "database"
        case _: DocumentError.NotFound                               => "not_found"
        case _                                                       => "invalid"
      s"[$code] ${e.describe}"

  /** Cancels the queued publication jobs of a version inside the current transaction (DMS-143): there can be
    * several, one per due moment. Returns how many.
    */
  def cancel(version: UUID, reason: String): IO[DbError, Int] =
    JobQueue.cancel(PublishKind, s"$PublishKind:$version:", reason)

  /** Whether the publication of a version failed for good: its newest job is dead. Older jobs belong to an
    * earlier due moment (a time zone change, ADR 0026). Only a decision that holds the row lock of the
    * version can rely on the answer: a job comes back from the dead only by a retry, which takes the same
    * lock.
    */
  def isDead(version: UUID): IO[DbError, Boolean] =
    Db.sql(
      sql"""select status = 'dead' from dms.job
            where kind = $PublishKind and payload ->> 'versionId' = ${version.toString}
            order by id desc limit 1""".query[Boolean].run().headOption.getOrElse(false)
    )

  /** Whether the rendition of a version failed for good (DMS-163, ADR 0078): its one rendering job is dead.
    * The same holds as for [[isDead]]: only a retry brings it back, under the row lock of the version.
    */
  def isRenditionDead(version: UUID): IO[DbError, Boolean] =
    Db.sql(
      sql"""select status = 'dead' from dms.job
            where kind = ${RenditionRequest.JobKind}
              and idempotency_key = ${RenditionRequest.idempotencyKey(version)}"""
        .query[Boolean]
        .run()
        .headOption
        .getOrElse(false)
    )

  private def versionOf(payload: Jsonb): Either[String, UUID] =
    payload.value
      .fromJson[Json.Obj]
      .flatMap(_.get("versionId").collect { case Json.Str(id) => id }.toRight("versionId missing"))
      .flatMap(id => scala.util.Try(UUID.fromString(id)).toEither.left.map(_.getMessage))
