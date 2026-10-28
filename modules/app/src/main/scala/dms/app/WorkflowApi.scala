package dms.app

import dms.authz.{AccessDenied, Action, Authorizer, ConfigurationArea, Policy, Resource, Role, Subject}
import dms.db.{Db, DbError}
import dms.documents.core.{DocumentDetails, DocumentService}
import dms.identity.{Authenticator, Principal}
import dms.kernel.DocumentId
import dms.workflow.*
import dms.workflow.core.{ApprovalWorkflow, WorkflowError}
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.time.Instant
import java.util.UUID

final case class LaneView(key: String, role: String) derives JsonCodec, Schema

/** `kind`: Review or Approval; `when`: Always or ExtraReviewRequired. */
final case class StepView(
    key: String,
    name: String,
    kind: String,
    lanes: List[LaneView],
    when: Option[String]
) derives JsonCodec,
      Schema

final case class WorkflowDefinitionView(steps: List[StepView], resetTo: Option[String])
    derives JsonCodec,
      Schema

final case class DefineWorkflow(
    definition: WorkflowDefinitionView,
    /** Default true. Disabling the four-eyes rule needs a reason and is audited. */
    fourEyes: Option[Boolean],
    reason: Option[String]
) derives JsonCodec,
      Schema

final case class StoredWorkflowView(
    classKey: String,
    version: Int,
    definition: WorkflowDefinitionView,
    fourEyes: Boolean,
    reason: Option[String],
    createdAt: Instant,
    createdBy: String
) derives JsonCodec,
      Schema

/** A deputy who may complete an open task in place of `principal`, one of its candidates (DMS-49). */
final case class TaskDeputyView(deputy: String, principal: String) derives JsonCodec, Schema

final case class TaskView(
    id: String,
    caseId: UUID,
    documentId: UUID,
    versionId: UUID,
    round: Int,
    step: String,
    stepName: String,
    /** Review or Approval; approval votes need a recent sign-in. */
    kind: String,
    lane: String,
    role: String,
    candidates: List[String],
    /** Open, Waiting (nobody eligible), Completed or Cancelled. */
    status: String,
    createdAt: Instant,
    completedAt: Option[Instant],
    completedBy: Option[String],
    /** Deputies of candidates who may complete the open task now (active deputyship, DMS-49). */
    deputies: List[TaskDeputyView] = Nil,
    /** In the caller's task inbox: the candidate the caller stands in for as deputy (UI: "X i. V. für Y");
      * empty if the caller is a candidate.
      */
    actingFor: Option[String] = None
) derives JsonCodec,
      Schema

final case class VoteView(
    id: UUID,
    taskId: String,
    round: Int,
    step: String,
    lane: String,
    voter: String,
    decision: String,
    reason: Option[String],
    contentSha256: String,
    authenticatedAt: Option[Instant],
    votedAt: Instant,
    /** Set when the content changed after the vote (DMS-41). */
    invalidatedAt: Option[Instant],
    /** The participant the voter stood in for as deputy: "voter on behalf of actingFor" (DMS-49). */
    actingFor: Option[String] = None
) derives JsonCodec,
      Schema

final case class ApprovalView(
    versionId: UUID,
    version: String,
    contentSha256: String,
    approvedBy: String,
    authenticatedAt: Instant,
    approvedAt: Instant,
    /** The approver the deputy `approvedBy` stood in for (DMS-49). */
    actingFor: Option[String] = None
) derives JsonCodec,
      Schema

final case class WorkflowCaseView(
    id: UUID,
    documentId: UUID,
    versionId: UUID,
    classKey: String,
    /** Empty: the built-in default workflow. */
    definitionVersion: Option[Int],
    fourEyes: Boolean,
    round: Int,
    /** Running, Returned, Rendering (all steps approved, the archive rendition is being produced) or
      * Approved.
      */
    status: String,
    currentStep: Option[String],
    returnedStep: Option[String],
    contentSha256: String,
    tasks: List[TaskView],
    votes: List[VoteView],
    approval: Option[ApprovalView]
) derives JsonCodec,
      Schema

final case class CastVote(
    /** Approve, ApproveWithConditions, RequestChanges or Reject. */
    decision: String,
    /** Required for everything except Approve. */
    reason: Option[String]
) derives JsonCodec,
      Schema

/** The caller's subject for the document of workflow tasks they may complete as deputy (DMS-49, ADR 0028):
  * extended by the principals' assignments of the tasks' roles, so the deputy reads and votes within the
  * principal's scope. Unchanged for everyone else; never used beyond the document of `tasks`.
  */
