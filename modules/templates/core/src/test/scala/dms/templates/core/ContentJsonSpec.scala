package dms.templates.core

import dms.templates.*
import zio.json.*
import zio.test.*

object ContentJsonSpec extends ZIOSpecDefault:
  import ContentJson.given

  def spec = suite("ContentJson")(
    test("stored content round-trips with its format version") {
      val json = ContentJson.encodeStored(Fixtures.content)
      assertTrue(
        json.startsWith("""{"format":1,"sections":"""),
        ContentJson.decodeStored(json) == Right(Fixtures.content)
      )
    },
    test("blocks carry their case name as type; defaults may be omitted") {
      val json = """{"sections":[{"id":"a","title":null,"locked":false,"blocks":[
                   |{"type":"Paragraph","content":[{"type":"Text","value":"Hi "},
                   |{"type":"Placeholder","path":"clientName","format":"amount"}]},
                   |{"type":"Conditional","condition":"true","body":[]},{"type":"PageBreak"}]}]}""".stripMargin
      assertTrue(
        json.fromJson[TemplateContent] == Right(
          TemplateContent(
            List(
              Section(
                SectionId("a"),
                None,
                locked = false,
                List(
                  Block.Paragraph(List(Inline.Text("Hi "), Inline.Placeholder("clientName", Some("amount")))),
                  Block.Conditional("true", Nil),
                  Block.PageBreak
                )
              )
            )
          )
        )
      )
    },
    test("unknown formats and malformed content are rejected") {
      assertTrue(
        ContentJson.decodeStored("""{"format":99,"sections":[]}""").isLeft,
        ContentJson.decodeStored("""{"sections":[]}""").isLeft,
        """{"sections":[{"id":"","locked":false,"blocks":[]}]}""".fromJson[TemplateContent].isLeft
      )
    }
  )
