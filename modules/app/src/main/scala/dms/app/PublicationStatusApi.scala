package dms.app

import com.augustnagro.magnum.sql
import dms.app.RenditionStatusApi.{Failed, JobRow, Pending, Retrying}
import dms.audit.*
import dms.authz.{Action, Resource}
import dms.db.{Db, DbError, Jsonb}
import dms.documents.{DocumentStatus, DocumentVersion}
import dms.documents.core.{DocumentError, PublicationJobs}
import dms.kernel.DocumentId
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.*
import sttp.tapir.ztapir.*
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.{Instant, OffsetDateTime}
import java.util.UUID

/** The state of the publication job of an approved version (DMS-142).
  *
  *   - `Pending`: the job waits for its moment or runs.
  *   - `Retrying`: an attempt failed; the job runs again at `nextAttemptAt` (automatic retry with backoff).
  *   - `Failed`: every attempt failed; the version stays approved and does not become valid until someone
  *     retries.
  *   - `None`: the version does not wait for publication, or its job is not enqueued yet.
  */
final case class PublicationStatusView(
    documentId: UUID,
    versionId: UUID,
    version: String,
    state: String,
    attempts: Int,
    maxAttempts: Int,
    nextAttemptAt: Option[Instant],
    failedAt: Option[Instant],
    lastError: Option[RenditionErrorView],
    canRetry: Boolean
) derives JsonCodec,
      Schema

/** An approved version whose publication failed or is being retried, for the records view. The title is left
  * out where the caller may not retry (e.g. not cleared for the document's confidentiality).
  */
final case class PublicationFailureView(
    documentId: UUID,
    versionId: UUID,
    version: String,
    title: Option[String],
    state: String,
    attempts: Int,
    maxAttempts: Int,
    nextAttemptAt: Option[Instant],
    failedAt: Option[Instant],
    lastError: Option[RenditionErrorView],
    canRetry: Boolean
) derives JsonCodec,
      Schema

/** Status and retry of scheduled publications (DMS-142, ADR 0058), the counterpart of [[RenditionStatusApi]].
  *
  * A dead publication job does not come back by itself: its idempotency key exists, so the periodic
  * enqueueing (ADR 0026) adds nothing, and no other path publishes a version. Without a retry the version
  * would stay approved for good. `lastError.code` is one of `conflict`, `legal_hold`, `invalid`, `not_found`,
  * `database`, `timeout`, `internal`.
  */
