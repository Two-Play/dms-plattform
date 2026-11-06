package dms.app

import dms.archive.LegalHoldId
import dms.archive.core.LegalHoldService
import dms.audit.AuditViolation
import dms.authz.{Action, Authorizer, Groups, Policy, Resource, Role, RoleAssignments, Subject}
import dms.db.Db
import dms.db.queue.OutboxEvent
import dms.documents.{DocumentStatus, DocumentVersion, Publication}
import dms.documents.core.{DocumentError, DocumentService, ReviewService}
import dms.identity.{TenantOrganizations, UserDirectory}
import dms.kernel.{DocumentId, TenantId, UserId}
import dms.notifications.*
import dms.notifications.core.{
  DeliverySettings,
  NotificationJobs,
  NotificationStore,
  NotificationTemplates,
  SmtpConfig,
  SmtpMailSender
}
import dms.rendering.RenditionEvents
import dms.workflow.{CaseDetails, WorkflowDefinition}
import dms.workflow.core.ApprovalWorkflow
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.Instant
import java.util.UUID
import scala.util.Try

/** What the platform's outbox events mean for whom (DMS-130, ADR 0052). Event payloads carry ids only; titles
  * are looked up at delivery time after the read check ([[PolicyNotificationDocuments]]).
  *
  *   - `workflow.task-assigned`: the candidates of a new task, and active deputies of a candidate on their
  *     behalf
  *   - `workflow.task-due-soon`, `workflow.task-overdue`, `workflow.task-escalated` (DMS-87): the candidates,
  *     their deputies when overdue, the escalation targets when escalated
  *   - `document.review-task-created` (DMS-141): the document's owner
  *   - `document.review-task-reopened` (DMS-145, ADR 0061): the document's owner
  *   - `document.review-task-due-soon`, `-overdue`, `-escalated` (DMS-141): the owner, the owner's deputies
  *     when overdue, the escalation targets when escalated
  *   - `document.compliance-notice` (DMS-108, ADR 0077): what a compliance officer sent from the cockpit: the
  *     owner and the owner's deputies, the escalation targets of an escalation
  *   - `workflow.returned`: author and owner (changes requested or rejected); not from the additional
  *     approval of a lower confidentiality, whose decision has a notice of its own
  *   - `document.confidentiality-lowering-requested`, `-decided`, `document.confidentiality-lowered` (ADR
  *     0083): who asked, the author and the owner, without who acted
  *   - `document.owner-replaced` (ADR 0083): the owner a version took the document from
  *   - `workflow.approved`: author and owner, without the approver
  *   - `workflow.participant-missing`: the tenant administrators
  *   - `document.acknowledgement-requested`, `document.acknowledgement-reminder`: the named readers
  *   - `workflow.deputyship-*`: principal and deputy
  *   - `document.status-changed` Approved → Published: the owner
  *   - `document.approved-version-withdrawn`: author, approvers and owner, without who withdrew it; never
  *     readers, for whom nothing changed (DMS-143)
  *   - `legal-hold.created`, `legal-hold.released`: tenant-wide records managers
  *   - `document.rendition-dead`, `document.publication-dead` (DMS-142, ADR 0058): the document's owner
  *     (without owner: the version's author) and their active deputies, the records managers whose role
  *     covers the document (DMS-146, ADR 0064), or the tenant administrators if there are none; of a
  *     publication also those who approved the version, who can withdraw it (DMS-148). Each audience gets the
  *     link it can reach; nothing is sent if the version does not wait any more (rejected, withdrawn,
  *     published in the meantime)
  *   - `document.failure-reminder` (DMS-146, ADR 0064): the same people as far as they have not read a notice
  *     of this failure
  *   - `audit.chain-violated` (DMS-169, ADR 0080): the active compliance officers, whatever their scope, and
  *     the tenant administrators; reminders too, as reading a notice does not close a violation
  */
