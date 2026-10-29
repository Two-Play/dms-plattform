package dms.documents.core

import dms.documents.*
import dms.kernel.{DocumentId, TenantId}
import dms.storage.{ObjectContent, ObjectStore, ObjectVersion, Sha256, StorageError}
import zio.*
import zio.stream.ZStream

import java.nio.file.{Files, Path}
import java.security.MessageDigest

enum AttachmentError:
  /** The file is larger than the configured limit. */
  case TooLarge(limit: Long)

  /** The file type is on the blocklist (by name or by content). */
  case Blocked(reason: String)

  /** The upload is malformed: no file part, empty file, invalid name, interrupted transfer. */
  case Invalid(message: String)

  /** The virus scanner found something; the file was not stored. */
  case Infected(signature: String)

  /** The virus scanner could not be asked; nothing was stored (fail closed). */
  case ScannerUnavailable(message: String)

  /** Object storage could not be reached. */
  case StorageUnavailable(message: String)

  /** The stored object does not match its recorded SHA-256. */
  case Integrity(message: String)

  case Storage(message: String)

  def describe: String = this match
    case TooLarge(limit)       => s"the file is larger than the limit of $limit bytes"
    case Blocked(reason)       => reason
    case Invalid(message)      => message
    case Infected(signature)   => s"the virus scanner found $signature"
    case ScannerUnavailable(m) => s"the virus scanner is not available: $m"
    case StorageUnavailable(m) => s"object storage is not available: $m"
    case Integrity(message)    => message
    case Storage(message)      => message

/** An upload written to a temporary file while it was received, with its size, SHA-256 and first bytes. */
final case class SpooledFile(path: Path, size: Long, sha256: String, head: Chunk[Byte])

/** The bytes of attachments (DMS-114, ADR 0041), outside any database transaction: receiving an upload into a
  * temporary file with the size limit enforced while it streams (never the whole file in memory), the checks
  * of name and content, the virus scan, storing it in object storage with a server-verified SHA-256, and
  * reading it back.
  */
