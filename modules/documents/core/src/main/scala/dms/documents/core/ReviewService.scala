package dms.documents.core

import com.augustnagro.magnum.DbTx
import dms.audit.*
import dms.db.queue.Outbox
import dms.db.{Db, Jsonb}
import dms.documents.*
import dms.kernel.{DocumentId, UserId}
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.LocalDate
import java.util.UUID

/** What the periodic review check of a tenant has to do on a day (see [[ReviewService.pendingWork]]).
  *
  * @param obsolete
  *   open tasks whose version is not valid any more
  * @param toOpen
  *   versions whose review task is due
  * @param reviewDue
  *   published versions whose review date has come
  */
final case class ReviewWork(obsolete: List[UUID], toOpen: List[UUID], reviewDue: List[UUID]):
  def isEmpty: Boolean = obsolete.isEmpty && toOpen.isEmpty && reviewDue.isEmpty

/** Periodic review and recertification of valid versions (business concept 6.2, US-07.1, ADR 0030).
  *
  * A valid version gets its review date when it becomes valid ([[DocumentService]], [[Review.dueOn]]). When
  * the class's lead time before that date is reached, [[openTask]] creates a review task for the document
  * owner; on the review date [[markReviewDue]] moves a version still published to ReviewDue (it stays valid).
  * The owner decides once: [[recertify]] (new review date, same version), [[revise]] (new draft, the version
  * stays valid until the draft is published) or [[withdraw]] (the version goes out of force; other versions,
  * e.g. an approved one waiting for its valid-from date, are not affected).
  *
  * Every method must run inside `Db.transact(tenant)`; every change is recorded in the audit trail and
  * published to the outbox in the same transaction. Authorization is the caller's job: decisions are the
  * document owner's (no deputies).
  */
