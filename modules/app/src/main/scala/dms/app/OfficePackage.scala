package dms.app

import org.w3c.dom.{Document, Element, Node}
import org.xml.sax.SAXParseException
import zio.*

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.{OutputKeys, TransformerFactory}
import scala.collection.mutable.ListBuffer

/** Why a file cannot be read: it is broken, or reading it would take more than the platform lends one file
  * (DMS-160, ADR 0076).
  */
private[app] enum OfficeProblem(val message: String):
  case Unreadable(detail: String) extends OfficeProblem(detail)
  case TooComplex(detail: String) extends OfficeProblem(detail)

  /** The same problem with `prefix` in front of a technical message. */
  def unreadableAs(prefix: String): OfficeProblem = this match
    case Unreadable(m) => Unreadable(prefix + m)
    case other         => other

private[app] object OfficeProblem:

  /** The error code of a file refused for its size or structure. */
  val TooComplexCode = "office_file_too_complex"

  /** The answer to a request; `code` is that of an unreadable file. */
  def response(code: String)(problem: OfficeProblem): (sttp.model.StatusCode, ApiError) = problem match
    case Unreadable(m) => (sttp.model.StatusCode.UnprocessableEntity, ApiError(code, m))
    case TooComplex(m) => (sttp.model.StatusCode.UnprocessableEntity, ApiError(TooComplexCode, m))

/** Every read of an Office file goes through here (DMS-160, ADR 0076). A parsed file can take about 1 GiB of
  * heap in the worst case, so only as many run at once as half of the heap holds, off the threads that run
  * requests of other tenants. The rest wait their turn. A failure the JVM itself reports must be turned into
  * a value before ZIO sees it: ZIO ends the process on a `VirtualMachineError`.
  */
private[app] object OfficeGate:

  private val BytesPerParse = 2L * 1024 * 1024 * 1024

  val Permits: Int = (java.lang.Runtime.getRuntime.maxMemory / BytesPerParse).max(1L).min(4L).toInt

  private val permits = Unsafe.unsafe(implicit unsafe => Semaphore.unsafe.make(Permits.toLong))

  def apply[A](work: => Either[OfficeProblem, A]): IO[OfficeProblem, A] =
    permits.withPermit(ZIO.attemptBlocking(OfficePackage.attempt(work).flatten).orDie.absolve)

/** Office Open XML packages (DOCX, XLSX, PPTX) read and written on the JVM with the JDK's XML parser
  * (DMS-115, DMS-122, ADR 0042): unzipped with limits against zip bombs, the parts that carry fields parsed
  * hardened (no DTDs, no external entities, limited size and depth), every other part copied unchanged,
  * written deterministically (fixed entry times). Call it through [[OfficeGate]].
  */
