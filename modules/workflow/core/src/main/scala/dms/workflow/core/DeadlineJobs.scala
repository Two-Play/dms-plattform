package dms.workflow.core

import dms.db.queue.{ClaimedJob, JobQueue}
import dms.db.{Db, DbError}
import dms.kernel.TenantId
import zio.*

/** @param checkInterval
  *   how often the deadline check of every tenant runs; a reminder or escalation is published at most this
  *   long after it is due
  */
final case class DeadlineScheduleConfig(checkInterval: Duration = 15.minutes)

/** Reminders before and escalation after the deadlines of review and approval tasks (DMS-87, ADR 0053)
  * through the job queue (ADR 0010).
  *
  * Every instance calls [[tick]], which enqueues one job `workflow.deadline-check` per tenant and interval
  * (idempotency key, so one job cluster-wide). The job ([[check]]) looks at the open tasks whose reminder,
  * deadline or escalation time has come and publishes the notice of the latest stage reached, each task in
  * its own transaction and recorded in `dms.workflow_task_deadline_notice`, so every notice is published
  * once; stages missed while no instance ran are not caught up. The notifications module delivers them.
  */
final class DeadlineJobs(db: Db, workflow: ApprovalWorkflow, config: DeadlineScheduleConfig):
  import DeadlineJobs.*

  /** Handlers to register with the job runner. */
  val handlers: Map[String, ClaimedJob => IO[String, Unit]] = Map(CheckKind -> run)

  /** Enqueues the deadline check of every tenant for the current interval. Returns the number of new jobs. */
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
    (tick.catchAll(e => ZIO.logWarning(s"Scheduling the deadline check failed: $e").as(0)) *>
      ZIO.sleep(config.checkInterval)).forever

  /** The deadline check of one tenant: returns the number of notices published. Fails (so that the job is
    * retried) if any notice failed; the others are published.
    */
  def check(tenant: TenantId): IO[String, Int] =
    for
      now     <- Clock.instant
      due     <- inTenant(tenant)(workflow.dueDeadlineNotices(now))
      results <- ZIO.foreach(due)((task, stage) =>
        inTenant(tenant)(workflow.noticeDeadline(task, stage, now)).either
          .map(_.left.map(e => s"$task ${stage.key}: $e"))
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

object DeadlineJobs:
  val CheckKind = "workflow.deadline-check"
