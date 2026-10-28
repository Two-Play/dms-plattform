package dms.templates.core

import dms.templates.*
import dms.templates.Block.*
import dms.templates.Inline.*
import zio.test.*

object HtmlRendererSpec extends ZIOSpecDefault:
  import Fixtures.*

  private val labels =
    Map("clientName" -> "Client name", "endDate" -> "End date", "monthlyFee" -> "Monthly fee")

  private def html(
      values: Map[String, FieldValue] = Fixtures.values,
      options: HtmlOptions = HtmlOptions(labels = labels),
      content: TemplateContent = Fixtures.content
  ): String =
    val compiled = TemplateCompiler.compile(content, schema).fold(i => throw AssertionError(i), identity)
    Resolver
      .resolve(compiled, values, options.language)
      .map(r => HtmlRenderer.render(r.content, options))
      .fold(e => throw AssertionError(e.message), identity)

  def spec = suite("HtmlRenderer")(
    test("renders every block type as semantic HTML with stable classes") {
      val h = html()
      assertTrue(
        h.startsWith("<article class=\"dms-document\" lang=\"en\">"),
        h.endsWith("</article>"),
        h.contains(
          "<section class=\"dms-section\" data-section=\"parties\" data-locked=\"true\">" +
            "<h2 class=\"dms-section-title\">Parties</h2>" +
            "<h1 class=\"dms-heading dms-heading-1\">Service contract</h1>"
        ),
        h.contains(
          "<p class=\"dms-paragraph\">Between <span class=\"dms-value\" data-field=\"clientName\">ACME GmbH</span>"
        ),
        h.contains("<section class=\"dms-section\" data-section=\"fees\" data-locked=\"false\">"),
        h.contains(
          "<ul class=\"dms-list\"><li><p class=\"dms-paragraph\"><span class=\"dms-value\" data-field=\"service\">" +
            "Hosting</span></p></li></ul>"
        ),
        h.contains(
          "<table class=\"dms-table\"><thead><tr><th scope=\"col\"><p class=\"dms-paragraph\">Item</p></th>"
        ),
        h.contains("</thead><tbody><tr><td><p class=\"dms-paragraph\">Term</p></td>"),
        h.contains("<ol class=\"dms-list\"><li><p class=\"dms-paragraph\">First</p></li>"),
        h.contains("<div class=\"dms-page-break\" aria-hidden=\"true\"></div>"),
        !h.contains("dms-draft")
      )
    },
    test("renders marks, line breaks and clause references") {
      val content = TemplateContent(
        List(
          section(
            "main",
            Paragraph(
              List(Text("bold", Set(Mark.Bold, Mark.Italic)), LineBreak, Text("under", Set(Mark.Underline)))
            ),
            ClauseRef("foreign-law", 2)
          )
        )
      )
      val h = html(content = content)
      assertTrue(
        h.contains("<p class=\"dms-paragraph\"><strong><em>bold</em></strong><br><u>under</u></p>"),
        h.contains(
          "<div class=\"dms-clause-ref\" data-clause=\"foreign-law\" data-clause-version=\"2\">" +
            "Clause foreign-law, version 2</div>"
        )
      )
    },
    test("marks missing values with the field label; required ones distinctly") {
      val h = html(values = Fixtures.values - "clientName" - "endDate")
      assertTrue(
        h.contains(
          "<span class=\"dms-missing dms-missing-required\" data-field=\"clientName\" data-required=\"true\">" +
            "Client name</span>"
        ),
        h.contains(
          "<span class=\"dms-missing\" data-field=\"endDate\" data-required=\"false\">End date</span>"
        )
      )
    },
    test("shows system fields without a value neutrally with a hint in the document language") {
      val values = Fixtures.values - "clientName" - "endDate"
      val en     = html(values, HtmlOptions.draft(Language.En, labels, Set("clientName")))
      val de     = html(values, HtmlOptions.draft(Language.De, labels, Set("clientName")))
      assertTrue(
        en.contains(
          "<span class=\"dms-system\" data-field=\"clientName\">Client name " +
            "<span class=\"dms-system-hint\">(assigned on save)</span></span>"
        ),
        !en.contains("data-field=\"clientName\" data-required"),
        en.contains(
          "<span class=\"dms-missing\" data-field=\"endDate\" data-required=\"false\">End date</span>"
        ),
        de.contains("<span class=\"dms-system-hint\">(wird beim Speichern vergeben)</span>")
      )
    },
    test("labels of nested paths name the field and the part; unknown paths show the path") {
      assertTrue(
        HtmlRenderer.label("monthlyFee.currency", labels) == "Monthly fee (currency)",
        HtmlRenderer.label("monthlyFee", labels) == "Monthly fee",
        HtmlRenderer.label("item.price", labels) == "item.price"
      )
    },
    test("escapes field values, template text, titles and labels (no markup injection)") {
      val attack  = "<script>alert('x')</script><img src=x onerror=\"alert(1)\">&"
      val content = TemplateContent(
        List(
          Section(
            SectionId("main"),
            Some("<b>Title</b>"),
            locked = false,
            List(
              Paragraph(List(Text("a < b & \"c\""), Placeholder("clientName"), Placeholder("noticePeriod")))
            )
          )
        )
      )
      val h = html(
        values = Fixtures.values + ("clientName" -> FieldValue.Text(attack)) - "noticePeriod",
        options = HtmlOptions(
          labels = Map("noticePeriod" -> "<i onmouseover=\"x\">Notice</i>"),
          draftMarking = Some("\"><script>")
        ),
        content = content
      )
      assertTrue(
        !h.contains("<script"),
        !h.contains("<img"),
        !h.contains("<b>"),
        !h.contains("<i "),
        h.contains(
          "&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;&lt;img src=x onerror=&quot;alert(1)&quot;&gt;&amp;"
        ),
        h.contains("<h2 class=\"dms-section-title\">&lt;b&gt;Title&lt;/b&gt;</h2>"),
        h.contains("a &lt; b &amp; &quot;c&quot;"),
        h.contains("&lt;i onmouseover=&quot;x&quot;&gt;Notice&lt;/i&gt;"),
        h.contains("data-watermark=\"&quot;&gt;&lt;script&gt;\"")
      )
    },
    test("drafts carry a banner and a watermark in the document language") {
      val en = html(options = HtmlOptions.draft(Language.En, labels))
      val de = html(options = HtmlOptions.draft(Language.De, labels))
      assertTrue(
        en.startsWith(
          "<article class=\"dms-document dms-draft\" lang=\"en\" data-watermark=\"Draft – not valid\">" +
            "<div class=\"dms-draft-banner\" role=\"note\">Draft – not valid</div>"
        ),
        de.contains("lang=\"de\" data-watermark=\"Entwurf – nicht gültig\""),
        de.contains("1.234,50 €")
      )
    },
    test("a stand-alone document embeds the shared stylesheet for the PDF worker") {
      val compiled = Fixtures.compiled()
      val resolved = Resolver.resolve(compiled, Fixtures.values).toOption.get.content
      val doc      = HtmlRenderer.renderDocument(resolved, HtmlOptions(title = Some("Contract <1>")))
      assertTrue(
        doc.startsWith("<!DOCTYPE html>\n<html lang=\"en\">"),
        doc.contains("<meta charset=\"utf-8\">"),
        doc.contains("<title>Contract &lt;1&gt;</title>"),
        doc.contains(HtmlRenderer.stylesheet),
        HtmlRenderer.stylesheet.contains("@page"),
        HtmlRenderer.stylesheet.contains(".dms-document .dms-missing-required"),
        doc.contains(HtmlRenderer.render(resolved, HtmlOptions(title = Some("Contract <1>"))))
      )
    }
  )
