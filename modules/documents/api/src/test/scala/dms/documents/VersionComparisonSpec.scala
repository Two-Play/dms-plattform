package dms.documents

import dms.documents.DocumentStatus.*
import dms.documents.metadata.*
import dms.kernel.{DocumentId, TemplateId, UserId, VersionNo}
import dms.templates.{Block, DiffStatus, Inline, Section, SectionId, SpanKind, TemplateContent, TextSpan}
import zio.test.*

import java.time.Instant
import java.util.UUID

object VersionComparisonSpec extends ZIOSpecDefault:

  private val document = DocumentId(UUID.randomUUID())

  private val term = ProfileField(
    FieldDefinition(FieldKey.unsafe("termMonths"), 1, "Term in months", None, FieldType.Number()),
    Set.empty,
    Some(ClassKey.unsafe("contract"))
  )

  private val profile = MetadataProfile(
    DocumentClassId.random(),
    ClassKey.unsafe("contract"),
    1,
    List(ClassKey.unsafe("contract")),
    CoreFields.fields :+ term
  )

  private def content(text: String) = TemplateContent(
    List(Section(SectionId("terms"), None, locked = false, List(Block.Paragraph(List(Inline.Text(text))))))
  )

  private def manual(value: MetadataValue)   = FieldEntry(value, ValueOrigin.Manual)
  private def text(value: String)            = MetadataValue.Text(value)
  private def at(seconds: Long): Instant     = Instant.EPOCH.plusSeconds(seconds)
  private def number(major: Int, minor: Int) = VersionNo(major, minor)

  private def version(
      no: VersionNo,
      status: DocumentStatus,
      created: Long,
      body: String = "The term is twelve months",
      values: Map[String, FieldEntry] = Map.empty,
      kind: ChangeKind = ChangeKind.Major,
      reason: Option[String] = None
  ) =
    DocumentVersion(
      UUID.randomUUID(),
      document,
      no,
      1,
      status,
      kind,
      reason,
      "",
      1,
      TemplateId(UUID.randomUUID()),
      1,
      content(body),
      values,
      None,
      Option.when(Versioning.frozen(status))(Rendition("key", None, "a" * 64)),
      UserId("author"),
      at(created),
      at(created),
      Option.when(Versioning.frozen(status))(UserId("approver")),
      Option.when(Versioning.frozen(status))(at(created + 10))
    )

  private val baseValues = Map(
    "title"           -> manual(text("Service contract")),
    "version"         -> FieldEntry(text("1.0"), ValueOrigin.Platform),
    "termMonths"      -> manual(MetadataValue.Number(12)),
    "contractPartner" -> FieldEntry(text("ACME"), ValueOrigin.System("partner-master"))
  )

  def spec = suite("VersionComparison")(
    test("text, field values and metadata are compared separately") {
      val v1 = version(number(1, 0), Superseded, 0, values = baseValues)
      val v2 = version(
        number(2, 0),
        Draft,
        100,
        body = "The term is twenty-four months",
        values = baseValues ++ Map(
          "title"           -> manual(text("Service contract 2027")),
          "version"         -> FieldEntry(text("2.0"), ValueOrigin.Platform),
          "termMonths"      -> manual(MetadataValue.Number(24)),
          "contractPartner" -> manual(text("ACME")),
          "totalValue"      -> FieldEntry(MetadataValue.Number(2400), ValueOrigin.Computed)
        ),
        reason = Some("Longer term")
      )
      val c = VersionComparison.compare(profile, v1, v2, List(v1, v2))
      assertTrue(
        c.text.sections.head.blocks.head.status == DiffStatus.Changed,
        c.text.sections.head.blocks.head.spans == List(
          TextSpan(SpanKind.Equal, "The term is"),
          TextSpan(SpanKind.Deleted, "twelve"),
          TextSpan(SpanKind.Inserted, "twenty-four"),
          TextSpan(SpanKind.Equal, "months")
        ),
        // Class fields in profile order, unknown (computed) ones after them; origin changes count.
        c.fields.map(f => (f.key, f.label, f.before, f.after)) == List(
          ("termMonths", "Term in months", Some(MetadataValue.Number(12)), Some(MetadataValue.Number(24))),
          ("contractPartner", "contractPartner", Some(text("ACME")), Some(text("ACME"))),
          ("totalValue", "totalValue", None, Some(MetadataValue.Number(2400)))
        ),
        c.fields(1).originBefore.contains(ValueOrigin.System("partner-master")),
        c.fields(1).originAfter.contains(ValueOrigin.Manual),
        // Core fields in the order of the core profile, then the properties of the version.
        c.metadata.map(m => (m.key, m.label)) == List(
          "title"             -> "Title",
          "version"           -> "Version",
          "status"            -> "Status",
          "renditionChecksum" -> "Rendition checksum",
          "approvedBy"        -> "Approved by",
          "approvedAt"        -> "Approved at"
        ),
        c.metadata.find(_.key == "status").map(m => (m.before, m.after)) ==
          Some((Some(MetadataValue.Selection("Superseded")), Some(MetadataValue.Selection("Draft")))),
        c.metadata.find(_.key == "approvedBy").map(m => (m.before, m.after)) ==
          Some((Some(MetadataValue.PersonRef("approver")), None)),
        c.summaries.map(s => (s.label, s.summary)) == List(("2.0-draft.1", Some("Longer term")))
      )
    },
    test("identical versions have no changes") {
      val v1 = version(number(1, 0), Published, 0, values = baseValues)
      val c  = VersionComparison.compare(profile, v1, v1, List(v1))
      assertTrue(!c.text.changed, c.fields.isEmpty, c.metadata.isEmpty, c.summaries.isEmpty)
    },
    test("summaries cover the versions after the earlier one up to the later one, in either direction") {
      val v1        = version(number(1, 0), Superseded, 0)
      val v11       = version(number(1, 1), Superseded, 100, kind = ChangeKind.Minor, reason = Some("Typo"))
      val withdrawn = version(number(2, 0), Withdrawn, 200, reason = Some("Abandoned"))
      val v2        = version(number(2, 0), Published, 300, reason = Some("New liability rules"))
      val v3        = version(number(3, 0), Draft, 400, reason = Some("Next"))
      val all       = List(v1, v11, withdrawn, v2, v3)
      val forward   = VersionComparison.compare(profile, v1, v2, all)
      val backward  = VersionComparison.compare(profile, v2, v1, all)
      // A version the caller may not read is not passed in; the later compared version is always included.
      val unread = VersionComparison.compare(profile, v1, v2, List(v1))
      assertTrue(
        forward.summaries.map(s => (s.label, s.changeKind, s.summary)) == List(
          ("1.1", ChangeKind.Minor, Some("Typo")),
          ("2.0", ChangeKind.Major, Some("New liability rules"))
        ),
        backward.summaries == forward.summaries,
        unread.summaries.map(_.label) == List("2.0")
      )
    }
  )