private[app] object OfficePackage:

  /** Limits against zip bombs: entries and uncompressed bytes. */
  private val MaxEntries = 2000
  private val MaxBytes   = 256L * 1024 * 1024

  /** How many times larger than its compressed form a part may be; the first bytes of a part are exempt.
    * Office XML normally stays well below that, deflate ends at about 1000.
    */
  private val MaxRatio   = 200L
  private val RatioGrace = 8L * 1024 * 1024

  /** The parts parsed at once, in bytes. A DOM takes 6 to 23 times its XML in heap (measured, ADR 0076), so
    * this is what limits the memory of one file.
    */
  private[app] val MaxParsedBytes = 32L * 1024 * 1024

  /** Nesting of XML elements; the code below walks the DOM recursively. */
  private val MaxDepth = 500

  /** Fixed modification time of written entries (1980-01-01, the DOS epoch). */
  private val EntryTime = 315532800000L

  type Entries = List[(String, Array[Byte])]

  /** Stops a read with a problem; [[attempt]] turns it back into a value. */
  private[app] final class Failed(val problem: OfficeProblem) extends RuntimeException(problem.message)

  private def tooComplex(kind: String, reason: String) =
    Failed(
      OfficeProblem.TooComplex(
        s"the $kind is too large or too complex to be processed ($reason); split it or save it without " +
          "unneeded content"
      )
    )

  /** The position in the compressed bytes. */
  private final class Source(bytes: Array[Byte]) extends ByteArrayInputStream(bytes):
    def position: Int = pos

  /** The entries of a package that must contain `mainPart`; `kind` names the format in messages. */
  def unzip(bytes: Array[Byte], mainPart: String, kind: String): Either[OfficeProblem, Entries] =
    attempt {
      val source  = Source(bytes)
      val in      = ZipInputStream(source)
      val entries = ListBuffer.empty[(String, Array[Byte])]
      var total   = 0L
      try
        var entry = in.getNextEntry
        while entry != null do
          if entries.size >= MaxEntries then throw tooComplex(kind, "too many parts")
          val out    = ByteArrayOutputStream()
          val buffer = new Array[Byte](65536)
          val start  = source.position
          var read   = in.read(buffer)
          while read >= 0 do
            total += read
            if total > MaxBytes then throw tooComplex(kind, "too much data when unpacked")
            out.write(buffer, 0, read)
            val compressed = (source.position - start).max(1).toLong
            if out.size > RatioGrace && out.size > compressed * MaxRatio then
              throw tooComplex(kind, "a part is packed too densely")
            read = in.read(buffer)
          if !entry.isDirectory then entries += (entry.getName -> out.toByteArray)
          entry = in.getNextEntry
      finally in.close()
      if !entries.exists(_._1 == mainPart) then throw IllegalArgumentException(s"not a $kind (no $mainPart)")
      entries.toList
    }.left.map(_.unreadableAs(s"the file is not a readable $kind: "))

  /** The parts whose names match `parts`, parsed as namespace-aware DOM. */
  def parse(
      entries: Entries,
      parts: String => Boolean,
      kind: String
  ): Either[OfficeProblem, List[(String, Document)]] =
    attempt {
      val selected = entries.filter((name, _) => parts(name))
      if selected.map(_._2.length.toLong).sum > MaxParsedBytes then
        throw tooComplex(kind, s"more than ${MaxParsedBytes / 1024 / 1024} MiB of text and structure")
      // the JDK's parser whatever else is on the class path: it knows the limit on nesting
      val factory = DocumentBuilderFactory.newDefaultInstance()
      factory.setNamespaceAware(true)
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
      factory.setXIncludeAware(false)
      factory.setExpandEntityReferences(false)
      factory.setAttribute("jdk.xml.maxElementDepth", MaxDepth.toString)
      selected.map { (name, data) =>
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(null)
        try name -> builder.parse(ByteArrayInputStream(data))
        catch
          case e: SAXParseException if Option(e.getMessage).exists(_.contains("maxElementDepth")) =>
            throw tooComplex(kind, "elements nested too deeply")
      }
    }.left.map(_.unreadableAs(s"the $kind cannot be read: "))

  /** The package with the changed parts written back, all other entries unchanged and in their order; `extra`
    * entries replace those of the same name or are appended in their order.
    */
  def write(
      entries: Entries,
      changed: List[(String, Document)],
      extra: Entries = Nil
  ): Either[OfficeProblem, Array[Byte]] =
    attempt {
      val parts = changed.map((name, doc) => name -> serialize(doc)).toMap ++ extra.toMap
      val known = entries.map(_._1).toSet
      zip(
        entries.map((name, data) => name -> parts.getOrElse(name, data)) ++
          extra.filterNot((name, _) => known(name))
      )
    }

  def serialize(doc: Document): Array[Byte] =
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

  /** `f` as a value. The memory and the stack run out on files the limits let through, and only for this
    * file: what it took is free again once it is refused.
    */
  def attempt[A](f: => A): Either[OfficeProblem, A] =
    try Right(f)
    catch
      case e: Failed           => Left(e.problem)
      case _: OutOfMemoryError =>
        Left(OfficeProblem.TooComplex("the file is too large or too complex to be processed right now"))
      case _: StackOverflowError =>
        Left(OfficeProblem.TooComplex("the file is too large or too complex to be processed"))
      case e: Exception =>
        Left(OfficeProblem.Unreadable(Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))

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
