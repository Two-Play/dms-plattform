package dms.audit.core

import dms.audit.{AuditTrail, VerificationReport, Violation}
import dms.db.{Db, DbError}
import dms.kernel.TenantId
import dms.storage.{ObjectContent, ObjectStore, Retention, RetentionMode, StorageError}
import zio.*

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit

/** @param retention
  *   compliance retention of each checkpoint; at least as long as the longest retention of audited documents
  * @param keyPrefix
  *   object key prefix; checkpoints are stored as `<prefix>/<tenant>/<chain seq, 20 digits>`
  */
final case class CheckpointConfig(
    retention: Duration = (11 * 365).days,
    keyPrefix: String = "audit-checkpoints"
)

/** A chain head written to write-once storage. */
final case class Checkpoint(tenant: TenantId, chainSeq: Long, chainHeadHash: String, writtenAt: Instant)

/** Copies the chain head of a tenant into object storage with Object Lock in compliance mode (DMS-74, ADR
  * 0017).
  *
  * Timestamp anchors are written once a day, so the entries after the last anchor could be cut off the end of
  * the chain without breaking any link. Checkpoints close that window down to the checkpoint interval: they
  * are cheap, need no TSA, and not even a database owner can delete or change a locked object version before
  * its retention ends. Verification compares the chain with the newest checkpoint.
  */
final class AuditCheckpoints(db: Db, trail: AuditTrail, store: ObjectStore, config: CheckpointConfig):
  import AuditCheckpoints.*

  /** Writes the current chain head of `tenant` unless it is already checkpointed. Returns the new checkpoint.
    */
  def write(tenant: TenantId): IO[DbError | StorageError, Option[Checkpoint]] =
    db.transact(tenant)(Db.sql(ChainStore.head(tenant.value))).flatMap {
      case None              => ZIO.none
      case Some((seq, hash)) =>
        val key = keyFor(tenant, seq)
        store.versions(key).flatMap {
          case _ :: _ => ZIO.none
          case Nil    =>
            for
              now <- Clock.instant.map(_.truncatedTo(ChronoUnit.MILLIS))
              checkpoint = Checkpoint(tenant, seq, AuditHash.hex(hash), now)
              _ <- store.put(
                key,
                ObjectContent.Bytes(Chunk.fromArray(render(checkpoint).getBytes(UTF_8))),
                "text/plain; charset=utf-8",
                retention = Some(Retention(RetentionMode.Compliance, now.plus(config.retention))),
                legalHold = false
              )
            yield Some(checkpoint)
        }
    }

  /** Verifies the chain (links, hashes, anchors) and compares it with every version of the newest checkpoint.
    * Older checkpoints are covered implicitly: the chain must contain the newest one unchanged.
    */
  def verify(tenant: TenantId): IO[DbError | StorageError, VerificationReport] =
    trail.verify(tenant).flatMap { report =>
      if !report.ok then ZIO.succeed(report)
      else
        newestKey(tenant).flatMap {
          case None      => ZIO.succeed(report)
          case Some(key) =>
            for
              versions    <- store.versions(key)
              checkpoints <- ZIO.foreach(versions)(v => readCheckpoint(key, v.versionId))
              violation   <- ZIO.foldLeft(checkpoints)(Option.empty[Violation])((found, cp) =>
                found.fold(compare(tenant, report, cp))(v => ZIO.some(v))
              )
            yield report.copy(violation = violation)
        }
    }

  /** Chain position of the newest checkpoint of `tenant`, if any. */
  def newestSeq(tenant: TenantId): IO[StorageError, Option[Long]] =
    newestKey(tenant).map(_.flatMap(_.split('/').lastOption).flatMap(_.toLongOption))

  private def compare(
      tenant: TenantId,
      report: VerificationReport,
      checkpoint: Either[String, Checkpoint]
  ): IO[DbError, Option[Violation]] =
    checkpoint match
      case Left(problem) =>
        ZIO.some(Violation(report.headSeq.getOrElse(0L), Violation.Kind.CheckpointMismatch, problem))
      case Right(cp) if cp.tenant != tenant =>
        ZIO.some(
          Violation(cp.chainSeq, Violation.Kind.CheckpointMismatch, "checkpoint belongs to another tenant")
        )
      case Right(cp) if report.headSeq.forall(_ < cp.chainSeq) =>
        ZIO.some(
          Violation(
            cp.chainSeq,
            Violation.Kind.CheckpointMismatch,
            s"chain ends at ${report.headSeq.getOrElse(0L)} but a checkpoint from ${cp.writtenAt} records ${cp.chainSeq}"
          )
        )
      case Right(cp) =>
        db.transact(tenant)(Db.sql(ChainStore.hashAt(tenant.value, cp.chainSeq))).map {
          case Some(hash) if MessageDigest.isEqual(AuditHash.unhex(cp.chainHeadHash), hash) => None
          case _                                                                            =>
            Some(
              Violation(
                cp.chainSeq,
                Violation.Kind.CheckpointMismatch,
                s"entry hash differs from the checkpoint written at ${cp.writtenAt}"
              )
            )
        }

  private def newestKey(tenant: TenantId): IO[StorageError, Option[String]] =
    store.listKeys(s"${config.keyPrefix}/${tenant.value}/").map(_.lastOption)

  private def readCheckpoint(key: String, versionId: String): IO[StorageError, Either[String, Checkpoint]] =
    store.get(key, versionId).runCollect.map(bytes => parse(String(bytes.toArray, UTF_8)))

  private def keyFor(tenant: TenantId, seq: Long): String = f"${config.keyPrefix}/${tenant.value}/$seq%020d"

object AuditCheckpoints:
  val layer: ZLayer[Db & AuditTrail & ObjectStore & CheckpointConfig, Nothing, AuditCheckpoints] =
    ZLayer.fromFunction(AuditCheckpoints(_, _, _, _))

  /** Checkpoint format 1: one `name=value` per line, readable without tooling. */
  private[core] def render(cp: Checkpoint): String =
    s"""format=1
       |tenant=${cp.tenant.value}
       |chainSeq=${cp.chainSeq}
       |chainHeadHash=${cp.chainHeadHash}
       |writtenAt=${cp.writtenAt}
       |""".stripMargin

  private[core] def parse(text: String): Either[String, Checkpoint] =
    val fields = text.linesIterator
      .flatMap(_.split("=", 2) match
        case Array(k, v) => Some(k -> v)
        case _           => None)
      .toMap
    def field(name: String) = fields.get(name).toRight(s"checkpoint lacks $name")
    for
      format <- field("format")
      _      <- Either.cond(format == "1", (), s"unknown checkpoint format $format")
      tenant <- field("tenant").flatMap(t =>
        scala.util.Try(TenantId(java.util.UUID.fromString(t))).toEither.left.map(_.toString)
      )
      seq  <- field("chainSeq").flatMap(_.toLongOption.toRight("invalid chainSeq"))
      hash <- field("chainHeadHash").filterOrElse(_.matches("[0-9a-f]{64}"), "invalid chainHeadHash")
      at   <- field("writtenAt").flatMap(w => scala.util.Try(Instant.parse(w)).toEither.left.map(_.toString))
    yield Checkpoint(tenant, seq, hash, at)
