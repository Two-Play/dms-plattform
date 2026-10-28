package dms.authz

import dms.documents.DocumentStatus
import dms.kernel.{TenantId, UserId}

import java.time.Instant

/** Business roles (business concept 2.2). */
enum Role:
  case Reader, Author, Owner, Reviewer, Approver, TemplateDesigner, RecordsManager, Auditor, TenantAdmin

/** Functions of the permission matrix (business concept 2.3).
  *
  * `ReleaseTemplate` and `ReadTemplateCatalog` refine the matrix row "Edit templates" (business concept 4.1):
  * releasing a template version needs a second template designer, and every business role may see the catalog
  * of released templates.
  */
enum Action:
  case ReadPublished, CreateFromTemplate, EditDraft, CommentInReview, Approve, Publish, Withdraw,
    EditTemplates, ReleaseTemplate, ReadTemplateCatalog,
    ManageRetention, Destroy, ViewHistory, Configure

/** Areas of tenant configuration (matrix row "Configuration"). */
enum ConfigurationArea:
  case DocumentClasses, Workflows, Taxonomies, Retention

enum Confidentiality:
  case Public, Internal, Confidential, StrictlyConfidential

/** A role granted in a tenant, optionally restricted to an organizational unit and/or a document class
  * subtree. `None` means the whole tenant.
  */
final case class RoleAssignment(
    role: Role,
    organizationalUnit: Option[String] = None,
    documentClass: Option[String] = None,
    validFrom: Instant = Instant.EPOCH,
    validUntil: Option[Instant] = None
):
  def isActiveAt(now: Instant): Boolean = !validFrom.isAfter(now) && validUntil.forall(now.isBefore)

/** Who acts. `groups` are the caller's ZITADEL project roles, used for confidential documents. */
final case class Subject(
    userId: UserId,
    tenantId: TenantId,
    assignments: Set[RoleAssignment],
    groups: Set[String] = Set.empty
)

/** What is acted on. For tenant-wide functions (templates, configuration) the document fields stay at
  * defaults.
  */
final case class Resource(
    tenantId: TenantId,
    organizationalUnit: Option[String] = None,
    /** Class path from root to leaf, e.g. `List("contract", "service-contract")`. */
    documentClassPath: List[String] = Nil,
    confidentiality: Confidentiality = Confidentiality.Internal,
    status: Option[DocumentStatus] = None,
    authorId: Option[UserId] = None,
    ownerId: Option[UserId] = None,
    /** Users assigned to the current review or approval step. */
    assignedParticipants: Set[UserId] = Set.empty,
    /** Users who approved the current version. */
    approvedBy: Set[UserId] = Set.empty,
    /** Groups allowed to see confidential or strictly confidential documents. */
    allowedGroups: Set[String] = Set.empty,
    /** Whether the document class lets owners approve (matrix: Owner ◐ for approval). */
    ownerMayApprove: Boolean = false,
    /** Set for configuration resources instead of documents. */
    configurationArea: Option[ConfigurationArea] = None,
    /** Users who changed the resource besides its author, e.g. editors of a template version (segregation of
      * duties).
      */
    contributors: Set[UserId] = Set.empty
):
  def isDocument: Boolean = status.isDefined

/** Additional steps a permitted action still requires. */
enum Condition:
  /** A second authorized person must confirm (e.g. destruction, business concept 2.4). */
  case SecondPersonConfirmation

enum Decision:
  case Permit(conditions: Set[Condition])
  case Deny(reason: String)

  def isPermitted: Boolean = this match
    case Permit(_) => true
    case Deny(_)   => false

object Decision:
  val permit: Decision = Permit(Set.empty)
