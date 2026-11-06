package dms.audit.core

import dms.audit.AuditViolation
import dms.db.queue.Outbox
import dms.db.{Db, DbError, Jsonb}
import zio.*

import java.time.temporal.ChronoUnit
import java.time.{Instant, ZoneOffset}

/** Telling people of a violation of the audit chain (DMS-169, ADR 0080), through the outbox like any other
  * notice. Who is told is decided on delivery, by the application.
  *
  * Once per violation: a run that finds what the run before it found is no news. The first notice goes out
  * with the run that found it, in the same transaction. While the newest run still finds it, one reminder
  * goes out per reminder day that has come, counted from the first notice, so that a violation older than
  * this feature does not reach people as a reminder. Missed days are not caught up, as with failed jobs (ADR
  * 0064). An intact run ends the violation; found again, it is a new one.
  *
  * `dms.audit_violation_notice` records each round under a primary key. The event says when, nothing else:
  * position, kind and detail of a violation stay in the run, as a notice must not carry audit content.
  *
  * All methods must run inside `Db.transact(tenant)`.
  */
private[core] object ViolationNotices:

  /** The notice of a new violation; a run calls this after it recorded what it found. */
  def tellOfNew(now: Instant): IO[DbError, Unit] =
    open.flatMap(
      ZIO.foreachDiscard(_)(violation => tell(violation, 0, now).when(violation.told.isEmpty))
    )

  /** The reminder that is due at `now`, if the newest run still finds a violation. */
  def remind(now: Instant, days: List[Int]): IO[DbError, Unit] =
    open.flatMap(
      ZIO.foreachDiscard(_)(violation =>
        ZIO.foreachDiscard(violation.told) { (firstNotice, round) =>
          ZIO.foreachDiscard(dueRound(firstNotice, now, days).filter(_ > round))(tell(violation, _, now))
        }
      )
    )

  /** How many reminder days have come since `firstNotice`, by calendar days in UTC: the job that sends the
    * reminder runs a few seconds off the time of day of the first notice. `None` before the first.
    */
  def dueRound(firstNotice: Instant, now: Instant, days: List[Int]): Option[Int] =
    val passed = ChronoUnit.DAYS.between(
      firstNotice.atZone(ZoneOffset.UTC).toLocalDate,
      now.atZone(ZoneOffset.UTC).toLocalDate
    )
    Some(days.count(_ <= passed)).filter(_ > 0)

  private def open: IO[DbError, Option[OpenViolation]] =
    Db.sql {
      val tenant = ChainStore.currentTenant
      ChainStore.lockViolations(tenant)
      ChainStore.openViolation(tenant)
    }

  private def tell(violation: OpenViolation, round: Int, now: Instant): IO[DbError, Unit] =
    for
      tenant <- Db.sql(ChainStore.currentTenant)
      added  <- Db.sql(ChainStore.insertViolationNotice(tenant, violation.firstRun, round, now))
      _      <- ZIO.when(added)(
        Outbox.publish(
          AuditViolation.AggregateType,
          tenant,
          AuditViolation.Event,
          Jsonb(payload(violation.foundAt, round))
        )
      )
    yield ()

  /** A timestamp and a number: nothing that needs escaping, and nothing of what was found. */
  private def payload(foundAt: Instant, round: Int): String =
    val reminder = if round > 0 then s""","reminder":"$round"""" else ""
    s"""{"foundAt":"$foundAt"$reminder}"""
