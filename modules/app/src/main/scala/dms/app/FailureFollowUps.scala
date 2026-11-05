package dms.app

import com.augustnagro.magnum.sql
import dms.audit.*
import dms.db.queue.{ClaimedJob, JobQueue, Outbox, OutboxEvent}
import dms.db.{Db, DbError, Jsonb}
import dms.documents.Publication
import dms.documents.core.PublicationJobs
import dms.kernel.TenantId
import dms.rendering.RenditionRequest
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.UUID

/** @param days
  *   days after a job failed for good on which a reminder is due, ascending; empty: no reminders. The
  *   business concept (8.3) has +3 and +7 days for what stays open
  * @param checkInterval
  *   how often the reminder check of every tenant runs; a reminder is sent at most this long after it is due
  */
final case class FailureReminderConfig(
    days: List[Int] = FailureReminderConfig.DefaultDays,
    checkInterval: Duration = 1.hour
)

object FailureReminderConfig:
  val DefaultDays: List[Int] = List(3, 7)

/** What follows when a rendering or publication job failed for good, besides the first notice of ADR 0058
  * (DMS-146, ADR 0064).
  *
  *   - **Audit.** [[deliver]], an outbox subscriber, records `document.publication-failed` for every
  *     `document.publication-dead`: the database announces a dead job on every path, a worker that crashed
  *     included, and the hash of an audit entry can only be computed here. The relay delivers at least once,
  *     so the entry names its event and a repeated delivery adds none.
  *   - **Reminder.** Every instance calls [[tick]], which enqueues one job
  *     `notifications.failure-reminder-check` per tenant and interval. While a version still waits for a dead
  *     job, [[check]] publishes one `document.failure-reminder` per reminder day that has come, recorded per
  *     death of the job in `dms.job_failure_reminder` (migration V44), so a repeated check and several
  *     instances send it once. Missed days are not caught up. A retry ends the reminders; dying again starts
  *     them anew. Who is reminded is decided on delivery ([[PlatformNotificationEvents]]): those not having
  *     read a notice of this failure.
  */
final class FailureFollowUps(db: Db, audit: AuditTrail, config: FailureReminderConfig):
  import FailureFollowUps.*

  /** Handlers to register with the job runner. */
  val handlers: Map[String, ClaimedJob => IO[String, Unit]] = Map(CheckKind -> run)

  /** Outbox subscriber: the audit entry of a publication that failed for good. */
  def deliver(event: OutboxEvent): IO[String, Unit] =
    ZIO
      .when(event.eventType == Publication.DeadEvent)(
        ZIO
          .fromEither(event.payload.value.fromJson[Json.Obj])
          .flatMap(payload => db.transact(event.tenant)(recordDeath(event, payload)).mapError(_.toString))
      )
      .unit

  private def recordDeath(event: OutboxEvent, payload: Json.Obj): IO[DbError, Unit] =
    for
      recorded <- Db.sql(
        sql"""select exists(select 1 from dms.audit_event
                            where object_id = ${event.aggregateId} and action = $PublicationFailed
                              and details ->> 'eventId' = ${event.id.toString})""".query[Boolean].run().head
      )
      _ <- ZIO.unless(recorded)(
        audit.record(
          AuditEvent(
            Actor(PublicationActor, ActorKind.Technical),
            PublicationFailed,
            AuditObject("document", Some(event.aggregateId)),
            Channel.Automation,
            // The code only, like the event: the error text can quote document content (ADR 0058).
            Jsonb(
              Json
                .Obj(
                  Chunk("versionId", "jobId", "attempts", "reason")
                    .flatMap(k => payload.get(k).map(k -> _)) :+ ("eventId" -> Json.Str(event.id.toString))
                )
                .toJson
            )
          )
        )
      )
    yield ()

  /** Enqueues the reminder check of every tenant for the current interval. Returns the number of new jobs. */
  def tick: IO[DbError, Int] =
    if config.days.isEmpty then ZIO.succeed(0)
    else
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
    (tick.catchAll(e => ZIO.logWarning(s"Scheduling the failure reminder check failed: $e").as(0)) *>
      ZIO.sleep(config.checkInterval)).forever

  /** The reminder check of one tenant at `now`: returns the number of reminders published. */
  def check(tenant: TenantId, now: Instant): IO[DbError, Int] =
    db.transact(tenant)(
      openFailures.flatMap(failures =>
        ZIO
          .foreach(failures)(f =>
            dueRound(f.failedAt, now, config.days)
              .filter(_ > f.lastRound)
              .fold(ZIO.succeed(false))(remind(f, _))
          )
          .map(_.count(identity))
      )
    )

  private def remind(failure: OpenFailure, round: Int): IO[DbError, Boolean] =
    Db.sql(
      sql"""insert into dms.job_failure_reminder (tenant_id, job_id, failed_at, round)
            values (dms.current_tenant(), ${failure.jobId}, ${utc(failure.failedAt)}, $round)
            on conflict do nothing""".update.run() > 0
    ).tap(recorded =>
      ZIO.when(recorded)(
        Outbox.publish(
          "document",
          failure.documentId,
          ReminderEvent,
          Jsonb(
            Json
              .Obj(
                "documentId" -> Json.Str(failure.documentId.toString),
                "versionId"  -> Json.Str(failure.versionId.toString),
                "jobId"      -> Json.Num(failure.jobId),
                "jobKind"    -> Json.Str(failure.kind),
                "reason"     -> failure.reason.fold(Json.Null)(Json.Str(_)),
                "failedAt"   -> Json.Str(failure.failedAt.toString),
                "reminder"   -> Json.Str(round.toString)
              )
              .toJson
          )
        )
      )
    )

  private def run(job: ClaimedJob): IO[String, Unit] =
    for
      tenant <- ZIO.fromOption(job.tenant).orElseFail(s"job ${job.id} ($CheckKind) has no tenant")
      now    <- Clock.instant
      _      <- check(tenant, now).mapError(_.toString)
    yield ()

