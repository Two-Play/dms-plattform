package dms.templates

import dms.templates.DeviationDiff.{Aligned, Token}

/** One step of the path from a section to a block. Indices are positions in the document; for deleted
  * content, the position in the document where it was removed.
  */
enum PathStep:
  case Block(index: Int)
  case Item(index: Int)
  case Row(index: Int)
  case Cell(row: Int, column: Int)
  case Then
  case Otherwise
  case Body

  def show: String = this match
    case Block(i)   => s"block $i"
    case Item(i)    => s"item $i"
    case Row(i)     => s"row $i"
    case Cell(r, c) => s"cell $r/$c"
    case Then       => "then"
    case Otherwise  => "otherwise"
    case Body       => "body"

/** Where a deviation is: the section, the block path inside it and, for inline content, the index of the
  * first affected word token of the document's heading or paragraph (placeholders and line breaks count as
  * one token each; whitespace does not count).
  */
final case class DeviationLocation(section: SectionId, path: List[PathStep] = Nil, token: Option[Int] = None):
  def show: String =
    (section.value :: path.map(_.show) ++ token.map(t => s"word $t").toList).mkString(" › ")

/** A single change of standard text. Text is rendered word by word separated by single spaces, with
  * placeholders as `{{path}}` or `{{path|format}}`.
  */
enum DeviationChange:
  case TextInserted(text: String)
  case TextDeleted(text: String)
  case TextReplaced(from: String, to: String)

  /** Same words, different marks (bold, italic, underline). */
  case FormattingChanged(text: String)

  case PlaceholderInserted(placeholder: Inline.Placeholder)
  case PlaceholderRemoved(placeholder: Inline.Placeholder)
  case PlaceholderChanged(from: Inline.Placeholder, to: Inline.Placeholder)
  case ConditionChanged(from: String, to: String)
  case RepeatChanged(fromCollection: String, fromItem: String, toCollection: String, toItem: String)
  case ClauseChanged(fromKey: String, fromVersion: Int, toKey: String, toVersion: Int)
  case HeadingLevelChanged(from: Int, to: Int)
  case HeaderRowsChanged(from: Int, to: Int)

  /** Blocks, a list item or a table row (its cells' blocks) were inserted or deleted. */
  case BlocksInserted(blocks: List[Block])
  case BlocksDeleted(blocks: List[Block])

  case SectionRemoved
  case SectionMoved
  case SectionRenamed(from: Option[String], to: Option[String])
  case SectionUnlocked
end DeviationChange

/** A change in a locked section with the deviation requests recorded for that section. Without a request the
  * deviation is unauthorized.
  */
final case class Deviation(
    location: DeviationLocation,
    change: DeviationChange,
    requests: List[DeviationRequest]
):
  def authorized: Boolean   = requests.nonEmpty
  def reasons: List[String] = requests.map(_.reason)
  def unauthorized: Boolean = !authorized
  def section: SectionId    = location.section

/** Deviation report (business concept 4.1/5, US-01.1): every change to the standard text of locked sections
  * of a document relative to its template, with location and the author's reason.
  */
final case class DeviationReport(deviations: List[Deviation]):
  def isEmpty: Boolean              = deviations.isEmpty
  def authorized: List[Deviation]   = deviations.filter(_.authorized)
  def unauthorized: List[Deviation] = deviations.filter(_.unauthorized)

