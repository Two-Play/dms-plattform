package dms.templates.core

import dms.templates.*

import java.nio.charset.StandardCharsets

/** Options of [[HtmlRenderer]].
  *
  * @param language
  *   document language, written to the `lang` attribute
  * @param labels
  *   field labels by field key, shown in markers of missing values; without a label the path is shown
  * @param draftMarking
  *   text of the draft banner and watermark, e.g. "Draft – not valid"; `None` for final renditions
  * @param title
  *   document title for the `<title>` of a stand-alone document
  * @param systemFields
  *   keys of the fields the platform sets (document ID, version, approval date, ...); before the first save
  *   they have no value yet, so their placeholders show the label and a neutral hint instead of a
  *   missing-value marker
  */
final case class HtmlOptions(
    language: Language = Language.default,
    labels: Map[String, String] = Map.empty,
    draftMarking: Option[String] = None,
    title: Option[String] = None,
    systemFields: Set[String] = Set.empty
)

object HtmlOptions:

  /** The draft marking of business concept US-03.2 in the document language. */
  def draftLabel(language: Language): String = language match
    case Language.De => "Entwurf – nicht gültig"
    case Language.En => "Draft – not valid"

  /** The hint next to a system field without a value, in the document language. */
  def systemHint(language: Language): String = language match
    case Language.De => "wird beim Speichern vergeben"
    case Language.En => "assigned on save"

  /** The hint of a computed field without value, naming the fields it needs, in the document language. */
  def missingInputsHint(language: Language, inputs: List[String]): String = language match
    case Language.De => s"fehlt: ${inputs.mkString(", ")}"
    case Language.En => s"missing: ${inputs.mkString(", ")}"

  def draft(
      language: Language,
      labels: Map[String, String] = Map.empty,
      systemFields: Set[String] = Set.empty
  ): HtmlOptions =
    HtmlOptions(language, labels, Some(draftLabel(language)), systemFields = systemFields)

/** Renders resolved content as semantic HTML (ADR 0007, 0008): the browser preview shows the fragment of
  * [[render]], the PDF worker prints the stand-alone document of [[renderDocument]]. Both use the stylesheet
  * [[stylesheet]] (`dms/templates/document.css`), which targets the stable classes below.
  *
  * Safety: all text and attribute values are escaped; content never carries raw HTML, so field values cannot
  * inject markup or scripts. No ids, no inline styles, no scripts, no external references.
  *
  * Markup contract (class names are stable; the stylesheet and the frontend rely on them):
  *   - `article.dms-document` (plus `dms-draft` and `data-watermark` when drafted), `lang` = document
  *     language
  *   - `div.dms-draft-banner[role=note]` with the draft marking
  *   - `section.dms-section[data-section][data-locked]`, title as `h2.dms-section-title`
  *   - `h1`–`h6.dms-heading.dms-heading-N`, `p.dms-paragraph`, `ul.dms-list`, `ol.dms-list`, `li`
  *   - `table.dms-table` with `thead` for header rows (`th scope=col`) and `tbody`
  *   - `div.dms-clause-ref[data-clause][data-clause-version]`, `div.dms-page-break`
  *   - `span.dms-value[data-field]` for a field value, `span.dms-missing[data-field][data-required]` (plus
  *     `dms-missing-required`) with the field label for a missing value; for a computed field that has no
  *     value because it reads fields without value also `dms-missing-computed`, `data-missing-inputs` (the
  *     field keys, space-separated) and `span.dms-missing-hint` naming those fields by label
  *   - `span.dms-system[data-field]` with the field label and `span.dms-system-hint` for a field the platform
  *     sets that has no value yet ([[HtmlOptions.systemFields]])
  *   - marks as `strong`, `em`, `u`; line breaks as `br`
  */