private[app] object Delegation:
  def subject(authz: Authorizer, subject: Subject, tasks: List[WorkflowTask]): IO[WorkflowError, Subject] =
    val delegated = tasks
      .flatMap(t => t.deputies.filter(_.deputy == subject.userId).map(d => (d.principal, t.role)))
      .flatMap((principal, role) => scala.util.Try(Role.valueOf(role)).toOption.map(principal -> _))
      .groupMap(_._1)(_._2)
      .toList
      .sortBy(_._1.value)
    ZIO
      .foldLeft(delegated)(subject) { case (s, (principal, roles)) =>
        authz.onBehalfOf(s, principal, roles.toSet)
      }
      .mapError(WorkflowError.Database(_))

/** Review and approval workflows (business concept 6.1; DMS-40 to DMS-42).
  *
  * Task inbox and votes for participants (authorization: `Review` for review steps, `Approve` for approval
  * steps, both only for the task's participants; approval votes additionally need a sign-in within
  * `dms.workflow.reauthenticationSeconds`, read from the `auth_time` of the ID token in header
  * `X-DMS-Id-Token` or of the access token, DMS-86); the workflow cases of a document for everyone who may
  * read it; workflow definitions per document class for tenant administrators (`Configure` on `Workflows`).
  */
object WorkflowApi:
  type Env = Authenticator & Db & Authorizer & DocumentService & ApprovalWorkflow

  private val tasks  = "api" / "v1" / "workflow" / "tasks"
  private val taskId = path[String]("taskId").description("Task id")

  val myTasks: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(tasks)
      .out(jsonBody[List[TaskView]])
      .description(
        "The open review and approval tasks of the caller, oldest first, including the tasks of people the " +
          "caller currently stands in for as deputy (actingFor names them)"
      )
      .tag("Workflow")
      .serverLogic(p => _ => inTransaction(p)(ctx => ctx.workflow.myTasks(p.userId).map(_.map(taskView))))

  val vote: ZServerEndpoint[Env, Any] =
    SecuredApi.stepUp.post
      .in(tasks / taskId / "votes")
      .in(jsonBody[CastVote])
      .out(jsonBody[WorkflowCaseView])
      .description(
        "Casts a vote on a task. A reason is required except for Approve (422). Approval votes need a recent " +
          s"sign-in, proven by the OIDC ID token in header ${SecuredApi.IdTokenHeader} (401 " +
          "reauthentication_required: sign in again with OIDC max_age=0 or prompt=login and repeat the vote " +
          "with the new ID token). " +
          "RequestChanges and Reject return the version to its author."
      )
      .tag("Workflow")
      .serverLogic(p =>
        (id, req) =>
          inTransaction(p) { ctx =>
            for
              decision <- ZIO
                .attempt(VoteDecision.valueOf(req.decision))
                .orElseFail(WorkflowError.Invalid(List(s"unknown decision ${req.decision}")))
              task    <- ctx.workflow.task(id)
              details <- ctx.documents.get(task.documentId)
              version <- ZIO
                .fromOption(details.versions.find(_.id == task.versionId))
                .orElseFail(WorkflowError.NotFound(s"document version ${task.versionId} does not exist"))
              action = if task.kind == StepKind.Approval then Action.Approve else Action.Review
              _ <- ctx.checkFor(
                List(task),
                action,
                DocumentApi
                  .resource(p.tenantId, details, version)
                  .copy(
                    // Deputies of candidates during an active deputyship (DMS-49).
                    assignedParticipants = task.candidates.toSet ++ task.deputies.map(_.deputy),
                    // The owner confirms editorial (minor) versions in the shortened path (DMS-46).
                    ownerMayApprove = task.role == WorkflowDefinition.DocumentOwner
                  )
              )
              result <- ctx.workflow.vote(id, decision, req.reason, p.userId, p.authenticatedAt)
            yield caseView(result)
          }.mapError(SecuredApi.explainReauthentication(p))
      )

  val resolveParticipants: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(tasks / taskId / "participants")
      .out(jsonBody[TaskView])
      .description(
        "Resolves the participants of an open or waiting task again, e.g. after the missing role was assigned. " +
          "Tenant administrators only."
      )
      .tag("Workflow")
      .serverLogic(p =>
        id =>
          inTransaction(p) { ctx =>
            ctx.configure(p) *> ctx.workflow.resolveParticipants(id, p.userId).map(taskView)
          }
      )

  val documentCases: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "documents" / path[UUID]("id").description("Document id") / "workflow")
      .out(jsonBody[List[WorkflowCaseView]])
      .description("The review and approval cases of the versions of a document the caller may read")
      .tag("Workflow")
      .serverLogic(p =>
        id =>
          inTransaction(p) { ctx =>
            for
              details  <- ctx.documents.get(DocumentId(id))
              cases    <- ctx.workflow.cases(DocumentId(id))
              readable <- ctx.readableVersions(details, cases)
            yield cases.filter(c => readable.contains(c.workflowCase.versionId)).map(caseView)
          }
      )

  private val definitions = "api" / "v1" / "admin" / "workflows" / path[String]("classKey")
    .description("Key of the document class")

  val listDefinitions: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(definitions)
      .out(jsonBody[List[StoredWorkflowView]])
      .description("All versions of the workflow of a document class, oldest first")
      .tag("Workflow")
      .serverLogic(p =>
        key =>
          inTransaction(p)(ctx => ctx.configure(p) *> ctx.workflow.definitions(key).map(_.map(storedView)))
      )

  val define: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(definitions)
      .in(jsonBody[DefineWorkflow])
      .out(statusCode(StatusCode.Created).and(jsonBody[StoredWorkflowView]))
      .description(
        "Stores the next version of the workflow of a document class. Running cases keep their version. " +
          "Lanes name a business role; approval steps use the Approver role. Disabling the four-eyes rule " +
          "needs a reason and is recorded in the audit trail."
      )
      .tag("Workflow")
      .serverLogic(p =>
        (key, req) =>
          inTransaction(p) { ctx =>
            for
              _          <- ctx.configure(p)
              definition <- ZIO.fromEither(fromView(req.definition)).mapError(WorkflowError.Invalid(_))
              stored     <- ctx.workflow
                .define(key, definition, req.fourEyes.getOrElse(true), req.reason, p.userId)
            yield storedView(stored)
          }
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] =
    List(myTasks, vote, resolveParticipants, documentCases, listDefinitions, define)

  private final class Context(
      val workflow: ApprovalWorkflow,
      service: DocumentService,
      authz: Authorizer,
      subject: Subject
  ):
    object documents:
      def get(id: DocumentId): IO[WorkflowError, DocumentDetails] =
        service.get(id).mapError(WorkflowDocuments.toWorkflowError)

    def check(action: Action, resource: Resource): IO[AccessDenied, Unit] =
      authz.check(subject, action, resource).unit

    /** Checks as deputy where the caller may complete one of `tasks` as such (DMS-49). */
    def checkFor(
        tasks: List[WorkflowTask],
        action: Action,
        resource: Resource
    ): IO[WorkflowError | AccessDenied, Unit] =
      Delegation.subject(authz, subject, tasks).flatMap(s => authz.check(s, action, resource).unit)

    def configure(p: Principal): IO[AccessDenied, Unit] =
      check(Action.Configure, Resource(p.tenantId, configurationArea = Some(ConfigurationArea.Workflows)))

    /** Ids of the versions the caller may read; denied if there is none. */
    def readableVersions(
        details: DocumentDetails,
        cases: List[CaseDetails]
    ): IO[WorkflowError | AccessDenied, Set[UUID]] =
      val participants = DocumentApi.participantsOf(cases)
      for
        now   <- Clock.instant
        actor <- Delegation.subject(authz, subject, cases.flatMap(_.tasks))
        visible = details.versions.filter(v =>
          Policy
            .decide(
              actor,
              Action.ReadDocument,
              DocumentApi.resource(subject.tenantId, details, v, participants),
              now
            )
            .isPermitted
        )
        _ <- ZIO.when(visible.isEmpty)(
          details.versions.lastOption.fold(ZIO.unit)(v =>
            authz
              .check(
                actor,
                Action.ReadDocument,
                DocumentApi.resource(subject.tenantId, details, v, participants)
              )
              .unit
          )
        )
      yield visible.map(_.id).toSet

  private def inTransaction[A](principal: Principal)(
      f: Context => ZIO[Any, WorkflowError | AccessDenied, A]
  ): ZIO[Db & Authorizer & DocumentService & ApprovalWorkflow, (StatusCode, ApiError), A] =
    (for
      db       <- ZIO.service[Db]
      authz    <- ZIO.service[Authorizer]
      service  <- ZIO.service[DocumentService]
      workflow <- ZIO.service[ApprovalWorkflow]
      result   <- db.transact(principal.tenantId)(
        authz.subject(principal).flatMap(subject => f(Context(workflow, service, authz, subject)))
      )
    yield result).mapError(toResponse)

  /** Lane roles must be business roles; approval steps need approvers (the policy grants `Approve` to them).
    */
  def fromView(v: WorkflowDefinitionView): Either[List[String], WorkflowDefinition] =
    val parsed = v.steps.map { s =>
      for
        kind <- parse(StepKind.valueOf, s.kind, s"unknown step kind ${s.kind} in step ${s.key}")
        when <- s.when.fold(Right(StepCondition.Always))(w =>
          parse(StepCondition.valueOf, w, s"unknown condition $w in step ${s.key}")
        )
        lanes <- s.lanes.foldLeft[Either[String, List[Lane]]](Right(Nil)) { (acc, l) =>
          acc.flatMap { done =>
            parse(Role.valueOf, l.role, s"unknown role ${l.role} in lane ${l.key}").flatMap { role =>
              if role == Role.TenantAdmin || role == Role.Reader || role == Role.TemplateDesigner ||
                role == Role.RecordsManager || role == Role.Auditor
              then Left(s"role ${l.role} cannot take part in reviews (lane ${l.key})")
              else if kind == StepKind.Approval && role != Role.Approver then
                Left(s"approval step ${s.key} needs the Approver role (lane ${l.key})")
              else Right(done :+ Lane(l.key, role.toString))
            }
          }
        }
      yield Step(s.key, s.name, kind, lanes, when)
    }
    val problems = parsed.collect { case Left(p) => p }
    if problems.nonEmpty then Left(problems)
    else Right(WorkflowDefinition(parsed.collect { case Right(s) => s }, v.resetTo))

  private def parse[A](f: String => A, value: String, problem: String): Either[String, A] =
    try Right(f(value))
    catch case _: IllegalArgumentException => Left(problem)

  private def definitionView(d: WorkflowDefinition): WorkflowDefinitionView =
    WorkflowDefinitionView(
      d.steps.map(s =>
        StepView(
          s.key,
          s.name,
          s.kind.toString,
          s.lanes.map(l => LaneView(l.key, l.role)),
          Option.when(s.when != StepCondition.Always)(s.when.toString)
        )
      ),
      d.resetTo
    )

  private def storedView(s: StoredDefinition): StoredWorkflowView =
    StoredWorkflowView(
      s.classKey,
      s.version,
      definitionView(s.definition),
      s.fourEyes,
      s.reason,
      s.createdAt,
      s.createdBy.value
    )

  private def taskView(t: WorkflowTask): TaskView =
    TaskView(
      t.id,
      t.caseId,
      t.documentId.value,
      t.versionId,
      t.round,
      t.step,
      t.stepName,
      t.kind.toString,
      t.lane,
      t.role,
      t.candidates.map(_.value),
      t.status.toString,
      t.createdAt,
      t.completedAt,
      t.completedBy.map(_.value),
      t.deputies.map(d => TaskDeputyView(d.deputy.value, d.principal.value)),
      t.actingFor.map(_.value)
    )

  def caseView(d: CaseDetails): WorkflowCaseView =
    val c = d.workflowCase
    WorkflowCaseView(
      c.id,
      c.documentId.value,
      c.versionId,
      c.classKey,
      c.definitionVersion,
      c.fourEyes,
      c.round,
      c.status.toString,
      c.currentStep,
      c.returnedStep,
      c.contentSha256,
      d.tasks.map(taskView),
      d.votes.map(v =>
        VoteView(
          v.id,
          v.taskId,
          v.round,
          v.step,
          v.lane,
          v.voter.value,
          v.decision.toString,
          v.reason,
          v.contentSha256,
          v.authenticatedAt,
          v.votedAt,
          v.invalidatedAt,
          v.actingFor.map(_.value)
        )
      ),
      d.approval.map(a =>
        ApprovalView(
          a.versionId,
          a.versionLabel,
          a.contentSha256,
          a.approvedBy.value,
          a.authenticatedAt,
          a.approvedAt,
          a.actingFor.map(_.value)
        )
      )
    )

  def toResponse(error: WorkflowError | AccessDenied | DbError): (StatusCode, ApiError) = error match
    case e: AccessDenied              => DocumentApi.toResponse(e)
    case e: DbError                   => DocumentApi.toResponse(e)
    case WorkflowError.NotFound(m)    => (StatusCode.NotFound, ApiError("not_found", m))
    case e @ WorkflowError.Invalid(_) => (StatusCode.UnprocessableEntity, ApiError("invalid", e.describe))
    case WorkflowError.Forbidden(m)   => (StatusCode.Forbidden, ApiError("forbidden", m))
    case WorkflowError.ReauthenticationRequired =>
      SecuredApi.toResponse(dms.identity.AuthError.ReauthenticationRequired)
    case WorkflowError.Conflict(m)       => (StatusCode.Conflict, ApiError("conflict", m))
    case WorkflowError.Document(code, m) => (documentStatus(code), ApiError(code, m))
    case WorkflowError.Database(e)       => DocumentApi.toResponse(e)
    case e @ WorkflowError.Engine(_)     =>
      (StatusCode.InternalServerError, ApiError("workflow_error", e.describe))

  private def documentStatus(code: String): StatusCode = code match
    case "not_found"                                 => StatusCode.NotFound
    case "forbidden" | "segregation_of_duties"       => StatusCode.Forbidden
    case "invalid" | "invalid_values" | "incomplete" => StatusCode.UnprocessableEntity
    case "database_error"                            => StatusCode.InternalServerError
    case _                                           => StatusCode.Conflict
