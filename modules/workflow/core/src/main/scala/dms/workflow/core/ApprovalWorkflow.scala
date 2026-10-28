package dms.workflow.core

import com.augustnagro.magnum.*
import dms.audit.*
import dms.db.queue.Outbox
import dms.db.{Db, DbError, Jsonb}
import dms.documents.{ChangeKind, DocumentStatus, LifecycleEvent}
import dms.kernel.{DocumentId, UserId}
import dms.workflow.*
import dms.workflow.core.WorkflowJson.given
import dms.workflow.core.WorkflowStore.{CaseRow, TaskRow}
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.Instant
import java.util.UUID
import scala.jdk.CollectionConverters.*

/** Review and approval of document versions along the workflow of their class (business concept 6.1; DMS-40,
  * DMS-41, DMS-42; ADR 0024).
  *
  * Flowable routes the steps (sequential steps, parallel lanes, the conditional extra review step, resuming
  * at a step); this service owns everything with business meaning: participants resolved from role
  * assignments with the four-eyes rule, votes with reasons bound to the content checksum, the step-up check
  * for approvals, the lifecycle transitions of the version, the approval record, audit entries and outbox
  * events.
  *
  * Every method must run inside `Db.transact(tenant)`; engine calls join that transaction, so a failure rolls
  * back the workflow step together with the status change, the vote and the audit entry. Votes lock the case,
  * which serializes concurrent votes on parallel lanes. Authorization of the caller is the caller's job; this
  * service only checks that the caller is a participant of the task.
  */
