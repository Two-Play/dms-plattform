package dms.app

import dms.archive.LegalHoldId
import dms.archive.core.LegalHoldService
import dms.authz.{Action, Authorizer, Groups, Policy, Role, RoleAssignments, Subject}
import dms.db.Db
import dms.db.queue.OutboxEvent
import dms.documents.DocumentStatus
import dms.documents.core.{DocumentError, DocumentService}
import dms.identity.{TenantOrganizations, UserDirectory}
import dms.kernel.{DocumentId, TenantId, UserId}
import dms.notifications.*
import dms.notifications.core.{DeliverySettings, NotificationJobs, SmtpConfig, SmtpMailSender}
import dms.workflow.core.ApprovalWorkflow
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.util.UUID
import scala.util.Try

/** What the platform's outbox events mean for whom (DMS-130, ADR 0052). Event payloads carry ids only; titles
  * are looked up at delivery time after the read check ([[PolicyNotificationDocuments]]).
  *
  *   - `workflow.task-assigned`: the candidates of a new task, and active deputies of a candidate on their
  *     behalf
  *   - `workflow.returned`: author and owner (changes requested or rejected)
  *   - `workflow.approved`: author and owner, without the approver
  *   - `workflow.participant-missing`: the tenant administrators
  *   - `document.acknowledgement-requested`, `document.acknowledgement-reminder`: the named readers
  *   - `workflow.deputyship-*`: principal and deputy
  *   - `document.status-changed` Approved → Published: the owner
  *   - `legal-hold.created`, `legal-hold.released`: tenant-wide records managers
  */
final class PlatformNotificationEvents(
    documents: DocumentService,
    roles: RoleAssignments,
    holds: LegalHoldService
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
        case "workflow.task-assigned" =>
          val base     = params("version", "stepKind", "taskId") ++ text("stepName").map("step" -> _)
          val deputies = payload.get("deputies").collect { case Json.Arr(items) =>
            items.toList.flatMap {
              case o: Json.Obj =>
                for
                  deputy    <- o.get("deputy").collect { case Json.Str(s) => s }
                  principal <- o.get("principal").collect { case Json.Str(s) => s }
                yield (UserId(deputy), principal)
              case _ => None
            }
          }
          ZIO.succeed(
            Notice(
              "workflow.task-assigned",
              NotificationCategory.Workflow,
              users("recipients"),
              document,
              base
            ) ::
              deputies
                .getOrElse(Nil)
                .map((deputy, principal) =>
                  Notice(
                    "workflow.task-assigned",
                    NotificationCategory.Workflow,
                    List(deputy),
                    document,
                    base + ("actingFor" -> principal)
                  )
                )
          )
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
        case _ => ZIO.succeed(Nil)
    }

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

object PlatformNotificationEvents:
  val Types: Set[String] = Set(
    "workflow.task-assigned",
    "workflow.returned",
    "workflow.approved",
    "workflow.participant-missing",
    "document.acknowledgement-requested",
    "document.acknowledgement-reminder",
    "workflow.deputyship-created",
    "workflow.deputyship-started",
    "workflow.deputyship-ended",
    "workflow.deputyship-ended-by-period",
    "document.status-changed",
    "legal-hold.created",
    "legal-hold.released"
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
            now <- Clock.instant
            participants = DocumentApi.participantsOf(cases)
            candidates   = details.versions.filter(v => ref.versionId.forall(_ == v.id))
            readable     = candidates.filter(v =>
              Policy
                .decide(
                  actor,
                  Action.ReadDocument,
                  DocumentApi.resource(tenant, details, v, participants),
                  now
                )
                .isPermitted
            )
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
        PlatformNotificationEvents(documents, roles, holds),
        PolicyNotificationDocuments(documents, workflow, authz, roles, groups),
        DirectoryRecipients(directory, orgs),
        SmtpMailSender(smtp),
        settings
      )
    )
