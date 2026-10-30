package dms.app

import dms.templates.{WordField, WordFields}
import org.w3c.dom.Element

import java.util.Locale

/** Fields of the Office documents a file document can carry (DMS-115, DMS-122, ADR 0042): Word documents
  * (DOCX, [[WordDocx]]: content controls, repeating sections and placeholders), Excel workbooks (XLSX) and
  * PowerPoint presentations (PPTX) with `{{key}}` / `{{dms:key}}` placeholders. The same field definitions,
  * values, form and filling pipeline serve all three; the filled file is converted into PDF/A by the
  * conversion worker like every original.
  *
  *   - **XLSX**: placeholders in the shared strings (`xl/sharedStrings.xml`, rich text runs included) and in
  *     inline strings of the sheets. A cell stays a text cell; a whole-cell placeholder filled with a number
  *     prints the number as text. Formulas, sheet headers and footers, charts and comments are not filled.
  *   - **PPTX**: placeholders in the text of the slides (`ppt/slides/slideN.xml`: shapes, tables), also when
  *     split into several runs. Line breaks of values become spaces. Layouts, masters and notes are not
  *     filled.
  */
object OfficeFields:

  /** The Office formats whose fields are filled. */
  enum Format:
    case Docx, Xlsx, Pptx

  private val Spreadsheet = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
  private val Drawing     = "http://schemas.openxmlformats.org/drawingml/2006/main"

  private val SheetParts = "xl/(sharedStrings|worksheets/sheet\\d+)\\.xml".r
  private val SlideParts = "ppt/slides/slide\\d+\\.xml".r

  /** The format of a file by its name, if its fields are filled. */
  def format(name: String): Option[Format] =
    val n = name.toLowerCase(Locale.ROOT)
    if n.endsWith(".docx") then Some(Format.Docx)
    else if n.endsWith(".xlsx") then Some(Format.Xlsx)
    else if n.endsWith(".pptx") then Some(Format.Pptx)
    else None

  /** Whether the fields of a file named `name` are read and filled. */
  def fillable(name: String): Boolean = format(name).isDefined

  /** The fields of a file in document order, each key once; none for files whose fields are not filled. */
  def scan(name: String, bytes: Array[Byte]): Either[String, List[WordField]] =
    format(name) match
      case None              => Right(Nil)
      case Some(Format.Docx) => WordDocx.scan(bytes)
      case Some(Format.Xlsx) => placeholders(bytes, Workbook)
      case Some(Format.Pptx) => placeholders(bytes, Presentation)

  /** The file with `values` filled into its fields; files whose fields are not filled unchanged. */
  def fill(name: String, bytes: Array[Byte], values: Map[String, FieldValue]): Either[String, Array[Byte]] =
    format(name) match
      case None              => Right(bytes)
      case Some(Format.Docx) => WordDocx.fillValues(bytes, values)
      case Some(Format.Xlsx) => fillPlaceholders(bytes, values, Workbook)
      case Some(Format.Pptx) => fillPlaceholders(bytes, values, Presentation)

  /** The values a file carries for its fields (DMS-127): for Word documents from their content controls, the
    * custom XML part and the custom document properties the platform writes when filling
    * ([[WordDocx.recover]]); none for other files (their placeholders are replaced when filled).
    */
  def recover(name: String, bytes: Array[Byte]): Either[String, RecoveredValues] =
    format(name) match
      case Some(Format.Docx) => WordDocx.recover(bytes)
      case _                 => Right(RecoveredValues.none)

  /** For each manual field, the first recovered candidate that is a valid value of the field (DMS-127). */
  def pick(
      fields: List[WordField],
      recovered: RecoveredValues,
      readRows: String => Either[String, WordFields.Rows]
  ): Map[String, String] =
    fields
      .filter(_.source == dms.templates.WordFieldSource.Manual)
      .flatMap(f =>
        recovered.candidates
          .getOrElse(f.key, Nil)
          .find(v => WordFields.check(fields, Map(f.key -> v), complete = false, readRows).isEmpty)
          .map(f.key -> _)
      )
      .toMap

  /** Where the placeholders of a format are. */
  private final case class Layout(
      kind: String,
      mainPart: String,
      parts: String => Boolean,
      ns: String,
      paragraph: Element => Boolean,
      skip: Element => Boolean,
      preserveSpace: Boolean,
      text: String => String
  )

  private val Workbook = Layout(
    "Excel workbook (XLSX)",
    "xl/workbook.xml",
    SheetParts.matches,
    Spreadsheet,
    // a shared string item or the inline string of a cell
    e => OfficePackage.is(e, Spreadsheet, "si") || OfficePackage.is(e, Spreadsheet, "is"),
    // phonetic runs repeat the text in another script
    e => OfficePackage.is(e, Spreadsheet, "rPh") || OfficePackage.is(e, Spreadsheet, "f"),
    preserveSpace = true,
    identity
  )

  private val Presentation = Layout(
    "PowerPoint presentation (PPTX)",
    "ppt/presentation.xml",
    SlideParts.matches,
    Drawing,
    OfficePackage.is(_, Drawing, "p"),
    _ => false,
    preserveSpace = false,
    _.replaceAll("\\s*\\n\\s*", " ")
  )

  private def placeholders(bytes: Array[Byte], layout: Layout): Either[String, List[WordField]] =
    for
      entries <- OfficePackage.unzip(bytes, layout.mainPart, layout.kind)
      parts   <- OfficePackage.parse(entries, layout.parts, layout.kind)
    yield parts
      .sortBy((name, _) => order(name))
      .flatMap((_, doc) => groups(doc.getDocumentElement, layout))
      .flatMap(Placeholders.names)
      .flatMap(WordFields.parse(_, None))
      .distinctBy(_.key)

  private def fillPlaceholders(
      bytes: Array[Byte],
      values: Map[String, FieldValue],
      layout: Layout
  ): Either[String, Array[Byte]] =
    for
      entries <- OfficePackage.unzip(bytes, layout.mainPart, layout.kind)
      parts   <- OfficePackage.parse(entries, layout.parts, layout.kind)
      _       <- OfficePackage.attempt(parts.foreach { (_, doc) =>
        groups(doc.getDocumentElement, layout).foreach(texts =>
          Placeholders.fill(
            texts,
            name =>
              WordFields.parse(name, None).flatMap(f => values.get(f.key)).map(v => layout.text(v.printed)),
            layout.preserveSpace
          )
        )
      })
      bytes <- OfficePackage.write(entries, parts)
    yield bytes

  private def groups(root: Element, layout: Layout): List[List[Element]] =
    Placeholders.paragraphs(root, layout.ns, "t", layout.paragraph, layout.skip)

  /** Parts in a natural order: shared strings first, then sheets or slides by number. */
  private def order(name: String): (Int, Int) =
    val number = "\\d+".r.findAllIn(name).toList.lastOption.flatMap(_.toIntOption).getOrElse(0)
    (if name.contains("sharedStrings") then 0 else 1, number)
