package dms.audit.core

import com.augustnagro.magnum.*
import dms.audit.*
import dms.db.{Db, DbError}
import dms.kernel.TenantId
import dms.storage.StorageError
import zio.*

import java.time.temporal.ChronoUnit
import java.time.{OffsetDateTime, ZoneOffset}
import java.util.UUID

/** [[AuditEvidence]] on the audit tables (ADR 0017, 0034, 0077).
  *
  * A report does not read the whole chain again: it starts after the prefix of the newest full verification.
  * That verification comes from the regular job `audit.verify`, or from here when the prefix cannot be used.
  * Anything that is not clean ends in a full verification, which is recorded, so a violation closes the
  * shortcut until a later full verification finds the chain intact.
  *
  * @param checkpoints
  *   the write-once checkpoints to compare the chain with; `None` where no object storage is configured
  * @param prefixMaxAge
  *   a prefix is not used once its full verification is older than this
  */
final class AuditEvidenceLive(
    db: Db,
    trail: AuditTrail,
    checkpoints: Option[AuditCheckpoints],
    prefixMaxAge: Duration = AuditEvidenceLive.PrefixMaxAge
) extends AuditEvidence:

  def entriesOf(objectId: UUID): IO[DbError, List[RecordedEntry]] =
    Db.sql(ChainStore.entriesOf(objectId).toList.map(AuditEvidenceLive.recorded))

  def integrity(tenant: TenantId, objectId: UUID): IO[DbError, ChainIntegrity] =
    afterPrefix(tenant, objectId).flatMap {
      case Some((report, problem)) if report.ok => state(tenant, report, problem)
      case _                                    => verifyFully(tenant)
    }

  def verifyFully(tenant: TenantId): IO[DbError, ChainIntegrity] =
    for
      verified <- trail.verify(tenant).flatMap(compared(tenant, _))
      (report, problem) = verified
      now <- Clock.instant.map(_.truncatedTo(ChronoUnit.MICROS))
      _   <- db.transact(tenant)(Db.sql {
        val prefix =
          if report.ok then report.headSeq.flatMap(ChainStore.newestAnchorUpTo(tenant.value, _)) else None
        ChainStore.insertRun(tenant.value, VerificationRun(now, report, problem, prefix))
      })
      integrity <- state(tenant, report, problem)
    yield integrity

  /** What follows the prefix of the newest full verification; `None` if there is no prefix to rely on. */
  private def afterPrefix(
      tenant: TenantId,
      objectId: UUID
  ): IO[DbError, Option[(VerificationReport, Option[String])]] =
    for
      now <- Clock.instant
      run <- db.transact(tenant)(Db.sql(ChainStore.newestRun(tenant.value)))
      prefix = run.collect {
        case (at, true, Some((seq, hash))) if at.plus(prefixMaxAge).isAfter(now) =>
          VerifiedPrefix(seq, AuditHash.hex(hash), at)
      }
      report <- ZIO.foreach(prefix)(trail.verifyAfter(tenant, _, objectId)).map(_.flatten)
      result <- ZIO.foreach(report)(compared(tenant, _))
    yield result

  /** The report compared with the newest checkpoint and why that was not possible, if it was not. */
  private def compared(
      tenant: TenantId,
      report: VerificationReport
  ): IO[DbError, (VerificationReport, Option[String])] =
    checkpoints match
      case None     => ZIO.succeed((report, None))
      case Some(cp) =>
        cp.compared(tenant, report)
          .map(r => (r, Option.empty[String]))
          .catchAll {
            case e: StorageError => ZIO.succeed((report, Some(s"checkpoints could not be read: $e")))
            case e: DbError      => ZIO.fail(e)
          }

  private def state(
      tenant: TenantId,
      report: VerificationReport,
      problem: Option[String]
  ): IO[DbError, ChainIntegrity] =
    for
      anchor <- db.transact(tenant)(Db.sql(ChainStore.newestAnchor(tenant.value)))
      newest <- checkpoints.fold(ZIO.succeed(Option.empty[Long] -> problem))(cp =>
        cp.newestSeq(tenant)
          .map(_ -> problem)
          .catchAll(e => ZIO.succeed(None -> problem.orElse(Some(s"checkpoints could not be read: $e"))))
      )
    yield ChainIntegrity(
      report,
      anchor.map((seq, at, url) => AnchorState(seq, at, url)),
      newest._1,
      newest._2
    )