final class ApprovalWorkflow(
    flowable: FlowableEngine,
    audit: AuditTrail,
    documents: DocumentGateway,
    directory: ParticipantDirectory,
    extraReview: ExtraReviewCheck,
    config: WorkflowConfig
):
  import ApprovalWorkflow.*

  // ---- definitions --------------------------------------------------------------------------------------

  /** Stores the next version of the workflow of a class. Running cases keep their version. Disabling the
    * four-eyes rule needs a reason and is recorded in the audit trail as `workflow.four-eyes-disabled`.
    */
  def define(
      classKey: String,
      definition: WorkflowDefinition,
      fourEyes: Boolean,
      reason: Option[String],
      actor: UserId
  ): IO[WorkflowError, StoredDefinition] =
    val why      = reason.map(_.trim).filter(_.nonEmpty)
    val problems = WorkflowDefinition.problems(definition) ++
      Option.when(!fourEyes && why.isEmpty)("disabling the four-eyes rule needs a reason")
    for
      _        <- ZIO.unless(problems.isEmpty)(ZIO.fail(WorkflowError.Invalid(problems)))
      previous <- sql(WorkflowStore.latestDefinition(classKey))
      now      <- Clock.instant
      stored = StoredDefinition(
        classKey,
        previous.fold(1)(_.version + 1),
        definition,
        fourEyes,
        why,
        now,
        actor
      )
      _ <- sql(WorkflowStore.insertDefinition(stored)).mapError {
        case WorkflowError.Database(DbError.Other("23503", _)) =>
          WorkflowError.NotFound(s"document class $classKey does not exist")
        case other => other
      }
      target = AuditObject("workflow-definition", None, Some(s"$classKey:${stored.version}"))
      _ <- record(
        actor,
        "workflow.defined",
        target,
        Json.Obj(
          "classKey"   -> Json.Str(classKey),
          "version"    -> Json.Num(stored.version),
          "fourEyes"   -> Json.Bool(fourEyes),
          "definition" -> definition.toJsonAST.getOrElse(Json.Null)
        ),
        why
      )
      wasOn = previous.forall(_.fourEyes)
      _ <- ZIO.when(wasOn && !fourEyes)(
        record(actor, "workflow.four-eyes-disabled", target, Json.Obj("classKey" -> Json.Str(classKey)), why)
      )
      _ <- ZIO.when(!wasOn && fourEyes)(
        record(actor, "workflow.four-eyes-enabled", target, Json.Obj("classKey" -> Json.Str(classKey)), why)
      )
    yield stored

  /** All versions of the workflow of a class, oldest first. */
  def definitions(classKey: String): IO[WorkflowError, List[StoredDefinition]] =
    sql(WorkflowStore.definitionVersions(classKey))

  /** The workflow a new case of a document of `classPath` (root first) uses: the latest version of the
    * nearest class that has one, or `None` for the built-in default.
    */
  def effective(classPath: List[String]): IO[WorkflowError, Option[StoredDefinition]] =
    ZIO
      .foldLeft(classPath.reverse)(Option.empty[StoredDefinition]) { (found, key) =>
        found.fold(sql(WorkflowStore.latestDefinition(key)))(f => ZIO.some(f))
      }

  // ---- cases ----------------------------------------------------------------------------------------------

  /** Starts the review of a version the owner just submitted (status `InReview`), or the next round of a
    * returned case. If the content changed since the last round, all earlier votes become invalid and the
    * round starts at the reset step of the definition (DMS-41); otherwise at the step that returned it.
    */
  def start(documentId: DocumentId, versionId: UUID, actor: UserId): IO[WorkflowError, CaseDetails] =
    for
      subject <- documents.subject(documentId, versionId)
      _       <- ZIO.unless(subject.status == DocumentStatus.InReview)(
        ZIO.fail(
          WorkflowError.Conflict(s"version ${subject.versionLabel} is ${subject.status}, not in review")
        )
      )
      existing <- sql(WorkflowStore.lockCaseOfVersion(versionId))
      caseId   <- existing match
        case None                                                    => open(subject, actor)
        case Some(row) if row.status == CaseStatus.Returned.toString => resubmit(row, subject, actor)
        case Some(row)                                               =>
          ZIO.fail(
            WorkflowError.Conflict(s"the review of version ${subject.versionLabel} is already ${row.status}")
          )
      details <- details(caseId)
    yield details

  /** Records a vote on a task. Approving votes complete the task and move the case on; when the last step is
    * done, the version is approved by the voter. Requesting changes or rejecting returns the version to its
    * author and cancels the other tasks of the round.
    *
    * @param authenticatedAt
    *   the voter's last sign-in (`auth_time` of the token); approval votes need it within the configured
    *   window
    */
  def vote(
      taskId: String,
      decision: VoteDecision,
      reason: Option[String],
      actor: UserId,
      authenticatedAt: Option[Instant]
  ): IO[WorkflowError, CaseDetails] =
    for
      found <- sql(WorkflowStore.task(taskId)).someOrFail(taskNotFound(taskId))
      row   <- sql(WorkflowStore.lockCase(found.caseId)).someOrFail(taskNotFound(taskId))
      task  <- sql(WorkflowStore.task(taskId)).someOrFail(taskNotFound(taskId))
      _     <- checkParticipant(row, task, actor)
      now   <- Clock.instant
      kind = StepKind.valueOf(task.stepKind)
      _ <- ZIO.fromEither(
        Rules.checkVote(kind, decision, reason, authenticatedAt, now, config.reauthenticationWindow)
      )
      subject <- documents.subject(DocumentId(row.documentId), row.versionId)
      _       <- ZIO.unless(subject.contentSha256 == row.contentSha256)(
        ZIO.fail(
          WorkflowError.Conflict(
            "the content changed since this round started; vote again after resubmission"
          )
        )
      )
      vote = Vote(
        UUID.randomUUID(),
        row.id,
        task.id,
        row.versionId,
        row.round,
        task.stepKey,
        task.laneKey,
        actor,
        decision,
        reason.map(_.trim).filter(_.nonEmpty),
        row.contentSha256,
        authenticatedAt,
        now,
        None
      )
      _ <- sql(WorkflowStore.insertVote(vote))
      _ <- record(
        actor,
        "workflow.voted",
        target(subject),
        Json.Obj(
          "caseId"        -> Json.Str(row.id.toString),
          "taskId"        -> Json.Str(task.id),
          "step"          -> Json.Str(task.stepKey),
          "lane"          -> Json.Str(task.laneKey),
          "decision"      -> Json.Str(decision.toString),
          "contentSha256" -> Json.Str(row.contentSha256),
          "authTime"      -> authenticatedAt.fold(Json.Null)(t => Json.Str(t.toString))
        ),
        vote.reason
      )
      _ <-
        if decision.approves then
          for
            _ <- sql(WorkflowStore.completeTask(task.id, TaskStatus.Completed, Some(actor)))
            _ <- flowable.run(_.getTaskService.complete(task.id))
            _ <- advance(row, subject, actor, authenticatedAt)
          yield ()
        else sendBack(row, subject, task, decision, actor)
      details <- details(row.id)
    yield details

  /** Re-resolves the participants of an open or waiting task, e.g. after a tenant administrator assigned the
    * missing role.
    */
  def resolveParticipants(taskId: String, actor: UserId): IO[WorkflowError, WorkflowTask] =
    for
      found <- sql(WorkflowStore.task(taskId)).someOrFail(taskNotFound(taskId))
      row   <- sql(WorkflowStore.lockCase(found.caseId)).someOrFail(taskNotFound(taskId))
      task  <- sql(WorkflowStore.task(taskId)).someOrFail(taskNotFound(taskId))
      _     <- ZIO.unless(task.status == "Open" || task.status == "Waiting")(
        ZIO.fail(WorkflowError.Conflict(s"task $taskId is ${task.status}"))
      )
      subject <- documents.subject(DocumentId(row.documentId), row.versionId)
      holders <- holdersOf(task.role, subject)
      eligible = Rules.eligible(holders, subject.author, row.fourEyes)
      status   = if eligible.isEmpty then TaskStatus.Waiting else TaskStatus.Open
      _ <- sql(WorkflowStore.updateCandidates(task.id, eligible, status))
      _ <- record(
        actor,
        "workflow.participants-resolved",
        target(subject),
        Json.Obj(
          "taskId"     -> Json.Str(task.id),
          "candidates" -> Json.Arr(eligible.map(u => Json.Str(u.value))*)
        ),
        None
      )
      _       <- ZIO.when(eligible.isEmpty)(notifyMissing(row, subject, task, holders))
      updated <- sql(WorkflowStore.task(taskId)).someOrFail(taskNotFound(taskId))
    yield view(row, updated)

  /** Open tasks the user may complete, oldest first. */
  def myTasks(user: UserId): IO[WorkflowError, List[WorkflowTask]] =
    for
      tasks <- sql(WorkflowStore.openTasksOf(user))
      cases <- ZIO.foreach(tasks.map(_.caseId).distinct)(id => caseRow(id).map(id -> _))
      byId = cases.toMap
    yield tasks.map(t => view(byId(t.caseId), t))

  def task(taskId: String): IO[WorkflowError, WorkflowTask] =
    for
      task <- sql(WorkflowStore.task(taskId)).someOrFail(taskNotFound(taskId))
      row  <- caseRow(task.caseId)
    yield view(row, task)

  /** The cases of all versions of a document, oldest first. */
  def cases(documentId: DocumentId): IO[WorkflowError, List[CaseDetails]] =
    sql(WorkflowStore.casesOfDocument(documentId)).flatMap(rows => ZIO.foreach(rows)(r => details(r.id)))

  /** The case of a version, if it was submitted. */
  def caseOfVersion(versionId: UUID): IO[WorkflowError, Option[CaseDetails]] =
    sql(WorkflowStore.caseOfVersion(versionId)).flatMap(ZIO.foreach(_)(r => details(r.id)))

  // ---- internals ------------------------------------------------------------------------------------------

  private def open(subject: ReviewSubject, actor: UserId): IO[WorkflowError, UUID] =
    for
      stored <- effective(subject.classPath)
      (definition, key) = definitionFor(subject, stored)
      tenant              <- sql(sql"select dms.current_tenant()".query[UUID].run().head)
      processDefinitionId <- deploy(tenant, key, definition)
      row = CaseRow(
        UUID.randomUUID(),
        subject.documentId.value,
        subject.versionId,
        stored.fold(subject.classPath.lastOption.getOrElse(""))(_.classKey),
        stored.filter(_ => definition != WorkflowDefinition.editorial).map(_.version),
        WorkflowStore.json(definition),
        stored.forall(_.fourEyes),
        processDefinitionId,
        None,
        1,
        CaseStatus.Running.toString,
        None,
        None,
        subject.contentSha256,
        subject.author.value,
        actor.value,
        java.time.OffsetDateTime.now(),
        java.time.OffsetDateTime.now()
      )
      instance <- startInstance(row, definition.firstStep)
      _        <- sql(WorkflowStore.insertCase(row.copy(processInstanceId = Some(instance))))
      _        <- record(
        actor,
        "workflow.started",
        target(subject),
        Json.Obj(
          "caseId"            -> Json.Str(row.id.toString),
          "classKey"          -> Json.Str(row.classKey),
          "definitionVersion" -> row.definitionVersion.fold(Json.Null)(Json.Num(_)),
          "fourEyes"          -> Json.Bool(row.fourEyes),
          "contentSha256"     -> Json.Str(subject.contentSha256)
        ),
        None
      )
      _ <- advance(row.copy(processInstanceId = Some(instance)), subject, actor, None)
    yield row.id

  private def resubmit(row: CaseRow, subject: ReviewSubject, actor: UserId): IO[WorkflowError, UUID] =
    val definition = WorkflowStore.parse(row.definition)
    val resume     = Rules.resume(definition, row.returnedStep, row.contentSha256, subject.contentSha256)
    for
      invalidated <-
        if resume.contentChanged then
          sql(
            WorkflowStore.invalidateVotes(row.id, subject.contentSha256, "the content changed after the vote")
          )
        else ZIO.succeed(0)
      next = row.copy(
        round = row.round + 1,
        status = CaseStatus.Running.toString,
        returnedStep = None,
        currentStep = None,
        contentSha256 = subject.contentSha256
      )
      instance <- startInstance(next, resume.step)
      started = next.copy(processInstanceId = Some(instance))
      _ <- sql(WorkflowStore.updateCase(started))
      _ <- record(
        actor,
        if resume.contentChanged then "workflow.reset" else "workflow.resubmitted",
        target(subject),
        Json.Obj(
          "caseId"           -> Json.Str(row.id.toString),
          "round"            -> Json.Num(started.round),
          "resumeAt"         -> Json.Str(resume.step),
          "contentChanged"   -> Json.Bool(resume.contentChanged),
          "invalidatedVotes" -> Json.Num(invalidated),
          "contentSha256"    -> Json.Str(subject.contentSha256)
        ),
        None
      )
      _ <- advance(started, subject, actor, None)
    yield row.id

  /** The workflow of a new case and its process key: minor (editorial) versions take the shortened path
    * (DMS-46), all others the class's workflow or the built-in default.
    */
  private def definitionFor(
      subject: ReviewSubject,
      stored: Option[StoredDefinition]
  ): (WorkflowDefinition, String) =
    if subject.changeKind == ChangeKind.Minor then (WorkflowDefinition.editorial, Bpmn.EditorialKey)
    else (stored.fold(WorkflowDefinition.default)(_.definition), Bpmn.processKey(stored.map(_.classKey)))

  /** People who may take a lane: the document owner for [[WorkflowDefinition.DocumentOwner]] lanes (their
    * deputies if the owner is the author or missing, see [[Rules.ownerConfirmation]]), otherwise the holders
    * of the lane's role.
    */
  private def holdersOf(role: String, subject: ReviewSubject): IO[WorkflowError, List[UserId]] =
    if role == WorkflowDefinition.DocumentOwner then
      Rules.ownerConfirmation(subject.owner, subject.author) match
        case Nil    => directory.holders(WorkflowDefinition.OwnerDeputyRole, subject)
        case owners => ZIO.succeed(owners)
    else directory.holders(role, subject)

  private def deploy(tenant: UUID, key: String, definition: WorkflowDefinition): IO[WorkflowError, String] =
    val xml = Bpmn.render(key, key, definition)
    flowable.run { engine =>
      val repository = engine.getRepositoryService
      val deployment = repository
        .createDeployment()
        .name(key)
        .tenantId(tenant.toString)
        .addString(s"$key.bpmn20.xml", xml)
        .enableDuplicateFiltering()
        .deploy()
      repository.createProcessDefinitionQuery().deploymentId(deployment.getId).singleResult().getId
    }

  private def startInstance(row: CaseRow, resumeAt: String): IO[WorkflowError, String] =
    for
      extra    <- extraReview.required(DocumentId(row.documentId), row.versionId)
      instance <- flowable.run(
        _.getRuntimeService
          .createProcessInstanceBuilder()
          .processDefinitionId(row.processDefinitionId)
          .businessKey(s"${row.documentId}:${row.versionId}")
          .variables(
            Map[String, AnyRef](
              "documentId"     -> row.documentId.toString,
              "versionId"      -> row.versionId.toString,
              "round"          -> Integer.valueOf(row.round),
              Bpmn.ResumeAt    -> resumeAt,
              Bpmn.ExtraReview -> java.lang.Boolean.valueOf(extra)
            ).asJava
          )
          .start()
          .getId
      )
    yield instance

  /** Mirrors the engine's new user tasks, applies the lifecycle transition of the phase that was reached and
    * approves the version when the process ended.
    */
  private def advance(
      row: CaseRow,
      subject: ReviewSubject,
      actor: UserId,
      authenticatedAt: Option[Instant]
  ): IO[WorkflowError, Unit] =
    val definition = WorkflowStore.parse(row.definition)
    for
      instance <- ZIO.fromOption(row.processInstanceId).orElseFail(WorkflowError.Conflict("no running round"))
      active   <- flowable.run(
        _.getTaskService
          .createTaskQuery()
          .processInstanceId(instance)
          .list()
          .asScala
          .toList
          .map(t => (t.getId, t.getTaskDefinitionKey))
      )
      known <- sql(WorkflowStore.tasksOfCase(row.id)).map(_.map(_.id).toSet)
      _     <- ZIO.foreachDiscard(active.filterNot((id, _) => known.contains(id)))((id, key) =>
        createTask(row, definition, subject, id, key)
      )
      _ <- active.headOption match
        case None           => approve(row, subject, actor, authenticatedAt)
        case Some((_, key)) =>
          for
            step <- stepOf(definition, key)
            _    <- sql(WorkflowStore.updateCase(row.copy(currentStep = Some(step.key))))
            _    <- ZIO.when(step.kind == StepKind.Approval && subject.status == DocumentStatus.InReview)(
              documents.advance(DocumentId(row.documentId), row.versionId, LifecycleEvent.ReviewPassed, actor)
            )
          yield ()
    yield ()

  private def createTask(
      row: CaseRow,
      definition: WorkflowDefinition,
      subject: ReviewSubject,
      taskId: String,
      key: String
  ): IO[WorkflowError, Unit] =
    for
      step <- stepOf(definition, key)
      lane <- ZIO
        .fromOption(Rules.parseTaskKey(key).flatMap((_, l) => step.lanes.find(_.key == l)))
        .orElseFail(WorkflowError.Engine(s"unknown task $key"))
      holders <- holdersOf(lane.role, subject)
      eligible = Rules.eligible(holders, subject.author, row.fourEyes)
      task     = TaskRow(
        taskId,
        row.id,
        row.round,
        step.key,
        step.kind.toString,
        lane.key,
        lane.role,
        WorkflowStore.candidatesJson(eligible),
        (if eligible.isEmpty then TaskStatus.Waiting else TaskStatus.Open).toString,
        java.time.OffsetDateTime.now(),
        None,
        None
      )
      _ <- sql(WorkflowStore.insertTask(task))
      _ <- ZIO.when(eligible.isEmpty)(notifyMissing(row, subject, task, holders))
    yield ()

  /** Nobody may take the task (DMS-42): the tenant administrators are notified through the outbox, and the
    * task waits until participants are resolved again.
    */
  private def notifyMissing(
      row: CaseRow,
      subject: ReviewSubject,
      task: TaskRow,
      holders: List[UserId]
  ): IO[WorkflowError, Unit] =
    for
      admins <- directory.tenantAdmins
      payload = Json.Obj(
        "caseId"         -> Json.Str(row.id.toString),
        "taskId"         -> Json.Str(task.id),
        "documentId"     -> Json.Str(subject.documentId.value.toString),
        "versionId"      -> Json.Str(subject.versionId.toString),
        "version"        -> Json.Str(subject.versionLabel),
        "step"           -> Json.Str(task.stepKey),
        "lane"           -> Json.Str(task.laneKey),
        "role"           -> Json.Str(task.role),
        "authorExcluded" -> Json.Bool(row.fourEyes && holders.contains(subject.author)),
        "recipients"     -> Json.Arr(admins.map(a => Json.Str(a.value))*)
      )
      _ <- Outbox
        .publish("workflow", row.id, "workflow.participant-missing", Jsonb(payload.toJson))
        .mapError(WorkflowError.Database(_))
      _ <- record(
        UserId("system"),
        "workflow.participant-missing",
        target(subject),
        payload,
        None,
        technical = true
      )
    yield ()

  private def sendBack(
      row: CaseRow,
      subject: ReviewSubject,
      task: TaskRow,
      decision: VoteDecision,
      actor: UserId
  ): IO[WorkflowError, Unit] =
    for
      event    <- ZIO.fromEither(Rules.returnEvent(subject.status))
      instance <- ZIO.fromOption(row.processInstanceId).orElseFail(WorkflowError.Conflict("no running round"))
      others   <- sql(WorkflowStore.tasksOfCase(row.id)).map(
        _.filter(t => t.id != task.id && (t.status == "Open" || t.status == "Waiting"))
      )
      _ <- sql {
        WorkflowStore.completeTask(task.id, TaskStatus.Completed, Some(actor))
        others.foreach(t => WorkflowStore.completeTask(t.id, TaskStatus.Cancelled, None))
      }
      _ <- flowable.run(_.getRuntimeService.deleteProcessInstance(instance, s"returned: $decision"))
      _ <- documents.advance(DocumentId(row.documentId), row.versionId, event, actor)
      _ <- sql(
        WorkflowStore.updateCase(
          row.copy(
            status = CaseStatus.Returned.toString,
            processInstanceId = None,
            currentStep = None,
            returnedStep = Some(task.stepKey)
          )
        )
      )
      payload = Json.Obj(
        "caseId"     -> Json.Str(row.id.toString),
        "documentId" -> Json.Str(subject.documentId.value.toString),
        "versionId"  -> Json.Str(subject.versionId.toString),
        "version"    -> Json.Str(subject.versionLabel),
        "step"       -> Json.Str(task.stepKey),
        "decision"   -> Json.Str(decision.toString),
        "recipients" -> Json.Arr(
          (subject.author :: subject.owner.toList).distinct.map(u => Json.Str(u.value))*
        )
      )
      _ <- Outbox
        .publish("workflow", row.id, "workflow.returned", Jsonb(payload.toJson))
        .mapError(WorkflowError.Database(_))
      _ <- record(actor, "workflow.returned", target(subject), payload, None)
    yield ()

  /** The last step is done: the voter approves the version, bound to the content the votes were cast on. */
  private def approve(
      row: CaseRow,
      subject: ReviewSubject,
      actor: UserId,
      authenticatedAt: Option[Instant]
  ): IO[WorkflowError, Unit] =
    for
      authTime <- ZIO.fromOption(authenticatedAt).orElseFail(WorkflowError.ReauthenticationRequired)
      current  <- documents.subject(DocumentId(row.documentId), row.versionId)
      _        <- ZIO.unless(current.contentSha256 == row.contentSha256)(
        ZIO.fail(WorkflowError.Conflict("the content changed during the review"))
      )
      approved <- documents.advance(DocumentId(row.documentId), row.versionId, LifecycleEvent.Approve, actor)
      now      <- Clock.instant
      approval = Approval(
        UUID.randomUUID(),
        row.id,
        row.versionId,
        approved.versionLabel,
        row.contentSha256,
        actor,
        authTime,
        now
      )
      _ <- sql {
        WorkflowStore.insertApproval(approval, subject.author)
        WorkflowStore.updateCase(
          row.copy(status = CaseStatus.Approved.toString, processInstanceId = None, currentStep = None)
        )
      }
      payload = Json.Obj(
        "caseId"        -> Json.Str(row.id.toString),
        "documentId"    -> Json.Str(subject.documentId.value.toString),
        "versionId"     -> Json.Str(row.versionId.toString),
        "version"       -> Json.Str(approved.versionLabel),
        "contentSha256" -> Json.Str(row.contentSha256),
        "authTime"      -> Json.Str(authTime.toString)
      )
      _ <- Outbox
        .publish("workflow", row.id, "workflow.approved", Jsonb(payload.toJson))
        .mapError(WorkflowError.Database(_))
      _ <- record(actor, "workflow.approved", target(approved), payload, None)
    yield ()

  private def checkParticipant(row: CaseRow, task: TaskRow, actor: UserId): IO[WorkflowError, Unit] =
    if task.status != "Open" then ZIO.fail(WorkflowError.Conflict(s"task ${task.id} is ${task.status}"))
    else if row.fourEyes && UserId(row.authorId) == actor then
      ZIO.fail(
        WorkflowError.Forbidden("four-eyes rule: the author cannot review or approve their own version")
      )
    else if !task.candidateList.contains(actor) then
      ZIO.fail(WorkflowError.Forbidden("you are not a participant of this task"))
    else ZIO.unit

  private def details(caseId: UUID): IO[WorkflowError, CaseDetails] =
    for
      row      <- caseRow(caseId)
      tasks    <- sql(WorkflowStore.tasksOfCase(caseId))
      votes    <- sql(WorkflowStore.votesOfCase(caseId))
      approval <- sql(WorkflowStore.approvalOfCase(caseId))
    yield CaseDetails(row.toCase, tasks.map(view(row, _)), votes, approval)

  private def caseRow(id: UUID): IO[WorkflowError, CaseRow] =
    sql(WorkflowStore.caseById(id)).someOrFail(WorkflowError.NotFound(s"workflow case $id does not exist"))

  private def record(
      actor: UserId,
      action: String,
      target: AuditObject,
      details: Json.Obj,
      reason: Option[String],
      technical: Boolean = false
  ): IO[WorkflowError, Unit] =
    audit
      .record(
        AuditEvent(
          Actor(actor.value, if technical then ActorKind.Technical else ActorKind.Person),
          action,
          target,
          if technical then Channel.Automation else Channel.Api,
          Jsonb(details.toJson),
          reason
        )
      )
      .mapError(WorkflowError.Database(_))
      .unit

