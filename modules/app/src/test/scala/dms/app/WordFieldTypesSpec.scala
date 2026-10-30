package dms.app

import dms.documents.core.DocumentJson
import dms.rendering.core.WordFixtures.*
import dms.templates.{WordField, WordFieldSource, WordFieldType, WordFields}
import zio.test.*

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate
import java.util.Locale
import java.util.zip.{ZipEntry, ZipOutputStream}

/** Typed Word fields (DMS-122): check boxes, date pickers, drop-down lists, repeating sections, placeholders
  * split over several runs, and placeholders in Excel and PowerPoint files – found by the scan, filled with
  * the formatting kept, deterministically.
  */
object WordFieldTypesSpec extends ZIOSpecDefault:

  private val W14 = "http://schemas.microsoft.com/office/word/2010/wordml"
  private val W15 = "http://schemas.microsoft.com/office/word/2012/wordml"

  private[app] def checkbox(tag: String, checked: Boolean) =
    s"""<w:sdt><w:sdtPr><w:alias w:val="Agreed"/><w:tag w:val="$tag"/><w:id w:val="1"/>""" +
      s"""<w14:checkbox xmlns:w14="$W14"><w14:checked w14:val="${if checked then 1 else 0}"/>""" +
      """<w14:checkedState w14:val="2612" w14:font="MS Gothic"/><w14:uncheckedState w14:val="2610" w14:font="MS Gothic"/>""" +
      """</w14:checkbox></w:sdtPr><w:sdtContent><w:r><w:rPr><w:rFonts w:ascii="MS Gothic" w:hAnsi="MS Gothic"/>""" +
      s"""<w:sz w:val="28"/></w:rPr><w:t>${if checked then "☒" else "☐"}</w:t></w:r></w:sdtContent></w:sdt>"""

  private[app] def datePicker(tag: String, format: Option[String], lid: String = "de-DE") =
    s"""<w:sdt><w:sdtPr><w:alias w:val="Start"/><w:tag w:val="$tag"/><w:id w:val="2"/><w:showingPlcHdr/>""" +
      s"""<w:date>${format.fold("")(f => s"""<w:dateFormat w:val="$f"/>""")}<w:lid w:val="$lid"/>""" +
      """<w:storeMappedDataAs w:val="dateTime"/><w:calendar w:val="gregorian"/></w:date></w:sdtPr>""" +
      """<w:sdtContent><w:r><w:rPr><w:rStyle w:val="PlaceholderText"/><w:i/></w:rPr>""" +
      """<w:t>Datum wählen</w:t></w:r></w:sdtContent></w:sdt>"""

  private[app] def dropDown(tag: String, element: String = "dropDownList") =
    s"""<w:sdt><w:sdtPr><w:alias w:val="Priority"/><w:tag w:val="$tag"/><w:id w:val="3"/><w:showingPlcHdr/>""" +
      s"""<w:$element><w:listItem w:displayText="Element auswählen." w:value=""/>""" +
      """<w:listItem w:displayText="High" w:value="H"/><w:listItem w:displayText="Low" w:value="L"/>""" +
      s"""</w:$element></w:sdtPr><w:sdtContent><w:r><w:rPr><w:rStyle w:val="PlaceholderText"/><w:u w:val="single"/></w:rPr>""" +
      """<w:t>Element auswählen.</w:t></w:r></w:sdtContent></w:sdt>"""

  private[app] def cell(content: String) = s"<w:tc>${paragraph(content)}</w:tc>"

  /** A repeating section around table rows, with the given content of its item. */
  private[app] def section(tag: Option[String], item: String) =
    s"""<w:tbl><w:tblPr/><w:sdt><w:sdtPr><w:alias w:val="Positions"/>${tag.fold("")(t =>
        s"""<w:tag w:val="$t"/>"""
      )}""" +
      s"""<w:id w:val="10"/><w15:repeatingSection xmlns:w15="$W15"/></w:sdtPr><w:sdtContent>""" +
      s"""<w:sdt><w:sdtPr><w:id w:val="11"/><w15:repeatingSectionItem xmlns:w15="$W15"/></w:sdtPr><w:sdtContent>""" +
      s"""$item</w:sdtContent></w:sdt></w:sdtContent></w:sdt></w:tbl>"""

  private[app] val positionRow =
    "<w:tr>" + cell(control("item", "Item", "Item")) + cell(control("quantity", "Quantity", "0")) +
      cell(textRun("Due {{dueDate}}")) + cell(control("dms:documentNumber", "No.", "n/a")) + "</w:tr>"

  private def zip(parts: List[(String, String)]): Array[Byte] =
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

  private val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
  private val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
  private val P = "http://schemas.openxmlformats.org/presentationml/2006/main"

  /** A minimal workbook with one shared string. */
  private[app] def workbook(text: String): Array[Byte] = zip(
    List(
      "[Content_Types].xml" -> """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
      "xl/workbook.xml"      -> s"""<workbook xmlns="$S"><sheets/></workbook>""",
      "xl/sharedStrings.xml" -> s"""<sst xmlns="$S" count="1" uniqueCount="1"><si><t>$text</t></si></sst>"""
    )
  )

  private[app] val xlsx = zip(
    List(
      "[Content_Types].xml" -> """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
      "xl/workbook.xml" -> s"""<workbook xmlns="$S"><sheets/></workbook>""",
      "xl/sharedStrings.xml" -> (s"""<sst xmlns="$S" count="3" uniqueCount="3"><si><t>Customer: {{customer}}</t></si>""" +
        """<si><r><rPr><b/></rPr><t>{{ dms:document</t></r><r><t>Number}} – fixed</t></r></si>""" +
        """<si><t>{{unknown}}</t><rPh><t>{{phonetic}}</t></rPh></si></sst>"""),
      "xl/worksheets/sheet1.xml" -> (s"""<worksheet xmlns="$S"><sheetData><row r="1">""" +
        """<c r="A1" t="s"><v>0</v></c><c r="B1" t="inlineStr"><is><t>Start {{startDate}}</t></is></c>""" +
        """<c r="C1"><f>CONCAT("{{x}}")</f><v>0</v></c></row></sheetData></worksheet>""")
    )
  )

  private val pptx = zip(
    List(
      "[Content_Types].xml" -> """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
      "ppt/presentation.xml"  -> s"""<p:presentation xmlns:p="$P"/>""",
      "ppt/slides/slide1.xml" -> (s"""<p:sld xmlns:p="$P" xmlns:a="$A"><p:cSld><p:spTree><p:sp><p:txBody>""" +
        """<a:p><a:r><a:rPr lang="de-DE" b="1"/><a:t>Project {{pro</a:t></a:r><a:r><a:rPr lang="de-DE"/><a:t>ject}}</a:t></a:r></a:p>""" +
        """<a:p><a:r><a:t>Owner: {{dms:owner}}</a:t></a:r></a:p>""" +
        """</p:txBody></p:sp></p:spTree></p:cSld></p:sld>""")
    )
  )

  def spec = suite("Typed Word fields and other Office files (DMS-122)")(
    test(
      "a check box control is a Checkbox field; filling sets its state and symbol and keeps the formatting"
    ) {
      val document = docx(paragraph(checkbox("agreed", checked = false)))
      val checked  = WordDocx.fillValues(document, Map("agreed" -> FieldValue.Check(true))).toOption.get
      val cleared  = WordDocx.fillValues(document, Map("agreed" -> FieldValue.Check(false))).toOption.get
      val xml      = part(checked, "word/document.xml")
      val unset    = part(cleared, "word/document.xml")
      assertTrue(
        WordDocx.scan(document).map(_.map(f => (f.key, f.fieldType))) == Right(
          List("agreed" -> WordFieldType.Checkbox)
        ),
        xml.contains("""w14:checked w14:val="1""""),
        xml.contains("☒"),
        !xml.contains("☐</w:t>"),
        xml.contains("""<w:sz w:val="28"/>"""),
        xml.contains("""w:ascii="MS Gothic""""),
        unset.contains("""w14:checked w14:val="0""""),
        unset.contains("☐"),
        WordDocx
          .fillValues(document, Map("agreed" -> FieldValue.Check(true)))
          .toOption
          .get
          .sameElements(checked),
        // as a placeholder, a check box prints its symbol
        FieldValue.Check(true).printed == "☒"
      )
    },
    test("a date picker is a Date field filled in its own format and language and stored as ISO date") {
      val document = docx(
        paragraph(datePicker("startDate", Some("dddd, d. MMMM yyyy"))) +
          paragraph(datePicker("endDate", None)) +
          paragraph(datePicker("usDate", Some("M/d/yy"), "en-US"))
      )
      val date   = LocalDate.of(2027, 3, 1)
      val filled = WordDocx
        .fillValues(
          document,
          Map(
            "startDate" -> FieldValue.Date(date, "01.03.2027"),
            "endDate"   -> FieldValue.Date(date, "01.03.2027"),
            "usDate"    -> FieldValue.Date(date, "01.03.2027")
          )
        )
        .toOption
        .get
      val xml = part(filled, "word/document.xml")
      assertTrue(
        WordDocx.scan(document).map(_.map(_.fieldType)) ==
          Right(List(WordFieldType.Date, WordFieldType.Date, WordFieldType.Date)),
        xml.contains("Montag, 1. März 2027"),
        // without a format of its own: the date in the document language
        xml.contains(">01.03.2027<"),
        xml.contains(">3/1/27<"),
        xml.contains("""w:fullDate="2027-03-01T00:00:00Z""""),
        !xml.contains("showingPlcHdr"),
        !xml.contains("PlaceholderText"),
        xml.contains("<w:i/>"),
        WordDocx.formatDate(date, "dd.MM.yyyy 'Uhr' HH:mm", Locale.GERMANY).contains("01.03.2027 Uhr 00:00")
      )
    },
    test("drop-down lists and combo boxes are Choice fields whose options are the items") {
      val document = docx(paragraph(dropDown("priority")) + paragraph(dropDown("level", "comboBox")))
      val filled   = WordDocx.fillValues(document, Map("priority" -> FieldValue.Text("Low"))).toOption.get
      val xml      = part(filled, "word/document.xml")
      assertTrue(
        WordDocx.scan(document).map(_.map(f => (f.key, f.fieldType, f.options))) == Right(
          List(
            ("priority", WordFieldType.Choice, List("High", "Low")),
            ("level", WordFieldType.Choice, List("High", "Low"))
          )
        ),
        xml.contains(">Low</w:t>"),
        xml.contains("""w:lastValue="L""""),
        xml.contains("""<w:u w:val="single"/>"""),
        // the other control keeps its placeholder
        xml.contains("Element auswählen.</w:t>")
      )
    },
    test("a repeating section is a Rows field; filling repeats its item per row with unique ids") {
      val document = docx(paragraph(textRun("Positions")) + section(Some("positions"), positionRow))
      val fields   = WordDocx.scan(document).toOption.get
      val rows     = FieldValue.Rows(
        List(
          Map("item" -> FieldValue.Text("Pump"), "quantity" -> FieldValue.Text("2")),
          Map(
            "item"    -> FieldValue.Text("Valve"),
            "dueDate" -> FieldValue.Date(LocalDate.of(2027, 1, 2), "02.01.2027")
          ),
          Map("item" -> FieldValue.Text("Seal & ring"))
        )
      )
      val values = Map[String, FieldValue]("positions" -> rows, "documentNumber" -> FieldValue.Text("VT-7"))
      val filled = WordDocx.fillValues(document, values).toOption.get
      val xml    = part(filled, "word/document.xml")
      val ids    = "<w:id w:val=\"(\\d+)\"/>".r.findAllMatchIn(xml).map(_.group(1)).toList
      assertTrue(
        fields.map(f => (f.key, f.fieldType, f.source)) == List(
          ("positions", WordFieldType.Rows, WordFieldSource.Manual),
          ("documentNumber", WordFieldType.Text, WordFieldSource.Automatic)
        ),
        fields.head.columns.map(c => (c.key, c.fieldType)) == List(
          "item"     -> WordFieldType.Text,
          "quantity" -> WordFieldType.Text,
          "dueDate"  -> WordFieldType.Date
        ),
        fields.head.label == "Positions",
        fields.head.warning.isEmpty,
        "<w:tr>".r.findAllIn(xml).size == 3,
        xml.contains(">Pump<"),
        xml.contains(">Valve<"),
        xml.contains("Seal &amp; ring"),
        WordDocx.text(filled).exists(_.contains("Due 02.01.2027")),
        // a row without a value keeps the item's placeholder text
        "Due \\{\\{dueDate\\}\\}".r.findAllIn(xml).size == 2,
        // automatic fields inside are filled in every row
        "VT-7".r.findAllIn(xml).size == 3,
        ids.distinct.size == ids.size,
        WordDocx.fillValues(document, values).toOption.get.sameElements(filled),
        // no rows: the section stays as it is
        part(
          WordDocx.fillValues(document, Map("positions" -> FieldValue.Rows(Nil))).toOption.get,
          "word/document.xml"
        )
          .contains(">Item<"),
        // the filled document can be scanned again
        WordDocx.scan(filled).map(_.map(_.key)) == Right(List("positions", "documentNumber"))
      )
    },
    test("unsupported repeating sections are reported in the scan instead of failing silently") {
      val nested = section(
        Some("outer"),
        "<w:tr>" + cell(control("name", "Name", "x")) + "</w:tr>" + section(Some("inner"), positionRow)
      )
      val untagged = section(None, "<w:tr>" + cell(control("lonely", "Lonely", "x")) + "</w:tr>")
      val empty    =
        s"""<w:sdt><w:sdtPr><w:tag w:val="nothing"/><w15:repeatingSection xmlns:w15="$W15"/></w:sdtPr><w:sdtContent>""" +
          paragraph(textRun("no item")) + "</w:sdtContent></w:sdt>"
      val fields = WordDocx.scan(docx(nested + untagged + empty)).toOption.get
      val byKey  = fields.map(f => f.key -> f).toMap
      assertTrue(
        byKey("outer").fieldType == WordFieldType.Rows,
        byKey("outer").columns.map(_.key) == List("name"),
        byKey("outer").warning.exists(_.contains("nested repeating sections are not supported")),
        byKey("outer").warning.exists(_.contains("inner")),
        byKey("lonely").fieldType == WordFieldType.Text,
        byKey("lonely").warning.exists(_.contains("without a tag")),
        byKey("nothing").warning.exists(_.contains("no item"))
      )
    },
    test("placeholders split over several runs are found and filled in the first run's formatting") {
      val document = docx(
        paragraph(
          """<w:r><w:rPr><w:b/></w:rPr><w:t xml:space="preserve">Dear {{cus</w:t></w:r>""" +
            """<w:proofErr w:type="spellStart"/><w:r><w:rPr><w:i/></w:rPr><w:t>tom</w:t></w:r>""" +
            """<w:r><w:rPr><w:u w:val="single"/></w:rPr><w:t xml:space="preserve">er}}, see {{other}}</w:t></w:r>"""
        )
      )
      val filled = WordDocx.fill(document, Map("customer" -> "ACME")).toOption.get
      val xml    = part(filled, "word/document.xml")
      assertTrue(
        WordDocx.scan(document).map(_.map(_.key)) == Right(List("customer", "other")),
        xml.contains("""<w:b/></w:rPr><w:t xml:space="preserve">Dear </w:t>"""),
        // the placeholder became a control in the formatting of the run where it started (DMS-127)
        xml.contains("""<w:tag w:val="customer"/>"""),
        xml.contains("""<w:sdtContent><w:r><w:rPr><w:b/></w:rPr><w:t xml:space="preserve">ACME</w:t>"""),
        xml.contains("""<w:u w:val="single"/></w:rPr><w:t xml:space="preserve">, see {{other}}</w:t>"""),
        !xml.contains(">tom<"),
        WordDocx.paragraphs(filled) == Right(List(WordParagraph("Dear ACME, see {{other}}"))),
        WordDocx.fill(document, Map("customer" -> "ACME")).toOption.get.sameElements(filled),
        // without a value the runs stay as they are
        part(WordDocx.fill(document, Map.empty).toOption.get, "word/document.xml").contains(">tom<")
      )
    },
    test("Excel: placeholders in shared strings (also split over rich text runs) and inline strings") {
      val fields = OfficeFields.scan("Budget.xlsx", xlsx)
      val filled = OfficeFields
        .fill(
          "Budget.xlsx",
          xlsx,
          Map(
            "customer"       -> FieldValue.Text("ACME"),
            "documentNumber" -> FieldValue.Text("VT-7"),
            "startDate"      -> FieldValue.Date(LocalDate.of(2027, 3, 1), "01.03.2027"),
            "x"              -> FieldValue.Text("formula")
          )
        )
        .toOption
        .get
      val strings = part(filled, "xl/sharedStrings.xml")
      val sheet   = part(filled, "xl/worksheets/sheet1.xml")
      assertTrue(
        fields.map(_.map(f => (f.key, f.source))) == Right(
          List(
            "customer"       -> WordFieldSource.Manual,
            "documentNumber" -> WordFieldSource.Automatic,
            "unknown"        -> WordFieldSource.Manual,
            "startDate"      -> WordFieldSource.Manual
          )
        ),
        strings.contains("Customer: ACME"),
        strings.contains("""<b/></rPr><t xml:space="preserve">VT-7</t>"""),
        strings.contains("""<t xml:space="preserve"> – fixed</t>"""),
        strings.contains("{{phonetic}}"),
        sheet.contains("Start 01.03.2027"),
        sheet.contains("{{x}}"),
        OfficeFields.fill("Budget.xlsx", xlsx, Map("customer" -> FieldValue.Text("ACME"))).isRight,
        OfficeFields.scan("Budget.xlsx", docx(paragraph(""))).isLeft
      )
    },
    test("PowerPoint: placeholders in the slides' text, also split over runs; line breaks become spaces") {
      val filled = OfficeFields
        .fill(
          "Kick-off.pptx",
          pptx,
          Map("project" -> FieldValue.Text("Atlas\nphase 2"), "owner" -> FieldValue.Text("Ada"))
        )
        .toOption
        .get
      val slide = part(filled, "ppt/slides/slide1.xml")
      assertTrue(
        OfficeFields.scan("Kick-off.pptx", pptx).map(_.map(_.key)) == Right(List("project", "owner")),
        slide.contains("""<a:rPr b="1" lang="de-DE"/><a:t>Project Atlas phase 2</a:t>""") ||
          slide.contains("""<a:rPr lang="de-DE" b="1"/><a:t>Project Atlas phase 2</a:t>"""),
        slide.contains("<a:t/>") || slide.contains("<a:t></a:t>"),
        slide.contains("Owner: Ada"),
        !slide.contains("xml:space"),
        OfficeFields
          .fill("Kick-off.pptx", pptx, Map("project" -> FieldValue.Text("Atlas")))
          .toOption
          .get
          .sameElements(
            OfficeFields.fill("Kick-off.pptx", pptx, Map("project" -> FieldValue.Text("Atlas"))).toOption.get
          ),
        OfficeFields.fill("notes.txt", "x".getBytes(UTF_8), Map.empty).map(_.toList) == Right(
          "x".getBytes(UTF_8).toList
        )
      )
    },
    test("values of the new types are checked; rows are stored canonically") {
      val rowsField = WordField(
        "positions",
        "Positions",
        WordFieldType.Rows,
        required = true,
        WordFieldSource.Manual,
        columns = List(
          WordField("item", "Item", WordFieldType.Text, required = true, WordFieldSource.Manual),
          WordField("due", "Due", WordFieldType.Date, required = false, WordFieldSource.Manual),
          WordField("ok", "OK", WordFieldType.Checkbox, required = false, WordFieldSource.Manual)
        )
      )
      val fields = List(
        WordField("agreed", "Agreed", WordFieldType.Checkbox, required = false, WordFieldSource.Manual),
        WordField(
          "priority",
          "Priority",
          WordFieldType.Choice,
          required = false,
          WordFieldSource.Manual,
          List("High", "Low")
        ),
        rowsField
      )
      def check(values: Map[String, String], complete: Boolean = false) =
        WordFields.check(fields, values, complete, DocumentJson.readRows)
      val canonical = DocumentJson.writeRows(
        rowsField,
        List(
          Map("ok"   -> "true", "item"       -> " Pump "),
          Map("item" -> "  "),
          Map("due"  -> "2027-01-02", "item" -> "Seal")
        )
      )
      assertTrue(
        check(Map("agreed" -> "true", "priority" -> "Low", "positions" -> """[{"item":"Pump"}]""")).isEmpty,
        check(Map("agreed" -> "yes")) == List("Agreed: true or false"),
        check(Map("priority" -> "Medium")) == List("Priority: one of High, Low"),
        check(Map("positions" -> "not json")).head.startsWith("Positions: a list of rows"),
        check(Map("positions" -> """[{"item":"Pump","due":"tomorrow","colour":"red"}]""")) ==
          List("Positions, row 1: colour is not a column", "Positions, row 1: Due: a date (yyyy-MM-dd)"),
        check(Map("positions" -> """[{"due":"2027-01-02"}]"""), complete = true) == List(
          "Positions, row 1: Item: required"
        ),
        check(Map.empty, complete = true) == List("Positions: required"),
        canonical.contains("""[{"item":"Pump","ok":"true"},{"item":"Seal","due":"2027-01-02"}]"""),
        DocumentJson.writeRows(rowsField, List(Map("item" -> ""))).isEmpty
      )
    },
    test("definitions: the file decides typed controls, options and columns; designers edit the rest") {
      val found = WordDocx
        .scan(docx(paragraph(dropDown("priority")) + section(Some("positions"), positionRow)))
        .toOption
        .get
      val previous = List(
        WordField("priority", "Priorität", WordFieldType.Text, required = true, WordFieldSource.Manual),
        WordField(
          "positions",
          "Positionen",
          WordFieldType.Rows,
          required = true,
          WordFieldSource.Manual,
          columns =
            List(WordField("item", "Artikel", WordFieldType.Text, required = true, WordFieldSource.Manual))
        )
      )
      val merged = WordFields.merge(found, previous)
      val rows   = merged.find(_.key == "positions").get
      val edited = rows.copy(columns =
        rows.columns.map(c => if c.key == "quantity" then c.copy(fieldType = WordFieldType.Number) else c)
      )
      val choice    = merged.find(_.key == "priority").get
      val notChoice = WordField("note", "Note", WordFieldType.Text, required = false, WordFieldSource.Manual)
      assertTrue(
        choice.label == "Priorität",
        choice.required,
        choice.fieldType == WordFieldType.Choice,
        choice.options == List("High", "Low"),
        rows.label == "Positionen",
        rows.columns.map(c => (c.key, c.label)) == List(
          "item"     -> "Artikel",
          "quantity" -> "Quantity",
          "dueDate"  -> "dueDate"
        ),
        WordFields.edit(rows, edited).map(_.columns.map(_.fieldType)) ==
          Right(List(WordFieldType.Text, WordFieldType.Number, WordFieldType.Date)),
        WordFields.edit(rows, rows.copy(fieldType = WordFieldType.Text)).isLeft,
        WordFields.edit(rows, edited.copy(columns = edited.columns.drop(1))).isLeft,
        WordFields.edit(notChoice, notChoice.copy(fieldType = WordFieldType.Rows)).isLeft,
        WordFields.edit(notChoice, notChoice.copy(fieldType = WordFieldType.Choice)).isLeft,
        WordFields
          .edit(notChoice, notChoice.copy(fieldType = WordFieldType.Choice, options = List(" A ", "B", "A")))
          .map(_.options) ==
          Right(List("A", "B"))
      )
    }
  )
