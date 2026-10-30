package dms.authz

import dms.documents.DocumentStatus
import dms.kernel.{TenantId, UserId}

import java.time.Instant

/** Business roles (business concept 2.2). `ComplianceOfficer` ("Compliance-Beauftragter") monitors
  * acknowledgements and review deadlines in the compliance cockpit (US-15.1, ADR 0036).
  */
enum Role:
  case Reader, Author, Owner, Reviewer, Approver, TemplateDesigner, RecordsManager, Auditor, TenantAdmin,
    ComplianceOfficer

/** Functions of the permission matrix (business concept 2.3).
  *
  * `ReleaseTemplate` and `ReadTemplateCatalog` refine the matrix row "Edit templates" (business concept 4.1):
  * releasing a template version needs a second template designer, and every business role may see the catalog
  * of released templates.
  *
  * `ReadDocument` covers reading a document version in any status, following the visibility column of the
  * status model (business concept 5.1): drafts for authors and owners, review and approval additionally for
  * the assigned participants, published versions like `ReadPublished`.
  *
  * `Review` is casting a review vote (business concept 6.1, DMS-40): only for participants the workflow
  * assigned to the step, who hold a business role taking part in reviews.
  *
  * `Recertify` is confirming a valid version without a change in the periodic review (business concept 6.2,
  * US-07.1): only the document's owner, who holds the owner role.
  *
  * `MonitorCompliance` is seeing a document version in the compliance cockpit (US-15.1, ADR 0036): its review
  * deadline and its acknowledgement rate, aggregated, without names of readers. Only compliance officers.
  *
  * `ViewAcknowledgementPersons` is seeing who has not acknowledged a version yet (purpose limitation,
  * business concept 10.2; ADR 0036): compliance officers for escalation and the document's owner for their
  * own documents. Callers additionally have to state the purpose, which is recorded in the audit trail.
  *
  * `GenerateEvidenceReport` is creating and reading the evidence report of a document (US-12.1, DMS-57, ADR
  * 0034): internal audit (auditor) and records managers, in any status of the document.
  *
  * `AssignDocumentNumber` is assigning a document number manually instead of (or in addition to) the number
  * range of the class (DMS-113, ADR 0040): records managers ("Registratur") and the document's owner, who
  * holds the owner role. The reason is required and recorded in the audit trail.
  *
  * `DiscardDraft` is discarding a draft that was never submitted, e.g. one created by accident (DMS-129, ADR
  * 0050): the draft's author (who holds the author or owner role), the document's owner (who holds the owner
  * role) and tenant administrators, who clean up drafts without reading their content. Only in status
  * `Draft`; submitted, approved and published versions are withdrawn or destroyed instead.
  *
  * `SearchUsers` is finding people of the own tenant by name or email, to pick a group member, a deputy or a
  * person in a metadata field (DMS-111, ADR 0037 addendum): every role of the tenant, the tenant
  * administrator included; users without any role assignment may not browse the directory.
  */
enum Action:
  case ReadPublished, ReadDocument, CreateFromTemplate, EditDraft, CommentInReview, Review, Approve, Publish,
    Withdraw, Recertify,
    EditTemplates, ReleaseTemplate, ReadTemplateCatalog,
    ManageRetention, Destroy, ViewHistory, Configure,
    MonitorCompliance, ViewAcknowledgementPersons, GenerateEvidenceReport, AssignDocumentNumber, SearchUsers,
    DiscardDraft

/** Areas of tenant configuration (matrix row "Configuration"). `Search` is the operation of the tenant's
  * search index, e.g. rebuilding it (DMS-51, ADR 0029). `Groups` is the administration of access groups and
  * their members (DMS-101, ADR 0032). `Webhooks` is the administration of webhook subscriptions of connected
  * systems (DMS-58, ADR 0035). `NumberRanges` is the administration of number ranges and their assignment to
  * document classes (DMS-113, ADR 0040).
  */
enum ConfigurationArea:
  /** `Notifications`: failed notification deliveries and the tenant's default language (DMS-130, ADR 0052).
    */
  case DocumentClasses, Workflows, Taxonomies, Retention, Search, Groups, Webhooks, NumberRanges,
    Notifications

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

/** Who acts. `groups` are the keys of the access groups the caller currently belongs to (ADR 0032), used for
  * confidential documents.
  */
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