final class ReviewService(audit: AuditTrail, documents: DocumentService, classes: DocumentClassService):
  import ReviewService.*

  /** The tenant's local date now; review dates and lead times count in the tenant's time zone. */
  def today: IO[DocumentError, LocalDate] =
    for
      zone <- documents.tenantZone
      now  <- Clock.instant
    yield LocalDate.ofInstant(now, zone)

  /** What the periodic check has to do today. */
  def pendingWork: IO[DocumentError, ReviewWork] =
    today.flatMap(day =>
      sql(
        ReviewWork(
          ReviewStore.obsoleteTasks(),
          ReviewStore.tasksToOpen(day).map(_.versionId),
          ReviewStore.reviewDateReached(day)
        )
      )
    )

  /** Creates the review task of a valid version if its lead time is reached and it has none for its current
    * review date yet. Idempotent: returns `None` if there is nothing to do.
    */
  def openTask(versionId: UUID): IO[DocumentError, Option[ReviewTask]] =
    for
      day       <- today
      candidate <- sql(ReviewStore.lockVersion(versionId))
      now       <- Clock.instant
      created   <- candidate match
        case Some(c)
            if Versioning.isValid(DocumentStatus.valueOf(c.status)) &&
              Review.taskDue(c.dueOn, ReviewPolicy(leadDays = c.leadDays), day) =>
          val task = ReviewTask(
            UUID.randomUUID(),
            DocumentId(c.documentId),
            c.versionId,
            c.dueOn,
            now,
            ReviewStatus.Open
          )
          sql(ReviewStore.insertTask(task.id, task.documentId, task.versionId, task.dueOn, now)).flatMap(
            inserted =>
              ZIO.when(inserted)(
                recorded(
                  ReviewActor,
                  "document.review-task-created",
                  task,
                  Json.Obj("dueOn" -> Json.Str(task.dueOn.toString), "leadDays" -> Json.Num(c.leadDays))
                ).as(task)
              )
          )
        case _ => ZIO.none
    yield created

  /** Moves a published version whose review date has come to ReviewDue (it stays valid). Idempotent. */
  def markReviewDue(versionId: UUID): IO[DocumentError, Option[DocumentVersion]] =
    for
      day       <- today
      candidate <- sql(ReviewStore.lockVersion(versionId))
      changed   <- candidate match
        case Some(c) if c.status == DocumentStatus.Published.toString && !c.dueOn.isAfter(day) =>
          documents.changeStatus(versionId, LifecycleEvent.ReviewDueReached, ReviewActor).asSome
        case _ => ZIO.none
    yield changed

  /** Closes an open task whose version is not valid any more (e.g. superseded by a version started outside
    * the review). Idempotent.
    */
  def closeObsolete(taskId: UUID): IO[DocumentError, Option[ReviewTask]] =
    for
      task    <- sql(ReviewStore.lockTask(taskId)).someOrFail(taskNotFound(taskId))
      version <- sql(ReviewStore.versionStatus(task.versionId))
      now     <- Clock.instant
      closed  <-
        if !task.isOpen || version.exists(Versioning.isValid) then ZIO.none
        else
          val obsolete = task.copy(status = ReviewStatus.Obsolete, decidedAt = Some(now))
          decide(
            obsolete,
            ReviewActor,
            Json.Obj("versionStatus" -> Json.Str(version.fold("")(_.toString)))
          ).asSome
    yield closed

  def task(id: UUID): IO[DocumentError, ReviewTask] =
    sql(ReviewStore.task(id)).someOrFail(taskNotFound(id))

  /** Review tasks of the documents `owner` is the owner of: open ones by review date, or decided ones, the
    * latest first.
    */
  def tasksOf(owner: UserId, open: Boolean, limit: Int = MaxTasks): IO[DocumentError, List[ReviewTask]] =
    sql(ReviewStore.tasksOfOwner(owner, open, limit))

  /** Recertifies the version without a change (business concept 6.2 a): it keeps its content and number and
    * gets a new review date, `nextDueOn` or today plus the class's interval; a version in ReviewDue is
    * Published again. Recorded as `document.recertified`.
    */
  def recertify(
      taskId: UUID,
      nextDueOn: Option[LocalDate],
      comment: Option[String],
      actor: UserId
  ): IO[DocumentError, ReviewTask] =
    for
      task     <- openTaskForDecision(taskId)
      details  <- validVersion(task)
      policy   <- classes.reviewPolicy(details._1.classId).mapError(DocumentService.metadataError)
      day      <- today
      next     <- ZIO.fromEither(Review.recertifiedDueOn(nextDueOn, policy, day)).mapError(e => invalid(e))
      note     <- ZIO.fromEither(note(comment, required = false))
      previous <- sql(ReviewStore.dueOn(task.versionId))
      _        <- sql(ReviewStore.setDueOn(task.versionId, next)).filterOrFail(identity)(notValid(task))
      _        <- ZIO.when(details._2.status == DocumentStatus.ReviewDue)(
        documents.changeStatus(task.versionId, LifecycleEvent.Recertify, actor)
      )
      now <- Clock.instant
      decided = task.copy(
        status = ReviewStatus.Recertified,
        decidedAt = Some(now),
        decidedBy = Some(actor),
        nextDueOn = Some(next),
        comment = note
      )
      result <- decide(
        decided,
        actor,
        Json.Obj(
          "previousDueOn" -> previous.fold(Json.Null)(d => Json.Str(d.toString)),
          "nextDueOn"     -> Json.Str(next.toString)
        )
      )
    yield result

  /** Revision required (business concept 6.2 b): starts the next version as a draft from the valid one, or
    * links the version already open (a document has at most one); the valid version stays in force until the
    * new one is published.
    */
  def revise(
      taskId: UUID,
      kind: ChangeKind,
      reason: Option[String],
      actor: UserId
  ): IO[DocumentError, (ReviewTask, DocumentVersion)] =
    for
      task    <- openTaskForDecision(taskId)
      _       <- validVersion(task)
      open    <- documents.get(task.documentId).map(_.open)
      created <- open.fold(documents.newVersion(task.documentId, kind, reason, actor))(ZIO.succeed(_))
      now     <- Clock.instant
      decided = task.copy(
        status = ReviewStatus.Revised,
        decidedAt = Some(now),
        decidedBy = Some(actor),
        newVersionId = Some(created.id),
        comment = reason.map(_.trim).filter(_.nonEmpty)
      )
      result <- decide(
        decided,
        actor,
        Json.Obj(
          "newVersion" -> Json.Str(created.label),
          "changeKind" -> Json.Str(created.changeKind.toString),
          "linked"     -> Json.Bool(open.isDefined)
        )
      )
    yield (result, created)

  /** Not needed any more (business concept 6.2 c): the version goes out of force (Withdrawn) without a
    * successor. Other versions of the document, e.g. an approved one waiting for its valid-from date or one
    * of another validity scope, are not affected. The reason is required.
    */
  def withdraw(taskId: UUID, reason: String, actor: UserId): IO[DocumentError, ReviewTask] =
    for
      task <- openTaskForDecision(taskId)
      text <- ZIO.fromEither(note(Some(reason), required = true))
      _    <- validVersion(task)
      _    <- documents.changeStatus(task.versionId, LifecycleEvent.Withdraw, actor)
      now  <- Clock.instant
      decided = task.copy(
        status = ReviewStatus.Withdrawn,
        decidedAt = Some(now),
        decidedBy = Some(actor),
        comment = text
      )
      result <- decide(decided, actor, Json.Obj())
    yield result

  private def openTaskForDecision(taskId: UUID): IO[DocumentError, ReviewTask] =
    sql(ReviewStore.lockTask(taskId))
      .someOrFail(taskNotFound(taskId))
      .filterOrElseWith(_.isOpen)(t =>
        ZIO.fail(DocumentError.Conflict(s"review task ${t.id} is already ${t.status}"))
      )

  /** The document and the task's version; the version must still be valid. */
  private def validVersion(task: ReviewTask): IO[DocumentError, (Document, DocumentVersion)] =
    for
      details <- documents.get(task.documentId)
      version <- ZIO
        .fromOption(details.versions.find(v => v.id == task.versionId && Versioning.isValid(v.status)))
        .orElseFail(notValid(task))
    yield (details.document, version)

  private def decide(task: ReviewTask, actor: UserId, details: Json.Obj): IO[DocumentError, ReviewTask] =
    for
      _ <- sql(ReviewStore.decide(task)).filterOrFail(identity)(
        DocumentError.Conflict(s"review task ${task.id} was decided concurrently")
      )
      _ <- recorded(
        actor,
        decisionAction(task.status),
        task,
        details.merge(
          Json.Obj(
            "dueOn"   -> Json.Str(task.dueOn.toString),
            "outcome" -> Json.Str(task.status.toString),
            "comment" -> task.comment.fold(Json.Null)(Json.Str(_))
          )
        )
      )
    yield task

  /** Records a review event in the audit trail and the outbox (event type = audit action). */
  private def recorded(actor: UserId, action: String, task: ReviewTask, details: Json.Obj) =
    val platform = DocumentService.isPlatform(actor)
    for
      label <- sql(ReviewStore.versionLabel(task.versionId))
      payload = details.merge(
        Json.Obj(
          "taskId"     -> Json.Str(task.id.toString),
          "documentId" -> Json.Str(task.documentId.value.toString),
          "versionId"  -> Json.Str(task.versionId.toString)
        )
      )
      _ <- audit
        .record(
          AuditEvent(
            Actor(actor.value, if platform then ActorKind.Technical else ActorKind.Person),
            action,
            AuditObject("document", Some(task.documentId.value), label),
            if platform then Channel.Automation else Channel.Api,
            Jsonb(payload.toJson)
          )
        )
        .mapError(DocumentError.Database(_))
      _ <- Outbox
        .publish("document", task.documentId.value, action, Jsonb(payload.toJson))
        .mapError(DocumentError.Database(_))
    yield ()

