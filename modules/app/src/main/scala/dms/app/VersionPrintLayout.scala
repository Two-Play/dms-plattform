package dms.app

import dms.rendering.core.{RenditionFacts, RenditionLayout}
import dms.templates.core.HtmlRenderer.escape

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate, ZoneId}
import java.util.Locale

/** One version in the change history appendix (DMS-112).
  *
  * @param version
  *   the version label, e.g. `1.0` or `1.1-draft.3`
  * @param changeKind
  *   `Major` or `Minor`
  * @param changeReason
  *   the author's change summary
  * @param approvedBy
  *   display name (or user id) of the approver; with `actingFor` the approver a deputy stood in for
  */
final case class ChangelogEntry(
    version: String,
    changeKind: String,
    changeReason: Option[String],
    status: String,
    createdAt: Instant,
    createdBy: String,
    approvedAt: Option[Instant],
    approvedBy: Option[String],
    actingFor: Option[String],
    validFrom: Option[LocalDate],
    publishedAt: Option[Instant]
)

/** One attachment in the list of attachments appendix ("Anlagenverzeichnis", DMS-115).
  *
  * @param size
  *   in bytes
  * @param sha256
  *   lowercase hex SHA-256 of the stored file
  */
final case class AttachmentEntry(name: String, size: Long, sha256: String)

/** What an on-demand PDF shows besides the content (DMS-112, ADR 0039).
  *
  * @param version
  *   the version label (`1.1-draft.3` while open)
  * @param documentNumber
  *   the document number, if the version has one (core field `documentNumber`, DMS-113)
  * @param generatedBy
  *   display name (or user id) of the person who downloads the PDF
  * @param zone
  *   the tenant's time zone for the generation time and the dates of the change history
  * @param copyOf
  *   for an approved version: the facts of its archive rendition (approval note, valid-from date) and its
  *   SHA-256; `None` for a version that was never approved, which is printed as a draft
  */
final case class PrintFacts(
    documentId: String,
    documentNumber: Option[String],
    title: String,
    version: String,
    language: String,
    generatedAt: Instant,
    generatedBy: String,
    zone: ZoneId,
    copyOf: Option[PrintFacts.Copy] = None
):
  def draft: Boolean = copyOf.isEmpty

object PrintFacts:
  final case class Copy(rendition: RenditionFacts, sha256: Option[String])

/** The running header and footer of on-demand PDFs of a version and the change history appendix (DMS-112, ADR
  * 0039), added to the stand-alone HTML page of the templates module like [[RenditionLayout]] does for the
  * archive rendition. Pure.
  *
  *   - **Draft PDF** (a version that was never approved): the header names the draft marking "Entwurf – nicht
  *     gültig" / "Draft – not valid" next to document id and version; the content carries the draft banner
  *     and a watermark on every page (`document.css`); the footer says when and by whom it was generated and
  *     that it is neither approved nor archived.
  *   - **Copy** (an approved version, only to attach the change history): header and footer like the archive
  *     rendition (valid-from date, approval note) plus "Copy generated on … – the archived rendition is
  *     authoritative" with its SHA-256.
  *
  * The change history lists the versions up to the printed one: label and kind of change, the change summary,
  * status with creation, approval and publication dates, and the approver.
  */
