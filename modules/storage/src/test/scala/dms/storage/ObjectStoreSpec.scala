package dms.storage

import com.dimafeng.testcontainers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import software.amazon.awssdk.services.s3.S3Client
import zio.*
import zio.stream.ZStream
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Integration tests against SeaweedFS (ADR 0016) started by Testcontainers. */
object ObjectStoreSpec extends ZIOSpecDefault:

  /** Same image as compose.yaml. */
  val image =
    "chrislusf/seaweedfs:4.51@sha256:f7a49e3f3972ebed5be2ed4befa4a0c68eb7bdddba6e692d42729ceb545134b8"

  private val accessKey = "dms-test"
  private val secretKey = "dms-test-secret"

  private val container: ZLayer[Any, Throwable, GenericContainer] = ZLayer.scoped(
    ZIO.acquireRelease(ZIO.attemptBlocking {
      val c = GenericContainer(
        dockerImage = image,
        exposedPorts = Seq(8333),
        env = Map("AWS_ACCESS_KEY_ID" -> accessKey, "AWS_SECRET_ACCESS_KEY" -> secretKey),
        command = Seq("mini", "-dir=/data"),
        waitStrategy = Wait.forLogMessage(".*All enabled components are running.*", 1)
      )
      c.start()
      c
    })(c => ZIO.attemptBlocking(c.stop()).orDie)
  )

  private val config: ZLayer[GenericContainer, Nothing, StorageConfig] =
    ZLayer.fromFunction((c: GenericContainer) =>
      StorageConfig(
        endpoint = s"http://${c.host}:${c.mappedPort(8333)}",
        region = "us-east-1",
        bucket = "dms-test",
        accessKey = accessKey,
        secretKey = secretKey
      )
    )

  private type Env = ObjectStore & S3Client & StorageConfig

  /** One container for the whole suite; the bucket is created with Object Lock by
    * [[ObjectStore.bucketSetup]].
    */
  private val env: ZLayer[Any, Throwable, Env] =
    (container >>> config >>> (ObjectStore.client ++ ZLayer.service[StorageConfig])) >+> ZLayer.fromZIO(
      for
        _      <- ObjectStore.bucketSetup.mapError(e => RuntimeException(s"bucket setup failed: $e"))
        s3     <- ZIO.service[S3Client]
        bucket <- ZIO.serviceWith[StorageConfig](_.bucket)
      yield S3ObjectStore(s3, bucket): ObjectStore
    )

  private def newKey: UIO[String] = ZIO.succeed(s"test/${UUID.randomUUID()}.pdf")

  private def bytes(text: String): ObjectContent = ObjectContent.Bytes(Chunk.fromArray(text.getBytes(UTF_8)))

  private def read(key: String, versionId: String): ZIO[ObjectStore, StorageError, String] =
    ZStream
      .serviceWithStream[ObjectStore](_.get(key, versionId))
      .runCollect
      .map(c => String(c.toArray, UTF_8))

  private def inOneDay: UIO[Instant] =
    Clock.instant.map(_.plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS))

  def spec = suite("ObjectStore on SeaweedFS")(
    test("put and get roundtrip with SHA-256 confirmed by the server") {
      for
        s      <- ZIO.service[ObjectStore]
        key    <- newKey
        result <- s.put(key, bytes("rendition v1"), "application/pdf")
        text   <- read(key, result.version.versionId)
        info   <- s.head(key, Some(result.version.versionId))
      yield assertTrue(
        text == "rendition v1",
        result.sha256 == Sha256.of(Chunk.fromArray("rendition v1".getBytes(UTF_8))),
        info.sha256.contains(result.sha256),
        info.size == 12L,
        info.contentType.contains("application/pdf")
      )
    },
    test("a streamed upload computes and confirms the SHA-256") {
      val data = Chunk.fromArray(Array.tabulate[Byte](300 * 1024)(i => (i % 251).toByte))
      for
        s      <- ZIO.service[ObjectStore]
        key    <- newKey
        result <- s.put(
          key,
          ObjectContent.Stream(ZStream.fromChunk(data).rechunk(8192), data.length.toLong),
          "application/pdf"
        )
        back <- s.get(key, result.version.versionId).runCollect
      yield assertTrue(result.sha256 == Sha256.of(data), back == data)
    },
    test("an upload with a wrong expected SHA-256 is rejected and stores nothing") {
      val wrong = Sha256.of(Chunk.fromArray("something else".getBytes(UTF_8)))
      for
        s      <- ZIO.service[ObjectStore]
        key    <- newKey
        bytesR <- s.put(key, bytes("content"), "text/plain", expectedSha256 = Some(wrong)).either
        stream <- s
          .put(
            key,
            ObjectContent.Stream(ZStream.fromChunk(Chunk.fromArray("content".getBytes(UTF_8))), 7L),
            "text/plain",
            expectedSha256 = Some(wrong)
          )
          .either
        head <- s.head(key, None).either
      yield assertTrue(
        bytesR.left.exists(_.isInstanceOf[StorageError.ChecksumMismatch]),
        stream.left.exists(_.isInstanceOf[StorageError.ChecksumMismatch]),
        head == Left(StorageError.NotFound(key, None))
      )
    },
    test("each put creates a new version with a distinct id") {
      for
        s    <- ZIO.service[ObjectStore]
        key  <- newKey
        v1   <- s.put(key, bytes("one"), "text/plain")
        v2   <- s.put(key, bytes("two"), "text/plain")
        old  <- read(key, v1.version.versionId)
        last <- s.head(key, None)
      yield assertTrue(v1.version.versionId != v2.version.versionId, old == "one", last.version == v2.version)
    },
    test("compliance retention blocks delete and overwrite of the locked version") {
      for
        s      <- ZIO.service[ObjectStore]
        key    <- newKey
        until  <- inOneDay
        locked <- s.put(
          key,
          bytes("archived"),
          "application/pdf",
          retention = Some(Retention(RetentionMode.Compliance, until))
        )
        delete <- s.delete(key, locked.version.versionId).either
        bypass <- s.delete(key, locked.version.versionId, bypassGovernance = true).either
        newer  <- s.put(key, bytes("overwrite attempt"), "application/pdf")
        stays  <- read(key, locked.version.versionId)
        info   <- s.head(key, Some(locked.version.versionId))
      yield assertTrue(
        delete == Left(
          StorageError.Locked(
            key,
            locked.version.versionId,
            Some(Retention(RetentionMode.Compliance, until)),
            false
          )
        ),
        bypass.left.exists(_.isInstanceOf[StorageError.Locked]),
        newer.version.versionId != locked.version.versionId,
        stays == "archived",
        info.retention.contains(Retention(RetentionMode.Compliance, until)),
        !info.legalHold
      )
    },
    test("compliance retention can be extended but not shortened") {
      for
        s     <- ZIO.service[ObjectStore]
        key   <- newKey
        until <- inOneDay
        v     <- s.put(
          key,
          bytes("archived"),
          "application/pdf",
          retention = Some(Retention(RetentionMode.Compliance, until))
        )
        id = v.version.versionId
        shorter <- s
          .extendRetention(key, id, Retention(RetentionMode.Compliance, until.minus(1, ChronoUnit.HOURS)))
          .either
        weaker <- s
          .extendRetention(key, id, Retention(RetentionMode.Governance, until.plus(1, ChronoUnit.DAYS)))
          .either
        later = until.plus(1, ChronoUnit.DAYS)
        _    <- s.extendRetention(key, id, Retention(RetentionMode.Compliance, later))
        info <- s.head(key, Some(id))
      yield assertTrue(
        shorter.left.exists(_.isInstanceOf[StorageError.InvalidRequest]),
        weaker.left.exists(_.isInstanceOf[StorageError.InvalidRequest]),
        info.retention.contains(Retention(RetentionMode.Compliance, later))
      )
    },
    test("governance retention blocks delete unless the bypass header is sent") {
      for
        s     <- ZIO.service[ObjectStore]
        key   <- newKey
        until <- inOneDay
        v     <- s.put(
          key,
          bytes("draft"),
          "application/pdf",
          retention = Some(Retention(RetentionMode.Governance, until))
        )
        blocked <- s.delete(key, v.version.versionId).either
        _       <- s.delete(key, v.version.versionId, bypassGovernance = true)
        gone    <- s.head(key, Some(v.version.versionId)).either
      yield assertTrue(
        blocked.left.exists(_.isInstanceOf[StorageError.Locked]),
        gone.left.exists(_.isInstanceOf[StorageError.NotFound])
      )
    },
    test("a legal hold blocks delete until it is released") {
      for
        s   <- ZIO.service[ObjectStore]
        key <- newKey
        v   <- s.put(key, bytes("evidence"), "application/pdf", legalHold = true)
        id = v.version.versionId
        held    <- s.head(key, Some(id))
        blocked <- s.delete(key, id).either
        _       <- s.setLegalHold(key, id, on = false)
        _       <- s.delete(key, id)
        gone    <- s.head(key, Some(id)).either
      yield assertTrue(
        held.legalHold,
        held.retention.isEmpty,
        blocked == Left(StorageError.Locked(key, id, None, true)),
        gone.left.exists(_.isInstanceOf[StorageError.NotFound])
      )
    },
    test("an unlocked version can be deleted") {
      for
        s    <- ZIO.service[ObjectStore]
        key  <- newKey
        v    <- s.put(key, bytes("temp"), "text/plain")
        _    <- s.delete(key, v.version.versionId)
        gone <- s.head(key, Some(v.version.versionId)).either
      yield assertTrue(gone.left.exists(_.isInstanceOf[StorageError.NotFound]))
    },
    test("bucket setup is idempotent") {
      for _ <- ObjectStore.bucketSetup
      yield assertCompletes
    },
    test("keys are listed by prefix and versions of one key newest first") {
      for
        s      <- ZIO.service[ObjectStore]
        prefix <- ZIO.succeed(s"list/${UUID.randomUUID()}/")
        _      <- s.put(s"${prefix}b", bytes("b"), "text/plain")
        first  <- s.put(s"${prefix}a", bytes("a1"), "text/plain")
        second <- s.put(s"${prefix}a", bytes("a2"), "text/plain")
        _      <- s.put(s"${prefix}ab", bytes("ab"), "text/plain")
        keys   <- s.listKeys(prefix)
        all    <- s.versions(s"${prefix}a")
      yield assertTrue(
        keys == List(s"${prefix}a", s"${prefix}ab", s"${prefix}b"),
        all.map(_.versionId) == List(second.version.versionId, first.version.versionId)
      )
    }
  ).provideShared(env) @@ TestAspect.sequential @@ TestAspect.withLiveClock
