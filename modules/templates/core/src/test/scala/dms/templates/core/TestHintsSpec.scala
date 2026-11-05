package dms.templates.core

import dms.templates.*
import dms.templates.Block.*
import dms.templates.Inline.*
import zio.test.*

object TestHintsSpec extends ZIOSpecDefault:
  import Fixtures.*

  private def preview(compiled: CompiledTemplate) =
    PreviewTemplate(PreviewFixtures.version, PreviewFixtures.profile, compiled)

  private def hints(content: TemplateContent): TestHints =
    val compiled = Fixtures.compiled(content)
    val cases    = TestCases.generate(compiled).cases
    TestHints.from(preview(compiled), cases.map(c => c -> Resolver.resolve(compiled, c.values)))

  private def computed(fields: ComputedField*) =
    TemplateContent(List(section("main", Paragraph(List(Text("x"))))), fields.toList)

  private def conditions(blocks: Block*) =
    TemplateContent(List(section("main", blocks*)), Fixtures.content.computed)

  private val term = MissingValue("termMonths", "Term in months")

  def spec = suite("TestHints")(
    test("a computed field without value when optional fields are empty gets a present_fields guard") {
      val found = hints(
        computed(ComputedField("totalValue", "monthlyFee.amount * termMonths", Some("Total contract value")))
      ).computed
      assertTrue(
        found.map(h => (h.key, h.label, h.missingInputs)) ==
          List(("totalValue", "Total contract value", List(term))),
        found.head.guard == "'termMonths' in present_fields",
        found.head.suggestion.contains(
          "'termMonths' in present_fields ? monthlyFee.amount * termMonths : 0.0"
        ),
        found.head.cases.contains("empty optional fields")
      )
    },
    test("without a label a hint shows the key") {
      val found = hints(computed(ComputedField("totalValue", "monthlyFee.amount * termMonths"))).computed
      assertTrue(found.map(_.label) == List("totalValue"))
    },
    test("following the suggestion ends the hint, also for several fields and through computed fields") {
      val first = hints(
        computed(
          ComputedField("sum", "termMonths + (international ? 1.0 : 0.0)"),
          ComputedField("twice", "sum * 2.0")
        )
      ).computed
      val fixed = first.map(h => ComputedField(h.key, h.suggestion.getOrElse(h.key)))
      assertTrue(
        first.map(_.key) == List("sum", "twice"),
        first.head.guard == "'termMonths' in present_fields && 'international' in present_fields",
        hints(computed(fixed*)).computed.isEmpty
      )
    },
    test("guarded computed fields and fields that are all required get no hint") {
      val found = hints(
        computed(
          ComputedField("term", "'termMonths' in present_fields ? termMonths : 12.0"),
          ComputedField("annual", "monthlyFee.amount * 12.0")
        )
      )
      assertTrue(found.computed.isEmpty)
    },
    test("a missing required field gets no guard: it has to be filled in") {
      val compiled = Fixtures.compiled(computed(ComputedField("annual", "monthlyFee.amount * 12.0")))
      val values   = Fixtures.values - "monthlyFee"
      val result   = Resolver.resolve(compiled, values)
      val found    = TestHints.from(preview(compiled), List(TestCase("given values", values) -> result))
      assertTrue(result.exists(_.missingComputed.nonEmpty), found.computed.isEmpty)
    },
    test("a calculation with a conditional stays one operand of the suggestion") {
      val found = hints(computed(ComputedField("fee", "termMonths > 12.0 ? 1.0 : 2.0"))).computed
      assertTrue(
        found.flatMap(_.suggestion) ==
          List("'termMonths' in present_fields ? (termMonths > 12.0 ? 1.0 : 2.0) : 0.0")
      )
    },
    test("a question mark in a text is no conditional") {
      val found = hints(computed(ComputedField("note", "noticePeriod + '?'"))).computed
      assertTrue(
        found.flatMap(_.suggestion) == List("'noticePeriod' in present_fields ? noticePeriod + '?' : ''")
      )
    },
    test("result types without an empty value get the guard alone; no date or currency is made up") {
      val found = hints(
        computed(
          ComputedField("ends", "endDate"),
          ComputedField("fee", "{'amount': termMonths, 'currency': 'EUR'}")
        )
      ).computed
      assertTrue(
        found.map(h => (h.key, h.guard, h.suggestion)) == List(
          ("ends", "'endDate' in present_fields", None),
          ("fee", "'termMonths' in present_fields", None)
        )
      )
    },
    test("a suggestion that would not compile is left out") {
      val frame = "termMonths + double(size(''))"
      val text  = "x" * (Limits.default.maxExpressionLength - frame.length)
      val found = hints(computed(ComputedField("sum", s"termMonths + double(size('$text'))")))
      assertTrue(found.computed.map(_.suggestion) == List(None))
    },
    test("a case that fails in a condition still shows its computed fields") {
      val found = hints(
        TemplateContent(
          List(section("main", Conditional("100 / int(termMonths) > 1", Nil))),
          List(ComputedField("twice", "termMonths * 2.0"))
        )
      )
      assertTrue(found.computed.map(_.key) == List("twice"))
    },
    test("conditions that read a missing value are listed with their fields and cases, each once") {
      val found = hints(
        conditions(
          Conditional("termMonths > 12.0", Nil),
          Conditional("'termMonths' in present_fields && termMonths > 12.0", Nil),
          Conditional("totalValue > 100.0", Nil),
          Conditional("termType == 'fixed'", Nil)
        )
      ).conditions
      assertTrue(
        found.map(h => (h.hint.condition, h.hint.missingInputs)) == List(
          ("termMonths > 12.0", List(term)),
          ("totalValue > 100.0", List(term))
        ),
        found.forall(_.cases.contains("empty optional fields")),
        found.map(_.hint.location.blockPath) == List("/blocks/0", "/blocks/2")
      )
    }
  )
