package dms.templates

import dms.templates.SpanKind.*
import zio.test.*

object ContentDiffSpec extends ZIOSpecDefault:

  private def paragraph(text: String, marks: Set[Mark] = Set.empty): Block =
    Block.Paragraph(List(Inline.Text(text, marks)))

  private def section(id: String, blocks: Block*): Section =
    Section(SectionId(id), Some(id.capitalize), locked = false, blocks.toList)

  private def content(sections: Section*): TemplateContent = TemplateContent(sections.toList)

  private def spans(d: ContentDiff, section: Int = 0, block: Int = 0): List[(SpanKind, String)] =
    d.sections(section).blocks(block).spans.map(s => (s.kind, s.text))

  def spec = suite("ContentDiff")(
    test("identical content is unchanged and keeps its text") {
      val c = content(section("a", Block.Heading(1, List(Inline.Text("Scope"))), paragraph("All staff")))
      val d = ContentDiff.compare(c, c)
      assertTrue(
        !d.changed,
        d.sections.map(_.status) == List(DiffStatus.Unchanged),
        d.sections.head.blocks.map(b => (b.kind, b.status)) ==
          List(("Heading", DiffStatus.Unchanged), ("Paragraph", DiffStatus.Unchanged)),
        spans(d, 0, 1) == List((Equal, "All staff")),
        d.insertedWords == 0,
        d.deletedWords == 0
      )
    },
    test("changed words are marked as deleted and inserted between the kept ones") {
      val before = content(section("a", paragraph("The fee is due within 30 days of invoice")))
      val after  = content(section("a", paragraph("The fee is due within 14 days of the invoice")))
      val d      = ContentDiff.compare(before, after)
      assertTrue(
        d.changed,
        d.sections.head.status == DiffStatus.Changed,
        d.sections.head.blocks.head.status == DiffStatus.Changed,
        spans(d) == List(
          (Equal, "The fee is due within"),
          (Deleted, "30"),
          (Inserted, "14"),
          (Equal, "days of"),
          (Inserted, "the"),
          (Equal, "invoice")
        ),
        d.insertedWords == 2,
        d.deletedWords == 1
      )
    },
    test("placeholders and line breaks are words of their own") {
      val before = content(
        section("a", Block.Paragraph(List(Inline.Text("Fee"), Inline.Placeholder("fee", Some("amount")))))
      )
      val after = content(
        section("a", Block.Paragraph(List(Inline.Text("Fee"), Inline.LineBreak, Inline.Placeholder("total"))))
      )
      assertTrue(
        spans(ContentDiff.compare(before, after)) ==
          List((Equal, "Fee"), (Deleted, "{{fee|amount}}"), (Inserted, "\n{{total}}"))
      )
    },
    test("a formatting change keeps the words and is reported as a structural change") {
      val before = content(section("a", paragraph("Important")))
      val after  = content(section("a", paragraph("Important", Set(Mark.Bold))))
      val block  = ContentDiff.compare(before, after).sections.head.blocks.head
      assertTrue(
        block.status == DiffStatus.Changed,
        block.changes == List(StructureChange.Formatting),
        block.spans == List(TextSpan(Equal, "Important"))
      )
    },
    test("inserted and deleted blocks are whole, nested content included, with their position") {
      val list   = Block.BulletList(List(List(paragraph("first")), List(paragraph("second"))))
      val before = content(section("a", paragraph("Intro"), paragraph("Old rule")))
      val after  = content(section("a", paragraph("Intro"), list))
      val d      = ContentDiff.compare(before, after)
      assertTrue(
        d.sections.head.blocks.map(b => (b.path, b.status, b.spans.map(_.text))) == List(
          (List(PathStep.Block(0)), DiffStatus.Unchanged, List("Intro")),
          (List(PathStep.Block(1)), DiffStatus.Deleted, List("Old rule")),
          (List(PathStep.Block(1), PathStep.Item(0), PathStep.Block(0)), DiffStatus.Inserted, List("first")),
          (List(PathStep.Block(1), PathStep.Item(1), PathStep.Block(0)), DiffStatus.Inserted, List("second"))
        )
      )
    },
    test("list items and table cells are compared in depth") {
      val before = content(
        section(
          "a",
          Block.OrderedList(List(List(paragraph("pay on time")), List(paragraph("keep records")))),
          Block.Table(List(List(List(paragraph("Fee")), List(paragraph("100")))))
        )
      )
      val after = content(
        section(
          "a",
          Block.OrderedList(List(List(paragraph("pay on time")), List(paragraph("keep all records")))),
          Block.Table(List(List(List(paragraph("Fee")), List(paragraph("120")))), headerRows = 0)
        )
      )
      val blocks = ContentDiff.compare(before, after).sections.head.blocks
      assertTrue(
        blocks.map(b => (b.path, b.status)) == List(
          (List(PathStep.Block(0), PathStep.Item(0), PathStep.Block(0)), DiffStatus.Unchanged),
          (List(PathStep.Block(0), PathStep.Item(1), PathStep.Block(0)), DiffStatus.Changed),
          (List(PathStep.Block(1)), DiffStatus.Changed),
          (List(PathStep.Block(1), PathStep.Cell(0, 0), PathStep.Block(0)), DiffStatus.Unchanged),
          (List(PathStep.Block(1), PathStep.Cell(0, 1), PathStep.Block(0)), DiffStatus.Changed)
        ),
        blocks(2).changes == List(StructureChange.HeaderRows(1, 0)),
        blocks(4).spans.map(s => (s.kind, s.text)) == List((Deleted, "100"), (Inserted, "120"))
      )
    },
    test("heading levels, clause versions, conditions and repeats are structural changes") {
      val before = content(
        section(
          "a",
          Block.Heading(2, List(Inline.Text("Liability"))),
          Block.ClauseRef("liability", 1),
          Block.Conditional("fee > 100", List(paragraph("High fee"))),
          Block.Repeat("items", "item", List(paragraph("Item")))
        )
      )
      val after = content(
        section(
          "a",
          Block.Heading(3, List(Inline.Text("Liability"))),
          Block.ClauseRef("liability", 2),
          Block.Conditional("fee > 200", List(paragraph("High fee"))),
          Block.Repeat("positions", "item", List(paragraph("Item")))
        )
      )
      val blocks = ContentDiff.compare(before, after).sections.head.blocks
      assertTrue(
        blocks.map(b => (b.kind, b.status, b.changes)) == List(
          ("Heading", DiffStatus.Changed, List(StructureChange.HeadingLevel(2, 3))),
          ("ClauseRef", DiffStatus.Changed, List(StructureChange.Clause("liability v1", "liability v2"))),
          ("Conditional", DiffStatus.Changed, List(StructureChange.Condition("fee > 100", "fee > 200"))),
          ("Paragraph", DiffStatus.Unchanged, Nil),
          ("Repeat", DiffStatus.Changed, List(StructureChange.Repeat("item in items", "item in positions"))),
          ("Paragraph", DiffStatus.Unchanged, Nil)
        ),
        blocks.head.level.contains(3),
        blocks(1).spans == List(TextSpan(Deleted, "liability v1"), TextSpan(Inserted, "liability v2"))
      )
    },
    test("a block of another kind replaces the old one") {
      val before = content(section("a", paragraph("Text")))
      val after  = content(section("a", Block.Heading(1, List(Inline.Text("Text")))))
      assertTrue(
        ContentDiff.compare(before, after).sections.head.blocks.map(b => (b.kind, b.status)) ==
          List(("Paragraph", DiffStatus.Deleted), ("Heading", DiffStatus.Inserted))
      )
    },
    test("sections are matched by id: inserted, deleted, renamed, moved and locked sections") {
      val before = content(
        section("a", paragraph("A")),
        section("b", paragraph("B")),
        section("c", paragraph("C")),
        section("gone", paragraph("Old"))
      )
      val after = content(
        section("b", paragraph("B")),
        section("a", paragraph("A")).copy(title = Some("Renamed")),
        section("c", paragraph("C")).copy(locked = true),
        section("new", paragraph("New"))
      )
      val d = ContentDiff.compare(before, after)
      assertTrue(
        d.sections.map(s => (s.id.value, s.status, s.moved)) == List(
          ("b", DiffStatus.Unchanged, false),
          ("a", DiffStatus.Changed, true),
          ("c", DiffStatus.Changed, false),
          ("gone", DiffStatus.Deleted, false),
          ("new", DiffStatus.Inserted, false)
        ),
        d.sections.find(_.id.value == "a").map(s => (s.titleBefore, s.titleAfter)) ==
          Some((Some("A"), Some("Renamed"))),
        d.sections.find(_.id.value == "c").map(s => (s.lockedBefore, s.lockedAfter)) ==
          Some((Some(false), Some(true))),
        d.sections.find(_.id.value == "gone").map(_.blocks.map(_.status)) == Some(List(DiffStatus.Deleted)),
        d.sections.find(_.id.value == "new").map(_.blocks.map(_.spans)) ==
          Some(List(List(TextSpan(Inserted, "New"))))
      )
    }
  )