object HtmlRenderer:

  /** The shared document stylesheet. */
  lazy val stylesheet: String =
    val stream = Option(getClass.getResourceAsStream("/dms/templates/document.css"))
      .getOrElse(throw IllegalStateException("resource dms/templates/document.css is missing"))
    try String(stream.readAllBytes(), StandardCharsets.UTF_8)
    finally stream.close()

  /** The content as an HTML fragment (one `article` element). */
  def render(resolved: ResolvedContent, options: HtmlOptions = HtmlOptions()): String =
    val out = Out(4096)
    article(resolved, options, out)
    out.result

  /** A stand-alone HTML document with the stylesheet embedded, for the PDF worker. */
  def renderDocument(resolved: ResolvedContent, options: HtmlOptions = HtmlOptions()): String =
    val out = Out(16384)
    out("<!DOCTYPE html>\n<html lang=\"", escape(options.language.code), "\">\n<head>\n")
    out("<meta charset=\"utf-8\">\n")
    options.title.foreach(t => out("<title>", escape(t), "</title>\n"))
    out("<style>\n", stylesheet, "</style>\n</head>\n<body>\n")
    article(resolved, options, out)
    out("\n</body>\n</html>\n")
    out.result

  /** Escapes text for element content and double-quoted attribute values. */
  def escape(text: String): String =
    if !text.exists(c => c == '&' || c == '<' || c == '>' || c == '"' || c == '\'') then text
    else
      val out = Out(text.length + 16)
      text.foreach {
        case '&'  => out("&amp;")
        case '<'  => out("&lt;")
        case '>'  => out("&gt;")
        case '"'  => out("&quot;")
        case '\'' => out("&#39;")
        case c    => out.char(c)
      }
      out.result

  /** The label of a field path: `monthlyFee.currency` → `Monthly fee (currency)`; the path without a label.
    */
  def label(path: String, labels: Map[String, String]): String =
    labels.getOrElse(
      path,
      path.split('.').toList match
        case root :: rest if labels.contains(root) && rest.nonEmpty =>
          s"${labels(root)} (${rest.mkString(".")})"
        case _ => path
    )

  /** Append-only output; every write returns `Unit`. */
  private final class Out(capacity: Int):
    private val builder             = java.lang.StringBuilder(capacity)
    def apply(parts: String*): Unit = parts.foreach(p => builder.append(p): Unit)
    def char(c: Char): Unit         = builder.append(c): Unit
    def result: String              = builder.toString

  private def article(resolved: ResolvedContent, options: HtmlOptions, out: Out): Unit =
    out("<article class=\"dms-document", if options.draftMarking.isDefined then " dms-draft" else "")
    out("\" lang=\"", escape(options.language.code), "\"")
    options.draftMarking.foreach(m => out(" data-watermark=\"", escape(m), "\""))
    out(">")
    options.draftMarking.foreach { m =>
      out("<div class=\"dms-draft-banner\" role=\"note\">", escape(m), "</div>")
    }
    resolved.sections.foreach(section(_, options, out))
    out("</article>")

  private def section(s: ResolvedSection, options: HtmlOptions, out: Out): Unit =
    out("<section class=\"dms-section\" data-section=\"", escape(s.id.value))
    out("\" data-locked=\"", s.locked.toString, "\">")
    s.title.foreach(t => out("<h2 class=\"dms-section-title\">", escape(t), "</h2>"))
    s.blocks.foreach(block(_, options, out))
    out("</section>")

  private def block(b: ResolvedBlock, options: HtmlOptions, out: Out): Unit = b match
    case ResolvedBlock.Heading(level, content) =>
      val n = level.max(1).min(6)
      out(s"<h$n class=\"dms-heading dms-heading-$n\">")
      inlines(content, options, out)
      out(s"</h$n>")
    case ResolvedBlock.Paragraph(content) =>
      out("<p class=\"dms-paragraph\">")
      inlines(content, options, out)
      out("</p>")
    case ResolvedBlock.BulletList(items)       => list("ul", items, options, out)
    case ResolvedBlock.OrderedList(items)      => list("ol", items, options, out)
    case ResolvedBlock.Table(rows, headerRows) =>
      val (head, body) = rows.splitAt(headerRows.max(0))
      out("<table class=\"dms-table\">")
      if head.nonEmpty then
        out("<thead>")
        head.foreach(row(_, header = true, options, out))
        out("</thead>")
      if body.nonEmpty then
        out("<tbody>")
        body.foreach(row(_, header = false, options, out))
        out("</tbody>")
      out("</table>")
    case ResolvedBlock.ClauseRef(key, version) =>
      out("<div class=\"dms-clause-ref\" data-clause=\"", escape(key))
      out("\" data-clause-version=\"", version.toString, "\">")
      out(escape(clauseLabel(key, version, options.language)), "</div>")
    case ResolvedBlock.PageBreak =>
      out("<div class=\"dms-page-break\" aria-hidden=\"true\"></div>")

  private def clauseLabel(key: String, version: Int, language: Language): String = language match
    case Language.De => s"Klausel $key, Version $version"
    case Language.En => s"Clause $key, version $version"

  private def list(tag: String, items: List[List[ResolvedBlock]], options: HtmlOptions, out: Out): Unit =
    out(s"<$tag class=\"dms-list\">")
    items.foreach { item =>
      out("<li>")
      item.foreach(block(_, options, out))
      out("</li>")
    }
    out(s"</$tag>")

  private def row(cells: List[List[ResolvedBlock]], header: Boolean, options: HtmlOptions, out: Out): Unit =
    out("<tr>")
    cells.foreach { cell =>
      out(if header then "<th scope=\"col\">" else "<td>")
      cell.foreach(block(_, options, out))
      out(if header then "</th>" else "</td>")
    }
    out("</tr>")

  private def inlines(content: List[ResolvedInline], options: HtmlOptions, out: Out): Unit =
    content.foreach {
      case ResolvedInline.Text(value, marks) => marked(marks, out)(out(escape(value)))
      case ResolvedInline.Value(path, text)  =>
        out("<span class=\"dms-value\" data-field=\"", escape(path), "\">", escape(text), "</span>")
      case ResolvedInline.Missing(path, _, _) if options.systemFields.contains(path.takeWhile(_ != '.')) =>
        out("<span class=\"dms-system\" data-field=\"", escape(path), "\">")
        out(escape(label(path, options.labels)), " <span class=\"dms-system-hint\">(")
        out(escape(HtmlOptions.systemHint(options.language)), ")</span></span>")
      case ResolvedInline.Missing(path, required, inputs) =>
        out("<span class=\"dms-missing", if required then " dms-missing-required" else "")
        if inputs.nonEmpty then out(" dms-missing-computed")
        out("\" data-field=\"", escape(path), "\" data-required=\"", required.toString, "\"")
        if inputs.nonEmpty then out(" data-missing-inputs=\"", escape(inputs.mkString(" ")), "\"")
        out(">", escape(label(path, options.labels)))
        if inputs.nonEmpty then
          val hint = HtmlOptions.missingInputsHint(options.language, inputs.map(label(_, options.labels)))
          out(" <span class=\"dms-missing-hint\">(", escape(hint), ")</span>")
        out("</span>")
      case ResolvedInline.LineBreak => out("<br>")
    }

  private val order = List(Mark.Bold -> "strong", Mark.Italic -> "em", Mark.Underline -> "u")

  private def marked(marks: Set[Mark], out: Out)(body: => Unit): Unit =
    val tags = order.collect { case (m, tag) if marks.contains(m) => tag }
    tags.foreach(t => out(s"<$t>"))
    body
    tags.reverse.foreach(t => out(s"</$t>"))
