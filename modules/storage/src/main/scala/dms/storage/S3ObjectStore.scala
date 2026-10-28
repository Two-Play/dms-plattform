package dms.storage

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.ContentStreamProvider
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{ObjectVersion as _, *}
import zio.*
import zio.stream.ZStream

import java.io.InputStream
import java.security.{DigestInputStream, MessageDigest}
import java.util.concurrent.atomic.AtomicBoolean

/** [[ObjectStore]] on the AWS SDK v2 synchronous S3 client. Blocking calls run on the blocking pool. */
final class S3ObjectStore(s3: S3Client, bucket: String) extends ObjectStore:
  import S3ObjectStore.*

  def put(
      key: String,
      content: ObjectContent,
      contentType: String,
      retention: Option[Retention],
      legalHold: Boolean,
      expectedSha256: Option[Sha256]
  ): IO[StorageError, PutResult] =
    content match
      case ObjectContent.Bytes(data) =>
        val sha = Sha256.of(data)
        for
          _ <- ZIO
            .fail(StorageError.ChecksumMismatch(key, expectedSha256.getOrElse(sha), Some(sha)))
            .when(expectedSha256.exists(_ != sha))
          response <- send(
            key,
            RequestBody.fromBytes(data.toArray),
            contentType,
            retention,
            legalHold,
            Some(sha)
          )
          result <- confirm(key, sha, data.length.toLong, response)
        yield result

      case ObjectContent.Stream(data, length) =>
        // With a known SHA-256 the server verifies it from the header. Otherwise the SDK computes it while
        // sending and transmits it as a trailing checksum; we compute it in parallel to compare.
        ZIO.scoped {
          for
            in <- data.toInputStream.mapError(StorageError.Unexpected(_))
            digest = MessageDigest.getInstance("SHA-256")
            body   = RequestBody.fromContentProvider(
              oneShot(new DigestInputStream(in, digest)),
              length,
              contentType
            )
            response <- send(key, body, contentType, retention, legalHold, expectedSha256)
            sha = Sha256.fromBytes(digest.digest())
            result <- confirm(key, expectedSha256.getOrElse(sha), length, response)
          yield result
        }

  def get(key: String, versionId: String): ZStream[Any, StorageError, Byte] =
    val request =
      GetObjectRequest
        .builder()
        .bucket(bucket)
        .key(key)
        .versionId(versionId)
        .checksumMode(ChecksumMode.ENABLED)
    val onError = StorageError.from(key, Some(versionId))
    ZStream
      .scoped(ZIO.fromAutoCloseable(ZIO.attemptBlocking(s3.getObject(request.build()))).mapError(onError))
      .flatMap(in => readAll(in, onError))

  def head(key: String, versionId: Option[String]): IO[StorageError, ObjectInfo] =
    val request = HeadObjectRequest.builder().bucket(bucket).key(key).checksumMode(ChecksumMode.ENABLED)
    versionId.foreach(request.versionId)
    ZIO
      .attemptBlocking(s3.headObject(request.build()))
      .mapError(StorageError.from(key, versionId))
      .map { r =>
        ObjectInfo(
          version = ObjectVersion(key, r.versionId()),
          size = r.contentLength(),
          contentType = Option(r.contentType()),
          lastModified = r.lastModified(),
          sha256 = Option(r.checksumSHA256()).flatMap(Sha256.fromBase64(_).toOption),
          retention = for
            mode  <- Option(r.objectLockMode()).flatMap(fromSdk)
            until <- Option(r.objectLockRetainUntilDate())
          yield Retention(mode, until),
          legalHold = r.objectLockLegalHoldStatus() == ObjectLockLegalHoldStatus.ON
        )
      }

  def setLegalHold(key: String, versionId: String, on: Boolean): IO[StorageError, Unit] =
    val status = if on then ObjectLockLegalHoldStatus.ON else ObjectLockLegalHoldStatus.OFF
    ZIO
      .attemptBlocking(
        s3.putObjectLegalHold(
          PutObjectLegalHoldRequest
            .builder()
            .bucket(bucket)
            .key(key)
            .versionId(versionId)
            .legalHold(ObjectLockLegalHold.builder().status(status).build())
            .build()
        )
      )
      .mapError(StorageError.from(key, Some(versionId)))
      .unit

  def extendRetention(key: String, versionId: String, retention: Retention): IO[StorageError, Unit] =
    for
      now     <- Clock.instant
      current <- head(key, Some(versionId)).map(_.retention.filter(_.until.isAfter(now)))
      _       <- current match
        case Some(c) if retention.until.isBefore(c.until) =>
          ZIO.fail(StorageError.InvalidRequest(s"retention of $key/$versionId would end before ${c.until}"))
        case Some(c) if c.mode == RetentionMode.Compliance && retention.mode == RetentionMode.Governance =>
          ZIO.fail(StorageError.InvalidRequest(s"compliance retention of $key/$versionId cannot be weakened"))
        case _ => ZIO.unit
      _ <- ZIO
        .attemptBlocking(
          s3.putObjectRetention(
            PutObjectRetentionRequest
              .builder()
              .bucket(bucket)
              .key(key)
              .versionId(versionId)
              .retention(
                ObjectLockRetention
                  .builder()
                  .mode(retentionMode(retention.mode))
                  .retainUntilDate(retention.until)
                  .build()
              )
              .build()
          )
        )
        .mapError(StorageError.from(key, Some(versionId)))
    yield ()

  def delete(key: String, versionId: String, bypassGovernance: Boolean): IO[StorageError, Unit] =
    val request = DeleteObjectRequest.builder().bucket(bucket).key(key).versionId(versionId)
    if bypassGovernance then request.bypassGovernanceRetention(true): Unit
    ZIO
      .attemptBlocking(s3.deleteObject(request.build()))
      .mapError(StorageError.from(key, Some(versionId)))
      .unit
      .catchSome { case denied: StorageError.AccessDenied => lockedOr(key, versionId, denied) }

  /** S3 reports Object Lock violations as a plain 403. Read the lock state to tell them apart from missing
    * permissions.
    */
  private def lockedOr(key: String, versionId: String, denied: StorageError): IO[StorageError, Unit] =
    for
      now  <- Clock.instant
      info <- head(key, Some(versionId)).orElseFail(denied)
      retention = info.retention.filter(_.until.isAfter(now))
      _ <-
        if retention.isDefined || info.legalHold then
          ZIO.fail(StorageError.Locked(key, versionId, retention, info.legalHold))
        else ZIO.fail(denied)
    yield ()

  private def send(
      key: String,
      body: RequestBody,
      contentType: String,
      retention: Option[Retention],
      legalHold: Boolean,
      sha256: Option[Sha256]
  ): IO[StorageError, PutObjectResponse] =
    val request = PutObjectRequest
      .builder()
      .bucket(bucket)
      .key(key)
      .contentType(contentType)
    sha256 match
      case Some(s) => request.checksumSHA256(s.base64): Unit
      case None    => request.checksumAlgorithm(ChecksumAlgorithm.SHA256): Unit
    retention.foreach(r => request.objectLockMode(toSdk(r.mode)).objectLockRetainUntilDate(r.until))
    if legalHold then request.objectLockLegalHoldStatus(ObjectLockLegalHoldStatus.ON): Unit
    ZIO
      .attemptBlocking(s3.putObject(request.build(), body))
      .mapError {
        case e: S3Exception if checksumErrorCodes.contains(errorCode(e)) =>
          sha256 match
            case Some(expected) => StorageError.ChecksumMismatch(key, expected, None)
            case None           => StorageError.InvalidRequest(s"checksum rejected for $key: ${e.getMessage}")
        case e => StorageError.from(key, None)(e)
      }

  private def confirm(
      key: String,
      sha: Sha256,
      size: Long,
      response: PutObjectResponse
  ): IO[StorageError, PutResult] =
    val confirmed = Option(response.checksumSHA256()).flatMap(Sha256.fromBase64(_).toOption)
    Option(response.versionId()) match
      case None =>
        ZIO.fail(StorageError.Unexpected(IllegalStateException(s"bucket $bucket returned no version id")))
      case Some(_) if !confirmed.contains(sha) => ZIO.fail(StorageError.ChecksumMismatch(key, sha, confirmed))
      case Some(versionId) => ZIO.succeed(PutResult(ObjectVersion(key, versionId), sha, size))

