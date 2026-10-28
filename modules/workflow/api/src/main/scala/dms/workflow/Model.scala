package dms.workflow

import dms.kernel.{DocumentId, UserId}

import java.time.Instant
import java.util.UUID

/** A stored version of the workflow of a document class. Versions are never changed; a running case keeps the
  * definition it started with.
  *
  * @param fourEyes
  *   the author of a version is never a participant of its review or approval (business concept 2.4, DMS-42)
  * @param reason
  *   why the four-eyes rule is disabled; required then
  */
final case class StoredDefinition(
    classKey: String,
    version: Int,
    definition: WorkflowDefinition,
    fourEyes: Boolean,
    reason: Option[String],
    createdAt: Instant,
    createdBy: UserId
)

enum CaseStatus:
  /** A round is running: tasks are open or waiting for participants. */
  case Running

  /** A vote returned the version to its author (changes requested or rejected); waits for resubmission. */
  case Returned

  /** All steps approved; the version is approved. */
  case Approved

/** The review and approval of one document version. Every submission starts a round; all rounds of a case run
  * on the same workflow definition (business concept 6.1: a running case stays on its workflow version).
  *
  * @param definitionVersion
  *   the stored definition of the class, `None` for the built-in default
  * @param contentSha256
  *   SHA-256 of the content under review in the current round; votes are bound to it (DMS-41)
  * @param returnedStep
  *   the step whose vote returned the version, while `Returned`
  */
final case class WorkflowCase(
    id: UUID,
    documentId: DocumentId,
    versionId: UUID,
    classKey: String,
    definitionVersion: Option[Int],
    definition: WorkflowDefinition,
    fourEyes: Boolean,
    round: Int,
    status: CaseStatus,
    currentStep: Option[String],
    returnedStep: Option[String],
    contentSha256: String,
    author: UserId,
    startedBy: UserId,
    startedAt: Instant,
    updatedAt: Instant
)

enum TaskStatus:
  case Open

  /** Nobody is eligible (e.g. only the author holds the role); the tenant administrators were notified. */
  case Waiting
  case Completed

  /** Another lane of the step returned the version. */
  case Cancelled

/** A task of a step lane. `candidates` are the people who may complete it. */
final case class WorkflowTask(
    id: String,
    caseId: UUID,
    documentId: DocumentId,
    versionId: UUID,
    round: Int,
    step: String,
    stepName: String,
    kind: StepKind,
    lane: String,
    role: String,
    candidates: List[UserId],
    status: TaskStatus,
    createdAt: Instant,
    completedAt: Option[Instant],
    completedBy: Option[UserId]
)

/** A cast vote, bound to the version and the SHA-256 of its content. It becomes invalid when the content
  * changes afterwards.
  *
  * @param authenticatedAt
  *   the voter's sign-in time from the token (`auth_time`); checked for approval steps
  */
final case class Vote(
    id: UUID,
    caseId: UUID,
    taskId: String,
    versionId: UUID,
    round: Int,
    step: String,
    lane: String,
    voter: UserId,
    decision: VoteDecision,
    reason: Option[String],
    contentSha256: String,
    authenticatedAt: Option[Instant],
    votedAt: Instant,
    invalidatedAt: Option[Instant]
)

/** The approval of a version: who approved which exact version and content, after which sign-in. */
final case class Approval(
    id: UUID,
    caseId: UUID,
    versionId: UUID,
    versionLabel: String,
    contentSha256: String,
    approvedBy: UserId,
    authenticatedAt: Instant,
    approvedAt: Instant
)

/** A case with its tasks, votes and approval. */
final case class CaseDetails(
    workflowCase: WorkflowCase,
    tasks: List[WorkflowTask],
    votes: List[Vote],
    approval: Option[Approval]
)
