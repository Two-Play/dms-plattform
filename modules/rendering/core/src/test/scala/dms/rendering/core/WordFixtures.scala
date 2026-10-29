package dms.rendering.core

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}

/** Minimal Word documents (DOCX) for tests (DMS-115): the parts Word and LibreOffice need, with a body of
  * WordprocessingML and optional header.
  */
object WordFixtures:

  val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

  /** A content control with tag, title (alias) and placeholder text in the given run properties. */
  def control(tag: String, alias: String, text: String, runProperties: String = "<w:b/>"): String =
    s"""<w:sdt><w:sdtPr><w:alias w:val="$alias"/><w:tag w:val="$tag"/><w:showingPlcHdr/></w:sdtPr>""" +
      s"""<w:sdtContent><w:r><w:rPr><w:rStyle w:val="PlaceholderText"/>$runProperties</w:rPr>""" +
      s"""<w:t>$text</w:t></w:r></w:sdtContent></w:sdt>"""

  def paragraph(content: String): String = s"<w:p>$content</w:p>"

  def textRun(text: String): String = s"""<w:r><w:t xml:space="preserve">$text</w:t></w:r>"""

  /** A DOCX with `body` (paragraphs) and, if given, a header part with `header` (paragraphs). */
  def docx(body: String, header: Option[String] = None): Array[Byte] =
    val headerRef =
      header.fold("")(_ => """<w:sectPr><w:headerReference w:type="default" r:id="rIdHeader"/></w:sectPr>""")
    val document =
      s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
         |<w:document xmlns:w="$W" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
         |<w:body>$body$headerRef</w:body></w:document>""".stripMargin
    val types =
      """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
        """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
        """<Default Extension="xml" ContentType="application/xml"/>""" +
        """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
        header.fold("")(_ =>
          """<Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>"""
        ) + "</Types>"
    val rels =
      """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
        """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
        "</Relationships>"
    val documentRels =
      """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
        header.fold("")(_ =>
          """<Relationship Id="rIdHeader" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>"""
        ) + "</Relationships>"
    val parts = List(
      "[Content_Types].xml"          -> types,
      "_rels/.rels"                  -> rels,
      "word/document.xml"            -> document,
      "word/_rels/document.xml.rels" -> documentRels
    ) ++ header.map(h =>
      "word/header1.xml" -> s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:hdr xmlns:w="$W">$h</w:hdr>"""
    )
    val out = ByteArrayOutputStream()
    val zip = ZipOutputStream(out)
    try
      parts.foreach { (name, text) =>
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.getBytes(UTF_8))
        zip.closeEntry()
      }
    finally zip.close()
    out.toByteArray

  /** The parts of a DOCX by name. */
  def parts(docx: Array[Byte]): Map[String, Array[Byte]] =
    val in = ZipInputStream(java.io.ByteArrayInputStream(docx))
    try
      Iterator
        .continually(in.getNextEntry)
        .takeWhile(_ != null)
        .map(e => e.getName -> in.readAllBytes())
        .toMap
    finally in.close()

  /** The text of a part. */
  def part(docx: Array[Byte], name: String): String = String(parts(docx)(name), UTF_8)
