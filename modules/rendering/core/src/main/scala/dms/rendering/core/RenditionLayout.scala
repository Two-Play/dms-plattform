package dms.rendering.core

import dms.kernel.TenantId

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.HexFormat

/** What the rendition shows about the version besides its content (business concept 5.1, US-06.1).
  *
  * @param documentId
  *   the document id as shown in the header and written to the XMP metadata
  * @param version
  *   the version number the version gets on approval, e.g. `1.0`
  * @param approvedAt
  *   when the last approving vote was cast; every date in the PDF is derived from it
  * @param approvedBy
  *   who approved, as shown in the approval note
  * @param language
  *   `de` or `en`; other languages fall back to English labels
  */
final case class RenditionFacts(
    documentId: String,
    title: String,
    version: String,
    validFrom: Option[LocalDate],
    approvedAt: Instant,
    approvedBy: String,
    language: String
)

/** The running header and footer and the document metadata of a rendition (DMS-44), added to the stand-alone
  * HTML page of the templates module (`HtmlRenderer.renderDocument`). Pure: equal facts give equal output, so
  * the PDF is reproducible (ADR 0008).
  *
  * Every page shows the title, document id and version in the header, and the valid-from date, the approval
  * note and the page number in the footer.
  */
object RenditionLayout:

  /** Version of the layout below; part of the renderer identity recorded with each rendition. Bump it when
    * the output changes.
    */
  val Version: String = "rendition-layout-1"

  private final case class Labels(
      documentId: String,
      version: String,
      validFrom: String,
      notSet: String,
      approved: String,
      by: String,
      page: String,
      of: String,
      date: DateTimeFormatter
  )

  private val german = Labels(
    "Dokument-ID",
    "Version",
    "Gültig ab",
    "nicht festgelegt",
    "Freigegeben am",
    "von",
    "Seite",
    "von",
    DateTimeFormatter.ofPattern("dd.MM.yyyy")
  )

  private val english = Labels(
    "Document ID",
    "Version",
    "Valid from",
    "not set",
    "Approved on",
    "by",
    "Page",
    "of",
    DateTimeFormatter.ISO_LOCAL_DATE
  )

  private def labels(language: String): Labels =
    if language.toLowerCase(java.util.Locale.ROOT).startsWith("de") then german else english

  private val time = DateTimeFormatter.ofPattern("HH:mm")

  /** The header text on the right: document id and version. */
  def identification(facts: RenditionFacts): String =
    val l = labels(facts.language)
    s"${l.documentId} ${facts.documentId} · ${l.version} ${facts.version}"

  /** The valid-from line of the footer. */
  def validity(facts: RenditionFacts): String =
    val l = labels(facts.language)
    s"${l.validFrom} ${facts.validFrom.fold(l.notSet)(l.date.format)}"

  /** The approval note of the footer (business concept 5.1: "Freigabevermerk"), in UTC. */
  def approvalNote(facts: RenditionFacts): String =
    val l    = labels(facts.language)
    val when = facts.approvedAt.atOffset(ZoneOffset.UTC)
    s"${l.approved} ${l.date.format(when)} ${time.format(when)} UTC ${l.by} ${facts.approvedBy}"

  /** The release timestamp written as creation and modification date: UTC, second precision. */
  def timestamp(facts: RenditionFacts): String =
    DateTimeFormatter.ISO_INSTANT.format(facts.approvedAt.truncatedTo(ChronoUnit.SECONDS))

  /** The PDF file identifier: SHA-256 of tenant, document and version, so a re-render of the same version
    * gets the same identifier (spike DMS-23).
    */
  def identifier(tenant: TenantId, facts: RenditionFacts): String =
    val digest = MessageDigest
      .getInstance("SHA-256")
      .digest(s"${tenant.value}:${facts.documentId}:${facts.version}".getBytes(StandardCharsets.UTF_8))
    HexFormat.of().formatHex(digest).take(32)

  /** Custom XMP properties of the rendition (declared by the worker in a PDF/A extension schema). */
  def xmp(facts: RenditionFacts): Map[String, String] =
    Map("documentId" -> facts.documentId, "version" -> facts.version) ++
      facts.validFrom.map(d => "validFrom" -> d.toString)

  /** The page rules with the running header and footer. */
  def pageCss(facts: RenditionFacts): String =
    val l      = labels(facts.language)
    val margin =
      "font-family: \"Noto Sans\", sans-serif; font-size: 7.5pt; color: #333; white-space: pre-wrap;"
    val footer = s"${validity(facts)}\n${approvalNote(facts)}"
    s"""@page {
       |  @top-left { content: ${css(facts.title)}; width: 55%; vertical-align: bottom; padding-bottom: 3mm;
       |    border-bottom: 0.5pt solid #888; $margin }
       |  @top-right { content: ${css(
        identification(facts)
      )}; width: 45%; text-align: right; vertical-align: bottom;
       |    padding-bottom: 3mm; border-bottom: 0.5pt solid #888; $margin }
       |  @bottom-left { content: ${css(footer)}; width: 75%; vertical-align: top; padding-top: 3mm;
       |    border-top: 0.5pt solid #888; $margin }
       |  @bottom-right { content: ${css(l.page + " ")} counter(page) ${css(" " + l.of + " ")} counter(pages);
       |    width: 25%; text-align: right; vertical-align: top; padding-top: 3mm; border-top: 0.5pt solid #888; $margin }
       |}
       |""".stripMargin

  /** Document metadata as `<meta>` tags; WeasyPrint writes them to the info dictionary and XMP. */
  def meta(facts: RenditionFacts): String =
    val created = timestamp(facts)
    List(
      s"""<meta name="keywords" content="${html(facts.documentId)}">""",
      s"""<meta name="keywords" content="${html("version " + facts.version)}">""",
      s"""<meta name="generator" content="${html("DMS " + Version)}">""",
      s"""<meta name="dcterms.created" content="$created">""",
      s"""<meta name="dcterms.modified" content="$created">"""
    ).mkString("\n")

  /** Adds the page rules and metadata to a stand-alone HTML page (`<head>` with a `<title>`). */
  def compose(page: String, facts: RenditionFacts): Either[String, String] =
    val head = page.indexOf("</head>")
    if head < 0 then Left("the page has no </head>")
    else
      val title = if page.contains("<title>") then "" else s"<title>${html(facts.title)}</title>\n"
      val extra = s"$title${meta(facts)}\n<style>\n${pageCss(facts)}</style>\n"
      Right(page.substring(0, head) + extra + page.substring(head))

  /** A CSS string literal. `<`, `>` and `&` are escaped too, so no value can close the `<style>` element. */
  def css(text: String): String =
    val out = StringBuilder("\"")
    text.foreach {
      case '"'                                   => out.append("\\\"")
      case '\\'                                  => out.append("\\\\")
      case '\n'                                  => out.append("\\A ")
      case c if c == '<' || c == '>' || c == '&' => out.append(f"\\${c.toInt}%x ")
      case c if c < ' '                          => out.append(' ')
      case c                                     => out.append(c)
    }
    out.append('"').toString

  private def html(text: String): String =
    text.flatMap {
      case '&'  => "&amp;"
      case '<'  => "&lt;"
      case '>'  => "&gt;"
      case '"'  => "&quot;"
      case '\'' => "&#39;"
      case c    => c.toString
    }
