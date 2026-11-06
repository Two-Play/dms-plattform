package dms.workflow.core

import dms.db.DbError
import dms.documents.{ChangeKind, DocumentStatus, LifecycleEvent}
import dms.kernel.{DocumentId, UserId}
import dms.workflow.DeadlinePolicy
import zio.*

import java.time.{Duration, Instant}
import java.util.UUID

enum WorkflowError:
  case NotFound(message: String)
  case Invalid(problems: List[String])

  /** The caller may not act on this task, e.g. not a candidate or the author under the four-eyes rule. */
  case Forbidden(message: String)

  /** The four-eyes rule refuses the caller: they created or changed the version (DMS-159, ADR 0075). */
  case FourEyes(message: String)

  /** An approval vote needs a sign-in within the configured window (step-up, DMS-41). */
  case ReauthenticationRequired

  case Conflict(message: String)

  /** The documents module refused a status change, e.g. because required fields are missing. */
  case Document(code: String, message: String)

  case Database(error: DbError)

  /** Unexpected failure of the workflow engine. */
  case Engine(message: String)

  def describe: String = this match
    case NotFound(m)              => m
    case Invalid(p)               => p.mkString("; ")
    case Forbidden(m)             => m
    case FourEyes(m)              => m
    case ReauthenticationRequired => "sign in again to confirm this approval"
    case Conflict(m)              => m
    case Document(_, m)           => m
    case Database(e)              => s"database error: $e"
    case Engine(m)                => s"workflow engine error: $m"

object WorkflowError:
  /** Carries a typed error through Flowable's blocking calls. */
  final class Failure(val error: WorkflowError) extends RuntimeException(error.describe, null, false, false)

/** What the workflow needs to know about the version under review.
  *
  * @param classPath
  *   class keys from the root to the document's class; the nearest class with a workflow wins
  * @param contentSha256
  *   SHA-256 over the canonical content and field values of the version
  * @param changeKind
  *   minor (editorial) versions take the shortened path [[dms.workflow.WorkflowDefinition.editorial]]
  *   (DMS-46)
  * @param accessGroups
  *   `None` for a version everyone with a role in scope may see; for a confidential version (DMS-101, ADR
  *   0032) the access groups whose members may see it besides author and owner. Only those people take part
  *   in its workflow, see [[ParticipantDirectory.cleared]].
  * @param editors
  *   everyone who changed the version while it was a draft, in any round (DMS-159, ADR 0075)
  */
final case class ReviewSubject(
    documentId: DocumentId,
    versionId: UUID,
    versionLabel: String,
    status: DocumentStatus,
    classPath: List[String],
    organizationalUnit: Option[String],
    author: UserId,
    owner: Option[UserId],
    contentSha256: String,
    changeKind: ChangeKind = ChangeKind.Major,
    accessGroups: Option[Set[String]] = None,
    editors: Set[UserId] = Set.empty
):
  /** Who counts as an author for the four-eyes rule: whoever started this version and whoever changed it. */
  def authors: Set[UserId] = editors + author

/** Port to the documents module, implemented by the application (module rule: `workflow-core` depends on
  * `documents-api` only). Runs inside the caller's transaction.
  */
trait DocumentGateway:
  def subject(documentId: DocumentId, versionId: UUID): IO[WorkflowError, ReviewSubject]

  /** [[subject]] for several versions at once, by version id, for lists (DMS-138); unknown ones are left out.
    */
  def subjects(versions: List[(DocumentId, UUID)]): IO[WorkflowError, Map[UUID, ReviewSubject]]

  /** The people who changed the given versions, by version id; cheap enough for a check over all open tasks.
    */
  def editors(versions: List[UUID]): IO[WorkflowError, Map[UUID, Set[UserId]]]

  /** Applies a lifecycle event to the version as `actor` (`ReviewPassed`, `Approve`, `RequestChanges`,
    * `Reject`).
    */
  def advance(
      documentId: DocumentId,
      versionId: UUID,
      event: LifecycleEvent,
      actor: UserId
  ): IO[WorkflowError, ReviewSubject]

