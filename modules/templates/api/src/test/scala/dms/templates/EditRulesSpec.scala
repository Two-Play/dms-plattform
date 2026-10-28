package dms.templates

import zio.test.*

object EditRulesSpec extends ZIOSpecDefault:
  import DeviationFixtures.*
  import EditViolation.*

  private val editedRules =
    rules.copy(blocks = rules.blocks.updated(1, para("Employees must report incidents.")))

  def spec = suite("EditRules")(
    test("the unchanged template is a permitted document") {
      assertTrue(EditRules.check(template, template, Nil).isEmpty)
    },
    test("editable sections may change content and title, be removed and swapped") {
      val document = TemplateContent(
        List(
          scope.copy(title = Some("Applicability"), blocks = List(para("Applies to contractors."))),
          purpose.copy(blocks = Nil),
          rules,
          violations,
          effective
        )
      )
      val withoutScope = TemplateContent(template.sections.filterNot(_.id == scope.id))
      assertTrue(
        EditRules.isPermitted(template, document, Nil),
        EditRules.isPermitted(template, withoutScope, Nil)
      )
    },
    test("changing a locked section without a deviation request is rejected") {
      val violations = EditRules.check(template, replace(template, editedRules), Nil)
      assertTrue(
        violations == List(LockedSectionChangedWithoutDeviation(rules.id)),
        violations.head.section == rules.id,
        violations.head.reason.contains("without a deviation request")
      )
    },
    test("changing a locked section with a deviation request for that section is permitted") {
      val document = replace(template, editedRules)
      assertTrue(
        EditRules.isPermitted(template, document, List(request("rules"))),
        !EditRules.isPermitted(template, document, List(request("violations")))
      )
    },
    test("removing a locked section is rejected, even with a request") {
      val document = TemplateContent(template.sections.filterNot(_.id == rules.id))
      assertTrue(
        EditRules.check(template, document, List(request("rules"))) == List(LockedSectionRemoved(rules.id))
      )
    },
    test("reordering locked sections is rejected") {
      val document = TemplateContent(List(purpose, scope, violations, rules, effective))
      assertTrue(
        EditRules.check(template, document, Nil) ==
          List(LockedSectionMoved(rules.id), LockedSectionMoved(violations.id))
      )
    },
    test("moving an editable section across a locked one is rejected") {
      val document = TemplateContent(List(scope, rules, purpose, violations, effective))
      assertTrue(EditRules.check(template, document, Nil) == List(LockedSectionMoved(rules.id)))
    },
    test("renaming or unlocking a locked section is rejected") {
      val document = replace(template, rules.copy(title = Some("Guidelines"), locked = false))
      assertTrue(
        EditRules.check(template, document, List(request("rules"))) ==
          List(
            LockedSectionRenamed(rules.id, Some("Rules"), Some("Guidelines")),
            LockedSectionUnlocked(rules.id)
          )
      )
    },
    test("new editable sections are permitted between editable sections and at the start") {
      val extra   = section("definitions", locked = false, para("Terms."))
      val between = TemplateContent(List(purpose, extra, scope, rules, violations, effective))
      val atStart = TemplateContent(extra :: template.sections)
      assertTrue(EditRules.isPermitted(template, between, Nil), EditRules.isPermitted(template, atStart, Nil))
    },
    test(
      "new sections next to locked sections, locked new sections and disallowed new sections are rejected"
    ) {
      val extra       = section("annex", locked = false, para("Annex."))
      val nextToLock  = TemplateContent(List(purpose, scope, extra, rules, violations, effective))
      val inLockedRun = TemplateContent(List(purpose, scope, rules, extra, violations, effective))
      val atEnd       = TemplateContent(template.sections :+ extra)
      val lockedNew   =
        TemplateContent(List(purpose, extra.copy(locked = true), scope, rules, violations, effective))
      val between = TemplateContent(List(purpose, extra, scope, rules, violations, effective))
      assertTrue(
        EditRules.check(template, nextToLock, Nil) == List(NewSectionMisplaced(extra.id)),
        EditRules.check(template, inLockedRun, Nil) == List(NewSectionMisplaced(extra.id)),
        EditRules.check(template, atEnd, Nil) == List(NewSectionMisplaced(extra.id)),
        EditRules.check(template, lockedNew, Nil) == List(NewSectionLocked(extra.id)),
        EditRules.check(template, between, Nil, EditPolicy(allowNewSections = false)) ==
          List(NewSectionNotAllowed(extra.id))
      )
    },
    test("duplicate section ids are rejected") {
      val document = TemplateContent(purpose :: template.sections)
      assertTrue(EditRules.check(template, document, Nil) == List(DuplicateSection(purpose.id)))
    }
  )
end EditRulesSpec
