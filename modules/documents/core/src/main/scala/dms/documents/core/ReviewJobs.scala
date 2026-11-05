package dms.documents.core

import dms.db.queue.{ClaimedJob, JobQueue}
import dms.db.{Db, DbError, Jsonb}
import dms.documents.metadata.DocumentClassId
import dms.kernel.TenantId
import zio.*
import zio.json.*

import java.util.UUID

/** @param checkInterval
  *   how often the review check of every tenant runs; a review task appears at most this long after its lead
  *   time is reached (00:00 of that day in the tenant's time zone)
  */
final case class ReviewScheduleConfig(checkInterval: Duration = 1.hour)

/** What one review check did. */
final case class ReviewCheck(
    opened: Int,
    reviewDue: Int,
    closed: Int,
    reopened: Int = 0,
    scheduled: Int = 0
)

/** Runs the periodic review (US-07.1, ADR 0030) through the job queue (ADR 0010).
  *
  * Every instance calls [[tick]]: it enqueues one job `document.review-check` per tenant and check interval
  * (idempotency key per tenant and interval, so one job cluster-wide). The job ([[check]]) creates the review
  * tasks whose lead time is reached, moves published versions whose review date has come to ReviewDue, closes
  * open tasks of versions that are not valid any more and opens tasks again whose revision never took effect
  * (for those from before DMS-145, ADR 0061). It also gives a review date to valid versions that have none
  * although their class has an interval (ADR 0068): that backfills the versions from before V18 and covers a
  * class that gets an interval later. Each item runs in its own transaction, so one failing version does not
  * block the others; every step is idempotent, so at-least-once delivery is safe.
  *
  * A change of the interval in force of a class enqueues `document.review-recalculation`
  * ([[ReviewJobs.requestRecalculation]]), which computes the review dates it reaches again ([[recalculate]]).
  */
final class ReviewJobs(db: Db, service: ReviewService, config: ReviewScheduleConfig):
  import ReviewJobs.*

  /** Handlers to register with the job runner. */
  val handlers: Map[String, ClaimedJob => IO[String, Unit]] =
    Map(CheckKind -> run, RecalculationKind -> runRecalculation)

  /** Enqueues the review check of every tenant for the current interval. Returns the number of new jobs. */
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
    (tick.catchAll(e => ZIO.logWarning(s"Scheduling the review check failed: $e").as(0)) *>
      ZIO.sleep(config.checkInterval)).forever

  /** The review check of one tenant. Fails (so that the job is retried) if any item failed; the others are
    * done.
    */
  def check(tenant: TenantId): IO[String, ReviewCheck] =
    for
      work     <- inTenant(tenant)(service.pendingWork)
      closed   <- each(tenant, work.obsolete)(service.closeObsolete)
      reopened <- each(tenant, work.toReopen)(service.reopen)
      dated    <- each(tenant, work.withoutDate)(service.scheduleMissing)
      opened   <- each(tenant, work.toOpen)(service.openTask)
      due      <- each(tenant, work.reviewDue)(service.markReviewDue)
      failures = List(closed, reopened, dated, opened, due).flatMap(_._2)
      _ <- ZIO.unless(failures.isEmpty)(ZIO.fail(failures.mkString("; ")))
    yield ReviewCheck(opened._1, due._1, closed._1, reopened._1, dated._1)

  /** Computes the review dates that a change of a class version reaches again. Returns how many dates were
    * moved or kept against the new interval. Fails (so that the job is retried) if any version failed; the
    * others are done and recorded, so a retry leaves them alone.
    */
  def recalculate(tenant: TenantId, request: Recalculation): IO[String, Int] =
    for
      versions <- inTenant(tenant)(service.reachedBy(request.classId))
      result   <- each(tenant, versions)(service.recalculate(_, request.classId, request.classVersion))
      _        <- ZIO.unless(result._2.isEmpty)(ZIO.fail(result._2.mkString("; ")))
    yield result._1

  private def run(job: ClaimedJob): IO[String, Unit] =
    ZIO.fromOption(job.tenant).orElseFail(s"job ${job.id} ($CheckKind) has no tenant").flatMap(check).unit

  private def runRecalculation(job: ClaimedJob): IO[String, Unit] =
    for
      tenant  <- ZIO.fromOption(job.tenant).orElseFail(s"job ${job.id} ($RecalculationKind) has no tenant")
      request <- ZIO.fromEither(job.payload.value.fromJson[Recalculation])
      _       <- recalculate(tenant, request)
    yield ()

  /** Runs `f` for every id in its own transaction; returns how many did something and the failures. */
  private def each[A](tenant: TenantId, ids: List[UUID])(
      f: UUID => IO[DocumentError, Option[A]]
  ): UIO[(Int, List[String])] =
    ZIO
      .foreach(ids)(id => inTenant(tenant)(f(id)).either.map(_.left.map(e => s"$id: $e")))
      .map(results => (results.count(_.exists(_.isDefined)), results.collect { case Left(e) => e }))

  private def inTenant[A](tenant: TenantId)(f: IO[DocumentError, A]): IO[String, A] =
    db.transact(tenant)(f).mapError {
      case e: DocumentError => e.describe
      case e: DbError       => e.toString
    }

object ReviewJobs:
  val CheckKind         = "document.review-check"
  val RecalculationKind = "document.review-recalculation"

  /** The class version whose change of the interval in force is to reach the review dates. */
  final case class Recalculation(classId: UUID, classVersion: Int) derives JsonCodec

  /** Enqueues the recalculation of a class version, in the transaction of the change. */
  private[core] def requestRecalculation(id: DocumentClassId, version: Int): IO[DbError, Unit] =
    JobQueue
      .enqueue(
        RecalculationKind,
        payload = Jsonb(Recalculation(id.value, version).toJson),
        idempotencyKey = Some(s"$RecalculationKind:${id.value}:$version"),
        maxAttempts = 5
      )
      .unit
