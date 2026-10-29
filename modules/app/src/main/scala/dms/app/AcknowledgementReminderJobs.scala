package dms.app

import dms.authz.{Audience, Groups, Population, RoleAssignments}
import dms.db.queue.{ClaimedJob, JobQueue}
import dms.db.{Db, DbError}
import dms.documents.AcknowledgementReminders
import dms.documents.core.{
  AcknowledgementError,
  AcknowledgementService,
  DocumentError,
  DocumentService,
  ReminderTarget
}
import dms.kernel.TenantId
import zio.*

import java.time.Instant

/** @param defaultDays
  *   the reminder interval where neither the class nor the tenant sets one; 0: no automatic reminders
  * @param checkInterval
  *   how often the reminder check of every tenant runs; a reminder is sent at most this long after it is due
  */
final case class AcknowledgementReminderConfig(
    defaultDays: Int = AcknowledgementReminderConfig.DefaultDays,
    checkInterval: Duration = 1.hour
)

object AcknowledgementReminderConfig:
  val DefaultDays: Int = 14

/** Automatic reminders of open acknowledgements (DMS-100, ADR 0048) through the job queue (ADR 0010).
  *
  * Every instance calls [[tick]], which enqueues one job `documents.acknowledgement-reminder-check` per
  * tenant and interval (idempotency key, so one job cluster-wide). The job ([[check]]) looks at every valid
  * version that requires acknowledgement: once a whole reminder interval has passed since its settings were
  * applied (publication), the recipients who have not acknowledged it get reminder round 1, after two
  * intervals round 2, up to [[AcknowledgementReminders.MaxAutomatic]]. Every reminder is recorded per
  * version, reader and round (`dms.acknowledgement_reminder`), so a retried or concurrent job sends none
  * twice; missed rounds are not caught up. The reminders go to the outbox
  * (`document.acknowledgement-reminder`); delivering them (mail, in-app) is the job of the notifications
  * module.
  */
final class AcknowledgementReminderJobs(
    db: Db,
    documents: DocumentService,
    acks: AcknowledgementService,
    roles: RoleAssignments,
    groups: Groups,
    config: AcknowledgementReminderConfig
):
  import AcknowledgementReminderJobs.*

  /** Handlers to register with the job runner. */
  val handlers: Map[String, ClaimedJob => IO[String, Unit]] = Map(CheckKind -> run)

  /** Enqueues the reminder check of every tenant for the current interval. Returns the number of new jobs. */
  def tick: IO[DbError, Int] =
    for
      now     <- Clock.instant
      tenants <- JobQueue.tenantIds(db)
      slot = now.toEpochMilli / config.checkInterval.toMillis.max(1)
      added <- ZIO.foreach(tenants)(tenant =>
        db.transact(tenant)(
          JobQueue
            .enqueue(CheckKind, idempotencyKey = Some(s"$CheckKind:${tenant.value}:$slot"), maxAttempts = 5)
        )
      )
    yield added.flatten.size

  /** Calls [[tick]] every check interval; failures are logged and retried on the next tick. */
  def schedule: UIO[Nothing] =
    (tick.catchAll(e => ZIO.logWarning(s"Scheduling the acknowledgement reminder check failed: $e").as(0)) *>
      ZIO.sleep(config.checkInterval)).forever

  /** The reminder check of one tenant at `now`: returns the number of readers reminded. */
  def check(tenant: TenantId, now: Instant): IO[String, Int] =
    db.transact(tenant)(remind(tenant, now)).mapError {
      case e: DocumentError        => e.toString
      case e: AcknowledgementError => e.describe
      case e: DbError              => e.toString
    }

  private def remind(tenant: TenantId, now: Instant) =
    for
      valid     <- documents.currentlyValid
      audiences <- acks.audiencesOfValid
      due = valid.filter(_.requiresAcknowledgement).flatMap { v =>
        val audience = audiences.get(v.versionId)
        val days     = audience.flatMap(_.reminderDays).getOrElse(config.defaultDays)
        audience
          .map(_.recordedAt)
          .orElse(v.publishedAt)
          .flatMap(since => AcknowledgementReminders.dueRound(since, now, days))
          .map(round => (v, round))
      }
      sent <-
        if due.isEmpty then ZIO.succeed(Nil)
        else
          for
            assignments  <- roles.activeAt(now)
            memberships  <- groups.currentMemberships
            acknowledged <- acks.acknowledgersOfValid
            readers = Audience(Population(tenant, assignments, memberships), now)
            sent <- ZIO.foreach(due) { (v, round) =>
              val done = acknowledged.getOrElse(v.versionId, Set.empty)
              val open = readers
                .readers(ComplianceApi.resource(tenant, v), Recipients.filterOf(audiences.get(v.versionId)))
                .members
                .filterNot(done.contains)
              ZIO
                .unless(open.isEmpty)(
                  acks.remindAutomatically(
                    ReminderTarget(v.document.id, v.versionId, v.label, v.document.title),
                    open,
                    round
                  )
                )
                .map(_.fold(0)(_.size))
            }
          yield sent
    yield sent.sum

  private def run(job: ClaimedJob): IO[String, Unit] =
    for
      tenant <- ZIO.fromOption(job.tenant).orElseFail(s"job ${job.id} ($CheckKind) has no tenant")
      now    <- Clock.instant
      _      <- check(tenant, now)
    yield ()

object AcknowledgementReminderJobs:
  val CheckKind = "documents.acknowledgement-reminder-check"
