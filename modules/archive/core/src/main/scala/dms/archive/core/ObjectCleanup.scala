package dms.archive.core

import com.augustnagro.magnum.*
import dms.archive.RetentionEvents
import dms.audit.*
import dms.db.queue.{ClaimedJob, JobQueue}
import dms.db.{Db, DbError, Jsonb}
import dms.kernel.{TenantId, UserId}
import dms.storage.{ObjectStore, StorageError}
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.UUID
import scala.util.Try

/** @param grace
  *   an orphaned object or file is removed only once it is older than this: uploads store the object before
  *   the database row is committed, and a file unlinked from a draft may be linked again
  * @param maxEntries
  *   most entries a run report keeps
  */
final case class OrphanCleanupConfig(grace: Duration = 7.days, maxEntries: Int = 500)

/** What one clean-up run found and did (stored in `dms.storage_cleanup_run`). */
final case class CleanupReport(
    id: UUID,
    dryRun: Boolean,
    startedAt: Instant,
    finishedAt: Instant,
    triggeredBy: String,
    graceHours: Long,
    summary: CleanupSummary,
    entries: List[CleanupEntry]
)

/** @param scanned
  *   object versions looked at
  * @param orphaned
  *   object versions no database row references
  * @param deleted
  *   removed (in a dry run: would be removed)
  * @param keptLocked
  *   kept because Object Lock (retention or legal hold) protects them
  * @param keptHeld
  *   kept because their document is under legal hold
  * @param keptRecent
  *   kept because they are younger than the grace period
  * @param filesDeleted
  *   stored-file rows no version references any more, deleted (in a dry run: would be deleted)
  */
final case class CleanupSummary(
    scanned: Int,
    orphaned: Int,
    deleted: Int,
    keptLocked: Int,
    keptHeld: Int,
    keptRecent: Int,
    failed: Int,
    filesDeleted: Int
) derives JsonCodec

/** One orphaned object (or stored-file row) and what happened to it: Deleted, WouldDelete, KeptLocked,
  * KeptHeld, KeptRecent, Failed or FileDeleted / FileWouldDelete.
  */
final case class CleanupEntry(
    key: String,
    version: String,
    outcome: String,
    detail: Option[String] = None,
    lastModified: Option[Instant] = None
) derives JsonCodec

/** Removal of objects that no version references any more (DMS-82, ADR 0054; follow-up of ADR 0016, 0041,
  * 0050): the objects released by a destruction (job [[RetentionEvents.ObjectCleanupJobKind]] per object,
  * after the commit) and, periodically or on request, every orphaned object of a tenant: older renditions of
  * drafts, attachments removed from drafts, replaced originals, objects of uploads that never got a database
  * row and stored files nothing links to any more (also those left by discarded drafts before DMS-82).
  *
  * Never removed: anything a database row references (stored files, renditions of versions that are not
  * destroyed, Word files of templates), objects of documents under legal hold, objects younger than the grace
  * period and object versions Object Lock protects (S3 retention or legal hold, ADR 0016). A protected object
  * of a destruction is retried when its retention ends; otherwise the next run finds it again. A dry run
  * reports without deleting.
  */
