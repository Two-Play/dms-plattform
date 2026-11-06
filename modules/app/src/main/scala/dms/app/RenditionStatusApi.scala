package dms.app

import com.augustnagro.magnum.sql
import dms.audit.*
import dms.authz.{Action, Confidentiality, Resource}
import dms.db.{Db, DbError, Jsonb}
import dms.documents.DocumentStatus
import dms.documents.core.DocumentError
import dms.kernel.DocumentId
import dms.rendering.RenditionRequest
import dms.rendering.core.RenditionJobs
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.*
import sttp.tapir.ztapir.*
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.{Instant, OffsetDateTime}
import java.util.UUID

/** Why the last attempt failed. `code` (`unavailable`, `rejected`, `not_archivable`, `invalid`,
  * `too_complex`, `integrity`, `database`, `timeout`, `internal`; of a publication `conflict`, `legal_hold`,
  * `invalid`, `not_found`, `database`, `timeout`, `internal`) says what kind of failure it was, so the client
  * can explain it; `message` is the technical detail.
  */
final case class RenditionErrorView(code: Option[String], message: String) derives JsonCodec, Schema

/** The state of a version's archive rendition (DMS-93).
  *
  *   - `Done`: the rendition is attached.
  *   - `Pending`: the rendering job waits for its first attempt or runs.
  *   - `Retrying`: an attempt failed; the job runs again at `nextAttemptAt` (automatic retry with backoff).
  *   - `Failed`: every attempt failed; the version stays in approval until someone retries.
  *   - `None`: no rendition was requested for the version.
  */
final case class RenditionStatusView(
    documentId: UUID,
    versionId: UUID,
    version: String,
    state: String,
    attempts: Int,
    maxAttempts: Int,
    requestedAt: Option[Instant],
    nextAttemptAt: Option[Instant],
    failedAt: Option[Instant],
    lastError: Option[RenditionErrorView],
    canRetry: Boolean,
    renderer: Option[String],
    workerImage: Option[String],
    workerBuild: Option[String]
) derives JsonCodec,
      Schema

/** A version whose rendition failed or is being retried, for the records view (DMS-93). The title is left out
  * where the caller may not retry (e.g. not cleared for the document's confidentiality).
  */
final case class RenditionFailureView(
    documentId: UUID,
    versionId: UUID,
    version: String,
    title: Option[String],
    state: String,
    attempts: Int,
    maxAttempts: Int,
    requestedAt: Instant,
    nextAttemptAt: Option[Instant],
    failedAt: Option[Instant],
    lastError: Option[RenditionErrorView],
    canRetry: Boolean
) derives JsonCodec,
      Schema

/** Status and retry of archive renditions (DMS-93, ADR 0055).
  *
  * The rendering job (ADR 0025) retries failed attempts automatically with exponential backoff (10 attempts,
  * 10 s doubling up to 1 h, ADR 0010); once every attempt failed, the job is dead and the version stays in
  * approval. These endpoints show that state on the document page and in a records view, and requeue the job:
  * a dead job starts over with all its attempts, a job waiting for its next attempt runs now. Retrying
  * requires `RetryRendition` and is audited (`document.rendition-retry-requested`).
  */
