package dms.templates.core

import dms.templates.*

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Resolves a compiled template with field values: evaluates computed fields and conditions, expands repeats
  * and replaces placeholders with formatted values. Pure apart from reading the clock for the time limit.
  */
object Resolver:

  /** Checks given values against the schema: known names and matching types. */
  def checkValues(schema: TemplateSchema, values: Map[String, FieldValue]): List[String] =
    values.toList.sortBy(_._1).flatMap { (name, value) =>
      schema.variable(name) match
        case None    => List(s"'$name' is not a field of the document class")
        case Some(v) =>
          Option.unless(fits(value, v.valueType))(s"'$name' needs a ${describe(v.valueType)}").toList
    }

  private def fits(value: FieldValue, t: ValueType): Boolean = (value, t) match
    case (FieldValue.Text(_), ValueType.Text | ValueType.PersonRef | ValueType.DocumentRef) => true
    case (FieldValue.Text(code), ValueType.Selection(choices))                              =>
      choices.isEmpty || choices.exists(_.code == code)
    case (FieldValue.Number(_), ValueType.Number)     => true
    case (FieldValue.Amount(_, _), ValueType.Amount)  => true
    case (FieldValue.Date(_), ValueType.Date)         => true
    case (FieldValue.YesNo(_), ValueType.YesNo)       => true
    case (FieldValue.ListOf(vs), ValueType.ListOf(e)) => vs.forall(fits(_, e))
    case _                                            => false

  private def describe(t: ValueType): String = t match
    case ValueType.Selection(choices) => s"selection code (${choices.map(_.code).mkString(", ")})"
    case ValueType.ListOf(e)          => s"list of ${describe(e)}"
    case ValueType.YesNo              => "yes/no value"
    case other                        => other.toString.toLowerCase(java.util.Locale.ROOT) + " value"

  def resolve(
      template: CompiledTemplate,
      values: Map[String, FieldValue],
      language: Language = Language.default
  ): Either[ResolveError, Resolution] =
    val problems = checkValues(template.schema, values)
    if problems.nonEmpty then Left(ResolveError.InvalidValues(problems))
    else
      val run = Run(template, values, language)
      try Right(run.resolution())
      catch
        case Failure(error)        => Left(error)
        case e: Cel.BudgetExceeded => Left(ResolveError.LimitExceeded(run.currentLocation, e.message))

  private final case class Failure(error: ResolveError)
      extends RuntimeException
      with scala.util.control.NoStackTrace

  /** Names in scope during resolution: the value (if any) and its static type. */
  private final case class Entry(
      value: Option[FieldValue],
      cel: Object,
      valueType: Option[ValueType],
      required: Boolean
  )

  private final class Run(template: CompiledTemplate, values: Map[String, FieldValue], language: Language):
    private val limits                    = template.limits
    private val budget                    = Cel.Budget(limits, System.nanoTime())
    private var repeatIterations          = 0
    private var outputLength              = 0L
    private val branches                  = mutable.LinkedHashMap.empty[Location, (String, Set[Boolean])]
    var currentLocation: Option[Location] = None

    def resolution(): Resolution =
      val fields = template.schema.variables.map { v =>
        val value = values.get(v.name)
        v.name -> Entry(value, value.fold(Cel.zero(v.valueType))(Cel.toCel), Some(v.valueType), v.required)
      }.toMap
      val present = Cel.PresentFields -> Entry(
        None,
        template.schema.variables.map(_.name).filter(values.contains).asJava,
        None,
        required = false
      )
      val scope = template.computed.foldLeft(fields + present) { case (scope, (field, expression)) =>
        val result = evaluate(expression, scope)
        val value  = Cel.fromCel(result).fold(m => fail(expression.location, m), identity)
        scope + (field.key -> Entry(
          Some(value),
          result,
          template.computedTypes.get(field.key).flatten,
          false
        ))
      }
      val sections = template.content.sections.map { s =>
        ResolvedSection(s.id, s.title, s.locked, blocks(s.blocks, Some(s.id), "/blocks", scope))
      }
      val missing = template.schema.variables.filter(v => v.required && !values.contains(v.name)).map(_.name)
      Resolution(
        ResolvedContent(sections),
        missing,
        branches.toList.map { case (location, (condition, outcomes)) =>
          BranchOutcome(location, condition, outcomes)
        }
      )

    private def fail(location: Location, message: String): Nothing =
      throw Failure(ResolveError.Evaluation(location, message))

    private def evaluate(expression: CompiledExpression, scope: Map[String, Entry]): Object =
      currentLocation = Some(expression.location)
      val activation = java.util.HashMap[String, Object](scope.size * 2)
      scope.foreach((name, entry) => activation.put(name, entry.cel))
      budget.evaluate(expression.program, activation).fold(m => fail(expression.location, m), identity)

    private def blocks(
        bs: List[Block],
        section: Option[SectionId],
        path: String,
        scope: Map[String, Entry]
    ): List[ResolvedBlock] =
      bs.zipWithIndex.flatMap((b, i) => block(b, section, s"$path/$i", scope))

    private def block(
        b: Block,
        section: Option[SectionId],
        path: String,
        scope: Map[String, Entry]
    ): List[ResolvedBlock] =
      budget.checkTime()
      val here = Location(section, path, "")
      def nested(children: List[Block], suffix: String, s: Map[String, Entry] = scope) =
        blocks(children, section, path + suffix, s)
      b match
        case Block.Heading(level, content) => List(ResolvedBlock.Heading(level, inlines(content, scope)))
        case Block.Paragraph(content)      => List(ResolvedBlock.Paragraph(inlines(content, scope)))
        case Block.BulletList(items)       =>
          List(ResolvedBlock.BulletList(items.zipWithIndex.map((item, k) => nested(item, s"/items/$k"))))
        case Block.OrderedList(items) =>
          List(ResolvedBlock.OrderedList(items.zipWithIndex.map((item, k) => nested(item, s"/items/$k"))))
        case Block.Table(rows, headerRows) =>
          val resolved =
            rows.zipWithIndex.map((row, r) => row.zipWithIndex.map((cell, c) => nested(cell, s"/rows/$r/$c")))
          List(ResolvedBlock.Table(resolved, headerRows))
        case Block.ClauseRef(key, version)                 => List(ResolvedBlock.ClauseRef(key, version))
        case Block.PageBreak                               => List(ResolvedBlock.PageBreak)
        case Block.Conditional(condition, body, otherwise) =>
          val location = here.copy(part = "condition")
          val outcome  = evaluate(template.expression(location), scope) match
            case b: java.lang.Boolean => b.booleanValue
            case other => fail(location, s"the condition did not yield true or false but $other")
          val (_, seen) = branches.getOrElse(location, (condition, Set.empty[Boolean]))
          branches.update(location, (condition, seen + outcome))
          if outcome then nested(body, "/body") else nested(otherwise, "/otherwise")
        case Block.Repeat(_, itemName, body) =>
          val location = here.copy(part = "collection")
          val items    = evaluate(template.expression(location), scope) match
            case l: java.util.List[?] => l.asScala.toList
            case other                => fail(location, s"the collection is not a list but $other")
          repeatIterations += items.size
          if repeatIterations > limits.maxRepeatIterations then
            throw Failure(
              ResolveError.LimitExceeded(
                Some(location),
                s"repeats produce more than ${limits.maxRepeatIterations} items in total"
              )
            )
          val itemType = template.itemTypes.get((section, path)).flatten
          items.flatMap { item =>
            val value = Cel.fromCel(item).fold(m => fail(location, m), identity)
            nested(
              body,
              "/body",
              scope + (itemName -> Entry(Some(value), item.asInstanceOf[Object], itemType, false))
            )
          }

    private def inlines(content: List[Inline], scope: Map[String, Entry]): List[ResolvedInline] =
      val resolved = content.map {
        case Inline.Text(value, marks)        => ResolvedInline.Text(value, marks)
        case Inline.LineBreak                 => ResolvedInline.LineBreak
        case Inline.Placeholder(path, format) =>
          val segments = path.split('.').toList
          val entry    = scope.get(segments.head)
          entry
            .flatMap(e => e.value.map(v => (v, e.valueType)))
            .flatMap((v, t) => select(v, t, segments.tail)) match
            case Some((value, valueType)) =>
              ResolvedInline.Value(path, Formatting.format(value, format, language, valueType))
            case None => ResolvedInline.Missing(path, entry.exists(_.required) && segments.size == 1)
      }
      outputLength += resolved.iterator.map(_.plainText.length.toLong).sum
      if outputLength > limits.maxOutputLength then
        throw Failure(
          ResolveError.LimitExceeded(
            currentLocation,
            s"the resolved text is longer than ${limits.maxOutputLength} characters"
          )
        )
      resolved

    private def select(
        value: FieldValue,
        valueType: Option[ValueType],
        rest: List[String]
    ): Option[(FieldValue, Option[ValueType])] =
      (value, rest) match
        case (_, Nil)                                   => Some((value, valueType))
        case (FieldValue.Amount(v, _), "amount" :: Nil) =>
          Some((FieldValue.Number(v), Some(ValueType.Number)))
        case (FieldValue.Amount(_, c), "currency" :: Nil) => Some((FieldValue.Text(c), Some(ValueType.Text)))
        case (FieldValue.Record(fields), field :: tail)   => fields.get(field).flatMap(select(_, None, tail))
        case _                                            => None
