package dms.rendering.core

import dms.documents.Rendition
import dms.kernel.{DocumentId, TenantId}
import dms.storage.{ObjectContent, ObjectStore, Sha256, StorageError}
import zio.*

import java.util.UUID

/** Where renditions are kept: object storage with Object Lock (ADR 0016). A stored rendition is referenced by
  * key, object version and SHA-256 ([[Rendition]], ADR 0021).
  */
trait RenditionArchive:

  /** Stores `pdf` under `key`. Idempotent: if the latest version of `key` already has the same SHA-256 (a
    * repeated job; rendering is deterministic), that version is returned instead of writing a new one.
    */
  def store(key: String, pdf: Chunk[Byte]): IO[RenderingError, Rendition]

  /** Reads a rendition and checks it against its SHA-256 ("checksum comparison", business concept 5.1). */
  def read(rendition: Rendition): IO[RenderingError, Chunk[Byte]]

object RenditionArchive:

  /** `renditions/<tenant>/<document>/<version>.pdf` */
  def key(tenant: TenantId, documentId: DocumentId, versionId: UUID): String =
    s"renditions/${tenant.value}/${documentId.value}/$versionId.pdf"

  def sha256(pdf: Chunk[Byte]): String = Sha256.of(pdf).hex

  /** The object store of the platform (ADR 0016). */
  def objectStore(objects: ObjectStore): RenditionArchive = new RenditionArchive:

    def store(key: String, pdf: Chunk[Byte]): IO[RenderingError, Rendition] =
      val checksum = Sha256.of(pdf)
      objects
        .head(key, None)
        .map(Some(_))
        .catchSome { case StorageError.NotFound(_, _) => ZIO.none }
        .flatMap {
          case Some(info) if info.sha256.contains(checksum) =>
            ZIO.succeed(Rendition(key, Some(info.version.versionId), checksum.hex))
          case _ =>
            objects
              .put(key, ObjectContent.Bytes(pdf), "application/pdf", expectedSha256 = Some(checksum))
              .map(r => Rendition(key, Some(r.version.versionId), r.sha256.hex))
        }
        .mapError(storageError)

    def read(rendition: Rendition): IO[RenderingError, Chunk[Byte]] =
      for
        versionId <- ZIO
          .fromOption(rendition.objectVersion)
          .orElseFail(RenderingError.Integrity(s"rendition ${rendition.objectKey} has no object version"))
        bytes <- objects.get(rendition.objectKey, versionId).runCollect.mapError(storageError)
        _     <- verify(rendition, bytes)
      yield bytes

  /** In memory, for tests and local runs without object storage. */
  def inMemory: UIO[RenditionArchive] =
    Ref.make(Map.empty[(String, String), Chunk[Byte]]).map { objects =>
      new RenditionArchive:
        def store(key: String, pdf: Chunk[Byte]): IO[RenderingError, Rendition] =
          val checksum = sha256(pdf)
          objects.modify { all =>
            val versionId = checksum.take(16)
            (Rendition(key, Some(versionId), checksum), all + ((key, versionId) -> pdf))
          }

        def read(rendition: Rendition): IO[RenderingError, Chunk[Byte]] =
          objects.get
            .map(_.get((rendition.objectKey, rendition.objectVersion.getOrElse(""))))
            .someOrFail(RenderingError.Integrity(s"rendition ${rendition.objectKey} does not exist"))
            .tap(verify(rendition, _))
    }

  private def verify(rendition: Rendition, bytes: Chunk[Byte]): IO[RenderingError, Unit] =
    val actual = sha256(bytes)
    ZIO
      .unless(actual == rendition.sha256)(
        ZIO.fail(
          RenderingError.Integrity(
            s"rendition ${rendition.objectKey} has SHA-256 $actual, expected ${rendition.sha256}"
          )
        )
      )
      .unit

  private def storageError(e: StorageError): RenderingError = e match
    case StorageError.ChecksumMismatch(key, expected, actual) =>
      RenderingError.Integrity(s"object $key: expected $expected, got ${actual.getOrElse("no checksum")}")
    case StorageError.NotFound(key, version) =>
      RenderingError.Integrity(s"object $key${version.fold("")(v => s" version $v")} does not exist")
    case other => RenderingError.Unavailable(s"object storage: $other")