final class PlatformNotificationEvents(
    documents: DocumentService,
    roles: RoleAssignments,
    holds: LegalHoldService,
    workflow: Option[ApprovalWorkflow] = None,
    groups: Option[Groups] = None
) extends NotificationEvents:
  import PlatformNotificationEvents.*

  def concerns(event: OutboxEvent): Boolean = Types.contains(event.eventType)

  def describe(event: OutboxEvent): IO[String, List[Notice]] =
    ZIO.fromEither(event.payload.value.fromJson[Json.Obj]).flatMap { payload =>
      def text(key: String): Option[String] = payload.get(key).collect { case Json.Str(s) => s }
      def users(key: String): List[UserId]  =
        payload
          .get(key)
          .collect { case Json.Arr(items) => items.toList.collect { case Json.Str(s) => UserId(s) } }
          .getOrElse(Nil)
      val document = text("documentId")
        .flatMap(d => Try(UUID.fromString(d)).toOption)
        .map(d =>
          DocumentRef(DocumentId(d), text("versionId").flatMap(v => Try(UUID.fromString(v)).toOption))
        )
      def params(keys: String*): Map[String, String] = keys.flatMap(k => text(k).map(k -> _)).toMap
      event.eventType match
        case AuditViolation.Event     => chainViolation(params("foundAt", "reminder"))
        case "workflow.task-assigned" =>
          val base = params("version", "stepKind", "taskId") ++ text("stepName").map("step" -> _)
          ZIO.succeed(
            Notice(
              "workflow.task-assigned",
              NotificationCategory.Workflow,
              users("recipients"),
              document,
              base
            ) ::
              deputiesOf(payload).map((deputy, principal) =>
                Notice(
                  "workflow.task-assigned",
                  NotificationCategory.Workflow,
                  List(deputy),
                  document,
                  base + ("actingFor" -> principal)
                )
              )
          )
        case "workflow.task-due-soon" | "workflow.task-overdue" | "workflow.task-escalated" =>
          val base = params("version", "stepKind", "taskId", "dueOn") ++ text("stepName").map("step" -> _)
          ZIO.succeed(
            deadlineNotices(
              event.eventType,
              users("recipients"),
              users("escalatedTo"),
              payload,
              document,
              base
            )
          )
        case "document.review-task-created" =>
          ZIO
            .foreach(document)(ref => owner(ref).map(ref -> _))
            .map(
              _.toList.flatMap((ref, owner) =>
                owner.map(o =>
                  Notice(
                    "review.task-created",
                    NotificationCategory.Workflow,
                    List(o),
                    Some(ref),
                    params("taskId", "dueOn")
                  )
                )
              )
            )
        case ReviewService.Reopened =>
          ZIO
            .foreach(document)(ref => owner(ref).map(ref -> _))
            .map(
              _.toList.flatMap((ref, owner) =>
                owner.map(o =>
                  Notice(
                    "review.task-reopened",
                    NotificationCategory.Workflow,
                    List(o),
                    Some(ref),
                    params("taskId", "dueOn", "revisionStatus")
                  )
                )
              )
            )
        case "document.review-task-due-soon" | "document.review-task-overdue" |
            "document.review-task-escalated" =>
          ZIO.succeed(
            deadlineNotices(
              s"review.${event.eventType.stripPrefix("document.review-")}",
              users("recipients"),
              users("escalatedTo"),
              payload,
              document,
              params("taskId", "dueOn")
            )
          )
        case ComplianceNotices.Event =>
          ZIO.succeed(
            deadlineNotices(
              s"review.compliance-${text("kind").getOrElse(ComplianceNotices.Reminder)}",
              users("recipients"),
              users("escalatedTo"),
              payload,
              document,
              params("dueOn")
            )
          )
        case "workflow.returned" if text("step").contains(WorkflowDefinition.LoweringStep) =>
          ZIO.succeed(Nil)
        case "workflow.returned" =>
          val kind =
            if text("decision").contains("Reject") then "workflow.rejected" else "workflow.changes-requested"
          ZIO.succeed(
            List(
              Notice(
                kind,
                NotificationCategory.Workflow,
                users("recipients"),
                document,
                params("version", "step", "returnedBy", "actingFor")
              )
            )
          )
        case "document.confidentiality-lowering-requested" | "document.confidentiality-lowering-decided" |
            "document.confidentiality-lowered" =>
          val kind = event.eventType match
            case "document.confidentiality-lowered"            => "lowering.in-force"
            case "document.confidentiality-lowering-requested" => "lowering.requested"
            case _ if text("status").contains("Approved")      => "lowering.approved"
            case _                                             => "lowering.refused"
          ZIO.succeed(
            List(
              Notice(
                kind,
                NotificationCategory.Workflow,
                users("recipients"),
                document,
                params("version", "requestedBy", "decidedBy", "actingFor")
              )
            )
          )
        case DocumentService.OwnerReplaced =>
          ZIO.succeed(
            List(
              Notice(
                "publication.owner-replaced",
                NotificationCategory.Publication,
                users("recipients"),
                document,
                params("version", "newOwner")
              )
            )
          )
        case "workflow.approved" =>
          ZIO
            .foreach(document)(ref => authorAndOwner(ref).map(ref -> _))
            .map(_.toList.map { (ref, people) =>
              Notice(
                "workflow.approved",
                NotificationCategory.Workflow,
                people.filterNot(u => text("approvedBy").contains(u.value)),
                Some(ref),
                params("version", "approvedBy", "actingFor")
              )
            })
        case "workflow.participant-missing" =>
          ZIO.succeed(
            List(
              Notice(
                "workflow.participant-missing",
                NotificationCategory.Workflow,
                users("recipients"),
                document,
                params("version", "step", "role")
              )
            )
          )
        case "document.acknowledgement-requested" | "document.acknowledgement-reminder" =>
          val kind =
            if event.eventType.endsWith("requested") then "acknowledgement.requested"
            else "acknowledgement.reminder"
          val round = payload.get("round").collect { case Json.Num(n) => "round" -> n.toString }
          ZIO.succeed(
            List(
              Notice(
                kind,
                NotificationCategory.Acknowledgement,
                users("userIds"),
                document,
                params("version", "kind") ++ round
              )
            )
          )
        case t if t.startsWith("workflow.deputyship-") =>
          ZIO.succeed(
            List(
              Notice(
                s"deputyship.${t.stripPrefix("workflow.deputyship-")}",
                NotificationCategory.Deputyship,
                users("recipients"),
                None,
                params("principal", "deputy", "validFrom", "validUntil")
              )
            )
          )
        case "document.status-changed" =>
          val published = text("status").contains(DocumentStatus.Published.toString) &&
            text("previousStatus").contains(DocumentStatus.Approved.toString)
          if !published then ZIO.succeed(Nil)
          else
            ZIO
              .foreach(document)(ref => owner(ref).map(ref -> _))
              .map(
                _.toList.flatMap((ref, owner) =>
                  owner.map(o =>
                    Notice(
                      "publication.published",
                      NotificationCategory.Publication,
                      List(o),
                      Some(ref),
                      params("version", "validFrom")
                    )
                  )
                )
              )
        case DocumentService.ApprovedVersionWithdrawn =>
          ZIO.succeed(
            List(
              Notice(
                "publication.withdrawn",
                NotificationCategory.Publication,
                users("recipients"),
                document,
                params("version", "validFrom", "withdrawnBy", "actingFor", "reason") ++
                  payload.get("publicationFailed").map(_ => "publicationFailed" -> "true") ++
                  payload.get("renditionFailed").map(_ => "renditionFailed" -> "true")
              )
            )
          )
        case "legal-hold.created" | "legal-hold.released" =>
          val count = payload.get("documentIds").collect { case Json.Arr(items) => items.size }.getOrElse(0)
          for
            managers <- recordsManagers
            name     <- text("name") match
              case Some(n) => ZIO.succeed(Some(n))
              case None    =>
                ZIO
                  .foreach(text("holdId").flatMap(h => Try(UUID.fromString(h)).toOption))(id =>
                    holds.get(LegalHoldId(id)).map(_.name).mapError(_.describe)
                  )
          yield List(
            Notice(
              event.eventType,
              NotificationCategory.LegalHold,
              managers,
              None,
              Map("name" -> name.getOrElse("?"), "documents" -> count.toString)
            )
          )
        case RenditionEvents.Dead | Publication.DeadEvent =>
          val rendition = event.eventType == RenditionEvents.Dead
          ZIO.foreach(document)(failureNotices(event, _, rendition, text("reason"))).map(_.toList.flatten)
        case FailureFollowUps.ReminderEvent =>
          val failedAt  = text("failedAt").flatMap(t => Try(Instant.parse(t)).toOption)
          val rendition = text("jobKind").contains(FailureFollowUps.RenditionJob)
          val reminder  = params("reminder", "failedAt")
          ZIO
            .foreach(document.zip(failedAt)) { (ref, since) =>
              for
                notices <- failureNotices(event, ref, rendition, text("reason"), reminder)
                read    <- ZIO
                  .foreach(ref.versionId)(NotificationStore.readersOf(failureKind(rendition), _, since))
                  .mapBoth(_.toString, _.getOrElse(Set.empty))
              yield notices
                .map(n => n.copy(recipients = n.recipients.filterNot(read)))
                .filter(_.recipients.nonEmpty)
            }
            .map(_.toList.flatten)
        case _ => ZIO.succeed(Nil)
    }

  /** The notices of a deadline: to the people responsible, the escalation targets and the deputies. */
  private def deadlineNotices(
      kind: String,
      recipients: List[UserId],
      escalatedTo: List[UserId],
      payload: Json.Obj,
      document: Option[DocumentRef],
      base: Map[String, String]
  ): List[Notice] =
    Notice(kind, NotificationCategory.Deadline, recipients, document, base) ::
      Notice(kind, NotificationCategory.Deadline, escalatedTo, document, base) ::
      deputiesOf(payload).map((deputy, principal) =>
        Notice(
          kind,
          NotificationCategory.Deadline,
          List(deputy),
          document,
          base + ("actingFor" -> principal)
        )
      )

  /** Who is told that the rendering or publication job of a version is dead, if the version still waits for
    * it. Recipients are looked up now, not when the job was enqueued: the owner may have changed.
    */
  private def failureNotices(
      event: OutboxEvent,
      ref: DocumentRef,
      rendition: Boolean,
      reason: Option[String],
      reminder: Map[String, String] = Map.empty
  ): IO[String, List[Notice]] =
    val kind  = failureKind(rendition)
    val retry = if rendition then Action.RetryRendition else Action.RetryPublication
    details(ref).flatMap { found =>
      val waiting = found.filterNot(_.discarded).flatMap { d =>
        d.versions
          .find(v => ref.versionId.contains(v.id))
          .filter(v =>
            if rendition then v.status == DocumentStatus.InApproval && v.rendition.isEmpty
            else v.status == DocumentStatus.Approved
          )
          .map(d -> _)
      }
      ZIO
        .foreach(waiting) { (d, v) =>
          val responsible = d.document.ownerId.getOrElse(v.createdBy)
          def notice(recipients: List[UserId], extra: (String, String)*) =
            Notice(
              kind,
              NotificationCategory.ProcessingFailure,
              recipients,
              Some(ref),
              Map("version" -> v.label) ++ reason.map("reason" -> _) ++ reminder ++ extra
            )
          for
            cases <- ZIO
              .foreach(workflow)(_.cases(d.document.id).mapError(_.describe))
              .map(_.getOrElse(Nil))
            base = DocumentApi.resource(event.tenant, d, v, DocumentApi.participantsOf(cases))
            now                <- Clock.instant
            (tenantWide, unit) <- recordsManagers(base, now)
            admins             <-
              if tenantWide.isEmpty && unit.isEmpty then tenantAdministrators else ZIO.succeed(Nil)
            approvers = if rendition then Set.empty[UserId] else approversOf(cases, v)
            deputies <- ZIO
              .foreach(workflow)(_.responsibleDeputies(d.document.id, v.id).mapError(_.describe))
              .map(_.getOrElse(Nil))
            resource = base.copy(approvedBy = approvers)
            may      = (subject: Subject, action: Action) =>
              Policy.decide(subject, action, resource, now).isPermitted
            // Someone in two audiences gets the link of the list, which needs no reading permission. A list does
            // not help someone not cleared for the document, so their text says who can retry.
            lists <- ZIO.foreach(
              List(
                NotificationTemplates.Audience.Records -> tenantWide,
                NotificationTemplates.Audience.Scoped  -> unit,
                NotificationTemplates.Audience.Admin   -> admins
              )
            ) { (audience, people) =>
              ZIO.foreach(people)(user => subjectOf(event.tenant, user).map(may(_, retry))).map { cleared =>
                val (can, cannot) = people.zip(cleared).partition(_._2)
                List(
                  notice(can.map(_._1), "audience"    -> audience),
                  notice(cannot.map(_._1), "audience" -> audience, "canRetry" -> "false")
                )
              }
            }
            onList = (tenantWide ++ unit ++ admins).toSet
            // Neither the owner nor an approver is always able to retry or withdraw (both depend on the roles
            // held), so the text must not promise it; the same goes for reading the version on the page.
            personal = (user: UserId, extra: Option[(String, String)]) =>
              for
                subject <- subjectOf(event.tenant, user)
                // A deputy's vote gives what it gives on the page, while the deputyship stands (ADR 0070, 0072).
                asDeputy <- ZIO
                  .foreach(workflow)(Delegation.approvedAsDeputy(roles, _, subject, d, cases))
                  .mapBoth(_.describe, _.getOrElse(Set.empty[UUID]))
                // Withdrawing is the way out if a retry cannot help (ADR 0061, 0078).
                canWithdraw <- ZIO
                  .foreach(workflow)(
                    Delegation.withdrawalRight(roles, _, subject, d, v, cases).map(_.isDefined)
                  )
                  .mapBoth(_.describe, _.getOrElse(may(subject, Action.Withdraw)))
              yield notice(
                List(user),
                List(
                  "audience"    -> NotificationTemplates.Audience.Owner,
                  "canRetry"    -> may(subject, retry).toString,
                  "canRead"     -> (asDeputy(v.id) || may(subject, Action.ReadDocument)).toString,
                  "canWithdraw" -> canWithdraw.toString
                ) ++ extra*
              )
            answerable = (responsible :: approvers.toList.sortBy(_.value)).distinct.filterNot(onList)
            direct  <- ZIO.foreach(answerable)(personal(_, None))
            standIn <- ZIO.foreach(
              deputies.filterNot(t => onList(t.deputy) || answerable.contains(t.deputy))
            )(t => personal(t.deputy, Some("actingFor" -> t.principal.value)))
          yield lists.flatten.filter(_.recipients.nonEmpty) ++ direct ++ standIn
        }
        .map(_.toList.flatten)
    }

  /** Whom a decision is about: their roles and, as a confidential version asks for it, their groups. */
  private def subjectOf(tenant: TenantId, user: UserId): IO[String, Subject] =
    for
      held     <- roles.forUser(user).mapError(_.toString)
      memberOf <- ZIO.foreach(groups)(_.groupsOf(user).mapError(_.toString))
    yield Subject(user, tenant, held, memberOf.getOrElse(Set.empty))

  /** Who approved a version; the final approver is on the version even without the workflow. */
  private def approversOf(cases: List[CaseDetails], v: DocumentVersion): Set[UserId] =
    DocumentApi.approversOf(cases, v.id) ++ v.releasedBy

  /** The `deputies` of a workflow event: deputy and the principal they stand in for. */
  private def deputiesOf(payload: Json.Obj): List[(UserId, String)] =
    payload
      .get("deputies")
      .collect { case Json.Arr(items) =>
        items.toList.flatMap {
          case o: Json.Obj =>
            for
              deputy    <- o.get("deputy").collect { case Json.Str(s) => s }
              principal <- o.get("principal").collect { case Json.Str(s) => s }
            yield (UserId(deputy), principal)
          case _ => None
        }
      }
      .getOrElse(Nil)

  private def details(ref: DocumentRef) =
    documents
      .get(ref.documentId)
      .map(Some(_))
      .catchSome { case DocumentError.NotFound(_) => ZIO.none }
      .mapError(_.describe)

  private def authorAndOwner(ref: DocumentRef): IO[String, List[UserId]] =
    details(ref).map(
      _.toList
        .flatMap { d =>
          d.versions
            .find(v => ref.versionId.contains(v.id))
            .map(_.createdBy)
            .toList ++ d.document.ownerId.toList
        }
        .distinct
    )

  private def owner(ref: DocumentRef): IO[String, Option[UserId]] =
    details(ref).map(_.flatMap(_.document.ownerId))

  /** Records managers for the whole tenant: the people who administer legal holds (ADR 0047). */
  private def recordsManagers: IO[String, List[UserId]] =
    for
      now     <- Clock.instant
      holders <- roles.holders(Role.RecordsManager).mapError(_.toString)
    yield holders.collect {
      case (user, a) if a.isActiveAt(now) && a.organizationalUnit.isEmpty && a.documentClass.isEmpty => user
    }.distinct

  /** The records managers of a document, for the whole tenant and for a unit or class that covers it. */
  private def recordsManagers(resource: Resource, now: Instant): IO[String, (List[UserId], List[UserId])] =
    roles.holders(Role.RecordsManager).mapError(_.toString).map { holders =>
      val covering = holders.filter((user, a) =>
        Policy.rolesInScope(Subject(user, resource.tenantId, Set(a)), resource, now).nonEmpty
      )
      val (tenantWide, restricted) =
        covering.partition((_, a) => a.organizationalUnit.isEmpty && a.documentClass.isEmpty)
      val wide = tenantWide.map(_._1).distinct
      (wide, restricted.map(_._1).distinct.diff(wide))
    }

  /** Who hears of a violation of the audit chain. Each person once: officers have the cockpit, so someone who
    * is both gets their text.
    */
  private def chainViolation(params: Map[String, String]): IO[String, List[Notice]] =
    for
      now     <- Clock.instant
      holders <- roles.holders(Role.ComplianceOfficer).mapError(_.toString)
      admins  <- tenantAdministrators
      officers = holders.collect { case (user, a) if a.isActiveAt(now) => user }.distinct
    yield List(
      NotificationTemplates.ChainAudience.Compliance -> officers,
      NotificationTemplates.ChainAudience.Admin      -> admins.diff(officers)
    ).collect {
      case (audience, people) if people.nonEmpty =>
        Notice(
          NotificationTemplates.ChainViolation,
          NotificationCategory.AuditIntegrity,
          people,
          None,
          params + ("audience" -> audience)
        )
    }

  /** Tenant administrators: told of failures when no records manager can be. */
  private def tenantAdministrators: IO[String, List[UserId]] =
    for
      now     <- Clock.instant
      holders <- roles.holders(Role.TenantAdmin).mapError(_.toString)
    yield holders.collect {
      case (user, a) if a.isActiveAt(now) && a.organizationalUnit.isEmpty && a.documentClass.isEmpty => user
    }.distinct

