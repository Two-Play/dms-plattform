package dms.workflow.core

import com.augustnagro.magnum.*
import dms.db.Jsonb
import dms.kernel.{DocumentId, UserId}
import dms.workflow.*
import dms.workflow.core.WorkflowJson.given
import zio.json.*

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.UUID

/** SQL for the workflow tables (migration V11). Runs inside `Db.sql` within `Db.transact(tenant)`; row-level
  * security limits all rows to the current tenant.
  */
private[core] object WorkflowStore:

  private type DefinitionRow = (String, Int, Jsonb, Boolean, Option[String], OffsetDateTime, String)

  def latestDefinition(classKey: String)(using DbTx): Option[StoredDefinition] =
    sql"""select class_key, version, definition, four_eyes, reason, created_at, created_by
          from dms.workflow_definition where class_key = $classKey order by version desc limit 1"""
      .query[DefinitionRow]
      .run()
      .headOption
      .map(toDefinition)

  def definitionVersions(classKey: String)(using DbTx): List[StoredDefinition] =
    sql"""select class_key, version, definition, four_eyes, reason, created_at, created_by
          from dms.workflow_definition where class_key = $classKey order by version"""
      .query[DefinitionRow]
      .run()
      .toList
      .map(toDefinition)

  def insertDefinition(d: StoredDefinition)(using DbTx): Unit =
    val _ = sql"""insert into dms.workflow_definition
                    (tenant_id, class_key, version, definition, four_eyes, reason, created_by)
                  values (dms.current_tenant(), ${d.classKey}, ${d.version}, ${json(
        d.definition
      )}, ${d.fourEyes},
                          ${d.reason}, ${d.createdBy.value})""".update.run()

  private def toDefinition(row: DefinitionRow): StoredDefinition =
    val (classKey, version, definition, fourEyes, reason, createdAt, createdBy) = row
    StoredDefinition(
      classKey,
      version,
      parse(definition),
      fourEyes,
      reason,
      createdAt.toInstant,
      UserId(createdBy)
    )

  final case class CaseRow(
      id: UUID,
      documentId: UUID,
      versionId: UUID,
      classKey: String,
      definitionVersion: Option[Int],
      definition: Jsonb,
      fourEyes: Boolean,
      processDefinitionId: String,
      processInstanceId: Option[String],
      round: Int,
      status: String,
      currentStep: Option[String],
      returnedStep: Option[String],
      contentSha256: String,
      authorId: String,
      startedBy: String,
      startedAt: OffsetDateTime,
      updatedAt: OffsetDateTime
  ) derives DbCodec:
    def toCase: WorkflowCase = WorkflowCase(
      id,
      DocumentId(documentId),
      versionId,
      classKey,
      definitionVersion,
      parse(definition),
      fourEyes,
      round,
      CaseStatus.valueOf(status),
      currentStep,
      returnedStep,
      contentSha256,
      UserId(authorId),
      UserId(startedBy),
      startedAt.toInstant,
      updatedAt.toInstant
    )

  // The column list is repeated in each query: Magnum binds parameters only for literal sql"" fragments.

  /** The case of a version, locked for the rest of the transaction (serializes votes of one case). */
  def lockCaseOfVersion(versionId: UUID)(using DbTx): Option[CaseRow] =
    sql"""select id, document_id, version_id, class_key, definition_version, definition, four_eyes,
                 process_definition_id, process_instance_id, round, status, current_step, returned_step,
                 content_sha256, author_id, started_by, started_at, updated_at
          from dms.workflow_case where version_id = $versionId for update"""
      .query[CaseRow]
      .run()
      .headOption

  def lockCase(id: UUID)(using DbTx): Option[CaseRow] =
    sql"""select id, document_id, version_id, class_key, definition_version, definition, four_eyes,
                 process_definition_id, process_instance_id, round, status, current_step, returned_step,
                 content_sha256, author_id, started_by, started_at, updated_at
          from dms.workflow_case where id = $id for update"""
      .query[CaseRow]
      .run()
      .headOption

  def caseById(id: UUID)(using DbTx): Option[CaseRow] =
    sql"""select id, document_id, version_id, class_key, definition_version, definition, four_eyes,
                 process_definition_id, process_instance_id, round, status, current_step, returned_step,
                 content_sha256, author_id, started_by, started_at, updated_at
          from dms.workflow_case where id = $id"""
      .query[CaseRow]
      .run()
      .headOption

  def caseOfVersion(versionId: UUID)(using DbTx): Option[CaseRow] =
    sql"""select id, document_id, version_id, class_key, definition_version, definition, four_eyes,
                 process_definition_id, process_instance_id, round, status, current_step, returned_step,
                 content_sha256, author_id, started_by, started_at, updated_at
          from dms.workflow_case where version_id = $versionId"""
      .query[CaseRow]
      .run()
      .headOption

  def casesOfDocument(documentId: DocumentId)(using DbTx): List[CaseRow] =
    sql"""select id, document_id, version_id, class_key, definition_version, definition, four_eyes,
                 process_definition_id, process_instance_id, round, status, current_step, returned_step,
                 content_sha256, author_id, started_by, started_at, updated_at
          from dms.workflow_case where document_id = ${documentId.value} order by started_at"""
      .query[CaseRow]
      .run()
      .toList

  def insertCase(c: CaseRow)(using DbTx): Unit =
    val _ = sql"""insert into dms.workflow_case
                    (id, tenant_id, document_id, version_id, class_key, definition_version, definition, four_eyes,
                     process_definition_id, process_instance_id, round, status, current_step, returned_step,
                     content_sha256, author_id, started_by)
                  values (${c.id}, dms.current_tenant(), ${c.documentId}, ${c.versionId}, ${c.classKey},
                          ${c.definitionVersion}, ${c.definition}, ${c.fourEyes}, ${c.processDefinitionId},
                          ${c.processInstanceId}, ${c.round}, ${c.status}, ${c.currentStep}, ${c.returnedStep},
                          ${c.contentSha256}, ${c.authorId}, ${c.startedBy})""".update.run()

  def updateCase(c: CaseRow)(using DbTx): Unit =
    val _ = sql"""update dms.workflow_case
                  set process_instance_id = ${c.processInstanceId}, round = ${c.round}, status = ${c.status},
                      current_step = ${c.currentStep}, returned_step = ${c.returnedStep},
                      content_sha256 = ${c.contentSha256}, updated_at = now()
                  where id = ${c.id}""".update.run()

  final case class TaskRow(
      id: String,
      caseId: UUID,
      round: Int,
      stepKey: String,
      stepKind: String,
      laneKey: String,
      role: String,
      candidates: Jsonb,
      status: String,
      createdAt: OffsetDateTime,
      completedAt: Option[OffsetDateTime],
      completedBy: Option[String]
  ) derives DbCodec:
    def candidateList: List[UserId] = candidates.value.fromJson[List[String]].getOrElse(Nil).map(UserId(_))

  def tasksOfCase(caseId: UUID)(using DbTx): List[TaskRow] =
    sql"""select id, case_id, round, step_key, step_kind, lane_key, role, candidates, status, created_at,
                 completed_at, completed_by
          from dms.workflow_task where case_id = $caseId order by created_at, id"""
      .query[TaskRow]
      .run()
      .toList

  def task(id: String)(using DbTx): Option[TaskRow] =
    sql"""select id, case_id, round, step_key, step_kind, lane_key, role, candidates, status, created_at,
                 completed_at, completed_by
          from dms.workflow_task where id = $id"""
      .query[TaskRow]
      .run()
      .headOption

  /** Open tasks the user may complete. */
  def openTasksOf(user: UserId)(using DbTx): List[TaskRow] =
    sql"""select id, case_id, round, step_key, step_kind, lane_key, role, candidates, status, created_at,
                 completed_at, completed_by
          from dms.workflow_task
          where status = 'Open' and candidates @> jsonb_build_array(${user.value}::text)
          order by created_at, id"""
      .query[TaskRow]
      .run()
      .toList

  /** Open tasks any of `users` may complete (the principals of a deputy, DMS-49). */
  def openTasksOfAny(users: List[UserId])(using DbTx): List[TaskRow] =
    sql"""select id, case_id, round, step_key, step_kind, lane_key, role, candidates, status, created_at,
                 completed_at, completed_by
          from dms.workflow_task t
          where status = 'Open'
            and exists (select 1 from jsonb_array_elements_text(${candidatesJson(users.distinct)}) p (id)
                        where t.candidates @> jsonb_build_array(p.id))
          order by created_at, id"""
      .query[TaskRow]
      .run()
      .toList

  def insertTask(t: TaskRow)(using DbTx): Unit =
    val _ = sql"""insert into dms.workflow_task
                    (id, tenant_id, case_id, round, step_key, step_kind, lane_key, role, candidates, status)
                  values (${t.id}, dms.current_tenant(), ${t.caseId}, ${t.round}, ${t.stepKey}, ${t.stepKind},
                          ${t.laneKey}, ${t.role}, ${t.candidates}, ${t.status})""".update.run()

  def updateCandidates(id: String, candidates: List[UserId], status: TaskStatus)(using DbTx): Unit =
    val _ = sql"""update dms.workflow_task set candidates = ${candidatesJson(
        candidates
      )}, status = ${status.toString}
                  where id = $id and status in ('Open', 'Waiting')""".update.run()

  def completeTask(id: String, status: TaskStatus, by: Option[UserId])(using DbTx): Unit =
    val _ = sql"""update dms.workflow_task
                  set status = ${status.toString}, completed_at = now(), completed_by = ${by.map(_.value)}
                  where id = $id""".update.run()

  final case class VoteRow(
      id: UUID,
      caseId: UUID,
      taskId: String,
      versionId: UUID,
      round: Int,
      stepKey: String,
      laneKey: String,
      voter: String,
      decision: String,
      reason: Option[String],
      contentSha256: String,
      authenticatedAt: Option[OffsetDateTime],
      votedAt: OffsetDateTime,
      invalidatedAt: Option[OffsetDateTime],
      actingFor: Option[String]
  ) derives DbCodec:
    def toVote: Vote = Vote(
      id,
      caseId,
      taskId,
      versionId,
      round,
      stepKey,
      laneKey,
      UserId(voter),
      VoteDecision.valueOf(decision),
      reason,
      contentSha256,
      authenticatedAt.map(_.toInstant),
      votedAt.toInstant,
      invalidatedAt.map(_.toInstant),
      actingFor.map(UserId(_))
    )

  def insertVote(v: Vote)(using DbTx): Unit =
    val _ = sql"""insert into dms.workflow_vote
                    (id, tenant_id, case_id, task_id, version_id, round, step_key, lane_key, voter, decision, reason,
                     content_sha256, authenticated_at, voted_at, acting_for)
                  values (${v.id}, dms.current_tenant(), ${v.caseId}, ${v.taskId}, ${v.versionId}, ${v.round},
                          ${v.step}, ${v.lane}, ${v.voter.value}, ${v.decision.toString}, ${v.reason},
                          ${v.contentSha256}, ${v.authenticatedAt.map(offset)}, ${offset(v.votedAt)},
                          ${v.actingFor.map(_.value)})""".update.run()

  def votesOfCase(caseId: UUID)(using DbTx): List[Vote] =
    sql"""select id, case_id, task_id, version_id, round, step_key, lane_key, voter, decision, reason,
                 content_sha256, authenticated_at, voted_at, invalidated_at, acting_for
          from dms.workflow_vote where case_id = $caseId order by voted_at, id"""
      .query[VoteRow]
      .run()
      .toList
      .map(_.toVote)

  /** Invalidates the valid votes of a case that were cast on other content. Returns their number. */
  def invalidateVotes(caseId: UUID, currentChecksum: String, reason: String)(using DbTx): Int =
    sql"""update dms.workflow_vote set invalidated_at = now(), invalidation_reason = $reason
          where case_id = $caseId and invalidated_at is null and content_sha256 <> $currentChecksum""".update
      .run()

  private type ApprovalRow =
    (UUID, UUID, UUID, String, String, String, OffsetDateTime, OffsetDateTime, Option[String])

  def insertApproval(a: Approval, author: UserId)(using DbTx): Unit =
    val _ = sql"""insert into dms.workflow_approval
                    (id, tenant_id, case_id, version_id, version_label, content_sha256, author_id, approved_by,
                     authenticated_at, approved_at, acting_for)
                  values (${a.id}, dms.current_tenant(), ${a.caseId}, ${a.versionId}, ${a.versionLabel},
                          ${a.contentSha256}, ${author.value}, ${a.approvedBy.value}, ${offset(
        a.authenticatedAt
      )},
                          ${offset(a.approvedAt)}, ${a.actingFor.map(_.value)})""".update.run()

  def approvalOfCase(caseId: UUID)(using DbTx): Option[Approval] =
    sql"""select id, case_id, version_id, version_label, content_sha256, approved_by, authenticated_at, approved_at,
                 acting_for
          from dms.workflow_approval where case_id = $caseId"""
      .query[ApprovalRow]
      .run()
      .headOption
      .map(toApproval)

  /** The approvals of the given versions; versions without one are missing. */
  def approvalsOfVersions(versionIds: List[UUID])(using DbTx): List[Approval] =
    if versionIds.isEmpty then Nil
    else
      val ids = versionIds.distinct.map(_.toString).toArray
      sql"""select id, case_id, version_id, version_label, content_sha256, approved_by, authenticated_at,
                   approved_at, acting_for
            from dms.workflow_approval where version_id = any(cast($ids as uuid[]))"""
        .query[ApprovalRow]
        .run()
        .map(toApproval)
        .toList

  private def toApproval(row: ApprovalRow): Approval =
    val (id, caseId, versionId, label, sha, by, authAt, at, actingFor) = row
    Approval(
      id,
      caseId,
      versionId,
      label,
      sha,
      UserId(by),
      authAt.toInstant,
      at.toInstant,
      actingFor.map(UserId(_))
    )

  def json(d: WorkflowDefinition): Jsonb = Jsonb(d.toJson)

  def candidatesJson(users: List[UserId]): Jsonb = Jsonb(users.map(_.value).toJson)

  def parse(json: Jsonb): WorkflowDefinition =
    json.value
      .fromJson[WorkflowDefinition]
      .fold(e => throw IllegalStateException(s"stored workflow: $e"), identity)

  def offset(i: Instant): OffsetDateTime = OffsetDateTime.ofInstant(i, ZoneOffset.UTC)
