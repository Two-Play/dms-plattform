package dms.app

import dms.app.ComplianceCockpit.{Entry, Rate}
import dms.documents.metadata.{DocumentClassId, MetadataValue}
import dms.documents.{Document, DocumentStatus, FieldEntry, ValueOrigin}
import dms.documents.core.CurrentVersion
import dms.kernel.{DocumentId, TemplateId, UserId, VersionNo}
import zio.test.*

import java.time.{Instant, LocalDate}
import java.util.UUID

/** Pure rules of the compliance cockpit (DMS-59): when a review is overdue, filters and grouping. */
object ComplianceCockpitSpec extends ZIOSpecDefault:

  private val today = LocalDate.of(2026, 11, 2)

  private def version(
      title: String,
      unit: Option[String],
      owner: Option[String],
      dueOn: Option[LocalDate],
      status: DocumentStatus = DocumentStatus.Published
  ) =
    CurrentVersion(
      Document(
        DocumentId.random(),
        DocumentClassId(UUID.randomUUID()),
        1,
        title,
        owner.map(UserId(_)),
        UserId("author"),
        TemplateId.random(),
        1,
        Instant.EPOCH
      ),
      List("policy"),
      UUID.randomUUID(),
      VersionNo(1, 0),
      status,
      unit.map(u => "organizationalUnit" -> FieldEntry(MetadataValue.Text(u), ValueOrigin.Manual)).toMap,
      UserId("author"),
      None,
      None,
      None,
      dueOn,
      requiresAcknowledgement = true
    )

  def spec = suite("Compliance cockpit figures")(
    test("a review is overdue from its review date on, or once the version is ReviewDue") {
      assertTrue(
        ComplianceCockpit.overdue(version("a", None, None, Some(today)), today),
        ComplianceCockpit.overdue(version("a", None, None, Some(today.minusDays(3))), today),
        !ComplianceCockpit.overdue(version("a", None, None, Some(today.plusDays(1))), today),
        !ComplianceCockpit.overdue(version("a", None, None, None), today),
        ComplianceCockpit.overdue(version("a", None, None, None, DocumentStatus.ReviewDue), today),
        ComplianceCockpit.daysOverdue(version("a", None, None, Some(today.minusDays(3))), today) == 3L,
        ComplianceCockpit.daysOverdue(version("a", None, None, Some(today)), today) == 0L
      )
    },
    test("filters: absent means all, empty means none") {
      assertTrue(
        ComplianceCockpit.matches(None, Some("HR")),
        ComplianceCockpit.matches(None, None),
        ComplianceCockpit.matches(Some("HR"), Some("HR")),
        !ComplianceCockpit.matches(Some("HR"), None),
        ComplianceCockpit.matches(Some(""), None),
        !ComplianceCockpit.matches(Some(""), Some("HR"))
      )
    },
    test("counts and rates are grouped per unit, versions without unit form a group of their own") {
      val entries = List(
        Entry(version("a", Some("HR"), Some("o1"), Some(today)), Some(Rate(10, 4))),
        Entry(version("b", Some("HR"), Some("o2"), Some(today)), Some(Rate(10, 10))),
        Entry(version("c", None, None, Some(today)), Some(Rate(3, 0))),
        Entry(version("d", Some("IT"), Some("o1"), None), None)
      )
      assertTrue(
        ComplianceCockpit.countBy(entries)(_.unit) == List(Some("HR") -> 2, Some("IT") -> 1, None -> 1),
        ComplianceCockpit.ratesByUnit(entries) == List((Some("HR"), 2, Rate(20, 14)), (None, 1, Rate(3, 0))),
        ComplianceCockpit.total(entries) == (3, Rate(23, 14)),
        Rate(0, 0).share.isEmpty,
        Rate(4, 1).share.contains(0.25)
      )
    }
  )
