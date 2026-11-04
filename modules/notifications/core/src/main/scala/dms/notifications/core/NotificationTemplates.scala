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
    Set("returnedBy", "approvedBy", "principal", "deputy", "actingFor", "assignee", "withdrawnBy")

  /** Notices of jobs that failed for good (DMS-142, ADR 0058). Parameters: `reason` (the failure code),
    * `audience` and `canRetry`.
    */
  val FailureKinds: Set[String] = Set("failure.rendition", "failure.publication")

  /** Who a failure notice is for, which decides where it leads: only some people can reach each place. */
  object Audience:
    /** The document's owner or, without owner, its author: the retry is on the document page. */
    val Owner = "owner"

    /** Tenant-wide records managers: they cannot open a version in approval, the list is on the legal holds
      * page.
      */
    val Records = "records"

    /** Tenant administrators: no legal holds page either, the list is in the administration. */
    val Admin = "admin"

  /** Kinds this module has texts for. */
  val Kinds: Set[String] = Set(
    "workflow.task-assigned",
    "workflow.changes-requested",
    "workflow.rejected",
    "workflow.approved",
    "workflow.participant-missing",
    "workflow.task-due-soon",
    "workflow.task-overdue",
    "workflow.task-escalated",
    "review.task-created",
    "review.task-reopened",
    "review.task-due-soon",
    "review.task-overdue",
    "review.task-escalated",
    "acknowledgement.requested",
    "acknowledgement.reminder",
    "deputyship.created",
    "deputyship.started",
    "deputyship.ended",
    "deputyship.ended-by-period",
    "publication.published",
    "failure.rendition",
    "failure.publication",
    "publication.withdrawn",
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
      case "workflow.task-due-soon" =>
        r(de = s"Frist läuft ab: $docShort", en = s"Due soon: $docShort")(
          bodyDe =
            s"Der Schritt „${p("step")}“ von $docDat ist bis ${date("dueOn")} zu erledigen.$actingForDe",
          bodyEn = s"Step “${p("step")}” of $doc is due by ${date("dueOn")}.$actingForEn"
        )(tasks)
      case "workflow.task-overdue" =>
        r(de = s"Überfällig: $docShort", en = s"Overdue: $docShort")(
          bodyDe =
            s"Der Schritt „${p("step")}“ von $docDat war bis ${date("dueOn")} zu erledigen und ist noch offen.$actingForDe",
          bodyEn = s"Step “${p("step")}” of $doc was due by ${date("dueOn")} and is still open.$actingForEn"
        )(tasks)
      case "workflow.task-escalated" =>
        r(de = s"Eskalation: $docShort", en = s"Escalated: $docShort")(
          bodyDe =
            s"Der Schritt „${p("step")}“ von $docDat ist seit ${date("dueOn")} überfällig. Bitte kümmern Sie sich darum oder sprechen Sie die Zuständigen an.",
          bodyEn =
            s"Step “${p("step")}” of $doc has been overdue since ${date("dueOn")}. Please take care of it or contact the participants."
        )(docLink)
      case "review.task-created" =>
        r(de = s"Überprüfung steht an: $docShort", en = s"Review coming up: $docShort")(
          bodyDe =
            s"Die regelmäßige Überprüfung von $docDat steht an. Bitte entscheiden Sie bis ${date("dueOn")}: bestätigen, überarbeiten oder außer Kraft setzen.",
          bodyEn =
            s"The periodic review of $doc is coming up. Please decide by ${date("dueOn")}: recertify, revise or withdraw."
        )(tasks)
      case "review.task-reopened" =>
        val discarded = p("revisionStatus") == "Discarded"
        r(de = s"Überprüfung wieder offen: $docShort", en = s"Review open again: $docShort")(
          bodyDe = s"Die Überarbeitung von $docDat wurde " +
            s"${
                if discarded then "verworfen" else "zurückgezogen"
              } und ist nie gültig geworden. Die Überprüfung ist " +
            s"deshalb wieder offen. Bitte entscheiden Sie bis ${date("dueOn")}: bestätigen, überarbeiten oder außer " +
            "Kraft setzen.",
          bodyEn =
            s"The revision of $doc was ${if discarded then "discarded" else "withdrawn"} and never took " +
              s"effect, so the review is open again. Please decide by ${date("dueOn")}: recertify, revise or withdraw."
        )(tasks)
      case "review.task-due-soon" =>
        r(de = s"Überprüfung bald fällig: $docShort", en = s"Review due soon: $docShort")(
          bodyDe = s"Die Überprüfung von $docDat ist bis ${date("dueOn")} zu entscheiden.",
          bodyEn = s"The review of $doc is due by ${date("dueOn")}."
        )(tasks)
      case "review.task-overdue" =>
        r(de = s"Überprüfung überfällig: $docShort", en = s"Review overdue: $docShort")(
          bodyDe =
            s"Die Überprüfung von $docDat war bis ${date("dueOn")} zu entscheiden und ist noch offen. Das Dokument bleibt gültig.$ownerDeputyDe",
          bodyEn =
            s"The review of $doc was due by ${date("dueOn")} and is still open. The document stays valid.$ownerDeputyEn"
        )(if params.contains("actingFor") then docLink else tasks)
      case "review.task-escalated" =>
        r(de = s"Eskalation Überprüfung: $docShort", en = s"Review escalated: $docShort")(
          bodyDe =
            s"Die Überprüfung von $docDat ist seit ${date("dueOn")} überfällig. Bitte kümmern Sie sich darum oder sprechen Sie den Eigentümer an.$ownerDeputyDe",
          bodyEn =
            s"The review of $doc has been overdue since ${date("dueOn")}. Please take care of it or contact the owner.$ownerDeputyEn"
        )(docLink)
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
      case "failure.rendition" =>
        r(de = s"Archiv-PDF fehlgeschlagen: $docShort", en = s"Archive PDF failed: $docShort")(
          bodyDe =
            s"Das Archiv-PDF von $docDat konnte endgültig nicht erzeugt werden. $reasonDe Die Version bleibt in Freigabe, bis es vorliegt. $retryHintDe",
          bodyEn =
            s"The archive PDF of $doc could not be produced: every attempt failed. $reasonEn The version stays in approval until it exists. $retryHintEn"
        )(failureLink)
      case "failure.publication" =>
        r(de = s"Veröffentlichung fehlgeschlagen: $docShort", en = s"Publication failed: $docShort")(
          bodyDe =
            s"Die Veröffentlichung von $docDat ist endgültig fehlgeschlagen. $reasonDe Die Version ist nicht gültig geworden. $retryHintDe $withdrawHintDe",
          bodyEn =
            s"The publication of $doc failed: every attempt failed. $reasonEn The version has not become valid. $retryHintEn $withdrawHintEn"
        )(failureLink)
      case "publication.withdrawn" =>
        // The reason may say what the document is about: like the title, only for those who may read it.
        val reason = Option.when(ctx.document.isDefined)(p("reason"))
        // After a publication that failed for good the date may have passed (ADR 0061).
        val failed = params.get("publicationFailed").contains("true")
        r(de = s"Zurückgezogen: $docShort", en = s"Withdrawn: $docShort")(
          bodyDe = s"${person("withdrawnBy")} hat $doc " +
            (if failed then
               s"zurückgezogen, weil die Veröffentlichung${validFromDe} endgültig fehlgeschlagen ist. "
             else s"vor dem Gültigkeitsdatum${validFromDe} zurückgezogen. ") +
            "Die Version wird nicht veröffentlicht; was bisher galt, gilt weiter." +
            reason.fold("")(t => s" Begründung: $t"),
          bodyEn = s"${person("withdrawnBy")} withdrew $doc " +
            (if failed then s"after its publication${validFromEn} failed for good. "
             else s"before its valid-from date${validFromEn}. ") +
            "The version will not be published; what applied so far still applies." +
            reason.fold("")(t => s" Reason: $t")
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

    /** Where a failure notice leads: the place with the retry that this audience can reach. */
    def failureLink: Option[String] = params.get("audience") match
      case Some(Audience.Records) => Some("/legal-holds")
      case Some(Audience.Admin)   => Some("/admin?tab=renditions")
      case _                      =>
        document.map(d => s"/documents/${d.documentId.value}${d.versionId.fold("")(v => s"?version=$v")}")

    private def retryHint(de: Boolean): String =
      (params.get("audience"), params.get("canRetry")) match
        case (Some(Audience.Records), _) =>
          if de then "Sie können es unter Legal Holds erneut versuchen."
          else "You can retry it on the legal holds page."
        case (Some(Audience.Admin), _) =>
          if de then "Sie können es in der Administration unter Archiv-PDFs erneut versuchen."
          else "You can retry it in the administration under Archive PDFs."
        case (_, Some("false")) =>
          if de then
            "Den Stand sehen Sie auf der Dokumentseite; erneut versuchen kann es das Records Management."
          else "The document page shows the state; records management can retry it."
        case _ =>
          if de then "Sie können es auf der Dokumentseite erneut versuchen."
          else "You can retry it on the document page."

    def retryHintDe: String = retryHint(de = true)
    def retryHintEn: String = retryHint(de = false)

    /** The way out if a retry cannot help (DMS-145, ADR 0061): withdrawing needs the owner role or having
      * approved the version, so records managers and administrators are told who has it.
      */
    private def withdrawHint(de: Boolean): String =
      if params.get("canWithdraw").contains("true") then
        if de then
          "Hilft das nicht, können Sie die Version auf der Dokumentseite zurückziehen und danach eine neue Version beginnen."
        else
          "If that does not help, you can withdraw the version on the document page and then start a new one."
      else if de then
        "Hilft das nicht, können Inhaber der Eigentümerrolle oder die Freigebenden die Version auf der Dokumentseite zurückziehen; danach lässt sich eine neue Version beginnen."
      else
        "If that does not help, holders of the owner role or those who approved the version can withdraw it on the document page; a new version can then be started."

    def withdrawHintDe: String = withdrawHint(de = true)
    def withdrawHintEn: String = withdrawHint(de = false)

    /** The reason by failure code. The technical text stays with the job: it can quote document content. */
    def reasonDe: String = params.get("reason") match
      case Some("unavailable") =>
        "Grund: Ein benötigter Dienst (Rendering, Konvertierung oder Objektspeicher) war nicht erreichbar."
      case Some("rejected")       => "Grund: Der Rendering-Dienst hat den Inhalt abgelehnt."
      case Some("not_archivable") => "Grund: Das PDF besteht die Prüfung gegen den Archivstandard nicht."
      case Some("invalid")    => "Grund: Die Version lässt sich in ihrem jetzigen Zustand nicht verarbeiten."
      case Some("integrity")  => "Grund: Eine gespeicherte Datei passt nicht zu ihrer Prüfsumme."
      case Some("database")   => "Grund: Die Datenbank war nicht erreichbar."
      case Some("timeout")    => "Grund: Die Verarbeitung wurde nicht rechtzeitig fertig."
      case Some("conflict")   => "Grund: Der Stand des Dokuments lässt den Schritt nicht zu."
      case Some("legal_hold") => "Grund: Auf dem Dokument liegt ein Legal Hold."
      case Some("not_found")  => "Grund: Die Version wurde nicht gefunden."
      case _                  => "Der Grund ist nicht bekannt."

    def reasonEn: String = params.get("reason") match
      case Some("unavailable") =>
        "Reason: a required service (rendering, conversion or object storage) could not be reached."
      case Some("rejected")       => "Reason: the rendering service refused the content."
      case Some("not_archivable") => "Reason: the PDF does not pass the check against the archive standard."
      case Some("invalid")        => "Reason: the version cannot be processed in its current state."
      case Some("integrity")      => "Reason: a stored file does not match its checksum."
      case Some("database")       => "Reason: the database could not be reached."
      case Some("timeout")        => "Reason: the processing did not finish in time."
      case Some("conflict")       => "Reason: the state of the document does not allow this step."
      case Some("legal_hold")     => "Reason: a legal hold is active on the document."
      case Some("not_found")      => "Reason: the version was not found."
      case _                      => "The reason is not known."

    def actingForDe: String =
      params.get("actingFor").fold("")(_ => s" Sie handeln als Vertretung von ${person("actingFor")}.")
    def actingForEn: String =
      params.get("actingFor").fold("")(_ => s" You act as deputy of ${person("actingFor")}.")

    /** Owner decisions are not forwarded (ADR 0030): a deputy is only told. */
    def ownerDeputyDe: String =
      params
        .get("actingFor")
        .fold("")(_ =>
          s" Sie erhalten dies als Vertretung von ${person("actingFor")}; entscheiden kann nur der Eigentümer."
        )
    def ownerDeputyEn: String =
      params
        .get("actingFor")
        .fold("")(_ => s" You receive this as deputy of ${person("actingFor")}; only the owner can decide.")

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
