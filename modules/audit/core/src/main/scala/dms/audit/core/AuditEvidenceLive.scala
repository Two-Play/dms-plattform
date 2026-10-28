package dms.audit.core

import com.augustnagro.magnum.*
import dms.audit.*
import dms.db.{Db, DbError}
import dms.kernel.TenantId
import dms.storage.StorageError
import zio.*

import java.time.{OffsetDateTime, ZoneOffset}
import java.util.UUID

/** [[AuditEvidence]] on the audit tables (ADR 0017, 0034).
  *
  * @param checkpoints
  *   the write-once checkpoints to compare the chain with; `None` where no object storage is configured
  */
final class AuditEvidenceLive(db: Db, trail: AuditTrail, checkpoints: Option[AuditCheckpoints])
    extends AuditEvidence:

  def entriesOf(objectId: UUID): IO[DbError, List[RecordedEntry]] =
    Db.sql(ChainStore.entriesOf(objectId).toList.map(AuditEvidenceLive.recorded))

  def integrity(tenant: TenantId): IO[DbError, ChainIntegrity] =
    for
      verified <- checkpoints match
        case None     => trail.verify(tenant).map(r => (r, Option.empty[String]))
        case Some(cp) =>
          cp.verify(tenant)
            .map(r => (r, Option.empty[String]))
            .catchAll {
              case e: StorageError =>
                trail.verify(tenant).map(r => (r, Some(s"checkpoints could not be read: $e")))
              case e: DbError => ZIO.fail(e)
            }
      (report, problem) = verified
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

/** [[EvidenceReportRegistry]] on `dms.evidence_report` (migration V21). */
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
            from dms.evidence_report where id = $id"""
        .query[Row]
        .run()
        .headOption
        .map { case (id, document, format, at, by, payload, sha) =>
          StoredEvidenceReport(id, document, format.toInt, at.toInstant, by, payload, sha)
        }
    )

  val layer: ULayer[EvidenceReportRegistry] = ZLayer.succeed(EvidenceReportStore)
