package dms.templates.core

import dev.cel.common.CelAbstractSyntaxTree
import dev.cel.common.types.{CelKind, CelType}
import dev.cel.compiler.CelCompiler
import dev.cel.runtime.CelRuntime
import dms.templates.*

import scala.jdk.CollectionConverters.*

/** A type-checked expression, ready for evaluation. */
final case class CompiledExpression(
    location: Location,
    source: String,
    private[core] val ast: CelAbstractSyntaxTree,
    private[core] val program: CelRuntime.Program
):
  private[core] def resultType: CelType = ast.getResultType

  /** Names of fields, computed fields and repeat items the expression uses. */
  def identifiers: Set[String] = Cel.identifiers(ast)

/** A compiled conditional block. */
final case class ConditionalInfo(expression: CompiledExpression, enclosingRepeats: List[CompiledExpression])

/** A template compiled against a schema: all expressions type-checked once, placeholders checked.
  *
  * Create with [[TemplateCompiler.compile]]; resolve with [[Resolver.resolve]].
  */
final class CompiledTemplate private[core] (
    val content: TemplateContent,
    val schema: TemplateSchema,
    val limits: Limits,
    private[core] val computed: List[(ComputedField, CompiledExpression)],
    private[core] val expressions: Map[(Option[SectionId], String, String), CompiledExpression],
    /** Value types of the items of each repeat, where known. */
    private[core] val itemTypes: Map[(Option[SectionId], String), Option[ValueType]],
    /** Value types of computed fields, where known. */
    private[core] val computedTypes: Map[String, Option[ValueType]],
    /** All conditionals in document order. */
    val conditionals: List[ConditionalInfo]
):
  private[core] def expression(location: Location): CompiledExpression =
    expressions((location.sectionId, location.blockPath, location.part))

/** Checks template content against a schema (the document class profile) when a template is saved.
  *
  * Reports every problem with its location: CEL syntax and type errors with line and column, unknown
  * placeholders, unknown formatters, invalid item names, too deep nesting.
  */