object PublicationStatusApi:
  type Env = DocumentApi.Env & AuditTrail

  private val versionPath =
    "api" / "v1" / "documents" / path[UUID]("id").description("Document id") / "versions" /
      path[UUID]("versionId").description("Version id") / "publication"

  val status: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(versionPath / "status")
      .out(jsonBody[PublicationStatusView])
      .description(
        "The state of the publication job of an approved version: Pending, Retrying (with the next attempt), " +
          "Failed (every attempt failed) or None, with the last error and whether the caller may retry. " +
          "Requires ReadDocument on the version."
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
              retry <- ctx.allowed(
                Action.RetryPublication,
                DocumentApi.resource(p.tenantId, details, version)
              )
            yield statusView(id, version, job.map(_._1), retry)
          }
      )

  val retry: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(versionPath / "retry")
      .out(jsonBody[PublicationStatusView])
      .description(
        "Requeues the publication job of an approved version whose publication failed (Failed: starts over " +
          "with all attempts) or waits for its next attempt (Retrying: runs now). 409 publication_not_failed in " +
          "any other state, also if a withdrawal got there first. Requires RetryPublication: the person named " +
          "owner (without owner: the author) with any role covering the document, records managers and tenant " +
          "administrators. Audited."
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
                    .check(Action.RetryPublication, resource.copy(status = Some(DocumentStatus.Approved)))
                  // The lock first, the job and the status after it: a withdrawal of the failed version does the
                  // same, so that exactly one of the two wins (ADR 0061).
                  locked <- ctx.service.lockForJobRetry(versionId)
                  before <- jobOf(versionId)
                  state = before.map((job, _) => RenditionStatusApi.stateOf(job))
                  outcome <- before match
                    case Some((job, key))
                        if locked.status == DocumentStatus.Approved &&
                          state.exists(s => s == Failed || s == Retrying) =>
                      for
                        _ <- Db
                          .sql(
                            sql"""select dms.job_retry(${PublicationJobs.PublishKind}, $key)"""
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
                                    "previousState"    -> Json.Str(state.getOrElse(NoJob)),
                                    "previousAttempts" -> Json.Num(job.attempts),
                                    "previousError"    -> job.lastError.fold(Json.Null)(Json.Str(_))
                                  )
                                  .toJson
                              )
                            )
                          )
                          .mapError(DocumentError.Database(_))
                        after <- jobOf(versionId)
                      yield Right(statusView(id, locked, after.map(_._1), canRetry = true))
                    case _ =>
                      ZIO.succeed(
                        Left(
                          s"the publication of version ${version.label} is ${state.getOrElse(NoJob)} and the " +
                            s"version is ${locked.status}: only a failed publication of an approved version " +
                            "can be retried"
                        )
                      )
                yield outcome
              }
              .flatMap {
                case Right(view)   => ZIO.succeed(view)
                case Left(message) =>
                  ZIO.fail((StatusCode.Conflict, ApiError("publication_not_failed", message)))
              }
          )
      )

  val failures: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "publications" / "failures")
      .out(jsonBody[List[PublicationFailureView]])
      .description(
        "Approved versions whose publication failed (Failed) or is being retried (Retrying), most recent first, " +
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
              tenantWide <- ctx.allowed(Action.RetryPublication, Resource(p.tenantId))
              jobs       <- failedJobs
              rows       <- ZIO.foreach(jobs) { (documentId, versionId, job) =>
                for
                  details <- ctx.service.get(DocumentId(documentId))
                  view    <- details.versions.find(_.id == versionId).fold(ZIO.none) { v =>
                    val resource = DocumentApi.resource(p.tenantId, details, v)
                    (ctx.allowed(Action.RetryPublication, resource) <*>
                      RenditionStatusApi.covers(ctx, Action.RetryPublication, resource)).map {
                      (retry, covered) =>
                        Option.when(tenantWide || covered)(
                          PublicationFailureView(
                            documentId,
                            versionId,
                            v.label,
                            Option.when(retry)(details.document.title),
                            RenditionStatusApi.stateOf(job),
                            job.attempts,
                            job.maxAttempts,
                            RenditionStatusApi.nextAttempt(job),
                            job.finishedAt,
                            job.lastError.map(RenditionStatusApi.errorView),
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
  val RetryRequested: String = "document.publication-retry-requested"

  private val NoJob = RenditionStatusApi.NoRendition

  /** The newest publication job of a version with its key; older ones belong to an earlier due moment (a time
    * zone change, ADR 0026).
    */
  private def jobOf(versionId: UUID): IO[DocumentError, Option[(JobRow, String)]] =
    Db.sql(
      sql"""select status, attempts, max_attempts, run_at, last_error, created_at, finished_at, idempotency_key
            from dms.job
            where kind = ${PublicationJobs.PublishKind} and payload ->> 'versionId' = ${versionId.toString}
            order by id desc limit 1"""
        .query[
          (String, Int, Int, OffsetDateTime, Option[String], OffsetDateTime, Option[OffsetDateTime], String)
        ]
        .run()
        .headOption
        .map { case (status, attempts, max, runAt, error, created, finished, key) =>
          (RenditionStatusApi.row((status, attempts, max, runAt, error, created, finished)), key)
        }
    ).mapError(DocumentError.Database(_))

  /** Failed and retrying publication jobs of versions still approved, most recent first. */
  private def failedJobs: IO[DocumentError, List[(UUID, UUID, JobRow)]] =
    Db.sql(
      sql"""select document_id, version_id, status, attempts, max_attempts, run_at, last_error, created_at,
                   finished_at
            from (select distinct on (v.id) v.document_id, v.id as version_id, j.id as job_id, j.status,
                         j.attempts, j.max_attempts, j.run_at, j.last_error, j.created_at, j.finished_at
                  from dms.job j
                  join dms.document_version v on v.id = (j.payload ->> 'versionId')::uuid
                  where j.kind = ${PublicationJobs.PublishKind} and v.status = 'Approved'
                  order by v.id, j.id desc) newest
            where status = 'dead' or (status = 'queued' and attempts > 0)
            order by coalesce(finished_at, run_at) desc, job_id desc
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
          (doc, version, RenditionStatusApi.row((status, attempts, max, runAt, error, created, finished)))
        }
    ).mapError((e: DbError) => DocumentError.Database(e))

  private def statusView(
      documentId: UUID,
      version: DocumentVersion,
      job: Option[JobRow],
      canRetry: Boolean
  ): PublicationStatusView =
    val waiting = job.filter(_ => version.status == DocumentStatus.Approved)
    val state   = waiting.fold(NoJob)(j =>
      RenditionStatusApi.stateOf(j) match
        case RenditionStatusApi.Done => NoJob
        case other                   => other
    )
    val open = state == Failed || state == Retrying || state == Pending
    PublicationStatusView(
      documentId,
      version.id,
      version.label,
      state,
      waiting.fold(0)(_.attempts),
      waiting.fold(0)(_.maxAttempts),
      waiting.filter(_ => open).flatMap(RenditionStatusApi.nextAttempt),
      waiting.flatMap(_.finishedAt).filter(_ => state == Failed),
      waiting.flatMap(_.lastError).filter(_ => open).map(RenditionStatusApi.errorView),
      canRetry && (state == Failed || state == Retrying)
    )
