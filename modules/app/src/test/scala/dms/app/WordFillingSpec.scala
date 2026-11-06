package dms.app

import dms.documents.*
import dms.documents.core.DocumentDetails
import dms.documents.metadata.{ClassKey, DocumentClassId, MetadataProfile, MetadataValue}
import dms.kernel.{DocumentId, TemplateId, UserId, VersionNo}
import dms.templates.TemplateContent
import zio.test.*

import java.time.Instant
import java.util.UUID

object WordFillingSpec extends ZIOSpecDefault:

  private val inForce = UserId("owner-in-force")
  private val named   = UserId("owner-named")
  private val names   = Map(inForce -> "Ada", named -> "Grace")

  private val template = TemplateId.random()
  private val document = Document(
    DocumentId.random(),
    DocumentClassId.random(),
    1,
    "Policy",
    Some(inForce),
    UserId("author"),
    template,
    1,
    Instant.EPOCH
  )
  private val profile = MetadataProfile(document.classId, ClassKey.parse("policy").toOption.get, 1, Nil, Nil)

  private def version(values: Map[String, FieldEntry]) =
    DocumentVersion(
      UUID.randomUUID(),
      document.id,
      VersionNo(2, 0),
      1,
      DocumentStatus.Approved,
      ChangeKind.Major,
      Some("New owner"),
      "",
      1,
      template,
      1,
      TemplateContent(Nil),
      values,
      None,
      None,
      UserId("author"),
      Instant.EPOCH,
      Instant.EPOCH,
      None,
      None
    )

  private def owner(v: DocumentVersion) =
    WordFilling.automatic(DocumentDetails(document, List(v), profile), v, names, None).get("owner")

  def spec = suite("WordFilling")(
    test("the owner printed is the one the version names, before the document has it (ADR 0074)") {
      val naming =
        Map("owner" -> FieldEntry(MetadataValue.PersonRef(named.value), ValueOrigin.Manual))
      assertTrue(owner(version(naming)).contains("Grace"), owner(version(Map.empty)).contains("Ada"))
    }
  )
