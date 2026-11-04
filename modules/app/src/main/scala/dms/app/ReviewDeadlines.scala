package dms.app

import dms.documents.ReviewTask
import dms.documents.core.{DocumentError, DocumentService, ReviewService}
import dms.kernel.UserId
import dms.workflow.DeadlinePolicy
import dms.workflow.core.{ApprovalWorkflow, DeadlineSource, DeadlineStage, Deadlines, WorkflowError}
import zio.*
import zio.json.ast.Json

import java.time.{Instant, ZoneId}
import java.util.UUID
import scala.util.Try

/** Reminder, overdue notice and escalation of periodic review tasks (DMS-141, ADR 0057) as a source of the
  * deadline check of workflow tasks (ADR 0053): same job, same stages, same working-day rules, same policy.
  *
  * A review task is due at the end of its review date. Its times follow from that date and the policy in
  * force (the tenant's default, else the installation's), so open tasks from before this change are covered
  * too. Every notice is recorded in `dms.document_review_deadline_notice` under the task's row lock, in the
  * same transaction as the audit entry and the outbox event; the notifications module delivers it (ADR 0052).
  */
final class ReviewDeadlines(reviews: ReviewService, documents: DocumentService, workflow: ApprovalWorkflow)
    extends DeadlineSource:
  import ReviewDeadlines.*

  def dueNotices(now: Instant): IO[WorkflowError, List[(String, DeadlineStage)]] =
    for
      policy <- workflow.defaultDeadlines
      zone   <- inDocuments(documents.tenantZone)
      // A reminder comes at most this many working days before the review date.
      until = Deadlines.shift(now, policy.reminderWorkingDays, zone).atZone(zone).toLocalDate.plusDays(1)
      open <- inDocuments(reviews.openForDeadlines(until))
    yield open.flatMap((task, sent) => pending(task, sent, policy, zone, now).map(task.id.toString -> _))

  def notice(id: String, stage: DeadlineStage, now: Instant): IO[WorkflowError, Boolean] =
    for
      taskId <- ZIO
        .fromTry(Try(UUID.fromString(id)))
        .orElseFail(WorkflowError.Invalid(List(s"$id is not a review task id")))
      locked    <- inDocuments(reviews.lockForDeadline(taskId))
      policy    <- workflow.defaultDeadlines
      zone      <- inDocuments(documents.tenantZone)
      published <- locked match
        case Some((task, sent)) if pending(task, sent, policy, zone, now).contains(stage) =>
          publish(task, stage, policy)
        case _ => ZIO.succeed(false)
    yield published

  private def publish(
      task: ReviewTask,
      stage: DeadlineStage,
      policy: DeadlinePolicy
  ): IO[WorkflowError, Boolean] =
    for
      audience <- workflow.ownerDeadlineAudience(task.documentId, task.versionId, stage, policy)
      details = Json.Obj(
        "stage"      -> Json.Str(stage.key),
        "dueOn"      -> Json.Str(task.dueOn.toString),
        "recipients" -> users(audience.recipients),
        "deputies"   -> Json.Arr(
          audience.deputies.map(d =>
            Json.Obj("deputy" -> Json.Str(d.deputy.value), "principal" -> Json.Str(d.principal.value))
          )*
        ),
        "escalatedTo" -> users(audience.escalatedTo)
      )
      published <- inDocuments(reviews.noticeDeadline(task, stage.key, eventOf(stage), details))
    yield published

object ReviewDeadlines:

  /** The outbox event and audit action of a stage. */
  def eventOf(stage: DeadlineStage): String = stage match
    case DeadlineStage.Reminder  => "document.review-task-due-soon"
    case DeadlineStage.Overdue   => "document.review-task-overdue"
    case DeadlineStage.Escalated => "document.review-task-escalated"

  /** The notice of the latest stage reached, unless it or a later one was published: the policy may have
    * changed since, so a stage can seem to come after a later one.
    */
  private def pending(
      task: ReviewTask,
      sent: Set[String],
      policy: DeadlinePolicy,
      zone: ZoneId,
      now: Instant
  ): Option[DeadlineStage] =
    Deadlines
      .stage(Deadlines.onDate(policy, task.dueOn, task.createdAt, zone), now)
      .filterNot(stage => DeadlineStage.values.drop(stage.ordinal).exists(s => sent(s.key)))

  private def users(people: List[UserId]): Json = Json.Arr(people.map(u => Json.Str(u.value))*)

  private def inDocuments[A](work: IO[DocumentError, A]): IO[WorkflowError, A] =
    work.mapError(WorkflowDocuments.toWorkflowError)
