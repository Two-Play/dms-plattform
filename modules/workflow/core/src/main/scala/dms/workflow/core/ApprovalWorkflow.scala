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
    rendition: ApprovalRendition,
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
    * done, the archive rendition is requested and the case waits in `Rendering`; once the rendition is
    * attached, [[completeApproval]] approves the version as the voter (DMS-44). Requesting changes or
    * rejecting returns the version to its author and cancels the other tasks of the round.
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
      found     <- sql(WorkflowStore.task(taskId)).someOrFail(taskNotFound(taskId))
      row       <- sql(WorkflowStore.lockCase(found.caseId)).someOrFail(taskNotFound(taskId))
      task      <- sql(WorkflowStore.task(taskId)).someOrFail(taskNotFound(taskId))
      actingFor <- participation(row, task, actor)
      now       <- Clock.instant
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
        None,
        actingFor
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
          "authTime"      -> authenticatedAt.fold(Json.Null)(t => Json.Str(t.toString)),
          "actingFor"     -> optional(actingFor)
        ),
        vote.reason,
        onBehalfOf = actingFor
      )
      _ <-
        if decision.approves then
          for
            _ <- sql(WorkflowStore.completeTask(task.id, TaskStatus.Completed, Some(actor)))
            _ <- flowable.run(_.getTaskService.complete(task.id))
            _ <- advance(row, subject, actor, authenticatedAt, actingFor)
          yield ()
        else sendBack(row, subject, task, decision, actor, actingFor)
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
      _       <- resolve(row, subject, task, actor, onlyIfChanged = false)
      updated <- sql(WorkflowStore.task(taskId)).someOrFail(taskNotFound(taskId))
      views   <- views(row, List(updated))
    yield views.head

  /** Re-resolves the participants of the open and waiting tasks of confidential versions that name the access
    * group `group`, after a member joined or left it (DMS-105): a new member who holds the lane's role takes
    * part from now on, a former member no longer. Tasks whose participants stay the same are left as they
    * are; changed ones are recorded like [[resolveParticipants]]. Returns the changed tasks.
    */
  def resolveParticipantsOfGroup(group: String, actor: UserId): IO[WorkflowError, List[WorkflowTask]] =
    for
      unfinished <- sql(WorkflowStore.unfinishedTasks)
      caseIds = unfinished.map(_.caseId).distinct
      changed <- ZIO.foreach(caseIds) { caseId =>
        for
          row     <- sql(WorkflowStore.lockCase(caseId)).someOrFail(caseNotFound(caseId))
          subject <- documents.subject(DocumentId(row.documentId), row.versionId)
          updated <-
            if !subject.accessGroups.exists(_.contains(group)) then ZIO.succeed(Nil)
            else
              for
                // re-read under the case lock: a concurrent vote may have completed a task meanwhile
                tasks <- sql(WorkflowStore.tasksOfCase(caseId))
                  .map(_.filter(t => t.status == "Open" || t.status == "Waiting"))
                resolved <- ZIO.foreach(tasks)(t => resolve(row, subject, t, actor, onlyIfChanged = true))
                ids = resolved.flatten.toSet
                after <- sql(WorkflowStore.tasksOfCase(caseId)).map(_.filter(t => ids.contains(t.id)))
                views <- views(row, after)
              yield views
        yield updated
      }
    yield changed.flatten

  /** Resolves the participants of a task again; with `onlyIfChanged` nothing is written or recorded when they
    * stay the same. Returns the task id if it was updated.
    */
  private def resolve(
      row: CaseRow,
      subject: ReviewSubject,
      task: TaskRow,
      actor: UserId,
      onlyIfChanged: Boolean
  ): IO[WorkflowError, Option[String]] =
    for
      holders <- holdersOf(task.role, subject)
      eligible  = Rules.eligible(holders, subject.author, row.fourEyes)
      status    = if eligible.isEmpty then TaskStatus.Waiting else TaskStatus.Open
      unchanged = eligible == task.candidateList && status.toString == task.status
      updated <-
        if onlyIfChanged && unchanged then ZIO.none
        else
          for
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
            _ <- ZIO.when(eligible.isEmpty)(notifyMissing(row, subject, task, holders))
          yield Some(task.id)
    yield updated

  /** Open tasks the user may complete, oldest first: their own and, during an active deputyship, those of
    * their principals (DMS-49) with `actingFor` set to the principal.
    */
  def myTasks(user: UserId): IO[WorkflowError, List[WorkflowTask]] =
    for
      now       <- Clock.instant
      direct    <- sql(WorkflowStore.openTasksOf(user))
      standing  <- sql(DeputyStore.activeForDeputy(user, now))
      forwarded <-
        if standing.isEmpty then ZIO.succeed(Nil)
        else
          sql(WorkflowStore.openTasksOfAny(standing.map(_.principal)))
            .map(_.filterNot(t => direct.exists(_.id == t.id)))
      all = direct ++ forwarded
      perCase <- ZIO.foreach(all.map(_.caseId).distinct)(id =>
        caseRow(id).flatMap(row => views(row, all.filter(_.caseId == id)))
      )
    yield perCase.flatten
      .flatMap(t =>
        if t.candidates.contains(user) then Some(t)
        else t.deputies.find(_.deputy == user).map(d => t.copy(actingFor = Some(d.principal)))
      )
      .sortBy(t => (t.createdAt, t.id))

  /** A task with the deputies who may currently complete it besides its candidates (DMS-49). */
  def task(taskId: String): IO[WorkflowError, WorkflowTask] =
    for
      task  <- sql(WorkflowStore.task(taskId)).someOrFail(taskNotFound(taskId))
      row   <- caseRow(task.caseId)
      views <- views(row, List(task))
    yield views.head

  /** The cases of all versions of a document, oldest first. */
  def cases(documentId: DocumentId): IO[WorkflowError, List[CaseDetails]] =
    sql(WorkflowStore.casesOfDocument(documentId)).flatMap(rows => ZIO.foreach(rows)(r => details(r.id)))

  /** The case of a version, if it was submitted. */
  def caseOfVersion(versionId: UUID): IO[WorkflowError, Option[CaseDetails]] =
    sql(WorkflowStore.caseOfVersion(versionId)).flatMap(ZIO.foreach(_)(r => details(r.id)))

  /** The approvals of the given versions by version id (approver, deputy's principal, time), e.g. for the
    * validity history (DMS-53). Versions approved without a workflow case are missing.
    */
  def approvals(versionIds: List[UUID]): IO[WorkflowError, Map[UUID, Approval]] =
    sql(WorkflowStore.approvalsOfVersions(versionIds)).map(_.map(a => a.versionId -> a).toMap)

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
      authenticatedAt: Option[Instant],
      actingFor: Option[UserId] = None
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
        case None           => approve(row, subject, actor, authenticatedAt, actingFor)
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
        // Role holders outside the access groups of a confidential version do not count (DMS-101).
        "confidential" -> Json.Bool(subject.accessGroups.isDefined),
        "recipients"   -> Json.Arr(admins.map(a => Json.Str(a.value))*)
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
      actor: UserId,
      actingFor: Option[UserId]
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
        "returnedBy" -> Json.Str(actor.value),
        "actingFor"  -> optional(actingFor),
        "recipients" -> Json.Arr(
          (subject.author :: subject.owner.toList).distinct.map(u => Json.Str(u.value))*
        )
      )
      _ <- Outbox
        .publish("workflow", row.id, "workflow.returned", Jsonb(payload.toJson))
        .mapError(WorkflowError.Database(_))
      _ <- record(actor, "workflow.returned", target(subject), payload, None, onBehalfOf = actingFor)
    yield ()

  /** The last step is done: the archive rendition is requested (DMS-44, ADR 0025). The version stays in
    * approval until the rendering job attaches the rendition and calls [[completeApproval]].
    */
  private def approve(
      row: CaseRow,
      subject: ReviewSubject,
      actor: UserId,
      authenticatedAt: Option[Instant],
      actingFor: Option[UserId]
  ): IO[WorkflowError, Unit] =
    for
      authTime <- ZIO.fromOption(authenticatedAt).orElseFail(WorkflowError.ReauthenticationRequired)
      current  <- documents.subject(DocumentId(row.documentId), row.versionId)
      _        <- ZIO.unless(current.contentSha256 == row.contentSha256)(
        ZIO.fail(WorkflowError.Conflict("the content changed during the review"))
      )
      now <- Clock.instant
      _   <- rendition.request(row.id, current, actor, now, authTime, actingFor)
      _   <- sql(
        WorkflowStore.updateCase(
          row.copy(status = CaseStatus.Rendering.toString, processInstanceId = None, currentStep = None)
        )
      )
      payload = Json.Obj(
        "caseId"        -> Json.Str(row.id.toString),
        "documentId"    -> Json.Str(subject.documentId.value.toString),
        "versionId"     -> Json.Str(row.versionId.toString),
        "contentSha256" -> Json.Str(row.contentSha256),
        "authTime"      -> Json.Str(authTime.toString),
        "actingFor"     -> optional(actingFor)
      )
      _ <- record(
        actor,
        "workflow.rendition-requested",
        target(current),
        payload,
        None,
        onBehalfOf = actingFor
      )
    yield ()

  /** Completes the approval of a case in `Rendering` once its archive rendition is attached (called by the
    * rendering job, DMS-44): `approver`, who cast the last approving vote, approves the version, bound to the
    * content the votes were cast on. Idempotent: an approved case is returned unchanged, so a repeated job
    * does not fail.
    *
    * @param authenticatedAt
    *   the approver's sign-in at the vote (`auth_time`), stored with the approval
    */
  def completeApproval(
      caseId: UUID,
      approver: UserId,
      authenticatedAt: Instant
  ): IO[WorkflowError, CaseDetails] =
    for
      row <- sql(WorkflowStore.lockCase(caseId))
        .someOrFail(WorkflowError.NotFound(s"workflow case $caseId does not exist"))
      _ <- CaseStatus.valueOf(row.status) match
        case CaseStatus.Approved  => ZIO.unit
        case CaseStatus.Rendering => finishApproval(row, approver, authenticatedAt)
        case other                =>
          ZIO.fail(WorkflowError.Conflict(s"workflow case $caseId is $other, not waiting for its rendition"))
      details <- details(caseId)
    yield details

  private def finishApproval(row: CaseRow, approver: UserId, authTime: Instant): IO[WorkflowError, Unit] =
    for
      votes <- sql(WorkflowStore.votesOfCase(row.id))
      approving = votes.filter(v =>
        v.voter == approver && v.round == row.round && v.decision.approves && v.invalidatedAt.isEmpty
      )
      _ <- ZIO.when(approving.isEmpty)(
        ZIO.fail(WorkflowError.Conflict(s"${approver.value} did not approve round ${row.round} of this case"))
      )
      // The last approving vote completed the case; a deputy approves on behalf of its principal (DMS-49).
      actingFor = approving.lastOption.flatMap(_.actingFor)
      subject <- documents.subject(DocumentId(row.documentId), row.versionId)
      _       <- ZIO.unless(subject.contentSha256 == row.contentSha256)(
        ZIO.fail(WorkflowError.Conflict("the content changed during the review"))
      )
      approved <- documents.advance(
        DocumentId(row.documentId),
        row.versionId,
        LifecycleEvent.Approve,
        approver
      )
      now <- Clock.instant
      approval = Approval(
        UUID.randomUUID(),
        row.id,
        row.versionId,
        approved.versionLabel,
        row.contentSha256,
        approver,
        authTime,
        now,
        actingFor
      )
      _ <- sql {
        WorkflowStore.insertApproval(approval, subject.author)
        WorkflowStore.updateCase(row.copy(status = CaseStatus.Approved.toString))
      }
      payload = Json.Obj(
        "caseId"        -> Json.Str(row.id.toString),
        "documentId"    -> Json.Str(subject.documentId.value.toString),
        "versionId"     -> Json.Str(row.versionId.toString),
        "version"       -> Json.Str(approved.versionLabel),
        "contentSha256" -> Json.Str(row.contentSha256),
        "authTime"      -> Json.Str(authTime.toString),
        "approvedBy"    -> Json.Str(approver.value),
        "actingFor"     -> optional(actingFor)
      )
      _ <- Outbox
        .publish("workflow", row.id, "workflow.approved", Jsonb(payload.toJson))
        .mapError(WorkflowError.Database(_))
      _ <- record(approver, "workflow.approved", target(approved), payload, None, onBehalfOf = actingFor)
    yield ()

  /** Whether `actor` may vote on the task: as a candidate (`None`) or as the deputy of a candidate (the
    * candidate they act for, DMS-49).
    */
  private def participation(row: CaseRow, task: TaskRow, actor: UserId): IO[WorkflowError, Option[UserId]] =
    if task.status != "Open" then ZIO.fail(WorkflowError.Conflict(s"task ${task.id} is ${task.status}"))
    else if row.fourEyes && UserId(row.authorId) == actor then
      ZIO.fail(
        WorkflowError.Forbidden("four-eyes rule: the author cannot review or approve their own version")
      )
    else if task.candidateList.contains(actor) then ZIO.none
    else
      views(row, List(task)).flatMap(
        _.flatMap(_.deputies).find(_.deputy == actor) match
          case Some(d) => ZIO.some(d.principal)
          case None    => ZIO.fail(WorkflowError.Forbidden("you are not a participant of this task"))
      )

  /** Views of tasks of one case, open tasks with the deputies who may complete them now (DMS-49): deputies of
    * candidates with an active deputyship for the lane's role and the document's class who still hold the
    * role themselves, see [[Rules.taskDeputies]]. Evaluated at read time, so tasks become available to
    * deputies when the period starts and are the candidates' alone again when it ends.
    */
  private def views(row: CaseRow, tasks: List[TaskRow]): IO[WorkflowError, List[WorkflowTask]] =
    val open       = tasks.filter(t => t.status == TaskStatus.Open.toString)
    val principals = open.flatMap(_.candidateList).distinct
    for
      now         <- Clock.instant
      deputyships <-
        if principals.isEmpty then ZIO.succeed(Nil)
        else sql(DeputyStore.activeForPrincipals(principals, now))
      deputies <-
        if deputyships.isEmpty then ZIO.succeed(Map.empty[String, List[TaskDeputy]])
        else
          for
            subject <- documents.subject(DocumentId(row.documentId), row.versionId)
            // A deputy who may not see a confidential version holds no role for it (DMS-101).
            cleared     <- directory.cleared(deputyships.map(_.deputy).toSet, subject)
            deputyRoles <- ZIO
              .foreach(deputyships.map(_.deputy).distinct.filter(cleared.contains))(d =>
                directory.heldRoles(d).map(held => d -> held.filter(_.isActiveAt(now)).map(_.role).toSet)
              )
              .map(_.toMap)
          yield open.map { t =>
            t.id -> Rules.taskDeputies(
              t.role,
              t.candidateList,
              subject.author,
              subject.classPath,
              deputyships,
              deputyRoles,
              now
            )
          }.toMap
    yield tasks.map(t => view(row, t).copy(deputies = deputies.getOrElse(t.id, Nil)))

  private def details(caseId: UUID): IO[WorkflowError, CaseDetails] =
    for
      row      <- caseRow(caseId)
      tasks    <- sql(WorkflowStore.tasksOfCase(caseId))
      views    <- views(row, tasks)
      votes    <- sql(WorkflowStore.votesOfCase(caseId))
      approval <- sql(WorkflowStore.approvalOfCase(caseId))
    yield CaseDetails(row.toCase, views, votes, approval)

  private def caseRow(id: UUID): IO[WorkflowError, CaseRow] =
    sql(WorkflowStore.caseById(id)).someOrFail(WorkflowError.NotFound(s"workflow case $id does not exist"))

  private def record(
      actor: UserId,
      action: String,
      target: AuditObject,
      details: Json.Obj,
      reason: Option[String],
      technical: Boolean = false,
      onBehalfOf: Option[UserId] = None
  ): IO[WorkflowError, Unit] =
    audit
      .record(
        AuditEvent(
          Actor(
            actor.value,
            if technical then ActorKind.Technical else ActorKind.Person,
            onBehalfOf.map(_.value)
          ),
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
    FlowableEngine & AuditTrail & DocumentGateway & ParticipantDirectory & ExtraReviewCheck &
      ApprovalRendition & WorkflowConfig,
    ApprovalWorkflow
  ] = ZLayer.fromFunction(ApprovalWorkflow(_, _, _, _, _, _, _))

  private def sql[A](f: DbTx ?=> A): IO[WorkflowError, A] = Db.sql(f).mapError(WorkflowError.Database(_))

  private def taskNotFound(id: String) = WorkflowError.NotFound(s"task $id does not exist")

  private def caseNotFound(id: UUID) = WorkflowError.NotFound(s"workflow case $id does not exist")

  private def optional(user: Option[UserId]): Json = user.fold(Json.Null)(u => Json.Str(u.value))

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
