package dms.templates.core

import dev.cel.common.ast.CelConstant
import dms.templates.*

import java.time.{Instant, LocalDate, ZoneOffset}
import scala.util.Try

/** Sample values for the test mode of a template. */
final case class TestCase(name: String, values: Map[String, FieldValue])

/** Which outcomes of a conditional the test cases reach. */
final case class BranchCoverage(location: Location, condition: String, covered: Set[Boolean]):
  def complete: Boolean = covered == Set(true, false)

final case class TestSuite(cases: List[TestCase], coverage: List[BranchCoverage]):
  def uncovered: List[BranchCoverage] = coverage.filterNot(_.complete)

/** Generates sample data for the test mode (business concept 4.1): typical values, empty optional fields,
  * long values, and for every conditional values that make it true and values that make it false.
  *
  * Candidate values come from the field types (all selection options, both yes/no values, empty lists) and
  * from the literals the expressions compare with (`contractValue.amount > 250000.0` yields 249999, 250000
  * and 250001). The search per branch is bounded; a branch no data can reach (e.g. `false && x`) is reported
  * as uncovered.
  */
object TestCases:

  private val SampleDate = LocalDate.of(2026, 3, 15)
  private val Currency   = "EUR"

  def generate(
      template: CompiledTemplate,
      language: Language = Language.default,
      maxSearch: Int = 256
  ): TestSuite =
    val variables  = template.schema.variables
    val constants  = template.expressions.values.toList.flatMap(e => Cel.constants(e.ast))
    val candidates = variables.map(v => v.name -> candidatesFor(v, constants)).toMap
    val typical    = variables.map(v => v.name -> sample(v.valueType)).toMap

    def outcomes(values: Map[String, FieldValue]): Map[Location, Set[Boolean]] =
      Resolver.resolve(template, values, language) match
        case Right(r) => r.branches.map(b => b.location -> b.outcomes).toMap
        case Left(_)  => Map.empty

    var cases                                                      = List(TestCase("typical values", typical))
    var coverage                                                   = outcomes(typical)
    def add(c: TestCase, found: Map[Location, Set[Boolean]]): Unit =
      cases = cases :+ c
      coverage = (coverage.keySet ++ found.keySet)
        .map(k => k -> (coverage.getOrElse(k, Set.empty) ++ found.getOrElse(k, Set.empty)))
        .toMap

    val optional = variables.filterNot(_.required).map(_.name)
    if optional.nonEmpty then
      val empty = typical -- optional
      add(TestCase("empty optional fields", empty), outcomes(empty))
    if variables.nonEmpty then
      val long = variables.map(v => v.name -> longValue(v.valueType)).toMap
      add(TestCase("long values", long), outcomes(long))

    val computedIds = template.computed.map((f, e) => f.key -> e.identifiers).toMap
    def inputs(ids: Set[String], seen: Set[String] = Set.empty): Set[String] =
      ids
        .flatMap { id =>
          if seen.contains(id) then Set.empty
          else computedIds.get(id).fold(Set(id))(inner => inputs(inner, seen + id))
        }
        .filter(candidates.contains)

    template.conditionals.foreach { info =>
      val location = info.expression.location
      List(true, false).foreach { wanted =>
        if !coverage.getOrElse(location, Set.empty).contains(wanted) then
          val relevant =
            inputs(info.expression.identifiers ++ info.enclosingRepeats.flatMap(_.identifiers)).toList.sorted
          combinations(relevant.map(n => n -> candidates(n)))
            .take(maxSearch)
            .map(overrides => applyOverrides(typical, overrides))
            .map(values => (values, outcomes(values)))
            .find((_, found) => found.getOrElse(location, Set.empty).contains(wanted))
            .foreach((values, found) =>
              add(TestCase(s"${location.show}: condition is $wanted", values), found)
            )
      }
    }

    TestSuite(
      cases,
      template.conditionals.map(i =>
        BranchCoverage(
          i.expression.location,
          i.expression.source,
          coverage.getOrElse(i.expression.location, Set.empty)
        )
      )
    )

  private def applyOverrides(base: Map[String, FieldValue], overrides: List[(String, Option[FieldValue])]) =
    overrides.foldLeft(base) {
      case (values, (name, Some(v))) => values + (name -> v)
      case (values, (name, None))    => values - name
    }

  /** All combinations of the candidates, lazily, varying the last variable fastest. */
  private def combinations(
      options: List[(String, List[Option[FieldValue]])]
  ): LazyList[List[(String, Option[FieldValue])]] =
    options match
      case Nil                => LazyList(Nil)
      case (name, vs) :: rest =>
        LazyList.from(vs).flatMap(v => combinations(rest).map((name -> v) :: _))

  private def candidatesFor(v: Variable, constants: List[CelConstant]): List[Option[FieldValue]] =
    val values  = (sample(v.valueType) :: candidateValues(v.valueType, constants)).distinct
    val missing = if v.required then Nil else List(None)
    values.map(Some(_)) ++ missing

  private def numbers(constants: List[CelConstant]): List[BigDecimal] =
    constants
      .flatMap { c =>
        c.getKind match
          case CelConstant.Kind.INT64_VALUE  => List(BigDecimal(c.int64Value))
          case CelConstant.Kind.UINT64_VALUE => List(BigDecimal(c.uint64Value.bigIntegerValue))
          case CelConstant.Kind.DOUBLE_VALUE => Try(BigDecimal(c.doubleValue)).toOption.toList
          case _                             => Nil
      }
      .flatMap(n => List(n, n - 1, n + 1))
      .distinct

  private def strings(constants: List[CelConstant]): List[String] =
    constants.collect { case c if c.getKind == CelConstant.Kind.STRING_VALUE => c.stringValue }.distinct

  private def dates(constants: List[CelConstant]): List[LocalDate] =
    strings(constants)
      .flatMap(s => Try(LocalDate.ofInstant(Instant.parse(s), ZoneOffset.UTC)).toOption)
      .flatMap(d => List(d, d.minusDays(1), d.plusDays(1)))
      .distinct

  private def candidateValues(t: ValueType, constants: List[CelConstant]): List[FieldValue] = t match
    case ValueType.Text | ValueType.PersonRef | ValueType.DocumentRef =>
      strings(constants).map(FieldValue.Text(_)) :+ FieldValue.Text("")
    case ValueType.Selection(choices) =>
      if choices.isEmpty then strings(constants).map(FieldValue.Text(_))
      else choices.map(c => FieldValue.Text(c.code))
    case ValueType.Number => (numbers(constants) :+ BigDecimal(0)).map(FieldValue.Number(_))
    case ValueType.Amount =>
      val currencies = strings(constants).filter(_.matches("[A-Z]{3}"))
      (numbers(constants) :+ BigDecimal(0)).map(FieldValue.Amount(_, Currency)) ++
        currencies.map(FieldValue.Amount(BigDecimal(1500), _))
    case ValueType.Date =>
      (dates(constants) ++ List(LocalDate.of(2000, 1, 1), LocalDate.of(2099, 12, 31))).map(FieldValue.Date(_))
    case ValueType.YesNo     => List(FieldValue.YesNo(true), FieldValue.YesNo(false))
    case ValueType.ListOf(e) =>
      val elements = (sample(e) :: candidateValues(e, constants)).distinct
      List(FieldValue.ListOf(Nil), FieldValue.ListOf(elements)) ++ elements
        .take(6)
        .map(x => FieldValue.ListOf(List(x)))

  /** A typical value of a type. */
  def sample(t: ValueType): FieldValue = t match
    case ValueType.Text         => FieldValue.Text("Sample text")
    case ValueType.PersonRef    => FieldValue.Text("user-1")
    case ValueType.DocumentRef  => FieldValue.Text("00000000-0000-0000-0000-000000000001")
    case ValueType.Selection(c) => FieldValue.Text(c.headOption.fold("option")(_.code))
    case ValueType.Number       => FieldValue.Number(BigDecimal(1500))
    case ValueType.Amount       => FieldValue.Amount(BigDecimal("1500.00"), Currency)
    case ValueType.Date         => FieldValue.Date(SampleDate)
    case ValueType.YesNo        => FieldValue.YesNo(true)
    case ValueType.ListOf(e)    => FieldValue.ListOf(List(sample(e), sample(e)))

  /** A long value, to check line breaks and table widths. */
  private def longValue(t: ValueType): FieldValue = t match
    case ValueType.Text | ValueType.PersonRef | ValueType.DocumentRef =>
      FieldValue.Text(("A long value that tests wrapping and table widths. " * 8).trim)
    case ValueType.Selection(c) => FieldValue.Text(c.maxByOption(_.label.length).fold("option")(_.code))
    case ValueType.Number       => FieldValue.Number(BigDecimal("1234567890.12"))
    case ValueType.Amount       => FieldValue.Amount(BigDecimal("1234567890.12"), Currency)
    case ValueType.Date         => FieldValue.Date(LocalDate.of(2099, 12, 31))
    case ValueType.YesNo        => FieldValue.YesNo(false)
    case ValueType.ListOf(e)    => FieldValue.ListOf(List.fill(25)(longValue(e)))
