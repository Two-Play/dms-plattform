package dms.templates

/** Sequence diff based on the longest common subsequence (LCS), used for words, blocks, list items and table
  * rows of the deviation report. Inputs are small (paragraphs, block lists), so the O(n·m) table is fine and
  * keeps the algorithm obviously correct; no dependency is needed.
  */
private[templates] object DeviationDiff:

  /** A contiguous run of differences: `old[oldFrom, oldUntil)` was replaced by `new[newFrom, newUntil)`. One
    * of the two ranges may be empty (pure insertion or deletion).
    */
  final case class Hunk(oldFrom: Int, oldUntil: Int, newFrom: Int, newUntil: Int):
    def oldRange: Range = oldFrom until oldUntil
    def newRange: Range = newFrom until newUntil

  /** One step of an edit script: indices into `before` (old) and `after` (new). */
  private enum Op:
    case Keep(oldIndex: Int, newIndex: Int)
    case Delete(oldIndex: Int, newIndex: Int)
    case Insert(oldIndex: Int, newIndex: Int)

  /** Edit script from an LCS under `same`; within a gap, deletions come before insertions. */
  private def script[A](before: IndexedSeq[A], after: IndexedSeq[A])(same: (A, A) => Boolean): List[Op] =
    val n = before.length
    val m = after.length
    // lcs(i)(j) = length of the LCS of before.drop(i) and after.drop(j)
    val lcs = Array.ofDim[Int](n + 1, m + 1)
    for
      i <- (n - 1) to 0 by -1
      j <- (m - 1) to 0 by -1
    do
      lcs(i)(j) =
        if same(before(i), after(j)) then lcs(i + 1)(j + 1) + 1
        else math.max(lcs(i + 1)(j), lcs(i)(j + 1))

    val out = List.newBuilder[Op]
    var i   = 0
    var j   = 0
    while i < n || j < m do
      if i < n && j < m && same(before(i), after(j)) then
        out += Op.Keep(i, j)
        i += 1
        j += 1
      else if j >= m || (i < n && lcs(i + 1)(j) >= lcs(i)(j + 1)) then
        out += Op.Delete(i, j)
        i += 1
      else
        out += Op.Insert(i, j)
        j += 1
    out.result()
  end script

  /** The hunks that turn `before` into `after`, in order; elements outside hunks are equal (an LCS). */
  def hunks[A](before: IndexedSeq[A], after: IndexedSeq[A]): List[Hunk] =
    val ops                               = script(before, after)(_ == _)
    def group(rest: List[Op]): List[Hunk] = rest.dropWhile(_.isInstanceOf[Op.Keep]) match
      case Nil                    => Nil
      case changed @ (first :: _) =>
        val (run, tail) = changed.span(!_.isInstanceOf[Op.Keep])
        val (oi, nj)    = first match
          case Op.Keep(o, n)   => (o, n)
          case Op.Delete(o, n) => (o, n)
          case Op.Insert(o, n) => (o, n)
        val deleted  = run.count(_.isInstanceOf[Op.Delete])
        val inserted = run.count(_.isInstanceOf[Op.Insert])
        Hunk(oi, oi + deleted, nj, nj + inserted) :: group(tail)
    group(ops)

  /** Alignment of two sequences: equal elements are matched by the LCS; inside a hunk, elements are paired by
    * a second LCS under `pairable` (paired elements are then compared in depth), the rest count as deleted or
    * inserted. Indices refer to `before` (old) and `after` (new); a deletion carries the position in `after`
    * where the element was removed.
    */
  enum Aligned:
    case Paired(oldIndex: Int, newIndex: Int)
    case Deleted(oldIndex: Int, atNewIndex: Int)
    case Inserted(newIndex: Int)

  def align[A](before: IndexedSeq[A], after: IndexedSeq[A])(pairable: (A, A) => Boolean): List[Aligned] =
    hunks(before, after).flatMap { h =>
      script(before.slice(h.oldFrom, h.oldUntil), after.slice(h.newFrom, h.newUntil))(pairable).map {
        case Op.Keep(o, n)   => Aligned.Paired(h.oldFrom + o, h.newFrom + n)
        case Op.Delete(o, n) => Aligned.Deleted(h.oldFrom + o, h.newFrom + n)
        case Op.Insert(_, n) => Aligned.Inserted(h.newFrom + n)
      }
    }

  /** Word-level token of inline content. Whitespace separates words and is not significant. */
  enum Token:
    case Word(text: String, marks: Set[Mark])
    case Field(placeholder: Inline.Placeholder)
    case Break

  def tokens(content: List[Inline]): IndexedSeq[Token] =
    content.toIndexedSeq.flatMap {
      case Inline.Text(value, marks) =>
        value.split("\\s+").iterator.filter(_.nonEmpty).map(Token.Word(_, marks)).toIndexedSeq
      case p: Inline.Placeholder => IndexedSeq(Token.Field(p))
      case Inline.LineBreak      => IndexedSeq(Token.Break)
    }

  /** Human-readable text of tokens; placeholders appear as `{{path}}` or `{{path|format}}`. */
  def render(ts: Seq[Token]): String =
    ts.map {
      case Token.Word(text, _) => text
      case Token.Field(p)      => s"{{${p.path}${p.format.fold("")("|" + _)}}}"
      case Token.Break         => "\n"
    }.mkString(" ")
      .replace(" \n", "\n")
      .replace("\n ", "\n")
end DeviationDiff
