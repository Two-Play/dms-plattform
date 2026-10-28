package dms.workflow.core

import dms.db.DbError
import dms.documents.{ChangeKind, DocumentStatus, LifecycleEvent}
import dms.kernel.{DocumentId, UserId}
import zio.*

import java.time.{Duration, Instant}
import java.util.UUID

enum WorkflowError:
  case NotFound(message: String)
  case Invalid(problems: List[String])

  /** The caller may not act on this task, e.g. not a candidate or the author under the four-eyes rule. */
  case Forbidden(message: String)

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
    changeKind: ChangeKind = ChangeKind.Major
)

/** Port to the documents module, implemented by the application (module rule: `workflow-core` depends on
  * `documents-api` only). Runs inside the caller's transaction.
  */
trait DocumentGateway:
  def subject(documentId: DocumentId, versionId: UUID): IO[WorkflowError, ReviewSubject]

  /** Applies a lifecycle event to the version as `actor` (`ReviewPassed`, `Approve`, `RequestChanges`,
    * `Reject`).
    */
  def advance(
      documentId: DocumentId,
      versionId: UUID,
      event: LifecycleEvent,
      actor: UserId
  ): IO[WorkflowError, ReviewSubject]

/** Resolves participants from role assignments (business concept 6.1: from roles, not from named persons). */
trait ParticipantDirectory:
  /** People who hold `role` with a scope covering the subject (organizational unit, document class), sorted.
    */
  def holders(role: String, subject: ReviewSubject): IO[WorkflowError, List[UserId]]

  /** Tenant administrators to notify when a step has no eligible participant. */
  def tenantAdmins: IO[WorkflowError, List[UserId]]

/** Produces the archive rendition before a version is approved (DMS-44, ADR 0025): the database requires a
  * rendition for `Approved` (ADR 0021). Called inside the transaction of the last approving vote; the
  * application enqueues an idempotent rendering job, which stores the rendition, attaches it and then calls
  * [[ApprovalWorkflow.completeApproval]]. A failure rolls back the vote.
  */
trait ApprovalRendition:
  def request(
      caseId: UUID,
      subject: ReviewSubject,
      approver: UserId,
      approvedAt: Instant,
      authenticatedAt: Instant
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
final case class WorkflowConfig(reauthenticationWindow: Duration)

object WorkflowConfig:
  val default: WorkflowConfig = WorkflowConfig(Duration.ofMinutes(5))
