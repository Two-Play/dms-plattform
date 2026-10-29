package dms.app

import dms.app.OfficePackage.{children, descendants, is}
import dms.templates.{WordField, WordFieldSource, WordFieldType, WordFields}
import org.w3c.dom.{Document, Element}

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.XMLConstants
import scala.collection.mutable.ListBuffer
import scala.util.Try

/** A value filled into a field of an Office document (DMS-122). */
enum FieldValue:
  /** Text; line breaks become breaks in Word. */
  case Text(text: String)

  /** A date: a date picker content control prints it in its own format and language and stores it as ISO
    * date; everywhere else `text` (the date in the document language) is printed.
    */
  case Date(date: LocalDate, text: String)

  /** Yes or no: a check box content control shows its checked or unchecked symbol; elsewhere ☒ or ☐. */
  case Check(checked: Boolean)

  /** Rows of a repeating section: the values of the columns by key. */
  case Rows(rows: List[Map[String, FieldValue]])

  /** The value as plain text, for placeholders and plain text controls. */
  def printed: String = this match
    case Text(t)    => t
    case Date(_, t) => t
    case Check(c)   => if c then FieldValue.CheckedSymbol else FieldValue.UncheckedSymbol
    case Rows(rows) => rows.map(_.toList.sortBy(_._1).map(_._2.printed).mkString(", ")).mkString("\n")

object FieldValue:
  val CheckedSymbol: String   = "☒"
  val UncheckedSymbol: String = "☐"

/** A paragraph of a Word document as text (DMS-121); `headingLevel` for paragraphs in a heading style. */
final case class WordParagraph(text: String, headingLevel: Option[Int] = None)

/** Fields of Word documents (DOCX, Office Open XML) found and filled on the JVM (DMS-115, DMS-122, ADR 0042),
  * with the JDK's XML parser only: the package is unzipped, the main document part, headers, footers and
  * notes are changed as DOM, every other part is copied unchanged, so formatting, styles, images and
  * everything else stay as they were.
  *
  * Fields:
  *   - **Content controls** (`w:sdt`, Word "Inhaltssteuerelemente", Developer tab): the tag (`w:tag`) is the
  *     field key, the title (`w:alias`) its label. Tags `dms:<key>` are filled by the platform, other tags
  *     are manual fields. Filling replaces the control's text with the value in the formatting of its first
  *     run; the control stays, so the filled document can be filled again. A data binding (`w:dataBinding`)
  *     is removed, since it would make Word show the bound value instead.
  *   - **Typed controls** (DMS-122): a check box (`w14:checkbox`) is a [[WordFieldType.Checkbox]] field whose
  *     state and symbol are set; a date picker (`w:date`) a date printed in the control's format and language
  *     (`w:dateFormat`, `w:lid`) and stored as ISO date (`w:fullDate`); a drop-down list or combo box
  *     (`w:dropDownList`, `w:comboBox`) a [[WordFieldType.Choice]] field whose options are the list items'
  *     display texts.
  *   - **Repeating sections** (`w15:repeatingSection`, DMS-122) with a tag: a [[WordFieldType.Rows]] field
  *     whose columns are the manual fields of its first item (`w15:repeatingSectionItem`, e.g. a table row);
  *     filling repeats that item once per row. Automatic fields inside are filled in every row. What cannot
  *     be filled (no item, nested repeating sections, sections without tag) is reported as the field's
  *     warning.
  *   - **Placeholders** `{{key}}` / `{{dms:key}}` in the text of a paragraph, also when Word split them into
  *     several runs; the value takes the formatting of the run where the placeholder starts (see
  *     [[Placeholders]]).
  *
  * Fields without value keep their content (e.g. Word's placeholder text). Deterministic: equal input gives
  * equal bytes (fixed entry times, ids of repeated controls numbered in document order), so a filled document
  * of an approved version can be reproduced.
  */
