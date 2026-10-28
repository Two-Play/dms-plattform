package dms.app

import dms.authz.{AccessDenied, Action, Authorizer}
import dms.db.{Db, DbError}
import dms.documents.*
import dms.documents.core.*
import dms.identity.Principal
import dms.workflow.core.{ApprovalWorkflow, WorkflowError}
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.time.{Instant, LocalDate}
import java.util.UUID

/** A review task of a valid version (US-07.1). */
final case class ReviewTaskView(
    id: UUID,
    documentId: UUID,
    /** Title of the document. */
    title: String,
    classPath: List[String],
    versionId: UUID,
    /** Version number, e.g. `2.0`. */
    versionLabel: String,
    /** DocumentStatus of the version now: Published, ReviewDue (review date reached), or after a decision
      * e.g. Withdrawn or Superseded.
      */
    versionStatus: String,
    /** Open, Recertified, Revised, Withdrawn or Obsolete (the version stopped being valid without a
      * decision).
      */
    status: String,
    /** The review date. */
    dueOn: LocalDate,
    /** When the task was created (lead time of the class before the review date). */
    createdAt: Instant,
    decidedAt: Option[Instant],
    decidedBy: Option[String],
    /** For Recertified: the next review date. */
    nextDueOn: Option[LocalDate],
    /** For Revised: the new version (draft) started for the revision. */
    newVersionId: Option[UUID],
    /** The owner's note; for Withdrawn the reason. */
    comment: Option[String],
    /** Review interval of the document's class; recertifying without a date sets today plus this interval. */
    reviewIntervalMonths: Option[Int]
) derives JsonCodec,
      Schema

final case class RecertifyVersion(
    /** The next review date, after today. Default: today plus the class's review interval (required if the
      * class has none).
      */
    nextReviewOn: Option[LocalDate],
    comment: Option[String]
) derives JsonCodec,
      Schema

final case class ReviseVersion(
    /** Major (default; change of content, full workflow) or Minor (editorial correction, reason required). */
    changeKind: Option[String],
    reason: Option[String]
) derives JsonCodec,
      Schema

final case class WithdrawVersion(
    /** Why the version is not needed any more; required. */
    reason: String
) derives JsonCodec,
      Schema

/** Periodic review and recertification (business concept 6.2, US-07.1, ADR 0030).
  *
  * The document owner gets a review task when the class's lead time before the review date of a valid version
  * is reached, and decides once: recertify (new review date, no new version; `Recertify`: owner role and
  * owner of the document), revise (new draft from the valid version; `EditDraft` as for a new version) or
  * withdraw (the version goes out of force; `Withdraw`). Only the document's owner decides; deputies do not.
  */
