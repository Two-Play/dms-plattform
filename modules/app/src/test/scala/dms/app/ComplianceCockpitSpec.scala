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
    },
    test("documents without owner count once, whatever their validity scopes") {
      val one     = version("A", Some("HR"), None, None)
      val entries = List(Entry(one, None), Entry(one.copy(versionId = UUID.randomUUID()), None)) ++
        List(Entry(version("B", Some("HR"), Some("o"), None), None))
      assertTrue(
        ComplianceCockpit.withoutOwner(entries, None).size == 1,
        ComplianceCockpit.withoutOwner(entries, Some("Finance")).isEmpty
      )
    },
    test("orphaned: an owner without role, once per document; no owner at all is another figure") {
      val gone    = version("A", Some("HR"), Some("gone"), None)
      val entries = List(
        Entry(gone, None, ownerHasRole = false),
        Entry(gone.copy(versionId = UUID.randomUUID()), None, ownerHasRole = false),
        Entry(version("B", Some("Finance"), Some("here"), None), None),
        Entry(version("C", Some("HR"), None, None), None, ownerHasRole = false)
      )
      assertTrue(
        ComplianceCockpit.orphaned(entries, None, None).map(_.version.document.title) == List("A"),
        ComplianceCockpit.orphaned(entries, Some("HR"), Some("gone")).size == 1,
        ComplianceCockpit.orphaned(entries, Some("Finance"), None).isEmpty,
        ComplianceCockpit.orphaned(entries, None, Some("here")).isEmpty
      )
    },
    test("deviations: only versions that have any, the most first, by unit and owner") {
      val entries = List(
        Entry(version("A", Some("HR"), Some("o"), None), None, deviations = 1),
        Entry(version("B", Some("HR"), Some("p"), None), None, deviations = 3),
        Entry(version("C", Some("Finance"), Some("o"), None), None)
      )
      assertTrue(
        ComplianceCockpit.deviationList(entries, None, None).map(_.deviations) == List(3, 1),
        ComplianceCockpit.deviationList(entries, Some("HR"), Some("o")).map(_.deviations) == List(1)
      )
    },
    test("a CSV cell cannot become a formula, whichever field it comes from") {
      val hostile = "=HYPERLINK(\"http://x\";\"y\")"
      val row     = OverdueReviewView(
        UUID.randomUUID(),
        UUID.randomUUID(),
        hostile,
        "+1",
        List("-class", "b"),
        Some("@unit"),
        Some(" =owner"),
        "Published",
        today,
        3
      )
      val csv   = ComplianceCsv.overdueReviews(Some("de"), List(row), _ => Some("\tname"))
      val lines = csv.stripPrefix("\uFEFF").split("\r\n").toList
      val cells = lines(1)
      assertTrue(
        csv.startsWith("\uFEFF"),
        lines.head.startsWith("Dokument-ID;Titel;"),
        cells.contains("\"'=HYPERLINK(\"\"http://x\"\";\"\"y\"\")\""),
        cells.contains(";'+1;"),
        cells.contains(";'-class/b;"),
        cells.contains(";'@unit;"),
        cells.contains(";' =owner;"),
        cells.contains(";'\tname;"),
        // numbers and dates are ours, and stay numbers and dates
        cells.endsWith(s";$today;3"),
        ComplianceCsv.overdueReviews(None, Nil, _ => None).contains("Document id;Title;"),
        ComplianceCsv.persons(Some("en"), List(("-id", Some("@name")))).contains("'-id;'@name")
      )
    }
  )
