package dms.notifications.core

import dms.kernel.UserId
import dms.notifications.*

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDate, ZoneId}
import java.util.Locale
import scala.util.Try

/** A notification in one language: a subject line, one or two sentences and the path in the web application
  * it leads to.
  */
final case class Rendered(subject: String, body: String, link: Option[String])

/** What a template may use besides the notice's parameters.
  *
  * @param recipient
  *   the user the notification is for (texts differ for principal and deputy of a deputyship)
  * @param document
  *   the facts of the document if the recipient may read it now; `None` for notices without document and for
  *   recipients who may not read it (the text then names no title, ADR 0052)
  * @param names
  *   display names of the people named in the parameters (ids stay where the directory has none)
  */
final case class TemplateContext(
    recipient: UserId,
    document: Option[DocumentFacts],
    names: Map[UserId, String],
    zone: ZoneId
)

/** German and English texts of every notification kind (DMS-130, ADR 0052). Texts are plain text; the email
  * adds a greeting, the absolute link and a footer ([[NotificationTemplates.email]]).
  */
object NotificationTemplates:

  /** Parameters holding user ids; the delivery resolves their display names. */
  val PersonParams: Set[String] =
    Set("returnedBy", "approvedBy", "principal", "deputy", "actingFor", "assignee")

  /** Kinds this module has texts for. */
  val Kinds: Set[String] = Set(
    "workflow.task-assigned",
    "workflow.changes-requested",
    "workflow.rejected",
    "workflow.approved",
    "workflow.participant-missing",
    "acknowledgement.requested",
    "acknowledgement.reminder",
    "deputyship.created",
    "deputyship.started",
    "deputyship.ended",
    "deputyship.ended-by-period",
    "publication.published",
    "legal-hold.created",
    "legal-hold.released"
  )

  def render(
      kind: String,
      language: Language,
      parameters: Map[String, String],
      about: Option[DocumentRef],
      context: TemplateContext
  ): Rendered =
    val t        = Texts(language, parameters, about, context)
    val docLink  = t.documentLink
    val tasks    = Some("/tasks")
    val deputies = Some("/deputies")
    import t.*
    kind match
      case "workflow.task-assigned" =>
        val approval = params.get("stepKind").contains("Approval")
        r(
          de =
            if approval then s"Freigabe erbeten: $docShort" else s"Prüfung erbeten: $docShort",
          en = if approval then s"Approval requested: $docShort" else s"Review requested: $docShort"
        )(
          bodyDe = s"Sie sind für den Schritt „${p("step")}“ von $docDat eingeteilt.$actingForDe",
          bodyEn = s"You are assigned to step “${p("step")}” of $doc.$actingForEn"
        )(tasks)
      case "workflow.changes-requested" =>
        r(
          de = s"Änderungen erbeten: $docShort",
          en = s"Changes requested: $docShort"
        )(
          bodyDe = s"${person("returnedBy")} hat im Schritt „${p("step")}“ Änderungen an $docDat erbeten.",
          bodyEn = s"${person("returnedBy")} requested changes to $doc in step “${p("step")}”."
        )(docLink)
      case "workflow.rejected" =>
        r(de = s"Abgelehnt: $docShort", en = s"Rejected: $docShort")(
          bodyDe = s"${person("returnedBy")} hat $doc im Schritt „${p("step")}“ abgelehnt.",
          bodyEn = s"${person("returnedBy")} rejected $doc in step “${p("step")}”."
        )(docLink)
      case "workflow.approved" =>
        r(de = s"Freigegeben: $docShort", en = s"Approved: $docShort")(
          bodyDe = s"${person("approvedBy")} hat $doc freigegeben.",
          bodyEn = s"${person("approvedBy")} approved $doc."
        )(docLink)
      case "workflow.participant-missing" =>
        r(
          de = s"Niemand kann den Schritt übernehmen: $docShort",
          en = s"Nobody can take the step: $docShort"
        )(
          bodyDe =
            s"Für den Schritt „${p("step")}“ von $docDat hat niemand die Rolle ${p("role")}. Bitte weisen Sie die Rolle zu und lösen Sie die Teilnehmer neu auf.",
          bodyEn =
            s"Nobody holds the role ${p("role")} for step “${p("step")}” of $doc. Please assign the role and resolve the participants again."
        )(tasks)
      case "acknowledgement.requested" =>
        r(de = s"Kenntnisnahme erforderlich: $docShort", en = s"Acknowledgement required: $docShort")(
          bodyDe = s"Bitte lesen Sie $doc und bestätigen Sie die Kenntnisnahme.",
          bodyEn = s"Please read $doc and acknowledge it."
        )(docLink)
      case "acknowledgement.reminder" =>
        r(
          de = s"Erinnerung: Kenntnisnahme offen – $docShort",
          en = s"Reminder: acknowledgement open – $docShort"
        )(
          bodyDe = s"Sie haben die Kenntnisnahme von $docDat noch nicht bestätigt.",
          bodyEn = s"You have not acknowledged $doc yet."
        )(docLink)
      case "deputyship.created" | "deputyship.started" | "deputyship.ended" | "deputyship.ended-by-period" =>
        deputyship(kind, t)(deputies)
      case "publication.published" =>
        r(de = s"Gültig: $docShort", en = s"Now valid: $docShort")(
          bodyDe = s"$docCapitalDe, dessen Eigentümer Sie sind, ist jetzt gültig${validFromDe}.",
          bodyEn = s"$docCapitalEn, which you own, is now valid${validFromEn}."
        )(docLink)
      case "legal-hold.created" =>
        r(de = s"Legal Hold gesetzt: ${p("name")}", en = s"Legal hold set: ${p("name")}")(
          bodyDe = s"Der Legal Hold „${p("name")}“ wurde gesetzt und umfasst ${p("documents")} Dokument(e).",
          bodyEn = s"The legal hold “${p("name")}” was set and covers ${p("documents")} document(s)."
        )(Some("/legal-holds"))
      case "legal-hold.released" =>
        r(de = s"Legal Hold aufgehoben: ${p("name")}", en = s"Legal hold released: ${p("name")}")(
          bodyDe = s"Der Legal Hold „${p("name")}“ wurde aufgehoben.",
          bodyEn = s"The legal hold “${p("name")}” was released."
        )(Some("/legal-holds"))
      case other =>
        r(de = "Neue Benachrichtigung", en = "New notification")(
          bodyDe = s"Es gibt eine Neuigkeit ($other)${docSuffixDe}.",
          bodyEn = s"There is news ($other)${docSuffixEn}."
        )(docLink)

  private def deputyship(kind: String, t: Texts)(link: Option[String]): Rendered =
    import t.*
    val principalIsMe = params.get("principal").contains(ctx.recipient.value)
    val until         = date("validUntil")
    val from          = date("validFrom")
    (kind, principalIsMe) match
      case ("deputyship.created", true) =>
        r(de = "Vertretung eingerichtet", en = "Deputy set")(
          bodyDe = s"${person("deputy")} vertritt Sie von $from bis $until.",
          bodyEn = s"${person("deputy")} stands in for you from $from until $until."
        )(link)
      case ("deputyship.created", false) =>
        r(de = "Sie sind als Vertretung eingetragen", en = "You were made deputy")(
          bodyDe = s"Sie vertreten ${person("principal")} von $from bis $until.",
          bodyEn = s"You stand in for ${person("principal")} from $from until $until."
        )(link)
      case ("deputyship.started", true) =>
        r(de = "Ihre Vertretung beginnt", en = "Your deputy starts")(
          bodyDe = s"${person("deputy")} vertritt Sie ab jetzt bis $until.",
          bodyEn = s"${person("deputy")} stands in for you from now until $until."
        )(link)
      case ("deputyship.started", false) =>
        r(de = "Ihre Vertretung beginnt", en = "Your deputyship starts")(
          bodyDe =
            s"Sie vertreten ${person("principal")} ab jetzt bis $until. Offene Aufgaben finden Sie im Posteingang.",
          bodyEn =
            s"You stand in for ${person("principal")} from now until $until. Open tasks are in your inbox."
        )(Some("/tasks"))
      case (_, true) =>
        r(de = "Vertretung beendet", en = "Deputy ended")(
          bodyDe = s"Die Vertretung durch ${person("deputy")} ist beendet.",
          bodyEn = s"${person("deputy")} no longer stands in for you."
        )(link)
      case (_, false) =>
        r(de = "Vertretung beendet", en = "Deputyship ended")(
          bodyDe = s"Ihre Vertretung für ${person("principal")} ist beendet.",
          bodyEn = s"You no longer stand in for ${person("principal")}."
        )(link)

  /** The email of a rendered notification: greeting, text, absolute link, footer with the settings page. */
  def email(
      language: Language,
      name: Option[String],
      rendered: Rendered,
      appBaseUrl: String
  ): (String, String) =
    val base = appBaseUrl.stripSuffix("/")
    val link = rendered.link.map(l => s"$base$l")
    language match
      case Language.De =>
        val text =
          List(
            Some(name.fold("Hallo,")(n => s"Hallo $n,")),
            Some(""),
            Some(rendered.body),
            link.map(l => s"\nÖffnen: $l"),
            Some(""),
            Some("-- "),
            Some("Diese Nachricht hat das Dokumentenmanagement automatisch versendet."),
            Some(s"E-Mail-Benachrichtigungen ändern: $base/settings/notifications")
          ).flatten.mkString("\n")
        (s"[DMS] ${rendered.subject}", text)
      case Language.En =>
        val text =
          List(
            Some(name.fold("Hello,")(n => s"Hello $n,")),
            Some(""),
            Some(rendered.body),
            link.map(l => s"\nOpen: $l"),
            Some(""),
            Some("-- "),
            Some("This message was sent automatically by the document management system."),
            Some(s"Change email notifications: $base/settings/notifications")
          ).flatten.mkString("\n")
        (s"[DMS] ${rendered.subject}", text)

  private final class Texts(
      language: Language,
      val params: Map[String, String],
      document: Option[DocumentRef],
      val ctx: TemplateContext
  ):
    def documentLink: Option[String] = document.map(d => s"/documents/${d.documentId.value}")

    /** A [[Rendered]] in the recipient's language from both texts. */
    def r(de: String, en: String)(bodyDe: String, bodyEn: String)(link: Option[String]): Rendered =
      if language == Language.De then Rendered(de, bodyDe, link) else Rendered(en, bodyEn, link)

    def p(key: String): String = params.getOrElse(key, "?")

    def person(key: String): String =
      params.get(key).fold("?")(id => ctx.names.getOrElse(UserId(id), id))

    private val version = ctx.document.flatMap(_.versionLabel).orElse(params.get("version"))

    /** The document in a sentence: title and version if the recipient may read it, else no title. */
    def doc: String = (document, ctx.document) match
      case (_, Some(f)) =>
        if language == Language.De then s"„${f.title}“${version.fold("")(v => s" (Version $v)")}"
        else s"“${f.title}”${version.fold("")(v => s" (version $v)")}"
      case (Some(_), None) => if language == Language.De then "ein Dokument" else "a document"
      case (None, None)    => ""

    /** The document in a German dative phrase ("von …", "an …"). */
    def docDat: String =
      if document.isDefined && ctx.document.isEmpty && language == Language.De then "einem Dokument" else doc

    /** The document in a subject line. */
    def docShort: String = ctx.document match
      case Some(f) => s"${f.title}${version.fold("")(v => s" ($v)")}"
      case None    => if language == Language.De then "Dokument" else "document"

    def docCapitalDe: String = doc.headOption.fold(doc)(c => s"${c.toUpper}${doc.drop(1)}")
    def docCapitalEn: String = docCapitalDe

    def docSuffixDe: String = if document.isEmpty then "" else s" zu $docDat"
    def docSuffixEn: String = if document.isEmpty then "" else s" about $doc"

    def actingForDe: String =
      params.get("actingFor").fold("")(_ => s" Sie handeln als Vertretung von ${person("actingFor")}.")
    def actingForEn: String =
      params.get("actingFor").fold("")(_ => s" You act as deputy of ${person("actingFor")}.")

    def validFromDe: String =
      params.get("validFrom").flatMap(localDate).fold("")(d => s" (ab ${d.format(GermanDate)})")
    def validFromEn: String =
      params.get("validFrom").flatMap(localDate).fold("")(d => s" (from ${d.format(EnglishDate)})")

    /** An instant or date parameter in the tenant's time zone. */
    def date(key: String): String =
      params
        .get(key)
        .flatMap(v => Try(Instant.parse(v)).toOption.map(_.atZone(ctx.zone).toLocalDate).orElse(localDate(v)))
        .fold("?")(d => d.format(if language == Language.De then GermanDate else EnglishDate))

  private def localDate(v: String): Option[LocalDate] = Try(LocalDate.parse(v)).toOption

  private val GermanDate  = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY)
  private val EnglishDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
