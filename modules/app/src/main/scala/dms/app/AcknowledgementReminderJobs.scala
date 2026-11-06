package dms.app

import dms.authz.{Audience, Groups, Population, RoleAssignments}
import dms.db.queue.{ClaimedJob, JobQueue, OutboxEvent}
import dms.db.{Db, DbError, Jsonb}
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
import java.util.UUID

/** @param defaultDays
  *   the reminder interval where neither the class nor the tenant sets one; 0: no automatic reminders
  * @param checkInterval
  *   how often the reminder check of every tenant runs; a reminder is sent at most this long after it is due
  * @param pageSize
  *   how many versions one job of the check looks at; the rest goes to the next job (DMS-137)
  */
final case class AcknowledgementReminderConfig(
    defaultDays: Int = AcknowledgementReminderConfig.DefaultDays,
    checkInterval: Duration = 1.hour,
    pageSize: Int = AcknowledgementReminderConfig.DefaultPageSize
)

object AcknowledgementReminderConfig:
  val DefaultDays: Int     = 14
  val DefaultPageSize: Int = 200

/** Automatic reminders of open acknowledgements (DMS-100, ADR 0048) through the job queue (ADR 0010).
  *
  * Every instance calls [[tick]], which enqueues one job `documents.acknowledgement-reminder-check` per
  * tenant and interval (idempotency key, so one job cluster-wide). The check looks at every valid version
  * that requires acknowledgement: once a whole reminder interval has passed since its settings were applied
  * (publication), the recipients who have not acknowledged it get reminder round 1, after two intervals round
  * 2, up to [[AcknowledgementReminders.MaxAutomatic]]. Since DMS-130 it also asks recipients who were not
  * asked yet (`document.acknowledgement-requested`; at publication and people who become recipients later),
  * and each recipient's rounds count from when they were asked, so a late joiner is not reminded right after
  * joining. A publication triggers a check of that version at once ([[deliver]]). Every reminder is recorded
  * per version, reader and round (`dms.acknowledgement_reminder`), so a retried or concurrent job sends none
  * twice; missed rounds are not caught up. The reminders go to the outbox
  * (`document.acknowledgement-reminder`); delivering them (mail, in-app) is the job of the notifications
  * module.
  *
  * At full volume (DMS-137, ADR 0079) nothing here grows with the tenant. A job handles one page of
  * [[AcknowledgementReminderConfig.pageSize]] versions: in the short transaction that reads the page it
  * enqueues the job of the next page, so the check goes on whatever becomes of this page, and the tenants
  * take turns in the lane of these jobs (`Background`). Each version then has one transaction of its own,
  * which reads that version only and records its requests, reminders, audit entries and outbox events
  * together: a crash loses or doubles nothing, it leaves a version either done or untouched, and a retry
  * skips what is recorded. A version that fails does not stop the others of its page; the job fails
  * afterwards and the retry repeats the page.
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
    * asked right away instead of at the next interval, by a check of that version: reading every
    * acknowledgement of the tenant per publication does not scale (DMS-60).
    */
  def deliver(event: OutboxEvent): IO[String, Unit] =
    val published = Option
      .when(event.eventType == "document.status-changed")(event.payload.value.fromJson[Json.Obj].toOption)
      .flatten
      .filter(p =>
        p.get("status").contains(Json.Str("Published")) && p
          .get("requiresAcknowledgement")
          .contains(Json.Bool(true))
      )
    ZIO
      .foreach(published)(p =>
        db.transact(event.tenant)(
          JobQueue.enqueue(
            CheckKind,
            payload = Jsonb(Json.Obj(p.fields.filter(_._1 == VersionKey)).toJson),
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

  /** The whole reminder check of one tenant at `now`, or of one of its versions, page by page in this call:
    * returns the number of readers reminded. The jobs do the same with a job per page.
    */
  def check(tenant: TenantId, now: Instant, only: Option[UUID] = None): IO[String, Int] =
    def from(after: Option[UUID], sum: Int): IO[String, Int] =
      inTenant(tenant)(documents.awaitingAcknowledgement(after, pageSize)).flatMap { page =>
        remindAll(tenant, now, page).flatMap(sent =>
          if page.size < pageSize then ZIO.succeed(sum + sent) else from(page.lastOption, sum + sent)
        )
      }
    only.fold(from(None, 0))(version => remindAll(tenant, now, List(version)))

  private val pageSize = config.pageSize.max(1)

  /** Checks one version after the other, each in a transaction of its own. The readers are computed once:
    * what they rest on is read for the whole tenant, and in a transaction that writes nothing.
    */
  private def remindAll(tenant: TenantId, now: Instant, versions: List[UUID]): IO[String, Int] =
    if versions.isEmpty then ZIO.succeed(0)
    else
      for
        readers <- inTenant(tenant)(
          for
            assignments <- roles.activeAt(now)
            memberships <- groups.currentMemberships
          yield Audience(Population(tenant, assignments, memberships), now)
        )
        results <- ZIO.foreach(versions)(version =>
          inTenant(tenant)(remind(tenant, now, readers, version))
            // Only the kind of a defect: its message could quote field values.
            .catchAllDefect(e => ZIO.fail(s"${e.getClass.getSimpleName} at version $version"))
            .either
        )
        failed = results.collect { case Left(e) => e }
        _ <- ZIO
          .fail(s"${failed.size} of ${versions.size} versions failed, the first with: ${failed.head}")
          .when(failed.nonEmpty)
      yield results.collect { case Right(sent) => sent }.sum

  /** Asks and reminds the recipients of one version; runs inside `Db.transact(tenant)`. */
  private def remind(tenant: TenantId, now: Instant, readers: Audience, version: UUID) =
    documents.awaitingAcknowledgement(version).flatMap {
      case None    => ZIO.succeed(0)
      case Some(v) =>
        for
          audience <- acks.audienceOf(v.versionId)
          done     <- acks.acknowledgersOf(v.versionId)
          asked    <- acks.requestsOf(v.versionId)
          baseline <- acks.hasRequestBaseline(v.versionId)
          open = readers
            .readers(ComplianceApi.resource(tenant, v), Recipients.filterOf(audience))
            .members
            .filterNot(done.contains)
          since  = audience.map(_.recordedAt).orElse(v.publishedAt).getOrElse(now)
          target = ReminderTarget(v.document.id, v.versionId, v.label, v.document.title)
          // Recipients of versions valid before V34 count as asked at publication, without a notice. The first
          // request of a version also counts from publication (the recipients it finds were recipients then);
          // later ones are people who became recipients since.
          askAt   = if baseline || asked.isEmpty then since else now
          unasked = open.filterNot(asked.contains)
          fresh <-
            if unasked.isEmpty && !baseline then ZIO.succeed(Nil)
            else acks.request(target, unasked, askAt, notify = !baseline)
          askedAt = asked ++ fresh.map(_ -> askAt)
          days    = audience.flatMap(_.reminderDays).getOrElse(config.defaultDays)
          // Each recipient's rounds count from when they were asked, at the earliest from publication: people
          // who become recipients later are asked first and reminded a whole interval later.
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

  /** A job: one version, or the page after `after`, whose first step is the job of the following page. */
  private def run(job: ClaimedJob): IO[String, Unit] =
    val payload         = job.payload.value.fromJson[Json.Obj].toOption
    def id(key: String) =
      payload
        .flatMap(_.get(key))
        .flatMap(_.asString)
        .flatMap(id => scala.util.Try(UUID.fromString(id)).toOption)
    for
      tenant <- ZIO.fromOption(job.tenant).orElseFail(s"job ${job.id} ($CheckKind) has no tenant")
      now    <- Clock.instant
      _      <- id(VersionKey) match
        case Some(version) => remindAll(tenant, now, List(version))
        case None          =>
          // All jobs of a check share the id of the first, so a retry enqueues no second chain.
          val first =
            payload.flatMap(_.get(RunKey)).flatMap(_.asNumber).map(_.value.longValue).getOrElse(job.id)
          inTenant(tenant)(
            documents
              .awaitingAcknowledgement(id(AfterKey), pageSize)
              .tap(page =>
                ZIO.foreachDiscard(page.lastOption.filter(_ => page.size == pageSize))(
                  enqueueNext(tenant, first, _)
                )
              )
          ).flatMap(remindAll(tenant, now, _))
    yield ()

  private def enqueueNext(tenant: TenantId, first: Long, after: UUID) =
    JobQueue.enqueue(
      CheckKind,
      payload = Jsonb(Json.Obj(AfterKey -> Json.Str(after.toString), RunKey -> Json.Num(first)).toJson),
      idempotencyKey = Some(s"$CheckKind:${tenant.value}:$first:after:$after"),
      maxAttempts = 5
    )

  private def inTenant[A](tenant: TenantId)(
      work: ZIO[Any, DocumentError | AcknowledgementError | DbError, A]
  ): IO[String, A] =
    db.transact(tenant)(work).mapError {
      case e: DocumentError        => e.toString
      case e: AcknowledgementError => e.describe
      case e: DbError              => e.toString
    }

object AcknowledgementReminderJobs:
  val CheckKind = "documents.acknowledgement-reminder-check"

  /** Key of the version in the payloads of the outbox event and of the check. */
  private val VersionKey = "versionId"

  /** Keys of the payload of a later page: the last version of the page before, and the id of the first job.
    */
  private val AfterKey = "after"
  private val RunKey   = "run"