object PlatformNotificationEvents:

  /** The kind of a failure notice. */
  private def failureKind(rendition: Boolean): String =
    if rendition then "failure.rendition" else "failure.publication"

  val Types: Set[String] = Set(
    "workflow.task-assigned",
    "workflow.task-due-soon",
    "workflow.task-overdue",
    "workflow.task-escalated",
    "document.review-task-created",
    ReviewService.Reopened,
    "document.review-task-due-soon",
    "document.review-task-overdue",
    "document.review-task-escalated",
    ComplianceNotices.Event,
    "workflow.returned",
    "workflow.approved",
    "document.confidentiality-lowering-requested",
    "document.confidentiality-lowering-decided",
    "document.confidentiality-lowered",
    DocumentService.OwnerReplaced,
    "workflow.participant-missing",
    "document.acknowledgement-requested",
    "document.acknowledgement-reminder",
    "workflow.deputyship-created",
    "workflow.deputyship-started",
    "workflow.deputyship-ended",
    "workflow.deputyship-ended-by-period",
    "document.status-changed",
    DocumentService.ApprovedVersionWithdrawn,
    "legal-hold.created",
    "legal-hold.released",
    RenditionEvents.Dead,
    Publication.DeadEvent,
    FailureFollowUps.ReminderEvent,
    AuditViolation.Event
  )

