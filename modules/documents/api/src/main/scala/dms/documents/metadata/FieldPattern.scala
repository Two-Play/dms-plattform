package dms.documents.metadata

import com.google.re2j.Pattern as Re2Pattern

import java.util.regex.{Pattern as JavaPattern, PatternSyntaxException}
import scala.util.Try

/** Tenant-defined formats of text fields (DMS-160, ADR 0076). They run in a transaction on whatever a user
  * enters, so a format must not be able to hold a thread: they run on RE2, whose time grows with the size of
  * the pattern times the length of the value and never with backtracking. RE2 expands counted repetition, so
  * the size is limited as well: `(.{1,1000}){1,1000}` is valid RE2 and took 22 s on 1000 characters.
  *
  * Formats saved before this rule may need what RE2 lacks (lookaround, backreferences). Documents stay on
  * their profile version, so these formats keep their meaning on `java.util.regex`, with a limit on the
  * steps.
  */
object FieldPattern:

  /** The longest value checked against a format. */
  val MaxValueLength: Int = 1000

  /** The largest pattern, in characters with counted repetition expanded. Size and value length together keep
    * the worst case of one value below about 50 ms.
    */
  val MaxSize: Int = 2000

  /** Characters a format saved before these rules may read for one value; far more than a sensible format
    * needs, and a few milliseconds of work.
    */
  private val MaxSteps = 1000000L

  /** Why a format cannot be saved, if it cannot. It must mean the same on both engines. */
  def problem(pattern: String): Option[String] =
    val shape = Shape.of(pattern)
    if shape.size > MaxSize then
      Some(
        s"invalid pattern: it is too large, at most $MaxSize characters with repetitions such as {100} " +
          "counted out are allowed"
      )
    else if shape.intersection then
      Some("invalid pattern: intersections of character classes (&&) are not supported")
    else
      syntaxError(pattern).orElse(
        Try(Re2Pattern.compile(pattern)).failed.toOption.map(e =>
          "invalid pattern: lookaround, backreferences, possessive quantifiers and some character classes " +
            s"are not supported (${e.getMessage})"
        )
      )

  private def syntaxError(pattern: String): Option[String] =
    try
      JavaPattern.compile(pattern)
      None
    catch case e: PatternSyntaxException => Some(s"invalid pattern: ${e.getDescription}")

  /** Whether the whole `value` matches. Never throws: a format that is too hard to evaluate does not match.
    */
  def matches(pattern: String, value: String): Boolean =
    val shape = Shape.of(pattern)
    val re2   =
      if shape.intersection || shape.size > MaxSize then None else Try(Re2Pattern.compile(pattern)).toOption
    re2.fold(matchesWithLimit(pattern, value))(_.matcher(value).matches())

  private def matchesWithLimit(pattern: String, value: String): Boolean =
    try JavaPattern.compile(pattern).matcher(Limited(value, Array(MaxSteps))).matches()
    catch
      case _: StepsExceeded | _: StackOverflowError => false
      // Saving checks the syntax, so this is a format nobody could have entered: it cannot fail a document.
      case _: PatternSyntaxException => true

  private final class StepsExceeded extends RuntimeException(null, null, false, false)

  /** A text that stops the matcher reading it after a number of characters, shared with its parts. */
  private final class Limited(text: CharSequence, steps: Array[Long]) extends CharSequence:
    def length: Int                                     = text.length
    def subSequence(start: Int, end: Int): CharSequence = Limited(text.subSequence(start, end), steps)
    def charAt(index: Int): Char                        =
      steps(0) -= 1
      if steps(0) < 0 then throw StepsExceeded()
      text.charAt(index)
    override def toString: String = text.toString

  /** @param size
    *   characters of the pattern with counted repetition expanded, stopping just above [[MaxSize]]
    * @param intersection
    *   a character class uses `&&`, which java.util.regex reads as intersection and RE2 as characters
    */
  private final case class Shape(size: Long, intersection: Boolean)

  private object Shape:
    private val Repeat = """\{(\d{1,9})(,(\d{0,9}))?\}""".r
    private val Cap    = MaxSize + 1L

    def of(pattern: String): Shape =
      var open                   = List(0L) // size of each open group, innermost first
      var last                   = 0L       // size of what a repetition would apply to
      var intersection           = false
      var i                      = 0
      def atom(size: Long): Unit =
        open = (open.head + size).min(Cap) :: open.tail
        last = size
      def endOfClass(start: Int): Int =
        var j     = start + 1
        var depth = 1
        if j < pattern.length && pattern(j) == '^' then j += 1
        if j < pattern.length && pattern(j) == ']' then j += 1
        while j < pattern.length && depth > 0 do
          pattern(j) match
            case '\\'                               => j += 1
            case '['                                => depth += 1
            case ']'                                => depth -= 1
            case '&' if pattern.startsWith("&&", j) => intersection = true
            case _                                  => ()
          j += 1
        j
      while i < pattern.length do
        pattern(i) match
          case '\\' if pattern.startsWith("\\Q", i) =>
            val end = pattern.indexOf("\\E", i) match
              case -1 => pattern.length
              case e  => e
            atom((end - i - 2).max(1).toLong)
            i = end + 2
          case '\\' =>
            atom(1)
            i += 2
          case '[' =>
            atom(1)
            i = endOfClass(i)
          case '(' =>
            open = 1L :: open
            i += 1
          case ')' =>
            val group = open.head
            open = open.drop(1) match
              case Nil  => List(0L)
              case rest => rest
            atom(group)
            i += 1
          case '{' =>
            Repeat.findPrefixMatchOf(pattern.substring(i)) match
              case Some(m) =>
                val least  = m.group(1).toLong
                val most   = Option(m.group(3)).filter(_.nonEmpty).map(_.toLong)
                val copies = most.getOrElse(if m.group(2) == null then least else least + 1).max(1L)
                val total  = (last * copies).min(Cap)
                open = (open.head + total - last).min(Cap) :: open.tail
                last = total
                i += m.end
              case None =>
                atom(1)
                i += 1
          case _ =>
            atom(1)
            i += 1
      Shape(open.foldLeft(0L)(_ + _), intersection)
