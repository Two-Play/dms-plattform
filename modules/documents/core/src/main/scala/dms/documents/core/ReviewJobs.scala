package dms.documents.core

import dms.db.queue.{ClaimedJob, JobQueue}
import dms.db.{Db, DbError}
import dms.kernel.TenantId
import zio.*

import java.util.UUID

/** @param checkInterval
  *   how often the review check of every tenant runs; a review task appears at most this long after its lead
  *   time is reached (00:00 of that day in the tenant's time zone)
  */
final case class ReviewScheduleConfig(checkInterval: Duration = 1.hour)

/** What one review check did. */
final case class ReviewCheck(opened: Int, reviewDue: Int, closed: Int)

/** Runs the periodic review (US-07.1, ADR 0030) through the job queue (ADR 0010).
  *
  * Every instance calls [[tick]]: it enqueues one job `document.review-check` per tenant and check interval
  * (idempotency key per tenant and interval, so one job cluster-wide). The job ([[check]]) creates the review
  * tasks whose lead time is reached, moves published versions whose review date has come to ReviewDue and
  * closes open tasks of versions that are not valid any more. Each item runs in its own transaction, so one
  * failing version does not block the others; every step is idempotent, so at-least-once delivery is safe.
  */
final class ReviewJobs(db: Db, service: ReviewService, config: ReviewScheduleConfig):
  import ReviewJobs.*

  /** Handlers to register with the job runner. */
  val handlers: Map[String, ClaimedJob => IO[String, Unit]] = Map(CheckKind -> run)

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
      work   <- inTenant(tenant)(service.pendingWork)
      closed <- each(tenant, work.obsolete)(service.closeObsolete)
      opened <- each(tenant, work.toOpen)(service.openTask)
      due    <- each(tenant, work.reviewDue)(service.markReviewDue)
      failures = List(closed, opened, due).flatMap(_._2)
      _ <- ZIO.unless(failures.isEmpty)(ZIO.fail(failures.mkString("; ")))
    yield ReviewCheck(opened._1, due._1, closed._1)

  private def run(job: ClaimedJob): IO[String, Unit] =
    ZIO.fromOption(job.tenant).orElseFail(s"job ${job.id} ($CheckKind) has no tenant").flatMap(check).unit

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
  val CheckKind = "document.review-check"
