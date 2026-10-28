package dms.templates

import zio.test.*

object TextChangeSpec extends ZIOSpecDefault:

  private def paragraph(text: String, marks: Set[Mark] = Set.empty): Block =
    Block.Paragraph(List(Inline.Text(text, marks)))

  private def content(sections: (String, List[Block])*): TemplateContent =
    TemplateContent(sections.toList.map((id, blocks) => Section(SectionId(id), None, locked = false, blocks)))

  private val hundred = (1 to 100).map(i => s"w$i").mkString(" ")

  def spec = suite("TextChange")(
    test("identical content has not changed") {
      val c = content("a" -> List(paragraph(hundred)))
      assertTrue(TextChange.compare(c, c).ratio == 0.0, TextChange.compare(content(), content()).ratio == 0.0)
    },
    test("replacing one word of a hundred changes one percent; appending a hundred words changes half") {
      val before   = content("a" -> List(paragraph(hundred)))
      val typo     = content("a" -> List(paragraph(hundred.replace("w50 ", "w50x "))))
      val appended = content("a" -> List(paragraph(hundred), paragraph(hundred.replace("w", "v"))))
      assertTrue(
        TextChange.compare(before, typo) == TextChange(99, 100, 100),
        TextChange.compare(before, typo).ratio == 0.01,
        TextChange.compare(before, appended).ratio == 0.5,
        TextChange.compare(before, typo).exceeds(0.005),
        !TextChange.compare(before, typo).exceeds(0.01)
      )
    },
    test("formatting and whitespace are not text changes") {
      val before = content("a" -> List(paragraph("Payment is due  within 30 days")))
      val after  = content("a" -> List(paragraph("Payment is due within 30 days", Set(Mark.Bold))))
      assertTrue(TextChange.compare(before, after).ratio == 0.0)
    },
    test("sections are compared by id; a removed section counts as changed") {
      val before = content("a" -> List(paragraph("one two")), "b" -> List(paragraph("three four")))
      val moved  = content("b" -> List(paragraph("three four")), "a" -> List(paragraph("one two")))
      val gone   = content("a" -> List(paragraph("one two")))
      assertTrue(
        TextChange.compare(before, moved).ratio == 0.0,
        TextChange.compare(before, gone) == TextChange(2, 4, 2),
        TextChange.compare(before, gone).ratio == 0.5
      )
    },
    test("nested blocks, placeholders and clause versions count") {
      val before = content(
        "a" -> List(
          Block.BulletList(List(List(paragraph("first item")))),
          Block.Paragraph(List(Inline.Text("Between "), Inline.Placeholder("partner"))),
          Block.ClauseRef("liability", 1)
        )
      )
      val after = content(
        "a" -> List(
          Block.BulletList(List(List(paragraph("first entry")))),
          Block.Paragraph(List(Inline.Text("Between "), Inline.Placeholder("customer"))),
          Block.ClauseRef("liability", 2)
        )
      )
      assertTrue(TextChange.compare(before, after) == TextChange(2, 5, 5))
    }
  )
