package dms.app

import dms.documents.Acknowledgement
import dms.kernel.{DocumentId, UserId}
import zio.json.ast.Json
import zio.test.*

import java.time.Instant
import java.util.UUID

/** The pure parts of evidence reports (DMS-57): canonical JSON, checksum and acknowledgement rate. */
object EvidenceReportSpec extends ZIOSpecDefault:

  private val at = Instant.parse("2026-10-01T08:00:00Z")

  private def ack(user: String) =
    Acknowledgement(
      UUID.randomUUID(),
      DocumentId(UUID.randomUUID()),
      UUID.randomUUID(),
      "1.0",
      UserId(user),
      at,
      at
    )

  def spec = suite("Evidence report")(
    test("canonical JSON sorts keys at every level, drops nulls and has no whitespace") {
      val json = Json.Obj(
        "b" -> Json.Arr(Json.Obj("z" -> Json.Num(1), "a" -> Json.Str("x y"))),
        "a" -> Json.Null,
        "Ä" -> Json.Bool(true),
        "A" -> Json.Str("\"quoted\"")
      )
      assertTrue(
        EvidenceReport.canonical(json) == """{"A":"\"quoted\"","b":[{"a":"x y","z":1}],"Ä":true}"""
      )
    },
    test("the checksum is the SHA-256 of the UTF-8 bytes") {
      assertTrue(
        EvidenceReport.sha256("abc") == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        EvidenceReport.sha256("ä") != EvidenceReport.sha256("a")
      )
    },
    test("the acknowledgement rate counts acknowledgements of the audience only") {
      val rate = EvidenceReport.acknowledgements(
        List(ack("a"), ack("b"), ack("former-reader")),
        Set("a", "b", "c"),
        at
      )
      val empty = EvidenceReport.acknowledgements(Nil, Set.empty, at)
      assertTrue(
        rate.audience == 3,
        rate.acknowledged == 2,
        rate.acknowledgedTotal == 3,
        rate.ratePercent.contains("66.7"),
        empty.ratePercent.isEmpty
      )
    }
  )
