package dms.audit.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import java.util.{HexFormat, UUID}

/** The hashed content of one chain entry, exactly as stored in `dms.audit_event`.
  *
  * @param details
  *   the PostgreSQL text form of the `jsonb` value (`details::text`), not the caller's JSON text
  */
final case class ChainEntry(
    tenantId: UUID,
    chainSeq: Long,
    prevHash: Option[Array[Byte]],
    occurredAt: Instant,
    actorId: String,
    actorKind: String,
    onBehalfOf: Option[String],
    action: String,
    objectType: String,
    objectId: Option[UUID],
    objectVersion: Option[String],
    details: String,
    reason: Option[String],
    channel: String,
    correlationId: Option[UUID]
)

/** Hash format of the audit chain. Documented for external verifiers in `modules/audit/README.md`.
  *
  * Format 1: `SHA-256(0x01 || field_1 || ... || field_15)`. Each field is `0x00` for NULL, otherwise `0x01`,
  * the byte length as unsigned 32-bit big-endian integer and the bytes.
  */
object AuditHash:
  val CurrentFormat: Int = 1
  val Length: Int        = 32

  private val timestampFormat =
    DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)

  /** Timestamp as hashed: UTC with exactly six fraction digits (PostgreSQL's microsecond precision). */
  def formatTimestamp(instant: Instant): String = timestampFormat.format(instant)

  def hash(entry: ChainEntry): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(serialize(entry))

  /** The canonical byte sequence of format 1 that is hashed. */
  def serialize(entry: ChainEntry): Array[Byte] =
    val out                                     = ByteArrayOutputStream()
    def field(value: Option[Array[Byte]]): Unit = value match
      case None        => out.write(0)
      case Some(bytes) =>
        out.write(1)
        out.write(ByteBuffer.allocate(4).putInt(bytes.length).array())
        out.write(bytes)
    def text(value: String): Option[Array[Byte]] = Some(value.getBytes(UTF_8))
    def uuid(value: UUID): Option[Array[Byte]]   = text(value.toString)

    out.write(CurrentFormat)
    field(uuid(entry.tenantId))
    field(Some(ByteBuffer.allocate(8).putLong(entry.chainSeq).array()))
    field(entry.prevHash)
    field(text(formatTimestamp(entry.occurredAt)))
    field(text(entry.actorId))
    field(text(entry.actorKind))
    field(entry.onBehalfOf.flatMap(text))
    field(text(entry.action))
    field(text(entry.objectType))
    field(entry.objectId.flatMap(uuid))
    field(entry.objectVersion.flatMap(text))
    field(text(entry.details))
    field(entry.reason.flatMap(text))
    field(text(entry.channel))
    field(entry.correlationId.flatMap(uuid))
    out.toByteArray

  def hex(bytes: Array[Byte]): String = HexFormat.of().formatHex(bytes)
