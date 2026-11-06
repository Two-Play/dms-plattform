package dms.rendering.core

import dms.kernel.TenantId
import dms.rendering.{ArchiveStandard, PdfVariant}
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.{PDDocument, PDPage}
import zio.*
import zio.test.*

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/** The rendering worker (WeasyPrint, built from `workers/rendering`) and veraPDF against real PDFs (DMS-44):
  * PDF/A-2b validity, the running header and footer on every page, reproducible bytes and refused external
  * references.
  */
object PdfRenditionSpec extends ZIOSpecDefault:

  private val tenant = TenantId.random()
  private val facts  = RenditionLayoutSpec.facts

  private val paragraph =
    "<p class=\"dms-paragraph\">The provider delivers the services described in this contract with due care " +
      "and in accordance with the service levels of annex 1. Changes need the written form. Prices are net " +
      "prices plus statutory value added tax; invoices are due within thirty days.</p>"

  /** Three pages: two forced page breaks; similar to `HtmlRenderer.renderDocument` with `document.css`. */
  private val page =
    s"""<!DOCTYPE html>
       |<html lang="en">
       |<head>
       |<meta charset="utf-8">
       |<title>${facts.title}</title>
       |<style>
       |@page { size: A4; margin: 25mm 20mm 22mm 25mm; }
       |.dms-document { font-family: "Noto Serif", serif; font-size: 10.5pt; line-height: 1.45; }
       |.dms-section-title { font-family: "Noto Sans", sans-serif; color: #0b3d6e; }
       |.dms-page-break { break-after: page; height: 0; }
       |table { border-collapse: collapse; } td, th { border: 0.5pt solid #777; padding: 1mm; }
       |</style>
       |</head>
       |<body>
       |<article class="dms-document" lang="en">
       |<h1 class="dms-heading dms-heading-1">Service contract</h1>
       |<section class="dms-section"><h2 class="dms-section-title">1 Subject</h2>${paragraph * 3}</section>
       |<div class="dms-page-break"></div>
       |<section class="dms-section"><h2 class="dms-section-title">2 Prices – Preise</h2>
       |<table class="dms-table"><thead><tr><th scope="col">Item</th><th scope="col">Amount</th></tr></thead>
       |<tbody><tr><td>Monthly fee (Grundgebühr)</td><td>1.250,00 €</td></tr></tbody></table>${paragraph * 2}</section>
       |<div class="dms-page-break"></div>
       |<section class="dms-section"><h2 class="dms-section-title">3 Term</h2>${paragraph}</section>
       |</article>
       |</body>
       |</html>
       |""".stripMargin

  private def job(html: String) =
    PdfJob(
      RenditionLayout.compose(html, facts).toOption.get,
      PdfVariant.Archive(ArchiveStandard.PdfA2b),
      RenditionLayout.identifier(tenant, facts),
      RenditionLayout.xmp(facts)
    )

  private def sha256(bytes: Chunk[Byte]) =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toArray))

  private def plainPdf: Chunk[Byte] =
    val document = PDDocument()
    try
      document.addPage(PDPage())
      val out = ByteArrayOutputStream()
      document.save(out)
      Chunk.fromArray(out.toByteArray)
    finally document.close()

  def spec = suite("PDF/A rendition")(
    test("passes veraPDF as PDF/A-2b and shows id, version, valid-from and approval note on every page") {
      for
        pdf    <- ZIO.serviceWithZIO[PdfRenderer](_.render(job(page)))
        report <- VeraPdfValidator.validate(pdf.bytes, ArchiveStandard.PdfA2b)
        texts = RenderingWorker.pageTexts(pdf.bytes).map(RenderingWorker.normalized)
      yield assertTrue(
        report.compliant,
        report.failures.isEmpty,
        report.validator.startsWith("veraPDF"),
        pdf.pages.contains(3),
        pdf.renderer.startsWith("WeasyPrint 70.0"),
        texts.size == 3,
        texts.forall(_.contains(RenditionLayout.identification(facts))),
        texts.forall(_.contains(RenditionLayout.validity(facts))),
        texts.forall(_.contains(RenditionLayout.approvalNote(facts))),
        texts.forall(_.contains(facts.title)),
        texts.zipWithIndex.forall((t, i) => t.contains(s"Page ${i + 1} of 3")),
        texts(1).contains("1.250,00 €")
      )
    },
    test("is reproducible, carries the identification in the metadata and names the worker build") {
      for
        first  <- ZIO.serviceWithZIO[PdfRenderer](_.render(job(page)))
        second <- ZIO.serviceWithZIO[PdfRenderer](_.render(job(page)))
        (xmp, info) =
          val document = Loader.loadPDF(first.bytes.toArray)
          try
            (
              String(
                document.getDocumentCatalog.getMetadata.exportXMPMetadata().readAllBytes(),
                StandardCharsets.UTF_8
              ),
              document.getDocumentInformation
            )
          finally document.close()
      yield assertTrue(
        sha256(first.bytes) == sha256(second.bytes),
        // Image reference as configured, build fingerprint computed by the worker from its packages (DMS-93).
        first.worker.image.contains(RenderingWorker.ImageReference),
        first.worker.build.exists(_.matches("sha256:[0-9a-f]{64}")),
        first.worker == second.worker,
        xmp.contains(s"<dms:documentId>${facts.documentId}</dms:documentId>"),
        xmp.contains("<dms:version>1.0</dms:version>"),
        xmp.contains("<dms:validFrom>2027-01-01</dms:validFrom>"),
        xmp.contains("""pdfaid:part="2" pdfaid:conformance="B""""),
        info.getTitle == facts.title,
        info.getCreationDate.toInstant == java.time.Instant.parse("2026-10-28T09:15:42Z")
      )
    },
    test("refuses pages that reference external resources") {
      val external = page.replace("</article>", """<img src="https://example.com/logo.png"></article>""")
      for result <- ZIO.serviceWithZIO[PdfRenderer](_.render(job(external))).either
      yield assertTrue(result match
        case Left(RenderingError.Rejected(message)) => message.contains("example.com")
        case _                                      => false)
    },
    test("an unreachable worker is reported as unavailable (retried by the job queue)") {
      val renderer = WeasyPrintRenderer(
        java.net.http.HttpClient.newHttpClient(),
        RendererConfig("http://127.0.0.1:9", timeoutSeconds = 2)
      )
      for result <- renderer.render(job(page)).either
      yield assertTrue(result.left.exists {
        case RenderingError.Unavailable(_) => true
        case _                             => false
      })
    },
    test("veraPDF rejects PDFs that are not PDF/A and files that are no PDF") {
      for
        plain   <- VeraPdfValidator.validate(plainPdf, ArchiveStandard.PdfA2b)
        garbage <- VeraPdfValidator.validate(Chunk.fromArray("no pdf".getBytes), ArchiveStandard.PdfA2b)
      yield assertTrue(
        !plain.compliant,
        plain.failures.nonEmpty,
        !garbage.compliant,
        garbage.failures.nonEmpty
      )
    },
    test("veraPDF gives the same results at the same time: rendition jobs can run in parallel (DMS-137)") {
      for
        pdf     <- ZIO.serviceWithZIO[PdfRenderer](_.render(job(page)))
        results <- ZIO.foreachPar((1 to 12).toList)(i =>
          VeraPdfValidator
            .validate(if i % 3 == 0 then plainPdf else pdf.bytes, ArchiveStandard.PdfA2b)
            .map(i -> _.compliant)
        )
      yield assertTrue(results.forall((i, compliant) => compliant == (i % 3 != 0)))
    },
    test("the plain variant prints the same page without anything that says PDF/A (DMS-118)") {
      for
        archive <- ZIO.serviceWithZIO[PdfRenderer](_.render(job(page)))
        plain   <- ZIO.serviceWithZIO[PdfRenderer](_.render(job(page).copy(variant = PdfVariant.Plain)))
        again   <- ZIO.serviceWithZIO[PdfRenderer](_.render(job(page).copy(variant = PdfVariant.Plain)))
        report  <- VeraPdfValidator.validate(plain.bytes, ArchiveStandard.PdfA2b)
        texts               = RenderingWorker.pageTexts(plain.bytes).map(RenderingWorker.normalized)
        (metadata, intents) =
          val document = Loader.loadPDF(plain.bytes.toArray)
          try (Option(document.getDocumentCatalog.getMetadata), document.getDocumentCatalog.getOutputIntents)
          finally document.close()
      yield assertTrue(
        metadata.isEmpty,
        intents.isEmpty,
        !report.compliant,
        !String(plain.bytes.toArray, StandardCharsets.ISO_8859_1).contains("pdfaid"),
        plain.pages == archive.pages,
        texts == RenderingWorker.pageTexts(archive.bytes).map(RenderingWorker.normalized),
        sha256(plain.bytes) == sha256(again.bytes),
        plain.worker.build.isDefined
      )
    }
  ).provideShared(RenderingWorker.renderer) @@ TestAspect.sequential @@ TestAspect.withLiveClock
