package dms.authz

import dms.documents.DocumentStatus
import dms.documents.DocumentStatus.*

import java.time.Instant

/** Pure authorization decisions: role-based permissions from the permission matrix (business concept 2.3),
  * restricted by attributes (tenant, organizational unit, document class, confidentiality, status,
  * ownership).
  *
  * Deny by default: an action is permitted only if a rule below explicitly grants it.
  */
object Policy:
  import Action.*
  import Role.*

  def decide(subject: Subject, action: Action, resource: Resource, now: Instant): Decision =
    if subject.tenantId != resource.tenantId then Decision.Deny("resource belongs to another tenant")
    else
      val roles = rolesInScope(subject, resource, now)
      if roles.isEmpty then Decision.Deny("no active role assignment covers this resource")
      else
        confidentialityDenial(subject, resource) match
          case Some(denial) => denial
          case None         => permissions(subject, roles, action, resource)

  /** Roles whose assignment is active and whose scope covers the resource. */
  def rolesInScope(subject: Subject, resource: Resource, now: Instant): Set[Role] =
    subject.assignments.collect {
      case a
          if a.isActiveAt(now) &&
            a.organizationalUnit.forall(ou => resource.organizationalUnit.contains(ou)) &&
            a.documentClass.forall(resource.documentClassPath.contains) =>
        a.role
    }

  /** Whether the confidentiality of the resource lets the subject see it at all, whatever their roles: always
    * below `Confidential`; from `Confidential` on only for author, owner and members of an allowed group
    * (business concept 2.3).
    */
  def clearedFor(subject: Subject, resource: Resource): Boolean =
    resource.confidentiality match
      case Confidentiality.Confidential | Confidentiality.StrictlyConfidential if resource.isDocument =>
        resource.authorId.contains(subject.userId) || resource.ownerId.contains(subject.userId) ||
        subject.groups.exists(resource.allowedGroups.contains)
      case _ => true

  private def confidentialityDenial(subject: Subject, resource: Resource): Option[Decision] =
    Option.unless(clearedFor(subject, resource))(
      Decision.Deny(s"${resource.confidentiality} document: caller is not in an allowed group")
    )

  private def permissions(subject: Subject, roles: Set[Role], action: Action, resource: Resource): Decision =
    def has(role: Role): Boolean     = roles.contains(role)
    def isAuthor: Boolean            = resource.authorId.contains(subject.userId)
    def isParticipant: Boolean       = resource.assignedParticipants.contains(subject.userId)
    def statusIn(s: DocumentStatus*) = resource.status.exists(s.contains)
    def requireStatus(s: DocumentStatus*)(decision: => Decision): Decision =
      if statusIn(s*) then decision
      else Decision.Deny(s"$action is not possible in status ${resource.status.getOrElse("none")}")
    def grantIf(condition: Boolean, reason: String): Decision =
      if condition then Decision.permit else Decision.Deny(reason)

    action match
      case ReadPublished =>
        requireStatus(Published, ReviewDue)(
          grantIf(
            roles.exists(_ != TenantAdmin),
            "administrators need a business role to read document content"
          )
        )

      case ReadDocument =>
        def participant(roles: Role*)    = roles.exists(has) && isParticipant
        def readable(condition: Boolean) =
          grantIf(
            condition,
            s"version in status ${resource.status.getOrElse("none")} is not visible to this caller"
          )
        resource.status match
          case Some(Published | ReviewDue) => permissions(subject, roles, ReadPublished, resource)
          case Some(Draft)                 => readable(has(Author) || has(Owner))
          case Some(InReview)              => readable(has(Author) || has(Owner) || participant(Reviewer))
          case Some(InApproval)            =>
            readable(has(Author) || has(Owner) || participant(Reviewer, Approver))
          case Some(Approved) =>
            readable(
              has(Author) || has(Owner) || participant(Reviewer, Approver) ||
                resource.approvedBy.contains(subject.userId)
            )
          case Some(Superseded) =>
            readable(roles.exists(_ != TenantAdmin))
          case Some(Withdrawn | Archived) => readable(has(Owner) || has(RecordsManager) || has(Auditor))
          case Some(Destroyed | Discarded) | None => Decision.Deny("there is no readable document version")

      case CreateFromTemplate =>
        grantIf(has(Author) || has(Owner), "creating documents requires the author or owner role")

      case EditDraft =>
        if statusIn(Draft) then
          grantIf(has(Author) || has(Owner), "editing drafts requires the author or owner role")
        else if statusIn(InReview) then
          grantIf(has(Reviewer) && isParticipant, "only assigned reviewers can suggest changes during review")
        else Decision.Deny(s"EditDraft is not possible in status ${resource.status.getOrElse("none")}")

      case CommentInReview =>
        requireStatus(InReview, InApproval)(
          grantIf(
            has(Author) || has(Owner) || has(Reviewer) || has(Approver),
            "commenting requires the author, owner, reviewer or approver role"
          )
        )

      case Review =>
        requireStatus(InReview)(
          grantIf(
            isParticipant && (has(Author) || has(Owner) || has(Reviewer) || has(Approver)),
            "review votes are cast by the participants the workflow assigned to the step"
          )
        )

      case Approve =>
        requireStatus(InApproval)(
          if isAuthor then Decision.Deny("segregation of duties: the author cannot approve their own version")
          else
            grantIf(
              has(Approver) || (has(Owner) && resource.ownerMayApprove),
              "approval requires the approver role (or owner, if the document class allows it)"
            )
        )

      case Publish =>
        requireStatus(Approved)(
          grantIf(
            has(Owner) || (has(Approver) && resource.approvedBy.contains(subject.userId)),
            "publishing requires the owner role or having approved this version"
          )
        )

      case Withdraw =>
        requireStatus(Published, ReviewDue)(
          grantIf(has(Owner) || has(Approver), "withdrawing requires the owner or approver role")
        )

      case Recertify =>
        requireStatus(Published, ReviewDue)(
          grantIf(
            has(Owner) && resource.ownerId.contains(subject.userId),
            "recertification is done by the document's owner"
          )
        )

      case EditTemplates =>
        grantIf(has(TemplateDesigner), "editing templates requires the template designer role")

      case ReleaseTemplate =>
        if isAuthor || resource.contributors.contains(subject.userId) then
          Decision.Deny(
            "segregation of duties: a template designer cannot release a version they created or changed"
          )
        else grantIf(has(TemplateDesigner), "releasing templates requires the template designer role")

      case ReadTemplateCatalog =>
        grantIf(roles.exists(_ != TenantAdmin), "the template catalog requires a business role")

      case ManageRetention =>
        grantIf(has(RecordsManager), "retention and legal holds require the records manager role")

      case Destroy =>
        requireStatus(Archived)(
          if has(RecordsManager) then Decision.Permit(Set(Condition.SecondPersonConfirmation))
          else Decision.Deny("destruction requires the records manager role")
        )

      case ViewHistory =>
        val unrestricted = has(Owner) || has(RecordsManager) || has(Auditor)
        val restricted   =
          (has(Author) && isAuthor) ||
            ((has(Reviewer) || has(Approver)) && isParticipant) ||
            (has(TenantAdmin) && !resource.isDocument)
        grantIf(unrestricted || restricted, "the history is not visible to this caller")

      case MonitorCompliance =>
        grantIf(has(ComplianceOfficer), "the compliance cockpit requires the compliance officer role")

      case ViewAcknowledgementPersons =>
        requireStatus(Published, ReviewDue)(
          grantIf(
            has(ComplianceOfficer) || (has(Owner) && resource.ownerId.contains(subject.userId)),
            "who has not acknowledged a version is visible to compliance officers and the document's owner only"
          )
        )

      case GenerateEvidenceReport =>
        grantIf(
          resource.isDocument && (has(Auditor) || has(RecordsManager)),
          "evidence reports require the auditor or records manager role"
        )

      case AssignDocumentNumber =>
        grantIf(
          resource.isDocument && !statusIn(Destroyed) &&
            (has(RecordsManager) || (has(Owner) && resource.ownerId.contains(subject.userId))),
          "assigning a document number requires the records manager role or being the document's owner"
        )

      case DiscardDraft =>
        requireStatus(Draft)(
          grantIf(
            ((has(Author) || has(Owner)) && isAuthor) ||
              (has(Owner) && resource.ownerId.contains(subject.userId)) || has(TenantAdmin),
            "discarding a draft requires being its author or the document's owner, or the tenant administrator role"
          )
        )

      case SearchUsers =>
        grantIf(
          !resource.isDocument && resource.configurationArea.isEmpty,
          "searching people is a tenant-wide action, not one on a document"
        )

      case Configure =>
        resource.configurationArea match
          case None       => Decision.Deny("not a configuration resource")
          case Some(area) =>
            grantIf(
              has(TenantAdmin) || (has(RecordsManager) && area == ConfigurationArea.Retention),
              s"configuring $area requires the tenant administrator role"
            )
