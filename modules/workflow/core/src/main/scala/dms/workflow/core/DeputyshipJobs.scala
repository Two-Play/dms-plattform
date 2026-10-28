package dms.workflow.core

import dms.db.queue.{ClaimedJob, JobQueue}
import dms.db.{Db, DbError}
import dms.kernel.TenantId
import zio.*

/** @param checkInterval
  *   how often the deputyship check of every tenant runs; a notice is published at most this long after the
  *   period began or ran out
  */
final case class DeputyshipScheduleConfig(checkInterval: Duration = 5.minutes)

/** Publishes the notices at the start and the end of deputyship periods (DMS-99, ADR 0028 addendum) through
  * the job queue (ADR 0010).
  *
  * Tasks need no job: the workflow evaluates deputyships when tasks are read or voted on. Principal and
  * deputy should still learn when a scheduled period begins and when one runs out, so every instance calls
  * [[tick]], which enqueues one job `workflow.deputyship-check` per tenant and interval (idempotency key, so
  * one job cluster-wide). The job ([[check]]) publishes the outbox events `workflow.deputyship-started` and
  * `workflow.deputyship-ended-by-period`, each deputyship in its own transaction and recorded in
  * `dms.deputyship_notice`, so at-least-once delivery publishes every notice once. Delivering the notices
  * (mail, in-app) is the job of the notifications module.
  */
final class DeputyshipJobs(db: Db, deputyships: Deputyships, config: DeputyshipScheduleConfig):
  import DeputyshipJobs.*

  /** Handlers to register with the job runner. */
  val handlers: Map[String, ClaimedJob => IO[String, Unit]] = Map(CheckKind -> run)

  /** Enqueues the deputyship check of every tenant for the current interval. Returns the number of new jobs.
    */
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
    (tick.catchAll(e => ZIO.logWarning(s"Scheduling the deputyship check failed: $e").as(0)) *>
      ZIO.sleep(config.checkInterval)).forever

  /** The deputyship check of one tenant: returns the number of notices published. Fails (so that the job is
    * retried) if any notice failed; the others are published.
    */
  def check(tenant: TenantId): IO[String, Int] =
    for
      now     <- Clock.instant
      due     <- inTenant(tenant)(deputyships.dueNotices(now))
      results <- ZIO.foreach(due)((id, kind) =>
        inTenant(tenant)(deputyships.notice(id, kind, now)).either
          .map(_.left.map(e => s"$id ${kind.key}: $e"))
      )
      failures = results.collect { case Left(e) => e }
      _ <- ZIO.unless(failures.isEmpty)(ZIO.fail(failures.mkString("; ")))
    yield results.count(_.contains(true))

  private def run(job: ClaimedJob): IO[String, Unit] =
    ZIO.fromOption(job.tenant).orElseFail(s"job ${job.id} ($CheckKind) has no tenant").flatMap(check).unit

  private def inTenant[A](tenant: TenantId)(f: IO[WorkflowError, A]): IO[String, A] =
    db.transact(tenant)(f).mapError {
      case e: WorkflowError => e.describe
      case e: DbError       => e.toString
    }

object DeputyshipJobs:
  val CheckKind = "workflow.deputyship-check"
