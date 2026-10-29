package dms.app

import zio.test.*

import java.time.{Instant, LocalDate, ZoneId}

/** Header, footer and change history of on-demand PDFs (DMS-112): pure. */
object VersionPrintLayoutSpec extends ZIOSpecDefault:

  private val facts = PrintFacts(
    documentId = "d-1",
    documentNumber = Some("POL-2026-0007"),
    title = "IT security policy",
    version = "1.1-draft.3",
    language = "en",
    generatedAt = Instant.parse("2026-10-29T08:30:00Z"),
    generatedBy = "Ada Author",
    zone = ZoneId.of("Europe/Berlin")
  )

  private val entry = ChangelogEntry(
    version = "1.0",
    changeKind = "Major",
    changeReason = Some("<script>alert(1)</script> & more"),
    status = "Published",
    createdAt = Instant.parse("2026-01-02T10:00:00Z"),
    createdBy = "Ada Author",
    approvedAt = Some(Instant.parse("2026-01-05T10:00:00Z")),
    approvedBy = Some("Dan Deputy"),
    actingFor = Some("Anna Approver"),
    validFrom = Some(LocalDate.parse("2026-01-10")),
    publishedAt = Some(Instant.parse("2026-01-09T23:00:00Z"))
  )

  private val page =
    "<!DOCTYPE html>\n<html><head><title>x</title></head><body><article class=\"dms-document\"><p>Text</p></article></body></html>"

  def spec = suite("Print layout of on-demand PDFs")(
    test("a draft names the marking, the document number and who generated it in the tenant's time zone") {
      assertTrue(
        facts.draft,
        VersionPrintLayout.marking(facts) == "Draft – not valid",
        VersionPrintLayout.identification(facts) ==
          "Document number POL-2026-0007 · Document ID d-1 · Version 1.1-draft.3",
        VersionPrintLayout.footer(facts) ==
          "Generated on 2026-10-29 09:30 (Europe/Berlin) by Ada Author · not approved, not archived",
        VersionPrintLayout.marking(facts.copy(language = "de-DE")) == "Entwurf – nicht gültig"
      )
    },
    test("the change history escapes the change summary and names the deputy's principal") {
      val html = VersionPrintLayout.changelog(List(entry), facts)
      assertTrue(
        html.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; more"),
        !html.contains("<script>"),
        html.contains("Dan Deputy (on behalf of Anna Approver)"),
        html.contains("Published 2026-01-10"),
        html.contains("Valid from 2026-01-10"),
        html.contains("Created 2026-01-02 by Ada Author")
      )
    },
    test("compose puts page rules into the head and the change history at the end of the document") {
      val composed = VersionPrintLayout.compose(page, facts, Some(List(entry))).toOption.get
      assertTrue(
        composed.indexOf("@page") < composed.indexOf("</head>"),
        composed.indexOf("<p>Text</p>") < composed.indexOf("Change history"),
        composed.indexOf("Change history") < composed.indexOf("</article>"),
        VersionPrintLayout.compose(page, facts, None).toOption.exists(!_.contains("Change history")),
        VersionPrintLayout.compose("<html></html>", facts, None).isLeft
      )
    },
    test("the list of attachments follows the change history with name, size and SHA-256") {
      val attachments = List(AttachmentEntry("Preisliste <2026>.xlsx", 1234567L, "a" * 64))
      val composed    =
        VersionPrintLayout.compose(page, facts, Some(List(entry)), Some(attachments)).toOption.get
      val german = VersionPrintLayout
        .compose(page, facts.copy(language = "de"), None, Some(attachments), startsPage = true)
        .toOption
        .get
      val empty = VersionPrintLayout.compose(page, facts, None, Some(Nil)).toOption.get
      assertTrue(
        composed.indexOf("Change history") < composed.indexOf("List of attachments"),
        composed.contains("Preisliste &lt;2026&gt;.xlsx"),
        composed.contains("1,234,567 bytes"),
        composed.contains("a" * 64),
        german.contains("Anlagenverzeichnis"),
        german.contains("1.234.567 Bytes"),
        // a page of appendices only starts without page break
        !german.contains("dms-page-break"),
        empty.contains("The version has no attachments.")
      )
    },
    test("the Word page carries the marking as first paragraph") {
      val word = VersionPrintLayout.wordPage(page.replace("<body>", "<body>"), facts)
      assertTrue(
        word.indexOf("dms-word-marking") < word.indexOf("<p>Text</p>"),
        word.contains("Draft – not valid · Document number POL-2026-0007")
      )
    }
  )
