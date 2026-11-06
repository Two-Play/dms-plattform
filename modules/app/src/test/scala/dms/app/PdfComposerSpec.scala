package dms.app

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.common.PDMetadata
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent
import org.apache.pdfbox.pdmodel.{PDDocument, PDPage}
import zio.test.*

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets

/** Stamping a converted original that identifies itself as PDF/A (DMS-118, ADR 0073). */
object PdfComposerSpec extends ZIOSpecDefault:

  /** One page with the marks of PDF/A: an XMP packet and an output intent. */
  private def identified: Array[Byte] =
    val document = PDDocument()
    try
      document.addPage(PDPage())
      val metadata = PDMetadata(document)
      metadata.importXMPMetadata("<pdfaid:part>2</pdfaid:part>".getBytes(StandardCharsets.UTF_8))
      val catalog = document.getDocumentCatalog
      catalog.setMetadata(metadata)
      catalog.addOutputIntent(PDOutputIntent(document, ByteArrayInputStream(profile)))
      val out = ByteArrayOutputStream()
      document.save(out)
      out.toByteArray
    finally document.close()

  private def profile: Array[Byte] =
    java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData

  private def marks(pdf: Array[Byte]): (Boolean, Int) =
    val document = Loader.loadPDF(pdf)
    try
      val catalog = document.getDocumentCatalog
      (Option(catalog.getMetadata).isDefined, catalog.getOutputIntents.size)
    finally document.close()

  def spec = suite("PdfComposer")(
    test("a stamped draft no longer identifies itself as PDF/A") {
      val stamped = PdfComposer.stamp(identified, List("Draft – not valid"), emphasize = true, plain = true)
      assertTrue(
        marks(identified) == (true, 1),
        stamped.map(marks) == Right((false, 0)),
        stamped.flatMap(PdfComposer.pages) == Right(1)
      )
    },
    test("other stamps keep what the file says about itself") {
      val stamped = PdfComposer.stamp(identified, List("Copy"), emphasize = false)
      assertTrue(stamped.map(marks) == Right((true, 1)))
    }
  )
