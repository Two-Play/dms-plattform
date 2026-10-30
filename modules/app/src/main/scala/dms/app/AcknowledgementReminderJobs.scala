package dms.app

import dms.authz.{Audience, Groups, Population, RoleAssignments}
import dms.db.queue.{ClaimedJob, JobQueue, OutboxEvent}
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
import zio.json.*
import zio.json.ast.Json

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
  * intervals round 2, up to [[AcknowledgementReminders.MaxAutomatic]]. Since DMS-130 the job also asks
  * recipients who were not asked yet (`document.acknowledgement-requested`; at publication and people who
  * become recipients later), and each recipient's rounds count from when they were asked, so a late joiner is
  * not reminded right after joining. A publication triggers a check at once ([[deliver]]). Every reminder is
  * recorded per version, reader and round (`dms.acknowledgement_reminder`), so a retried or concurrent job
  * sends none twice; missed rounds are not caught up. The reminders go to the outbox
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

  /** Outbox subscriber (DMS-130): a version that requires acknowledgement became valid, so its recipients are
    * asked by a check of the tenant right away instead of at the next interval.
    */
  def deliver(event: OutboxEvent): IO[String, Unit] =
    val published = event.eventType == "document.status-changed" &&
      event.payload.value
        .fromJson[Json.Obj]
        .toOption
        .exists(p =>
          p.get("status").contains(Json.Str("Published")) && p
            .get("requiresAcknowledgement")
            .contains(Json.Bool(true))
        )
    ZIO
      .when(published)(
        db.transact(event.tenant)(
          JobQueue.enqueue(
            CheckKind,
            idempotencyKey = Some(s"$CheckKind:${event.tenant.value}:event:${event.id}"),
            maxAttempts = 5
          )
        ).mapError(_.toString)
      )
      .unit

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
      valid     <- documents.currentlyValid.map(_.filter(_.requiresAcknowledgement))
      audiences <- acks.audiencesOfValid
      sent      <-
        if valid.isEmpty then ZIO.succeed(Nil)
        else
          for
            assignments  <- roles.activeAt(now)
            memberships  <- groups.currentMemberships
            acknowledged <- acks.acknowledgersOfValid
            requested    <- acks.requests(valid.map(_.versionId).toSet)
            baselines    <- acks.requestBaselines
            readers = Audience(Population(tenant, assignments, memberships), now)
            sent <- ZIO.foreach(valid) { v =>
              val audience = audiences.get(v.versionId)
              val done     = acknowledged.getOrElse(v.versionId, Set.empty)
              val open     = readers
                .readers(ComplianceApi.resource(tenant, v), Recipients.filterOf(audience))
                .members
                .filterNot(done.contains)
              val since  = audience.map(_.recordedAt).orElse(v.publishedAt).getOrElse(now)
              val target = ReminderTarget(v.document.id, v.versionId, v.label, v.document.title)
              val asked  = requested.getOrElse(v.versionId, Map.empty)
              // Recipients of versions valid before V34 count as asked at publication, without a notice. The
              // first request of a version also counts from publication (the recipients it finds were
              // recipients then); later ones are people who became recipients since.
              val baseline = baselines.contains(v.versionId)
              val askAt    = if baseline || asked.isEmpty then since else now
              val unasked  = open.filterNot(asked.contains)
              for
                fresh <-
                  if unasked.isEmpty && !baseline then ZIO.succeed(Nil)
                  else acks.request(target, unasked, askAt, notify = !baseline)
                askedAt = asked ++ fresh.map(_ -> askAt)
                days    = audience.flatMap(_.reminderDays).getOrElse(config.defaultDays)
                // Each recipient's rounds count from when they were asked, at the earliest from publication:
                // people who become recipients later are asked first and reminded a whole interval later.
                rounds = open
                  .flatMap(reader =>
                    val from = askedAt.get(reader).fold(now)(at => if at.isAfter(since) then at else since)
                    AcknowledgementReminders.dueRound(from, now, days).map(reader -> _)
                  )
                  .groupMap(_._2)(_._1)
                reminded <- ZIO.foreach(rounds.toList.sortBy(_._1)) { (round, people) =>
                  acks.remindAutomatically(target, people, round).map(_.size)
                }
              yield reminded.sum
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