final class ObjectCleanup(db: Db, objects: ObjectStore, audit: AuditTrail, config: OrphanCleanupConfig):
  import ObjectCleanup.*

  val handlers: Map[String, ClaimedJob => IO[String, Unit]] =
    Map(RetentionEvents.ObjectCleanupJobKind -> handle)

  def handle(job: ClaimedJob): IO[String, Unit] =
    for
      tenant  <- ZIO.fromOption(job.tenant).orElseFail(s"job ${job.id} (${job.kind}) has no tenant")
      request <- ZIO.fromEither(job.payload.value.fromJson[Request])
      _       <- remove(tenant, request)
    yield ()

  /** Removes one released object unless something references or protects it; returns the outcome. */
  def remove(tenant: TenantId, request: Request): IO[String, String] =
    for
      blocked <- inTenant(tenant)(Db.sql {
        request.documentId.exists(heldDocument) || referenced(request.objectKey, request.objectVersion)
      })
      outcome <-
        if blocked then
          ZIO
            .logInfo(
              s"Object ${request.objectKey} (${request.objectVersion}) is kept: referenced again or held"
            )
            .as(Kept)
        else
          objects
            .delete(request.objectKey, request.objectVersion)
            .as(Deleted)
            .catchAll {
              case StorageError.NotFound(_, _)                   => ZIO.succeed(Deleted)
              case StorageError.Locked(_, _, Some(retention), _) =>
                // Retried when the retention ends (S3 Object Lock, DMS-93); the database keeps the tombstone.
                inTenant(tenant)(
                  JobQueue.enqueue(
                    RetentionEvents.ObjectCleanupJobKind,
                    Jsonb(request.toJson),
                    idempotencyKey = Some(
                      s"${RetentionEvents.ObjectCleanupJobKind}:${request.objectKey}:${request.objectVersion}:" +
                        retention.until.getEpochSecond
                    ),
                    runAt = Some(retention.until.plusSeconds(60))
                  )
                ).as(KeptLocked) <* ZIO.logInfo(
                  s"Object ${request.objectKey} (${request.objectVersion}) is under retention until " +
                    s"${retention.until}; its removal is retried then"
                )
              case StorageError.Locked(_, _, None, _) =>
                ZIO
                  .logInfo(
                    s"Object ${request.objectKey} (${request.objectVersion}) has an object legal hold and is kept"
                  )
                  .as(KeptLocked)
              case e: StorageError.Unavailable =>
                ZIO.fail(s"object store unavailable while deleting ${request.objectKey}: ${e.cause}")
              case e =>
                ZIO
                  .logWarning(s"Object ${request.objectKey} (${request.objectVersion}) was not deleted: $e")
                  .as(Failed)
            }
    yield outcome

  /** Finds the orphaned objects and stored files of a tenant and removes them (or only reports them with
    * `dryRun`). The run is stored with its report and recorded in the audit trail.
    */
  def sweep(tenant: TenantId, dryRun: Boolean, actor: UserId): IO[String, CleanupReport] =
    for
      started <- Clock.instant
      cutoff = started.minus(config.grace)
      files <- inTenant(tenant)(Db.sql(orphanFiles(cutoff)))
      _     <- ZIO.unless(dryRun || files.isEmpty)(
        inTenant(tenant)(
          Db.sql(
            files.foreach(f => sql"delete from dms.document_attachment_file where id = ${f._1}".update.run())
          )
        )
      )
      fileEntries = files.map((_, _, key, version) =>
        CleanupEntry(key, version, if dryRun then "FileWouldDelete" else "FileDeleted")
      )
      known <- inTenant(tenant)(Db.sql((references, heldDocuments)))
      (refs, held) = known
      // In a dry run the files above still exist; their objects count as orphaned as they would after the run.
      refsNow  = if dryRun then refs -- files.map(f => (f._3, f._4)) else refs
      prefixes = List("attachments", "renditions", "templates").map(p => s"$p/${tenant.value}/")
      keys <- ZIO
        .foreach(prefixes)(p => objects.listKeys(p))
        .mapBoth(e => s"object storage could not be listed: $e", _.flatten)
      versions <- ZIO
        .foreach(keys)(k => objects.versions(k).map(_.map(v => (k, v.versionId))))
        .mapBoth(e => s"object versions could not be listed: $e", _.flatten)
      orphans = versions.filterNot(refsNow.contains)
      results  <- ZIO.foreach(orphans)((key, version) => judgeOrphan(key, version, held, cutoff, dryRun))
      finished <- Clock.instant
      summary = CleanupSummary(
        scanned = versions.size,
        orphaned = orphans.size,
        deleted = results.count(e => e.outcome == Deleted || e.outcome == WouldDelete),
        keptLocked = results.count(_.outcome == KeptLocked),
        keptHeld = results.count(_.outcome == KeptHeld),
        keptRecent = results.count(_.outcome == KeptRecent),
        failed = results.count(_.outcome == Failed),
        filesDeleted = files.size
      )
      report = CleanupReport(
        UUID.randomUUID(),
        dryRun,
        started,
        finished,
        actor.value,
        config.grace.toHours,
        summary,
        (fileEntries ++ results).take(config.maxEntries)
      )
      _ <- inTenant(tenant)(store(report) *> recordRun(actor, report))
    yield report

  /** The latest runs of the tenant, newest first. */
  def runs(limit: Int): IO[DbError, List[CleanupReport]] =
    Db.sql(
      sql"""select id, dry_run, started_at, finished_at, triggered_by, grace_hours, summary, entries
            from dms.storage_cleanup_run order by started_at desc limit $limit"""
        .query[(UUID, Boolean, OffsetDateTime, OffsetDateTime, String, Int, Jsonb, Jsonb)]
        .run()
        .toList
        .map((id, dry, started, finished, by, grace, summary, entries) =>
          CleanupReport(
            id,
            dry,
            started.toInstant,
            finished.toInstant,
            by,
            grace.toLong,
            summary.value.fromJson[CleanupSummary].getOrElse(CleanupSummary(0, 0, 0, 0, 0, 0, 0, 0)),
            entries.value.fromJson[List[CleanupEntry]].getOrElse(Nil)
          )
        )
    )

  private def judgeOrphan(
      key: String,
      version: String,
      held: Set[UUID],
      cutoff: Instant,
      dryRun: Boolean
  ): IO[String, CleanupEntry] =
    if documentOf(key).exists(held.contains) then ZIO.succeed(CleanupEntry(key, version, KeptHeld))
    else
      objects
        .head(key, Some(version))
        .foldZIO(
          {
            case StorageError.NotFound(_, _) => ZIO.succeed(CleanupEntry(key, version, Deleted, Some("gone")))
            case e: StorageError.Unavailable => ZIO.fail(s"object store unavailable: ${e.cause}")
            case e => ZIO.succeed(CleanupEntry(key, version, Failed, Some(e.toString)))
          },
          info =>
            Clock.instant.flatMap { now =>
              val modified  = Some(info.lastModified)
              val retention = info.retention.filter(_.until.isAfter(now))
              if info.lastModified.isAfter(cutoff) then
                ZIO.succeed(CleanupEntry(key, version, KeptRecent, None, modified))
              else if retention.isDefined || info.legalHold then
                ZIO.succeed(
                  CleanupEntry(
                    key,
                    version,
                    KeptLocked,
                    retention.map(r => s"retention until ${r.until}").orElse(Some("object legal hold")),
                    modified
                  )
                )
              else if dryRun then ZIO.succeed(CleanupEntry(key, version, WouldDelete, None, modified))
              else
                objects
                  .delete(key, version)
                  .as(CleanupEntry(key, version, Deleted, None, modified))
                  .catchAll {
                    case StorageError.NotFound(_, _) =>
                      ZIO.succeed(CleanupEntry(key, version, Deleted, None, modified))
                    case StorageError.Locked(_, _, r, _) =>
                      ZIO.succeed(
                        CleanupEntry(
                          key,
                          version,
                          KeptLocked,
                          r.map(x => s"retention until ${x.until}"),
                          modified
                        )
                      )
                    case e: StorageError.Unavailable => ZIO.fail(s"object store unavailable: ${e.cause}")
                    case e => ZIO.succeed(CleanupEntry(key, version, Failed, Some(e.toString), modified))
                  }
            }
        )

  private def store(report: CleanupReport): IO[DbError, Unit] =
    Db.sql(
      sql"""insert into dms.storage_cleanup_run (id, tenant_id, started_at, finished_at, dry_run, triggered_by,
                                                 grace_hours, summary, entries)
            values (${report.id}, dms.current_tenant(), ${utc(report.startedAt)}, ${utc(report.finishedAt)},
                    ${report.dryRun}, ${report.triggeredBy}, ${report.graceHours.toInt},
                    ${Jsonb(report.summary.toJson)}, ${Jsonb(report.entries.toJson)})""".update.run()
    ).unit

  private def recordRun(actor: UserId, report: CleanupReport): IO[DbError, Unit] =
    val technical = actor.value.startsWith("system:")
    audit
      .record(
        AuditEvent(
          Actor(actor.value, if technical then ActorKind.Technical else ActorKind.Person),
          "storage.cleanup-run",
          AuditObject("storage-cleanup", Some(report.id)),
          if technical then Channel.Automation else Channel.Api,
          Jsonb(
            Json
              .Obj(
                "dryRun"     -> Json.Bool(report.dryRun),
                "graceHours" -> Json.Num(report.graceHours),
                "summary"    -> report.summary.toJsonAST.getOrElse(Json.Obj())
              )
              .toJson
          )
        )
      )
      .unit

  private def inTenant[A](tenant: TenantId)(f: IO[DbError, A]): IO[String, A] =
    db.transact(tenant)(f).mapError(e => s"database error: $e")