object ReviewService:
  val layer: URLayer[AuditTrail & DocumentService & DocumentClassService, ReviewService] =
    ZLayer.fromFunction(ReviewService(_, _, _))

  /** The platform as actor of the periodic review check; recorded as technical actor. */
  val ReviewActor: UserId = UserId("system:review")

  /** Largest number of tasks listed at once. */
  val MaxTasks: Int = 200

  /** Longest accepted comment or reason. */
  val MaxCommentLength: Int = 4000

  private def sql[A](f: DbTx ?=> A): IO[DocumentError, A] = Db.sql(f).mapError(DocumentError.Database(_))

  private def invalid(problem: String) = DocumentError.Invalid(List(problem))

  private def note(text: Option[String], required: Boolean): Either[DocumentError, Option[String]] =
    text.map(_.trim).filter(_.nonEmpty) match
      case None if required                       => Left(invalid("withdrawing needs a reason"))
      case Some(t) if t.length > MaxCommentLength =>
        Left(invalid(s"the text must not exceed $MaxCommentLength characters"))
      case other => Right(other)

  private def decisionAction(status: ReviewStatus): String = status match
    case ReviewStatus.Recertified => "document.recertified"
    case ReviewStatus.Revised     => "document.review-revised"
    case ReviewStatus.Withdrawn   => "document.review-withdrawn"
    case ReviewStatus.Obsolete    => "document.review-task-closed"
    case ReviewStatus.Open        => "document.review-task-created"

  private def taskNotFound(id: UUID) = DocumentError.NotFound(s"review task $id does not exist")

  private def notValid(task: ReviewTask) =
    DocumentError.Conflict(s"the version of review task ${task.id} is not valid any more")
