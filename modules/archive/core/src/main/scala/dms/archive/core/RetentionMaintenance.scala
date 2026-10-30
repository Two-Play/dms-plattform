package dms.archive.core

import com.augustnagro.magnum.*
import dms.archive.RetentionEvents
import dms.audit.*
import dms.db.queue.{ClaimedJob, JobQueue}
import dms.db.{Db, DbError, Jsonb}
import dms.kernel.{TenantId, UserId}
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.UUID

/** Rules of the periodic maintenance (ADR 0054).
  *
  * @param interval
  *   how often the maintenance of every tenant runs
  * @param keepLatestOpen
  *   working states kept at least for a version that is still worked on (Draft, InReview, InApproval)
  * @param keepLatestClosed
  *   working states kept at least for a version that is no longer open (approved, withdrawn, ...)
  * @param keepRecent
  *   working states saved within this period are never compacted
  * @param discardedContentGrace
  *   the content of a discarded draft is removed this long after it was discarded (data protection, ADR 0050)
  * @param sweepOrphans
  *   whether the maintenance also removes orphaned objects (otherwise only on request)
  */
final case class MaintenanceConfig(
    interval: Duration = 24.hours,
    keepLatestOpen: Int = 10,
    keepLatestClosed: Int = 1,
    keepRecent: Duration = 7.days,
    discardedContentGrace: Duration = 30.days,
    sweepOrphans: Boolean = true,
    batchSize: Int = 500
)

/** What one maintenance run of a tenant did. */
final case class MaintenanceResult(compactedVersions: Int, removedStates: Int, purgedDrafts: Int)

/** Pure rules of the compaction of working states (ADR 0054). */
object WorkingStateCompaction:

  /** A saved working state: its number and when it was saved. */
  final case class State(number: Int, savedAt: Instant)

  /** The working states that may be removed, in ascending order. Kept are: the current state, the latest
    * `keepLatest`, those saved after `keepAfter`, and every state a vote or a deviation request was given on
    * (the latest state saved at or before it: what the reviewers saw).
    */
  def removable(
      states: List[State],
      current: Int,
      reviewedAt: List[Instant],
      keepLatest: Int,
      keepAfter: Instant
  ): List[Int] =
    val sorted   = states.sortBy(_.number)
    val latest   = sorted.takeRight(keepLatest.max(1)).map(_.number).toSet
    val reviewed =
      reviewedAt.flatMap(at => sorted.filterNot(_.savedAt.isAfter(at)).lastOption.map(_.number)).toSet
    sorted
      .filterNot(s =>
        s.number >= current || latest.contains(s.number) || reviewed.contains(s.number) || s.savedAt
          .isAfter(keepAfter)
      )
      .map(_.number)

/** Periodic maintenance of retention data (DMS-82, ADR 0054), one job [[RetentionEvents.MaintenanceJobKind]]
  * per tenant and interval:
  *
  *   - compaction of working states ([[WorkingStateCompaction]]): never the current state, never one that was
  *     reviewed, never within the recent period, never of a document under legal hold (also refused by the
  *     database, migration V33);
  *   - removal of the content of discarded drafts after the grace period (ADR 0050): working states deleted,
  *     content and values emptied; the version row and the audit trail stay; not while under legal hold;
  *   - the clean-up of orphaned objects ([[ObjectCleanup.sweep]]), if enabled.
  *
  * Each version runs in its own transaction; every step is idempotent.
  */