object WordDocx:

  private val W   = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
  private val W14 = "http://schemas.microsoft.com/office/word/2010/wordml"
  private val W15 = "http://schemas.microsoft.com/office/word/2012/wordml"

  private val MarkupCompatibility = "http://schemas.openxmlformats.org/markup-compatibility/2006"

  /** Parts that can carry fields. */
  private val FieldParts = "word/(document|header\\d*|footer\\d*|footnotes|endnotes)\\.xml".r

  private val Kind = "Word document (DOCX)"

  /** The fields of a DOCX in document order, each key once. */
  def scan(docx: Array[Byte]): Either[String, List[WordField]] =
    for
      entries <- OfficePackage.unzip(docx, "word/document.xml", Kind)
      parts   <- OfficePackage.parse(entries, FieldParts.matches, Kind)
    yield parts
      .flatMap((_, doc) => fieldsIn(doc.getDocumentElement, None))
      .distinctBy(_.key)

  /** The DOCX with `values` (by key, without the `dms:` prefix) filled into its fields as text. */
  def fill(docx: Array[Byte], values: Map[String, String]): Either[String, Array[Byte]] =
    fillValues(docx, values.map((k, v) => k -> FieldValue.Text(v)))

  /** The DOCX with `values` (by key, without the `dms:` prefix) filled into its fields. */
  def fillValues(docx: Array[Byte], values: Map[String, FieldValue]): Either[String, Array[Byte]] =
    for
      entries <- OfficePackage.unzip(docx, "word/document.xml", Kind)
      parts   <- OfficePackage.parse(entries, FieldParts.matches, Kind)
      _       <- OfficePackage.attempt(parts.foreach((_, doc) => fillDocument(doc, values)))
      bytes   <- OfficePackage.write(entries, parts)
    yield bytes

  /** The paragraphs of the main document part in document order (paragraphs in tables and text boxes
    * included), for comparing versions (DMS-121): the text of every run (`w:t`; tabs as spaces, breaks as
    * `\n`), without deleted text of tracked changes and without field codes. Empty paragraphs are left out.
    * Headers, footers and notes are not part of it.
    */
  def paragraphs(docx: Array[Byte]): Either[String, List[WordParagraph]] =
    for
      entries <- OfficePackage.unzip(docx, "word/document.xml", Kind)
      parts   <- OfficePackage.parse(entries.filter(_._1 == "word/document.xml"), FieldParts.matches, Kind)
    yield parts.flatMap((_, doc) => paragraphsOf(doc))

  /** The plain text of a DOCX for the search index (DMS-121): the paragraphs of the main document part, then
    * those of headers, footers, footnotes and endnotes, one per line.
    */
  def text(docx: Array[Byte]): Either[String, String] =
    for
      entries <- OfficePackage.unzip(docx, "word/document.xml", Kind)
      parts   <- OfficePackage.parse(entries, FieldParts.matches, Kind)
    yield parts
      .sortBy((name, _) => if name == "word/document.xml" then 0 else 1)
      .flatMap((_, doc) => paragraphsOf(doc))
      .map(_.text)
      .mkString("\n")

  private val HeadingStyle = "(?i)(?:heading|überschrift|berschrift)\\s*([1-6])".r

  private def paragraphsOf(doc: Document): List[WordParagraph] =
    val found                       = ListBuffer.empty[WordParagraph]
    def paragraph(p: Element): Unit =
      val text                      = StringBuilder()
      def collect(e: Element): Unit =
        children(e).foreach { c =>
          if isW(c, "p") then paragraph(c)
          else if isW(c, "t") then text.append(c.getTextContent)
          else if isW(c, "tab") then text.append(' ')
          else if isW(c, "br") || isW(c, "cr") then text.append('\n')
          else if !skipped(c) then collect(c)
        }
      collect(p)
      val style = child(p, "pPr").flatMap(child(_, "pStyle")).flatMap(attr(_, "val"))
      val level = style.flatMap {
        case HeadingStyle(n)                  => n.toIntOption
        case s if s.equalsIgnoreCase("Title") => Some(1)
        case _                                => None
      }
      val value = text.toString.split('\n').map(_.strip).mkString("\n").strip
      if value.nonEmpty then found += WordParagraph(value, level)
    def visit(e: Element): Unit =
      children(e).foreach(c => if isW(c, "p") then paragraph(c) else if !skipped(c) then visit(c))
    visit(doc.getDocumentElement)
    found.toList

  /** Properties and the fallback copy of drawings (text boxes are written twice, for old and new readers). */
  private def skipped(e: Element): Boolean =
    isW(e, "pPr") || isW(e, "rPr") || isW(e, "sdtPr") ||
      (e.getLocalName == "Fallback" && e.getNamespaceURI == MarkupCompatibility)

  // Scanning

  private val NotRepeated =
    "inside a repeating section without a tag: the section is not repeated, every item gets the same value"

  /** The fields below `root` in document order; `note` is the warning of fields in an untagged repeating
    * section.
    */
  private def fieldsIn(root: Element, note: Option[String]): List[WordField] =
    val found                                         = ListBuffer.empty[WordField]
    def visit(e: Element, note: Option[String]): Unit =
      val control = Option.when(isW(e, "sdt"))(child(e, "sdtPr").flatMap(controlField)).flatten
      if isW(e, "sdt") && repeating(e) then
        control.filter(_.source == WordFieldSource.Manual) match
          case Some(f) => found ++= rowsField(e, f)
          case None    =>
            found ++= control.map(_.copy(warning = Some(NotRepeated)))
            children(e).foreach(visit(_, Some(NotRepeated)))
      else
        control.foreach(f => found += f.copy(warning = note.orElse(f.warning)))
        if isW(e, "p") || isW(e, "sdt") then
          Placeholders
            .names(ownTexts(e))
            .flatMap(WordFields.parse(_, None))
            .foreach(f => found += f.copy(warning = note))
        children(e).foreach(visit(_, note))
    visit(root, note)
    found.toList

  /** A repeating section with a manual key: the field with its columns, and the automatic fields inside. */
  private def rowsField(section: Element, field: WordField): List[WordField] =
    val items      = sectionItems(section)
    val nested     = items.flatMap(i => descendants(i, W, "sdt").filter(repeating))
    val inner      = items.headOption.toList.flatMap(fieldsIn(_, None))
    val nestedKeys =
      nested.flatMap(n => child(n, "sdtPr").flatMap(controlField).map(_.key)).distinct
    val columns = inner
      .filter(c => c.source == WordFieldSource.Manual && c.fieldType != WordFieldType.Rows)
      .filterNot(c => nestedKeys.contains(c.key))
      .distinctBy(_.key)
      .map(_.copy(warning = None))
    val warnings = List(
      Option.when(items.isEmpty)("the repeating section has no item to repeat"),
      Option.when(nested.nonEmpty)(
        "nested repeating sections are not supported; they are copied unchanged into every row" +
          (if nestedKeys.isEmpty then "" else s" (${nestedKeys.mkString(", ")})")
      ),
      Option.when(items.nonEmpty && columns.isEmpty)(
        "the repeating section has no manual fields (content controls with a tag or {{key}} placeholders)"
      )
    ).flatten
    field.copy(
      fieldType = WordFieldType.Rows,
      options = Nil,
      columns = columns,
      warning = Option.when(warnings.nonEmpty)(warnings.mkString("; "))
    ) :: inner.filter(_.source == WordFieldSource.Automatic)

  /** The field a content control names, typed by the kind of control. */
  private def controlField(pr: Element): Option[WordField] =
    child(pr, "tag")
      .flatMap(attr(_, "val"))
      .flatMap(WordFields.parse(_, child(pr, "alias").flatMap(attr(_, "val"))))
      .map { f =>
        if f.source == WordFieldSource.Automatic then f
        else if childNs(pr, W14, "checkbox").isDefined then f.copy(fieldType = WordFieldType.Checkbox)
        else if child(pr, "date").isDefined then f.copy(fieldType = WordFieldType.Date)
        else
          child(pr, "dropDownList").orElse(child(pr, "comboBox")) match
            case Some(list) => f.copy(fieldType = WordFieldType.Choice, options = listItems(list).map(_._1))
            case None       => f
      }

  /** Display text and value of the items of a drop-down list or combo box; Word's empty "choose an item"
    * entry (empty value) is left out.
    */
  private def listItems(list: Element): List[(String, String)] =
    children(list)
      .filter(isW(_, "listItem"))
      .flatMap { item =>
        val value   = attr(item, "value")
        val display = attr(item, "displayText").orElse(value).map(_.trim).getOrElse("")
        Option.when(display.nonEmpty && !value.contains(""))(display -> value.getOrElse(display))
      }
      .distinctBy(_._1)

  private def repeating(sdt: Element): Boolean =
    child(sdt, "sdtPr").exists(pr => childNs(pr, W15, "repeatingSection").isDefined)

  private def sectionItems(section: Element): List[Element] =
    child(section, "sdtContent").toList
      .flatMap(children)
      .filter(c =>
        isW(c, "sdt") && child(c, "sdtPr").exists(pr => childNs(pr, W15, "repeatingSectionItem").isDefined)
      )

  /** The text elements of a paragraph or inline content control, without those of nested paragraphs and
    * controls.
    */
  private def ownTexts(e: Element): List[Element] =
    val texts                     = ListBuffer.empty[Element]
    def collect(c: Element): Unit =
      children(c).foreach { x =>
        if isW(x, "t") then texts += x
        else if !(isW(x, "p") || isW(x, "sdt") || isW(x, "sdtPr") || isW(x, "rPr")) then collect(x)
      }
    collect(e)
    texts.toList

  // Filling

  private type Lookup = WordField => Option[FieldValue]

  private def fillDocument(doc: Document, values: Map[String, FieldValue]): Unit =
    val ids = Iterator.from(
      descendants(doc.getDocumentElement, W, "id")
        .flatMap(attr(_, "val"))
        .flatMap(_.toIntOption)
        .maxOption
        .getOrElse(0) + 1
    )
    fillIn(doc, doc.getDocumentElement, f => values.get(f.key), ids, automaticOnly = false)

  /** Fills the fields below `root`: repeating sections first (each item filled with its row), then the
    * controls (innermost first; an outer control with a value replaces its whole content anyway), then the
    * placeholders. With `automaticOnly` only automatic fields are filled (inside repeating sections, whose
    * manual fields are the columns of their rows).
    */
  private def fillIn(
      doc: Document,
      root: Element,
      lookup: Lookup,
      ids: Iterator[Int],
      automaticOnly: Boolean
  ): Unit =
    val controls = ListBuffer.empty[(Element, FieldValue)]
    val sections = ListBuffer.empty[(Element, List[Map[String, FieldValue]])]
    val groups   = ListBuffer.empty[(List[Element], Boolean)]
    def visit(e: Element, automaticOnly: Boolean): Unit =
      def allowed(f: WordField) = !automaticOnly || f.source == WordFieldSource.Automatic
      val field                 = Option.when(isW(e, "sdt"))(child(e, "sdtPr").flatMap(controlField)).flatten
      if isW(e, "sdt") && repeating(e) && field.exists(_.source == WordFieldSource.Manual) then
        if !automaticOnly then
          field.flatMap(lookup).foreach {
            case FieldValue.Rows(rows) if rows.nonEmpty => sections += (e -> rows)
            case _                                      => ()
          }
        // the items are filled with their rows; inside, only automatic fields get the document's values
        children(e).foreach(visit(_, automaticOnly = true))
      else
        field.filter(allowed).flatMap(lookup).foreach(v => controls += (e -> v))
        if isW(e, "p") || isW(e, "sdt") then
          val own = ownTexts(e)
          if own.nonEmpty then groups += (own -> automaticOnly)
        children(e).foreach(visit(_, automaticOnly))
    visit(root, automaticOnly)
    sections.foreach((section, rows) => fillSection(doc, section, rows, lookup, ids))
    controls.reverse.foreach((sdt, value) => fillControl(doc, sdt, value))
    groups.foreach((texts, automaticOnly) =>
      Placeholders.fill(
        texts,
        name =>
          WordFields
            .parse(name, None)
            .filter(f => !automaticOnly || f.source == WordFieldSource.Automatic)
            .flatMap(lookup)
            .map(_.printed)
      )
    )

  /** Repeats the first item of a repeating section once per row, filled with the row's values. */
  private def fillSection(
      doc: Document,
      section: Element,
      rows: List[Map[String, FieldValue]],
      lookup: Lookup,
      ids: Iterator[Int]
  ): Unit =
    val items = sectionItems(section)
    items.headOption.foreach { prototype =>
      child(section, "sdtPr").foreach { pr =>
        child(pr, "showingPlcHdr").foreach(pr.removeChild)
        child(pr, "dataBinding").foreach(pr.removeChild)
      }
      val parent = prototype.getParentNode
      rows.zipWithIndex.foreach { (row, i) =>
        val copy = prototype.cloneNode(true).asInstanceOf[Element]
        if i > 0 then
          // ids of controls and bookmarks must stay unique in the document
          descendants(copy, W, "id").foreach(_.setAttributeNS(W, "w:val", ids.next().toString))
          (descendants(copy, W, "bookmarkStart") ++ descendants(copy, W, "bookmarkEnd"))
            .foreach(b => b.getParentNode.removeChild(b))
        parent.insertBefore(copy, prototype)
        val rowLookup: Lookup =
          f => if f.source == WordFieldSource.Automatic then lookup(f) else row.get(f.key)
        fillIn(doc, copy, rowLookup, ids, automaticOnly = false)
      }
      items.foreach(parent.removeChild)
    }

  private def fillControl(doc: Document, sdt: Element, value: FieldValue): Unit =
    val pr = child(sdt, "sdtPr")
    pr.foreach { p =>
      child(p, "showingPlcHdr").foreach(p.removeChild)
      child(p, "dataBinding").foreach(p.removeChild)
    }
    val checkbox = pr.flatMap(childNs(_, W14, "checkbox"))
    val date     = pr.flatMap(child(_, "date"))
    (value, checkbox, date) match
      case (FieldValue.Check(checked), Some(box), _) =>
        val state  = childNs(box, W14, if checked then "checkedState" else "uncheckedState")
        val symbol = state
          .flatMap(s => Option(s.getAttributeNS(W14, "val")).filter(_.nonEmpty))
          .flatMap(hex => Try(new String(Character.toChars(Integer.parseInt(hex, 16)))).toOption)
          .getOrElse(if checked then FieldValue.CheckedSymbol else FieldValue.UncheckedSymbol)
        val font = state.flatMap(s => Option(s.getAttributeNS(W14, "font")).filter(_.nonEmpty))
        val flag = childNs(box, W14, "checked").getOrElse {
          val c = doc.createElementNS(W14, "w14:checked")
          box.insertBefore(c, box.getFirstChild)
          c
        }
        flag.setAttributeNS(W14, "w14:val", if checked then "1" else "0")
        val run = fillText(doc, sdt, symbol)
        font.foreach { name =>
          val rpr = child(run, "rPr").getOrElse {
            val r = doc.createElementNS(W, "w:rPr")
            run.insertBefore(r, run.getFirstChild)
            r
          }
          val fonts = child(rpr, "rFonts").getOrElse {
            val f = doc.createElementNS(W, "w:rFonts")
            rpr.insertBefore(f, rpr.getFirstChild)
            f
          }
          List("ascii", "hAnsi", "eastAsia", "cs").foreach(a => fonts.setAttributeNS(W, s"w:$a", name))
        }
      case (FieldValue.Date(d, fallback), _, Some(picker)) =>
        picker.setAttributeNS(W, "w:fullDate", s"${d}T00:00:00Z")
        val pattern = child(picker, "dateFormat").flatMap(attr(_, "val")).filter(_.nonEmpty)
        val locale  = child(picker, "lid").flatMap(attr(_, "val")).map(Locale.forLanguageTag)
        fillText(
          doc,
          sdt,
          pattern.flatMap(formatDate(d, _, locale.getOrElse(Locale.ROOT))).getOrElse(fallback)
        ): Unit
      case (FieldValue.Rows(_), _, _) if repeating(sdt) => ()
      case _                                            =>
        child(sdt, "sdtPr").flatMap(p => child(p, "dropDownList").orElse(child(p, "comboBox"))).foreach {
          list =>
            listItems(list)
              .find(_._1 == value.printed)
              .foreach((_, v) => list.setAttributeNS(W, "w:lastValue", v))
        }
        fillText(doc, sdt, value.printed): Unit

  /** Replaces the text of a control by `value` in the formatting of its first run (without the gray
    * placeholder style); returns that run.
    */
  private def fillText(doc: Document, sdt: Element, value: String): Element =
    val content = child(sdt, "sdtContent").getOrElse {
      val c = doc.createElementNS(W, "w:sdtContent")
      sdt.appendChild(c)
      c
    }
    // Block-level controls: keep the first paragraph, drop the others.
    val paragraphs = children(content).filter(isW(_, "p"))
    paragraphs.drop(1).foreach(content.removeChild)
    val runs  = descendants(content, W, "r")
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
    run

  /** A date in the format of a Word date picker (`dd.MM.yyyy`, `d. MMMM yyyy`, `dddd, d MMMM yyyy`, …). */
  private[app] def formatDate(date: LocalDate, wordPattern: String, locale: Locale): Option[String] =
    Try(DateTimeFormatter.ofPattern(javaPattern(wordPattern), locale).format(date.atStartOfDay())).toOption

  /** Word's date format letters in Java's: `dddd`/`ddd` are week days, `am/pm` the half of the day, text in
    * quotes and other letters stay literal.
    */
  private def javaPattern(word: String): String =
    val out           = StringBuilder()
    val pending       = StringBuilder()
    def flush(): Unit =
      if pending.nonEmpty then
        out.append("'").append(pending.toString.replace("'", "''")).append("'"): Unit
        pending.clear()
    var i = 0
    while i < word.length do
      val c = word.charAt(i)
      if c == '\'' then
        val end  = word.indexOf('\'', i + 1)
        val stop = if end < 0 then word.length else end
        pending.append(word.substring(i + 1, stop))
        i = stop + 1
      else if word.regionMatches(true, i, "am/pm", 0, 5) then
        flush()
        out.append("a")
        i += 5
      else if c.isLetter then
        var j = i
        while j < word.length && word.charAt(j) == c do j += 1
        val n       = j - i
        val pattern = c match
          case 'd'                   => Some(if n >= 4 then "EEEE" else if n == 3 then "EEE" else "d" * n)
          case 'M'                   => Some("M" * n.min(4))
          case 'y' | 'Y'             => Some(if n <= 2 then "yy" else "yyyy")
          case 'H' | 'h' | 'm' | 's' => Some(c.toString * n.min(2))
          case _                     => None
        pattern match
          case Some(p) =>
            flush()
            out.append(p)
          case None => pending.append(word.substring(i, j))
        i = j
      else
        pending.append(c)
        i += 1
    flush()
    out.toString

  private def isW(n: org.w3c.dom.Node, local: String): Boolean = is(n, W, local)

  private def child(e: Element, local: String): Option[Element] = children(e).find(isW(_, local))

  private def childNs(e: Element, ns: String, local: String): Option[Element] =
    children(e).find(is(_, ns, local))

  private def attr(e: Element, local: String): Option[String] =
    Option(e.getAttributeNodeNS(W, local)).map(_.getValue)
