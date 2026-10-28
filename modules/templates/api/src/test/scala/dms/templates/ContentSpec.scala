package dms.templates

import zio.test.*

object ContentSpec extends ZIOSpecDefault:
  import Block.*
  import Inline.*

  private val content = TemplateContent(
    List(
      Section(
        SectionId("parties"),
        Some("Parties"),
        locked = false,
        List(
          Paragraph(
            List(Text("Between "), Placeholder("clientName"), Text(" and "), Placeholder("supplierName"))
          )
        )
      ),
      Section(
        SectionId("fees"),
        Some("Fees"),
        locked = true,
        List(
          Conditional(
            "term == 'fixed'",
            List(Paragraph(List(Text("Ends on "), Placeholder("endDate", Some("date:long"))))),
            List(Paragraph(List(Text("Notice period: "), Placeholder("noticePeriod"))))
          ),
          Repeat(
            "services",
            "service",
            List(BulletList(List(List(Paragraph(List(Placeholder("service.name")))))))
          )
        )
      )
    )
  )

  def spec = suite("TemplateContent")(
    test("placeholders are found in nested blocks in document order") {
      assertTrue(
        TemplateContent.placeholders(content) ==
          List("clientName", "supplierName", "endDate", "noticePeriod", "service.name")
      )
    },
    test("blocks include nested blocks of conditionals, repeats and lists") {
      assertTrue(TemplateContent.blocks(content).size == 7)
    }
  )