object FailureFollowUps:
  val CheckKind = "notifications.failure-reminder-check"

  /** Outbox event: a version still waits for a dead job on a reminder day. Payload: `documentId`,
    * `versionId`, `jobId`, `jobKind` ([[RenditionJob]] or [[PublicationJob]]), `reason`, `failedAt` and
    * `reminder` (the round).
    */
  val ReminderEvent = "document.failure-reminder"

  val RenditionJob   = "rendition"
  val PublicationJob = "publication"

  /** Audit action of a publication job that is dead. */
  val PublicationFailed = "document.publication-failed"

  /** The technical actor of scheduled publications in the audit trail. */
  val PublicationActor = "dms-publication"

  /** How many reminder days have come since `failedAt`; `None` before the first. */
  def dueRound(failedAt: Instant, now: Instant, days: List[Int]): Option[Int] =
    Some(days.count(d => !failedAt.plus(java.time.Duration.ofDays(d.toLong)).isAfter(now))).filter(_ > 0)

  private final case class OpenFailure(
      jobId: Long,
      kind: String,
      failedAt: Instant,
      documentId: UUID,
      versionId: UUID,
      reason: Option[String],
      lastRound: Int
  )

  /** Dead jobs a version still waits for, as the failure lists find them, with the last reminder of this
    * death.
    */
  private def openFailures: IO[DbError, List[OpenFailure]] =
    Db.sql(
      sql"""select j.id, j.kind, j.finished_at, v.document_id, v.id, dms.job_failure_code(j.last_error),
                   coalesce((select max(r.round) from dms.job_failure_reminder r
                             where r.job_id = j.id and r.failed_at = j.finished_at), 0)
            from dms.job j
            join dms.document_version v on v.id::text = j.payload ->> 'versionId'
            where j.status = 'dead' and j.kind in ('document.render-rendition', 'document.publish')
              and j.finished_at is not null
              and ((j.kind = ${RenditionRequest.JobKind} and v.status = 'InApproval' and v.rendition_key is null)
                or (j.kind = ${PublicationJobs.PublishKind} and v.status = 'Approved'
                    and j.id = (select max(newest.id) from dms.job newest
                                where newest.kind = j.kind
                                  and newest.payload ->> 'versionId' = j.payload ->> 'versionId')))
            order by j.id"""
        .query[(Long, String, OffsetDateTime, UUID, UUID, Option[String], Int)]
        .run()
        .toList
        .map { case (id, kind, failedAt, document, version, reason, round) =>
          OpenFailure(
            id,
            if kind == RenditionRequest.JobKind then RenditionJob else PublicationJob,
            failedAt.toInstant,
            document,
            version,
            reason,
            round
          )
        }
    )

  private def utc(i: Instant): OffsetDateTime = OffsetDateTime.ofInstant(i, ZoneOffset.UTC)
