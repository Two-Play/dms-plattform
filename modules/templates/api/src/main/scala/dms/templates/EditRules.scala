package dms.templates

/** Configuration of the structural edit rules.
  *
  * @param allowNewSections
  *   whether authors may add sections of their own; even then, a new section may only be placed where both
  *   neighbouring template sections are editable (see [[EditRules]])
  */
final case class EditPolicy(allowNewSections: Boolean = true)

object EditPolicy:
  val default: EditPolicy = EditPolicy()

/** A change an author is not allowed to make to a document created from a template. */
enum EditViolation:
  case LockedSectionRemoved(section: SectionId)
  case LockedSectionMoved(section: SectionId)
  case LockedSectionRenamed(section: SectionId, from: Option[String], to: Option[String])
  case LockedSectionUnlocked(section: SectionId)
  case LockedSectionChangedWithoutDeviation(section: SectionId)
  case NewSectionNotAllowed(section: SectionId)
  case NewSectionMisplaced(section: SectionId)
  case NewSectionLocked(section: SectionId)
  case DuplicateSection(section: SectionId)

  def section: SectionId

  def reason: String = this match
    case LockedSectionRemoved(s) => s"Locked section '${s.value}' must not be removed."
    case LockedSectionMoved(s)   =>
      s"Locked section '${s.value}' must keep its position relative to the other template sections."
    case LockedSectionRenamed(s, from, to) =>
      s"Locked section '${s.value}' must not be renamed (from '${from.getOrElse("")}' to '${to.getOrElse("")}')."
    case LockedSectionUnlocked(s)                => s"Locked section '${s.value}' must stay locked."
    case LockedSectionChangedWithoutDeviation(s) =>
      s"Locked section '${s.value}' was changed without a deviation request."
    case NewSectionNotAllowed(s) =>
      s"Section '${s.value}' is not part of the template; new sections are not allowed."
    case NewSectionMisplaced(s) =>
      s"New section '${s.value}' may only be placed between editable template sections."
    case NewSectionLocked(s) =>
      s"New section '${s.value}' must not be locked; only templates define locked sections."
    case DuplicateSection(s) => s"Section id '${s.value}' occurs more than once."
end EditViolation

/** Edit rules for documents created from a template (business concept 4.1, US-01.1).
  *
  * The document content has the template's structure: sections are identified by their id. Rules:
  *
  *   - Editable sections: content and title may change freely; they may be removed and reordered among each
  *     other.
  *   - Locked sections: the content (blocks) may only change if a [[DeviationRequest]] for the section
  *     exists. Structural changes are never allowed, not even with a request: a locked section cannot be
  *     removed, renamed (title), unlocked or moved relative to any other retained template section.
  *   - New sections (ids not in the template): only if [[EditPolicy.allowNewSections]], never locked, and
  *     only where the nearest template sections before and after are editable (the start and end of the
  *     document count as editable). Locked sequences such as "Violations, Effective date" therefore stay
  *     contiguous.
  *   - Section ids are unique.
  */
object EditRules:

  /** All violations of the rules; empty if the document is a permitted edit of the template. */
  def check(
      template: TemplateContent,
      document: TemplateContent,
      requests: List[DeviationRequest],
      policy: EditPolicy = EditPolicy.default
  ): List[EditViolation] =
    import EditViolation.*
    val templateById = template.sections.map(s => s.id -> s).toMap
    val documentById = document.sections.map(s => s.id -> s).toMap
    val requested    = requests.map(_.sectionId).toSet

    val duplicates = document.sections
      .groupBy(_.id)
      .collect { case (id, ss) if ss.size > 1 => id }
      .toList
      .sortBy(id => document.sections.indexWhere(_.id == id))
      .map(DuplicateSection(_))

    val locked = template.sections.filter(_.locked).flatMap { t =>
      documentById.get(t.id) match
        case None    => List(LockedSectionRemoved(t.id))
        case Some(d) =>
          List(
            Option.when(d.title != t.title)(LockedSectionRenamed(t.id, t.title, d.title)),
            Option.when(!d.locked)(LockedSectionUnlocked(t.id)),
            Option.when(d.blocks != t.blocks && !requested(t.id))(LockedSectionChangedWithoutDeviation(t.id))
          ).flatten
    }

    val moved = movedLockedSections(template, document).map(LockedSectionMoved(_))

    val added =
      document.sections.zipWithIndex.filterNot((s, _) => templateById.contains(s.id)).flatMap { (s, index) =>
        if !policy.allowNewSections then List(NewSectionNotAllowed(s.id))
        else
          val editableAround =
            nearestTemplateSection(document.sections.take(index).reverse, templateById).forall(!_.locked) &&
              nearestTemplateSection(document.sections.drop(index + 1), templateById).forall(!_.locked)
          List(
            Option.when(s.locked)(NewSectionLocked(s.id)),
            Option.when(!editableAround)(NewSectionMisplaced(s.id))
          ).flatten
      }

    duplicates ++ locked ++ moved ++ added
  end check

  /** True if [[check]] finds no violation. */
  def isPermitted(
      template: TemplateContent,
      document: TemplateContent,
      requests: List[DeviationRequest],
      policy: EditPolicy = EditPolicy.default
  ): Boolean = check(template, document, requests, policy).isEmpty

  /** Locked template sections whose order relative to another retained template section differs between
    * template and document, in template order.
    */
  def movedLockedSections(template: TemplateContent, document: TemplateContent): List[SectionId] =
    val templatePos = template.sections.map(_.id).zipWithIndex.toMap
    val retained    = document.sections.map(_.id).filter(templatePos.contains).distinct
    val documentPos = retained.zipWithIndex.toMap
    template.sections.filter(s => s.locked && documentPos.contains(s.id)).map(_.id).filter { l =>
      retained.exists(x => x != l && (documentPos(x) < documentPos(l)) != (templatePos(x) < templatePos(l)))
    }

  private def nearestTemplateSection(
      sections: List[Section],
      templateById: Map[SectionId, Section]
  ): Option[Section] =
    sections.collectFirst(Function.unlift(s => templateById.get(s.id)))
end EditRules
