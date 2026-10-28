package dms.templates

import java.time.LocalDate
import java.util.Locale

/** An option of a selection field: the stored code and the label printed in documents. */
final case class Choice(code: String, label: String)

/** Type of a value that placeholders and expressions refer to, derived from the field types of the document
  * class profile (business concept 3.2). Expressions see these as CEL types (ADR 0020).
  */
enum ValueType:
  case Text, Number, Amount, Date, YesNo, PersonRef, DocumentRef
  case Selection(choices: List[Choice])

  /** A field with multiple values. */
  case ListOf(element: ValueType)

/** A field of the document class profile as seen by a template.
  *
  * @param required
  *   the value must be present before a document can be released; missing required values are reported by the
  *   resolver, missing optional values render as an empty marker
  */
final case class Variable(
    name: String,
    valueType: ValueType,
    required: Boolean = false,
    label: Option[String] = None
)

/** The fields a template can use: those of the document class profile version it was designed against. */
final case class TemplateSchema(variables: List[Variable]):
  def variable(name: String): Option[Variable] = variables.find(_.name == name)

/** A value for a placeholder or an expression. */
enum FieldValue:
  case Text(value: String)
  case Number(value: BigDecimal)
  case Amount(value: BigDecimal, currency: String)
  case Date(value: LocalDate)
  case YesNo(value: Boolean)
  case ListOf(values: List[FieldValue])

  /** Structured result of a computed field (a CEL map), e.g. `{"name": "...", "price": ...}`. */
  case Record(fields: Map[String, FieldValue])

/** Language of the document, which controls the formatting of dates, numbers and amounts (business concept
  * 4.2), independent of the language of the user interface.
  */
enum Language(val code: String, val locale: Locale):
  case En extends Language("en", Locale.ENGLISH)
  case De extends Language("de", Locale.GERMAN)

object Language:
  val default: Language = En

  def parse(code: String): Option[Language] = values.find(_.code == code.toLowerCase(Locale.ROOT))

/** Where an expression or placeholder sits in the content, for error messages in the editor.
  *
  * @param sectionId
  *   the section, or `None` for a computed field
  * @param blockPath
  *   JSON-pointer-like path within the section, e.g. `/blocks/1/otherwise/0`, or `/computed/0` for a computed
  *   field
  * @param part
  *   which part of the block, e.g. `condition`, `collection`, `itemName`, `expression` or `content/2` for a
  *   placeholder
  * @param line
  *   1-based line within the expression (0 if unknown)
  * @param column
  *   1-based column within the expression (0 if unknown)
  */
final case class Location(
    sectionId: Option[SectionId],
    blockPath: String,
    part: String,
    line: Int = 0,
    column: Int = 0
):
  def show: String =
    val section  = sectionId.fold("computed fields")(id => s"section ${id.value}")
    val position = if line > 0 then s", line $line, column $column" else ""
    s"$section, $blockPath ($part$position)"

/** A problem found when a template is checked against its schema (on save). */
final case class ContentIssue(location: Location, message: String):
  def show: String = s"${location.show}: $message"

/** Limits for the evaluation of a template, protecting against runaway expressions and huge outputs (business
  * concept 4.2: closed expression language with bounded loops and runtime).
  *
  * @param maxExpressionLength
  *   characters per expression, checked on save
  * @param maxNesting
  *   depth of nested blocks, checked on save
  * @param maxEvaluationSteps
  *   evaluated sub-expressions per expression evaluation (cost limit)
  * @param maxComprehensionIterations
  *   iterations of CEL macros such as `all`, `exists`, `map`, `filter` per expression
  * @param maxRepeatIterations
  *   repeat iterations in total per resolution
  * @param maxOutputLength
  *   characters of resolved text in total per resolution
  * @param timeoutMillis
  *   wall-clock time per resolution
  */
final case class Limits(
    maxExpressionLength: Int = 2000,
    maxNesting: Int = 24,
    maxEvaluationSteps: Int = 10000,
    maxComprehensionIterations: Int = 1000,
    maxRepeatIterations: Int = 2000,
    maxOutputLength: Int = 2000000,
    timeoutMillis: Long = 2000
)

object Limits:
  val default: Limits = Limits()
