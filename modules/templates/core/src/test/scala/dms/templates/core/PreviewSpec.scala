package dms.templates.core

import dms.documents.metadata.MetadataValue
import dms.templates.*
import zio.*
import zio.test.*

object PreviewSpec extends ZIOSpecDefault:
  import PreviewFixtures.*

  private def run(
      values: Map[String, MetadataValue] = PreviewFixtures.values,
      language: Language = Language.En
  ) =
    Preview.run(template, values, language)

  /** Milliseconds of `n` runs of `f`, sorted. */
  private def timings(n: Int)(f: => Unit): Vector[Double] =
    (1 to n)
      .map { _ =>
        val start = java.lang.System.nanoTime()
        f
        (java.lang.System.nanoTime() - start) / 1e6
      }
      .toVector
      .sorted

  private def percentile(sorted: Vector[Double], p: Double): Double =
    sorted(math.min(sorted.size - 1, math.ceil(p * sorted.size).toInt - 1))

  def spec = suite("Preview")(
    test("renders the filled-in template marked as draft, without missing required values") {
      val r = run()
      assertTrue(
        r.html.startsWith("<article class=\"dms-document dms-draft\""),
        r.html.contains("Draft – not valid"),
        r.html.contains("ACME Municipal Utilities Ltd."),
        r.html.contains("€12,500.00"),
        r.html.contains("Premium"),
        !r.html.contains("dms-missing-required"),
        r.missingRequired.isEmpty,
        r.issues.isEmpty
      )
    },
    test("lists missing required fields with their labels, but not the fields the platform sets") {
      val r = run(PreviewFixtures.values - "clientName" - "monthlyFee" - "title")
      assertTrue(
        r.missingRequired == List(
          MissingValue("title", "Title"),
          MissingValue("clientName", "Client name"),
          MissingValue("monthlyFee", "Monthly fee")
        ),
        r.html.contains(
          "<span class=\"dms-missing dms-missing-required\" data-field=\"clientName\" data-required=\"true\">" +
            "Client name</span>"
        ),
        r.html.contains("data-field=\"monthlyFee\" data-required=\"true\">Monthly fee</span>")
      )
    },
    test("shows fields the platform sets neutrally with a hint, not as missing values") {
      val en = run().html
      val de = run(language = Language.De).html
      assertTrue(
        en.contains(
          "Contract number: <span class=\"dms-system\" data-field=\"documentId\">Document ID " +
            "<span class=\"dms-system-hint\">(assigned on save)</span></span>, version " +
            "<span class=\"dms-system\" data-field=\"version\">Version " +
            "<span class=\"dms-system-hint\">(assigned on save)</span></span>."
        ),
        !en.contains("data-field=\"documentId\" data-required"),
        de.contains(
          "<span class=\"dms-system\" data-field=\"documentId\">Document ID " +
            "<span class=\"dms-system-hint\">(wird beim Speichern vergeben)</span></span>"
        )
      )
    },
    test("blank values count as missing") {
      val r = run(PreviewFixtures.values + ("clientName" -> MetadataValue.Text("  ")))
      assertTrue(r.missingRequired.map(_.field).contains("clientName"), r.issues.isEmpty)
    },
    test("values that do not fit their field are left out and reported; the preview still renders") {
      val r = run(
        PreviewFixtures.values ++ Map(
          "termMonths"   -> MetadataValue.Number(500),
          "clientName"   -> MetadataValue.Number(1),
          "unknownField" -> MetadataValue.Text("x"),
          "documentId"   -> MetadataValue.Text("DOC-1")
        )
      )
      assertTrue(
        r.html.nonEmpty,
        r.missingRequired.map(_.field) == List("clientName"),
        r.issues.map(i => (i.field, i.code)) == List(
          Some("clientName")   -> "wrong_type",
          Some("termMonths")   -> "out_of_range",
          Some("unknownField") -> "unknown_field",
          Some("documentId")   -> "system_field"
        ),
        r.html.contains("data-field=\"termMonths\" data-required=\"false\">Term in months</span>")
      )
    },
    test("formats in the document language") {
      val r = run(language = Language.De)
      assertTrue(
        r.html.contains("Entwurf – nicht gültig"),
        r.html.contains("12.500,00 €"),
        r.html.contains("1. Januar 2027")
      )
    },
    test("offers the editable fields, those the template uses first") {
      val fields = template.fields
      val keys   = fields.map(_.key)
      assertTrue(
        keys.take(11) == List(
          "clientName",
          "clientAddress",
          "monthlyFee",
          "termMonths",
          "termType",
          "startDate",
          "endDate",
          "noticePeriod",
          "services",
          "international",
          "slaLevel"
        ),
        fields.take(11).forall(_.usedInTemplate),
        keys.contains("title"),
        !keys.contains("documentId"),
        !keys.contains("renditionChecksum"),
        fields.find(_.key == "clientName").exists(f => f.required && f.label == "Client name"),
        fields.find(_.key == "services").exists(_.multiple)
      )
    },
    test("the 12-page contract template resolves and renders well within 2 seconds (p95)") {
      val n = 50
      // Warm up the JIT like a running server; the first requests of a fresh JVM are slower.
      val _    = timings(10)(run(): Unit)
      val cold = timings(n) {
        val t = PreviewTemplate(version, profile, compile())
        Preview.run(t, PreviewFixtures.values, Language.En): Unit
      }
      val warm       = timings(n)(run(): Unit)
      val html       = run().html
      val text       = html.replaceAll("<[^>]+>", " ").split("\\s+").count(_.nonEmpty)
      val coldP95    = percentile(cold, 0.95)
      val warmP95    = percentile(warm, 0.95)
      val coldMedian = percentile(cold, 0.5)
      val warmMedian = percentile(warm, 0.5)
      for _ <- ZIO.debug(
          f"Preview of the 12-page contract ($text words, ${html.length / 1024} KB HTML), $n runs: " +
            f"compile+resolve+render median $coldMedian%.1f ms, p95 $coldP95%.1f ms; " +
            f"resolve+render (compiled version cached) median $warmMedian%.1f ms, p95 $warmP95%.1f ms"
        )
      yield assertTrue(text > 5000, coldP95 < 1000.0, warmP95 < 250.0)
    } @@ TestAspect.withLiveClock
  )