object TemplateCompiler:

  /** Formatters a placeholder can name after the value path. */
  val formatters: Map[String, Set[String]] = Map(
    "plain"      -> Set.empty,
    "number"     -> Set("Number"),
    "amount"     -> Set("Amount"),
    "date:long"  -> Set("Date"),
    "date:short" -> Set("Date")
  )

  private val Identifier = "[a-z][a-zA-Z0-9]{0,62}".r

  /** Static information about a name in scope. `valueType` is `None` when only CEL knows the type. */
  private final case class Binding(valueType: Option[ValueType], celType: CelType)

  private final case class Scope(compiler: CelCompiler, bindings: Map[String, Binding]):
    def add(name: String, binding: Binding): Scope =
      Scope(Cel.withVar(compiler, name, binding.celType), bindings + (name -> binding))

  private final class State(val limits: Limits):
    val runtime                                                                   = Cel.runtime(limits)
    var issues: List[ContentIssue]                                                = Nil
    var expressions: Map[(Option[SectionId], String, String), CompiledExpression] = Map.empty
    var itemTypes: Map[(Option[SectionId], String), Option[ValueType]]            = Map.empty
    var conditionals: List[ConditionalInfo]                                       = Nil

    def issue(location: Location, message: String): Unit = issues = ContentIssue(location, message) :: issues

  def compile(
      content: TemplateContent,
      schema: TemplateSchema,
      limits: Limits = Limits.default
  ): Either[List[ContentIssue], CompiledTemplate] =
    val state = State(limits)
    val base  = schema.variables.foldLeft(Scope(Cel.compiler(limits), Map.empty)) { (scope, v) =>
      scope.add(v.name, Binding(Some(v.valueType), Cel.typeOf(v.valueType)))
    }
    val (scope, computed) = compileComputed(content.computed, base, schema, state)
    checkReferences(content.references, schema, state)
    content.sections.groupBy(_.id).collect { case (id, ss) if ss.size > 1 => id }.foreach { id =>
      state.issue(Location(Some(id), "", "id"), s"section id ${id.value} is used more than once")
    }
    content.sections.foreach { section =>
      blocks(section.blocks, Some(section.id), "/blocks", scope, Nil, 1, state)
    }
    if state.issues.nonEmpty then Left(state.issues.reverse)
    else
      Right(
        CompiledTemplate(
          content,
          schema,
          limits,
          computed,
          state.expressions,
          state.itemTypes,
          computed.map((f, e) => f.key -> Cel.valueTypeOf(e.resultType)).toMap,
          state.conditionals.reverse
        )
      )

  /** Reference fields (ADR 0023): valid, unique keys; known attributes including the identifier; each mapped
    * to a single-valued text field of the profile that no other reference fills.
    */
  private def checkReferences(references: List[ReferenceField], schema: TemplateSchema, state: State): Unit =
    references.groupBy(_.key).collect { case (key, rs) if rs.size > 1 => key }.toList.sorted.foreach { key =>
      state.issue(Location(None, "/references", "key"), s"reference field '$key' is defined more than once")
    }
    references.zipWithIndex.foreach { (reference, i) =>
      val location = Location(None, s"/references/$i", "key")
      if !Identifier.matches(reference.key) then
        state.issue(location, s"'${reference.key}' is not a valid name (lower camel case)")
      if reference.label.trim.isEmpty then state.issue(location.copy(part = "label"), "the label is empty")
      if !reference.fields.exists(_.attribute == ReferenceSource.IdAttribute) then
        state.issue(
          location.copy(part = "fields"),
          s"the identifier ('${ReferenceSource.IdAttribute}') must be taken over into a field as source reference"
        )
      reference.fields
        .groupBy(_.attribute)
        .collect { case (a, ms) if ms.size > 1 => a }
        .toList
        .sorted
        .foreach { a =>
          state.issue(location.copy(part = "fields"), s"attribute '$a' is taken over more than once")
        }
      reference.fields.zipWithIndex.foreach { (mapping, j) =>
        val at = location.copy(part = s"fields/$j")
        if !reference.source.attributes.contains(mapping.attribute) then
          state.issue(
            at,
            s"'${mapping.attribute}' is not an attribute of ${reference.source.key}; known: " +
              reference.source.attributes.mkString(", ")
          )
        schema.variable(mapping.field).map(_.valueType) match
          case Some(ValueType.Text) => ()
          case Some(_) => state.issue(at, s"field '${mapping.field}' must be a single text field")
          case None    => state.issue(at, s"unknown field '${mapping.field}'")
      }
    }
    references
      .flatMap(_.fieldKeys)
      .groupBy(identity)
      .collect { case (field, uses) if uses.size > 1 => field }
      .toList
      .sorted
      .foreach { field =>
        state.issue(
          Location(None, "/references", "fields"),
          s"field '$field' is filled by more than one reference"
        )
      }

  private def compileComputed(
      fields: List[ComputedField],
      base: Scope,
      schema: TemplateSchema,
      state: State
  ): (Scope, List[(ComputedField, CompiledExpression)]) =
    fields.zipWithIndex.foldLeft((base, List.empty[(ComputedField, CompiledExpression)])) {
      case ((scope, done), (field, i)) =>
        val location = Location(None, s"/computed/$i", "expression")
        if !Identifier.matches(field.key) then
          state.issue(location.copy(part = "key"), s"'${field.key}' is not a valid name (lower camel case)")
          (scope, done)
        else if scope.bindings.contains(field.key) then
          val what = if schema.variable(field.key).isDefined then "a field" else "another computed field"
          state.issue(location.copy(part = "key"), s"'${field.key}' is already the name of $what")
          (scope, done)
        else
          expression(field.expression, location, scope, state) match
            case Some(e) =>
              val binding = Binding(Cel.valueTypeOf(e.resultType), e.resultType)
              (scope.add(field.key, binding), done :+ (field -> e))
            case None => (scope, done)
    }

  private def blocks(
      bs: List[Block],
      section: Option[SectionId],
      path: String,
      scope: Scope,
      repeats: List[CompiledExpression],
      depth: Int,
      state: State
  ): Unit =
    bs.zipWithIndex.foreach((b, i) => block(b, section, s"$path/$i", scope, repeats, depth, state))

  private def block(
      b: Block,
      section: Option[SectionId],
      path: String,
      scope: Scope,
      repeats: List[CompiledExpression],
      depth: Int,
      state: State
  ): Unit =
    val here = Location(section, path, "")
    if depth > state.limits.maxNesting then
      state.issue(
        here.copy(part = "block"),
        s"blocks are nested deeper than ${state.limits.maxNesting} levels"
      )
    else
      def nested(
          children: List[Block],
          suffix: String,
          s: Scope = scope,
          rs: List[CompiledExpression] = repeats
      ) =
        blocks(children, section, path + suffix, s, rs, depth + 1, state)
      b match
        case Block.Heading(level, content) =>
          if level < 1 || level > 6 then state.issue(here.copy(part = "level"), "heading levels are 1 to 6")
          inlines(content, here, scope, state)
        case Block.Paragraph(content) => inlines(content, here, scope, state)
        case Block.PageBreak          => ()
        case Block.BulletList(items)  => items.zipWithIndex.foreach((item, k) => nested(item, s"/items/$k"))
        case Block.OrderedList(items) => items.zipWithIndex.foreach((item, k) => nested(item, s"/items/$k"))
        case Block.Table(rows, headerRows) =>
          if headerRows < 0 || headerRows > rows.size then
            state.issue(
              here.copy(part = "headerRows"),
              "header rows must be between 0 and the number of rows"
            )
          for
            (row, r)  <- rows.zipWithIndex
            (cell, c) <- row.zipWithIndex
          do nested(cell, s"/rows/$r/$c")
        case Block.ClauseRef(key, version) =>
          if key.trim.isEmpty then state.issue(here.copy(part = "clauseKey"), "the clause key is empty")
          if version < 1 then state.issue(here.copy(part = "version"), "clause versions start at 1")
        case Block.Conditional(condition, body, otherwise) =>
          expression(condition, here.copy(part = "condition"), scope, state).foreach { e =>
            if e.resultType.kind != CelKind.BOOL then
              state.issue(
                e.location,
                s"the condition must be true or false, but its type is ${e.resultType.name}"
              )
            else state.conditionals = ConditionalInfo(e, repeats) :: state.conditionals
          }
          nested(body, "/body")
          nested(otherwise, "/otherwise")
        case Block.Repeat(collection, itemName, body) =>
          val itemOk =
            if !Identifier.matches(itemName) then
              state.issue(here.copy(part = "itemName"), s"'$itemName' is not a valid name (lower camel case)")
              false
            else if scope.bindings.contains(itemName) then
              state.issue(here.copy(part = "itemName"), s"'$itemName' is already in use; choose another name")
              false
            else true
          expression(collection, here.copy(part = "collection"), scope, state) match
            case Some(e) =>
              Cel.elementType(e.resultType) match
                case None =>
                  state.issue(e.location, s"a repeat needs a list, but the type is ${e.resultType.name}")
                case Some(elementType) if itemOk =>
                  val itemType = Cel.valueTypeOf(elementType)
                  state.itemTypes = state.itemTypes + ((section, path) -> itemType)
                  nested(body, "/body", scope.add(itemName, Binding(itemType, elementType)), e :: repeats)
                case Some(_) => ()
            case None => ()

  private def inlines(content: List[Inline], block: Location, scope: Scope, state: State): Unit =
    content.zipWithIndex.foreach {
      case (Inline.Placeholder(path, format), i) =>
        val location = block.copy(part = s"content/$i")
        val segments = path.split('.').toList
        segments match
          case root :: rest if scope.bindings.contains(root) =>
            val valueType = selectType(scope.bindings(root).valueType, rest) match
              case Left(message) =>
                state.issue(location, s"placeholder '$path': $message")
                None
              case Right(t) => t
            format.foreach { f =>
              formatters.get(f) match
                case None =>
                  state.issue(
                    location,
                    s"unknown format '$f'; known formats: ${formatters.keys.toList.sorted.mkString(", ")}"
                  )
                case Some(kinds) =>
                  valueType.filter(t => kinds.nonEmpty && !kinds.contains(kindOf(t))).foreach { t =>
                    state.issue(location, s"format '$f' does not apply to ${kindOf(t)} values of '$path'")
                  }
            }
          case _ => state.issue(location, s"placeholder '$path' does not refer to a field in scope")
      case _ => ()
    }

  private def kindOf(t: ValueType): String = t match
    case ValueType.Selection(_) => "Selection"
    case ValueType.ListOf(_)    => "ListOf"
    case other                  => other.toString

  /** The type at `rest` below a value of type `t`; amounts have `amount` and `currency`. */
  private def selectType(t: Option[ValueType], rest: List[String]): Either[String, Option[ValueType]] =
    (t, rest) match
      case (_, Nil)                                    => Right(t)
      case (Some(ValueType.Amount), "amount" :: Nil)   => Right(Some(ValueType.Number))
      case (Some(ValueType.Amount), "currency" :: Nil) => Right(Some(ValueType.Text))
      case (Some(other), field :: _) => Left(s"a ${kindOf(other)} value has no part '$field'")
      case (None, _)                 => Right(None)

  private def expression(
      source: String,
      location: Location,
      scope: Scope,
      state: State
  ): Option[CompiledExpression] =
    if source.trim.isEmpty then
      state.issue(location, "the expression is empty")
      None
    else if source.codePointCount(0, source.length) > state.limits.maxExpressionLength then
      state.issue(location, s"the expression is longer than ${state.limits.maxExpressionLength} characters")
      None
    else
      val result = scope.compiler.compile(source)
      if result.hasError then
        result.getErrors.asScala.foreach { e =>
          val l = e.getSourceLocation
          state.issue(
            if l.getLine > 0 then location.copy(line = l.getLine, column = l.getColumn + 1) else location,
            e.getMessage
          )
        }
        None
      else
        val ast      = result.getAst
        val compiled = CompiledExpression(location, source, ast, state.runtime.createProgram(ast))
        state.expressions =
          state.expressions + ((location.sectionId, location.blockPath, location.part) -> compiled)
        Some(compiled)
