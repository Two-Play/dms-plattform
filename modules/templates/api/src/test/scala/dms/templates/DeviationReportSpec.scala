package dms.templates

import zio.test.*

object DeviationReportSpec extends ZIOSpecDefault:
  import Block.*
  import DeviationChange.*
  import DeviationFixtures.*
  import Inline.*

  private def loc(section: Section, path: PathStep*)(token: Int): DeviationLocation =
    DeviationLocation(section.id, path.toList, Some(token))

  private def changes(document: TemplateContent): List[(DeviationLocation, DeviationChange)] =
    DeviationReport.compare(template, document, Nil).deviations.map(d => d.location -> d.change)

  def spec = suite("DeviationReport")(
    test("an unchanged document produces an empty report") {
      val report = DeviationReport.compare(template, template, List(request("rules")))
      assertTrue(report.isEmpty, !DeviationReport.requiresExtraReview(report))
    },
    test("changes in editable sections are not reported") {
      val document = replace(template, scope.copy(blocks = List(para("Applies to contractors only."))))
      assertTrue(DeviationReport.compare(template, document, Nil).isEmpty)
    },
    test("an authorized deviation is reported with its reason and requires the extra review") {
      val edited = rules.copy(blocks =
        rules.blocks.updated(
          1,
          Paragraph(
            List(
              Text("Employees of "),
              Placeholder("companyName"),
              Text(" must report incidents within 48 hours.")
            )
          )
        )
      )
      val report =
        DeviationReport.compare(template, replace(template, edited), List(request("rules", "Shift work")))
      val single = report.deviations.head
      assertTrue(
        report.deviations.size == 1,
        single.location == loc(rules, PathStep.Block(1))(7),
        single.change == TextReplaced("24", "48"),
        single.authorized,
        single.reasons == List("Shift work"),
        report.unauthorized.isEmpty,
        DeviationReport.requiresExtraReview(report)
      )
    },
    test("a deviation without a request is flagged as unauthorized and does not add the extra review") {
      val edited =
        rules.copy(blocks = rules.blocks.updated(0, Heading(2, List(Text("Rules"), Text("(binding)")))))
      val report = DeviationReport.compare(template, replace(template, edited), List(request("violations")))
      assertTrue(
        report.deviations.map(_.change) == List(TextInserted("(binding)")),
        report.unauthorized.size == 1,
        !DeviationReport.requiresExtraReview(report)
      )
    },
    test("word-level diff reports insertions, deletions and replacements at their word position") {
      val edited = effective.copy(blocks =
        List(
          Paragraph(List(Text("Valid and effective from "), Placeholder("effectiveDate", Some("date:long"))))
        )
      )
      val deleted = rules.copy(blocks =
        rules.blocks.updated(
          1,
          Paragraph(List(Text("Employees of "), Placeholder("companyName"), Text(" must report incidents.")))
        )
      )
      assertTrue(
        changes(replace(template, edited)) == List(
          loc(effective, PathStep.Block(0))(0) -> TextReplaced("Effective", "Valid and effective")
        ),
        changes(replace(template, deleted)) ==
          List(loc(rules, PathStep.Block(1))(5) -> TextReplaced("incidents within 24 hours.", "incidents."))
      )
    },
    test("placeholders inside changed paragraphs are reported as placeholder changes") {
      val renamed =
        effective.copy(blocks = List(Paragraph(List(Text("Effective from "), Placeholder("startDate")))))
      val added = rules.copy(blocks =
        rules.blocks.updated(
          1,
          Paragraph(
            List(
              Text("Employees of "),
              Placeholder("companyName"),
              Text(" must report incidents to "),
              Placeholder("securityOfficer"),
              Text(" within 24 hours.")
            )
          )
        )
      )
      val removed =
        rules.copy(blocks = rules.blocks.updated(1, para("Employees must report incidents within 24 hours.")))
      val p2: Placeholder = Placeholder("effectiveDate", Some("date:long"))
      assertTrue(
        changes(replace(template, renamed)) ==
          List(loc(effective, PathStep.Block(0))(2) -> PlaceholderChanged(p2, Placeholder("startDate"))),
        changes(replace(template, added)) == List(
          loc(rules, PathStep.Block(1))(6) -> TextInserted("to {{securityOfficer}}"),
          loc(rules, PathStep.Block(1))(6) -> PlaceholderInserted(Placeholder("securityOfficer"))
        ),
        changes(replace(template, removed)) == List(
          loc(rules, PathStep.Block(1))(1) -> TextDeleted("of {{companyName}}"),
          loc(rules, PathStep.Block(1))(1) -> PlaceholderRemoved(Placeholder("companyName"))
        )
      )
    },
    test("changed formatting of the same words is reported as formatting change") {
      val bold = rules.copy(blocks = rules.blocks.updated(0, Heading(2, List(Text("Rules", Set(Mark.Bold))))))
      assertTrue(
        changes(replace(template, bold)) == List(
          loc(rules, PathStep.Block(0))(0) -> FormattingChanged("Rules")
        )
      )
    },
    test("changes in nested list items are located by item path") {
      val list = BulletList(
        List(
          List(para("Use approved devices only.")),
          List(para("Lock the screen.")),
          List(para("Report loss."))
        )
      )
      val edited = rules.copy(blocks = rules.blocks.updated(2, list))
      assertTrue(
        changes(replace(template, edited)) == List(
          loc(rules, PathStep.Block(2), PathStep.Item(1), PathStep.Block(0))(2) ->
            TextReplaced("screen when leaving.", "screen."),
          DeviationLocation(rules.id, List(PathStep.Block(2), PathStep.Item(2))) ->
            BlocksInserted(List(para("Report loss.")))
        )
      )
    },
    test("changes in conditionals report condition and branch changes") {
      val edited = violations.copy(blocks =
        violations.blocks.updated(
          0,
          Conditional(
            "contractValue > 100000",
            List(para("Violations are reported to the management board.")),
            List(para("Violations are reported to the line manager and HR."))
          )
        )
      )
      assertTrue(
        changes(replace(template, edited)) == List(
          DeviationLocation(violations.id, List(PathStep.Block(0))) ->
            ConditionChanged("contractValue > 250000", "contractValue > 100000"),
          loc(violations, PathStep.Block(0), PathStep.Otherwise, PathStep.Block(0))(6) ->
            TextReplaced("manager.", "manager and HR.")
        )
      )
    },
    test("changes in tables are located by cell; deleted rows are reported") {
      val table = Table(
        List(
          List(List(para("Severity")), List(para("Sanction"))),
          List(List(para("High")), List(para("Warning")))
        )
      )
      val shortTable = Table(List(List(List(para("Severity")), List(para("Sanction")))))
      assertTrue(
        changes(replace(template, violations.copy(blocks = violations.blocks.updated(1, table)))) == List(
          loc(violations, PathStep.Block(1), PathStep.Cell(1, 1), PathStep.Block(0))(0) ->
            TextReplaced("Dismissal", "Warning")
        ),
        changes(
          replace(template, violations.copy(blocks = violations.blocks.updated(1, shortTable)))
        ) == List(
          DeviationLocation(violations.id, List(PathStep.Block(1), PathStep.Row(1))) ->
            BlocksDeleted(List(para("High"), para("Dismissal")))
        )
      )
    },
    test("deleted and inserted blocks, clause and repeat changes are reported") {
      val base = template.sections.map {
        case s if s.id == effective.id =>
          s.copy(blocks =
            s.blocks ++ List(ClauseRef("liability", 2), Repeat("sites", "site", List(para("Site"))))
          )
        case s => s
      }
      val tpl    = TemplateContent(base)
      val edited = TemplateContent(base.map {
        case s if s.id == effective.id =>
          s.copy(blocks =
            List(ClauseRef("liability", 3), Repeat("sites", "location", List(para("Site"))), PageBreak)
          )
        case s => s
      })
      assertTrue(
        DeviationReport.compare(tpl, edited, Nil).deviations.map(d => d.location.path -> d.change) == List(
          List(PathStep.Block(0)) -> BlocksDeleted(List(effective.blocks.head)),
          List(PathStep.Block(0)) -> ClauseChanged("liability", 2, "liability", 3),
          List(PathStep.Block(1)) -> RepeatChanged("sites", "site", "sites", "location"),
          List(PathStep.Block(2)) -> BlocksInserted(List(PageBreak))
        )
      )
    },
    test("structural changes of locked sections are reported") {
      val document =
        TemplateContent(List(purpose, scope, violations.copy(title = Some("Breaches")), effective))
      assertTrue(
        changes(document).map(_._2) == List(
          SectionRemoved,
          SectionRenamed(Some("Violations"), Some("Breaches"))
        )
      )
    }
  )
end DeviationReportSpec
