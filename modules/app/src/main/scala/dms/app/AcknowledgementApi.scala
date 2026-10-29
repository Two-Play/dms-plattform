package dms.app

import dms.authz.{AccessDenied, Authorizer}
import dms.db.{Db, DbError}
import dms.documents.*
import dms.documents.core.*
import dms.identity.Principal
import dms.kernel.DocumentId
import dms.workflow.core.{ApprovalWorkflow, WorkflowError}
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.time.{Instant, LocalDate}
import java.util.UUID

/** A version in the caller's acknowledgement overview (DMS-47): open (to acknowledge) or done. */
final case class AcknowledgementItemView(
    documentId: UUID,
    versionId: UUID,
    /** Title of the document. */
    title: String,
    /** Version number, e.g. `2.0`. */
    versionLabel: String,
    classPath: List[String],
    validFrom: Option[LocalDate],
    /** When the version became valid. */
    publishedAt: Option[Instant],
    /** DocumentStatus of the version now, e.g. Published or (for done items) Superseded. */
    status: String,
    /** True once the caller opened the version; acknowledging is possible from then on. */
    opened: Boolean,
    openedAt: Option[Instant],
    acknowledgedAt: Option[Instant]
) derives JsonCodec,
      Schema

/** A recorded acknowledgement. */
final case class AcknowledgementView(
    documentId: UUID,
    versionId: UUID,
    versionLabel: String,
    openedAt: Instant,
    acknowledgedAt: Instant
) derives JsonCodec,
      Schema

/** What the caller did with one version. */
final case class AcknowledgementStateView(
    versionId: UUID,
    versionLabel: String,
    /** True if the version asks for acknowledgement now: valid and requiring acknowledgement. */
    requested: Boolean,
    opened: Boolean,
    openedAt: Option[Instant],
    acknowledgedAt: Option[Instant]
) derives JsonCodec,
      Schema

/** Acknowledgement of valid versions (business concept 6.3, US-06.2, ADR 0027).
  *
  * Who must acknowledge: everyone who may read the valid version (`ReadPublished`: every business role whose
  * assignment covers the document's class and organizational unit, respecting confidentiality) and passes the
  * recipient filter of its document class recorded at publication (DMS-100, ADR 0048). Opening is recorded by
  * the client when it displays the version (`POST .../opened`); acknowledging requires it. Opening,
  * acknowledging and the per-version state require `ReadDocument` on the version.
  */
