package dms.rendering.core

import dms.audit.*
import dms.db.queue.{ClaimedJob, JobQueue, Outbox}
import dms.db.{Db, DbError, Jsonb}
import dms.documents.Rendition
import dms.kernel.{DocumentId, TenantId, UserId}
import dms.rendering.{ArchiveStandard, RenditionEvents, RenditionRequest}
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.Instant
import java.util.UUID

/** A version ready to print: the stand-alone HTML page of its content and what the header and footer show. */
final case class RenditionPage(html: String, facts: RenditionFacts)

/** What rendering needs from the documents and workflow modules, implemented by the application (module rule:
  * `rendering-core` depends on other modules' `*-api` only). Both methods run inside `Db.transact(tenant)`.
  */
trait RenditionSource:

  /** The page of the version of `request`, or `None` if the version is already approved with its rendition (a
    * repeated job). Fails with [[RenderingError.Invalid]] if the version does not wait for its rendition.
    */
  def load(request: RenditionRequest): IO[RenderingError, Option[RenditionPage]]

  /** Attaches the stored rendition to the version and completes the approval of the request's case. */
  def complete(request: RenditionRequest, rendition: Rendition): IO[RenderingError, Unit]

/** Result of a successful job. */
final case class RenditionResult(
    rendition: Rendition,
    pages: Option[Int],
    renderer: String,
    report: ValidationReport
)

/** Renders the archive rendition of an approved version (DMS-44, ADR 0025): the job of kind
  * [[RenditionRequest.JobKind]], enqueued by the last approving vote.
  *
  * Steps: load the page (own transaction) → print it with the rendering worker → validate it against the
  * archive standard with veraPDF → store it in object storage with its SHA-256 → attach it and complete the
  * approval, with an audit entry of renderer and validation (own transaction). Nothing changes the version
  * before the last step, so a failure leaves it in approval.
  *
  * Failures are retried by the job queue with backoff (ADR 0010). The owner is notified with the outbox event
  * [[RenditionEvents.Failed]] on the first failed attempt and again when no retry follows. Every step is
  * idempotent: rendering is deterministic, storing reuses an equal stored version, and an approved version is
  * left alone.
  */
