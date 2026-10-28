package dms.templates

import dms.templates.DeviationDiff.{Aligned, Hunk}
import zio.test.*

object DeviationDiffSpec extends ZIOSpecDefault:
  private def words(s: String): IndexedSeq[String] = s.split(" ").toIndexedSeq.filter(_.nonEmpty)

  /** Applies the hunks to `before`; must yield `after`. */
  private def patch(
      before: IndexedSeq[String],
      after: IndexedSeq[String],
      hunks: List[Hunk]
  ): IndexedSeq[String] =
    val (out, last) = hunks.foldLeft((IndexedSeq.empty[String], 0)) { case ((acc, from), h) =>
      (acc ++ before.slice(from, h.oldFrom) ++ after.slice(h.newFrom, h.newUntil), h.oldUntil)
    }
    out ++ before.drop(last)

  private val gen = Gen.listOfBounded(0, 12)(Gen.elements("a", "b", "c", "d")).map(_.toIndexedSeq)

  def spec = suite("DeviationDiff")(
    test("identical sequences have no hunks") {
      assertTrue(DeviationDiff.hunks(words("a b c"), words("a b c")).isEmpty)
    },
    test("hunks are minimal for insertion, deletion and replacement") {
      assertTrue(
        DeviationDiff.hunks(words("a b c"), words("a x b c")) == List(Hunk(1, 1, 1, 2)),
        DeviationDiff.hunks(words("a b c"), words("a c")) == List(Hunk(1, 2, 1, 1)),
        DeviationDiff.hunks(words("a b c d"), words("a x y d")) == List(Hunk(1, 3, 1, 3)),
        DeviationDiff.hunks(Vector.empty[String], words("a")) == List(Hunk(0, 0, 0, 1))
      )
    },
    test("applying the hunks turns before into after and unchanged elements form an LCS") {
      check(gen, gen) { (before, after) =>
        val hunks     = DeviationDiff.hunks(before, after)
        val unchanged = before.length - hunks.map(h => h.oldUntil - h.oldFrom).sum
        assertTrue(patch(before, after, hunks) == after, unchanged == lcsLength(before, after))
      }
    },
    test("align pairs pairable elements of a hunk and reports the rest as deleted or inserted") {
      assertTrue(
        DeviationDiff.align(words("a b c"), words("a B x c"))((x, y) => x.equalsIgnoreCase(y)) ==
          List(Aligned.Paired(1, 1), Aligned.Inserted(2)),
        DeviationDiff.align(words("a b c"), words("a c"))((_, _) => true) == List(Aligned.Deleted(1, 1))
      )
    },
    test("tokens ignore whitespace and keep placeholders and line breaks") {
      val inlines =
        List(Inline.Text("  Hello   world "), Inline.Placeholder("name"), Inline.LineBreak, Inline.Text("x"))
      val tokens = DeviationDiff.tokens(inlines)
      assertTrue(tokens.size == 5, DeviationDiff.render(tokens) == "Hello world {{name}}\nx")
    }
  )

  private def lcsLength(a: IndexedSeq[String], b: IndexedSeq[String]): Int =
    val t = Array.ofDim[Int](a.length + 1, b.length + 1)
    for i <- a.indices; j <- b.indices do
      t(i + 1)(j + 1) = if a(i) == b(j) then t(i)(j) + 1 else math.max(t(i)(j + 1), t(i + 1)(j))
    t(a.length)(b.length)
end DeviationDiffSpec
