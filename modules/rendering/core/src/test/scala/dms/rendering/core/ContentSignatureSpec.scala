package dms.rendering.core

import zio.test.*

import java.nio.charset.StandardCharsets.{UTF_16LE, UTF_8}

/** The content of an upload must fit its extension before it is converted (DMS-121). */
object ContentSignatureSpec extends ZIOSpecDefault:

  private val zip  = Array[Byte](0x50, 0x4b, 0x03, 0x04, 0x14, 0x00)
  private val ole  = Array(0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1, 0x00).map(_.toByte)
  private val pdf  = "%PDF-1.7\n%âãÏÓ".getBytes(UTF_8)
  private val rtf  = "{\\rtf1\\ansi Hello}".getBytes(UTF_8)
  private val text = "a;b;c\n1;2;3\n".getBytes(UTF_8)

  private val textExtensions = Set("txt", "csv", "html", "htm")

  private def ok(name: String, head: Array[Byte])  = DocumentConverter.contentMatches(name, head).isRight
  private def bad(name: String, head: Array[Byte]) = DocumentConverter.contentMatches(name, head).isLeft

  def spec = suite("Content signatures")(
    test("every convertible binary format has a signature: an empty file is refused") {
      val unchecked = DocumentConverter.ConvertibleExtensions
        .diff(textExtensions)
        .filter(ext => ok(s"file.$ext", Array.emptyByteArray))
      assertTrue(unchecked.isEmpty)
    },
    test("matching content is accepted") {
      assertTrue(
        ok("Budget.XLSX", zip),
        ok("Slides.pptx", zip),
        ok("Text.odt", zip),
        ok("Old.xls", ole),
        ok("Old.doc", ole),
        ok("Old.doc", rtf),
        ok("Letter.rtf", rtf),
        ok("Scan.pdf", pdf),
        // PDF readers accept a few bytes before the header
        ok("Scan.pdf", Array.fill[Byte](20)(' '.toByte) ++ pdf),
        ok("Data.csv", text),
        ok("Page.html", "<!doctype html><p>Hi</p>".getBytes(UTF_8)),
        ok("Wide.txt", Array(0xff, 0xfe).map(_.toByte) ++ "Hi".getBytes(UTF_16LE)),
        // names that are not convertible are decided elsewhere
        ok("data.zip", pdf)
      )
    },
    test("mismatches are refused with what was expected") {
      val message = DocumentConverter.contentMatches("Budget.xlsx", pdf).swap.getOrElse("")
      assertTrue(
        bad("Budget.xlsx", pdf),
        bad("Budget.xlsx", ole),
        bad("Old.xls", zip),
        bad("Old.doc", pdf),
        bad("Scan.pdf", zip),
        bad("Scan.pdf", Array.fill[Byte](2000)(' '.toByte) ++ pdf),
        bad("Letter.rtf", text),
        bad("Data.csv", zip),
        bad("Notes.txt", pdf),
        bad("Notes.txt", Array[Byte](0x41, 0x00, 0x42)),
        bad("Empty.docx", Array.emptyByteArray),
        message.contains("Budget.xlsx"),
        message.contains(".xlsx"),
        message.contains("ZIP")
      )
    }
  )
