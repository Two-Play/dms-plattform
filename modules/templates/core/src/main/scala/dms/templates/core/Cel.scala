package dms.templates.core

import dev.cel.common.ast.{CelConstant, CelExpr}
import dev.cel.common.types.{CelKind, CelType, ListType, MapType, SimpleType}
import dev.cel.common.{CelAbstractSyntaxTree, CelOptions}
import dev.cel.compiler.{CelCompiler, CelCompilerFactory}
import dev.cel.parser.CelStandardMacro
import dev.cel.runtime.{CelEvaluationException, CelEvaluationListener, CelRuntime, CelRuntimeFactory}
import dms.templates.*

import java.time.{Instant, LocalDate, ZoneOffset}
import scala.jdk.CollectionConverters.*
import scala.util.control.NoStackTrace

/** The Common Expression Language environment for template expressions (ADR 0020).
  *
  * Expressions are type-checked against the declared variables when a template is saved and evaluated with a
  * step budget and a deadline. CEL is closed: no I/O, no user-defined functions, only the standard library
  * and the standard macros (`has`, `all`, `exists`, `exists_one`, `map`, `filter`).
  */
private[core] object Cel:

  /** Holds the names of the fields that have a value, e.g. `'endDate' in present_fields`. Field keys cannot
    * contain underscores, so the name never clashes with a field.
    */
  val PresentFields = "present_fields"

  def options(limits: Limits): CelOptions =
    CelOptions
      .current()
      .maxExpressionCodePointSize(limits.maxExpressionLength)
      .comprehensionMaxIterations(limits.maxComprehensionIterations)
      .enableHeterogeneousNumericComparisons(true)
      .build()

  def compiler(limits: Limits): CelCompiler =
    CelCompilerFactory
      .standardCelCompilerBuilder()
      .setOptions(options(limits))
      .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
      .addVar(PresentFields, ListType.create(SimpleType.STRING))
      .build()

  def runtime(limits: Limits): CelRuntime =
    CelRuntimeFactory.plannerRuntimeBuilder().setOptions(options(limits)).build()

  def withVar(compiler: CelCompiler, name: String, tpe: CelType): CelCompiler =
    compiler.toCompilerBuilder().addVar(name, tpe).build()

  /** The CEL type of a field: text → string, number → double, amount → map{amount, currency}, date →
    * timestamp, selection → string (the code), yes/no → bool, multiple → list, person and document references
    * → string.
    */
  def typeOf(valueType: ValueType): CelType = valueType match
    case ValueType.Text | ValueType.PersonRef | ValueType.DocumentRef | ValueType.Selection(_) =>
      SimpleType.STRING
    case ValueType.Number    => SimpleType.DOUBLE
    case ValueType.Amount    => MapType.create(SimpleType.STRING, SimpleType.DYN)
    case ValueType.Date      => SimpleType.TIMESTAMP
    case ValueType.YesNo     => SimpleType.BOOL
    case ValueType.ListOf(e) => ListType.create(typeOf(e))

  /** The value type of an expression result, where it has one (for formatting and static checks). */
  def valueTypeOf(tpe: CelType): Option[ValueType] = tpe.kind match
    case CelKind.STRING                              => Some(ValueType.Text)
    case CelKind.DOUBLE | CelKind.INT | CelKind.UINT => Some(ValueType.Number)
    case CelKind.BOOL                                => Some(ValueType.YesNo)
    case CelKind.TIMESTAMP                           => Some(ValueType.Date)
    case CelKind.LIST                                =>
      tpe match
        case l: ListType if l.hasElemType => valueTypeOf(l.elemType).map(ValueType.ListOf(_))
        case _                            => None
    case _ => None

  /** The element type of a list type, or `dyn`. */
  def elementType(tpe: CelType): Option[CelType] = tpe match
    case l: ListType => Some(if l.hasElemType then l.elemType else SimpleType.DYN)
    case _           => Option.when(tpe.kind == CelKind.DYN)(SimpleType.DYN)

  /** The value CEL sees for a field value. Amounts are maps with `amount` (double) and `currency`, dates are
    * timestamps at midnight UTC.
    */
  def toCel(value: FieldValue): Object = value match
    case FieldValue.Text(v)             => v
    case FieldValue.Number(v)           => java.lang.Double.valueOf(v.toDouble)
    case FieldValue.Amount(v, currency) =>
      Map[String, Object]("amount" -> java.lang.Double.valueOf(v.toDouble), "currency" -> currency).asJava
    case FieldValue.Date(v)    => v.atStartOfDay(ZoneOffset.UTC).toInstant
    case FieldValue.YesNo(v)   => java.lang.Boolean.valueOf(v)
    case FieldValue.ListOf(vs) => vs.map(toCel).asJava
    case FieldValue.Record(fs) => fs.map((k, v) => k -> toCel(v)).asJava

  /** The value CEL sees for a missing field, so that expressions do not fail on empty optional fields; use
    * `present_fields` to tell an empty value apart.
    */
  def zero(valueType: ValueType): Object = valueType match
    case ValueType.Text | ValueType.PersonRef | ValueType.DocumentRef | ValueType.Selection(_) => ""
    case ValueType.Number => java.lang.Double.valueOf(0)
    case ValueType.Amount =>
      Map[String, Object]("amount" -> java.lang.Double.valueOf(0), "currency" -> "").asJava
    case ValueType.Date      => Instant.EPOCH
    case ValueType.YesNo     => java.lang.Boolean.FALSE
    case ValueType.ListOf(_) => java.util.List.of()

  /** Converts an evaluation result back; maps with exactly `amount` and `currency` become amounts. */
  def fromCel(value: Any): Either[String, FieldValue] = value match
    case s: String           => Right(FieldValue.Text(s))
    case d: java.lang.Double =>
      if d.isNaN || d.isInfinite then Left(s"the result $d is not a number")
      else Right(FieldValue.Number(BigDecimal(d.toString)))
    case l: java.lang.Long                            => Right(FieldValue.Number(BigDecimal(l.longValue)))
    case u: com.google.common.primitives.UnsignedLong =>
      Right(FieldValue.Number(BigDecimal(u.bigIntegerValue)))
    case b: java.lang.Boolean             => Right(FieldValue.YesNo(b.booleanValue))
    case i: Instant                       => Right(FieldValue.Date(LocalDate.ofInstant(i, ZoneOffset.UTC)))
    case t: com.google.protobuf.Timestamp =>
      Right(
        FieldValue.Date(LocalDate.ofInstant(Instant.ofEpochSecond(t.getSeconds, t.getNanos), ZoneOffset.UTC))
      )
    case l: java.util.List[?] =>
      l.asScala.toList
        .foldRight[Either[String, List[FieldValue]]](Right(Nil))((v, acc) =>
          acc.flatMap(rest => fromCel(v).map(_ :: rest))
        )
        .map(FieldValue.ListOf(_))
    case m: java.util.Map[?, ?] =>
      val entries = m.asScala.toList.map((k, v) => (String.valueOf(k), v))
      val keys    = entries.map(_._1).toSet
      (keys, m.get("amount"), m.get("currency")) match
        case (ks, amount: java.lang.Number, currency: String) if ks == Set("amount", "currency") =>
          Right(FieldValue.Amount(BigDecimal(amount.toString), currency))
        case _ =>
          entries
            .foldRight[Either[String, List[(String, FieldValue)]]](Right(Nil))((kv, acc) =>
              acc.flatMap(rest => fromCel(kv._2).map(v => (kv._1, v) :: rest))
            )
            .map(fs => FieldValue.Record(fs.toMap))
    case null  => Left("the result is null")
    case other => Left(s"unsupported result type ${other.getClass.getSimpleName}")

  /** Identifiers an expression refers to (fields, computed fields, repeat items), excluding macro variables.
    */
  def identifiers(ast: CelAbstractSyntaxTree): Set[String] =
    def walk(e: CelExpr, bound: Set[String]): Set[String] = e.getKind match
      case CelExpr.ExprKind.Kind.IDENT  => Set(e.ident.name).diff(bound)
      case CelExpr.ExprKind.Kind.SELECT => walk(e.select.operand, bound)
      case CelExpr.ExprKind.Kind.CALL   =>
        val call = e.call
        (call.target.toScala.toList ++ call.args.asScala).flatMap(walk(_, bound)).toSet
      case CelExpr.ExprKind.Kind.LIST => e.list.elements.asScala.flatMap(walk(_, bound)).toSet
      case CelExpr.ExprKind.Kind.MAP  =>
        e.map.entries.asScala.flatMap(en => walk(en.key, bound) ++ walk(en.value, bound)).toSet
      case CelExpr.ExprKind.Kind.COMPREHENSION =>
        val c     = e.comprehension
        val inner = bound + c.iterVar + c.accuVar
        walk(c.iterRange, bound) ++ walk(c.accuInit, bound) ++ walk(c.loopCondition, inner) ++
          walk(c.loopStep, inner) ++ walk(c.result, inner)
      case _ => Set.empty
    walk(ast.getExpr, Set.empty)

  /** Literal values in an expression, used to derive test data around the values a condition compares with.
    */
  def constants(ast: CelAbstractSyntaxTree): List[CelConstant] =
    def walk(e: CelExpr): List[CelConstant] = e.getKind match
      case CelExpr.ExprKind.Kind.CONSTANT => List(e.constant)
      case CelExpr.ExprKind.Kind.SELECT   => walk(e.select.operand)
      case CelExpr.ExprKind.Kind.CALL     =>
        val call = e.call
        (call.target.toScala.toList ++ call.args.asScala).flatMap(walk)
      case CelExpr.ExprKind.Kind.LIST => e.list.elements.asScala.toList.flatMap(walk)
      case CelExpr.ExprKind.Kind.MAP  =>
        e.map.entries.asScala.toList.flatMap(en => walk(en.key) ++ walk(en.value))
      case CelExpr.ExprKind.Kind.COMPREHENSION =>
        val c = e.comprehension
        List(c.iterRange, c.accuInit, c.loopCondition, c.loopStep, c.result).flatMap(walk)
      case _ => Nil
    walk(ast.getExpr)

  extension [A](o: java.util.Optional[A])
    private def toScala: Option[A] = if o.isPresent then Some(o.get) else None

  /** Raised by the evaluation listener when a budget is used up; aborts the evaluation. */
  final class BudgetExceeded(val message: String) extends RuntimeException(message) with NoStackTrace

  /** Evaluation budget shared by all evaluations of one resolution: a deadline and a step limit per
    * evaluation.
    */
  final class Budget(limits: Limits, startNanos: Long):
    private val deadline = startNanos + limits.timeoutMillis * 1000000L

    def checkTime(): Unit =
      if System.nanoTime() > deadline then
        throw BudgetExceeded(s"the evaluation took longer than ${limits.timeoutMillis} ms")

    /** Evaluates `program`; fails with a message on errors, throws [[BudgetExceeded]] when a limit is hit. */
    def evaluate(
        program: CelRuntime.Program,
        activation: java.util.Map[String, Object]
    ): Either[String, Object] =
      var steps    = 0
      val listener = new CelEvaluationListener:
        def callback(expr: CelExpr, result: Object): Unit =
          steps += 1
          if steps > limits.maxEvaluationSteps then
            throw BudgetExceeded(
              s"the expression needs more than ${limits.maxEvaluationSteps} evaluation steps"
            )
          if (steps & 63) == 0 then checkTime()
      checkTime()
      try Right(program.trace(activation, listener))
      catch
        case e: CelEvaluationException =>
          budgetCause(e) match
            case Some(b) => throw b
            case None    =>
              if e.getMessage != null && e.getMessage.contains("Iteration budget exceeded") then
                throw BudgetExceeded(
                  s"a macro iterates more than ${limits.maxComprehensionIterations} times"
                )
              else Left(Option(e.getMessage).getOrElse(e.toString))
        case e: BudgetExceeded   => throw e
        case e: RuntimeException =>
          budgetCause(e) match
            case Some(b) => throw b
            case None    => Left(Option(e.getMessage).getOrElse(e.toString))

    private def budgetCause(t: Throwable): Option[BudgetExceeded] =
      Iterator.iterate(t)(_.getCause).takeWhile(_ != null).collectFirst { case b: BudgetExceeded => b }