object AuditEvidenceLive:

  /** The regular full verification runs daily; this leaves room for a job that failed over a weekend. */
  val PrefixMaxAge: Duration = 3.days

  val layer: URLayer[Db & AuditTrail, AuditEvidence] =
    ZLayer.fromFunction((db: Db, trail: AuditTrail) => AuditEvidenceLive(db, trail, None))

  def withCheckpoints: URLayer[Db & AuditTrail & AuditCheckpoints, AuditEvidence] =
    ZLayer.fromFunction((db: Db, trail: AuditTrail, cp: AuditCheckpoints) =>
      AuditEvidenceLive(db, trail, Some(cp))
    )

  private def recorded(stored: StoredEntry): RecordedEntry =
    val e = stored.entry
    RecordedEntry(
      chainSeq = e.chainSeq,
      occurredAt = e.occurredAt,
      actorId = e.actorId,
      actorKind = e.actorKind,
      onBehalfOf = e.onBehalfOf,
      action = e.action,
      objectType = e.objectType,
      objectId = e.objectId,
      objectVersion = e.objectVersion,
      details = e.details,
      reason = e.reason,
      channel = e.channel,
      correlationId = e.correlationId,
      hash = AuditHash.hex(stored.hash)
    )

/** [[EvidenceReportRegistry]] on `dms.evidence_report` (migrations V21, V54). */
object EvidenceReportStore extends EvidenceReportRegistry:

  private type Row = (UUID, UUID, Short, OffsetDateTime, String, String, String)

  def register(r: StoredEvidenceReport): IO[DbError, Unit] =
    val createdAt = OffsetDateTime.ofInstant(r.createdAt, ZoneOffset.UTC)
    val format    = r.format.toShort
    Db.sql {
      val _ = sql"""insert into dms.evidence_report
                      (id, tenant_id, document_id, format, created_at, created_by, payload, sha256)
                    values (${r.id}, dms.current_tenant(), ${r.documentId}, $format, $createdAt, ${r.createdBy},
                            ${r.payload}, ${r.sha256})""".update.run()
    }

  def find(id: UUID): IO[DbError, Option[StoredEvidenceReport]] =
    Db.sql(
      sql"""select id, document_id, format, created_at, created_by, payload, sha256
            from dms.evidence_report where id = $id and payload is not null"""
        .query[Row]
        .run()
        .headOption
        .map { case (id, document, format, at, by, payload, sha) =>
          StoredEvidenceReport(id, document, format.toInt, at.toInstant, by, payload, sha)
        }
    )

  private type EntryRow =
    (UUID, UUID, Short, OffsetDateTime, String, String, Option[String], Option[OffsetDateTime])

  private def entryOf(row: EntryRow): EvidenceReportEntry =
    val (id, document, format, at, by, sha, status, removed) = row
    EvidenceReportEntry(id, document, format.toInt, at.toInstant, by, sha, status, removed.map(_.toInstant))

  def entry(id: UUID): IO[DbError, Option[EvidenceReportEntry]] =
    Db.sql(
      sql"""select id, document_id, format, created_at, created_by, sha256,
                   payload::jsonb #>> '{integrity,status}', payload_removed_at
            from dms.evidence_report where id = $id""".query[EntryRow].run().headOption.map(entryOf)
    )

  def entries(document: UUID, limit: Int, offset: Int): IO[DbError, List[EvidenceReportEntry]] =
    Db.sql(
      sql"""select id, document_id, format, created_at, created_by, sha256,
                   payload::jsonb #>> '{integrity,status}', payload_removed_at
            from dms.evidence_report where document_id = $document
            order by created_at desc, id limit $limit offset $offset"""
        .query[EntryRow]
        .run()
        .toList
        .map(entryOf)
    )

  def count(document: UUID): IO[DbError, Int] =
    Db.sql(
      sql"select count(*) from dms.evidence_report where document_id = $document".query[Long].run().head.toInt
    )

  val layer: ULayer[EvidenceReportRegistry] = ZLayer.succeed(EvidenceReportStore)
