package dms.notifications.core

import dms.kernel.{DocumentId, UserId}
import dms.notifications.*
import zio.test.*

import java.time.ZoneId
import java.util.UUID

/** The German and English texts of every notification kind (DMS-130): titles only for readers, names of the
  * people involved, dates in the tenant's time zone, links into the web application, the email frame.
  */
object NotificationTemplatesSpec extends ZIOSpecDefault:

  private val doc   = DocumentRef(DocumentId(UUID.fromString("00000000-0000-0000-0000-000000000007")), None)
  private val facts = DocumentFacts("Reiserichtlinie", Some("2.0"), None)
  private val anna  = UserId("anna")
  private val ben   = UserId("ben")
  private val zone  = ZoneId.of("Europe/Berlin")

  private def ctx(document: Option[DocumentFacts], recipient: UserId = anna) =
    TemplateContext(recipient, document, Map(anna -> "Anna Albers", ben -> "Ben Becker"), zone)

  private val allKinds = NotificationTemplates.Kinds.toList.sorted

  private val reviewParams = Map("dueOn" -> "2026-12-01")

  private def review(
      kind: String,
      language: Language,
      facts: Option[DocumentFacts],
      params: Map[String, String]
  ) =
    NotificationTemplates.render(kind, language, params, Some(doc), ctx(facts))

  def spec = suite("NotificationTemplates")(
    test("review tasks: German and English, the date, the task inbox for the owner (DMS-141)") {
      val created   = review("review.task-created", Language.De, Some(facts), reviewParams)
      val createdEn = review("review.task-created", Language.En, Some(facts), reviewParams)
      val soon      = review("review.task-due-soon", Language.De, Some(facts), reviewParams)
      val soonEn    = review("review.task-due-soon", Language.En, Some(facts), reviewParams)
      val late      = review("review.task-overdue", Language.De, Some(facts), reviewParams)
      val lateEn    = review("review.task-overdue", Language.En, Some(facts), reviewParams)
      val up        = review("review.task-escalated", Language.De, Some(facts), reviewParams)
      val upEn      = review("review.task-escalated", Language.En, Some(facts), reviewParams)
      assertTrue(
        created.subject == "Überprüfung steht an: Reiserichtlinie (2.0)",
        created.body.contains("bis 01.12.2026"),
        created.link.contains("/tasks"),
        createdEn.subject == "Review coming up: Reiserichtlinie (2.0)",
        createdEn.body.contains("by 1 Dec 2026"),
        soon.subject == "Überprüfung bald fällig: Reiserichtlinie (2.0)",
        soonEn.subject == "Review due soon: Reiserichtlinie (2.0)",
        late.subject == "Überprüfung überfällig: Reiserichtlinie (2.0)",
        late.body.contains("bleibt gültig"),
        late.link.contains("/tasks"),
        lateEn.subject == "Review overdue: Reiserichtlinie (2.0)",
        lateEn.body.contains("stays valid"),
        up.subject == "Eskalation Überprüfung: Reiserichtlinie (2.0)",
        up.link.contains(s"/documents/${doc.documentId.value}"),
        upEn.subject == "Review escalated: Reiserichtlinie (2.0)",
        upEn.body.contains("overdue since 1 Dec 2026")
      )
    },
    test("review tasks: no title for someone who may not read the document; deputies are only told") {
      val kinds =
        List("review.task-created", "review.task-due-soon", "review.task-overdue", "review.task-escalated")
      val hidden = for
        kind     <- kinds
        language <- Language.values.toList
      yield review(kind, language, None, reviewParams)
      val deputy   = reviewParams + ("actingFor" -> ben.value)
      val toDeputy = review("review.task-overdue", Language.De, Some(facts), deputy)
      val deputyEn = review("review.task-overdue", Language.En, Some(facts), deputy)
      assertTrue(
        hidden.forall(r => !r.subject.contains("Reiserichtlinie") && !r.body.contains("Reiserichtlinie")),
        toDeputy.body.contains("als Vertretung von Ben Becker; entscheiden kann nur der Eigentümer"),
        toDeputy.link.contains(s"/documents/${doc.documentId.value}"),
        deputyEn.body.contains("as deputy of Ben Becker; only the owner can decide")
      )
    },
    test("every kind has a German and an English text with a subject and a body") {
      val rendered = for
        kind     <- allKinds
        language <- Language.values.toList
      yield (
        kind,
        language,
        NotificationTemplates.render(kind, language, Map.empty, Some(doc), ctx(Some(facts)))
      )
      assertTrue(
        rendered.forall((_, _, r) => r.subject.nonEmpty && r.body.nonEmpty),
        rendered.filter(_._2 == Language.De).map(_._3.subject).distinct.size ==
          rendered.filter(_._2 == Language.En).map(_._3.subject).distinct.size
      )
    },
    test("task assigned: review or approval, the step, deputies acting for someone, a link to the inbox") {
      val params = Map("step" -> "Rechtsprüfung", "stepKind" -> "Approval", "actingFor" -> "ben")
      val de     = NotificationTemplates.render(
        "workflow.task-assigned",
        Language.De,
        params,
        Some(doc),
        ctx(Some(facts))
      )
      val en = NotificationTemplates.render(
        "workflow.task-assigned",
        Language.En,
        params,
        Some(doc),
        ctx(Some(facts))
      )
      assertTrue(
        de.subject == "Freigabe erbeten: Reiserichtlinie (2.0)",
        de.body == "Sie sind für den Schritt „Rechtsprüfung“ von „Reiserichtlinie“ (Version 2.0) eingeteilt. Sie handeln als Vertretung von Ben Becker.",
        de.link.contains("/tasks"),
        en.subject == "Approval requested: Reiserichtlinie (2.0)",
        en.body.contains("You act as deputy of Ben Becker.")
      )
    },
    test("without read permission no title, no number, but the link and a neutral text") {
      val r = NotificationTemplates.render(
        "acknowledgement.reminder",
        Language.De,
        Map("version" -> "2.0"),
        Some(doc),
        ctx(None)
      )
      assertTrue(
        !r.subject.contains("Reiserichtlinie"),
        !r.body.contains("Reiserichtlinie"),
        r.body == "Sie haben die Kenntnisnahme von einem Dokument noch nicht bestätigt.",
        r.link.contains(s"/documents/${doc.documentId.value}")
      )
    },
    test("deputyships: different texts for principal and deputy, dates in the tenant's time zone") {
      val params = Map(
        "principal"  -> "anna",
        "deputy"     -> "ben",
        "validFrom"  -> "2026-11-01T23:30:00Z",
        "validUntil" -> "2026-11-14T22:59:00Z"
      )
      val principal =
        NotificationTemplates.render("deputyship.created", Language.De, params, None, ctx(None, anna))
      val deputy =
        NotificationTemplates.render("deputyship.created", Language.De, params, None, ctx(None, ben))
      val english =
        NotificationTemplates.render("deputyship.started", Language.En, params, None, ctx(None, ben))
      assertTrue(
        principal.body == "Ben Becker vertritt Sie von 02.11.2026 bis 14.11.2026.",
        deputy.body == "Sie vertreten Anna Albers von 02.11.2026 bis 14.11.2026.",
        english.body.startsWith("You stand in for Anna Albers from now until 14 Nov 2026."),
        english.link.contains("/tasks")
      )
    },
    test("publication and legal holds") {
      val published = NotificationTemplates.render(
        "publication.published",
        Language.De,
        Map("validFrom" -> "2026-12-01"),
        Some(doc),
        ctx(Some(facts))
      )
      val hold = NotificationTemplates.render(
        "legal-hold.created",
        Language.En,
        Map("name" -> "Matter 42", "documents" -> "3"),
        None,
        ctx(None)
      )
      assertTrue(
        published.body == "„Reiserichtlinie“ (Version 2.0), dessen Eigentümer Sie sind, ist jetzt gültig (ab 01.12.2026).",
        hold.subject == "Legal hold set: Matter 42",
        hold.body.contains("covers 3 document(s)"),
        hold.link.contains("/legal-holds")
      )
    },
    test("the email adds greeting, absolute link and the settings page") {
      val rendered        = Rendered("Gültig: X", "Text.", Some("/documents/1"))
      val (subject, text) =
        NotificationTemplates.email(Language.De, Some("Anna Albers"), rendered, "https://dms.example.org/")
      val (_, english) = NotificationTemplates.email(Language.En, None, rendered, "https://dms.example.org")
      assertTrue(
        subject == "[DMS] Gültig: X",
        text.startsWith("Hallo Anna Albers,\n\nText.\n\nÖffnen: https://dms.example.org/documents/1"),
        text.contains("E-Mail-Benachrichtigungen ändern: https://dms.example.org/settings/notifications"),
        english.startsWith("Hello,\n"),
        english.contains("Open: https://dms.example.org/documents/1")
      )
    }
  )