object S3ObjectStore:
  private val checksumErrorCodes = Set("BadDigest", "InvalidDigest", "XAmzContentChecksumMismatch")

  private def errorCode(e: S3Exception): String =
    Option(e.awsErrorDetails()).flatMap(d => Option(d.errorCode())).getOrElse("")

  private def toSdk(mode: RetentionMode): ObjectLockMode = mode match
    case RetentionMode.Compliance => ObjectLockMode.COMPLIANCE
    case RetentionMode.Governance => ObjectLockMode.GOVERNANCE

  private def retentionMode(mode: RetentionMode): ObjectLockRetentionMode = mode match
    case RetentionMode.Compliance => ObjectLockRetentionMode.COMPLIANCE
    case RetentionMode.Governance => ObjectLockRetentionMode.GOVERNANCE

  private def fromSdk(mode: ObjectLockMode): Option[RetentionMode] = mode match
    case ObjectLockMode.COMPLIANCE => Some(RetentionMode.Compliance)
    case ObjectLockMode.GOVERNANCE => Some(RetentionMode.Governance)
    case _                         => None

  /** A stream body can be read once. If the SDK retries after a partial upload, the put fails instead of
    * sending truncated content.
    */
  private def oneShot(in: InputStream): ContentStreamProvider =
    val used = AtomicBoolean(false)
    () =>
      if used.compareAndSet(false, true) then in
      else throw IllegalStateException("stream content cannot be replayed for a retry")

  /** Reads on the blocking pool. Unlike `ZStream.fromInputStream`, unchecked SDK exceptions (e.g. a failed
    * checksum validation at the end of the body) become typed errors instead of defects.
    */
  private def readAll(in: InputStream, onError: Throwable => StorageError): ZStream[Any, StorageError, Byte] =
    ZStream.repeatZIOChunkOption(
      ZIO
        .attemptBlocking {
          val buffer = new Array[Byte](64 * 1024)
          val n      = in.read(buffer)
          if n < 0 then None else Some(Chunk.fromArray(java.util.Arrays.copyOf(buffer, n)))
        }
        .mapError(onError)
        .some
    )
