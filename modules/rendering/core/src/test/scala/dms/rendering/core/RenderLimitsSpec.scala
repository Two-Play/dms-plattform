package dms.rendering.core

import dms.rendering.{ArchiveStandard, PdfVariant}
import zio.*
import zio.test.*

/** The limits of the rendering worker against a real worker with small limits (DMS-171, ADR 0081). */
object RenderLimitsSpec extends ZIOSpecDefault:

  private val paragraph =
    "<p>The provider delivers the services described in this contract with due care and in accordance " +
      "with the service levels of annex 1. Changes need the written form. Prices are net prices plus " +
      "statutory value added tax; invoices are due within thirty days of receipt, without any deduction.</p>"

  private def page(paragraphs: Int) =
    s"""<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><title>Limits</title></head>
       |<body>${paragraph * paragraphs}</body></html>""".stripMargin

  private def job(html: String) =
    PdfJob(html, PdfVariant.Archive(ArchiveStandard.PdfA2b), "0" * 32, Map.empty)

  private val worker = RenderingWorker.configured(
    Map("DMS_RENDER_MAX_PAGES" -> "2000", "DMS_RENDER_TIME_LIMIT_SECONDS" -> "5")
  ) >>> WeasyPrintRenderer.layer

  def spec = suite("Limits of the rendering worker")(
    test("a document over the page limit is refused without being rendered") {
      for result <- ZIO.serviceWithZIO[PdfRenderer](_.render(job(page(30000)))).either
      yield assertTrue(result match
        case Left(RenderingError.TooLong(message)) => message.contains("the limit is 2000")
        case _                                     => false)
    },
    test("a render over the time limit is ended, and the worker prints the next page at once") {
      for
        renderer <- ZIO.service[PdfRenderer]
        slow     <- renderer.render(job(page(6000))).either
        next     <- renderer.render(job(page(3))).either
      yield assertTrue(
        slow match
          case Left(RenderingError.TimeLimit(message)) => message.contains("5 seconds")
          case _                                       => false
        ,
        next.exists(_.pages.contains(1))
      )
    },
    test("a refused page does not count as a limit: it stays rejected") {
      val external = page(1).replace("</body>", """<img src="https://example.com/logo.png"></body>""")
      for result <- ZIO.serviceWithZIO[PdfRenderer](_.render(job(external))).either
      yield assertTrue(result match
        case Left(RenderingError.Rejected(message)) => message.contains("example.com")
        case _                                      => false)
    }
  ).provideShared(worker) @@ TestAspect.sequential @@ TestAspect.withLiveClock
