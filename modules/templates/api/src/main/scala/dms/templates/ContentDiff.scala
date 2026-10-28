package dms.templates

import dms.templates.DeviationDiff.{Aligned, Token}

/** How a part of the content changed from the earlier to the later content. */
enum DiffStatus:
  case Unchanged, Inserted, Deleted, Changed

/** Whether a run of words is in both contents, only in the later one or only in the earlier one. */
enum SpanKind:
  case Equal, Inserted, Deleted

/** A run of words of one kind, rendered like the deviation report: words separated by single spaces,
  * placeholders as `{{path}}` or `{{path|format}}`, line breaks as `\n`.
  */
final case class TextSpan(kind: SpanKind, text: String)

/** A change of a block that is not a change of its words. Clauses are shown as `key v2`, repeats as
  * `item in items`.
  */
enum StructureChange:
  case HeadingLevel(from: Int, to: Int)

  /** Same words, different marks (bold, italic, underline). */
  case Formatting
  case Condition(from: String, to: String)
  case Repeat(from: String, to: String)
  case Clause(from: String, to: String)
  case HeaderRows(from: Int, to: Int)

/** One block of the compared content in document order.
  *
  * Headings and paragraphs carry their words; lists and tables are not entries of their own, their content
  * appears as blocks with an `Item` or `Cell` step in the path. Clause references, conditions and repeats are
  * entries with a description (`key v2`, the condition, `item in items`) as their text, followed by the
  * blocks inside them.
  *
  * @param path
  *   the position in the later content; for deleted blocks the position where they were removed
  * @param kind
  *   Heading, Paragraph, ClauseRef, Conditional, Repeat or PageBreak
  * @param level
  *   the heading level in the later content (the earlier one for a deleted heading)
  */
final case class BlockDiff(
    path: List[PathStep],
    kind: String,
    status: DiffStatus,
    spans: List[TextSpan],
    changes: List[StructureChange] = Nil,
    level: Option[Int] = None
)

/** One section of the compared content. Sections are matched by id.
  *
  * @param moved
  *   the section exists in both contents but at another position among the sections
  */
final case class SectionDiff(
    id: SectionId,
    titleBefore: Option[String],
    titleAfter: Option[String],
    status: DiffStatus,
    moved: Boolean,
    lockedBefore: Option[Boolean],
    lockedAfter: Option[Boolean],
    blocks: List[BlockDiff]
)

/** The comparison of two contents for readers (US-05.1, DMS-54): every section and block of both contents in
  * the order of the later one, with word-level insertions and deletions and structural changes.
  *
  * Unlike the [[DeviationReport]], which lists only the changes of locked sections against the template, this
  * covers all sections and keeps unchanged text, so that a reader sees the changes in context.
  */
final case class ContentDiff(sections: List[SectionDiff]):
  def changed: Boolean = sections.exists(_.status != DiffStatus.Unchanged)

  private def words(kind: SpanKind): Int =
    sections
      .flatMap(_.blocks)
      .flatMap(_.spans)
      .filter(_.kind == kind)
      .map(_.text.split("\\s+").count(_.nonEmpty))
      .sum

  /** Number of inserted words, placeholders and line breaks. */
  def insertedWords: Int = words(SpanKind.Inserted)

  /** Number of deleted words, placeholders and line breaks. */
  def deletedWords: Int = words(SpanKind.Deleted)