final class RetentionMaintenance(
    db: Db,
    audit: AuditTrail,
    cleanup: ObjectCleanup,
    config: MaintenanceConfig
):
  import RetentionMaintenance.*

  val handlers: Map[String, ClaimedJob => IO[String, Unit]] = Map(RetentionEvents.MaintenanceJobKind -> run)

  /** Enqueues the maintenance of every tenant for the current interval. */
  def tick: IO[DbError, Int] =
    for
      now     <- Clock.instant
      tenants <- JobQueue.tenantIds(db)
      slot = now.toEpochMilli / config.interval.toMillis.max(1)
      added <- ZIO.foreach(tenants)(tenant =>
        db.transact(tenant)(
          JobQueue.enqueue(
            RetentionEvents.MaintenanceJobKind,
            idempotencyKey = Some(s"${RetentionEvents.MaintenanceJobKind}:${tenant.value}:$slot"),
            maxAttempts = 3
          )
        )
      )
    yield added.flatten.size

  def schedule: UIO[Nothing] =
    (tick.catchAll(e => ZIO.logWarning(s"Scheduling the retention maintenance failed: $e").as(0)) *>
      ZIO.sleep(config.interval)).forever

  private def run(job: ClaimedJob): IO[String, Unit] =
    for
      tenant <- ZIO.fromOption(job.tenant).orElseFail(s"job ${job.id} (${job.kind}) has no tenant")
      result <- maintain(tenant)
      _      <- ZIO.logInfo(
        s"Retention maintenance of tenant ${tenant.value}: ${result.removedStates} working states of " +
          s"${result.compactedVersions} versions compacted, ${result.purgedDrafts} discarded drafts emptied"
      )
      _ <- ZIO.when(config.sweepOrphans)(cleanup.sweep(tenant, dryRun = false, Maintainer))
    yield ()

  /** Compaction and removal of discarded drafts' content for one tenant. */
  def maintain(tenant: TenantId): IO[String, MaintenanceResult] =
    for
      compacted <- compact(tenant)
      purged    <- purgeDiscarded(tenant)
    yield MaintenanceResult(compacted.count(_ > 0), compacted.sum, purged)

  /** Compacts the working states of every version with more states than its rule keeps; the removed count per
    * version.
    */
  def compact(tenant: TenantId): IO[String, List[Int]] =
    for
      ids <- inTenant(tenant)(
        Db.sql(versionsToCompact(config.keepLatestOpen, config.keepLatestClosed, config.batchSize))
      )
      now     <- Clock.instant
      removed <- ZIO.foreach(ids)(id => inTenant(tenant)(compactVersion(id, now)))
    yield removed

  private def compactVersion(id: UUID, now: Instant): IO[DbError, Int] =
    for
      facts <- Db.sql {
        val version =
          sql"""select v.document_id, v.status, v.working_state, v.major, v.minor,
                       dms.document_under_legal_hold(v.document_id)
                from dms.document_version v where v.id = $id for update"""
            .query[(UUID, String, Int, Int, Int, Boolean)]
            .run()
            .headOption
        val states =
          sql"""select working_state, saved_at from dms.document_working_state where version_id = $id
                order by working_state""".query[(Int, OffsetDateTime)].run().toList
        val reviewed =
          sql"""select voted_at from dms.workflow_vote where version_id = $id
                union all
                select requested_at from dms.document_deviation_request where version_id = $id"""
            .query[OffsetDateTime]
            .run()
            .toList
        (version, states, reviewed)
      }
      (version, states, reviewed) = facts
      removed <- version match
        case None                                                   => ZIO.succeed(Nil)
        case Some((_, _, _, _, _, true))                            => ZIO.succeed(Nil)
        case Some((document, status, current, major, minor, false)) =>
          val keep = if OpenStatuses.contains(status) then config.keepLatestOpen else config.keepLatestClosed
          val numbers = WorkingStateCompaction.removable(
            states.map((n, at) => WorkingStateCompaction.State(n, at.toInstant)),
            current,
            reviewed.map(_.toInstant),
            keep,
            now.minus(config.keepRecent)
          )
          ZIO
            .unless(numbers.isEmpty)(
              Db.sql(
                // Ascending, so that the database's reference check sees the later states (migration V33).
                numbers.foreach(n =>
                  sql"delete from dms.document_working_state where version_id = $id and working_state = $n".update
                    .run()
                )
              ) *> record(
                "document.working-states-compacted",
                document,
                s"$major.$minor",
                Json.Obj(
                  "versionId" -> Json.Str(id.toString),
                  "removed"   -> Json.Arr(Chunk.fromIterable(numbers.map(n => Json.Num(n)))),
                  "kept"      -> Json.Num(states.size - numbers.size)
                )
              )
            )
            .as(numbers)
    yield removed.size

  /** Removes the content of discarded drafts whose grace period passed; the number of drafts emptied. */
  def purgeDiscarded(tenant: TenantId): IO[String, Int] =
    for
      now <- Clock.instant
      cutoff = OffsetDateTime.ofInstant(now.minus(config.discardedContentGrace), ZoneOffset.UTC)
      ids <- inTenant(tenant)(
        Db.sql(
          sql"""select v.id from dms.document_version v
                where v.status = 'Discarded' and v.content_removed_at is null and v.status_changed_at < $cutoff
                  and not dms.document_under_legal_hold(v.document_id)
                order by v.status_changed_at, v.id
                limit ${config.batchSize}""".query[UUID].run().toList
        )
      )
      purged <- ZIO.foreach(ids)(id => inTenant(tenant)(purgeVersion(id)))
    yield purged.count(identity)

  private def purgeVersion(id: UUID): IO[DbError, Boolean] =
    for
      found <- Db.sql {
        val version =
          sql"""select document_id, major, minor from dms.document_version
                where id = $id and status = 'Discarded' and content_removed_at is null
                  and not dms.document_under_legal_hold(document_id)
                for update""".query[(UUID, Int, Int)].run().headOption
        version.map { v =>
          val states = sql"delete from dms.document_working_state where version_id = $id".update.run()
          val _      = sql"""update dms.document_version
                    set content = ${Jsonb(DestructionService.TombstoneContent)}, field_values = ${Jsonb(
              "{}"
            )},
                        deviation_report = ${Jsonb(DestructionService.TombstoneDeviations)},
                        content_removed_at = now(), content_removed_by = ${Maintainer.value}, updated_at = now()
                    where id = $id""".update.run()
          (v, states)
        }
      }
      _ <- ZIO.foreachDiscard(found) { case ((document, major, minor), states) =>
        record(
          "document.discarded-content-removed",
          document,
          s"$major.$minor",
          Json.Obj(
            "versionId"     -> Json.Str(id.toString),
            "workingStates" -> Json.Num(states),
            "graceDays"     -> Json.Num(config.discardedContentGrace.toDays)
          )
        )
      }
    yield found.isDefined

  private def record(action: String, document: UUID, version: String, details: Json.Obj): IO[DbError, Unit] =
    audit
      .record(
        AuditEvent(
          Actor(Maintainer.value, ActorKind.Technical),
          action,
          AuditObject("document", Some(document), Some(version)),
          Channel.Automation,
          Jsonb(details.toJson)
        )
      )
      .unit

  private def inTenant[A](tenant: TenantId)(f: IO[DbError, A]): IO[String, A] =
    db.transact(tenant)(f).mapError(e => s"database error: $e")

object RetentionMaintenance:

  /** The platform as actor of the maintenance; recorded as technical actor. */
  val Maintainer: UserId = UserId("system:retention")

  private val OpenStatuses = Set("Draft", "InReview", "InApproval")

  /** Versions (not destroyed, not discarded, not under legal hold) with more working states than kept. */
  private def versionsToCompact(open: Int, closed: Int, limit: Int)(using DbTx): List[UUID] =
    sql"""select v.id from dms.document_version v
          where v.status not in ('Destroyed', 'Discarded')
            and not dms.document_under_legal_hold(v.document_id)
            and (select count(*) from dms.document_working_state s where s.version_id = v.id) >
                case when v.status in ('Draft', 'InReview', 'InApproval') then $open else $closed end
          order by v.updated_at, v.id
          limit $limit""".query[UUID].run().toList
