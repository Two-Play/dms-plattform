package dms.app

import dms.authz.DocumentResource
import dms.documents.DocumentStatus
import dms.documents.core.CurrentVersion

import java.time.{Instant, LocalDate}
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Pure figures of the compliance cockpit (US-15.1, ADR 0036) over the valid versions a compliance officer
  * may see. Organizational unit and owner may be missing; the unit `None` and the owner `None` are reported
  * as groups of their own.
  */
object ComplianceCockpit:

  /** A valid version with its organizational unit (core field) and, if it asks for acknowledgement, the size
    * of its audience and how many of them acknowledged it.
    *
    * @param deviations
    *   how many deviations from locked sections its stored report holds (ADR 0022)
    * @param lastReminder
    *   when readers were last reminded by hand (ADR 0048)
    * @param lastNotices
    *   when the last notice of each kind went out from the cockpit (ADR 0077)
    */
  final case class Entry(
      version: CurrentVersion,
      acknowledgement: Option[Rate],
      deviations: Int = 0,
      lastReminder: Option[Instant] = None,
      lastNotices: Map[String, Instant] = Map.empty
  ):
    def unit: Option[String]  = DocumentResource.organizationalUnit(version.metadata)
    def owner: Option[String] = version.document.ownerId.map(_.value)

  /** Readers of a version (the acknowledgement audience, ADR 0027) and how many of them acknowledged it. */
  final case class Rate(audience: Int, acknowledged: Int):
    def open: Int = audience - acknowledged

    /** Share of the audience that acknowledged, 0 to 1; `None` without audience. */
    def share: Option[Double] = Option.when(audience > 0)(acknowledged.toDouble / audience)

    def +(other: Rate): Rate = Rate(audience + other.audience, acknowledged + other.acknowledged)

  object Rate:
    val zero: Rate = Rate(0, 0)

  /** Whether the review of a valid version is overdue on `today`: its review date has been reached (it is or
    * becomes ReviewDue, ADR 0030) and it was not recertified.
    */
  def overdue(v: CurrentVersion, today: LocalDate): Boolean =
    v.status == DocumentStatus.ReviewDue || v.reviewDueOn.exists(!_.isAfter(today))

  /** Days since the review date; 0 on the review date itself. */
  def daysOverdue(v: CurrentVersion, today: LocalDate): Long =
    v.reviewDueOn.fold(0L)(due => math.max(0L, ChronoUnit.DAYS.between(due, today)))

  /** Filter of a list by unit or owner: absent means all, the empty string means "none". */
  def matches(filter: Option[String], value: Option[String]): Boolean =
    filter.forall(f => if f.isEmpty then value.isEmpty else value.contains(f))

  /** Counts per key, the largest first, then by key with `None` last. */
  def countBy(entries: List[Entry])(key: Entry => Option[String]): List[(Option[String], Int)] =
    entries.groupMapReduce(key)(_ => 1)(_ + _).toList.sortBy((k, n) => (-n, k.isEmpty, k.getOrElse("")))

  /** Number of versions asking for acknowledgement and their summed rate, per unit, the most open first. */
  def ratesByUnit(entries: List[Entry]): List[(Option[String], Int, Rate)] =
    entries
      .flatMap(e => e.acknowledgement.map(r => e.unit -> r))
      .groupMapReduce(_._1)((_, r) => (1, r))((a, b) => (a._1 + b._1, a._2 + b._2))
      .toList
      .map { case (unit, (versions, rate)) => (unit, versions, rate) }
      .sortBy((unit, _, rate) => (-rate.open, unit.isEmpty, unit.getOrElse("")))

  def total(entries: List[Entry]): (Int, Rate) =
    val rates = entries.flatMap(_.acknowledgement)
    (rates.size, rates.foldLeft(Rate.zero)(_ + _))

  /** Versions asking for acknowledgement, filtered by unit and document, the most open first. */
  def acknowledgementList(
      entries: List[Entry],
      unit: Option[String],
      document: Option[UUID]
  ): List[(Entry, Rate)] =
    entries
      .flatMap(e => e.acknowledgement.map(e -> _))
      .filter((e, _) => matches(unit, e.unit) && document.forall(_ == e.version.document.id.value))
      .sortBy((e, r) => (-r.open, e.version.document.title, e.version.versionId.toString))

  /** One valid version per document without owner, filtered by unit, by title (business concept 16). */
  def withoutOwner(entries: List[Entry], unit: Option[String]): List[Entry] =
    entries
      .filter(e => e.owner.isEmpty && matches(unit, e.unit))
      .distinctBy(_.version.document.id)
      .sortBy(e => (e.version.document.title, e.version.versionId.toString))

  /** Versions that deviate from locked sections, filtered by unit and owner, the most deviations first. */
  def deviationList(entries: List[Entry], unit: Option[String], owner: Option[String]): List[Entry] =
    entries
      .filter(e => e.deviations > 0 && matches(unit, e.unit) && matches(owner, e.owner))
      .sortBy(e => (-e.deviations, e.version.document.title, e.version.versionId.toString))

  /** Overdue versions, filtered by unit and owner, the longest overdue first. */
  def overdueList(
      entries: List[Entry],
      today: LocalDate,
      unit: Option[String],
      owner: Option[String]
  ): List[Entry] =
    entries
      .filter(e => overdue(e.version, today) && matches(unit, e.unit) && matches(owner, e.owner))
      .sortBy(e => (-daysOverdue(e.version, today), e.version.document.title, e.version.versionId.toString))
