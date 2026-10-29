package dms.app

import dms.rendering.core.WordFixtures.*
import dms.templates.{WordField, WordFieldSource, WordFieldType, WordFields}
import zio.test.*

/** Fields of Word documents (DMS-115): content controls and `{{key}}` placeholders are found with their
  * sources and filled without touching the formatting or the other parts of the package.
  */
object WordDocxSpec extends ZIOSpecDefault:

  private val sample = docx(
    paragraph(textRun("Contract ") + control("dms:documentNumber", "Document number", "Click here")) +
      paragraph(textRun("Customer: {{customer}}, signed {{ signingDate }}")) +
      paragraph(control("Customer", "Kunde", "Name of the customer", "<w:i/>")),
    header = Some(paragraph(textRun("Valid from ") + control("dms:validFrom", "Valid from", "date")))
  )

  def spec = suite("Word fields")(
    test("content controls and placeholders are found in document order, automatic with the dms: prefix") {
      val fields = WordDocx.scan(sample)
      assertTrue(
        fields.map(_.map(f => (f.key, f.label, f.source, f.fieldType))) == Right(
          List(
            ("documentNumber", "Document number", WordFieldSource.Automatic, WordFieldType.Text),
            ("customer", "customer", WordFieldSource.Manual, WordFieldType.Text),
            ("signingDate", "signingDate", WordFieldSource.Manual, WordFieldType.Date),
            ("Customer", "Kunde", WordFieldSource.Manual, WordFieldType.Text),
            ("validFrom", "Valid from", WordFieldSource.Automatic, WordFieldType.Text)
          )
        )
      )
    },
    test("filling replaces the text, keeps the run formatting and leaves fields without value alone") {
      val filled = WordDocx.fill(
        sample,
        Map("documentNumber" -> "VT-2026-0007", "customer" -> "ACME & Co", "validFrom" -> "01.01.2027")
      )
      val document = filled.map(part(_, "word/document.xml")).getOrElse("")
      val header   = filled.map(part(_, "word/header1.xml")).getOrElse("")
      assertTrue(
        filled.isRight,
        document.contains("VT-2026-0007"),
        !document.contains("Click here"),
        // the bold run formatting stays, the gray placeholder style and the placeholder flag go
        document.contains("<w:b/>"),
        "showingPlcHdr".r.findAllIn(document).size == 1,
        "PlaceholderText".r.findAllIn(document).size == 1,
        document.contains("Customer: ACME &amp; Co, signed {{ signingDate }}"),
        // no value: the control keeps its placeholder text
        document.contains("Name of the customer"),
        header.contains("01.01.2027"),
        // the controls stay, so the document can be filled again
        WordDocx.scan(filled.toOption.get).map(_.map(_.key)) ==
          Right(List("documentNumber", "signingDate", "Customer", "validFrom"))
      )
    },
    test("only the field parts change; filling is deterministic") {
      val values = Map("documentNumber" -> "1")
      val first  = WordDocx.fill(sample, values).toOption.get
      val second = WordDocx.fill(sample, values).toOption.get
      assertTrue(
        first.sameElements(second),
        parts(first)("[Content_Types].xml").sameElements(parts(sample)("[Content_Types].xml")),
        parts(first).keySet == parts(sample).keySet
      )
    },
    test("multi-line values become line breaks; block-level controls keep one paragraph") {
      val block =
        docx(
          """<w:sdt><w:sdtPr><w:tag w:val="notes"/></w:sdtPr><w:sdtContent>""" +
            paragraph(textRun("first")) + paragraph(textRun("second")) + "</w:sdtContent></w:sdt>"
        )
      val document = WordDocx.fill(block, Map("notes" -> "line 1\nline 2")).map(part(_, "word/document.xml"))
      assertTrue(
        document.exists(d => d.contains("line 1") && d.contains("<w:br/>") && d.contains("line 2")),
        document.exists(d => !d.contains("second") && !d.contains("first"))
      )
    },
    test("files that are not Word documents and documents with a DTD are refused") {
      val evil =
        val p     = parts(docx(""))
        val bytes = new java.io.ByteArrayOutputStream()
        val zip   = java.util.zip.ZipOutputStream(bytes)
        p.foreach { (name, data) =>
          zip.putNextEntry(java.util.zip.ZipEntry(name))
          zip.write(
            if name == "word/document.xml" then
              """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><x>&e;</x>"""
                .getBytes("UTF-8")
            else data
          )
          zip.closeEntry()
        }
        zip.close()
        bytes.toByteArray
      assertTrue(
        WordDocx.scan("not a zip".getBytes("UTF-8")).isLeft,
        WordDocx.scan(evil).isLeft,
        WordDocx.fill(evil, Map.empty).isLeft
      )
    },
    test("definitions of known keys are kept; manual values are checked") {
      val previous =
        List(WordField("customer", "Kunde", WordFieldType.Text, required = true, WordFieldSource.Manual))
      val found  = WordDocx.scan(sample).toOption.get
      val merged = WordFields.merge(found, previous)
      assertTrue(
        merged.find(_.key == "customer").contains(previous.head),
        WordFields.check(merged, Map.empty, complete = true) == List("Kunde: required"),
        WordFields.check(merged, Map("signingDate" -> "31.12.2026"), complete = false) ==
          List("signingDate: a date (yyyy-MM-dd)"),
        WordFields.check(merged, Map("documentNumber" -> "x"), complete = false) ==
          List("documentNumber: not a manual field")
      )
    }
  )
