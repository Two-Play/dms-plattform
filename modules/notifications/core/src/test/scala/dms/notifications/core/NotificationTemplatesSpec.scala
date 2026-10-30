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

  def spec = suite("NotificationTemplates")(
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