object DeviationReport:
  import DeviationChange.*

  /** True if the document contains at least one authorized deviation. The document workflow (sprint 6) calls
    * this when a document is submitted and, if true, adds the additional review step of the document class
    * (e.g. the legal department, business concept 6, "contract with clause deviation"). Unauthorized
    * deviations never reach the workflow: [[EditRules.check]] rejects them before submission.
    */
  def requiresExtraReview(report: DeviationReport): Boolean = report.deviations.exists(_.authorized)

  /** Compares the locked sections of `template` with the sections of the same id in `document`. Editable and
    * new sections are not reported. Requests are attached to all deviations of their section.
    */
  def compare(
      template: TemplateContent,
      document: TemplateContent,
      requests: List[DeviationRequest]
  ): DeviationReport =
    val documentById = document.sections.map(s => s.id -> s).toMap
    val moved        = EditRules.movedLockedSections(template, document).toSet
    val deviations   = template.sections.filter(_.locked).flatMap { t =>
      val here    = DeviationLocation(t.id)
      val changes = documentById.get(t.id) match
        case None    => List(here -> SectionRemoved)
        case Some(d) =>
          List(
            Option.when(moved(t.id))(here        -> SectionMoved),
            Option.when(d.title != t.title)(here -> SectionRenamed(t.title, d.title)),
            Option.when(!d.locked)(here          -> SectionUnlocked)
          ).flatten ++ blocks(here, t.blocks, d.blocks)
      val sectionRequests = requests.filter(_.sectionId == t.id)
      changes.map((location, change) => Deviation(location, change, sectionRequests))
    }
    DeviationReport(deviations)
  end compare

  private type Found = List[(DeviationLocation, DeviationChange)]

  private def at(loc: DeviationLocation, step: PathStep): DeviationLocation =
    loc.copy(path = loc.path :+ step)

  private def blocks(loc: DeviationLocation, before: List[Block], after: List[Block]): Found =
    val (b, a) = (before.toIndexedSeq, after.toIndexedSeq)
    DeviationDiff.align(b, a)(_.ordinal == _.ordinal).flatMap {
      case Aligned.Paired(o, n)  => block(at(loc, PathStep.Block(n)), b(o), a(n))
      case Aligned.Deleted(o, n) => List(at(loc, PathStep.Block(n)) -> BlocksDeleted(List(b(o))))
      case Aligned.Inserted(n)   => List(at(loc, PathStep.Block(n)) -> BlocksInserted(List(a(n))))
    }

  /** Aligns items (list items, table rows); paired items are compared with `inner`. */
  private def items[A](loc: DeviationLocation, before: List[A], after: List[A], step: Int => PathStep)(
      content: A => List[Block]
  )(inner: (Int, A, A) => Found): Found =
    val (b, a) = (before.toIndexedSeq, after.toIndexedSeq)
    DeviationDiff.align(b, a)((_, _) => true).flatMap {
      case Aligned.Paired(o, n)  => inner(n, b(o), a(n))
      case Aligned.Deleted(o, n) => List(at(loc, step(n)) -> BlocksDeleted(content(b(o))))
      case Aligned.Inserted(n)   => List(at(loc, step(n)) -> BlocksInserted(content(a(n))))
    }

  private def block(loc: DeviationLocation, before: Block, after: Block): Found =
    import dms.templates.Block as B
    (before, after) match
      case (B.Heading(l1, c1), B.Heading(l2, c2)) =>
        Option.when(l1 != l2)(loc -> HeadingLevelChanged(l1, l2)).toList ++ inlines(loc, c1, c2)
      case (B.Paragraph(c1), B.Paragraph(c2))     => inlines(loc, c1, c2)
      case (B.BulletList(i1), B.BulletList(i2))   => listItems(loc, i1, i2)
      case (B.OrderedList(i1), B.OrderedList(i2)) => listItems(loc, i1, i2)
      case (B.Table(r1, h1), B.Table(r2, h2))     =>
        Option.when(h1 != h2)(loc -> HeaderRowsChanged(h1, h2)).toList ++
          items(loc, r1, r2, PathStep.Row(_))(_.flatten) { (row, cells1, cells2) =>
            val paired = cells1.zip(cells2).zipWithIndex.flatMap { case ((c1, c2), col) =>
              blocks(at(loc, PathStep.Cell(row, col)), c1, c2)
            }
            val deleted = cells1
              .drop(cells2.length)
              .map(c => at(loc, PathStep.Cell(row, cells2.length)) -> BlocksDeleted(c))
            val inserted = cells2.zipWithIndex
              .drop(cells1.length)
              .map((c, col) => at(loc, PathStep.Cell(row, col)) -> BlocksInserted(c))
            paired ++ deleted ++ inserted
          }
      case (B.ClauseRef(k1, v1), B.ClauseRef(k2, v2)) =>
        Option.when(k1 != k2 || v1 != v2)(loc -> ClauseChanged(k1, v1, k2, v2)).toList
      case (B.Conditional(c1, t1, o1), B.Conditional(c2, t2, o2)) =>
        Option.when(c1 != c2)(loc -> ConditionChanged(c1, c2)).toList ++
          blocks(at(loc, PathStep.Then), t1, t2) ++ blocks(at(loc, PathStep.Otherwise), o1, o2)
      case (B.Repeat(c1, n1, b1), B.Repeat(c2, n2, b2)) =>
        Option.when(c1 != c2 || n1 != n2)(loc -> RepeatChanged(c1, n1, c2, n2)).toList ++
          blocks(at(loc, PathStep.Body), b1, b2)
      case (B.PageBreak, B.PageBreak) => Nil
      case _ => List(loc -> BlocksDeleted(List(before)), loc -> BlocksInserted(List(after)))
    end match
  end block

  private def listItems(loc: DeviationLocation, before: List[List[Block]], after: List[List[Block]]): Found =
    items(loc, before, after, PathStep.Item(_))(identity)((n, i1, i2) =>
      blocks(at(loc, PathStep.Item(n)), i1, i2)
    )

  /** Word-level diff of inline content: one text change per hunk of changed words, plus placeholder changes
    * for the placeholders of that hunk.
    */
  private def inlines(loc: DeviationLocation, before: List[Inline], after: List[Inline]): Found =
    val (b, a) = (DeviationDiff.tokens(before), DeviationDiff.tokens(after))
    DeviationDiff.hunks(b, a).flatMap { h =>
      val here       = loc.copy(token = Some(h.newFrom))
      val (old, neu) = (b.slice(h.oldFrom, h.oldUntil), a.slice(h.newFrom, h.newUntil))
      val oldFields  = old.collect { case Token.Field(p) => p }
      val newFields  = neu.collect { case Token.Field(p) => p }
      val oldWords   = old.filterNot(_.isInstanceOf[Token.Field])
      val newWords   = neu.filterNot(_.isInstanceOf[Token.Field])
      val text       =
        if oldWords == newWords then None
        else if oldWords.isEmpty then Some(TextInserted(DeviationDiff.render(neu)))
        else if newWords.isEmpty then Some(TextDeleted(DeviationDiff.render(old)))
        else if DeviationDiff.render(oldWords) == DeviationDiff.render(newWords) then
          Some(FormattingChanged(DeviationDiff.render(newWords)))
        else Some(TextReplaced(DeviationDiff.render(old), DeviationDiff.render(neu)))
      val fields =
        if oldFields == newFields then Nil
        else
          oldFields.zip(newFields).collect { case (o, n) if o != n => PlaceholderChanged(o, n) } ++
            oldFields.drop(newFields.length).map(PlaceholderRemoved(_)) ++
            newFields.drop(oldFields.length).map(PlaceholderInserted(_))
      (text.toList ++ fields).map(here -> _)
    }
end DeviationReport