object ApprovalWorkflow:
  val layer: URLayer[
    FlowableEngine & AuditTrail & DocumentGateway & ParticipantDirectory & ExtraReviewCheck & WorkflowConfig,
    ApprovalWorkflow
  ] = ZLayer.fromFunction(ApprovalWorkflow(_, _, _, _, _, _))

  private def sql[A](f: DbTx ?=> A): IO[WorkflowError, A] = Db.sql(f).mapError(WorkflowError.Database(_))

  private def taskNotFound(id: String) = WorkflowError.NotFound(s"task $id does not exist")

  private def target(s: ReviewSubject): AuditObject =
    AuditObject("document", Some(s.documentId.value), Some(s.versionLabel))

  private def stepOf(definition: WorkflowDefinition, key: String): IO[WorkflowError, Step] =
    ZIO
      .fromOption(Rules.parseTaskKey(key).flatMap((s, _) => definition.step(s)))
      .orElseFail(WorkflowError.Engine(s"task $key does not belong to the workflow"))

  private def view(row: CaseRow, t: TaskRow): WorkflowTask =
    val definition = WorkflowStore.parse(row.definition)
    WorkflowTask(
      t.id,
      t.caseId,
      DocumentId(row.documentId),
      row.versionId,
      t.round,
      t.stepKey,
      definition.step(t.stepKey).fold(t.stepKey)(_.name),
      StepKind.valueOf(t.stepKind),
      t.laneKey,
      t.role,
      t.candidateList,
      TaskStatus.valueOf(t.status),
      t.createdAt.toInstant,
      t.completedAt.map(_.toInstant),
      t.completedBy.map(UserId(_))
    )
