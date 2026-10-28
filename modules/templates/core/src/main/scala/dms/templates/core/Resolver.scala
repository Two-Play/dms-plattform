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
    running(template, values, language)(_.resolution())

  /** Evaluates only the computed fields, e.g. to store their values with a document (business concept 4.3).
    * Returns the value of every computed field that has one by key; computed fields that read a field without
    * value have none (see [[missingComputed]]).
    */
  def computedValues(
      template: CompiledTemplate,
      values: Map[String, FieldValue]
  ): Either[ResolveError, Map[String, FieldValue]] =
    running(template, values, Language.default) { run =>
      val scope = run.scope()
      template.computed.flatMap((field, _) => scope.get(field.key).flatMap(_.value).map(field.key -> _)).toMap
    }

  /** The computed fields that have no value because they read fields without value, with those fields
    * (DMS-83), e.g. to block the approval of a document.
    */
  def missingComputed(
      template: CompiledTemplate,
      values: Map[String, FieldValue]
  ): Either[ResolveError, List[MissingComputed]] =
    running(template, values, Language.default)(run => run.missingComputed(run.scope()))

  private def running[A](template: CompiledTemplate, values: Map[String, FieldValue], language: Language)(
      f: Run => A
  ): Either[ResolveError, A] =
    val problems = checkValues(template.schema, values)
    if problems.nonEmpty then Left(ResolveError.InvalidValues(problems))
    else
      val run = Run(template, values, language)
      try Right(f(run))
      catch
        case Failure(error)        => Left(error)
        case e: Cel.BudgetExceeded => Left(ResolveError.LimitExceeded(run.currentLocation, e.message))

  private final case class Failure(error: ResolveError)
      extends RuntimeException
      with scala.util.control.NoStackTrace

  /** A name in scope during resolution.
    *
    * @param value
    *   the value, if any
    * @param cel
    *   what conditions and repeats see: the CEL value; for a field without value its empty value
    *   ([[Cel.zero]]); for a computed field without value the result of its expression with empty values, so
    *   conditions behave as before DMS-83; `None` if that fails
    * @param missing
    *   a field or computed field without value: computed fields see it as unknown
    * @param missingInputs
    *   for a computed field without value: the fields without value it reads, in schema order
    */
  private final case class Entry(
      value: Option[FieldValue],
      cel: Option[Object],
      valueType: Option[ValueType],
      required: Boolean,
      missing: Boolean = false,
      missingInputs: List[String] = Nil
  )

  private final class Run(template: CompiledTemplate, values: Map[String, FieldValue], language: Language):
    private val limits                    = template.limits
    private val budget                    = Cel.Budget(limits, System.nanoTime())
    private var repeatIterations          = 0
    private var outputLength              = 0L
    private val branches                  = mutable.LinkedHashMap.empty[Location, (String, Set[Boolean])]
    var currentLocation: Option[Location] = None

    /** The field values and, evaluated in order, the computed fields. A computed field that reads a field
      * without value (or a computed field without value) has no value itself (DMS-83); one that only reads
      * fields it checks first, e.g. `'endDate' in present_fields ? ... : ...`, keeps its value.
      */
    def scope(): Map[String, Entry] =
      val fields = template.schema.variables.map { v =>
        val value = values.get(v.name)
        v.name -> Entry(
          value,
          Some(value.fold(Cel.zero(v.valueType))(Cel.toCel)),
          Some(v.valueType),
          v.required,
          missing = value.isEmpty
        )
      }.toMap
      val present = Cel.PresentFields -> Entry(
        None,
        Some(template.schema.variables.map(_.name).filter(values.contains).asJava),
        None,
        required = false
      )
      template.computed.foldLeft(fields + present) { case (scope, (field, expression)) =>
        val known     = scope.collect { case (name, e) if !e.missing => name -> e.cel }
        val unknown   = scope.collect { case (name, e) if e.missing => name }
        val result    = run(expression, known, unknown).fold(m => fail(expression.location, m), identity)
        val valueType = template.computedTypes.get(field.key).flatten
        Cel.unknownVariables(result) match
          case None =>
            val value = Cel.fromCel(result).fold(m => fail(expression.location, m), identity)
            scope + (field.key -> Entry(Some(value), Some(result), valueType, required = false))
          case Some(names) =>
            val inputs =
              names.flatMap(n => scope.get(n).map(_.missingInputs).filter(_.nonEmpty).getOrElse(List(n)))
            val asBefore = conditionView(expression, scope).toOption.filter(Cel.unknownVariables(_).isEmpty)
            scope + (field.key -> Entry(
              None,
              asBefore,
              valueType,
              required = false,
              missing = true,
              missingInputs = template.schema.variables.map(_.name).filter(inputs.contains)
            ))
      }

    def missingComputed(scope: Map[String, Entry]): List[MissingComputed] =
      template.computed.flatMap { (field, _) =>
        scope.get(field.key).filter(_.missing).map(e => MissingComputed(field.key, e.missingInputs))
      }

    def resolution(): Resolution =
      val scope    = this.scope()
      val sections = template.content.sections.map { s =>
        ResolvedSection(s.id, s.title, s.locked, blocks(s.blocks, Some(s.id), "/blocks", scope))
      }
      val missing = template.schema.variables.filter(v => v.required && !values.contains(v.name)).map(_.name)
      Resolution(
        ResolvedContent(sections),
        missing,
        branches.toList.map { case (location, (condition, outcomes)) =>
          BranchOutcome(location, condition, outcomes)
        },
        missingComputed(scope)
      )

    private def fail(location: Location, message: String): Nothing =
      throw Failure(ResolveError.Evaluation(location, message))

    private def run(
        expression: CompiledExpression,
        known: Iterable[(String, Option[Object])],
        unknown: Iterable[String]
    ): Either[String, Object] =
      currentLocation = Some(expression.location)
      val activation = java.util.HashMap[String, Object](known.size * 2)
      known.foreach((name, cel) => cel.foreach(activation.put(name, _)))
      budget.evaluate(expression.program, activation, unknown)

    /** Evaluates a condition or repeat collection: fields without value read as empty values. */
    private def conditionView(
        expression: CompiledExpression,
        scope: Map[String, Entry]
    ): Either[String, Object] =
      run(expression, scope.view.mapValues(_.cel), scope.collect { case (name, e) if e.cel.isEmpty => name })

    private def evaluate(expression: CompiledExpression, scope: Map[String, Entry]): Object =
      val result = conditionView(expression, scope).fold(m => fail(expression.location, m), identity)
      Cel.unknownVariables(result).foreach { names =>
        val inputs = names.toList.sorted.flatMap(n => scope.get(n).toList.flatMap(_.missingInputs)).distinct
        fail(
          expression.location,
          s"it reads computed fields without value (${names.toList.sorted.mkString(", ")}); " +
            s"missing: ${inputs.mkString(", ")}"
        )
      }
      result

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
              scope + (itemName -> Entry(Some(value), Some(item.asInstanceOf[Object]), itemType, false))
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
            case None =>
              val inputs = entry.fold(Nil)(_.missingInputs)
              ResolvedInline.Missing(
                path,
                inputs.nonEmpty || (entry.exists(_.required) && segments.size == 1),
                inputs
              )
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
