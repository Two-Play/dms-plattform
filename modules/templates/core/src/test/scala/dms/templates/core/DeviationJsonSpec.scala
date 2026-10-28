package dms.templates.core

import dms.kernel.UserId
import dms.templates.*
import zio.test.*

import java.time.Instant

object DeviationJsonSpec extends ZIOSpecDefault:

  private def section(id: String, blocks: Block*) =
    Section(SectionId(id), Some(id), locked = true, blocks.toList)

  private val template = TemplateContent(
    List(
      section(
        "liability",
        Block.Paragraph(List(Inline.Text("Payment within 30 days to "), Inline.Placeholder("partner"))),
        Block.BulletList(List(List(Block.Paragraph(List(Inline.Text("one")))))),
        Block.Table(List(List(List(Block.Paragraph(List(Inline.Text("cell")))))), headerRows = 1)
      ),
      section("clauses", Block.ClauseRef("gdpr", 1))
    )
  )

  private val document = TemplateContent(
    List(
      section(
        "liability",
        Block.Paragraph(
          List(Inline.Text("Payment within 60 days to "), Inline.Placeholder("partner", Some("upper")))
        ),
        Block.BulletList(List(List(Block.Paragraph(List(Inline.Text("one")))), List(Block.PageBreak))),
        Block.Table(List(List(List(Block.Paragraph(List(Inline.Text("cell")))))), headerRows = 0),
        Block.Heading(2, List(Inline.Text("New", Set(Mark.Bold))))
      ),
      section("clauses", Block.ClauseRef("gdpr", 2))
    )
  )

  private val request =
    DeviationRequest(
      SectionId("liability"),
      "Customer",
      UserId("author-1"),
      Instant.parse("2026-10-28T12:00:00Z")
    )

  def spec = suite("DeviationJson")(
    test("a stored report round-trips with its format version") {
      val report = DeviationReport.compare(template, document, List(request))
      val json   = DeviationJson.encodeStored(report)
      assertTrue(
        report.deviations.size >= 5,
        report.authorized.nonEmpty,
        report.unauthorized.nonEmpty,
        json.startsWith("""{"format":1,"deviations":"""),
        json.contains(""""type":"TextReplaced""""),
        json.contains(""""type":"Block","index":0"""),
        DeviationJson.decodeStored(json) == Right(report)
      )
    },
    test("an empty report is the column default of migration V12") {
      assertTrue(
        DeviationJson.encodeStored(DeviationReport(Nil)) == """{"format":1,"deviations":[]}""",
        DeviationJson.decodeStored("""{"format":1,"deviations":[]}""") == Right(DeviationReport(Nil))
      )
    },
    test("unknown or missing format versions are rejected") {
      assertTrue(
        DeviationJson.decodeStored("""{"format":2,"deviations":[]}""").isLeft,
        DeviationJson.decodeStored("""{"deviations":[]}""").isLeft
      )
    }
  )
