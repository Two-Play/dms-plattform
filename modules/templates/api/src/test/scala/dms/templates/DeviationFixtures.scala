package dms.templates

import dms.kernel.UserId

import java.time.Instant

/** A policy template: editable purpose and scope, locked rules, violations and effective date. */
object DeviationFixtures:
  import Block.*
  import Inline.*

  def section(id: String, locked: Boolean, blocks: Block*): Section =
    Section(SectionId(id), Some(id.capitalize), locked, blocks.toList)

  def para(text: String): Paragraph = Paragraph(List(Text(text)))

  val purpose: Section = section("purpose", locked = false, para("Describe the purpose."))
  val scope: Section   = section("scope", locked = false, para("This policy applies to all employees."))

  val rules: Section = section(
    "rules",
    locked = true,
    Heading(2, List(Text("Rules"))),
    Paragraph(
      List(Text("Employees of "), Placeholder("companyName"), Text(" must report incidents within 24 hours."))
    ),
    BulletList(List(List(para("Use approved devices only.")), List(para("Lock the screen when leaving."))))
  )

  val violations: Section = section(
    "violations",
    locked = true,
    Conditional(
      "contractValue > 250000",
      List(para("Violations are reported to the management board.")),
      List(para("Violations are reported to the line manager."))
    ),
    Table(
      List(
        List(List(para("Severity")), List(para("Sanction"))),
        List(List(para("High")), List(para("Dismissal")))
      )
    )
  )

  val effective: Section =
    section(
      "effective",
      locked = true,
      Paragraph(List(Text("Effective from "), Placeholder("effectiveDate", Some("date:long"))))
    )

  val template: TemplateContent = TemplateContent(List(purpose, scope, rules, violations, effective))

  def request(sectionId: String, reason: String = "Negotiated with works council"): DeviationRequest =
    DeviationRequest(SectionId(sectionId), reason, UserId("author-1"), Instant.parse("2026-10-28T10:00:00Z"))

  def replace(content: TemplateContent, s: Section): TemplateContent =
    TemplateContent(content.sections.map(old => if old.id == s.id then s else old))
end DeviationFixtures
