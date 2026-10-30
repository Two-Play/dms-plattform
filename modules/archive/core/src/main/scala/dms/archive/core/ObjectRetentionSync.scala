package dms.archive.core

import com.augustnagro.magnum.*
import dms.archive.{ObjectRetentionPolicy, ObjectRetentions, RetentionRule, RetentionTrigger, VersionTimes}
import dms.audit.*
import dms.db.queue.ClaimedJob
import dms.db.{Db, DbError, Jsonb}
import dms.kernel.{DocumentId, TenantId, UserId}
import dms.storage.{ObjectStore, Retention, StorageError}
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.{OffsetDateTime, ZoneId, ZoneOffset}
import java.util.UUID

/** Sets the S3 Object Lock retention on the objects of an approved version (DMS-93, ADR 0055): its rendition,
  * the original of a file document and its attachment files, until the end of the retention its rule in
  * `dms.retention_rule` (DMS-82) gives in the tenant's time zone (see [[ObjectRetentionPolicy]]). Each lock
  * is recorded in `dms.object_retention` and the job writes the audit entry `document.objects-retained`.
  *
  * Runs as job [[ObjectRetentions.JobKind]] after the commit (the object store is not transactional): when
  * the version is approved, when it goes out of force and when its retention rule is created or changed
  * (triggers of migration V36). Idempotent: a retention that already ends at or after the target is kept (S3
  * never shortens one, and an object keeps the mode of its first lock), so repeated jobs and objects shared
  * by several versions are safe; the later approval extends the lock.
  *
  * The object legal hold is independent of the retention and handled by [[LegalHoldSync]]: releasing a hold
  * never touches the retention, and a retention never ends a hold. Errors a retry does not fix (missing
  * object, no Object Lock support, missing permission) are logged and reported as skipped; an unreachable
  * store fails the job for a retry.
  */
