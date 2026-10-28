package dms.audit.core

import dms.db.queue.{ClaimedJob, JobQueue}
import dms.db.{Db, DbError}
import dms.kernel.TenantId
import zio.*

import java.time.{Instant, LocalDate, ZoneOffset}

/** @param checkpointInterval
  *   how often the chain head of every tenant is checkpointed; this is the unprotected window (ADR 0017)
  * @param anchorHourUtc
  *   hour of the day (UTC) at which the daily RFC 3161 anchor is due
  */
final case class AuditScheduleConfig(checkpointInterval: Duration = 5.minutes, anchorHourUtc: Int = 2)

/** Schedules and runs the periodic audit work as jobs (DMS-75): a checkpoint per tenant and interval, and one
  * timestamp anchor per tenant and day.
  *
  * Every instance calls [[tick]]; idempotency keys per tenant and period make sure each job exists only once,
  * and the job queue makes sure only one worker runs it. Without trusted TSA certificates `anchoring` is
  * `None` and only checkpoints are scheduled.
  */
final class AuditJobs(
    db: Db,
    anchoring: Option[AuditAnchoring],
    checkpoints: AuditCheckpoints,
    config: AuditScheduleConfig
):
  import AuditJobs.*

  /** Handlers to register with the job runner. */
  val handlers: Map[String, ClaimedJob => IO[String, Unit]] =
    Map(CheckpointKind -> forTenant(checkpoints.write(_).unit)) ++
      anchoring.map(a => AnchorKind -> forTenant(a.anchor(_).unit))

  /** Enqueues the jobs that are due at `now` for all tenants. Returns how many jobs were newly enqueued. */
  def tick(now: Instant): IO[DbError, Int] =
    val window   = now.getEpochSecond / config.checkpointInterval.toSeconds.max(1)
    val day      = LocalDate.ofInstant(now, ZoneOffset.UTC)
    val anchorAt = day.atTime(config.anchorHourUtc, 0).toInstant(ZoneOffset.UTC)
    JobQueue.tenantIds(db).flatMap { tenants =>
      ZIO
        .foreach(tenants) { tenant =>
          db.transact(tenant)(
            for
              checkpoint <- JobQueue.enqueue(
                CheckpointKind,
                idempotencyKey = Some(s"$CheckpointKind:${tenant.value}:$window"),
                maxAttempts = 5
              )
              anchor <- ZIO.foreach(anchoring)(_ =>
                JobQueue.enqueue(
                  AnchorKind,
                  idempotencyKey = Some(s"$AnchorKind:${tenant.value}:$day"),
                  runAt = Some(anchorAt),
                  maxAttempts = 24
                )
              )
            yield checkpoint.size + anchor.flatten.size
          )
        }
        .map(_.sum)
    }

  /** Calls [[tick]] every checkpoint interval; failures are logged and retried on the next tick. */
  def schedule: UIO[Nothing] =
    (Clock.instant.flatMap(tick).catchAll(e => ZIO.logWarning(s"Scheduling audit jobs failed: $e").as(0)) *>
      ZIO.sleep(config.checkpointInterval)).forever

object AuditJobs:
  val CheckpointKind = "audit.checkpoint"
  val AnchorKind     = "audit.anchor"

  private def forTenant(work: TenantId => IO[Any, Unit]): ClaimedJob => IO[String, Unit] = job =>
    job.tenant match
      case Some(tenant) => work(tenant).mapError(_.toString)
      case None         => ZIO.fail(s"job ${job.id} (${job.kind}) has no tenant")
