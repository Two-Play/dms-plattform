package dms.templates

/** Template content with conditions decided, repeats expanded and placeholders replaced by formatted values:
  * the input of rendering (ADR 0008). Clause references stay references; the rendering resolves them.
  */
final case class ResolvedContent(sections: List[ResolvedSection]):
  /** The resolved text without markup, for previews and tests. */
  def plainText: String = sections.map(_.plainText).filter(_.nonEmpty).mkString("\n\n")

final case class ResolvedSection(
    id: SectionId,
    title: Option[String],
    locked: Boolean,
    blocks: List[ResolvedBlock]
):
  def plainText: String = (title.toList ++ blocks.map(_.plainText)).filter(_.nonEmpty).mkString("\n")

enum ResolvedInline:
  case Text(value: String, marks: Set[Mark] = Set.empty)

  /** A placeholder replaced by its formatted value. */
  case Value(path: String, text: String)

  /** A placeholder without value: renders as a visible marker in drafts (business concept 4.2). */
  case Missing(path: String, required: Boolean)

  case LineBreak

  def plainText: String = this match
    case Text(value, _)       => value
    case Value(_, text)       => text
    case Missing(path, true)  => s"[missing: $path]"
    case Missing(path, false) => s"[$path]"
    case LineBreak            => "\n"

enum ResolvedBlock:
  case Heading(level: Int, content: List[ResolvedInline])
  case Paragraph(content: List[ResolvedInline])
  case BulletList(items: List[List[ResolvedBlock]])
  case OrderedList(items: List[List[ResolvedBlock]])
  case Table(rows: List[List[List[ResolvedBlock]]], headerRows: Int)
  case ClauseRef(clauseKey: String, version: Int)
  case PageBreak

  def plainText: String = this match
    case Heading(_, content) => content.map(_.plainText).mkString
    case Paragraph(content)  => content.map(_.plainText).mkString
    case BulletList(items)   => items.map(i => "- " + i.map(_.plainText).mkString(" ")).mkString("\n")
    case OrderedList(items)  =>
      items.zipWithIndex.map((i, n) => s"${n + 1}. " + i.map(_.plainText).mkString(" ")).mkString("\n")
    case Table(rows, _) => rows.map(_.map(_.map(_.plainText).mkString(" ")).mkString(" | ")).mkString("\n")
    case ClauseRef(key, version) => s"[clause $key v$version]"
    case PageBreak               => ""

/** Which branch a conditional took during a resolution; inside repeats a conditional may take both. */
final case class BranchOutcome(location: Location, condition: String, outcomes: Set[Boolean])

/** Result of resolving a template with field values.
  *
  * @param missingRequired
  *   paths of required values that were missing; a document with missing required values cannot be released
  * @param branches
  *   the outcome of every conditional that was evaluated, in document order
  */
final case class Resolution(
    content: ResolvedContent,
    missingRequired: List[String],
    branches: List[BranchOutcome]
)

enum ResolveError:
  /** The template does not fit the schema; found on save, but also checked before resolving. */
  case Invalid(issues: List[ContentIssue])

  /** An expression failed at runtime, e.g. a division by zero or a value of the wrong type. */
  case Evaluation(location: Location, detail: String)

  /** A limit of [[Limits]] was exceeded. */
  case LimitExceeded(location: Option[Location], detail: String)

  /** Given values do not match the schema, e.g. text for a number field. */
  case InvalidValues(problems: List[String])

  def message: String = this match
    case Invalid(issues)                 => issues.map(_.show).mkString("; ")
    case Evaluation(location, detail)    => s"${location.show}: $detail"
    case LimitExceeded(location, detail) => location.fold(detail)(l => s"${l.show}: $detail")
    case InvalidValues(problems)         => problems.mkString("; ")
