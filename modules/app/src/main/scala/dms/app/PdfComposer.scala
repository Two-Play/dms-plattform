package dms.app

import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.multipdf.PDFMergerUtility
import org.apache.pdfbox.pdmodel.font.{PDType1Font, Standard14Fonts}
import org.apache.pdfbox.pdmodel.{PDDocument, PDPageContentStream}

import java.io.ByteArrayOutputStream
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Marks and joins the on-demand PDFs of file documents (DMS-115, ADR 0042) with PDFBox: the converted
  * original gets a header line on every page (the draft marking, or the copy note), and the appendices
  * (change history, list of attachments) printed by the rendering worker are appended. The results are handed
  * out, never archived; the archived rendition is not altered.
  */
object PdfComposer:

  /** Font size of the header line in points. */
  private val FontSize = 7f

  /** Writes `lines` at the top of every page, right-aligned, the first line in red if `emphasize`. Text that
    * the standard font cannot encode is replaced by `?`.
    */
  def stamp(pdf: Array[Byte], lines: List[String], emphasize: Boolean): Either[String, Array[Byte]] =
    attempt {
      Using.resource(Loader.loadPDF(pdf)) { document =>
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        document.getPages.asScala.foreach { page =>
          val box = page.getCropBox
          Using.resource(
            PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)
          ) { out =>
            lines.zipWithIndex.foreach { (raw, i) =>
              val text  = encodable(font, raw)
              val width = font.getStringWidth(text) / 1000 * FontSize
              val x     = (box.getUpperRightX - 18 - width).max(box.getLowerLeftX + 18)
              val y     = box.getUpperRightY - 12 - i * (FontSize + 2)
              if emphasize && i == 0 then out.setNonStrokingColor(0.71f, 0.14f, 0.09f)
              else out.setNonStrokingColor(0.2f, 0.2f, 0.2f)
              out.beginText()
              out.setFont(font, FontSize)
              out.newLineAtOffset(x, y)
              out.showText(text)
              out.endText()
            }
          }
        }
        save(document)
      }
    }

  /** `first` followed by the pages of `rest`. */
  def append(first: Array[Byte], rest: Array[Byte]): Either[String, Array[Byte]] =
    attempt {
      Using.resources(Loader.loadPDF(first), Loader.loadPDF(rest)) { (target, source) =>
        PDFMergerUtility().appendDocument(target, source)
        save(target)
      }
    }

  /** Number of pages, e.g. for tests and checks. */
  def pages(pdf: Array[Byte]): Either[String, Int] =
    attempt(Using.resource(Loader.loadPDF(RandomAccessReadBuffer(pdf)))(_.getNumberOfPages))

  private def save(document: PDDocument): Array[Byte] =
    val out = ByteArrayOutputStream()
    document.save(out)
    out.toByteArray

  private def encodable(font: PDType1Font, text: String): String =
    text.map { c =>
      val s = c.toString
      if scala.util.Try(font.encode(s)).isSuccess then c else '?'
    }

  private def attempt[A](f: => A): Either[String, A] =
    try Right(f)
    catch case e: Exception => Left(s"the PDF cannot be processed: ${e.getMessage}")