object RenditionStatusApi:
  type Env = DocumentApi.Env & AuditTrail

  private val versionPath =
    "api" / "v1" / "documents" / path[UUID]("id").description("Document id") / "versions" /
      path[UUID]("versionId").description("Version id") / "rendition"

  val status: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(versionPath / "status")
      .out(jsonBody[RenditionStatusView])
      .description(
        "The state of the version's archive rendition: Done, Pending, Retrying (with the next attempt), Failed " +
          "(every attempt failed) or None, with the last error and whether the caller may retry. Requires " +
          "ReadDocument on the version."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, versionId) =>
          DocumentApi.inTransaction(p) { ctx =>
            for
              details <- ctx.service.get(DocumentId(id))
              version <- ZIO
                .fromOption(details.versions.find(_.id == versionId))
                .orElseFail(DocumentError.NotFound(s"document $id has no version $versionId"))
              _     <- ctx.checkRead(details, version)
              job   <- jobOf(versionId)
              prov  <- provenanceOf(versionId)
              retry <- ctx.allowed(Action.RetryRendition, DocumentApi.resource(p.tenantId, details, version))
            yield statusView(id, versionId, version.label, version.rendition.isDefined, job, prov, retry)
          }
      )

  val retry: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(versionPath / "retry")
      .out(jsonBody[RenditionStatusView])
      .description(
        "Requeues the rendering job of a version in approval whose rendition failed (Failed: starts over with " +
          "all attempts) or waits for its next attempt (Retrying: runs now). 409 rendition_not_failed in any " +
          "other state. Requires RetryRendition: the person named owner (without owner: the author) with any " +
          "role covering the document, records managers and tenant administrators. Audited."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, versionId) =>
          ZIO.serviceWithZIO[AuditTrail](audit =>
            DocumentApi
              .inTransaction(p) { ctx =>
                for
                  details <- ctx.service.get(DocumentId(id))
                  version <- ZIO
                    .fromOption(details.versions.find(_.id == versionId))
                    .orElseFail(DocumentError.NotFound(s"document $id has no version $versionId"))
                  resource = DocumentApi.resource(p.tenantId, details, version)
                  _ <- ctx
                    .check(Action.RetryRendition, resource.copy(status = Some(DocumentStatus.InApproval)))
                  before <- jobOf(versionId)
                  state = before.map(stateOf)
                  outcome <-
                    if version.status != DocumentStatus.InApproval || version.rendition.isDefined ||
                      !state.exists(s => s == Failed || s == Retrying)
                    then
                      ZIO.succeed(
                        Left(
                          s"the rendition of version ${version.label} is ${state.getOrElse(NoRendition)} and " +
                            s"the version is ${version.status}: only a failed rendition of a version in approval " +
                            "can be retried"
                        )
                      )
                    else
                      for
                        _ <- Db
                          .sql(
                            sql"""select dms.job_retry(${RenditionRequest.JobKind},
                                    ${RenditionRequest.idempotencyKey(versionId)})"""
                              .query[Option[String]]
                              .run()
                              .head
                          )
                          .mapError(DocumentError.Database(_))
                        _ <- audit
                          .record(
                            AuditEvent(
                              Actor(p.userId.value, ActorKind.Person),
                              RetryRequested,
                              AuditObject("document", Some(id), Some(version.label)),
                              Channel.Api,
                              Jsonb(
                                Json
                                  .Obj(
                                    "versionId"        -> Json.Str(versionId.toString),
                                    "previousState"    -> Json.Str(state.getOrElse(NoRendition)),
                                    "previousAttempts" -> Json.Num(before.fold(0)(_.attempts)),
                                    "previousError"    -> before
                                      .flatMap(_.lastError)
                                      .fold(Json.Null)(Json.Str(_))
                                  )
                                  .toJson
                              )
                            )
                          )
                          .mapError(DocumentError.Database(_))
                        after <- jobOf(versionId)
                        prov  <- provenanceOf(versionId)
                      yield Right(
                        statusView(id, versionId, version.label, false, after, prov, canRetry = true)
                      )
                yield outcome
              }
              .flatMap {
                case Right(view)   => ZIO.succeed(view)
                case Left(message) =>
                  ZIO.fail((StatusCode.Conflict, ApiError("rendition_not_failed", message)))
              }
          )
      )

  val failures: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "renditions" / "failures")
      .out(jsonBody[List[RenditionFailureView]])
      .description(
        "Versions in approval whose rendition failed (Failed) or is being retried (Retrying), most recent first, " +
          "at most 200 of the tenant before the filter. Records managers and tenant administrators see those " +
          "their role covers, everyone else those they are named owner of (without owner: author of); for others " +
          "the list is empty. The title is left out where the caller may not retry (e.g. not cleared for the " +
          "document's confidentiality)."
      )
      .tag("Documents")
      .serverLogic(p =>
        _ =>
          DocumentApi.inTransaction(p) { ctx =>
            for
              tenantWide <- ctx.allowed(Action.RetryRendition, Resource(p.tenantId))
              jobs       <- failedJobs
              rows       <- ZIO.foreach(jobs) { job =>
                for
                  details <- ctx.service.get(DocumentId(job.documentId))
                  version = details.versions.find(_.id == job.versionId)
                  view <- version.fold(ZIO.none) { v =>
                    val resource = DocumentApi.resource(p.tenantId, details, v)
                    (ctx.allowed(Action.RetryRendition, resource) <*> covers(
                      ctx,
                      Action.RetryRendition,
                      resource
                    ))
                      .map { (retry, covered) =>
                        Option.when(tenantWide || covered)(
                          RenditionFailureView(
                            job.documentId,
                            job.versionId,
                            v.label,
                            Option.when(retry)(details.document.title),
                            stateOf(job.row),
                            job.row.attempts,
                            job.row.maxAttempts,
                            job.row.createdAt,
                            nextAttempt(job.row),
                            job.row.finishedAt,
                            job.row.lastError.map(errorView),
                            retry
                          )
                        )
                      }
                  }
                yield view
              }
            yield rows.flatten
          }
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(status, retry, failures)

  /** Audit action of a retry. */
  val RetryRequested: String = "document.rendition-retry-requested"

  private[app] val Done        = "Done"
  private[app] val Pending     = "Pending"
  private[app] val Retrying    = "Retrying"
  private[app] val Failed      = "Failed"
  private[app] val NoRendition = "None"

  /** A job of a version as stored in `dms.job`. */
  private[app] final case class JobRow(
      status: String,
      attempts: Int,
      maxAttempts: Int,
      runAt: Instant,
      lastError: Option[String],
      createdAt: Instant,
      finishedAt: Option[Instant]
  )

  private final case class FailedJob(documentId: UUID, versionId: UUID, row: JobRow)

  private[app] type Row =
    (String, Int, Int, OffsetDateTime, Option[String], OffsetDateTime, Option[OffsetDateTime])

  private[app] def row(r: Row): JobRow =
    val (status, attempts, max, runAt, error, created, finished) = r
    JobRow(status, attempts, max, runAt.toInstant, error, created.toInstant, finished.map(_.toInstant))

  private def jobOf(versionId: UUID): IO[DocumentError, Option[JobRow]] =
    Db.sql(
      sql"""select status, attempts, max_attempts, run_at, last_error, created_at, finished_at from dms.job
            where kind = ${RenditionRequest.JobKind}
              and idempotency_key = ${RenditionRequest.idempotencyKey(versionId)}"""
        .query[Row]
        .run()
        .headOption
        .map(row)
    ).mapError(DocumentError.Database(_))

  private final case class Provenance(renderer: String, image: Option[String], build: Option[String])

  private def provenanceOf(versionId: UUID): IO[DocumentError, Option[Provenance]] =
    Db.sql(
      sql"""select renderer, worker_image, worker_build from dms.rendition_provenance where version_id = $versionId"""
        .query[(String, Option[String], Option[String])]
        .run()
        .headOption
        .map(Provenance.apply)
    ).mapError(DocumentError.Database(_))

  /** Failed and retrying rendering jobs of versions still in approval, most recent first. */
  private def failedJobs: IO[DocumentError, List[FailedJob]] =
    Db.sql(
      sql"""select (j.payload ->> 'documentId')::uuid, v.id, j.status, j.attempts, j.max_attempts, j.run_at,
                   j.last_error, j.created_at, j.finished_at
            from dms.job j
            join dms.document_version v on v.id = (j.payload ->> 'versionId')::uuid
            where j.kind = ${RenditionRequest.JobKind}
              and (j.status = 'dead' or (j.status = 'queued' and j.attempts > 0))
              and v.status = 'InApproval' and v.rendition_key is null
            order by coalesce(j.finished_at, j.run_at) desc, j.id desc
            limit 200"""
        .query[
          (
              UUID,
              UUID,
              String,
              Int,
              Int,
              OffsetDateTime,
              Option[String],
              OffsetDateTime,
              Option[OffsetDateTime]
          )
        ]
        .run()
        .toList
        .map { case (doc, version, status, attempts, max, runAt, error, created, finished) =>
          FailedJob(doc, version, row((status, attempts, max, runAt, error, created, finished)))
        }
    ).mapError((e: DbError) => DocumentError.Database(e))

  /** Whether a version belongs in the caller's failure list: they could retry it but for its confidentiality
    * (ADR 0064). Someone not cleared gets the row without title, as tenant-wide records managers always did.
    */
  private[app] def covers(ctx: DocumentApi.Context, retry: Action, resource: Resource): UIO[Boolean] =
    ctx.allowed(retry, resource.copy(confidentiality = Confidentiality.Internal))

  private[app] def stateOf(job: JobRow): String = job.status match
    case "dead"                       => Failed
    case "queued" if job.attempts > 0 => Retrying
    case "done"                       => Done
    case _                            => Pending

  private[app] def nextAttempt(job: JobRow): Option[Instant] =
    Option.when(job.status == "queued")(job.runAt)

  private[app] def errorView(text: String): RenditionErrorView =
    val (code, message) = RenditionJobs.parseJobError(text)
    RenditionErrorView(code, message)

  private def statusView(
      documentId: UUID,
      versionId: UUID,
      label: String,
      attached: Boolean,
      job: Option[JobRow],
      provenance: Option[Provenance],
      canRetry: Boolean
  ): RenditionStatusView =
    val state =
      if attached then Done else job.fold(NoRendition)(stateOf)
    RenditionStatusView(
      documentId,
      versionId,
      label,
      state,
      job.fold(0)(_.attempts),
      job.fold(0)(_.maxAttempts),
      job.map(_.createdAt),
      job.flatMap(j => if state == Done then None else nextAttempt(j)),
      job.flatMap(_.finishedAt).filter(_ => state == Failed),
      job.flatMap(_.lastError).filter(_ => state != Done).map(errorView),
      canRetry && (state == Failed || state == Retrying),
      provenance.map(_.renderer),
      provenance.flatMap(_.image),
      provenance.flatMap(_.build)
    )
