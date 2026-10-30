package dms.rendering.core

import dms.rendering.ArchiveStandard
import zio.test.*

/** The error text of a failed rendering job carries the failure's code, so the rendition status can explain
  * it (DMS-93).
  */
object RenditionJobErrorSpec extends ZIOSpecDefault:

  def spec = suite("Rendering job errors")(
    test("every failure is written with its code and read back") {
      val errors = List(
        RenderingError.Unavailable("connection refused"),
        RenderingError.Rejected("external resource"),
        RenderingError.NotArchivable(
          ValidationReport(ArchiveStandard.PdfA2b, compliant = false, "veraPDF", List("6.2.11.4.1-1"))
        ),
        RenderingError.Invalid("version 1.0 is Draft"),
        RenderingError.Integrity("checksum mismatch")
      )
      val parsed = errors.map(e => RenditionJobs.parseJobError(RenditionJobs.jobError(e)))
      assertTrue(
        parsed.map(_._1) ==
          List(
            Some("unavailable"),
            Some("rejected"),
            Some("not_archivable"),
            Some("invalid"),
            Some("integrity")
          ),
        parsed.head._2 == "rendering unavailable: connection refused",
        parsed(2)._2.contains("6.2.11.4.1-1")
      )
    },
    test("texts of other writers (timeouts, defects, older jobs) keep their message without a code") {
      assertTrue(
        RenditionJobs.parseJobError("timed out after 270 s") == (None, "timed out after 270 s"),
        RenditionJobs.parseJobError("[Unavailable] x") == (None, "[Unavailable] x"),
        RenditionJobs.parseJobError("[invalid] line 1\nline 2") == (Some("invalid"), "line 1\nline 2")
      )
    }
  )
