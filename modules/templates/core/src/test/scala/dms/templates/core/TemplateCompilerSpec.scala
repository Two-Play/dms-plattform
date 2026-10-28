package dms.templates.core

import dms.templates.*
import dms.templates.Block.*
import dms.templates.Inline.*
import zio.test.*

object TemplateCompilerSpec extends ZIOSpecDefault:
  import Fixtures.*

  private def issues(content: TemplateContent, limits: Limits = Limits.default): List[ContentIssue] =
    TemplateCompiler.compile(content, schema, limits).left.getOrElse(Nil)

  private def oneSection(blocks: Block*) = TemplateContent(List(section("main", blocks*)))

  def spec = suite("TemplateCompiler")(
    test("the example template compiles") {
      val compiled = Fixtures.compiled()
      assertTrue(compiled.conditionals.size == 3)
    },
    test("a syntax error is reported with section, block path, line and column") {
      val found = issues(
        oneSection(Paragraph(Nil), Conditional("true", List(Conditional("termMonths >\n  ", Nil))))
      )
      assertTrue(
        found.size == 1,
        found.head.location.sectionId.contains(SectionId("main")),
        found.head.location.blockPath == "/blocks/1/body/0",
        found.head.location.part == "condition",
        found.head.location.line == 2,
        found.head.location.column == 3
      )
    },
    test("an unknown field in an expression is reported with its column") {
      val found = issues(oneSection(Conditional("termMonths > 2.0 && colour == 'red'", Nil)))
      assertTrue(
        found.size == 1,
        found.head.message.contains("colour"),
        found.head.location.line == 1,
        found.head.location.column == 21
      )
    },
    test("type errors are reported: non-boolean conditions, repeats over non-lists, wrong operand types") {
      val found = issues(
        oneSection(
          Conditional("clientName", Nil),
          Repeat("termMonths", "x", Nil),
          Conditional("clientName > 3.0", Nil)
        )
      )
      assertTrue(
        found.map(i => (i.location.blockPath, i.location.part)) ==
          List(("/blocks/0", "condition"), ("/blocks/1", "collection"), ("/blocks/2", "condition"))
      )
    },
    test("computed fields are checked in order and may use earlier computed fields only") {
      val content = TemplateContent(
        Nil,
        List(
          ComputedField("a", "b + 1.0"),
          ComputedField("b", "termMonths * 2.0"),
          ComputedField("c", "b * 2.0")
        )
      )
      val found = issues(content)
      assertTrue(
        found.size == 1,
        found.head.location == Location(None, "/computed/0", "expression", 1, 1),
        found.head.show.startsWith("computed fields, /computed/0 (expression, line 1, column 1)")
      )
    },
    test("computed fields cannot reuse field names") {
      val found = issues(TemplateContent(Nil, List(ComputedField("clientName", "'x'"))))
      assertTrue(found.map(_.location.part) == List("key"))
    },
    test("placeholders must refer to a field, computed field or repeat item in scope") {
      val found = issues(
        oneSection(
          Paragraph(
            List(Placeholder("unknown"), Placeholder("monthlyFee.amount"), Placeholder("clientName.x"))
          ),
          Repeat("services", "service", List(Paragraph(List(Placeholder("service"))))),
          Paragraph(List(Placeholder("service")))
        )
      )
      assertTrue(
        found.map(i => (i.location.blockPath, i.location.part)) ==
          List(("/blocks/0", "content/0"), ("/blocks/0", "content/2"), ("/blocks/2", "content/0"))
      )
    },
    test("formats must exist and fit the value type") {
      val found = issues(
        oneSection(
          Paragraph(
            List(Placeholder("endDate", Some("date:medium")), Placeholder("clientName", Some("amount")))
          )
        )
      )
      assertTrue(
        found.size == 2,
        found.head.message.contains("unknown format"),
        found(1).message.contains("does not apply")
      )
    },
    test("repeat item names must be valid and must not hide other names") {
      val found =
        issues(oneSection(Repeat("services", "clientName", Nil), Repeat("services", "Bad-Name", Nil)))
      assertTrue(found.map(_.location.part) == List("itemName", "itemName"))
    },
    test("reference fields take known attributes incl. the identifier into distinct single text fields") {
      def reference(key: String, mappings: (String, String)*) =
        ReferenceField(
          key,
          "Partner",
          ReferenceSource.PartnerMaster,
          mappings.map(ReferenceMapping(_, _)).toList
        )
      val valid = TemplateContent(
        Nil,
        references = List(reference("partner", "id" -> "noticePeriod", "name" -> "clientName"))
      )
      val found = issues(
        TemplateContent(
          Nil,
          references = List(
            reference("partner", "name" -> "clientName", "colour" -> "endDate", "name" -> "unknownField"),
            reference("partner", "id"   -> "services", "city"     -> "clientName"),
            reference("Bad-Key", "id"   -> "noticePeriod")
          )
        )
      )
      val compiled = TemplateCompiler.compile(valid, schema)
      assertTrue(
        compiled.isRight,
        found.map(_.message) == List(
          "reference field 'partner' is defined more than once",
          "the identifier ('id') must be taken over into a field as source reference",
          "attribute 'name' is taken over more than once",
          "'colour' is not an attribute of partner-master; known: id, name, street, postalCode, city, country, address, vatId",
          "field 'endDate' must be a single text field",
          "unknown field 'unknownField'",
          "field 'services' must be a single text field",
          "'Bad-Key' is not a valid name (lower camel case)",
          "field 'clientName' is filled by more than one reference"
        ),
        found.head.location.show.startsWith("reference fields, /references")
      )
    },
    test("expressions longer than the limit and too deep nesting are rejected") {
      val deep =
        (1 to 5).foldLeft(List[Block](Paragraph(Nil)))((inner, _) => List(Conditional("true", inner)))
      val found = issues(
        oneSection(Conditional("true" + " && true" * 20, Nil) :: deep*),
        Limits(maxExpressionLength = 50, maxNesting = 3)
      )
      assertTrue(
        found.size == 2,
        found.head.message.contains("longer than 50"),
        found(1).message.contains("nested")
      )
    }
  )
