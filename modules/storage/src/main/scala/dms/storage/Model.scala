package dms.storage

import zio.Chunk
import zio.stream.ZStream

import java.security.MessageDigest
import java.time.Instant
import java.util.{Base64, HexFormat}

/** Object Lock retention mode (S3 semantics).
  *
  *   - `Compliance`: nobody can delete the version or shorten the retention before `until`.
  *   - `Governance`: callers with the bypass permission can delete the version or shorten the retention.
  */
enum RetentionMode:
  case Compliance, Governance

final case class Retention(mode: RetentionMode, until: Instant)

/** A SHA-256 digest, kept as lowercase hex. */
final case class Sha256 private (hex: String):
  def bytes: Array[Byte]  = HexFormat.of().parseHex(hex)
  def base64: String      = Base64.getEncoder.encodeToString(bytes)
  override def toString() = s"sha256:$hex"

object Sha256:
  def of(data: Chunk[Byte]): Sha256 = fromBytes(MessageDigest.getInstance("SHA-256").digest(data.toArray))

  def fromBytes(digest: Array[Byte]): Sha256 =
    require(digest.length == 32, s"SHA-256 digest must be 32 bytes, got ${digest.length}")
    Sha256(HexFormat.of().formatHex(digest))

  def fromHex(hex: String): Either[String, Sha256] =
    if hex.matches("[0-9a-fA-F]{64}") then Right(Sha256(hex.toLowerCase))
    else Left(s"not a SHA-256 hex: $hex")

  def fromBase64(value: String): Either[String, Sha256] =
    try
      val bytes = Base64.getDecoder.decode(value)
      if bytes.length == 32 then Right(fromBytes(bytes)) else Left(s"not a SHA-256 digest: $value")
    catch case _: IllegalArgumentException => Left(s"not base64: $value")

/** Content to upload. A stream needs its exact length up front (S3 PutObject without multipart). */
enum ObjectContent:
  case Bytes(data: Chunk[Byte])
  case Stream(data: ZStream[Any, Throwable, Byte], length: Long)

/** Identifies one immutable version of an object. */
final case class ObjectVersion(key: String, versionId: String)

/** Result of a successful upload. `sha256` was computed by us and confirmed by the server. */
final case class PutResult(version: ObjectVersion, sha256: Sha256, size: Long)

final case class ObjectInfo(
    version: ObjectVersion,
    size: Long,
    contentType: Option[String],
    lastModified: Instant,
    sha256: Option[Sha256],
    retention: Option[Retention],
    legalHold: Boolean
)
