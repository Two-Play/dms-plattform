package dms.storage

import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.checksums.{RequestChecksumCalculation, ResponseChecksumValidation}
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.*
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}
import zio.*
import zio.stream.ZStream

import java.net.URI

/** Object storage with Object Lock (ADR 0009, 0016), coded against the plain S3 API.
  *
  * All objects live in one bucket with Object Lock enabled, so every write creates a new version with its own
  * id. Callers keep `(key, versionId)` as the stable reference; a version is never modified in place.
  */
trait ObjectStore:

  /** Uploads a new version of `key`.
    *
    * The SHA-256 is computed while uploading and sent as S3 additional checksum (`x-amz-checksum-sha256`);
    * the server verifies it and echoes it back, and the upload fails with [[StorageError.ChecksumMismatch]]
    * if the server does not confirm it or if it differs from `expectedSha256`.
    */
  def put(
      key: String,
      content: ObjectContent,
      contentType: String,
      retention: Option[Retention] = None,
      legalHold: Boolean = false,
      expectedSha256: Option[Sha256] = None
  ): IO[StorageError, PutResult]

  /** Reads a version. The SDK validates the stored SHA-256 while the stream is consumed. */
  def get(key: String, versionId: String): ZStream[Any, StorageError, Byte]

  /** Metadata and lock state of a version, or of the latest version if `versionId` is empty. */
  def head(key: String, versionId: Option[String]): IO[StorageError, ObjectInfo]

  def setLegalHold(key: String, versionId: String, on: Boolean): IO[StorageError, Unit]

  /** Sets a retention that ends no earlier than the current one. Shortening is rejected with
    * [[StorageError.InvalidRequest]] before the request is sent; switching from compliance to governance is
    * rejected the same way.
    */
  def extendRetention(key: String, versionId: String, retention: Retention): IO[StorageError, Unit]

  /** Permanently deletes a version. Fails with [[StorageError.Locked]] while retention or a legal hold
    * protects it. `bypassGovernance` sends `x-amz-bypass-governance-retention` (needs the matching permission
    * and has no effect on compliance retention or legal holds).
    */
  def delete(key: String, versionId: String, bypassGovernance: Boolean = false): IO[StorageError, Unit]

object ObjectStore:

  /** S3 client for the configured endpoint: path-style, static credentials, JDK URL connection HTTP client.
    * Checksums are calculated only where an operation requires them or where we request one explicitly.
    */
  val client: ZLayer[StorageConfig, Throwable, S3Client] = ZLayer.scoped(
    ZIO.serviceWithZIO[StorageConfig] { config =>
      ZIO.fromAutoCloseable(ZIO.attempt {
        S3Client
          .builder()
          .endpointOverride(URI.create(config.endpoint))
          .region(Region.of(config.region))
          .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKey, config.secretKey))
          )
          .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
          .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
          .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
          .httpClient(UrlConnectionHttpClient.builder().build())
          .build()
      })
    }
  )

  val live: ZLayer[S3Client & StorageConfig, Nothing, ObjectStore] =
    ZLayer.fromFunction((s3: S3Client, config: StorageConfig) => S3ObjectStore(s3, config.bucket))

  /** Creates the configured bucket with Object Lock enabled if it does not exist. For local development and
    * tests only: production buckets are provisioned by operations (ADR 0016). Fails if an existing bucket has
    * Object Lock disabled.
    */
  val bucketSetup: ZIO[S3Client & StorageConfig, StorageError, Unit] =
    for
      s3     <- ZIO.service[S3Client]
      bucket <- ZIO.serviceWith[StorageConfig](_.bucket)
      exists <- ZIO
        .attemptBlocking(s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build()))
        .as(true)
        .catchSome { case _: NoSuchBucketException => ZIO.succeed(false) }
        .catchSome { case e: S3Exception if e.statusCode() == 404 => ZIO.succeed(false) }
        .mapError(StorageError.from(bucket, None))
      _ <- ZIO
        .attemptBlocking(
          s3.createBucket(
            CreateBucketRequest.builder().bucket(bucket).objectLockEnabledForBucket(true).build()
          )
        )
        .mapError(StorageError.from(bucket, None))
        .unless(exists)
      lock <- ZIO
        .attemptBlocking(
          s3.getObjectLockConfiguration(GetObjectLockConfigurationRequest.builder().bucket(bucket).build())
        )
        .mapError(StorageError.from(bucket, None))
      enabled = Option(lock.objectLockConfiguration())
        .exists(_.objectLockEnabled() == ObjectLockEnabled.ENABLED)
      _ <- ZIO.fail(StorageError.InvalidRequest(s"bucket $bucket exists without Object Lock")).unless(enabled)
    yield ()
