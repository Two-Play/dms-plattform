package dms.archive.core

import com.augustnagro.magnum.*
import dms.archive.LegalHoldEvents
import dms.db.queue.ClaimedJob
import dms.db.{Db, Jsonb}
import dms.kernel.{DocumentId, TenantId}
import dms.storage.{ObjectStore, StorageError}
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.util.UUID

/** Brings the object legal hold (S3 Object Lock, ADR 0016) of a document's renditions and attachment files
  * (DMS-114, ADR 0041) in line with its legal holds: on while an active hold covers the document, off
  * otherwise. Defence in depth besides the database guard: an object under legal hold cannot be deleted by
  * anyone, not even with governance bypass.
  *
  * Runs as job [[LegalHoldEvents.SyncJobKind]] after the transaction that changed the holds (the object store
  * is not transactional); the job is idempotent and reads the current state, so at-least-once delivery and
  * reordered jobs are safe. If the holds change while the objects are updated, it repeats.
  *
  * Best effort for errors that a retry does not fix (a missing object, an object store without legal hold
  * support, missing permissions): logged as a warning and skipped, the database guard still protects the
  * document. An unreachable object store fails the job, which the job runner retries.
  */
final class LegalHoldSync(db: Db, objects: ObjectStore):
  import LegalHoldSync.*

  val handlers: Map[String, ClaimedJob => IO[String, Unit]] = Map(LegalHoldEvents.SyncJobKind -> handle)

  def handle(job: ClaimedJob): IO[String, Unit] =
    for
      tenant   <- ZIO.fromOption(job.tenant).orElseFail(s"job ${job.id} (${job.kind}) has no tenant")
      document <- ZIO.fromEither(documentOf(job.payload))
      _        <- sync(tenant, document)
    yield ()

  /** Sets or releases the object legal hold of every rendition and attachment file of the document. Returns
    * the objects changed or confirmed and whether they are now held.
    */
  def sync(tenant: TenantId, document: DocumentId): IO[String, SyncResult] =
    def round(attempt: Int): IO[String, SyncResult] =
      for
        before <- state(tenant, document)
        (held, renditions) = before
        applied <- ZIO.foreach(renditions)((key, version) => apply(key, version, held))
        after   <- state(tenant, document)
        result  <-
          if after == before || attempt >= MaxRounds then ZIO.succeed(SyncResult(held, applied.flatten))
          else round(attempt + 1)
      yield result
    round(1)

  private def state(tenant: TenantId, document: DocumentId): IO[String, (Boolean, List[(String, String)])] =
    db.transact(tenant)(
      Db.sql {
        val held = sql"select dms.document_under_legal_hold(${document.value})".query[Boolean].run().head
        val renditions =
          sql"""select rendition_key, rendition_version from dms.document_version
                where document_id = ${document.value} and rendition_key is not null
                  and rendition_version is not null
                order by major, minor, id""".query[(String, String)].run().toList
        // Attachment files of the document (DMS-114, migration V26), whichever versions reference them.
        val attachments =
          sql"""select object_key, object_version from dms.document_attachment_file
                where document_id = ${document.value}
                order by created_at, id""".query[(String, String)].run().toList
        (held, renditions ++ attachments)
      }
    ).mapError(e => s"legal holds of document ${document.value} could not be read: $e")

  private def apply(key: String, version: String, on: Boolean): IO[String, Option[(String, String)]] =
    objects
      .setLegalHold(key, version, on)
      .as(Some((key, version)))
      .catchAll {
        case e: StorageError.Unavailable =>
          ZIO.fail(s"object store unavailable while setting the legal hold of $key ($version): ${e.cause}")
        case e =>
          ZIO
            .logWarning(
              s"Object legal hold ${if on then "on" else "off"} for $key ($version) not applied: $e. " +
                "The database guard still protects the document (ADR 0031)."
            )
            .as(None)
      }

object LegalHoldSync:

  /** Rounds when the holds keep changing while the objects are updated; the next job settles the rest. */
  val MaxRounds: Int = 3

  /** @param objects
    *   rendition and attachment objects whose legal hold now matches `held`
    */
  final case class SyncResult(held: Boolean, objects: List[(String, String)])

  private def documentOf(payload: Jsonb): Either[String, DocumentId] =
    payload.value
      .fromJson[Json.Obj]
      .flatMap(_.get("documentId").flatMap(_.asString).toRight("payload has no documentId"))
      .flatMap(id => scala.util.Try(UUID.fromString(id)).toEither.left.map(_.getMessage))
      .map(DocumentId(_))
