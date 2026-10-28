package dms.templates.core

import dms.templates.*
import zio.test.*

/** Rounding of computed amounts (DMS-77). */
object MoneySpec extends ZIOSpecDefault:

  private val schema = TemplateSchema(
    List(
      Variable("fee", ValueType.Amount),
      Variable("factor", ValueType.Number),
      Variable("rate", ValueType.Number)
    )
  )

  /** Evaluates the computed amount field `result` defined by `expression` and returns it as printed. */
  private def compute(expression: String, values: Map[String, FieldValue]): String =
    val content = TemplateContent(
      List(
        Section(
          SectionId("s"),
          None,
          locked = false,
          List(Block.Paragraph(List(Inline.Placeholder("result", Some("amount")))))
        )
      ),
      computed = List(ComputedField("result", expression))
    )
    val compiled = TemplateCompiler
      .compile(content, schema, Limits.default)
      .fold(i => throw AssertionError(i.map(_.show)), identity)
    Resolver
      .resolve(compiled, values, Language.En)
      .fold(e => throw AssertionError(e.message), identity)
      .content
      .plainText

  private def eur(v: String) = FieldValue.Amount(BigDecimal(v), "EUR")

  def spec = suite("Money")(
    test("0.10 + 0.20 is exactly 0.30") {
      assertTrue(
        compute(
          "{'amount': fee.amount + 0.2, 'currency': fee.currency}",
          Map("fee" -> eur("0.10"))
        ) == "€0.30"
      )
    },
    test("3 × 33.33 is exactly 99.99") {
      assertTrue(
        compute(
          "{'amount': fee.amount * factor, 'currency': fee.currency}",
          Map("fee" -> eur("33.33"), "factor" -> FieldValue.Number(3))
        ) == "€99.99"
      )
    },
    test("percent surcharges round half up to the cent") {
      assertTrue(
        compute(
          "{'amount': fee.amount * rate / 100.0, 'currency': fee.currency}",
          Map("fee" -> eur("100.05"), "rate" -> FieldValue.Number(19))
        ) == "€19.01",
        compute(
          "{'amount': fee.amount * factor, 'currency': fee.currency}",
          Map("fee" -> eur("1.005"), "factor" -> FieldValue.Number(1))
        ) == "€1.01"
      )
    },
    test("currencies without minor units round to whole units") {
      assertTrue(
        Money.round(BigDecimal("1501.5"), "JPY") == BigDecimal("1502"),
        Money.round(BigDecimal("0.30000000000000004"), "EUR") == BigDecimal("0.30"),
        Money.fractionDigits("XXX") == 0,
        Money.fractionDigits("not-a-currency") == 2
      )
    }
  )
