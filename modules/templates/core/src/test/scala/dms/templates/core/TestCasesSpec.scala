package dms.templates.core

import dms.templates.*
import dms.templates.Block.*
import dms.templates.Inline.*
import zio.test.*

object TestCasesSpec extends ZIOSpecDefault:
  import Fixtures.*

  private def oneSection(blocks: Block*) = TemplateContent(List(section("main", blocks*)), content.computed)

  def spec = suite("TestCases")(
    test("cover every branch of the example template, plus empty optionals and long values") {
      val suite = TestCases.generate(Fixtures.compiled())
      val names = suite.cases.map(_.name)
      assertTrue(
        suite.coverage.size == 3,
        suite.uncovered.isEmpty,
        names.take(3) == List("typical values", "empty optional fields", "long values"),
        suite.cases(1).values.keySet == Set("clientName", "monthlyFee", "termType"),
        suite.cases(2).values("clientName").asInstanceOf[FieldValue.Text].value.length > 300
      )
    },
    test("find values around the literals a condition compares with, also through computed fields") {
      val template = Fixtures.compiled(
        oneSection(
          Conditional("monthlyFee.amount > 250000.0", List(Paragraph(List(Text("board approval"))))),
          Conditional("totalValue >= 1000000.0", List(Paragraph(List(Text("large contract"))))),
          Conditional(
            "endDate < timestamp('2030-01-01T00:00:00Z') && termType == 'open'",
            List(Paragraph(List(Text("early end"))))
          ),
          Conditional("monthlyFee.currency == 'CHF'", List(Paragraph(List(Text("Swiss francs")))))
        )
      )
      val suite = TestCases.generate(template)
      assertTrue(suite.uncovered.isEmpty, suite.coverage.size == 4)
    },
    test("every generated case resolves; each branch is shown by at least one case") {
      val template = Fixtures.compiled()
      val suite    = TestCases.generate(template)
      val results  = suite.cases.map(c => Resolver.resolve(template, c.values, Language.En))
      val outcomes =
        results.collect { case Right(r) => r.branches }.flatten.groupMapReduce(_.location)(_.outcomes)(_ ++ _)
      assertTrue(results.forall(_.isRight), outcomes.values.forall(_ == Set(true, false)))
    },
    test("branches no data can reach are reported as uncovered") {
      val template = Fixtures.compiled(oneSection(Conditional("false && international", Nil)))
      val suite    = TestCases.generate(template)
      assertTrue(suite.uncovered.map(_.covered) == List(Set(false)))
    }
  )
