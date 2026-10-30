package dms.archive

import com.augustnagro.magnum.*
import dms.db.{Db, DbError}
import dms.kernel.DocumentId
import dms.storage.{Retention, RetentionMode}
import zio.*

import java.time.{Instant, OffsetDateTime, ZoneId, ZoneOffset}
import java.util.UUID

/** When a version's retention trigger happened and what is known about it, for the Object Lock end. */
final case class VersionTimes(approvedAt: Instant, documentCreatedAt: Instant, outOfForceAt: Option[Instant])

/** How long the objects of an approved version are locked in object storage (S3 Object Lock retention,
  * DMS-93, ADR 0055).
  *
  * The single source of the period is the version's retention rule (`dms.retention_rule`, DMS-82, ADR 0054),
  * referenced by the core field `retentionRule`: the lock ends when the rule's retention would end
  * ([[RetentionPeriod.endsOn]] in the tenant's time zone; the object is locked through that last day). The
  * trigger is taken as the rule defines it (approval, creation of the document, end of validity); while the
  * end of validity is not known yet (the version was just approved), the approval stands in for it, which is
  * a lower bound: the version goes out of force at or after its approval. The job runs again when the version
  * goes out of force and when the rule changes, and only ever extends a lock (S3 never shortens one).
  *
  * A version whose rule key has no row (or that has no rule) is locked `defaultYears` after its approval.
  *
  * @param enabled
  *   capability flag: `false` for an object store without Object Lock; no retention is set then and the
  *   database guards alone protect the objects
  * @param mode
  *   the mode of new locks; an object keeps the mode of its first lock
  */
final case class ObjectRetentionPolicy(enabled: Boolean, mode: RetentionMode, defaultYears: Int):

  /** The retention for the objects of a version under `rule` (`None`: no row for its rule key). */
  def retention(rule: Option[RetentionRule], times: VersionTimes, zone: ZoneId): Retention =
    rule match
      case None =>
        Retention(mode, times.approvedAt.atOffset(ZoneOffset.UTC).plusYears(defaultYears.toLong).toInstant)
      case Some(r) =>
        val triggered = RetentionPeriod
          .triggeredAt(r.trigger, times.outOfForceAt, Some(times.approvedAt), times.documentCreatedAt)
          .getOrElse(times.approvedAt)
        // The retention ends after its last day: the object stays locked through it.
        Retention(mode, RetentionPeriod.endsOn(r, triggered, zone).plusDays(1).atStartOfDay(zone).toInstant)

object ObjectRetentionPolicy:

  /** Governance mode, ten years for versions without rule. */
  val default: ObjectRetentionPolicy = ObjectRetentionPolicy(true, RetentionMode.Governance, 10)

  /** Parses `mode` (`governance` or `compliance`). */
  def parse(enabled: Boolean, mode: String, defaultYears: Int): Either[String, ObjectRetentionPolicy] =
    for
      parsedMode <- mode.trim.toLowerCase(java.util.Locale.ROOT) match
        case "governance" => Right(RetentionMode.Governance)
        case "compliance" => Right(RetentionMode.Compliance)
        case other => Left(s"unknown Object Lock retention mode '$other'; supported: governance, compliance")
      _ <- Either.cond(defaultYears >= 1 && defaultYears <= 999, (), "defaultYears must be between 1 and 999")
    yield ObjectRetentionPolicy(enabled, parsedMode, defaultYears)

/** The Object Lock retention the platform set on one object version (table `dms.object_retention`). */
final case class ObjectLock(
    objectKey: String,
    objectVersion: String,
    mode: RetentionMode,
    retainUntil: Instant,
    documentId: DocumentId,
    versionId: UUID,
    retentionRule: Option[String]
):
  def activeAt(now: Instant): Boolean = retainUntil.isAfter(now)

/** Read access to the lock state of stored objects for other modules, in particular the destruction path and
  * the clean-up of orphaned objects (DMS-82): an object with an active lock cannot be deleted before
  * `retainUntil` (in governance mode only with the bypass permission, in compliance mode not at all), so it
  * must not be proposed for deletion. Object storage holds the authoritative lock (`ObjectStore.head`); this
  * is its queryable record, written after the store confirmed the lock. SQL queries can read
  * `dms.object_retention` (`object_key`, `object_version`, `mode`, `retain_until`).
  *
  * Every method runs inside `Db.transact(tenant)`.
  */
object ObjectRetentions:

  /** Job that sets the retention on the objects of an approved version (rendition, original of a file
    * document, attachment files). Payload: `documentId`, `versionId`. Enqueued when a version is approved
    * (migration V36), once per version.
    */
  val JobKind: String = "archive.object-retention"

  private type Row = (String, String, String, OffsetDateTime, UUID, UUID, Option[String])

  /** The recorded lock of an object version, if any (active or expired). */
  def lockOf(objectKey: String, objectVersion: String): IO[DbError, Option[ObjectLock]] =
    Db.sql(
      sql"""select object_key, object_version, mode, retain_until, document_id, version_id, retention_rule
            from dms.object_retention where object_key = $objectKey and object_version = $objectVersion"""
        .query[Row]
        .run()
        .headOption
        .map(lock)
    )

  /** All recorded locks of a document's objects, by object key and version. */
  def ofDocument(document: DocumentId): IO[DbError, List[ObjectLock]] =
    Db.sql(
      sql"""select object_key, object_version, mode, retain_until, document_id, version_id, retention_rule
            from dms.object_retention where document_id = ${document.value}
            order by object_key, object_version"""
        .query[Row]
        .run()
        .toList
        .map(lock)
    )

  /** Whether the object version is locked at `now` according to the record. */
  def isLocked(objectKey: String, objectVersion: String, now: Instant): IO[DbError, Boolean] =
    lockOf(objectKey, objectVersion).map(_.exists(_.activeAt(now)))

  private def lock(row: Row): ObjectLock =
    val (key, version, mode, until, document, versionId, rule) = row
    ObjectLock(
      key,
      version,
      if mode == "Compliance" then RetentionMode.Compliance else RetentionMode.Governance,
      until.toInstant,
      DocumentId(document),
      versionId,
      rule
    )
