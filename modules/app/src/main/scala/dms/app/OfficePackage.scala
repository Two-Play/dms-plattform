package dms.app

import org.w3c.dom.{Document, Element, Node}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.{OutputKeys, TransformerFactory}
import scala.collection.mutable.ListBuffer

/** Office Open XML packages (DOCX, XLSX, PPTX) read and written on the JVM with the JDK's XML parser
  * (DMS-115, DMS-122, ADR 0042): unzipped with limits against zip bombs, the parts that carry fields parsed
  * hardened (no DTDs, no external entities), every other part copied unchanged, written deterministically
  * (fixed entry times).
  */
private[app] object OfficePackage:

  /** Limits against zip bombs: entries and uncompressed bytes. */
  private val MaxEntries = 2000
  private val MaxBytes   = 256L * 1024 * 1024

  /** Fixed modification time of written entries (1980-01-01, the DOS epoch). */
  private val EntryTime = 315532800000L

  type Entries = List[(String, Array[Byte])]

  /** The entries of a package that must contain `mainPart`; `kind` names the format in messages. */
  def unzip(bytes: Array[Byte], mainPart: String, kind: String): Either[String, Entries] =
    attempt {
      val in      = ZipInputStream(ByteArrayInputStream(bytes))
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
      if !entries.exists(_._1 == mainPart) then throw IllegalArgumentException(s"not a $kind (no $mainPart)")
      entries.toList
    }.left.map(m => s"the file is not a readable $kind: $m")

  /** The parts whose names match `parts`, parsed as namespace-aware DOM. */
  def parse(
      entries: Entries,
      parts: String => Boolean,
      kind: String
  ): Either[String, List[(String, Document)]] =
    attempt {
      val factory = DocumentBuilderFactory.newInstance()
      factory.setNamespaceAware(true)
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
      factory.setXIncludeAware(false)
      factory.setExpandEntityReferences(false)
      entries.collect {
        case (name, data) if parts(name) =>
          val builder = factory.newDocumentBuilder()
          builder.setErrorHandler(null)
          name -> builder.parse(ByteArrayInputStream(data))
      }
    }.left.map(m => s"the $kind cannot be read: $m")

  /** The package with the changed parts written back, all other entries unchanged and in their order. */
  def write(entries: Entries, changed: List[(String, Document)]): Either[String, Array[Byte]] =
    attempt {
      val parts = changed.map((name, doc) => name -> serialize(doc)).toMap
      zip(entries.map((name, data) => name -> parts.getOrElse(name, data)))
    }

  private def serialize(doc: Document): Array[Byte] =
    doc.setXmlStandalone(true)
    val transformer = TransformerFactory.newInstance().newTransformer()
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
    val out = ByteArrayOutputStream()
    transformer.transform(DOMSource(doc), StreamResult(out))
    out.toByteArray

  private def zip(entries: Entries): Array[Byte] =
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

  def attempt[A](f: => A): Either[String, A] =
    try Right(f)
    catch case e: Exception => Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))

  // DOM helpers

  def is(n: Node, ns: String, local: String): Boolean =
    n.getNodeType == Node.ELEMENT_NODE && n.getNamespaceURI == ns && n.getLocalName == local

  def children(e: Element): List[Element] =
    val nodes = e.getChildNodes
    (0 until nodes.getLength).map(nodes.item).collect { case c: Element => c }.toList

  def descendants(e: Element, ns: String, local: String): List[Element] =
    val nodes = e.getElementsByTagNameNS(ns, local)
    (0 until nodes.getLength).map(nodes.item).collect { case c: Element => c }.toList

/** `{{key}}` / `{{dms:key}}` placeholders in the text of a paragraph (DMS-115, DMS-122): the text elements of
  * one paragraph (Word `w:t`, PowerPoint `a:t`, Excel `t` of a shared or inline string) are read as one text,
  * so placeholders split over several runs (Word splits runs where formatting, spelling marks or revision ids
  * change) are found. Filling a split placeholder writes the value into the text element where the
  * placeholder starts, so the value takes that run's formatting, and removes the rest of the placeholder from
  * the following ones; text around placeholders stays where it was.
  */
private[app] object Placeholders:

  val Pattern: scala.util.matching.Regex = "\\{\\{\\s*([A-Za-z][A-Za-z0-9_.:-]{0,67})\\s*\\}\\}".r

  /** The names inside the placeholders of a paragraph's text elements, in order. */
  def names(texts: List[Element]): List[String] =
    val joined = texts.map(_.getTextContent).mkString
    if !joined.contains("{{") then Nil else Pattern.findAllMatchIn(joined).map(_.group(1)).toList

  /** Replaces the placeholders with a value; others stay as they are. `preserveSpace` marks changed text
    * elements with `xml:space="preserve"` (WordprocessingML and SpreadsheetML; DrawingML keeps spaces
    * anyway).
    */
  def fill(texts: List[Element], value: String => Option[String], preserveSpace: Boolean = true): Unit =
    val contents = texts.map(_.getTextContent).toVector
    val joined   = contents.mkString
    if joined.contains("{{") then
      // the text element holding each position of the joined text
      val owner                          = contents.zipWithIndex.flatMap((c, i) => Vector.fill(c.length)(i))
      val out                            = Vector.fill(contents.size)(StringBuilder())
      def copy(from: Int, to: Int): Unit =
        (from until to).foreach(p => out(owner(p)).append(joined.charAt(p)))
      var pos = 0
      Pattern.findAllMatchIn(joined).foreach { m =>
        value(m.group(1)).foreach { v =>
          copy(pos, m.start)
          out(owner(m.start)).append(v): Unit
          pos = m.end
        }
      }
      if pos > 0 then
        copy(pos, joined.length)
        texts.zip(out).zip(contents).foreach { case ((t, b), before) =>
          val after = b.toString
          if after != before then
            t.setTextContent(after)
            if preserveSpace then t.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve")
        }

  /** The text elements (`ns`:`text`) below `root` grouped by their paragraph: the nearest ancestor for which
    * `paragraph` holds. Elements for which `skip` holds are not entered.
    */
  def paragraphs(
      root: Element,
      ns: String,
      text: String,
      paragraph: Element => Boolean,
      skip: Element => Boolean = _ => false
  ): List[List[Element]] =
    val groups                                              = ListBuffer.empty[List[Element]]
    def collect(e: Element, acc: ListBuffer[Element]): Unit =
      OfficePackage.children(e).foreach { c =>
        if OfficePackage.is(c, ns, text) then acc += c
        else if skip(c) then ()
        else if paragraph(c) then visit(c)
        else collect(c, acc)
      }
    def visit(p: Element): Unit =
      val own = ListBuffer.empty[Element]
      collect(p, own)
      if own.nonEmpty then groups += own.toList
    visit(root)
    groups.toList
