package dms.integration

import dms.db.queue.{ClaimedJob, JobQueue, Outbox, OutboxEvent}
import dms.db.{Db, DbError}
import zio.*

/** @param name
  *   worker identity, unique per process (e.g. host name + purpose); used to own leases
  * @param lease
  *   how long a claimed item stays reserved; must exceed the longest handler run
  * @param pollInterval
  *   pause when nothing was due
  * @param retryBase
  *   first retry delay; doubles per attempt up to `retryMax`
  */
final case class WorkerConfig(
    name: String,
    batchSize: Int = 10,
    lease: Duration = 5.minutes,
    pollInterval: Duration = 2.seconds,
    retryBase: Duration = 10.seconds,
    retryMax: Duration = 1.hour
):
  def retryDelay(attempts: Int): Duration =
    val factor = math.pow(2, (attempts - 1).max(0).min(30).toDouble)
    Duration.fromMillis((retryBase.toMillis * factor).toLong.min(retryMax.toMillis))

/** Handles one job; runs its own transactions. A failure is retried with backoff until the job is dead. */
type JobHandler = ClaimedJob => IO[String, Unit]

/** Processes jobs from `dms.job` (ADR 0010). Claims only the kinds it has handlers for, so jobs of a kind
  * introduced by a newer instance stay queued for instances that know them.
  */
final class JobRunner(db: Db, handlers: Map[String, JobHandler], config: WorkerConfig):

  /** Claims and processes one batch. Returns the number of claimed jobs. */
  def runOnce: IO[DbError, Int] =
    JobQueue.claim(db, config.name, handlers.keySet, config.batchSize, config.lease).flatMap { jobs =>
      ZIO.foreachDiscard(jobs)(process).as(jobs.size)
    }

  /** Runs forever; meant to be forked by the application. */
  def run: UIO[Nothing] = Workers.loop(runOnce, config, "job runner")

  private def process(job: ClaimedJob): IO[DbError, Unit] =
    val handler =
      handlers.getOrElse(job.kind, (_: ClaimedJob) => ZIO.fail(s"no handler for job kind ${job.kind}"))
    Workers.guarded(handler(job), config).flatMap {
      case None        => JobQueue.complete(db, job.id, config.name).unit
      case Some(error) =>
        JobQueue
          .fail(db, job.id, config.name, error, config.retryDelay(job.attempts))
          .flatMap(status =>
            ZIO.logWarning(
              s"Job ${job.id} (${job.kind}) attempt ${job.attempts} failed, now ${status.getOrElse("?")}: $error"
            )
          )
    }

/** Delivers outbox events (ADR 0010) at least once; `deliver` must be idempotent per event id. */
final class OutboxRelay(db: Db, deliver: OutboxEvent => IO[String, Unit], config: WorkerConfig):

  def runOnce: IO[DbError, Int] =
    Outbox.claim(db, config.name, config.batchSize, config.lease).flatMap { events =>
      ZIO.foreachDiscard(events)(process).as(events.size)
    }

  def run: UIO[Nothing] = Workers.loop(runOnce, config, "outbox relay")

  private def process(event: OutboxEvent): IO[DbError, Unit] =
    Workers.guarded(deliver(event), config).flatMap {
      case None        => Outbox.complete(db, event.id, config.name).unit
      case Some(error) =>
        Outbox.fail(db, event.id, config.name, error, config.retryDelay(event.attempts)) *>
          ZIO.logWarning(
            s"Outbox event ${event.id} (${event.eventType}) attempt ${event.attempts} failed: $error"
          )
    }

private object Workers:

  /** Runs `work` with a timeout below the lease; turns failures, defects and timeouts into an error text. */
  def guarded(work: IO[String, Unit], config: WorkerConfig): UIO[Option[String]] =
    work
      .timeoutFail(s"timed out after ${config.lease.toSeconds * 9 / 10} s")(config.lease * 0.9)
      .foldCauseZIO(
        cause =>
          ZIO.some(
            cause.failureOption.getOrElse(cause.prettyPrint.linesIterator.nextOption().getOrElse("defect"))
          ),
        _ => ZIO.none
      )

  def loop(runOnce: IO[DbError, Int], config: WorkerConfig, what: String): UIO[Nothing] =
    runOnce
      .catchAll(e => ZIO.logWarning(s"$what ${config.name} could not claim work: $e").as(0))
      .flatMap(processed => ZIO.sleep(config.pollInterval).when(processed == 0))
      .forever