/** The read check at delivery time (DMS-130): the recipient's current role assignments and access group
  * memberships, the workflow participants of the document and deputyships, decided by [[Policy]] like opening
  * the document (`ReadDocument`). A recipient who may not read the version (any version, if none is named)
  * learns no title.
  */
final class PolicyNotificationDocuments(
    documents: DocumentService,
    workflow: ApprovalWorkflow,
    authz: Authorizer,
    roles: RoleAssignments,
    groups: Groups
) extends NotificationDocuments:

  def visibleTo(tenant: TenantId, user: UserId, ref: DocumentRef): IO[String, Option[DocumentFacts]] =
    factsFor(tenant, user, ref, Set(Action.ReadDocument))

  override def visibleForRetry(
      tenant: TenantId,
      user: UserId,
      ref: DocumentRef
  ): IO[String, Option[DocumentFacts]] =
    factsFor(tenant, user, ref, Set(Action.ReadDocument, Action.RetryRendition, Action.RetryPublication))

  private def factsFor(
      tenant: TenantId,
      user: UserId,
      ref: DocumentRef,
      actions: Set[Action]
  ): IO[String, Option[DocumentFacts]] =
    documents
      .get(ref.documentId)
      .map(Some(_))
      .catchSome { case DocumentError.NotFound(_) => ZIO.none }
      .mapError(_.describe)
      .flatMap {
        case None                   => ZIO.none
        case Some(d) if d.discarded => ZIO.none
        case Some(details)          =>
          for
            held     <- roles.forUser(user).mapError(_.toString)
            memberOf <- groups.groupsOf(user).mapError(_.toString)
            cases    <- workflow.cases(details.document.id).mapError(_.toString)
            actor    <- Delegation
              .subject(authz, Subject(user, tenant, held, memberOf), cases.flatMap(_.tasks))
              .mapError(_.toString)
            asDeputy <- Delegation
              .approvedAsDeputy(roles, workflow, Subject(user, tenant, held, memberOf), details, cases)
              .mapError(_.toString)
            now <- Clock.instant
            participants = DocumentApi.participantsOf(cases)
            candidates   = details.versions.filter(v => ref.versionId.forall(_ == v.id))
            readable     = candidates.filter { v =>
              val resource = DocumentApi.resource(tenant, details, v, participants)
              asDeputy(v.id) || actions.exists(Policy.decide(actor, _, resource, now).isPermitted)
            }
          yield readable.lastOption.map(v =>
            DocumentFacts(DocumentRenditions.title(details, v), Some(v.label), details.document.number)
          )
      }