final class RenditionJobs(
    db: Db,
    source: RenditionSource,
    renderer: PdfRenderer,
    validator: PdfValidator,
    archive: RenditionArchive,
    audit: AuditTrail,
    standard: ArchiveStandard = ArchiveStandard.default
):
  import RenditionJobs.*

  /** The job handler for the job runner (`dms.integration.JobRunner`). */
  def handle(job: ClaimedJob): IO[String, Unit] =
    for
      tenant  <- ZIO.fromOption(job.tenant).orElseFail("a rendition job needs a tenant")
      request <- ZIO.fromEither(decode(job.payload))
      _       <- render(tenant, request).foldZIO(
        error =>
          notifyFailure(tenant, request, error, job)
            .catchAll(e => ZIO.logWarning(s"Rendition failure of ${request.versionId} not recorded: $e")) *>
            ZIO.fail(error.describe),
        _ => ZIO.unit
      )
    yield ()

  /** Produces, stores and attaches the rendition. `None` if the version was approved already. */
  def render(tenant: TenantId, request: RenditionRequest): IO[RenderingError, Option[RenditionResult]] =
    transact(tenant)(source.load(request)).flatMap {
      case None       => ZIO.logInfo(s"Version ${request.versionId} already has its rendition").as(None)
      case Some(page) =>
        for
          html <- ZIO
            .fromEither(RenditionLayout.compose(page.html, page.facts))
            .mapError(RenderingError.Invalid(_))
          pdf <- renderer.render(
            PdfJob(
              html,
              standard,
              RenditionLayout.identifier(tenant, page.facts),
              RenditionLayout.xmp(page.facts)
            )
          )
          report <- validator.validate(pdf.bytes, standard)
          _      <- ZIO.unless(report.compliant)(ZIO.fail(RenderingError.NotArchivable(report)))
          stored <- archive.store(
            RenditionArchive.key(tenant, request.documentId, request.versionId),
            pdf.bytes
          )
          result = RenditionResult(stored, pdf.pages, s"${pdf.renderer}; ${RenditionLayout.Version}", report)
          _ <- transact(tenant)(source.complete(request, stored) *> recordCreated(request, result))
        yield Some(result)
    }

  private def recordCreated(request: RenditionRequest, result: RenditionResult): IO[RenderingError, Unit] =
    record(
      "document.rendition-created",
      request,
      Json.Obj(
        "objectKey"     -> Json.Str(result.rendition.objectKey),
        "objectVersion" -> result.rendition.objectVersion.fold(Json.Null)(Json.Str(_)),
        "sha256"        -> Json.Str(result.rendition.sha256),
        "pages"         -> result.pages.fold(Json.Null)(Json.Num(_)),
        "standard"      -> Json.Str(result.report.standard.label),
        "validator"     -> Json.Str(result.report.validator),
        "renderer"      -> Json.Str(result.renderer)
      )
    )

  /** Records the failure in the audit trail and, on the first attempt and when no retry follows, notifies the
    * recipients through the outbox. Runs in its own transaction.
    */
  private def notifyFailure(
      tenant: TenantId,
      request: RenditionRequest,
      error: RenderingError,
      job: ClaimedJob
  ): IO[RenderingError, Unit] =
    val last    = job.attempts >= job.maxAttempts
    val details = Json.Obj(
      "documentId"  -> Json.Str(request.documentId.value.toString),
      "versionId"   -> Json.Str(request.versionId.toString),
      "caseId"      -> Json.Str(request.caseId.toString),
      "reason"      -> Json.Str(error.describe),
      "attempt"     -> Json.Num(job.attempts),
      "maxAttempts" -> Json.Num(job.maxAttempts),
      "final"       -> Json.Bool(last),
      "recipients"  -> Json.Arr(Chunk.fromIterable(request.recipients.map(u => Json.Str(u.value))))
    )
    transact(tenant)(
      record("document.rendition-failed", request, details) *>
        ZIO.when(job.attempts == 1 || last)(
          Outbox
            .publish("document", request.documentId.value, RenditionEvents.Failed, Jsonb(details.toJson))
            .mapError(RenderingError.Database(_))
        )
    ).unit

  private def record(action: String, request: RenditionRequest, details: Json.Obj): IO[RenderingError, Unit] =
    audit
      .record(
        AuditEvent(
          Actor(RenderingActor, ActorKind.Technical, onBehalfOf = Some(request.approver.value)),
          action,
          AuditObject("document", Some(request.documentId.value)),
          Channel.Automation,
          Jsonb(details.merge(Json.Obj("versionId" -> Json.Str(request.versionId.toString))).toJson)
        )
      )
      .mapError(RenderingError.Database(_))
      .unit

  private def transact[A](tenant: TenantId)(body: IO[RenderingError, A]): IO[RenderingError, A] =
    db.transact(tenant)(body).mapError {
      case e: DbError        => RenderingError.Database(e)
      case e: RenderingError => e
    }

object RenditionJobs:

  /** The technical actor of rendering in the audit trail. */
  val RenderingActor: String = "dms-rendering"

  /** Enqueues the rendering job of a version for the tenant of the current transaction (ADR 0010). Requesting
    * the same version again is a no-op.
    */
  def enqueue(request: RenditionRequest): IO[DbError, Unit] =
    JobQueue
      .enqueue(
        RenditionRequest.JobKind,
        Jsonb(Payload.from(request).toJson),
        Some(RenditionRequest.idempotencyKey(request.versionId))
      )
      .unit

  def decode(payload: Jsonb): Either[String, RenditionRequest] =
    payload.value.fromJson[Payload].map(_.toRequest).left.map(e => s"invalid rendition job payload: $e")

  private final case class Payload(
      documentId: UUID,
      versionId: UUID,
      caseId: UUID,
      approver: String,
      approvedAt: Instant,
      authenticatedAt: Instant,
      recipients: List[String]
  ) derives JsonCodec:
    def toRequest: RenditionRequest =
      RenditionRequest(
        DocumentId(documentId),
        versionId,
        caseId,
        UserId(approver),
        approvedAt,
        authenticatedAt,
        recipients.map(UserId(_))
      )

  private object Payload:
    def from(r: RenditionRequest): Payload =
      Payload(
        r.documentId.value,
        r.versionId,
        r.caseId,
        r.approver.value,
        r.approvedAt,
        r.authenticatedAt,
        r.recipients.map(_.value)
      )
