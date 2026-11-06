package dms.documents.metadata

import zio.test.*

/** Field formats a tenant defines (DMS-160, ADR 0076). */
object FieldPatternSpec extends ZIOSpecDefault:

  private def millis[A](f: => A): (A, Long) =
    val start = System.nanoTime()
    val value = f
    (value, (System.nanoTime() - start) / 1000000)

  private val refused = suite("saving a format")(
    test("usual formats are accepted") {
      val usual = List(
        """\d+\.\d+""",
        "[A-Z]{2,3}-[0-9]{4}",
        "[a-z]{2}(-[A-Z]{2})?",
        "(?i)vertrag|nda",
        """\p{L}+""",
        """[\w.+-]+@[\w-]+\.[a-z]{2,}""",
        ".{1,1000}"
      )
      assertTrue(usual.flatMap(FieldPattern.problem).isEmpty)
    },
    test("what RE2 lacks is refused with a message that says so") {
      val lacking = List("(?=a)a", "(?!a)b", "(?<=a)b", """(a)\1""", "a++", """\p{Alpha}+""", "(?x)a b")
      assertTrue(lacking.forall(FieldPattern.problem(_).exists(_.contains("not supported"))))
    },
    test("an intersection is refused: RE2 would read it as plain characters without saying so") {
      assertTrue(
        FieldPattern.problem("[a-z&&[^b]]+").exists(_.contains("&&")),
        FieldPattern.problem("[&]+").isEmpty,
        FieldPattern.problem("a&&b").isEmpty
      )
    },
    test("repetition that expands beyond the size limit is refused, however short the pattern") {
      assertTrue(
        FieldPattern.problem("(.{1,1000}){1,1000}").exists(_.contains("too large")),
        FieldPattern.problem("((a{1,100}){1,100}){1,100}").exists(_.contains("too large")),
        FieldPattern.problem("(a{1000})(b{1000})(c{1000})").exists(_.contains("too large")),
        FieldPattern.problem("(a{900,})(b{900,})").isEmpty,
        FieldPattern.problem("a" * 2001).exists(_.contains("too large")),
        FieldPattern.problem("(" * 5000).exists(_.contains("too large")),
        FieldPattern.problem("""\Q{1000}\E""").isEmpty
      )
    },
    test("syntax errors stay what they were") {
      assertTrue(FieldPattern.problem("[unclosed").exists(_.startsWith("invalid pattern: ")))
    }
  )

  private val matching = suite("checking a value")(
    test("the whole value must match, as before") {
      assertTrue(
        FieldPattern.matches("""\d+\.\d+""", "1.0"),
        !FieldPattern.matches("""\d+\.\d+""", "v1.0"),
        !FieldPattern.matches("""\d+\.\d+""", "1.0 "),
        // an earlier alternative must not end the match early
        FieldPattern.matches("a|ab", "ab"),
        FieldPattern.matches("(?i)nda", "NDA")
      )
    },
    test("catastrophic backtracking does not happen: 4.4 s on java.util.regex at 30 characters") {
      val (matched, took) = millis(FieldPattern.matches("(.*a){12}b", "a" * 30))
      assertTrue(!matched, took < 1000L)
    },
    test("the worst pattern that can be saved stays fast on the longest value that is checked") {
      val worst     = ".{1,1000}.{1,900}"
      val _         = FieldPattern.matches(worst, "a" * 10) // loads the engine
      val (_, took) = millis(FieldPattern.matches(worst, "a" * FieldPattern.MaxValueLength))
      assertTrue(FieldPattern.problem(worst).isEmpty, took < 2000L)
    },
    test("a format saved before these rules keeps its meaning") {
      assertTrue(
        FieldPattern.matches("(?=A)[A-Z]+", "ABC"),
        !FieldPattern.matches("(?=A)[A-Z]+", "BCD"),
        FieldPattern.matches("""(a|b)\1""", "aa"),
        !FieldPattern.matches("""(a|b)\1""", "ab"),
        FieldPattern.matches("[a-z&&[^b]]+", "ac"),
        !FieldPattern.matches("[a-z&&[^b]]+", "ab")
      )
    },
    test("a format saved before these rules cannot hold a thread: it stops and does not match") {
      val (backtracking, took) = millis(FieldPattern.matches("(?=a)(.*a){12}b", "a" * 30))
      val (oversized, took2)   = millis(FieldPattern.matches("(.{1,1000}){1,1000}b", "a" * 1000))
      assertTrue(!backtracking, took < 2000L, !oversized, took2 < 2000L)
    },
    test("a format nobody could have entered does not throw") {
      assertTrue(FieldPattern.matches("(?=a", "a"))
    }
  )

  def spec = suite("Field formats")(refused, matching)
