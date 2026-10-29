package dms.app

import dms.templates.{WordField, WordFields}
import org.w3c.dom.{Document, Element, Node}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.{OutputKeys, TransformerFactory}
import scala.collection.mutable.ListBuffer

/** Fields of Word documents (DOCX, Office Open XML) found and filled on the JVM (DMS-115, ADR 0042), with the
  * JDK's XML parser only: the package is unzipped, the main document part, headers and footers are changed as
  * DOM, every other part is copied unchanged, so formatting, styles, images and everything else stay as they
  * were.
  *
  * Fields:
  *   - **Content controls** (`w:sdt`, Word "Inhaltssteuerelemente", Developer tab): the tag (`w:tag`) is the
  *     field key, the title (`w:alias`) its label. Tags `dms:<key>` are filled by the platform, other tags
  *     are manual fields. Filling replaces the control's text with the value in the formatting of its first
  *     run; the control stays, so the filled document can be filled again. A data binding (`w:dataBinding`)
  *     is removed, since it would make Word show the bound value instead.
  *   - **Placeholders** `{{key}}` / `{{dms:key}}` inside one run of text (Word splits text into runs when
  *     formatting or spelling marks change in the middle; such placeholders are not found).
  *
  * Fields without value keep their content (e.g. Word's placeholder text). Deterministic: equal input gives
  * equal bytes (fixed entry times), so a filled document of an approved version can be reproduced.
  */
