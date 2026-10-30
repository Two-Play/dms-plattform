package dms.documents.core

import dms.db.queue.{ClaimedJob, JobQueue}
import dms.db.{Db, DbError, Jsonb}
import dms.kernel.{DocumentId, TenantId}
import dms.storage.{ObjectStore, StorageError}
import zio.*
import zio.json.*

import java.util.UUID

/** Removes the objects of stored files that were deleted because no version references them any more
  * (DMS-129, ADR 0050: discarding a draft). The file row is deleted in the transaction that discards the
  * draft; the object store is not transactional, so this job (ADR 0010) deletes the object version after the
  * commit.
  *
  * The job is idempotent: an object that is gone already counts as removed. Before deleting it checks again
  * in the database: if the document came under legal hold in the meantime, or a file row points at the object
  * again, the object is kept. An object the store refuses to delete because of Object Lock (retention or
  * legal hold) is kept as well and logged; an unreachable store fails the job for a retry.
  */
final class FileCleanupJobs(db: Db, objects: ObjectStore):
  import FileCleanupJobs.*

  val handlers: Map[String, ClaimedJob => IO[String, Unit]] = Map(JobKind -> handle)

  def handle(job: ClaimedJob): IO[String, Unit] =
    for
      tenant  <- ZIO.fromOption(job.tenant).orElseFail(s"job ${job.id} (${job.kind}) has no tenant")
      request <- ZIO.fromEither(job.payload.value.fromJson[Request])
      _       <- cleanUp(tenant, request)
    yield ()

  /** Deletes the object unless something protects or references it again; returns whether it was removed. */
  def cleanUp(tenant: TenantId, request: Request): IO[String, Boolean] =
    for
      blocked <- db
        .transact(tenant)(
          Db.sql(
            AttachmentStore.underLegalHold(DocumentId(request.documentId)) ||
              AttachmentStore.objectReferenced(request.objectKey, request.objectVersion)
          )
        )
        .mapError(e => s"the file ${request.fileId} could not be checked: $e")
      removed <-
        if blocked then
          ZIO
            .logInfo(
              s"Object ${request.objectKey} (${request.objectVersion}) of document ${request.documentId} is kept: " +
                "the document is under legal hold or the object is referenced again"
            )
            .as(false)
        else delete(request)
    yield removed

  private def delete(request: Request): IO[String, Boolean] =
    objects
      .delete(request.objectKey, request.objectVersion)
      .as(true)
      .catchAll {
        case StorageError.NotFound(_, _) => ZIO.succeed(true)
        case e: StorageError.Unavailable =>
          ZIO.fail(s"object store unavailable while deleting ${request.objectKey}: ${e.cause}")
        case e =>
          ZIO
            .logWarning(
              s"Object ${request.objectKey} (${request.objectVersion}) was not deleted and is kept: $e"
            )
            .as(false)
      }

object FileCleanupJobs:

  val JobKind: String = "documents.file-cleanup"

  /** The object of a deleted file of `documentId`. */
  final case class Request(documentId: UUID, fileId: UUID, objectKey: String, objectVersion: String)
      derives JsonCodec

  /** Enqueues the removal of a deleted file's object in the current transaction; once per file. */
  def enqueue(file: AttachmentFile): IO[DbError, Unit] =
    JobQueue
      .enqueue(
        JobKind,
        Jsonb(Request(file.documentId.value, file.id, file.objectKey, file.objectVersion).toJson),
        idempotencyKey = Some(s"$JobKind:${file.id}")
      )
      .unit
