package dms.documents

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate, ZoneId}

/** When a version was in force (US-09.1, DMS-53): from its effective start until its successor's effective
  * start; `until` is `None` while it is still valid.
  */
final case class ValidityPeriod(from: Instant, until: Option[Instant]):
  def contains(at: Instant): Boolean = !from.isAfter(at) && until.forall(at.isBefore)

/** Pure rules of the validity history (US-09.1, DMS-53).
  *
  * A version is in force from its effective start: 00:00 of its valid-from date in the tenant's time zone,
  * but never before it was actually published (a version approved after its valid-from date is not valid
  * retroactively). It is in force until the effective start of the version that superseded it (the same
  * moment, see ADR 0026), or for as long as it is valid. Versions in force for a date are those in force at
  * the last moment of that date in the tenant's time zone, so that a switch during the day (e.g. a scheduled
  * publication a few seconds after 00:00) shows the version that applied for the rest of the day.
  */
object Validity:

  /** Statuses of versions that were in force at some time: valid ones and the ones that replaced or archived
    * them. Destroyed versions are left out: their content no longer exists.
    */
  val EverValid: Set[DocumentStatus] =
    Set(
      DocumentStatus.Published,
      DocumentStatus.ReviewDue,
      DocumentStatus.Superseded,
      DocumentStatus.Archived
    )

  /** The moment a version became valid: 00:00 of its valid-from date in `zone`, or its publication if that
    * was later.
    */
  def effectiveStart(validFrom: Option[LocalDate], publishedAt: Instant, zone: ZoneId): Instant =
    validFrom.map(Publication.dueAt(_, zone)).filter(_.isAfter(publishedAt)).getOrElse(publishedAt)

  /** The moment a "valid on `date`" question refers to: the last moment of the date in `zone`, at the
    * precision PostgreSQL stores (microseconds).
    */
  def endOfDay(date: LocalDate, zone: ZoneId): Instant =
    date.plusDays(1).atStartOfDay(zone).toInstant.minus(1, ChronoUnit.MICROS)