final class AttachmentUploads(
    objects: ObjectStore,
    scanner: AttachmentScanner,
    val settings: AttachmentSettings
):
  import AttachmentUploads.*

  /** Writes the stream to a temporary file that is deleted when the scope closes. Fails with
    * [[AttachmentError.TooLarge]] as soon as more than the limit has arrived.
    */
  def spool(data: ZStream[Any, Throwable, Byte]): ZIO[Scope, AttachmentError, SpooledFile] =
    for
      path <- ZIO
        .acquireRelease(ZIO.attemptBlocking(Files.createTempFile("dms-attachment-", ".upload")))(p =>
          ZIO.attemptBlocking(Files.deleteIfExists(p)).ignore
        )
        .mapError(e => AttachmentError.Storage(s"no temporary file for the upload: ${e.getMessage}"))
      digest = MessageDigest.getInstance("SHA-256")
      result <- ZIO.scoped(
        ZIO
          .fromAutoCloseable(ZIO.attemptBlocking(Files.newOutputStream(path)))
          .mapError(e => AttachmentError.Storage(s"the upload cannot be buffered: ${e.getMessage}"))
          .flatMap { out =>
            data
              .mapError {
                case e: AttachmentException => e.error
                case e                      =>
                  AttachmentError.Invalid(Option(e.getMessage).getOrElse("the upload was interrupted"))
              }
              .chunks
              .runFoldZIO((0L, Chunk.empty[Byte])) { case ((size, head), chunk) =>
                val next = size + chunk.size
                if next > settings.maxSizeBytes then ZIO.fail(AttachmentError.TooLarge(settings.maxSizeBytes))
                else
                  ZIO
                    .attemptBlocking {
                      val bytes = chunk.toArray
                      digest.update(bytes)
                      out.write(bytes)
                    }
                    .mapError(e => AttachmentError.Storage(s"the upload cannot be buffered: ${e.getMessage}"))
                    .as((next, if head.size < HeadBytes then (head ++ chunk).take(HeadBytes) else head))
              }
          }
      )
      (size, head) = result
    yield SpooledFile(path, size, java.util.HexFormat.of().formatHex(digest.digest()), head)

  /** Checks name and content of a received file: not empty, name not on the blocklist, no executable. */
  def check(name: String, file: SpooledFile): IO[AttachmentError, Unit] =
    if file.size == 0 then ZIO.fail(AttachmentError.Invalid("the file is empty"))
    else
      AttachmentRules
        .blockedName(name, settings)
        .orElse(AttachmentRules.blockedContent(file.head))
        .fold(ZIO.unit)(reason => ZIO.fail(AttachmentError.Blocked(reason)))

  /** Asks the virus scanner; an infected file or an unavailable scanner fails. */
  def scan(file: SpooledFile): IO[AttachmentError, ScanStatus] =
    scanner
      .scan(file.path, file.size)
      .mapError { case ScanError.Unavailable(m) => AttachmentError.ScannerUnavailable(m) }
      .flatMap {
        case ScanVerdict.Clean               => ZIO.succeed(ScanStatus.Clean)
        case ScanVerdict.NotScanned          => ZIO.succeed(ScanStatus.NotScanned)
        case ScanVerdict.Infected(signature) => ZIO.fail(AttachmentError.Infected(signature))
      }

  def scannerName: String = scanner.name

  /** Stores the file as a new object; the server verifies the SHA-256 computed while receiving it. */
  def store(
      tenant: TenantId,
      document: DocumentId,
      fileId: java.util.UUID,
      file: SpooledFile,
      contentType: String
  ): IO[AttachmentError, ObjectVersion] =
    storeAt(AttachmentService.objectKey(tenant, document, fileId), file, contentType)

  /** Stores a received file under `key` (e.g. the Word file of a template, DMS-115), SHA-256 verified. */
  def storeAt(key: String, file: SpooledFile, contentType: String): IO[AttachmentError, ObjectVersion] =
    for
      sha <- ZIO.fromEither(Sha256.fromHex(file.sha256)).mapError(AttachmentError.Storage(_))
      put <- objects
        .put(
          key,
          ObjectContent.Stream(ZStream.fromPath(file.path), file.size),
          contentType,
          expectedSha256 = Some(sha)
        )
        .mapError(storageError)
    yield put.version

  /** Stores bytes as a new attachment file object of a document, e.g. the copy of a Word template (DMS-115).
    */
  def storeBytes(
      tenant: TenantId,
      document: DocumentId,
      fileId: java.util.UUID,
      bytes: Chunk[Byte],
      contentType: String
  ): IO[AttachmentError, ObjectVersion] =
    storeBytesAt(AttachmentService.objectKey(tenant, document, fileId), bytes, contentType)

  /** Stores bytes under `key`, e.g. the copy of a document's original as Word template (DMS-127). */
  def storeBytesAt(key: String, bytes: Chunk[Byte], contentType: String): IO[AttachmentError, ObjectVersion] =
    objects
      .put(key, ObjectContent.Bytes(bytes), contentType, expectedSha256 = Some(Sha256.of(bytes)))
      .mapBoth(storageError, _.version)

  /** The bytes of a stored object, checked against `sha256` (e.g. a template's Word file). */
  def readBytes(key: String, version: String, sha256: String): IO[AttachmentError, Chunk[Byte]] =
    readStored(s"object $key", key, version, sha256).flatMap(
      _.runCollect.mapError {
        case e: AttachmentException => e.error
        case e                      => AttachmentError.StorageUnavailable(e.getMessage)
      }
    )

  /** Removes an object that ended up unreferenced (e.g. a concurrent upload stored the same bytes). Best
    * effort: an object that cannot be removed is left for the clean-up of orphaned objects.
    */
  def discard(version: ObjectVersion): UIO[Unit] =
    objects
      .delete(version.key, version.versionId)
      .catchAll(e => ZIO.logWarning(s"Unreferenced attachment object ${version.key} not removed: $e"))

  /** The bytes of an attachment. The stored object must carry the recorded SHA-256 (checked before the first
    * byte is sent); the object store client validates the checksum while the stream is consumed.
    */
  def read(attachment: VersionAttachment): IO[AttachmentError, ZStream[Any, Throwable, Byte]] =
    readStored(
      s"attachment ${attachment.id}",
      attachment.objectKey,
      attachment.objectVersion,
      attachment.sha256
    )

  /** The bytes of the uploaded original of a file document version (DMS-115), checked like an attachment. */
  def read(file: VersionFile): IO[AttachmentError, ZStream[Any, Throwable, Byte]] =
    readStored(s"the file of version ${file.versionId}", file.objectKey, file.objectVersion, file.sha256)

  private def readStored(
      what: String,
      key: String,
      version: String,
      sha256: String
  ): IO[AttachmentError, ZStream[Any, Throwable, Byte]] =
    objects
      .head(key, Some(version))
      .mapError(storageError)
      .flatMap { info =>
        if !info.sha256.map(_.hex).contains(sha256) then
          ZIO.fail(
            AttachmentError.Integrity(
              s"$what is stored with SHA-256 ${info.sha256.fold("(none)")(_.hex)}, expected $sha256"
            )
          )
        else ZIO.succeed(objects.get(key, version).mapError(e => AttachmentException(storageError(e))))
      }

  /** Reads the whole object and compares its SHA-256 with the recorded one, e.g. for evidence reports. */
  def verify(attachment: VersionAttachment): IO[AttachmentError, Unit] =
    verifyStored(attachment.objectKey, attachment.objectVersion, attachment.sha256)

  /** Like [[verify]] for the original of a file document version (DMS-115). */
  def verify(file: VersionFile): IO[AttachmentError, Unit] =
    verifyStored(file.objectKey, file.objectVersion, file.sha256)

  private def verifyStored(key: String, version: String, sha256: String): IO[AttachmentError, Unit] =
    val digest = MessageDigest.getInstance("SHA-256")
    objects
      .get(key, version)
      .mapError(storageError)
      .runForeachChunk(c => ZIO.succeed(digest.update(c.toArray)))
      .flatMap { _ =>
        val actual = java.util.HexFormat.of().formatHex(digest.digest())
        ZIO.unless(actual == sha256)(
          ZIO.fail(AttachmentError.Integrity(s"the stored object has SHA-256 $actual"))
        )
      }
      .unit

object AttachmentUploads:
  /** First bytes kept for the check of executable signatures. */
  val HeadBytes: Int = 16

  val layer: URLayer[ObjectStore & AttachmentScanner & AttachmentSettings, AttachmentUploads] =
    ZLayer.fromFunction(AttachmentUploads(_, _, _))

  def storageError(e: StorageError): AttachmentError = e match
    case StorageError.Unavailable(cause) => AttachmentError.StorageUnavailable(cause.getMessage)
    case StorageError.ChecksumMismatch(key, expected, actual) =>
      AttachmentError.Integrity(s"$key: expected $expected, got ${actual.fold("none")(_.toString)}")
    case StorageError.NotFound(key, version) =>
      AttachmentError.Integrity(s"the object $key (${version.getOrElse("latest")}) is missing")
    case other => AttachmentError.Storage(other.toString)

  /** Carries an [[AttachmentError]] through a byte stream, whose error type is `Throwable`. */
  final class AttachmentException(val error: AttachmentError) extends RuntimeException(error.describe)
