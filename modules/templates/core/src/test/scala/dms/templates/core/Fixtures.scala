package dms.templates.core

import dms.templates.*
import dms.templates.Block.*
import dms.templates.Inline.*

import java.time.LocalDate

/** A service contract template in the style of business concept 4.2. */
object Fixtures:

  val schema: TemplateSchema = TemplateSchema(
    List(
      Variable("clientName", ValueType.Text, required = true),
      Variable("monthlyFee", ValueType.Amount, required = true),
      Variable("termMonths", ValueType.Number),
      Variable(
        "termType",
        ValueType.Selection(List(Choice("fixed", "Fixed term"), Choice("open", "Open-ended"))),
        required = true
      ),
      Variable("endDate", ValueType.Date),
      Variable("noticePeriod", ValueType.Text),
      Variable("services", ValueType.ListOf(ValueType.Text)),
      Variable("international", ValueType.YesNo)
    )
  )

  def section(id: String, blocks: Block*): Section =
    Section(SectionId(id), None, locked = false, blocks.toList)

  val content: TemplateContent = TemplateContent(
    List(
      Section(
        SectionId("parties"),
        Some("Parties"),
        locked = true,
        List(
          Heading(1, List(Text("Service contract"))),
          Paragraph(List(Text("Between "), Placeholder("clientName"), Text(" and Example Ltd.")))
        )
      ),
      section(
        "fees",
        Paragraph(List(Text("Monthly fee: "), Placeholder("monthlyFee", Some("amount")))),
        Paragraph(List(Text("Total: "), Placeholder("totalValue", Some("number")))),
        Conditional(
          "termType == 'fixed'",
          List(Paragraph(List(Text("Ends on "), Placeholder("endDate", Some("date:long"))))),
          List(Paragraph(List(Text("Notice period: "), Placeholder("noticePeriod"))))
        ),
        Repeat(
          "services",
          "service",
          List(
            BulletList(List(List(Paragraph(List(Placeholder("service")))))),
            Conditional("service.size() > 20", List(Paragraph(List(Text("(long service name)")))))
          )
        ),
        Conditional("international", List(ClauseRef("foreign-law", 2))),
        Table(
          List(
            List(List(Paragraph(List(Text("Item")))), List(Paragraph(List(Text("Value"))))),
            List(List(Paragraph(List(Text("Term")))), List(Paragraph(List(Placeholder("termType")))))
          )
        ),
        OrderedList(List(List(Paragraph(List(Text("First")))), List(Paragraph(List(Text("Second")))))),
        PageBreak
      )
    ),
    computed = List(ComputedField("totalValue", "monthlyFee.amount * termMonths"))
  )

  val values: Map[String, FieldValue] = Map(
    "clientName"    -> FieldValue.Text("ACME GmbH"),
    "monthlyFee"    -> FieldValue.Amount(BigDecimal("1234.5"), "EUR"),
    "termMonths"    -> FieldValue.Number(24),
    "termType"      -> FieldValue.Text("fixed"),
    "endDate"       -> FieldValue.Date(LocalDate.of(2027, 10, 31)),
    "services"      -> FieldValue.ListOf(List(FieldValue.Text("Hosting"), FieldValue.Text("Support"))),
    "international" -> FieldValue.YesNo(false)
  )

  def compiled(content: TemplateContent = content, limits: Limits = Limits.default): CompiledTemplate =
    TemplateCompiler.compile(content, schema, limits).fold(i => throw AssertionError(i.map(_.show)), identity)