final class ObjectRetentionSync(
    db: Db,
    objects: ObjectStore,
    policy: ObjectRetentionPolicy,
    audit: AuditTrail
):
  import ObjectRetentionSync.*

  val handlers: Map[String, ClaimedJob => IO[String, Unit]] = Map(ObjectRetentions.JobKind -> handle)

  def handle(job: ClaimedJob): IO[String, Unit] =
    for
      tenant  <- ZIO.fromOption(job.tenant).orElseFail(s"job ${job.id} (${job.kind}) has no tenant")
      payload <- ZIO.fromEither(payloadOf(job.payload))
      _       <- lock(tenant, payload._1, payload._2)
    yield ()

  /** Locks the objects of the version. `None` if the version is not approved or Object Lock is disabled. */
  def lock(tenant: TenantId, document: DocumentId, versionId: UUID): IO[String, Option[LockResult]] =
    if !policy.enabled then
      ZIO
        .logInfo(s"Object Lock is disabled: no retention set for version $versionId (ADR 0055)")
        .as(None)
    else
      facts(tenant, document, versionId).flatMap {
        case None =>
          ZIO
            .logWarning(s"Version $versionId of ${document.value} is not approved: no retention set")
            .as(None)
        case Some(f) =>
          val target = policy.retention(f.rule, f.times, f.zone)
          for
            now <- Clock.instant
            // A retention that ended already (e.g. counted from the document's creation long ago) sets nothing.
            applied <-
              if target.until.isAfter(now) then ZIO.foreach(f.objects)(o => apply(o, target))
              else
                ZIO.succeed(f.objects.map(o => Left(Skipped(o, s"the retention ended on ${target.until}"))))
            locked  = applied.collect { case Right(l) => l }
            skipped = applied.collect { case Left(s) => s }
            result  = LockResult(target, f.ruleKey, locked, skipped)
            _ <- record(tenant, document, versionId, result)
          yield Some(result)
      }

  private def apply(o: StoredObject, target: Retention): IO[String, Either[Skipped, Locked]] =
    val attempt =
      for
        now     <- Clock.instant
        current <- objects.head(o.key, Some(o.version)).map(_.retention.filter(_.until.isAfter(now)))
        locked  <- current match
          case Some(c) if !c.until.isBefore(target.until) => ZIO.succeed(Locked(o, c, changed = false))
          case _                                          =>
            // An object keeps the mode of its first lock: S3 does not weaken compliance, and changing
            // governance into compliance would need the bypass permission.
            val retention = Retention(current.fold(target.mode)(_.mode), target.until)
            objects.extendRetention(o.key, o.version, retention).as(Locked(o, retention, changed = true))
      yield locked
    attempt.map(Right(_)).catchAll {
      case e: StorageError.Unavailable =>
        ZIO.fail(
          s"object store unavailable while setting the retention of ${o.key} (${o.version}): ${e.cause}"
        )
      case e =>
        ZIO
          .logWarning(
            s"Retention of ${o.key} (${o.version}) not set: $e. The database guards still protect the version " +
              "(ADR 0055)."
          )
          .as(Left(Skipped(o, e.toString)))
    }

  private def facts(
      tenant: TenantId,
      document: DocumentId,
      versionId: UUID
  ): IO[String, Option[VersionFacts]] =
    db.transact(tenant)(
      Db.sql {
        // Out of force since the withdrawal or the successor's publication, as the destruction list counts it
        // (DMS-82); for other statuses after Approved, since the last status change.
        sql"""select v.released_at, v.metadata_snapshot #>> '{values,retentionRule,value}', d.created_at,
                     case when v.status in ('Superseded', 'Withdrawn', 'Archived', 'Destroyed')
                          then coalesce(v.withdrawn_at, s.published_at, v.status_changed_at) end
              from dms.document_version v
              join dms.document d on d.id = v.document_id
              left join dms.document_version s on s.id = v.superseded_by
              where v.id = $versionId and v.document_id = ${document.value}"""
          .query[(Option[OffsetDateTime], Option[String], OffsetDateTime, Option[OffsetDateTime])]
          .run()
          .headOption
          .flatMap { case (released, rule, created, outOfForce) =>
            released
              .map(r => (VersionTimes(r.toInstant, created.toInstant, outOfForce.map(_.toInstant)), rule))
          }
          .map { (times, ruleKey) =>
            val rule = ruleKey.flatMap(key =>
              sql"""select period_months, trigger_event, from_year_end, updated_by, updated_at, name
                    from dms.retention_rule where key = $key"""
                .query[(Int, String, Boolean, String, OffsetDateTime, String)]
                .run()
                .headOption
                .flatMap { (months, trigger, yearEnd, by, at, name) =>
                  RetentionTrigger.values
                    .find(_.toString == trigger)
                    .map(t => RetentionRule(key, name, months, t, yearEnd, UserId(by), at.toInstant))
                }
            )
            val zone =
              sql"select time_zone from dms.tenant where id = dms.current_tenant()"
                .query[String]
                .run()
                .headOption
                .flatMap(z => scala.util.Try(ZoneId.of(z)).toOption)
                .getOrElse(ZoneId.of("Europe/Berlin"))
            val rendition =
              sql"""select rendition_key, rendition_version from dms.document_version
                    where id = $versionId and rendition_key is not null and rendition_version is not null"""
                .query[(String, String)]
                .run()
                .map((k, v) => StoredObject(k, v, "rendition"))
            val original =
              sql"""select f.object_key, f.object_version from dms.document_version_file vf
                    join dms.document_attachment_file f on f.id = vf.file_id
                    where vf.version_id = $versionId"""
                .query[(String, String)]
                .run()
                .map((k, v) => StoredObject(k, v, "original"))
            val attachments =
              sql"""select f.object_key, f.object_version from dms.document_version_attachment a
                    join dms.document_attachment_file f on f.id = a.file_id
                    where a.version_id = $versionId order by a.added_at, a.id"""
                .query[(String, String)]
                .run()
                .map((k, v) => StoredObject(k, v, "attachment"))
            VersionFacts(
              times,
              ruleKey,
              rule,
              zone,
              (rendition ++ original ++ attachments).distinctBy(o => (o.key, o.version)).toList
            )
          }
      }
    ).mapError(e => s"version $versionId of ${document.value} could not be read: $e")

  /** Records the locks and the audit entry in one transaction. */
  private def record(
      tenant: TenantId,
      document: DocumentId,
      versionId: UUID,
      result: LockResult
  ): IO[String, Unit] =
    db.transact(tenant)(
      Db.sql {
        result.locked.foreach { l =>
          val until = OffsetDateTime.ofInstant(l.retention.until, ZoneOffset.UTC)
          val mode  = l.retention.mode.toString
          sql"""insert into dms.object_retention
                  (tenant_id, object_key, object_version, mode, retain_until, document_id, version_id, retention_rule)
                values (dms.current_tenant(), ${l.obj.key}, ${l.obj.version}, $mode, $until, ${document.value},
                        $versionId, ${result.rule})
                on conflict (tenant_id, object_key, object_version) do update
                  set mode = excluded.mode, retain_until = excluded.retain_until, version_id = excluded.version_id,
                      retention_rule = excluded.retention_rule, set_at = now()
                  where dms.object_retention.retain_until < excluded.retain_until""".update.run(): Unit
        }
      } *> audit
        .record(
          AuditEvent(
            Actor(ArchiveActor, ActorKind.Technical),
            "document.objects-retained",
            AuditObject("document", Some(document.value)),
            Channel.Automation,
            Jsonb(details(versionId, result).toJson)
          )
        )
        .unit
    ).mapError((e: DbError) => s"retention of version $versionId could not be recorded: $e")