object VersionPrintLayout:

  /** Version of the layout below; written as generator into the PDF. Bump it when the output changes. */
  val Version: String = "print-layout-2"

  private final case class Labels(
      documentId: String,
      documentNumber: String,
      version: String,
      draft: String,
      copy: String,
      generated: String,
      by: String,
      notArchived: String,
      archived: String,
      page: String,
      of: String,
      changelog: String,
      changelogIntro: String,
      change: String,
      statusAndDates: String,
      approval: String,
      created: String,
      approvedOn: String,
      published: String,
      validFrom: String,
      onBehalfOf: String,
      noSummary: String,
      attachments: String,
      attachmentsIntro: String,
      noAttachments: String,
      name: String,
      size: String,
      bytes: String,
      kinds: Map[String, String],
      statuses: Map[String, String],
      date: DateTimeFormatter,
      dateTime: DateTimeFormatter
  )

  private val german = Labels(
    documentId = "Dokument-ID",
    documentNumber = "Dokumentnummer",
    version = "Version",
    draft = "Entwurf – nicht gültig",
    copy = "Kopie – maßgeblich ist die archivierte Fassung",
    generated = "Erzeugt am",
    by = "von",
    notArchived = "nicht freigegeben, nicht archiviert",
    archived = "Archivierte Fassung (PDF/A) SHA-256",
    page = "Seite",
    of = "von",
    changelog = "Änderungshistorie",
    changelogIntro = "Versionen bis einschließlich Version",
    change = "Änderung",
    statusAndDates = "Status und Daten",
    approval = "Freigabe",
    created = "Erstellt",
    approvedOn = "Freigegeben",
    published = "Veröffentlicht",
    validFrom = "Gültig ab",
    onBehalfOf = "i. V. für",
    noSummary = "ohne Änderungsgrund",
    attachments = "Anlagenverzeichnis",
    attachmentsIntro = "Anlagen der Version",
    noAttachments = "Die Version hat keine Anlagen.",
    name = "Name",
    size = "Größe",
    bytes = "Bytes",
    kinds = Map("Major" -> "Inhaltlich", "Minor" -> "Redaktionell"),
    statuses = Map(
      "Draft"      -> "Entwurf",
      "InReview"   -> "In Prüfung",
      "InApproval" -> "In Freigabe",
      "Approved"   -> "Freigegeben",
      "Published"  -> "Veröffentlicht",
      "ReviewDue"  -> "Rezertifizierung fällig",
      "Superseded" -> "Abgelöst",
      "Withdrawn"  -> "Außer Kraft",
      "Archived"   -> "Archiviert",
      "Destroyed"  -> "Vernichtet",
      "Discarded"  -> "Verworfen"
    ),
    date = DateTimeFormatter.ofPattern("dd.MM.yyyy"),
    dateTime = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
  )

  private val english = Labels(
    documentId = "Document ID",
    documentNumber = "Document number",
    version = "Version",
    draft = "Draft – not valid",
    copy = "Copy – the archived rendition is authoritative",
    generated = "Generated on",
    by = "by",
    notArchived = "not approved, not archived",
    archived = "Archived rendition (PDF/A) SHA-256",
    page = "Page",
    of = "of",
    changelog = "Change history",
    changelogIntro = "Versions up to and including version",
    change = "Change",
    statusAndDates = "Status and dates",
    approval = "Approval",
    created = "Created",
    approvedOn = "Approved",
    published = "Published",
    validFrom = "Valid from",
    onBehalfOf = "on behalf of",
    noSummary = "no change summary",
    attachments = "List of attachments",
    attachmentsIntro = "Attachments of version",
    noAttachments = "The version has no attachments.",
    name = "Name",
    size = "Size",
    bytes = "bytes",
    kinds = Map("Major" -> "Major", "Minor" -> "Minor (editorial)"),
    statuses = Map(
      "Draft"      -> "Draft",
      "InReview"   -> "In review",
      "InApproval" -> "In approval",
      "Approved"   -> "Approved",
      "Published"  -> "Published",
      "ReviewDue"  -> "Recertification due",
      "Superseded" -> "Superseded",
      "Withdrawn"  -> "Withdrawn",
      "Archived"   -> "Archived",
      "Destroyed"  -> "Destroyed",
      "Discarded"  -> "Discarded"
    ),
    date = DateTimeFormatter.ISO_LOCAL_DATE,
    dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
  )

  /** The name of a status in the document language, e.g. "Freigegeben". */
  def statusLabel(status: String, language: String): String =
    labels(language).statuses.getOrElse(status, status)

  /** A date in the document language's format. */
  def date(value: java.time.LocalDate, language: String): String = labels(language).date.format(value)

  private def labels(language: String): Labels =
    if language.toLowerCase(Locale.ROOT).startsWith("de") then german else english

  /** The marking of the header: the draft marking, or the copy note of an approved version. */
  def marking(facts: PrintFacts): String =
    val l = labels(facts.language)
    if facts.draft then l.draft else l.copy

  /** Document id (and number) and version, as in the header on the right. */
  def identification(facts: PrintFacts): String =
    val l      = labels(facts.language)
    val number = facts.documentNumber.fold("")(n => s"${l.documentNumber} $n · ")
    s"$number${l.documentId} ${facts.documentId} · ${l.version} ${facts.version}"

  /** "Generated on <date time> (<zone>) by <name>". */
  def generated(facts: PrintFacts): String =
    val l    = labels(facts.language)
    val when = l.dateTime.format(facts.generatedAt.atZone(facts.zone))
    s"${l.generated} $when (${facts.zone.getId}) ${l.by} ${facts.generatedBy}"

  /** The footer text on the left. */
  def footer(facts: PrintFacts): String =
    val l = labels(facts.language)
    facts.copyOf match
      case None       => s"${generated(facts)} · ${l.notArchived}"
      case Some(copy) =>
        List(
          Some(RenditionLayout.validity(copy.rendition)),
          Some(RenditionLayout.approvalNote(copy.rendition)),
          Some(generated(facts)),
          copy.sha256.map(sha => s"${l.archived} $sha")
        ).flatten.mkString("\n")

  /** The page rules with the running header and footer. */
  def pageCss(facts: PrintFacts): String =
    val l      = labels(facts.language)
    val margin =
      "font-family: \"Noto Sans\", sans-serif; font-size: 7.5pt; color: #333; white-space: pre-wrap;"
    val mark  = if facts.draft then "color: #b42318; font-weight: 700;" else ""
    val right = s"${marking(facts)}\n${identification(facts)}"
    s"""@page {
       |  ${if facts.draft then "" else "margin-bottom: 34mm;"}
       |  @top-left { content: ${RenditionLayout.css(facts.title)}; width: 50%; vertical-align: bottom;
       |    padding-bottom: 3mm; border-bottom: 0.5pt solid #888; $margin }
       |  @top-right { content: ${RenditionLayout.css(right)}; width: 50%; text-align: right;
       |    vertical-align: bottom; padding-bottom: 3mm; border-bottom: 0.5pt solid #888; $margin $mark }
       |  @bottom-left { content: ${RenditionLayout.css(footer(facts))}; width: 75%; vertical-align: top;
       |    padding-top: 3mm; border-top: 0.5pt solid #888; $margin }
       |  @bottom-right { content: ${RenditionLayout.css(l.page + " ")} counter(page) ${RenditionLayout.css(
        " " + l.of + " "
      )} counter(pages);
       |    width: 25%; text-align: right; vertical-align: top; padding-top: 3mm; border-top: 0.5pt solid #888; $margin }
       |}
       |.dms-document .dms-changelog .dms-table { font-size: 8.5pt; }
       |.dms-document .dms-changelog td { vertical-align: top; }
       |.dms-document .dms-changelog .dms-changelog-current td { font-weight: 700; }
       |.dms-document .dms-attachment-list .dms-table { font-size: 8.5pt; }
       |.dms-document .dms-attachment-list .dms-checksum { font-family: "Noto Sans Mono", monospace; font-size: 7pt;
       |  overflow-wrap: anywhere; }
       |""".stripMargin

  /** Document metadata as `<meta>` tags. */
  def meta(facts: PrintFacts): String =
    val created = java.time.format.DateTimeFormatter.ISO_INSTANT
      .format(facts.generatedAt.truncatedTo(ChronoUnit.SECONDS))
    List(
      s"""<meta name="keywords" content="${escape(facts.documentId)}">""",
      s"""<meta name="keywords" content="${escape("version " + facts.version)}">""",
      s"""<meta name="keywords" content="${escape(marking(facts))}">""",
      s"""<meta name="generator" content="${escape("DMS " + Version)}">""",
      s"""<meta name="dcterms.created" content="$created">""",
      s"""<meta name="dcterms.modified" content="$created">"""
    ).mkString("\n")

  /** The change history appendix: a section after a page break (unless it starts the page), one row per
    * version, oldest first.
    */
  def changelog(entries: List[ChangelogEntry], facts: PrintFacts, pageBreak: Boolean = true): String =
    val l                  = labels(facts.language)
    def date(i: Instant)   = l.date.format(i.atZone(facts.zone))
    def status(s: String)  = l.statuses.getOrElse(s, s)
    def kind(k: String)    = l.kinds.getOrElse(k, k)
    def line(text: String) = s"<div>${escape(text)}</div>"
    val rows               = entries.map { e =>
      val dates = List(
        Some(s"${l.created} ${date(e.createdAt)} ${l.by} ${e.createdBy}"),
        e.approvedAt.map(a => s"${l.approvedOn} ${date(a)}"),
        e.validFrom.map(d => s"${l.validFrom} ${l.date.format(d)}"),
        e.publishedAt.map(p => s"${l.published} ${date(p)}")
      ).flatten
      val approver = e.approvedBy.fold("–")(a => a + e.actingFor.fold("")(f => s" (${l.onBehalfOf} $f)"))
      val current  = if e.version == facts.version then " class=\"dms-changelog-current\"" else ""
      s"<tr$current><td>${escape(e.version)}<div>${escape(kind(e.changeKind))}</div></td>" +
        s"<td>${escape(e.changeReason.filter(_.trim.nonEmpty).getOrElse(l.noSummary))}</td>" +
        s"<td>${line(status(e.status))}${dates.map(line).mkString}</td>" +
        s"<td>${escape(approver)}</td></tr>"
    }
    s"""${if pageBreak then "<div class=\"dms-page-break\"></div>" else ""}
       |<section class="dms-section dms-changelog" data-section="changelog">
       |<h2 class="dms-section-title">${escape(l.changelog)}</h2>
       |<p class="dms-paragraph">${escape(s"${l.changelogIntro} ${facts.version}")}</p>
       |<table class="dms-table"><thead><tr><th scope="col">${escape(l.version)}</th><th scope="col">${escape(
        l.change
      )}</th><th scope="col">${escape(l.statusAndDates)}</th><th scope="col">${escape(
        l.approval
      )}</th></tr></thead>
       |<tbody>${rows.mkString("\n")}</tbody></table>
       |</section>
       |""".stripMargin

  /** The list of attachments appendix ("Anlagenverzeichnis", DMS-115): a section after a page break (unless
    * it starts the page), one row per attachment with name, size and SHA-256, in the order they were added.
    */
  def attachmentList(entries: List[AttachmentEntry], facts: PrintFacts, pageBreak: Boolean = true): String =
    val l      = labels(facts.language)
    val locale =
      if facts.language.toLowerCase(Locale.ROOT).startsWith("de") then Locale.GERMANY else Locale.UK
    val rows = entries.map { a =>
      s"<tr><td>${escape(a.name)}</td><td>${escape(String.format(locale, "%,d", a.size) + " " + l.bytes)}</td>" +
        s"<td class=\"dms-checksum\">${escape(a.sha256)}</td></tr>"
    }
    val body =
      if entries.isEmpty then s"""<p class="dms-paragraph">${escape(l.noAttachments)}</p>"""
      else
        s"""<table class="dms-table"><thead><tr><th scope="col">${escape(
            l.name
          )}</th><th scope="col">${escape(
            l.size
          )}</th><th scope="col">SHA-256</th></tr></thead>
           |<tbody>${rows.mkString("\n")}</tbody></table>""".stripMargin
    s"""${if pageBreak then "<div class=\"dms-page-break\"></div>" else ""}
       |<section class="dms-section dms-attachment-list" data-section="attachments">
       |<h2 class="dms-section-title">${escape(l.attachments)}</h2>
       |<p class="dms-paragraph">${escape(s"${l.attachmentsIntro} ${facts.version}")}</p>
       |$body
       |</section>
       |""".stripMargin

  /** The appendices in print order: the change history, then the list of attachments. With `startsPage` the
    * first one starts the page (a page of appendices only, e.g. after the original of a file document).
    */
  def appendices(
      facts: PrintFacts,
      history: Option[List[ChangelogEntry]],
      attachments: Option[List[AttachmentEntry]],
      startsPage: Boolean = false
  ): String =
    val parts = history.map(h => changelog(h, facts, _)).toList ++
      attachments.map(a => attachmentList(a, facts, _)).toList
    parts.zipWithIndex.map((part, i) => part(!(startsPage && i == 0))).mkString

  /** The page for the Word export (DMS-115): the marking and identification as first paragraph, since Word
    * has no running header from CSS page rules; `.dms-page-break` as page break before.
    */
  def wordPage(page: String, facts: PrintFacts): String =
    val body   = page.indexOf("<body>")
    val note   = s"${marking(facts)} · ${identification(facts)} · ${generated(facts)}"
    val color  = if facts.draft then "#b42318" else "#333333"
    val marker =
      s"""<p class="dms-word-marking" style="color: $color; font-size: 8pt; font-weight: 700">${escape(
          note
        )}</p>
         |<style>.dms-page-break { page-break-before: always; }</style>
         |""".stripMargin
    if body < 0 then marker + page
    else page.substring(0, body + "<body>".length) + "\n" + marker + page.substring(body + "<body>".length)

  /** Adds page rules, metadata and (if given) the change history and the list of attachments to a stand-alone
    * HTML page of the templates module (`<head>` and one `article.dms-document`).
    */
  def compose(
      page: String,
      facts: PrintFacts,
      history: Option[List[ChangelogEntry]],
      attachments: Option[List[AttachmentEntry]] = None,
      startsPage: Boolean = false
  ): Either[String, String] =
    val head = page.indexOf("</head>")
    val end  = page.lastIndexOf("</article>")
    if head < 0 then Left("the page has no </head>")
    else if end < 0 then Left("the page has no </article>")
    else
      val title    = if page.contains("<title>") then "" else s"<title>${escape(facts.title)}</title>\n"
      val extra    = s"$title${meta(facts)}\n<style>\n${pageCss(facts)}</style>\n"
      val appendix = appendices(facts, history, attachments, startsPage)
      Right(page.substring(0, head) + extra + page.substring(head, end) + appendix + page.substring(end))