object ReviewApi:
  type Env = DocumentApi.Env & ReviewService & DocumentClassService

  private val reviews = "api" / "v1" / "reviews"
  private val taskId  = path[UUID]("taskId").description("Review task id")

  val list: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(reviews)
      .in(query[Option[String]]("status").description("open (default): to decide; done: decided or closed"))
      .out(jsonBody[List[ReviewTaskView]])
      .description(
        "Review tasks of the documents the caller is the owner of. open: tasks of valid versions waiting for a " +
          s"decision, the earliest review date first; done: decided and closed tasks, the latest first. At most " +
          s"${ReviewService.MaxTasks}."
      )
      .tag("Reviews")
      .serverLogic(p =>
        status =>
          status.getOrElse("open") match
            case s @ ("open" | "done") =>
              inTransaction(p) { (ctx, reviews, classes) =>
                for
                  tasks <- reviews.tasksOf(p.userId, open = s == "open")
                  views <- ZIO.foreach(tasks)(t =>
                    ctx.service
                      .get(t.documentId)
                      .flatMap(details =>
                        ZIO
                          .foreach(details.versions.find(_.id == t.versionId))(v =>
                            ctx
                              .allowed(Action.ReadDocument, DocumentApi.resource(p.tenantId, details, v))
                              .flatMap(readable =>
                                ZIO.when(readable)(intervalOf(classes, details).map(view(details, v, t, _)))
                              )
                          )
                          .map(_.flatten)
                      )
                  )
                yield views.flatten
              }
            case other =>
              ZIO.fail((StatusCode.BadRequest, ApiError("invalid", s"unknown status '$other'; open or done")))
      )

  val recertify: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(reviews / taskId / "recertify")
      .in(jsonBody[RecertifyVersion])
      .out(jsonBody[ReviewTaskView])
      .description(
        "Recertifies the version without a change: it keeps its content and number and gets a new review date " +
          "(`nextReviewOn`, or today plus the class's interval; 422 if neither or not after today); a version in " +
          "ReviewDue is Published again. Recorded in the audit trail. 403 unless the caller is the document's " +
          "owner, 409 if the task was decided already or the version is not valid any more."
      )
      .tag("Reviews")
      .serverLogic(p =>
        (id, req) =>
          inTransaction(p) { (ctx, reviews, classes) =>
            for
              found <- ownersVersion(p, ctx, reviews, id)
              (details, version) = found
              _       <- ctx.check(Action.Recertify, DocumentApi.resource(p.tenantId, details, version))
              decided <- reviews.recertify(id, req.nextReviewOn, req.comment, p.userId)
              result  <- viewAfter(ctx, classes, decided)
            yield result
          }
      )

  val revise: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(reviews / taskId / "revise")
      .in(jsonBody[ReviseVersion])
      .out(jsonBody[ReviewTaskView])
      .description(
        "Revision required: starts the next version as a draft from the valid version (Major by default; Minor " +
          "needs a reason) or links the version already open; the valid version stays in force until the new one " +
          "is published. `newVersionId` names the draft. 403 unless the caller is the document's owner and may " +
          "edit drafts, 409 if the task was decided already or an approved version waits for its valid-from date."
      )
      .tag("Reviews")
      .serverLogic(p =>
        (id, req) =>
          inTransaction(p) { (ctx, reviews, classes) =>
            for
              kind <- ZIO
                .fromOption(ChangeKind.values.find(_.toString == req.changeKind.getOrElse("Major")))
                .orElseFail(
                  DocumentError
                    .Invalid(List(s"unknown change kind ${req.changeKind.getOrElse("")}; Major or Minor"))
                )
              found <- ownersVersion(p, ctx, reviews, id)
              (details, version) = found
              // The caller becomes the author of a draft of this document, as for a new version.
              _ <- ctx.check(
                Action.EditDraft,
                DocumentApi
                  .resource(p.tenantId, details, version)
                  .copy(status = Some(DocumentStatus.Draft), authorId = Some(p.userId))
              )
              decided <- reviews.revise(id, kind, req.reason, p.userId)
              result  <- viewAfter(ctx, classes, decided._1)
            yield result
          }
      )

  val withdraw: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(reviews / taskId / "withdraw")
      .in(jsonBody[WithdrawVersion])
      .out(jsonBody[ReviewTaskView])
      .description(
        "Not needed any more: the version goes out of force (Withdrawn) without a successor; other versions of " +
          "the document (e.g. an approved one waiting for its valid-from date) are not affected. The reason is " +
          "required (422). 403 unless the caller is the document's owner and may withdraw, 409 if the task was " +
          "decided already or the version is not valid any more."
      )
      .tag("Reviews")
      .serverLogic(p =>
        (id, req) =>
          inTransaction(p) { (ctx, reviews, classes) =>
            for
              found <- ownersVersion(p, ctx, reviews, id)
              (details, version) = found
              _       <- ctx.check(Action.Withdraw, DocumentApi.resource(p.tenantId, details, version))
              decided <- reviews.withdraw(id, req.reason, p.userId)
              result  <- viewAfter(ctx, classes, decided)
            yield result
          }
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(list, recertify, revise, withdraw)

  private type Failure = DocumentError | AccessDenied | WorkflowError

  /** The task's document and version; only the document's owner may decide (403 otherwise, 404 if the task
    * does not exist).
    */
  private def ownersVersion(
      p: Principal,
      ctx: DocumentApi.Context,
      reviews: ReviewService,
      id: UUID
  ): IO[Failure, (DocumentDetails, DocumentVersion)] =
    for
      task    <- reviews.task(id)
      details <- ctx.service.get(task.documentId)
      _       <- ZIO.unless(details.document.ownerId.contains(p.userId))(
        ZIO.logWarning(s"Access denied: review task $id belongs to another owner") *>
          ZIO.fail(AccessDenied(Action.Recertify, "only the document's owner decides on its review"))
      )
      version <- ZIO
        .fromOption(details.versions.find(_.id == task.versionId))
        .orElseFail(DocumentError.NotFound(s"the version of review task $id does not exist"))
    yield (details, version)

  private def viewAfter(
      ctx: DocumentApi.Context,
      classes: DocumentClassService,
      task: ReviewTask
  ): IO[Failure, ReviewTaskView] =
    for
      details <- ctx.service.get(task.documentId)
      version <- ZIO
        .fromOption(details.versions.find(_.id == task.versionId))
        .orElseFail(DocumentError.NotFound(s"the version of review task ${task.id} does not exist"))
      interval <- intervalOf(classes, details)
    yield view(details, version, task, interval)

  private def intervalOf(
      classes: DocumentClassService,
      details: DocumentDetails
  ): IO[DocumentError, Option[Int]] =
    classes
      .reviewPolicy(details.document.classId)
      .mapError(DocumentService.metadataError)
      .map(_.intervalMonths)

  private def view(details: DocumentDetails, v: DocumentVersion, t: ReviewTask, interval: Option[Int]) =
    ReviewTaskView(
      t.id,
      details.document.id.value,
      details.document.title,
      details.profile.classPath.map(_.value),
      v.id,
      v.label,
      v.status.toString,
      t.status.toString,
      t.dueOn,
      t.createdAt,
      t.decidedAt,
      t.decidedBy.map(_.value),
      t.nextDueOn,
      t.newVersionId,
      t.comment,
      interval
    )

  private def inTransaction[A](principal: Principal)(
      f: (DocumentApi.Context, ReviewService, DocumentClassService) => ZIO[Any, Failure, A]
  ): ZIO[Env, (StatusCode, ApiError), A] =
    (for
      db       <- ZIO.service[Db]
      authz    <- ZIO.service[Authorizer]
      service  <- ZIO.service[DocumentService]
      workflow <- ZIO.service[ApprovalWorkflow]
      reviews  <- ZIO.service[ReviewService]
      classes  <- ZIO.service[DocumentClassService]
      result   <- db.transact(principal.tenantId)(
        authz
          .subject(principal)
          .flatMap(subject => f(DocumentApi.Context(service, workflow, authz, subject), reviews, classes))
      )
    yield result).mapError {
      case e: WorkflowError => WorkflowApi.toResponse(e)
      case e: AccessDenied  => DocumentApi.toResponse(e)
      case e: DbError       => DocumentApi.toResponse(e)
      case e: DocumentError => DocumentApi.toResponse(e)
    }
