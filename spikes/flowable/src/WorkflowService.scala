package spike.flowable

import org.flowable.common.engine.api.{FlowableObjectNotFoundException, FlowableException}
import org.flowable.engine.ProcessEngine
import org.flowable.task.api.Task as FTask
import zio.*

import java.util.UUID
import scala.jdk.CollectionConverters.*

// ---- Types that would live in modules/workflow/api (no Flowable types) ----
final case class DocumentRef(documentId: UUID, version: Int):
  def businessKey: String = s"$documentId:$version"

final case class ProcessRef(processInstanceId: String, definitionId: String, definitionVersion: Int)
final case class TaskView(
    id: String,
    name: String,
    taskKey: String,
    processInstanceId: String,
    document: Option[DocumentRef],
    candidateGroups: List[String]
)

enum WorkflowError:
  case ProcessDefinitionNotFound(key: String, tenant: String)
  case TaskNotFound(taskId: String)
  case NotCandidate(taskId: String, user: String)
  case Engine(message: String, cause: Throwable)

trait WorkflowService:
  def deploy(tenant: String, resource: String): IO[WorkflowError, String]
  def startReview(tenant: String, doc: DocumentRef, initiator: String, approvalTimeout: String): IO[WorkflowError, ProcessRef]
  def listTasks(tenant: String, user: String, groups: List[String]): IO[WorkflowError, List[TaskView]]
  def tasksForDocument(tenant: String, doc: DocumentRef): IO[WorkflowError, List[TaskView]]
  def completeTask(taskId: String, user: String, groups: List[String], vars: Map[String, Any] = Map.empty): IO[WorkflowError, Unit]

// ---- modules/workflow/core ----
final class FlowableWorkflowService(engine: ProcessEngine) extends WorkflowService:
  private val rt   = engine.getRuntimeService
  private val ts   = engine.getTaskService
  private val repo = engine.getRepositoryService
  val ProcessKey   = "documentReview"

  private def call[A](f: => A): IO[WorkflowError, A] =
    Tx.blocking(f).mapError {
      case e: WorkflowErrorException => e.error
      case e: FlowableObjectNotFoundException => WorkflowError.Engine(e.getMessage, e)
      case e => WorkflowError.Engine(String.valueOf(e.getMessage), e)
    }

  def deploy(tenant: String, resource: String): IO[WorkflowError, String] =
    call(repo.createDeployment().name(resource).tenantId(tenant).addClasspathResource(resource).enableDuplicateFiltering().deploy().getId)

  def startReview(tenant: String, doc: DocumentRef, initiator: String, approvalTimeout: String): IO[WorkflowError, ProcessRef] =
    call {
      if repo.createProcessDefinitionQuery().processDefinitionKey(ProcessKey).processDefinitionTenantId(tenant).count() == 0 then
        throw WorkflowErrorException(WorkflowError.ProcessDefinitionNotFound(ProcessKey, tenant))
      engine.getIdentityService.setAuthenticatedUserId(initiator) // fills flowable:initiator
      try
        val pi = rt
          .createProcessInstanceBuilder()
          .processDefinitionKey(ProcessKey)
          .tenantId(tenant)
          .businessKey(doc.businessKey)
          .variable("documentId", doc.documentId.toString)
          .variable("documentVersion", doc.version)
          .variable("approvalTimeout", approvalTimeout)
          .start()
        val pd = repo.getProcessDefinition(pi.getProcessDefinitionId)
        ProcessRef(pi.getId, pd.getId, pd.getVersion)
      finally engine.getIdentityService.setAuthenticatedUserId(null)
    }

  private def toView(t: FTask): TaskView =
    val vars = t.getProcessVariables.asScala
    val doc  = for id <- vars.get("documentId"); v <- vars.get("documentVersion") yield DocumentRef(UUID.fromString(id.toString), v.toString.toInt)
    val groups = ts.getIdentityLinksForTask(t.getId).asScala.toList.flatMap(l => Option(l.getGroupId))
    TaskView(t.getId, t.getName, t.getTaskDefinitionKey, t.getProcessInstanceId, doc, groups)

  def listTasks(tenant: String, user: String, groups: List[String]): IO[WorkflowError, List[TaskView]] =
    call(
      ts.createTaskQuery().taskTenantId(tenant).taskCandidateOrAssigned(user).taskCandidateGroupIn(groups.asJava)
        .includeProcessVariables().orderByTaskCreateTime().asc().list().asScala.toList.map(toView)
    )

  // Correlation: business key "<documentId>:<version>" (indexed column) or process variables.
  def tasksForDocument(tenant: String, doc: DocumentRef): IO[WorkflowError, List[TaskView]] =
    call(
      ts.createTaskQuery().taskTenantId(tenant).processInstanceBusinessKey(doc.businessKey)
        .includeProcessVariables().list().asScala.toList.map(toView)
    )

  def completeTask(taskId: String, user: String, groups: List[String], vars: Map[String, Any]): IO[WorkflowError, Unit] =
    call {
      if ts.createTaskQuery().taskId(taskId).count() == 0 then throw WorkflowErrorException(WorkflowError.TaskNotFound(taskId))
      val allowed = ts.createTaskQuery().taskId(taskId).taskCandidateOrAssigned(user).taskCandidateGroupIn(groups.asJava).count() > 0
      if !allowed then throw WorkflowErrorException(WorkflowError.NotCandidate(taskId, user))
      ts.claim(taskId, user)
      ts.complete(taskId, vars.map((k, v) => k -> v.asInstanceOf[AnyRef]).asJava)
    }

final class WorkflowErrorException(val error: WorkflowError) extends RuntimeException(error.toString)
