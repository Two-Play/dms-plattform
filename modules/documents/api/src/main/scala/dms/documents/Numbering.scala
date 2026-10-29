package dms.documents

import java.time.Instant
import java.util.UUID

/** One part of a [[NumberPattern]]. */
enum NumberToken:
  /** Fixed text, e.g. the prefix `POL-`. */
  case Literal(text: String)

  /** `{YYYY}`: the four-digit year of the creation in the tenant's time zone. */
  case Year

  /** `{YY}`: the last two digits of the year. */
  case ShortYear

  /** `{OU}`: the document's organizational unit (see [[NumberPattern.unitSegment]]). */
  case Unit

  /** `{NNNN}`: the counter, zero-padded to at least `digits` digits (1 to 10); longer values are not cut. */
  case Counter(digits: Int)

/** The pattern of a number range (DMS-113, ADR 0040), e.g. `POL-{YYYY}-{NNNN}`: fixed text with the
  * placeholders `{YYYY}`, `{YY}`, `{OU}` and exactly one counter `{N…}` (one to ten `N`, the minimum number
  * of digits).
  */
final case class NumberPattern(tokens: List[NumberToken]):
  import NumberToken.*

  def usesYear: Boolean = tokens.exists(t => t == Year || t == ShortYear)

  def usesUnit: Boolean = tokens.contains(Unit)

  /** The pattern as entered. */
  def show: String = tokens.map {
    case Literal(text)   => text
    case Year            => "{YYYY}"
    case ShortYear       => "{YY}"
    case Unit            => "{OU}"
    case Counter(digits) => "{" + "N" * digits + "}"
  }.mkString

  /** The number for `year`, the organizational unit (needed if the pattern has `{OU}`) and `counter`. Fails
    * if the unit is needed but missing, or the result is not a valid document number.
    */
  def format(year: Int, unit: Option[String], counter: Long): Either[String, String] =
    val segment = unit.map(NumberPattern.unitSegment).filter(_.nonEmpty)
    if usesUnit && segment.isEmpty then Left("the pattern needs an organizational unit")
    else
      val number = tokens.map {
        case Literal(text)   => text
        case Year            => f"${year % 10000}%04d"
        case ShortYear       => f"${year % 100}%02d"
        case Unit            => segment.getOrElse("")
        case Counter(digits) => s"%0${digits}d".format(counter)
      }.mkString
      DocumentNumber.validate(number)

object NumberPattern:
  import NumberToken.*

  /** Longest accepted pattern. */
  val MaxLength = 100

  private val Placeholder = """\{([^{}]*)\}""".r

  private val Known = "{YYYY}, {YY}, {OU} and {N} to {NNNNNNNNNN}"

  /** Parses a pattern; reports every problem. */
  def parse(text: String): Either[List[String], NumberPattern] =
    val input                    = text.trim
    val tokens                   = List.newBuilder[NumberToken]
    val problems                 = List.newBuilder[String]
    var position                 = 0
    def literal(s: String): Unit =
      if s.nonEmpty then
        if s.exists(c => c == '{' || c == '}') then problems += s"braces only enclose placeholders: $Known"
        else
          val invalid = s.filterNot(DocumentNumber.isAllowed).distinct
          if invalid.nonEmpty then
            problems += s"the characters '$invalid' are not allowed; use letters, digits, '.', '_' and '-'"
        tokens += Literal(s)
    Placeholder.findAllMatchIn(input).foreach { m =>
      literal(input.substring(position, m.start))
      m.group(1) match
        case "YYYY"                                                  => tokens += Year
        case "YY"                                                    => tokens += ShortYear
        case "OU"                                                    => tokens += Unit
        case n if n.nonEmpty && n.forall(_ == 'N') && n.length <= 10 => tokens += Counter(n.length)
        case other => problems += s"unknown placeholder {$other}; known: $Known"
      position = m.end
    }
    literal(input.substring(position))
    val parsed   = merge(tokens.result())
    val counters = parsed.count {
      case Counter(_) => true
      case _          => false
    }
    val all = (Option.when(input.isEmpty)("the pattern is required").toList ++
      Option.when(input.length > MaxLength)(s"the pattern must have at most $MaxLength characters").toList ++
      problems.result() ++
      Option
        .when(input.nonEmpty && counters != 1)("the pattern needs exactly one counter, e.g. {NNNN}")
        .toList).distinct
    if all.nonEmpty then Left(all) else Right(NumberPattern(parsed))

  /** Adjacent literals as one. */
  private def merge(tokens: List[NumberToken]): List[NumberToken] =
    tokens.foldRight(List.empty[NumberToken]) {
      case (Literal(a), Literal(b) :: rest) => Literal(a + b) :: rest
      case (t, acc)                         => t :: acc
    }

  /** The organizational unit as part of a number: runs of characters a number cannot contain become `-`,
    * leading and trailing `-` are dropped (`Legal & Compliance` → `Legal-Compliance`).
    */
  def unitSegment(unit: String): String =
    unit.trim
      .map(c => if DocumentNumber.isAllowed(c) then c else '-')
      .replaceAll("-{2,}", "-")
      .stripPrefix("-")
      .stripSuffix("-")