object ObjectCleanup:

  /** An object released by `origin` (e.g. destruction) of a document, if it belongs to one. */
  final case class Request(documentId: Option[UUID], objectKey: String, objectVersion: String, origin: String)
      derives JsonCodec

  val Deleted     = "Deleted"
  val WouldDelete = "WouldDelete"
  val Kept        = "Kept"
  val KeptLocked  = "KeptLocked"
  val KeptHeld    = "KeptHeld"
  val KeptRecent  = "KeptRecent"
  val Failed      = "Failed"

  /** Enqueues the removal of an object in the current transaction; once per object version. */
  def enqueue(request: Request): IO[DbError, Unit] =
    JobQueue
      .enqueue(
        RetentionEvents.ObjectCleanupJobKind,
        Jsonb(request.toJson),
        idempotencyKey =
          Some(s"${RetentionEvents.ObjectCleanupJobKind}:${request.objectKey}:${request.objectVersion}")
      )
      .unit

  /** Whether a row references the object version: a stored file, the rendition of a version that is not
    * destroyed, or the Word file of a template version.
    */
  private[core] def referenced(key: String, version: String)(using DbTx): Boolean =
    sql"""select exists (select 1 from dms.document_attachment_file
                         where object_key = $key and object_version = $version)
              or exists (select 1 from dms.document_version
                         where rendition_key = $key and rendition_version = $version and status <> 'Destroyed')
              or exists (select 1 from dms.template_word_file
                         where object_key = $key and object_version = $version)""".query[Boolean].run().head

  /** Every object version a row of the tenant references (see [[referenced]]). */
  private def references(using DbTx): Set[(String, String)] =
    (sql"select object_key, object_version from dms.document_attachment_file".query[(String, String)].run() ++
      sql"""select rendition_key, rendition_version from dms.document_version
            where rendition_key is not null and rendition_version is not null and status <> 'Destroyed'"""
        .query[(String, String)]
        .run() ++
      sql"select object_key, object_version from dms.template_word_file".query[(String, String)].run()).toSet

  private def heldDocuments(using DbTx): Set[UUID] = LegalHoldService.heldDocuments.map(_.value).toSet

  private def heldDocument(document: UUID)(using DbTx): Boolean =
    sql"select dms.document_under_legal_hold($document)".query[Boolean].run().head

  /** Stored files older than `cutoff` that no version links (as attachment or original), of documents not
    * under legal hold: (file, document, object key, object version).
    */
  private def orphanFiles(cutoff: Instant)(using DbTx): List[(UUID, UUID, String, String)] =
    sql"""select f.id, f.document_id, f.object_key, f.object_version from dms.document_attachment_file f
          where f.created_at < ${utc(cutoff)}
            and not exists (select 1 from dms.document_version_attachment a where a.file_id = f.id)
            and not exists (select 1 from dms.document_version_file o where o.file_id = f.id)
            and not dms.document_under_legal_hold(f.document_id)
          order by f.created_at, f.id""".query[(UUID, UUID, String, String)].run().toList

  /** The document of an attachment or rendition key (`<kind>/<tenant>/<document>/...`). */
  def documentOf(key: String): Option[UUID] =
    key.split('/').toList match
      case ("attachments" | "renditions") :: _ :: document :: _ => Try(UUID.fromString(document)).toOption
      case _                                                    => None

  private def utc(at: Instant): OffsetDateTime = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
