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
    test("reference fields name their source; older content without them reads as none") {
      val json =
        """{"format":1,"sections":[],"references":[{"key":"contractPartner","label":"Contract partner",
          |"source":"partner-master","fields":[{"attribute":"id","field":"partnerId"}]}]}""".stripMargin
      val expected = TemplateContent(
        Nil,
        references = List(
          ReferenceField(
            "contractPartner",
            "Contract partner",
            ReferenceSource.PartnerMaster,
            List(ReferenceMapping("id", "partnerId"))
          )
        )
      )
      assertTrue(
        ContentJson.decodeStored(json) == Right(expected),
        ContentJson.decodeStored(ContentJson.encodeStored(expected)) == Right(expected),
        ContentJson.decodeStored("""{"format":1,"sections":[]}""") == Right(TemplateContent(Nil)),
        ContentJson.decodeStored(json.replace("partner-master", "crm")).isLeft
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