/** Names and email addresses from the user directory (ADR 0037), filtered by the tenant's organization. */
final class DirectoryRecipients(directory: UserDirectory, organizations: TenantOrganizations)
    extends RecipientDirectory:

  def people(tenant: TenantId, users: Set[UserId]): IO[String, Map[UserId, Person]] =
    if users.isEmpty then ZIO.succeed(Map.empty)
    else
      organizations
        .organizationOf(tenant)
        .mapError(_.toString)
        .flatMap {
          case None      => ZIO.succeed(Map.empty)
          case Some(org) =>
            directory
              .lookup(org, users)
              .mapError(_.toString)
              .map(_.map((id, info) => id -> Person(Some(info.displayName), info.email)))
        }

/** The notification delivery of the platform (DMS-130): events, read check, user directory, SMTP. */
object PlatformNotifications:
  val layer: ZLayer[
    Db & DocumentService & RoleAssignments & Groups & Authorizer & LegalHoldService & ApprovalWorkflow &
      UserDirectory & TenantOrganizations & DeliverySettings & SmtpConfig,
    Nothing,
    NotificationJobs
  ] =
    ZLayer.fromZIO(
      for
        db        <- ZIO.service[Db]
        documents <- ZIO.service[DocumentService]
        roles     <- ZIO.service[RoleAssignments]
        groups    <- ZIO.service[Groups]
        authz     <- ZIO.service[Authorizer]
        holds     <- ZIO.service[LegalHoldService]
        workflow  <- ZIO.service[ApprovalWorkflow]
        directory <- ZIO.service[UserDirectory]
        orgs      <- ZIO.service[TenantOrganizations]
        settings  <- ZIO.service[DeliverySettings]
        smtp      <- ZIO.service[SmtpConfig]
        _         <- ZIO
          .logWarning("Notifications: no SMTP server configured (dms.notifications.smtp.host); in-app only")
          .unless(smtp.enabled)
      yield NotificationJobs(
        db,
        PlatformNotificationEvents(documents, roles, holds, Some(workflow), Some(groups)),
        PolicyNotificationDocuments(documents, workflow, authz, roles, groups),
        DirectoryRecipients(directory, orgs),
        SmtpMailSender(smtp),
        settings
      )
    )
