package dms.documents

import dms.documents.DocumentStatus.*
import dms.documents.metadata.MetadataValue
import dms.kernel.{DocumentId, TemplateId, UserId, VersionNo}
import dms.templates.{Block, Inline, Section, SectionId, TemplateContent}
import zio.test.*

import java.time.{Instant, LocalDate, ZoneId}
import java.util.UUID

object PublicationSpec extends ZIOSpecDefault:

  private val berlin = ZoneId.of("Europe/Berlin")

  private def content(text: String) = TemplateContent(
    List(Section(SectionId("terms"), None, locked = false, List(Block.Paragraph(List(Inline.Text(text))))))
  )

  private val document = DocumentId(UUID.randomUUID())

  private def version(
      number: VersionNo,
      status: DocumentStatus,
      kind: ChangeKind = ChangeKind.Major,
      text: String = "one two three four five six seven eight nine ten",
      scope: String = ""
  ) =
    DocumentVersion(
      UUID.randomUUID(),
      document,
      number,
      1,
      status,
      kind,
      Option.when(kind == ChangeKind.Minor)("Typo"),
      scope,
      1,
      TemplateId(UUID.randomUUID()),
      1,
      content(text),
      Map.empty,
      None,
      None,
      UserId("author"),
      Instant.EPOCH,
      Instant.EPOCH,
      Option.when(Versioning.frozen(status))(UserId("approver")),
      Option.when(Versioning.frozen(status))(Instant.EPOCH)
    )

  def spec = suite("Publication")(
    test("a version becomes valid at 00:00 of its valid-from date in the tenant's time zone") {
      val newYear = LocalDate.of(2027, 1, 1)
      assertTrue(
        Publication.dueAt(newYear, berlin) == Instant.parse("2026-12-31T23:00:00Z"),
        Publication.dueAt(LocalDate.of(2027, 7, 1), berlin) == Instant.parse("2027-06-30T22:00:00Z"),
        Publication.dueAt(newYear, ZoneId.of("America/New_York")) == Instant.parse("2027-01-01T05:00:00Z"),
        !Publication.isDue(Some(newYear), berlin, Instant.parse("2026-12-31T22:59:59Z")),
        Publication.isDue(Some(newYear), berlin, Instant.parse("2026-12-31T23:00:00Z")),
        Publication.isDue(None, berlin, Instant.EPOCH)
      )
    },
    test("where 00:00 does not exist on the date, the first moment of the date counts") {
      // Chile moved to daylight saving time at 00:00 on 2026-09-06: the day starts at 01:00.
      val santiago = ZoneId.of("America/Santiago")
      assertTrue(
        Publication.dueAt(LocalDate.of(2026, 9, 6), santiago) == Instant.parse("2026-09-06T04:00:00Z")
      )
    },
    test("the valid-from date comes from the core field") {
      assertTrue(
        Publication.validFrom(Map("validFrom" -> MetadataValue.Date(LocalDate.of(2027, 1, 1)))) ==
          Some(LocalDate.of(2027, 1, 1)),
        Publication.validFrom(Map("validFrom" -> MetadataValue.Text("2027-01-01"))).isEmpty
      )
    },
    test(
      "readers of a superseded version are pointed to its successor, of a valid one to the scheduled one"
    ) {
      val v1  = version(VersionNo(1, 0), Superseded)
      val v2  = version(VersionNo(2, 0), Published)
      val v3  = version(VersionNo(3, 0), Approved)
      val at  = version(VersionNo(3, 1), Approved, scope = "AT")
      val all = List(v1.copy(supersededBy = Some(v2.id)), v2, v3, at)
      assertTrue(
        Publication.successor(all.head, all) == Some(v2),
        Publication.successor(v2, all) == Some(v3),
        Publication.successor(v3, all).isEmpty,
        Publication.successor(v1, all).isEmpty
      )
    },
    test("the editorial check compares a minor version with the released version it is based on") {
      val v1   = version(VersionNo(1, 0), Superseded)
      val v2   = version(VersionNo(1, 1), Published, ChangeKind.Minor)
      val typo =
        version(VersionNo(1, 2), Draft, ChangeKind.Minor, "one two three four five six seven eight nine 10")
      val major = version(VersionNo(2, 0), Draft, ChangeKind.Major, "completely new text")
      val check = EditorialCheck.of(typo, List(v1, v2, typo), 0.05)
      assertTrue(
        EditorialCheck.basis(typo, List(v1, v2, typo)) == Some(v2),
        check == Some(EditorialCheck("1.1", 0.1, 0.05)),
        check.exists(_.exceedsThreshold),
        !EditorialCheck.of(typo, List(v1, v2, typo), 0.1).exists(_.exceedsThreshold),
        EditorialCheck.of(major, List(v1, v2, major), 0.05).isEmpty,
        EditorialCheck.of(typo, List(typo), 0.05).isEmpty
      )
    }
  )
