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

  /** A report of format 1 as it was stored: no names, no prefix. */
  private val stored = EvidenceReportPayload(
    "dms.evidence-report/1",
    UUID.randomUUID(),
    at,
    "auditor-1",
    UUID.randomUUID(),
    EvidenceDocument(
      UUID.randomUUID(),
      "Policy",
      List("policy"),
      "author-1",
      Some("owner-1"),
      at,
      UUID.randomUUID(),
      1
    ),
    Nil,
    List(
      EvidenceLegalHold(UUID.randomUUID(), "Hold", None, "Claim", "records-1", at, None, None, None)
    ),
    List(
      EvidenceAuditEntry(
        1,
        at,
        "system:review",
        "technical",
        Some("deputy-1"),
        "x",
        None,
        None,
        "api",
        Json.Obj(),
        "h"
      )
    ),
    EvidenceIntegrity(
      "Intact",
      at,
      EvidenceChain(true, 7, Some(7), Some("h"), 0, None, 1, true, None, None, false, None, false, None),
      Nil,
      Nil
    ),
    List(EvidenceReport.PersonNote)
  )

  def spec = suite("Evidence report")(
    test("a report of format 1 still parses and prints the user ids") {
      val text = EvidenceReport.canonical(stored)
      val html = EvidenceReportLayout.html(EvidenceReport.parse(text).toOption.get, "a" * 64)
      assertTrue(
        !text.contains("persons"),
        !text.contains("trustedPrefix"),
        html.contains("by auditor-1"),
        html.contains("author-1 / owner-1"),
        !html.contains("<h2>Persons</h2>"),
        !html.contains("Verified prefix")
      )
    },
    test("the editors of a version are persons of the report, with their names (ADR 0078)") {
      val version = EvidenceVersion(
        UUID.randomUUID(),
        "1.0",
        "Published",
        "Major",
        None,
        "author-1",
        at,
        None,
        None,
        None,
        None,
        None,
        None,
        "b" * 64,
        None,
        Nil,
        Nil,
        requiresAcknowledgement = false,
        None,
        Nil,
        editors = Some(List("author-1", "editor-1"))
      )
      val changed = stored.copy(versions = List(version))
      val html    = EvidenceReportLayout.html(
        EvidenceReport.named(changed, Map("editor-1" -> "Eva Editor")),
        "a" * 64
      )
      assertTrue(
        EvidenceReport.personIds(changed) == EvidenceReport.personIds(stored) + "editor-1",
        html.contains("author-1, Eva Editor"),
        html.contains("<td>Eva Editor</td><td>editor-1</td>")
      )
    },
    test(
      "names are recorded in the report and printed instead of the ids, which stay in a table (ADR 0077)"
    ) {
      val names = Map(
        "auditor-1" -> "Anna Auditor",
        "author-1"  -> "Sam Gleich",
        "owner-1"   -> "Sam Gleich",
        "<b>x</b>"  -> "<b>Name</b>"
      )
      val report = EvidenceReport.named(stored, names)
      val html   = EvidenceReportLayout.html(report, "a" * 64)
      val json   = EvidenceReport.canonical(report)
      assertTrue(
        EvidenceReport.personIds(stored) ==
          Set("auditor-1", "author-1", "owner-1", "records-1", "system:review", "deputy-1"),
        EvidenceReportLayout.footer(report, "a" * 64).contains("by Anna Auditor"),
        html.contains("by Anna Auditor"),
        // two people with one name stay apart
        html.contains("Sam Gleich (author-1) / Sam Gleich (owner-1)"),
        // without a name the id stays, and a name is no way into the markup
        html.contains("by records-1"),
        html.contains("<h2>Persons</h2>"),
        html.contains("<td>Anna Auditor</td><td>auditor-1</td>"),
        !html.contains("<b>Name</b>"),
        report.notes.contains(EvidenceReport.NamedPersonNote),
        !report.notes.contains(EvidenceReport.PersonNote),
        // the checksum covers the names, and the JSON keeps the ids
        json.contains(""""createdBy":"auditor-1""""),
        json.contains(""""persons":{"""),
        EvidenceReport.parse(json).toOption.flatMap(_.persons).contains(names),
        EvidenceReport.named(stored, Map.empty) == stored
      )
    },
    test("a report states what it took from the last full verification") {
      val verified = java.time.Instant.parse("2026-09-30T03:00:00Z")
      val chain    = stored.integrity.auditChain
        .copy(trustedPrefix = Some(EvidenceTrustedPrefix(5, "f" * 64, verified)), entriesChecked = 2)
      val report = stored.copy(integrity = stored.integrity.copy(auditChain = chain))
      val html   = EvidenceReportLayout.html(report, "a" * 64)
      assertTrue(
        html.contains("intact, 2 entries after position 5 verified"),
        html.contains("Verified prefix"),
        html.contains("f" * 64),
        html.contains("2026-09-30 03:00:00 UTC")
      )
    },
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
    test("a review task opened again names the decided task (DMS-148)") {
      val day     = java.time.LocalDate.of(2027, 1, 1)
      val decided = EvidenceReview(
        UUID.randomUUID(),
        day,
        Instant.EPOCH,
        "Revised",
        Some(Instant.parse("2026-11-10T09:00:00Z")),
        Some("owner-1"),
        None,
        None,
        None
      )
      val reopened = decided.copy(
        taskId = UUID.randomUUID(),
        status = "Open",
        decidedAt = None,
        decidedBy = None,
        reopens = Some(decided.taskId)
      )
      val all = List(decided, reopened)
      assertTrue(
        EvidenceReportLayout.reviewStatus(decided, all) == "Revised",
        EvidenceReportLayout
          .reviewStatus(reopened, all)
          .startsWith("Open (reopens the task decided 2026-11-10"),
        EvidenceReportLayout.reviewStatus(reopened, Nil) == "Open"
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
