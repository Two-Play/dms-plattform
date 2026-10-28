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
    case ReadPublished      => document(Published)
    case CreateFromTemplate => Resource(tenant, documentClassPath = List("policy"))
    case EditDraft          => document(Draft)
    case CommentInReview    => document(InReview)
    case Approve            => document(InApproval)
    case Publish            => document(Approved)
    case Withdraw           => document(Published)
    case EditTemplates      => Resource(tenant, documentClassPath = List("policy"))
    case ManageRetention    => document(Archived)
    case Destroy            => document(Archived)
    case ViewHistory        => document(Published)
    case Configure          => Resource(tenant, configurationArea = Some(ConfigurationArea.DocumentClasses))

  /** The condition that turns a ◐ cell of the matrix into a permission. */
  private def favorable(action: Action, role: Role): Resource =
    val r = base(action)
    (action, role) match
      case (EditDraft, Reviewer)      => r.copy(status = Some(InReview), assignedParticipants = Set(user))
      case (Approve, Owner)           => r.copy(ownerMayApprove = true)
      case (Publish, Approver)        => r.copy(approvedBy = Set(user))
      case (ViewHistory, Author)      => r.copy(authorId = Some(user))
      case (ViewHistory, Reviewer)    => r.copy(assignedParticipants = Set(user))
      case (ViewHistory, Approver)    => r.copy(assignedParticipants = Set(user))
      case (ViewHistory, TenantAdmin) =>
        Resource(tenant, configurationArea = Some(ConfigurationArea.Workflows))
      case (Configure, RecordsManager) => r.copy(configurationArea = Some(ConfigurationArea.Retention))
      case _                           => r

  enum Mark:
    case Full, Partial, Never

  import Mark.*

  /** Permission matrix of business concept 2.3, columns in the order of [[Role]] (Destroy is checked
    * separately).
    */
  private val matrix: List[(Action, List[Mark])] = List(
    //                     Reader  Author   Owner    Reviewer Approver Designer Records  Auditor  Admin
    ReadPublished      -> List(Full, Full, Full, Full, Full, Full, Full, Full, Partial),
    CreateFromTemplate -> List(Never, Full, Full, Never, Never, Never, Never, Never, Never),
    EditDraft          -> List(Never, Full, Full, Partial, Never, Never, Never, Never, Never),
    CommentInReview    -> List(Never, Full, Full, Full, Full, Never, Never, Never, Never),
    Approve            -> List(Never, Never, Partial, Never, Full, Never, Never, Never, Never),
    Publish            -> List(Never, Never, Full, Never, Partial, Never, Never, Never, Never),
    Withdraw           -> List(Never, Never, Full, Never, Full, Never, Never, Never, Never),
    EditTemplates      -> List(Never, Never, Never, Never, Never, Full, Never, Never, Never),
    ManageRetention    -> List(Never, Never, Never, Never, Never, Never, Full, Never, Never),
    ViewHistory        -> List(Never, Partial, Full, Partial, Partial, Never, Full, Full, Partial),
    Configure          -> List(Never, Never, Never, Never, Never, Never, Partial, Never, Full)
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
        !permitted(Seq(Owner), Publish, document(InApproval))
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
        Policy.decide(reader, ReadPublished, confidential.copy(ownerId = Some(user)), now).isPermitted
      )
    },
    test("every denial carries a reason") {
      val decision = Policy.decide(subjectWith(Reader), EditTemplates, base(EditTemplates), now)
      assertTrue(decision match
        case Decision.Deny(reason) => reason.nonEmpty
        case _                     => false)
    }
  )
