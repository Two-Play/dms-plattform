package dms.templates.core

import dms.templates.*
import dms.templates.Block.*
import dms.templates.Inline.*
import zio.test.*

import java.time.LocalDate

object ResolverSpec extends ZIOSpecDefault:
  import Fixtures.*

  private def resolve(
      values: Map[String, FieldValue] = Fixtures.values,
      language: Language = Language.En,
      template: CompiledTemplate = Fixtures.compiled()
  ): Resolution =
    Resolver.resolve(template, values, language).fold(e => throw AssertionError(e.message), identity)

  private def oneSection(blocks: Block*) = TemplateContent(List(section("main", blocks*)))

  private def text(r: Resolution) = r.content.plainText

  def spec = suite("Resolver")(
    test("resolves every block type") {
      val r = resolve()
      assertTrue(
        text(r) ==
          """Parties
            |Service contract
            |Between ACME GmbH and Example Ltd.
            |
            |Monthly fee: €1,234.50
            |Total: 29,628
            |Ends on October 31, 2027
            |- Hosting
            |- Support
            |Item | Value
            |Term | Fixed term
            |1. First
            |2. Second
            |""".stripMargin.trim,
        r.content.sections(1).blocks.contains(ResolvedBlock.PageBreak),
        r.content.sections(0).locked,
        r.missingRequired.isEmpty
      )
    },
    test("conditionals choose the otherwise branch and include clause references") {
      val r = resolve(
        Fixtures.values ++ Map(
          "termType"      -> FieldValue.Text("open"),
          "noticePeriod"  -> FieldValue.Text("3 months"),
          "international" -> FieldValue.YesNo(true)
        )
      )
      assertTrue(
        text(r).contains("Notice period: 3 months"),
        !text(r).contains("Ends on"),
        r.content.sections(1).blocks.contains(ResolvedBlock.ClauseRef("foreign-law", 2)),
        r.branches.map(_.outcomes) == List(Set(false), Set(false), Set(true))
      )
    },
    test("a conditional inside a repeat can take both branches") {
      val r = resolve(
        Fixtures.values + ("services" -> FieldValue.ListOf(
          List(FieldValue.Text("Hosting"), FieldValue.Text("Managed database operations"))
        ))
      )
      assertTrue(
        r.branches.find(_.condition == "service.size() > 20").map(_.outcomes).contains(Set(true, false)),
        text(r).contains("(long service name)")
      )
    },
    test("formats follow the document language") {
      val r = resolve(language = Language.De)
      assertTrue(
        text(r).contains("Monthly fee: 1.234,50 €"),
        text(r).contains("Total: 29.628"),
        text(r).contains("Ends on 31. Oktober 2027")
      )
    },
    test("formatters: plain, number, amount, date:long, date:short, yes/no, lists, selection labels") {
      def f(v: FieldValue, format: Option[String], l: Language, t: Option[ValueType] = None) =
        Formatting.format(v, format, l, t)
      val date = FieldValue.Date(LocalDate.of(2026, 10, 28))
      assertTrue(
        f(date, Some("date:short"), Language.De) == "28.10.2026",
        f(date, Some("date:short"), Language.En) == "10/28/2026",
        f(date, Some("date:long"), Language.De) == "28. Oktober 2026",
        f(date, None, Language.De) == "28.10.2026",
        f(FieldValue.Number(BigDecimal("1234567.125")), Some("number"), Language.De) == "1.234.567,125",
        f(FieldValue.Amount(BigDecimal(99), "USD"), Some("amount"), Language.En) == "$99.00",
        f(FieldValue.Amount(BigDecimal(99), "CHF"), Some("amount"), Language.En) == "CHF 99.00",
        f(FieldValue.Amount(BigDecimal(1000), "JPY"), None, Language.De) == "1.000 ¥",
        f(FieldValue.YesNo(true), None, Language.De) == "ja",
        f(FieldValue.YesNo(false), None, Language.En) == "no",
        f(
          FieldValue.ListOf(List(FieldValue.Number(1), FieldValue.Number(2.5))),
          None,
          Language.En
        ) == "1, 2.5",
        f(
          FieldValue.Text("open"),
          None,
          Language.En,
          Some(ValueType.Selection(List(Choice("open", "Open"))))
        ) == "Open",
        f(FieldValue.Text("x"), Some("amount"), Language.En) == "x"
      )
    },
    test("missing optional values render as an empty marker, missing required values are reported") {
      val r       = resolve(Fixtures.values - "endDate" - "clientName")
      val inlines =
        r.content.sections.flatMap(_.blocks).collect { case ResolvedBlock.Paragraph(c) => c }.flatten
      assertTrue(
        inlines.contains(ResolvedInline.Missing("endDate", required = false)),
        inlines.contains(ResolvedInline.Missing("clientName", required = true)),
        r.missingRequired == List("clientName")
      )
    },
    test("missing optional values evaluate as empty values, present_fields tells them apart") {
      val template = Fixtures.compiled(
        oneSection(
          Conditional("'endDate' in present_fields", List(Paragraph(List(Text("has end date"))))),
          Conditional("termMonths == 0.0 && size(services) == 0", List(Paragraph(List(Text("empty")))))
        )
      )
      val r = resolve(Map.empty, template = template)
      assertTrue(text(r) == "empty")
    },
    test("values that do not fit the schema are rejected") {
      val result = Resolver.resolve(
        Fixtures.compiled(),
        Map(
          "termMonths" -> FieldValue.Text("two"),
          "termType"   -> FieldValue.Text("weekly"),
          "x"          -> FieldValue.YesNo(true)
        ),
        Language.En
      )
      assertTrue(result match
        case Left(ResolveError.InvalidValues(problems)) => problems.size == 3
        case _                                          => false)
    },
    test("runtime errors are reported with the location of the expression") {
      val template =
        Fixtures.compiled(oneSection(Paragraph(Nil), Conditional("1 / int(termMonths) > 0", Nil)))
      val result =
        Resolver.resolve(template, Fixtures.values + ("termMonths" -> FieldValue.Number(0)), Language.En)
      assertTrue(result match
        case Left(ResolveError.Evaluation(location, message)) =>
          location.blockPath == "/blocks/1" && message.contains("by zero")
        case _ => false)
    },
    test("a runaway expression is stopped by the step limit") {
      val runaway  = "services.all(a, services.all(b, services.all(c, services.all(d, a + b + c + d != ''))))"
      val template = Fixtures.compiled(oneSection(Conditional(runaway, Nil)))
      val services = FieldValue.ListOf(List.tabulate(12)(i => FieldValue.Text(s"s$i")))
      val result   = Resolver.resolve(template, Fixtures.values + ("services" -> services), Language.En)
      assertTrue(result match
        case Left(ResolveError.LimitExceeded(Some(location), message)) =>
          location.part == "condition" && message.contains("evaluation steps")
        case _ => false)
    },
    test("macros are limited in their iterations") {
      val template = Fixtures.compiled(
        oneSection(Conditional("services.exists(s, s == 'x')", Nil)),
        Limits(maxComprehensionIterations = 10)
      )
      val services = FieldValue.ListOf(List.tabulate(50)(i => FieldValue.Text(s"s$i")))
      val result   = Resolver.resolve(template, Fixtures.values + ("services" -> services), Language.En)
      assertTrue(result.left.exists(_.isInstanceOf[ResolveError.LimitExceeded]))
    },
    test("huge repeats are stopped by the repeat limit") {
      val template = Fixtures.compiled(
        oneSection(
          Repeat("services", "a", List(Repeat("services", "b", List(Paragraph(List(Placeholder("b")))))))
        ),
        Limits(maxRepeatIterations = 1000)
      )
      val services = FieldValue.ListOf(List.tabulate(100)(i => FieldValue.Text(s"s$i")))
      val result   = Resolver.resolve(template, Fixtures.values + ("services" -> services), Language.En)
      assertTrue(result match
        case Left(ResolveError.LimitExceeded(_, message)) => message.contains("more than 1000 items")
        case _                                            => false)
    },
    test("the resolved text is limited in size") {
      val template = Fixtures.compiled(
        oneSection(Repeat("services", "s", List(Paragraph(List(Placeholder("s")))))),
        Limits(maxOutputLength = 1000)
      )
      val services = FieldValue.ListOf(List.fill(20)(FieldValue.Text("x" * 100)))
      val result   = Resolver.resolve(template, Fixtures.values + ("services" -> services), Language.En)
      assertTrue(result match
        case Left(ResolveError.LimitExceeded(_, message)) => message.contains("longer than 1000 characters")
        case _                                            => false)
    },
    test("resolution is limited in time") {
      val template = Fixtures.compiled(limits = Limits(timeoutMillis = 0))
      val result   = Resolver.resolve(template, Fixtures.values, Language.En)
      assertTrue(result match
        case Left(ResolveError.LimitExceeded(_, message)) => message.contains("longer than 0 ms")
        case _                                            => false)
    }
  )