/** A role assignment of a person as the workflow sees it: role name, scope (organizational unit, document
  * class subtree; empty means the whole tenant) and validity. Used to check that a deputy has the same
  * authority as the principal (DMS-49).
  */
final case class HeldRole(
    role: String,
    organizationalUnit: Option[String],
    documentClass: Option[String],
    validFrom: Instant,
    validUntil: Option[Instant]
):
  def isActiveAt(now: Instant): Boolean = !validFrom.isAfter(now) && validUntil.forall(now.isBefore)

/** Resolves participants from role assignments (business concept 6.1: from roles, not from named persons). */
trait ParticipantDirectory:
  /** People who hold `role` with a scope covering the subject (organizational unit, document class) and who
    * may see it ([[cleared]]), sorted.
    */
  def holders(role: String, subject: ReviewSubject): IO[WorkflowError, List[UserId]]

  /** Those of `users` who may see the subject: everyone for a version that is not confidential; for a
    * confidential one its author, its owner and the current members of its access groups (DMS-101).
    */
  def cleared(users: Set[UserId], subject: ReviewSubject): IO[WorkflowError, Set[UserId]]

  /** [[cleared]] for several subjects at once, by version id, for lists (DMS-138). */
  def clearedFor(
      users: Set[UserId],
      subjects: List[ReviewSubject]
  ): IO[WorkflowError, Map[UUID, Set[UserId]]]

  /** Tenant administrators to notify when a step has no eligible participant. */
  def tenantAdmins: IO[WorkflowError, List[UserId]]

  /** All role assignments of `user`, including expired and future ones (callers check the validity). */
  def heldRoles(user: UserId): IO[WorkflowError, List[HeldRole]]

  /** [[heldRoles]] for several people at once, for lists (DMS-138); people without any are left out. */
  def heldRolesOf(users: Set[UserId]): IO[WorkflowError, Map[UserId, List[HeldRole]]]

/** Who hears of a deadline: the responsible people, their deputies and the escalation targets (without the
  * responsible people).
  */
final case class DeadlineAudience(
    recipients: List[UserId],
    deputies: List[dms.workflow.TaskDeputy],
    escalatedTo: List[UserId]
)

/** Produces the archive rendition before a version is approved (DMS-44, ADR 0025): the database requires a
  * rendition for `Approved` (ADR 0021). Called inside the transaction of the last approving vote; the
  * application enqueues an idempotent rendering job, which stores the rendition, attaches it and then calls
  * [[ApprovalWorkflow.completeApproval]]. A failure rolls back the vote.
  */
trait ApprovalRendition:
  /** @param onBehalfOf
    *   the approver the deputy `approver` stood in for (DMS-49), shown in the approval note
    */
  def request(
      caseId: UUID,
      subject: ReviewSubject,
      approver: UserId,
      approvedAt: Instant,
      authenticatedAt: Instant,
      onBehalfOf: Option[UserId]
  ): IO[WorkflowError, Unit]

/** Whether a version needs the extra review step (deviations from locked standard text, DMS-79). */
trait ExtraReviewCheck:
  def required(documentId: DocumentId, versionId: UUID): IO[WorkflowError, Boolean]

object ExtraReviewCheck:
  /** No version needs the extra step (tests, classes without deviation handling). */
  val never: ExtraReviewCheck = (_: DocumentId, _: UUID) => ZIO.succeed(false)

/** @param reauthenticationWindow
  *   how recent the sign-in (`auth_time`) must be for an approval vote
  */
/** @param deadlines
  *   the installation's deadline policy where neither the workflow nor the tenant sets one (DMS-87)
  */
final case class WorkflowConfig(
    reauthenticationWindow: Duration,
    deadlines: DeadlinePolicy = DeadlinePolicy.default
)

object WorkflowConfig:
  val default: WorkflowConfig = WorkflowConfig(Duration.ofMinutes(5))
