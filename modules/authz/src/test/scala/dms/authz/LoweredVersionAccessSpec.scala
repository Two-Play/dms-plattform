package dms.authz

import dms.documents.metadata.{CoreFields, DocumentClassId, MetadataValue}
import dms.documents.*
import dms.kernel.{DocumentId, TemplateId, TenantId, UserId, VersionNo}
import dms.templates.TemplateContent
import zio.test.*

import java.time.Instant
import java.util.UUID

/** A version with an approved lowering under the policy (ADR 0083): it states the lower level, but the old
  * one decides who sees it until it is published; and nobody who changed it decides.
  */
object LoweredVersionAccessSpec extends ZIOSpecDefault:

  private val tenant = TenantId.random()
  private val now    = Instant.parse("2026-11-06T12:00:00Z")
  private val owner  = UserId("owner")
  private val member = UserId("member")
  private val reader = UserId("reader")

  private val document = Document(
    DocumentId.random(),
    DocumentClassId(UUID.randomUUID()),
    1,
    "Board minutes",
    Some(owner),
    UserId("author"),
    TemplateId.random(),
    1,
    Instant.EPOCH
  )

  private def version(status: DocumentStatus, publishedAt: Option[Instant] = None) =
    val id                          = UUID.randomUUID()
    def entry(value: MetadataValue) = FieldEntry(value, ValueOrigin.Manual)
    DocumentVersion(
      id,
      document.id,
      VersionNo(2, 0),
      1,
      status,
      ChangeKind.Major,
      None,
      "",
      1,
      document.templateId,
      1,
      TemplateContent(Nil),
      Map(
        CoreFields.Confidentiality -> entry(MetadataValue.Selection("Internal")),
        CoreFields.AllowedGroups   -> entry(MetadataValue.Many(List(MetadataValue.Text("board"))))
      ),
      None,
      None,
      UserId("author"),
      Instant.EPOCH,
      Instant.EPOCH,
      None,
      None,
      publishedAt = publishedAt,
      lowerings = List(
        ConfidentialityLowering(
          UUID.randomUUID(),
          id,
          "Confidential",
          "Internal",
          "Reason",
          member,
          Instant.EPOCH,
          LoweringStatus.Approved
        )
      )
    )

  private def resource(v: DocumentVersion) = DocumentResource(tenant, document, List("policy"), v)

  private def subject(user: UserId, groups: Set[String], role: Role) =
    Subject(user, tenant, Set(RoleAssignment(role)), groups)

  private def reads(s: Subject, v: DocumentVersion) =
    Policy.decide(s, Action.ReadDocument, resource(v), now).isPermitted

  def spec = suite("A version with an approved lowering")(
    test("is decided on the old level until it is published") {
      val approved = version(DocumentStatus.Approved)
      assertTrue(
        resource(approved).confidentiality == Confidentiality.Confidential,
        resource(version(DocumentStatus.InApproval)).confidentiality == Confidentiality.Confidential,
        !reads(subject(reader, Set.empty, Role.Author), approved),
        reads(subject(member, Set("board"), Role.Author), approved)
      )
    },
    test("reaches readers of the lower level from its publication on") {
      val valid = version(DocumentStatus.Published, Some(now))
      assertTrue(
        resource(valid).confidentiality == Confidentiality.Internal,
        reads(subject(reader, Set.empty, Role.Reader), valid)
      )
    },
    test("stays closed if it never takes effect") {
      val stopped = version(DocumentStatus.Withdrawn)
      assertTrue(
        resource(stopped).confidentiality == Confidentiality.Confidential,
        !Policy.clearedFor(subject(reader, Set.empty, Role.RecordsManager), resource(stopped))
      )
    },
    test(
      "an approver outside the groups is not cleared; an approver who changed the version may not approve"
    ) {
      val open     = version(DocumentStatus.InApproval)
      val cleared  = subject(member, Set("board"), Role.Approver)
      val outside  = subject(reader, Set.empty, Role.Approver)
      val decision = resource(open).copy(contributors = Set(member))
      assertTrue(
        !Policy.clearedFor(outside, resource(open)),
        !Policy.decide(outside, Action.Approve, resource(open), now).isPermitted,
        !Policy.decide(cleared, Action.Approve, decision, now).isPermitted,
        Policy.decide(cleared, Action.Approve, resource(open), now).isPermitted
      )
    }
  )