object ObjectRetentionSync:

  /** The technical actor of records management in the audit trail. */
  val ArchiveActor: String = "dms-archive"

  /** A stored object of a version: rendition, original or attachment. */
  final case class StoredObject(key: String, version: String, role: String)

  /** @param changed
    *   whether this job set or extended the retention (otherwise it already ended at or after the target)
    */
  final case class Locked(obj: StoredObject, retention: Retention, changed: Boolean)

  final case class Skipped(obj: StoredObject, reason: String)

  final case class LockResult(
      target: Retention,
      rule: Option[String],
      locked: List[Locked],
      skipped: List[Skipped]
  )

  private final case class VersionFacts(
      times: VersionTimes,
      ruleKey: Option[String],
      rule: Option[RetentionRule],
      zone: ZoneId,
      objects: List[StoredObject]
  )

  private def details(versionId: UUID, result: LockResult): Json.Obj =
    def obj(o: StoredObject) =
      Json.Obj(
        "role"          -> Json.Str(o.role),
        "objectKey"     -> Json.Str(o.key),
        "objectVersion" -> Json.Str(o.version)
      )
    Json.Obj(
      "versionId"     -> Json.Str(versionId.toString),
      "retentionRule" -> result.rule.fold(Json.Null)(Json.Str(_)),
      "mode"          -> Json.Str(result.target.mode.toString),
      "retainUntil"   -> Json.Str(result.target.until.toString),
      "objects"       -> Json.Arr(
        Chunk.fromIterable(
          result.locked.map(l =>
            obj(l.obj).merge(
              Json.Obj(
                "mode"        -> Json.Str(l.retention.mode.toString),
                "retainUntil" -> Json.Str(l.retention.until.toString),
                "changed"     -> Json.Bool(l.changed)
              )
            )
          )
        )
      ),
      "skipped" -> Json.Arr(
        Chunk.fromIterable(
          result.skipped.map(s => obj(s.obj).merge(Json.Obj("reason" -> Json.Str(s.reason))))
        )
      )
    )

  private def payloadOf(payload: Jsonb): Either[String, (DocumentId, UUID)] =
    for
      obj      <- payload.value.fromJson[Json.Obj]
      document <- obj.get("documentId").flatMap(_.asString).toRight("payload has no documentId")
      version  <- obj.get("versionId").flatMap(_.asString).toRight("payload has no versionId")
      ids      <- scala.util
        .Try((DocumentId(UUID.fromString(document)), UUID.fromString(version)))
        .toEither
        .left
        .map(_.getMessage)
    yield ids