object AcknowledgementApi:
  type Env = DocumentApi.Env & AcknowledgementService

  private val version =
    "api" / "v1" / "documents" / path[UUID]("id").description("Document id") / "versions" /
      path[UUID]("versionId").description("Version id")

  val list: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "acknowledgements")
      .in(
        query[Option[String]]("status").description(
          "open (default): still to acknowledge; done: acknowledged"
        )
      )
      .out(jsonBody[List[AcknowledgementItemView]])
      .description(
        "The caller's acknowledgements. open: valid versions that require acknowledgement and that the caller may " +
          "read but has not acknowledged, the most recently published first; versions that were superseded drop " +
          "out. done: the caller's acknowledgements, the latest first, also of versions superseded since."
      )
      .tag("Acknowledgements")
      .serverLogic(p =>
        status =>
          status.getOrElse("open") match
            case "open" => inTransaction(p)((ctx, acks) => open(p, ctx, acks))
            case "done" => inTransaction(p)((ctx, acks) => done(p, ctx, acks))
            case other  =>
              ZIO.fail(
                (StatusCode.BadRequest, ApiError("invalid", s"unknown status '$other'; open or done"))
              )
      )

  val opened: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(version / "opened")
      .out(statusCode(StatusCode.NoContent))
      .description(
        "Records that the caller opened (displayed) the version; acknowledging requires it. Idempotent: the first " +
          "opening is kept. Recorded only for valid versions that require acknowledgement, otherwise nothing " +
          "happens."
      )
      .tag("Acknowledgements")
      .serverLogic(p =>
        (id, versionId) =>
          inTransaction(p) { (ctx, acks) =>
            for
              found     <- readableVersion(ctx, id, versionId)
              recipient <- isRecipient(ctx, acks, found._1, found._2)
              _         <- ZIO.when(recipient)(acks.markOpened(found._2, p.userId))
            yield ()
          }
      )

  val acknowledge: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(version / "acknowledgement")
      .out(statusCode(StatusCode.Created).and(jsonBody[AcknowledgementView]))
      .description(
        "Acknowledges the version for the caller, with version and timestamp; recorded once and never changed. " +
          "409 not_opened if the caller did not open it before, 409 already_acknowledged, 422 if the version is " +
          "not valid or does not require acknowledgement."
      )
      .tag("Acknowledgements")
      .serverLogic(p =>
        (id, versionId) =>
          inTransaction(p) { (ctx, acks) =>
            for
              found     <- readableVersion(ctx, id, versionId)
              recipient <- isRecipient(ctx, acks, found._1, found._2)
              _         <- ZIO.unless(recipient)(
                ZIO.fail(
                  AcknowledgementError.NotRequested(
                    s"version ${found._2.label} does not ask the caller for acknowledgement: they are not among " +
                      "the recipients its document class sets"
                  )
                )
              )
              a <- acks.acknowledge(found._2, p.userId)
            yield AcknowledgementView(
              a.documentId.value,
              a.versionId,
              a.versionLabel,
              a.openedAt,
              a.acknowledgedAt
            )
          }
      )

  val state: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(version / "acknowledgement")
      .out(jsonBody[AcknowledgementStateView])
      .description(
        "Whether the version asks for acknowledgement and whether the caller opened and acknowledged it"
      )
      .tag("Acknowledgements")
      .serverLogic(p =>
        (id, versionId) =>
          inTransaction(p) { (ctx, acks) =>
            for
              found <- readableVersion(ctx, id, versionId)
              (details, v) = found
              s         <- acks.status(v.id, p.userId)
              recipient <- isRecipient(ctx, acks, details, v)
            yield AcknowledgementStateView(
              v.id,
              v.label,
              Acknowledgements.requested(v) && recipient,
              s.openedAt.isDefined,
              s.openedAt,
              s.acknowledgement.map(_.acknowledgedAt)
            )
          }
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(list, opened, acknowledge, state)

  private type Failure = DocumentError | AccessDenied | WorkflowError | AcknowledgementError

  private def open(p: Principal, ctx: DocumentApi.Context, acks: AcknowledgementService) =
    for
      pending   <- acks.pending(p.userId)
      opened    <- acks.openedValid(p.userId)
      audiences <- acks.audiences(pending.map(_._2).toSet)
      byDoc     <- documents(ctx, pending.map(_._1))
      items     <- ZIO.foreach(pending) { (doc, versionId) =>
        val details = byDoc(doc)
        ZIO
          .foreach(details.versions.find(_.id == versionId))(v =>
            ctx
              .isRecipient(details, v, Recipients.filterOf(audiences.get(v.id)))
              .map(Option.when(_)(item(details, v, opened.get(v.id), None)))
          )
          .map(_.flatten)
      }
    yield items.flatten

  private def done(p: Principal, ctx: DocumentApi.Context, acks: AcknowledgementService) =
    for
      acknowledged <- acks.acknowledgementsOf(p.userId)
      byDoc        <- documents(ctx, acknowledged.map(_.documentId))
    yield acknowledged.flatMap(a =>
      val details = byDoc(a.documentId)
      details.versions
        .find(_.id == a.versionId)
        .map(v =>
          item(details, v, Some(a.openedAt), Some(a.acknowledgedAt)).copy(versionLabel = a.versionLabel)
        )
    )

  private def documents(ctx: DocumentApi.Context, ids: List[DocumentId]) =
    ZIO.foreach(ids.distinct)(id => ctx.service.get(id).map(id -> _)).map(_.toMap)

  private def item(
      details: DocumentDetails,
      v: DocumentVersion,
      openedAt: Option[Instant],
      acknowledgedAt: Option[Instant]
  ) =
    AcknowledgementItemView(
      details.document.id.value,
      v.id,
      details.document.title,
      v.label,
      details.profile.classPath.map(_.value),
      v.validFrom,
      v.publishedAt,
      v.status.toString,
      openedAt.isDefined,
      openedAt,
      acknowledgedAt
    )

  /** Whether the caller is among the recipients of the valid version `v` (DMS-100, ADR 0048). */
  private def isRecipient(
      ctx: DocumentApi.Context,
      acks: AcknowledgementService,
      details: DocumentDetails,
      v: DocumentVersion
  ) =
    if !Acknowledgements.requested(v) then ZIO.succeed(false)
    else
      acks
        .audiences(Set(v.id))
        .flatMap(a => ctx.isRecipient(details, v, Recipients.filterOf(a.get(v.id))))

  /** The version of the document if the caller may read it (404 if it does not exist, 403 if not readable).
    */
  private def readableVersion(ctx: DocumentApi.Context, id: UUID, versionId: UUID) =
    for
      details <- ctx.service.get(DocumentId(id))
      v       <- ZIO
        .fromOption(details.versions.find(_.id == versionId))
        .orElseFail(DocumentError.NotFound(s"document $id has no version $versionId"))
      _ <- ctx.checkRead(details, v)
    yield (details, v)

  private def inTransaction[A](principal: Principal)(
      f: (DocumentApi.Context, AcknowledgementService) => ZIO[Any, Failure, A]
  ): ZIO[Env, (StatusCode, ApiError), A] =
    (for
      db       <- ZIO.service[Db]
      authz    <- ZIO.service[Authorizer]
      service  <- ZIO.service[DocumentService]
      workflow <- ZIO.service[ApprovalWorkflow]
      acks     <- ZIO.service[AcknowledgementService]
      result   <- db.transact(principal.tenantId)(
        authz
          .subject(principal)
          .flatMap(subject => f(DocumentApi.Context(service, workflow, authz, subject), acks))
      )
    yield result).mapError {
      case e: WorkflowError        => WorkflowApi.toResponse(e)
      case e: AccessDenied         => DocumentApi.toResponse(e)
      case e: DbError              => DocumentApi.toResponse(e)
      case e: DocumentError        => DocumentApi.toResponse(e)
      case e: AcknowledgementError => toResponse(e)
    }

  def toResponse(error: AcknowledgementError): (StatusCode, ApiError) = error match
    case AcknowledgementError.NotRequested(m) =>
      (StatusCode.UnprocessableEntity, ApiError("acknowledgement_not_requested", m))
    case AcknowledgementError.NotOpened(m)           => (StatusCode.Conflict, ApiError("not_opened", m))
    case AcknowledgementError.AlreadyAcknowledged(m) =>
      (StatusCode.Conflict, ApiError("already_acknowledged", m))
    case AcknowledgementError.ReminderTooSoon(m, _) =>
      (StatusCode.TooManyRequests, ApiError("reminder_too_soon", m))
    case AcknowledgementError.Invalid(problems) =>
      (StatusCode.UnprocessableEntity, ApiError("invalid_settings", problems.mkString("; ")))
    case AcknowledgementError.Database(e) => DocumentApi.toResponse(e)
