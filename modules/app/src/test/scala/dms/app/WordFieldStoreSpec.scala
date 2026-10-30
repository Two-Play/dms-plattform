package dms.app

import dms.documents.core.DocumentJson
import dms.rendering.core.WordFixtures.*
import dms.templates.{WordField, WordFieldSource, WordFieldType, WordFields}
import zio.test.*

import java.time.LocalDate

/** Field values kept in filled Word documents (DMS-127, ADR 0049): content controls keep their keys, values
  * are written into a custom XML part (with data binding of plain text controls) and custom document
  * properties, and a re-scan of a filled document recovers keys and values.
  */
object WordFieldStoreSpec extends ZIOSpecDefault:

  private val W14 = "http://schemas.microsoft.com/office/word/2010/wordml"

  private def plainControl(tag: String, alias: String, text: String) =
    s"""<w:sdt><w:sdtPr><w:alias w:val="$alias"/><w:tag w:val="$tag"/><w:showingPlcHdr/><w:text/></w:sdtPr>""" +
      s"""<w:sdtContent><w:r><w:rPr><w:rStyle w:val="PlaceholderText"/><w:b/></w:rPr><w:t>$text</w:t></w:r>""" +
      "</w:sdtContent></w:sdt>"

  private def checkbox(tag: String) =
    s"""<w:sdt><w:sdtPr><w:tag w:val="$tag"/><w14:checkbox xmlns:w14="$W14"><w14:checked w14:val="0"/>""" +
      s"""</w14:checkbox></w:sdtPr><w:sdtContent><w:r><w:t>☐</w:t></w:r></w:sdtContent></w:sdt>"""

  private val sample = docx(
    paragraph(textRun("No. ") + plainControl("dms:documentNumber", "Document number", "Number")) +
      paragraph(textRun("Customer: {{customer}}")) +
      paragraph(textRun("Signed ") + control("signingDate", "Signing date", "date")) +
      paragraph(textRun("Notes ") + control("notes", "Notes", "Notes")) +
      paragraph(checkbox("urgent") + textRun(" urgent"))
  )

  private val fields = List(
    WordField("documentNumber", "Document number", WordFieldType.Text, false, WordFieldSource.Automatic),
    WordField("customer", "customer", WordFieldType.Text, true, WordFieldSource.Manual),
    WordField("signingDate", "Signing date", WordFieldType.Date, false, WordFieldSource.Manual),
    WordField("notes", "Notes", WordFieldType.Text, false, WordFieldSource.Manual),
    WordField("urgent", "urgent", WordFieldType.Checkbox, false, WordFieldSource.Manual)
  )

  private val values = Map[String, FieldValue](
    "documentNumber" -> FieldValue.Text("VT-2026-0007"),
    "customer"       -> FieldValue.Text("ACME & Co"),
    "signingDate"    -> FieldValue.Date(LocalDate.of(2026, 10, 29), "29. Oktober 2026"),
    "notes"          -> FieldValue.Text("line 1\nline 2"),
    "urgent"         -> FieldValue.Check(true)
  )

  private def recovered(docx: Array[Byte]): Map[String, String] =
    OfficeFields.pick(fields, WordDocx.recover(docx).toOption.get, DocumentJson.readRows)

  def spec = suite("Field values in filled Word documents (DMS-127)")(
    test("the values are written into a custom XML part and custom properties; keys stay on the controls") {
      val filled     = WordDocx.fillValues(sample, values).toOption.get
      val document   = part(filled, "word/document.xml")
      val item       = part(filled, "customXml/item1.xml")
      val props      = part(filled, "docProps/custom.xml")
      val types      = part(filled, "[Content_Types].xml")
      val rootRels   = part(filled, "_rels/.rels")
      val docRels    = part(filled, "word/_rels/document.xml.rels")
      val itemProps  = part(filled, "customXml/itemProps1.xml")
      val namespaced = s"""xmlns="${WordFieldStore.Namespace}""""
      assertTrue(
        item.contains(namespaced),
        item.contains("<customer>ACME &amp; Co</customer>"),
        item.contains("<documentNumber>VT-2026-0007</documentNumber>"),
        // raw values: ISO dates, true/false
        item.contains("<signingDate>2026-10-29</signingDate>"),
        item.contains("<urgent>true</urgent>"),
        props.contains("""name="dms.documentNumber""""),
        props.contains("VT-2026-0007"),
        props.contains("""name="dms.signingDate""""),
        itemProps.contains(WordFieldStore.StoreItemId),
        itemProps.contains(WordFieldStore.Namespace),
        types.contains("/docProps/custom.xml"),
        types.contains("/customXml/itemProps1.xml"),
        rootRels.contains("docProps/custom.xml"),
        docRels.contains("../customXml/item1.xml"),
        // tag and title stay; the generated text is visible
        document.contains("""<w:tag w:val="dms:documentNumber"/>"""),
        document.contains("""<w:alias w:val="Document number"/>"""),
        document.contains("VT-2026-0007"),
        // plain text controls (also the one the placeholder became) are bound to the part
        document.contains("""w:xpath="/dms:fields[1]/dms:documentNumber[1]""""),
        document.contains("""w:xpath="/dms:fields[1]/dms:customer[1]""""),
        document.contains(s"""w:storeItemID="${WordFieldStore.StoreItemId}""""),
        // rich text controls and date values are not bound
        !document.contains("dms:notes[1]"),
        !document.contains("dms:signingDate[1]"),
        WordDocx.fillValues(sample, values).toOption.get.sameElements(filled)
      )
    },
    test("re-scanning a filled document recovers keys and values (round trip)") {
      val filled = WordDocx.fillValues(sample, values).toOption.get
      assertTrue(
        WordDocx.scan(filled).map(_.map(_.key)) ==
          Right(List("documentNumber", "customer", "signingDate", "notes", "urgent")),
        recovered(filled) == Map(
          "customer" -> "ACME & Co",
          // the control shows the date in the document language; the custom XML part has the ISO date
          "signingDate" -> "2026-10-29",
          "notes"       -> "line 1\nline 2",
          "urgent"      -> WordFields.Checked
        ),
        WordDocx.recover(filled).toOption.get.filledByPlatform,
        // automatic values are recovered as well (informational)
        WordDocx.recover(filled).toOption.get.candidates.get("documentNumber").contains(List("VT-2026-0007"))
      )
    },
    test("values edited in Word win over the stored ones; without controls the stored values are used") {
      val filled = WordDocx.fillValues(sample, values).toOption.get
      val edited = rewrite(filled, "word/document.xml", _.replace(">ACME &amp; Co<", ">ACME GmbH<"))
      val noText = rewrite(filled, "word/document.xml", _.replace(">ACME &amp; Co<", "><"))
      val noXml  = rewrite(noText, "customXml/item1.xml", _.replace("<customer>ACME &amp; Co</customer>", ""))
      assertTrue(
        recovered(edited).get("customer").contains("ACME GmbH"),
        recovered(noText).get("customer").contains("ACME & Co"),
        // the custom property is the last resort
        recovered(noXml).get("customer").contains("ACME & Co")
      )
    },
    test("filling again replaces all values; other custom properties stay") {
      val own =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/custom-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">""" +
          """<property fmtid="{D5CDD505-2E9C-101B-9397-08002B2CF9AE}" pid="2" name="Department"><vt:lpwstr>Legal</vt:lpwstr></property></Properties>"""
      val withOwn = add(sample, "docProps/custom.xml", own)
      val first   = WordDocx.fillValues(withOwn, values).toOption.get
      val second  = WordDocx
        .fillValues(first, Map("customer" -> FieldValue.Text("Other Ltd")))
        .toOption
        .get
      val props = part(second, "docProps/custom.xml")
      val item  = part(second, "customXml/item1.xml")
      assertTrue(
        props.contains("Department"),
        props.contains("Legal"),
        props.contains("Other Ltd"),
        !props.contains("dms.documentNumber"),
        !item.contains("documentNumber"),
        item.contains("<customer>Other Ltd</customer>"),
        // one part, not a second one
        !parts(second).contains("customXml/item2.xml"),
        part(second, "word/document.xml").contains("Other Ltd"),
        "customXml/item1.xml".r.findAllIn(part(second, "word/_rels/document.xml.rels")).size == 1
      )
    },
    test("an unfilled document has no values: placeholder texts and unchecked boxes are not values") {
      val found = WordDocx.recover(sample).toOption.get
      assertTrue(
        !found.filledByPlatform,
        recovered(sample).isEmpty,
        // a control filled by hand in Word is a value
        recovered(
          docx(paragraph(control("customer", "Customer", "x").replace("<w:showingPlcHdr/>", "")))
        ) == Map("customer" -> "x")
      )
    },
    test("rows are stored as JSON and recovered") {
      val rowsField = WordField(
        "positions",
        "Positions",
        WordFieldType.Rows,
        false,
        WordFieldSource.Manual,
        columns = List(WordField("item", "Item", WordFieldType.Text, false, WordFieldSource.Manual))
      )
      val document = docx(
        paragraph(textRun("Positions")) +
          WordFieldTypesSpec.section(
            Some("positions"),
            "<w:tr>" + WordFieldTypesSpec.cell(control("item", "Item", "Item")) + "</w:tr>"
          )
      )
      val filled = WordDocx
        .fillValues(
          document,
          Map(
            "positions" -> FieldValue.Rows(
              List(Map("item" -> FieldValue.Text("Pump")), Map("item" -> FieldValue.Text("Valve")))
            )
          )
        )
        .toOption
        .get
      assertTrue(
        OfficeFields.pick(List(rowsField), WordDocx.recover(filled).toOption.get, DocumentJson.readRows) ==
          Map("positions" -> """[{"item":"Pump"},{"item":"Valve"}]""")
      )
    },
    test("other Office files carry no stored values") {
      assertTrue(OfficeFields.recover("Budget.xlsx", Array.emptyByteArray) == Right(RecoveredValues.none))
    }
  )

  private def rewrite(docx: Array[Byte], name: String, f: String => String): Array[Byte] =
    repack(parts(docx).map((n, b) => n -> (if n == name then f(String(b, "UTF-8")).getBytes("UTF-8") else b)))

  private def add(docx: Array[Byte], name: String, text: String): Array[Byte] =
    val withPart = parts(docx) + (name -> text.getBytes("UTF-8"))
    val rels     = String(withPart("_rels/.rels"), "UTF-8").replace(
      "</Relationships>",
      """<Relationship Id="rId9" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/custom-properties" Target="docProps/custom.xml"/></Relationships>"""
    )
    repack(withPart + ("_rels/.rels" -> rels.getBytes("UTF-8")))

  private def repack(entries: Map[String, Array[Byte]]): Array[Byte] =
    val out = java.io.ByteArrayOutputStream()
    val zip = java.util.zip.ZipOutputStream(out)
    try
      entries.toList.sortBy(_._1).foreach { (name, data) =>
        zip.putNextEntry(java.util.zip.ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
      }
    finally zip.close()
    out.toByteArray
