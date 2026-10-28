package dms.templates

/** How much of the text of a content changed against another content (DMS-46): the automatic comparison that
  * warns when an editorial (minor) version changes more than a configured share of the text.
  *
  * The text is compared word by word (the tokens of the deviation report: words, placeholders, line breaks,
  * and one token per clause reference with its version) section by section; sections are matched by id, and a
  * section that exists on one side only counts as completely changed. Formatting marks are ignored: an
  * editorial correction of bold or italic text does not change words.
  *
  * @param kept
  *   words both contents share in the same order (longest common subsequence, summed over the sections)
  * @param before
  *   number of words of the earlier content
  * @param after
  *   number of words of the later content
  */
final case class TextChange(kept: Int, before: Int, after: Int):

  /** Share of the text that changed, from 0 (identical) to 1 (nothing kept): the words of the longer content
    * that are not kept. Replacing one word of 100 is 0.01; appending 100 words to 100 is 0.5.
    */
  def ratio: Double =
    val longer = before.max(after)
    if longer == 0 then 0.0 else (longer - kept).toDouble / longer

  def exceeds(threshold: Double): Boolean = ratio > threshold

object TextChange:

  def compare(earlier: TemplateContent, later: TemplateContent): TextChange =
    val before = earlier.sections.map(s => s.id -> words(s.blocks)).toMap
    val after  = later.sections.map(s => s.id -> words(s.blocks)).toMap
    val kept   = before.keySet.intersect(after.keySet).toList.map(id => lcsLength(before(id), after(id))).sum
    TextChange(kept, before.values.map(_.size).sum, after.values.map(_.size).sum)

  /** Words of blocks in document order; nested blocks (lists, tables, conditions) included. */
  private def words(blocks: List[Block]): IndexedSeq[String] =
    TemplateContent
      .blocks(TemplateContent(List(Section(SectionId("_"), None, locked = false, blocks))))
      .toIndexedSeq
      .flatMap {
        case Block.Heading(_, inlines)           => text(inlines)
        case Block.Paragraph(inlines)            => text(inlines)
        case Block.ClauseRef(clauseKey, version) => IndexedSeq(s"clause:$clauseKey@$version")
        case _                                   => IndexedSeq.empty
      }

  private def text(inlines: List[Inline]): IndexedSeq[String] =
    DeviationDiff.tokens(inlines).map {
      case DeviationDiff.Token.Word(t, _) => t
      case DeviationDiff.Token.Field(p)   => s"{{${p.path}${p.format.fold("")("|" + _)}}}"
      case DeviationDiff.Token.Break      => "\n"
    }

  /** Length of the longest common subsequence in O(n·m) time and O(m) memory: a whole document section can
    * have thousands of words, so the full table of [[DeviationDiff]] is not used here.
    */
  private def lcsLength(a: IndexedSeq[String], b: IndexedSeq[String]): Int =
    var previous = Array.fill(b.length + 1)(0)
    var current  = Array.fill(b.length + 1)(0)
    for i <- a.indices do
      for j <- b.indices do
        current(j + 1) =
          if a(i) == b(j) then previous(j) + 1
          else math.max(previous(j + 1), current(j))
      val swap = previous
      previous = current
      current = swap
    previous(b.length)