/** Document numbers: unique per tenant, at most 64 characters of letters, digits, `.`, `_` and `-`, starting
  * with a letter or digit, so that they can be used in a URL path (stable address by number).
  */
object DocumentNumber:
  val MaxLength = 64

  def isAllowed(c: Char): Boolean = c.isLetterOrDigit || c == '.' || c == '_' || c == '-'

  def validate(number: String): Either[String, String] =
    if number.isEmpty then Left("the document number is empty")
    else if number.length > MaxLength then
      Left(s"the document number '$number' is longer than $MaxLength characters")
    else if !number.head.isLetterOrDigit then
      Left(s"the document number '$number' must start with a letter or digit")
    else if !number.forall(isAllowed) then
      Left(s"the document number '$number' may only contain letters, digits, '.', '_' and '-'")
    else Right(number)

/** A number range of a tenant (DMS-113, ADR 0040): numbers documents of the classes it is assigned to get on
  * creation.
  *
  * @param key
  *   stable key (lowercase letters, digits and hyphens)
  * @param startValue
  *   the first counter value of a period; raising it raises the next counter value of the current period
  * @param yearlyReset
  *   whether the counter starts again at `startValue` every year (the pattern then needs `{YYYY}` or `{YY}`)
  */
final case class NumberRange(
    id: UUID,
    key: String,
    name: String,
    pattern: NumberPattern,
    startValue: Long,
    yearlyReset: Boolean,
    createdBy: String,
    createdAt: Instant,
    updatedAt: Instant
):
  /** The counter period of `year`: the year with a yearly reset, otherwise one period for ever (0). */
  def period(year: Int): Int = if yearlyReset then year else 0

  /** The counter value used next when the stored counter is `stored` (`None`: the period has no number yet).
    */
  def nextCounter(stored: Option[Long]): Long = stored.fold(startValue)(_.max(startValue))

object NumberRange:
  val KeyPattern = "[a-z0-9][a-z0-9-]{0,62}"

  /** Largest start value. */
  val MaxStart: Long = 999_999_999_999L

  /** Problems of the settings of a range, empty if they are valid. */
  def check(
      key: String,
      name: String,
      pattern: Either[List[String], NumberPattern],
      startValue: Long,
      yearlyReset: Boolean
  ): List[String] =
    Option.when(!key.matches(KeyPattern))(s"the key must match $KeyPattern").toList ++
      Option.when(name.trim.isEmpty)("the name is required").toList ++
      Option.when(name.trim.length > 200)("the name must have at most 200 characters").toList ++
      pattern.left.getOrElse(Nil) ++
      Option
        .when(startValue < 0 || startValue > MaxStart)(s"the start value must be between 0 and $MaxStart")
        .toList ++
      Option
        .when(yearlyReset && pattern.exists(!_.usesYear))(
          "a yearly reset needs the year in the pattern ({YYYY} or {YY}), otherwise numbers would repeat"
        )
        .toList ++
      pattern.toOption
        .flatMap(p => p.format(2026, Some("OU"), startValue).left.toOption)
        .toList