object ContentDiff:

  def compare(earlier: TemplateContent, later: TemplateContent): ContentDiff =
    val before    = earlier.sections.toIndexedSeq
    val after     = later.sections.toIndexedSeq
    val beforeIds = before.map(_.id).toSet
    val afterIds  = after.map(_.id).toSet
    // Sections whose id is on both sides but not paired in place moved: they are compared at their new position.
    val sections = DeviationDiff.alignAll(before.map(_.id), after.map(_.id))((_, _) => false).flatMap {
      case Aligned.Paired(o, n) => List(section(Some(before(o)), Some(after(n)), false))
      case Aligned.Deleted(o, _) if afterIds.contains(before(o).id) => Nil
      case Aligned.Deleted(o, _) => List(section(Some(before(o)), None, false))
      case Aligned.Inserted(n) if beforeIds.contains(after(n).id) =>
        List(section(before.find(_.id == after(n).id), Some(after(n)), true))
      case Aligned.Inserted(n) => List(section(None, Some(after(n)), false))
    }
    ContentDiff(sections)

  private def section(before: Option[Section], after: Option[Section], moved: Boolean): SectionDiff =
    val id     = after.orElse(before).map(_.id).getOrElse(SectionId(""))
    val blocks = (before, after) match
      case (Some(b), Some(a)) => this.blocks(Nil, b.blocks, a.blocks)
      case (Some(b), None)    =>
        b.blocks.zipWithIndex.flatMap((x, i) => whole(List(PathStep.Block(i)), x, SpanKind.Deleted))
      case (None, Some(a)) =>
        a.blocks.zipWithIndex.flatMap((x, i) => whole(List(PathStep.Block(i)), x, SpanKind.Inserted))
      case (None, None) => Nil
    val status = (before, after) match
      case (Some(_), None) => DiffStatus.Deleted
      case (None, Some(_)) => DiffStatus.Inserted
      case (b, a)          =>
        val same = !moved && b.map(_.title) == a.map(_.title) && b.map(_.locked) == a.map(_.locked) &&
          blocks.forall(_.status == DiffStatus.Unchanged)
        if same then DiffStatus.Unchanged else DiffStatus.Changed
    SectionDiff(
      id,
      before.flatMap(_.title),
      after.flatMap(_.title),
      status,
      moved,
      before.map(_.locked),
      after.map(_.locked),
      blocks
    )
  end section

  private def blocks(path: List[PathStep], before: List[Block], after: List[Block]): List[BlockDiff] =
    val (b, a) = (before.toIndexedSeq, after.toIndexedSeq)
    DeviationDiff.alignAll(b, a)(_.ordinal == _.ordinal).flatMap {
      case Aligned.Paired(o, n)  => block(path :+ PathStep.Block(n), b(o), a(n))
      case Aligned.Deleted(o, n) => whole(path :+ PathStep.Block(n), b(o), SpanKind.Deleted)
      case Aligned.Inserted(n)   => whole(path :+ PathStep.Block(n), a(n), SpanKind.Inserted)
    }

  /** Items (list items, table rows) paired by position after the equal ones; unpaired items are whole. */
  private def items[A](path: List[PathStep], before: List[A], after: List[A], step: Int => PathStep)(
      content: (List[PathStep], A, SpanKind) => List[BlockDiff]
  )(inner: (Int, A, A) => List[BlockDiff]): List[BlockDiff] =
    val (b, a) = (before.toIndexedSeq, after.toIndexedSeq)
    DeviationDiff.alignAll(b, a)((_, _) => true).flatMap {
      case Aligned.Paired(o, n)  => inner(n, b(o), a(n))
      case Aligned.Deleted(o, n) => content(path :+ step(n), b(o), SpanKind.Deleted)
      case Aligned.Inserted(n)   => content(path :+ step(n), a(n), SpanKind.Inserted)
    }

  private def wholeBlocks(path: List[PathStep], blocks: List[Block], kind: SpanKind): List[BlockDiff] =
    blocks.zipWithIndex.flatMap((b, i) => whole(path :+ PathStep.Block(i), b, kind))

  private def wholeCells(path: List[PathStep], cells: List[List[Block]], kind: SpanKind): List[BlockDiff] =
    path.lastOption match
      case Some(PathStep.Row(row)) =>
        cells.zipWithIndex.flatMap((c, col) => wholeBlocks(path.init :+ PathStep.Cell(row, col), c, kind))
      case _ => cells.flatMap(wholeBlocks(path, _, kind))

  private def statusOf(kind: SpanKind): DiffStatus = kind match
    case SpanKind.Equal    => DiffStatus.Unchanged
    case SpanKind.Inserted => DiffStatus.Inserted
    case SpanKind.Deleted  => DiffStatus.Deleted

  private def spans(kind: SpanKind, text: String): List[TextSpan] =
    if text.isEmpty then Nil else List(TextSpan(kind, text))

  /** A block that is only in one of the contents, with everything inside it. */
  private def whole(path: List[PathStep], block: Block, kind: SpanKind): List[BlockDiff] =
    val status = statusOf(kind)
    block match
      case Block.Heading(level, content) =>
        List(BlockDiff(path, "Heading", status, spans(kind, render(content)), level = Some(level)))
      case Block.Paragraph(content) =>
        List(BlockDiff(path, "Paragraph", status, spans(kind, render(content))))
      case Block.BulletList(items) =>
        items.zipWithIndex.flatMap((item, i) => wholeBlocks(path :+ PathStep.Item(i), item, kind))
      case Block.OrderedList(items) =>
        items.zipWithIndex.flatMap((item, i) => wholeBlocks(path :+ PathStep.Item(i), item, kind))
      case Block.Table(rows, _) =>
        rows.zipWithIndex.flatMap((cells, r) =>
          cells.zipWithIndex.flatMap((c, col) => wholeBlocks(path :+ PathStep.Cell(r, col), c, kind))
        )
      case Block.ClauseRef(key, version) =>
        List(BlockDiff(path, "ClauseRef", status, spans(kind, clause(key, version))))
      case Block.Conditional(condition, body, otherwise) =>
        BlockDiff(path, "Conditional", status, spans(kind, condition)) ::
          (wholeBlocks(path :+ PathStep.Then, body, kind) ++ wholeBlocks(
            path :+ PathStep.Otherwise,
            otherwise,
            kind
          ))
      case Block.Repeat(collection, item, body) =>
        BlockDiff(path, "Repeat", status, spans(kind, repeat(collection, item))) ::
          wholeBlocks(path :+ PathStep.Body, body, kind)
      case Block.PageBreak => List(BlockDiff(path, "PageBreak", status, Nil))
    end match
  end whole

  /** Two blocks of the same kind at matching positions. */
  private def block(path: List[PathStep], before: Block, after: Block): List[BlockDiff] =
    import dms.templates.Block as B
    def described(kind: String, b: String, a: String, change: StructureChange) =
      if b == a then BlockDiff(path, kind, DiffStatus.Unchanged, spans(SpanKind.Equal, a))
      else
        BlockDiff(
          path,
          kind,
          DiffStatus.Changed,
          spans(SpanKind.Deleted, b) ++ spans(SpanKind.Inserted, a),
          List(change)
        )
    (before, after) match
      case (B.Heading(l1, c1), B.Heading(l2, c2)) =>
        List(
          text(
            path,
            "Heading",
            c1,
            c2,
            Option.when(l1 != l2)(StructureChange.HeadingLevel(l1, l2)).toList,
            Some(l2)
          )
        )
      case (B.Paragraph(c1), B.Paragraph(c2))     => List(text(path, "Paragraph", c1, c2, Nil, None))
      case (B.BulletList(i1), B.BulletList(i2))   => listItems(path, i1, i2)
      case (B.OrderedList(i1), B.OrderedList(i2)) => listItems(path, i1, i2)
      case (B.Table(r1, h1), B.Table(r2, h2))     =>
        val header =
          Option
            .when(h1 != h2)(
              BlockDiff(path, "Table", DiffStatus.Changed, Nil, List(StructureChange.HeaderRows(h1, h2)))
            )
            .toList
        header ++ items(path, r1, r2, PathStep.Row(_))(wholeCells) { (row, cells1, cells2) =>
          val paired = cells1.zip(cells2).zipWithIndex.flatMap { case ((c1, c2), col) =>
            blocks(path :+ PathStep.Cell(row, col), c1, c2)
          }
          val deleted = cells1.zipWithIndex
            .drop(cells2.length)
            .flatMap((c, col) => wholeBlocks(path :+ PathStep.Cell(row, col), c, SpanKind.Deleted))
          val inserted = cells2.zipWithIndex
            .drop(cells1.length)
            .flatMap((c, col) => wholeBlocks(path :+ PathStep.Cell(row, col), c, SpanKind.Inserted))
          paired ++ deleted ++ inserted
        }
      case (B.ClauseRef(k1, v1), B.ClauseRef(k2, v2)) =>
        List(
          described(
            "ClauseRef",
            clause(k1, v1),
            clause(k2, v2),
            StructureChange.Clause(clause(k1, v1), clause(k2, v2))
          )
        )
      case (B.Conditional(c1, t1, o1), B.Conditional(c2, t2, o2)) =>
        described("Conditional", c1, c2, StructureChange.Condition(c1, c2)) ::
          (blocks(path :+ PathStep.Then, t1, t2) ++ blocks(path :+ PathStep.Otherwise, o1, o2))
      case (B.Repeat(c1, n1, b1), B.Repeat(c2, n2, b2)) =>
        val (r1, r2) = (repeat(c1, n1), repeat(c2, n2))
        described("Repeat", r1, r2, StructureChange.Repeat(r1, r2)) :: blocks(path :+ PathStep.Body, b1, b2)
      case (B.PageBreak, B.PageBreak) => List(BlockDiff(path, "PageBreak", DiffStatus.Unchanged, Nil))
      case _ => whole(path, before, SpanKind.Deleted) ++ whole(path, after, SpanKind.Inserted)
    end match
  end block

  private def listItems(path: List[PathStep], before: List[List[Block]], after: List[List[Block]]) =
    items(path, before, after, PathStep.Item(_))(wholeBlocks)((n, i1, i2) =>
      blocks(path :+ PathStep.Item(n), i1, i2)
    )

  /** Word-level comparison of inline content; marks are compared separately as a formatting change. */
  private def text(
      path: List[PathStep],
      kind: String,
      before: List[Inline],
      after: List[Inline],
      changes: List[StructureChange],
      level: Option[Int]
  ): BlockDiff =
    val (b, a)         = (DeviationDiff.tokens(before), DeviationDiff.tokens(after))
    val (bKeys, aKeys) = (b.map(key), a.map(key))
    // Equal words are shown as in the later content, so a formatting change shows the new formatting.
    val (_, rest, collected) = DeviationDiff.hunks(bKeys, aKeys).foldLeft((0, 0, List.empty[TextSpan])) {
      case ((_, newFrom, acc), h) =>
        (
          h.oldUntil,
          h.newUntil,
          acc ++ spans(SpanKind.Equal, DeviationDiff.render(a.slice(newFrom, h.newFrom))) ++
            spans(SpanKind.Deleted, DeviationDiff.render(b.slice(h.oldFrom, h.oldUntil))) ++
            spans(SpanKind.Inserted, DeviationDiff.render(a.slice(h.newFrom, h.newUntil)))
        )
    }
    val all        = collected ++ spans(SpanKind.Equal, DeviationDiff.render(a.drop(rest)))
    val formatting = Option.when(bKeys == aKeys && b != a)(StructureChange.Formatting).toList
    val allChanges = changes ++ formatting
    val status     =
      if allChanges.isEmpty && all.forall(_.kind == SpanKind.Equal) then DiffStatus.Unchanged
      else DiffStatus.Changed
    BlockDiff(path, kind, status, all, allChanges, level)
  end text

  /** Tokens compared without their marks. */
  private def key(t: Token): String = t match
    case Token.Word(text, _) => s"w:$text"
    case Token.Field(p)      => s"f:${p.path}|${p.format.getOrElse("")}"
    case Token.Break         => "b"

  private def render(content: List[Inline]): String = DeviationDiff.render(DeviationDiff.tokens(content))

  private def clause(key: String, version: Int): String = s"$key v$version"

  private def repeat(collection: String, item: String): String = s"$item in $collection"
end ContentDiff
