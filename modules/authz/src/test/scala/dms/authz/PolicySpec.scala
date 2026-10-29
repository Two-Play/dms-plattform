package dms.authz

import dms.documents.DocumentStatus
import dms.documents.DocumentStatus.*
import dms.kernel.{TenantId, UserId}
import zio.test.*

import java.time.Instant

object PolicySpec extends ZIOSpecDefault:
  import Action.*
  import Role.*

  private val now    = Instant.parse("2026-11-02T09:00:00Z")
  private val tenant = TenantId.random()
  private val user   = UserId("user-1")
  private val other  = UserId("user-2")

  private def subjectWith(roles: Role*): Subject =
    Subject(user, tenant, roles.map(RoleAssignment(_)).toSet)

  private def document(status: DocumentStatus): Resource =
    Resource(tenant, documentClassPath = List("policy"), status = Some(status), authorId = Some(other))

  /** Resource on which the action is meaningful, with the caller in no special relation to it. */
  private def base(action: Action): Resource = action match
    case ReadPublished       => document(Published)
    case ReadDocument        => document(Draft)
    case CreateFromTemplate  => Resource(tenant, documentClassPath = List("policy"))
    case EditDraft           => document(Draft)
    case CommentInReview     => document(InReview)
    case Review              => document(InReview)
    case Approve             => document(InApproval)
    case Publish             => document(Approved)
    case Withdraw            => document(Published)
    case Recertify           => document(ReviewDue)
    case EditTemplates       => Resource(tenant, documentClassPath = List("policy"))
    case ReleaseTemplate     => Resource(tenant, authorId = Some(other))
    case ReadTemplateCatalog => Resource(tenant)
    case ManageRetention     => document(Archived)
    case Destroy             => document(Archived)
    case ViewHistory         => document(Published)
    case Configure           => Resource(tenant, configurationArea = Some(ConfigurationArea.DocumentClasses))
    case MonitorCompliance   => document(ReviewDue)
    case ViewAcknowledgementPersons => document(Published)
    case GenerateEvidenceReport     => document(Superseded)
    case AssignDocumentNumber       => document(Draft)

  /** The condition that turns a ◐ cell of the matrix into a permission. */
  private def favorable(action: Action, role: Role): Resource =
    val r = base(action)
    (action, role) match
      case (EditDraft, Reviewer) => r.copy(status = Some(InReview), assignedParticipants = Set(user))
      case (Review, _)           => r.copy(assignedParticipants = Set(user))
      case (Approve, Owner)      => r.copy(ownerMayApprove = true)
      case (Recertify, _)        => r.copy(ownerId = Some(user))
      case (ViewAcknowledgementPersons, _) => r.copy(ownerId = Some(user))
      case (AssignDocumentNumber, _)       => r.copy(ownerId = Some(user))
      case (Publish, Approver)             => r.copy(approvedBy = Set(user))
      case (ViewHistory, Author)           => r.copy(authorId = Some(user))
      case (ViewHistory, Reviewer)         => r.copy(assignedParticipants = Set(user))
      case (ViewHistory, Approver)         => r.copy(assignedParticipants = Set(user))
      case (ViewHistory, TenantAdmin)      =>
        Resource(tenant, configurationArea = Some(ConfigurationArea.Workflows))
      case (Configure, RecordsManager) => r.copy(configurationArea = Some(ConfigurationArea.Retention))
      case _                           => r

  enum Mark:
    case Full, Partial, Never

  import Mark.*

  /** Permission matrix of business concept 2.3, columns in the order of [[Role]] (Destroy is checked
    * separately). The compliance officer ("Compliance-Beauftragter", business concept 2.2) has no column in
    * the matrix of the business concept; its rights follow ADR 0036: reading published documents like every
    * business role, the compliance cockpit and, for escalation, who has not acknowledged a version.
    */
  private val matrix: List[(Action, List[Mark])] = List(
    //                     Reader  Author   Owner    Reviewer Approver Designer Records  Auditor  Admin  Compliance
    ReadPublished       -> List(Full, Full, Full, Full, Full, Full, Full, Full, Partial, Full),
    CreateFromTemplate  -> List(Never, Full, Full, Never, Never, Never, Never, Never, Never, Never),
    EditDraft           -> List(Never, Full, Full, Partial, Never, Never, Never, Never, Never, Never),
    CommentInReview     -> List(Never, Full, Full, Full, Full, Never, Never, Never, Never, Never),
    Review              -> List(Never, Partial, Partial, Partial, Partial, Never, Never, Never, Never, Never),
    Approve             -> List(Never, Never, Partial, Never, Full, Never, Never, Never, Never, Never),
    Publish             -> List(Never, Never, Full, Never, Partial, Never, Never, Never, Never, Never),
    Withdraw            -> List(Never, Never, Full, Never, Full, Never, Never, Never, Never, Never),
    Recertify           -> List(Never, Never, Partial, Never, Never, Never, Never, Never, Never, Never),
    EditTemplates       -> List(Never, Never, Never, Never, Never, Full, Never, Never, Never, Never),
    ReleaseTemplate     -> List(Never, Never, Never, Never, Never, Full, Never, Never, Never, Never),
    ReadTemplateCatalog -> List(Full, Full, Full, Full, Full, Full, Full, Full, Never, Full),
    ManageRetention     -> List(Never, Never, Never, Never, Never, Never, Full, Never, Never, Never),
    ViewHistory         -> List(Never, Partial, Full, Partial, Partial, Never, Full, Full, Partial, Never),
    Configure           -> List(Never, Never, Never, Never, Never, Never, Partial, Never, Full, Never),
    MonitorCompliance   -> List(Never, Never, Never, Never, Never, Never, Never, Never, Never, Full),
    ViewAcknowledgementPersons -> List(Never, Never, Partial, Never, Never, Never, Never, Never, Never, Full),
    // Evidence reports (US-12.1, DMS-57): internal audit and records management.
    GenerateEvidenceReport -> List(Never, Never, Never, Never, Never, Never, Full, Full, Never, Never),
    // Manual document numbers (DMS-113, ADR 0040): records management, and owners for their own documents.
    AssignDocumentNumber -> List(Never, Never, Partial, Never, Never, Never, Full, Never, Never, Never)
  )

  private def permitted(roles: Seq[Role], action: Action, resource: Resource): Boolean =
    Policy.decide(subjectWith(roles*), action, resource, now).isPermitted

  private val matrixTests = matrix.flatMap { (action, marks) =>
    Role.values.toList.zip(marks).map { (role, mark) =>
      test(s"$action / $role: $mark") {
        mark match
          case Full    => assertTrue(permitted(Seq(role), action, base(action)))
          case Partial =>
            val withCondition =
              if action == ReadPublished then permitted(Seq(role, Reader), action, base(action))
              else permitted(Seq(role), action, favorable(action, role))
            assertTrue(withCondition, !permitted(Seq(role), action, base(action)))
          case Never => assertTrue(!permitted(Seq(role), action, favorable(action, role)))
      }
    }
  }

  def spec = suite("Policy")(
    suite("permission matrix (business concept 2.3)")(matrixTests*),
    test("destruction by the records manager needs a second person") {
      val decision = Policy.decide(subjectWith(RecordsManager), Destroy, base(Destroy), now)
      assertTrue(
        decision == Decision.Permit(Set(Condition.SecondPersonConfirmation)),
        !permitted(Seq(Owner, Approver, Auditor), Destroy, base(Destroy))
      )
    },
    test("evidence reports need a document and respect confidentiality") {
      val confidential = document(Published).copy(confidentiality = Confidentiality.Confidential)
      assertTrue(
        !permitted(Seq(Auditor), GenerateEvidenceReport, Resource(tenant)),
        !permitted(Seq(Auditor), GenerateEvidenceReport, confidential),
        permitted(
          Seq(Auditor),
          GenerateEvidenceReport,
          confidential.copy(allowedGroups = Set("audit"))
        ) == false,
        Policy
          .decide(
            subjectWith(Auditor).copy(groups = Set("audit")),
            GenerateEvidenceReport,
            confidential.copy(allowedGroups = Set("audit")),
            now
          )
          .isPermitted
      )
    },
    test("deny by default without any role") {
      assertTrue(!permitted(Seq.empty, ReadPublished, base(ReadPublished)))
    },
    test("another tenant's resource is denied regardless of roles") {
      val foreign = base(ReadPublished).copy(tenantId = TenantId.random())
      assertTrue(!permitted(Role.values.toSeq, ReadPublished, foreign))
    },
    test("the author cannot approve their own version, even as approver") {
      val own = base(Approve).copy(authorId = Some(user))
      assertTrue(!permitted(Seq(Approver), Approve, own))
    },
    test("status restricts actions") {
      assertTrue(
        !permitted(Seq(Author), EditDraft, document(Published)),
        !permitted(Seq(Reader), ReadPublished, document(Draft)),
        !permitted(Seq(Approver), Approve, document(Draft)),
        !permitted(Seq(Reviewer), Review, document(InApproval).copy(assignedParticipants = Set(user))),
        !permitted(Seq(Owner), Publish, document(InApproval)),
        !permitted(Seq(Owner), Recertify, document(Superseded).copy(ownerId = Some(user)))
      )
    },
    test("assignments scoped to an organizational unit or class only cover matching resources") {
      val scoped = Subject(
        user,
        tenant,
        Set(RoleAssignment(Reader, organizationalUnit = Some("hr"), documentClass = Some("policy")))
      )
      val inScope =
        base(ReadPublished).copy(organizationalUnit = Some("hr"), documentClassPath = List("policy", "it"))
      val otherUnit  = inScope.copy(organizationalUnit = Some("finance"))
      val otherClass = inScope.copy(documentClassPath = List("contract"))
      assertTrue(
        Policy.decide(scoped, ReadPublished, inScope, now).isPermitted,
        !Policy.decide(scoped, ReadPublished, otherUnit, now).isPermitted,
        !Policy.decide(scoped, ReadPublished, otherClass, now).isPermitted
      )
    },
    test("expired or future assignments do not count") {
      val expired = Subject(user, tenant, Set(RoleAssignment(Reader, validUntil = Some(now.minusSeconds(1)))))
      val future  = Subject(user, tenant, Set(RoleAssignment(Reader, validFrom = now.plusSeconds(60))))
      assertTrue(
        !Policy.decide(expired, ReadPublished, base(ReadPublished), now).isPermitted,
        !Policy.decide(future, ReadPublished, base(ReadPublished), now).isPermitted
      )
    },
    test("confidential documents need an allowed group or personal involvement") {
      val confidential = base(ReadPublished).copy(
        confidentiality = Confidentiality.Confidential,
        allowedGroups = Set("legal")
      )
      val reader = subjectWith(Reader)
      assertTrue(
        !Policy.decide(reader, ReadPublished, confidential, now).isPermitted,
        Policy.decide(reader.copy(groups = Set("legal")), ReadPublished, confidential, now).isPermitted,
        Policy.decide(reader, ReadPublished, confidential.copy(ownerId = Some(user)), now).isPermitted,
        // the confidentiality part alone, used to filter task inboxes (DMS-101)
        !Policy.clearedFor(reader, confidential),
        Policy.clearedFor(reader.copy(groups = Set("legal")), confidential),
        Policy.clearedFor(reader, confidential.copy(confidentiality = Confidentiality.Internal))
      )
    },
    test("a template designer cannot release a template version they created or changed") {
      val designer = subjectWith(TemplateDesigner)
      val version  = base(ReleaseTemplate)
      assertTrue(
        Policy.decide(designer, ReleaseTemplate, version, now).isPermitted,
        !Policy.decide(designer, ReleaseTemplate, version.copy(authorId = Some(user)), now).isPermitted,
        !Policy.decide(designer, ReleaseTemplate, version.copy(contributors = Set(user)), now).isPermitted,
        !Policy
          .decide(
            subjectWith(TemplateDesigner, Approver),
            ReleaseTemplate,
            version.copy(authorId = Some(user)),
            now
          )
          .isPermitted
      )
    },
    test("the visibility of a document version follows its status (business concept 5.1)") {
      def reads(status: DocumentStatus, roles: Role*)(adjust: Resource => Resource = identity) =
        permitted(roles, ReadDocument, adjust(document(status)))
      val participant = (r: Resource) => r.copy(assignedParticipants = Set(user))
      assertTrue(
        reads(Draft, Author)(),
        reads(Draft, Owner)(),
        !reads(Draft, Reader)(),
        !reads(Draft, Reviewer)(participant),
        !reads(Draft, TenantAdmin)(),
        !reads(InReview, Reviewer)(),
        reads(InReview, Reviewer)(participant),
        !reads(InReview, Approver)(participant),
        reads(InApproval, Approver)(participant),
        reads(Approved, Approver)(_.copy(approvedBy = Set(user))),
        !reads(Approved, Reader)(),
        reads(Published, Reader)(),
        reads(ReviewDue, Reader)(),
        !reads(Published, TenantAdmin)(),
        reads(Superseded, Reader)(),
        !reads(Archived, Reader)(),
        reads(Archived, RecordsManager)(),
        reads(Withdrawn, Owner)(),
        !reads(Destroyed, RecordsManager)()
      )
    },
    test("the compliance cockpit follows the scope and the confidentiality of the version (ADR 0036)") {
      val hrOfficer =
        Subject(user, tenant, Set(RoleAssignment(ComplianceOfficer, organizationalUnit = Some("hr"))))
      val hr     = document(Published).copy(organizationalUnit = Some("hr"))
      val secret = hr.copy(confidentiality = Confidentiality.Confidential, allowedGroups = Set("board"))
      assertTrue(
        Policy.decide(hrOfficer, MonitorCompliance, hr, now).isPermitted,
        !Policy
          .decide(hrOfficer, MonitorCompliance, hr.copy(organizationalUnit = Some("finance")), now)
          .isPermitted,
        !Policy.decide(hrOfficer, MonitorCompliance, secret, now).isPermitted,
        Policy.decide(hrOfficer.copy(groups = Set("board")), MonitorCompliance, secret, now).isPermitted,
        Policy.decide(hrOfficer, ViewAcknowledgementPersons, hr, now).isPermitted,
        // names only for versions that ask for acknowledgement now
        !Policy
          .decide(hrOfficer, ViewAcknowledgementPersons, hr.copy(status = Some(Superseded)), now)
          .isPermitted
      )
    },
    test("every denial carries a reason") {
      val decision = Policy.decide(subjectWith(Reader), EditTemplates, base(EditTemplates), now)
      assertTrue(decision match
        case Decision.Deny(reason) => reason.nonEmpty
        case _                     => false)
    }
  )
