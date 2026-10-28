package dms.rendering.core

import dms.kernel.TenantId
import zio.test.*

import java.time.{Instant, LocalDate}
import java.util.UUID

object RenditionLayoutSpec extends ZIOSpecDefault:

  val facts: RenditionFacts = RenditionFacts(
    documentId = "5f0c2a7e-0000-4000-8000-000000000001",
    title = "Service contract",
    version = "1.0",
    validFrom = Some(LocalDate.of(2027, 1, 1)),
    approvedAt = Instant.parse("2026-10-28T09:15:42.123456Z"),
    approvedBy = "approver-1",
    language = "en"
  )

  private val tenant = TenantId(UUID.fromString("7c1e2f0a-0000-4000-8000-000000000002"))

  private val page =
    "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<title>Service contract</title>\n" +
      "<style>\nbody {}\n</style>\n</head>\n<body>\n<article class=\"dms-document\"></article>\n</body>\n</html>\n"

  def spec = suite("RenditionLayout")(
    test("header and footer name document id, version, valid-from and the approval note") {
      val css = RenditionLayout.pageCss(facts)
      assertTrue(
        RenditionLayout.identification(facts) == s"Document ID ${facts.documentId} · Version 1.0",
        RenditionLayout.validity(facts) == "Valid from 2027-01-01",
        RenditionLayout.approvalNote(facts) == "Approved on 2026-10-28 09:15 UTC by approver-1",
        css.contains("@top-right { content: \"Document ID 5f0c2a7e"),
        css.contains("Valid from 2027-01-01\\A Approved on 2026-10-28 09:15 UTC by approver-1"),
        css.contains("counter(page)"),
        css.contains("counter(pages)")
      )
    },
    test("German labels and date format for German documents; a missing valid-from date is named") {
      val german = facts.copy(language = "de", validFrom = None)
      assertTrue(
        RenditionLayout.identification(german) == s"Dokument-ID ${facts.documentId} · Version 1.0",
        RenditionLayout.validity(german) == "Gültig ab nicht festgelegt",
        RenditionLayout.approvalNote(german) == "Freigegeben am 28.10.2026 09:15 UTC von approver-1",
        RenditionLayout.validity(
          german.copy(validFrom = Some(LocalDate.of(2027, 1, 1)))
        ) == "Gültig ab 01.01.2027"
      )
    },
    test("every date of the PDF comes from the approval, at second precision") {
      val meta = RenditionLayout.meta(facts)
      assertTrue(
        RenditionLayout.timestamp(facts) == "2026-10-28T09:15:42Z",
        meta.contains("""<meta name="dcterms.created" content="2026-10-28T09:15:42Z">"""),
        meta.contains("""<meta name="dcterms.modified" content="2026-10-28T09:15:42Z">""")
      )
    },
    test("the file identifier is stable per tenant, document and version") {
      val id = RenditionLayout.identifier(tenant, facts)
      assertTrue(
        id.matches("[0-9a-f]{32}"),
        id == RenditionLayout.identifier(tenant, facts.copy(approvedAt = Instant.EPOCH, title = "Other")),
        id != RenditionLayout.identifier(tenant, facts.copy(version = "1.1")),
        id != RenditionLayout.identifier(TenantId.random(), facts)
      )
    },
    test("XMP properties carry document id, version and valid-from date") {
      assertTrue(
        RenditionLayout.xmp(facts) ==
          Map("documentId" -> facts.documentId, "version" -> "1.0", "validFrom" -> "2027-01-01"),
        RenditionLayout.xmp(facts.copy(validFrom = None)).keySet == Set("documentId", "version")
      )
    },
    test("page rules and metadata go into the head of the page") {
      val composed = RenditionLayout.compose(page, facts)
      assertTrue(
        composed.exists(p => p.indexOf("@page") < p.indexOf("</head>") && p.indexOf("@page") > 0),
        composed.exists(_.contains("<title>Service contract</title>")),
        composed.exists(p => "<title>".r.findAllMatchIn(p).size == 1),
        RenditionLayout.compose("<html><body></body></html>", facts).isLeft
      )
    },
    test("values cannot break out of a CSS string or the style element") {
      val hostile = facts.copy(title = "A \"quoted\" \\ title </style><script>x</script>\nnext")
      val css     = RenditionLayout.css(hostile.title)
      val page2   = RenditionLayout.compose(page, hostile).toOption.get
      assertTrue(
        css == "\"A \\\"quoted\\\" \\\\ title \\3c /style\\3e \\3c script\\3e x\\3c /script\\3e \\A next\"",
        !page2.contains("</style><script>"),
        page2.contains("<title>Service contract</title>")
      )
    }
  )
