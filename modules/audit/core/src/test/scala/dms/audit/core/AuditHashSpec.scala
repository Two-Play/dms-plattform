package dms.audit.core

import zio.test.*

import java.time.Instant
import java.util.UUID

/** Pins hash format 1: an external verifier implementing modules/audit/README.md must get the same values. */
object AuditHashSpec extends ZIOSpecDefault:

  private val genesis = ChainEntry(
    tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
    chainSeq = 1L,
    prevHash = None,
    occurredAt = Instant.parse("2026-10-28T12:00:00.123456Z"),
    actorId = "user-1",
    actorKind = "person",
    onBehalfOf = None,
    action = "document.created",
    objectType = "document",
    objectId = Some(UUID.fromString("00000000-0000-0000-0000-0000000000aa")),
    objectVersion = Some("1.0"),
    details = """{"title": "Travel policy"}""",
    reason = None,
    channel = "ui",
    correlationId = None
  )

  def spec = suite("AuditHash")(
    test("format 1 serializes length-prefixed fields after the version byte") {
      val bytes = AuditHash.serialize(genesis)
      assertTrue(
        bytes(0) == 1.toByte,
        // tenant id: present marker, length 36, UUID text
        bytes.slice(1, 6).toSeq == Seq[Byte](1, 0, 0, 0, 36),
        String(bytes.slice(6, 42), "UTF-8") == "00000000-0000-0000-0000-000000000001",
        // chain_seq: present marker, length 8, int64 big-endian
        bytes.slice(42, 55).toSeq == Seq[Byte](1, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0, 0, 1),
        // prev_hash of the genesis entry is NULL
        bytes(55) == 0.toByte
      )
    },
    test("format 1 hash of a known entry matches an independent implementation") {
      // Computed with the Python reference verifier from modules/audit/README.md.
      assertTrue(
        AuditHash.hex(
          AuditHash.hash(genesis)
        ) == "989b56771ad9c9052f939a2bbb39acdc88ea992257e60a4db6c924d80bf6ac42"
      )
    },
    test("timestamps are hashed with exactly six fraction digits") {
      assertTrue(
        AuditHash.formatTimestamp(Instant.parse("2026-01-02T03:04:05Z")) == "2026-01-02T03:04:05.000000Z"
      )
    },
    test("a NULL field and an empty text differ") {
      val withEmpty = genesis.copy(reason = Some(""))
      assertTrue(!AuditHash.hash(genesis).sameElements(AuditHash.hash(withEmpty)))
    }
  )
