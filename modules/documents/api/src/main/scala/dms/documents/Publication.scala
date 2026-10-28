package dms.documents

import dms.documents.metadata.MetadataValue
import dms.templates.TextChange

import java.time.{Instant, LocalDate, ZoneId}

/** Pure rules of the scheduled publication (business concept 6.3, US-06.3, DMS-45; ADR 0026).
  *
  * An approved version becomes valid at 00:00 of its valid-from date in the tenant's time zone: it is then
  * published, and the valid version of the same scope is superseded in the same transaction. A version is
  * never published before that moment.
  */
object Publication:

  /** The core field holding the valid-from date. */
  val ValidFromField = "validFrom"

  /** Time zone of tenants that did not choose one (migration V12). */
  val DefaultZone: ZoneId = ZoneId.of("Europe/Berlin")

  /** The valid-from date of field values, if set. */
  def validFrom(values: Map[String, MetadataValue]): Option[LocalDate] =
    values.get(ValidFromField).collect { case MetadataValue.Date(d) => d }

  /** The moment a version with this valid-from date becomes valid: 00:00 of the date in `zone`, or the first
    * moment of the date if 00:00 does not exist there (daylight saving time change at midnight).
    */
  def dueAt(validFrom: LocalDate, zone: ZoneId): Instant = validFrom.atStartOfDay(zone).toInstant

  /** True if a version with this valid-from date may be published at `now`. A version without valid-from date
    * (classes that do not require it) is due at once.
    */
  def isDue(validFrom: Option[LocalDate], zone: ZoneId, now: Instant): Boolean =
    validFrom.forall(d => !dueAt(d, zone).isAfter(now))

  /** The version readers of `v` are pointed to: for a superseded version the version that replaced it, for a
    * valid version the approved version of the same scope that is scheduled to replace it.
    */
  def successor(v: DocumentVersion, versions: List[DocumentVersion]): Option[DocumentVersion] =
    v.status match
      case DocumentStatus.Superseded =>
        v.supersededBy.flatMap(id => versions.find(_.id == id))
      case status if Versioning.isValid(status) =>
        versions
          .filter(o => o.status == DocumentStatus.Approved && o.scope == v.scope)
          .maxByOption(_.number)
      case _ => None

/** The automatic comparison of an editorial (minor) version with the version it is based on (US-05.2,
  * DMS-46): it warns, without blocking, when more than `threshold` of the text changed.
  *
  * @param basedOn
  *   label of the released version the minor version was compared with
  * @param changedRatio
  *   share of the text that changed, see [[TextChange.ratio]]
  */
final case class EditorialCheck(basedOn: String, changedRatio: Double, threshold: Double):
  def exceedsThreshold: Boolean = changedRatio > threshold

object EditorialCheck:

  /** The released version a version was started from: the latest approved version with a lower number. */
  def basis(v: DocumentVersion, versions: List[DocumentVersion]): Option[DocumentVersion] =
    import scala.math.Ordering.Implicits.infixOrderingOps
    versions.filter(o => o.releasedAt.isDefined && o.number < v.number).maxByOption(_.number)

  /** The check of a minor version; `None` for major versions and versions without a released basis. */
  def of(v: DocumentVersion, versions: List[DocumentVersion], threshold: Double): Option[EditorialCheck] =
    if v.changeKind != ChangeKind.Minor then None
    else
      basis(v, versions).map(base =>
        EditorialCheck(base.label, TextChange.compare(base.content, v.content).ratio, threshold)
      )