object WordDocx:

  private val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

  /** Parts that can carry fields. */
  private val FieldParts = "word/(document|header\\d*|footer\\d*|footnotes|endnotes)\\.xml".r

  private val Placeholder = "\\{\\{\\s*([A-Za-z][A-Za-z0-9_.:-]{0,67})\\s*\\}\\}".r

  /** Limits against zip bombs: entries and uncompressed bytes. */
  private val MaxEntries = 2000
  private val MaxBytes   = 256L * 1024 * 1024

  /** Fixed modification time of written entries (1980-01-01, the DOS epoch). */
  private val EntryTime = 315532800000L

  /** The fields of a DOCX in document order, each key once. */
  def scan(docx: Array[Byte]): Either[String, List[WordField]] =
    for
      entries <- unzip(docx)
      parts   <- parse(entries)
    yield parts
      .flatMap((_, doc) => fieldsOf(doc))
      .distinctBy(_.key)

  /** The DOCX with `values` (by key, without the `dms:` prefix) filled into its fields. */
  def fill(docx: Array[Byte], values: Map[String, String]): Either[String, Array[Byte]] =
    for
      entries <- unzip(docx)
      parts   <- parse(entries)
      changed = parts.map { (name, doc) =>
        fillDocument(doc, values)
        name -> serialize(doc)
      }.toMap
      bytes <- attempt(zip(entries.map((name, data) => name -> changed.getOrElse(name, data))))
    yield bytes

  private def fieldsOf(doc: Document): List[WordField] =
    val found = ListBuffer.empty[WordField]
    walk(doc.getDocumentElement) { e =>
      if isW(e, "sdt") then
        val pr = child(e, "sdtPr")
        pr.flatMap(p => child(p, "tag"))
          .flatMap(attr(_, "val"))
          .flatMap(tag => WordFields.parse(tag, pr.flatMap(p => child(p, "alias")).flatMap(attr(_, "val"))))
          .foreach(found += _)
      else if isW(e, "t") then
        Placeholder
          .findAllMatchIn(e.getTextContent)
          .foreach(m => WordFields.parse(m.group(1), None).foreach(found += _))
    }
    found.toList

  private def fillDocument(doc: Document, values: Map[String, String]): Unit =
    val controls = ListBuffer.empty[(Element, String)]
    val texts    = ListBuffer.empty[Element]
    walk(doc.getDocumentElement) { e =>
      if isW(e, "sdt") then
        child(e, "sdtPr")
          .flatMap(p => child(p, "tag"))
          .flatMap(attr(_, "val"))
          .flatMap(WordFields.parse(_, None))
          .flatMap(f => values.get(f.key))
          .foreach(v => controls += (e -> v))
      else if isW(e, "t") then texts += e
    }
    // Innermost controls first; an outer control with a value replaces its whole content anyway.
    controls.reverse.foreach((sdt, value) => fillControl(doc, sdt, value))
    texts.foreach { t =>
      val text = t.getTextContent
      if text.contains("{{") then
        val replaced = Placeholder.replaceAllIn(
          text,
          m =>
            java.util.regex.Matcher.quoteReplacement(
              WordFields.parse(m.group(1), None).flatMap(f => values.get(f.key)).getOrElse(m.matched)
            )
        )
        if replaced != text then
          t.setTextContent(replaced)
          t.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve")
    }

  private def fillControl(doc: Document, sdt: Element, value: String): Unit =
    child(sdt, "sdtPr").foreach { pr =>
      child(pr, "showingPlcHdr").foreach(pr.removeChild)
      child(pr, "dataBinding").foreach(pr.removeChild)
    }
    child(sdt, "sdtContent").foreach { content =>
      // Block-level controls: keep the first paragraph, drop the others.
      val paragraphs = children(content).filter(isW(_, "p"))
      paragraphs.drop(1).foreach(content.removeChild)
      val runs  = descendants(content, "r")
      val first = runs.headOption
      runs.drop(1).foreach(r => r.getParentNode.removeChild(r))
      val run = first.getOrElse {
        val r      = doc.createElementNS(W, "w:r")
        val holder = paragraphs.headOption.getOrElse(content)
        holder.appendChild(r)
        r
      }
      // The run keeps its formatting (w:rPr) except the gray placeholder style.
      children(run).filterNot(isW(_, "rPr")).foreach(run.removeChild)
      child(run, "rPr")
        .flatMap(rpr => child(rpr, "rStyle").filter(attr(_, "val").contains("PlaceholderText")).map(rpr -> _))
        .foreach((rpr, style) => rpr.removeChild(style))
      value.split("\n", -1).zipWithIndex.foreach { (line, i) =>
        if i > 0 then run.appendChild(doc.createElementNS(W, "w:br")): Unit
        val t = doc.createElementNS(W, "w:t")
        t.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve")
        t.setTextContent(line)
        run.appendChild(t): Unit
      }
    }

  private def isW(n: Node, local: String): Boolean =
    n.getNodeType == Node.ELEMENT_NODE && n.getNamespaceURI == W && n.getLocalName == local

  private def children(e: Element): List[Element] =
    val nodes = e.getChildNodes
    (0 until nodes.getLength).map(nodes.item).collect { case c: Element => c }.toList

  private def child(e: Element, local: String): Option[Element] = children(e).find(isW(_, local))

  private def descendants(e: Element, local: String): List[Element] =
    val nodes = e.getElementsByTagNameNS(W, local)
    (0 until nodes.getLength).map(nodes.item).collect { case c: Element => c }.toList

  private def attr(e: Element, local: String): Option[String] =
    Option(e.getAttributeNodeNS(W, local)).map(_.getValue)

  private def walk(e: Element)(f: Element => Unit): Unit =
    f(e)
    children(e).foreach(walk(_)(f))

  private def unzip(docx: Array[Byte]): Either[String, List[(String, Array[Byte])]] =
    attempt {
      val in      = ZipInputStream(ByteArrayInputStream(docx))
      val entries = ListBuffer.empty[(String, Array[Byte])]
      var total   = 0L
      try
        var entry = in.getNextEntry
        while entry != null do
          if entries.size >= MaxEntries then throw IllegalArgumentException("too many parts")
          val out    = ByteArrayOutputStream()
          val buffer = new Array[Byte](65536)
          var read   = in.read(buffer)
          while read >= 0 do
            total += read
            if total > MaxBytes then throw IllegalArgumentException("the unpacked document is too large")
            out.write(buffer, 0, read)
            read = in.read(buffer)
          if !entry.isDirectory then entries += (entry.getName -> out.toByteArray)
          entry = in.getNextEntry
      finally in.close()
      if !entries.exists(_._1 == "word/document.xml") then
        throw IllegalArgumentException("not a Word document (no word/document.xml)")
      entries.toList
    }.left.map(m => s"the file is not a readable Word document (DOCX): $m")

  private def parse(entries: List[(String, Array[Byte])]): Either[String, List[(String, Document)]] =
    attempt {
      val factory = DocumentBuilderFactory.newInstance()
      factory.setNamespaceAware(true)
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
      factory.setXIncludeAware(false)
      factory.setExpandEntityReferences(false)
      entries.collect {
        case (name, data) if FieldParts.matches(name) =>
          val builder = factory.newDocumentBuilder()
          builder.setErrorHandler(null)
          name -> builder.parse(ByteArrayInputStream(data))
      }
    }.left.map(m => s"the Word document cannot be read: $m")

  private def serialize(doc: Document): Array[Byte] =
    doc.setXmlStandalone(true)
    val transformer = TransformerFactory.newInstance().newTransformer()
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
    val out = ByteArrayOutputStream()
    transformer.transform(DOMSource(doc), StreamResult(out))
    out.toByteArray

  private def zip(entries: List[(String, Array[Byte])]): Array[Byte] =
    val out = ByteArrayOutputStream()
    val zip = ZipOutputStream(out)
    try
      entries.foreach { (name, data) =>
        val entry = ZipEntry(name)
        entry.setTime(EntryTime)
        zip.putNextEntry(entry)
        zip.write(data)
        zip.closeEntry()
      }
    finally zip.close()
    out.toByteArray

  private def attempt[A](f: => A): Either[String, A] =
    try Right(f)
    catch case e: Exception => Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
