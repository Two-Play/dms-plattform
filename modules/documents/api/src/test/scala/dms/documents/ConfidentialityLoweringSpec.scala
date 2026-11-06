package dms.documents

import dms.documents.DocumentStatus.*
import dms.documents.metadata.{CoreFields, MetadataValue}
import dms.kernel.{DocumentId, TemplateId, UserId, VersionNo}
import dms.templates.TemplateContent
import zio.test.*

import java.time.Instant
import java.util.UUID

/** What may be asked for, and which level a version with a lowering is decided on (ADR 0083). */
object ConfidentialityLoweringSpec extends ZIOSpecDefault:

  private def version(
      level: String,
      status: DocumentStatus,
      lowering: Option[LoweringStatus],
      publishedAt: Option[Instant] = None
  ) =
    val id = UUID.randomUUID()
    DocumentVersion(
      id,
      DocumentId.random(),
      VersionNo(2, 0),
      1,
      status,
      ChangeKind.Major,
      None,
      "",
      1,
      TemplateId.random(),
      1,
      TemplateContent(Nil),
      Map(
        CoreFields.Confidentiality -> FieldEntry(MetadataValue.Selection(level), ValueOrigin.Manual)
      ),
      None,
      None,
      UserId("author"),
      Instant.EPOCH,
      Instant.EPOCH,
      None,
      None,
      publishedAt = publishedAt,
      lowerings = lowering.toList.map(s =>
        ConfidentialityLowering(
          UUID.randomUUID(),
          id,
          "Confidential",
          "Internal",
          "Reason",
          UserId("asker"),
          Instant.EPOCH,
          s
        )
      )
    )

  private def levelOf(v: DocumentVersion) = CoreFields.confidentialityLevel(v.accessMetadata)

  def spec = suite("Lowering the confidentiality")(
    test("only a level below the basis can be asked for, and only on a later version") {
      def problems(to: String, current: String, basis: Option[String]) =
        ConfidentialityLowering.problems(to, current, basis)
      assertTrue(
        problems("Internal", "Confidential", Some("Confidential")).isEmpty,
        problems("Public", "StrictlyConfidential", Some("Confidential")).isEmpty,
        problems("Internal", "Confidential", None).exists(_.contains("first version")),
        problems("Confidential", "Confidential", Some("Confidential")).exists(_.contains("save the draft")),
        problems("Confidential", "StrictlyConfidential", Some("Internal"))
          .exists(_.contains("save the draft")),
        problems("Secret", "Confidential", Some("Confidential")).exists(_.contains("unknown"))
      )
    },
    test("an open or refused request changes nothing about access") {
      assertTrue(
        levelOf(version("Confidential", Draft, Some(LoweringStatus.Requested))) == "Confidential",
        levelOf(version("Confidential", Draft, Some(LoweringStatus.Refused))) == "Confidential",
        version("Confidential", InApproval, Some(LoweringStatus.Requested)).accessFloor.isEmpty
      )
    },
    test("an approved lowering keeps the old level for access until the version is published") {
      val waiting = version("Internal", Approved, Some(LoweringStatus.Approved))
      assertTrue(
        levelOf(version("Internal", InApproval, Some(LoweringStatus.Approved))) == "Confidential",
        levelOf(waiting) == "Confidential",
        CoreFields.confidentialityLevel(waiting.metadata) == "Internal",
        CoreFields.isRestricted(waiting.accessMetadata)
      )
    },
    test("a version that was never published keeps the old level for good") {
      assertTrue(levelOf(version("Internal", Withdrawn, Some(LoweringStatus.Approved))) == "Confidential")
    },
    test("publication ends it, also for what becomes of the version later") {
      val published = Some(Instant.EPOCH)
      assertTrue(
        levelOf(version("Internal", Published, Some(LoweringStatus.Approved), published)) == "Internal",
        levelOf(version("Internal", Superseded, Some(LoweringStatus.Approved), published)) == "Internal"
      )
    }
  )
