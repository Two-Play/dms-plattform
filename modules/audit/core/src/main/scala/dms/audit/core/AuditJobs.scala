package dms.audit.core

import dms.audit.AuditEvidence
import dms.db.queue.{ClaimedJob, JobQueue}
import dms.db.{Db, DbError}
import dms.kernel.TenantId
import zio.*

import java.time.{Instant, LocalDate, ZoneOffset}

/** @param checkpointInterval
  *   how often the chain head of every tenant is checkpointed; this is the unprotected window (ADR 0017)
  * @param anchorHourUtc
  *   hour of the day (UTC) at which the daily RFC 3161 anchor is due
  * @param verifyHourUtc
  *   hour of the day (UTC) at which the daily full verification is due; after the anchor, so that the
  *   verified prefix reaches it
  * @param violationReminderDays
  *   days after the first notice of a violation on which a reminder is due while it stays open, ascending;
  *   empty: no reminders
  */
final case class AuditScheduleConfig(
    checkpointInterval: Duration = 5.minutes,
    anchorHourUtc: Int = 2,
    verifyHourUtc: Int = 3,
    violationReminderDays: List[Int] = AuditScheduleConfig.DefaultReminderDays
)

object AuditScheduleConfig:
  /** As for what stays open after a failed job (business concept 8.3, ADR 0064). */
  val DefaultReminderDays: List[Int] = List(3, 7)

/** Schedules and runs the periodic audit work as jobs (DMS-75): a checkpoint per tenant and interval, one
  * timestamp anchor and one full verification of the chain per tenant and day (ADR 0077).
  *
  * Every instance calls [[tick]]; idempotency keys per tenant and period make sure each job exists only once,
  * and the job queue makes sure only one worker runs it. Without trusted TSA certificates `anchoring` is
  * `None` and only checkpoints are scheduled.
  */
final class AuditJobs(
    db: Db,
    anchoring: Option[AuditAnchoring],
    checkpoints: AuditCheckpoints,
    evidence: AuditEvidence,
    config: AuditScheduleConfig
):
  import AuditJobs.*

  /** Handlers to register with the job runner. */
  val handlers: Map[String, ClaimedJob => IO[String, Unit]] =
    Map(CheckpointKind -> forTenant(checkpoints.write(_).unit), VerifyKind -> forTenant(verify)) ++
      anchoring.map(a => AnchorKind -> forTenant(a.anchor(_).unit))

  /** Enqueues the jobs that are due at `now` for all tenants. Returns how many jobs were newly enqueued. */
  def tick(now: Instant): IO[DbError, Int] =
    val window   = now.getEpochSecond / config.checkpointInterval.toSeconds.max(1)
    val day      = LocalDate.ofInstant(now, ZoneOffset.UTC)
    val anchorAt = day.atTime(config.anchorHourUtc, 0).toInstant(ZoneOffset.UTC)
    val verifyAt = day.atTime(config.verifyHourUtc, 0).toInstant(ZoneOffset.UTC)
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
              verify <- JobQueue.enqueue(
                VerifyKind,
                idempotencyKey = Some(s"$VerifyKind:${tenant.value}:$day"),
                runAt = Some(verifyAt),
                maxAttempts = 3
              )
            yield checkpoint.size + anchor.flatten.size + verify.size
          )
        }
        .map(_.sum)
    }

  /** Calls [[tick]] every checkpoint interval; failures are logged and retried on the next tick. */
  def schedule: UIO[Nothing] =
    (Clock.instant.flatMap(tick).catchAll(e => ZIO.logWarning(s"Scheduling audit jobs failed: $e").as(0)) *>
      ZIO.sleep(config.checkpointInterval)).forever

  /** The whole chain, recorded as a run. A violation is a finding, not a failure of the job: it stays on
    * record, and from then on every report verifies the whole chain and shows it. The run tells people of a
    * new violation; a run that still finds it reminds them on the reminder days (ADR 0080).
    */
  private def verify(tenant: TenantId): IO[DbError, Unit] =
    evidence.verifyFully(tenant).flatMap { state =>
      ZIO.foreachDiscard(state.verification.violation)(v =>
        ZIO.logError(
          s"Audit chain of tenant ${tenant.value} violated at position ${v.chainSeq}: ${v.kind} (${v.detail})"
        ) *> Clock.instant
          .flatMap(now => db.transact(tenant)(ViolationNotices.remind(now, config.violationReminderDays)))
      )
    }

object AuditJobs:
  val CheckpointKind = "audit.checkpoint"
  val AnchorKind     = "audit.anchor"
  val VerifyKind     = "audit.verify"

  private def forTenant(work: TenantId => IO[Any, Unit]): ClaimedJob => IO[String, Unit] = job =>
    job.tenant match
      case Some(tenant) => work(tenant).mapError(_.toString)
      case None         => ZIO.fail(s"job ${job.id} (${job.kind}) has no tenant")
