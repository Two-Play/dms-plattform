package dms.app

import dms.authz.Action
import dms.documents.*
import dms.documents.core.*
import dms.identity.Principal
import dms.kernel.DocumentId
import dms.storage.{ObjectVersion, Sha256}
import sttp.capabilities.zio.ZioStreams
import sttp.model.StatusCode
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import sttp.tapir.{CodecFormat, Schema}
import zio.*
import zio.json.JsonCodec
import zio.stream.{ZSink, ZStream}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/** An attachment of a document version (DMS-114). */
final case class AttachmentView(
    id: UUID,
    versionId: UUID,
    name: String,
    /** Media type, from the upload or guessed from the name. */
    contentType: String,
    size: Long,
    /** SHA-256 (lowercase hex) of the stored bytes. */
    sha256: String,
    /** Clean (checked by the virus scanner) or NotScanned (no scanner configured). */
    scanStatus: String,
    addedBy: String,
    addedAt: Instant,
    /** The attachment of the previous version this one was taken over from. */
    basedOn: Option[UUID],
    /** Where to download it. */
    downloadUrl: String
) derives JsonCodec,
      Schema

/** The attachments of a version and whether the caller may change them. */
final case class AttachmentsView(
    versionId: UUID,
    version: String,
    status: String,
    /** True while the version is a draft and the caller may edit it. */
    editable: Boolean,
    /** Largest accepted file in bytes. */
    maxSizeBytes: Long,
    attachments: List[AttachmentView]
) derives JsonCodec,
      Schema

final case class RenameAttachment(name: String) derives JsonCodec, Schema

/** Documentation of the multipart upload: one part `file` with the file name in its Content-Disposition. */
final case class AttachmentUpload(file: Array[Byte]) derives Schema

/** Attachments of document versions (DMS-114, ADR 0041).
  *
  * Attachments belong to a version: added, renamed and removed while it is a draft (`EditDraft`), frozen from
  * submission on, taken over by the next version. Reading and downloading need `ReadDocument` on the version,
  * like the version itself. Uploads stream into a temporary file with the size limit enforced while
  * receiving, are checked against the blocklist of executable types and scanned for viruses before they are
  * stored in object storage with their SHA-256 and linked to the draft.
  */
object AttachmentApi:
  type Env = DocumentApi.Env & AttachmentService & AttachmentUploads

  private val documents    = "api" / "v1" / "documents"
  private val documentId   = path[UUID]("id").description("Document id")
  private val attachmentId = path[UUID]("attachmentId").description("Attachment id")

  /** Bytes a multipart request may carry beyond the file itself (boundaries, part headers). */
  private[app] val MultipartOverhead: Long = 64L * 1024

  val upload: ZServerEndpoint[Env, ZioStreams] =
    SecuredApi.base.post
      .in(documents / documentId / "attachments")
      .in(header[Option[String]]("Content-Type"))
      .in(
        streamBody(ZioStreams)(summon[Schema[AttachmentUpload]], CodecFormat.MultipartFormData())
          .description(
            "multipart/form-data with one part `file`; its file name becomes the attachment's name"
          )
      )
      .out(statusCode(StatusCode.Created).and(jsonBody[AttachmentView]))
      .description(
        "Adds a file to the draft of the document. 413 above the configured size limit, 422 for blocked " +
          "(executable) types, empty files and missing names, 409 if the version is not a draft or the name or " +
          "the same content is already attached, 422 `attachment_infected` if the virus scanner finds " +
          "something, 503 if the scanner or object storage is unavailable. The same bytes are stored once per " +
          "document."
      )
      .tag("Documents")
      .serverLogic(p => (id, contentType, body) => addAttachment(p, DocumentId(id), contentType, body))

  val list: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(
        documents / documentId / "versions" / path[UUID]("versionId").description(
          "Version id"
        ) / "attachments"
      )
      .out(jsonBody[AttachmentsView])
      .description("The attachments of a version, in the order they were added; requires reading the version")
      .tag("Documents")
      .serverLogic(p =>
        (id, versionId) =>
          for
            settings <- ZIO.serviceWith[AttachmentUploads](_.settings)
            result   <- DocumentApi.inTransaction(p) { ctx =>
              for
                details <- ctx.service.get(DocumentId(id))
                version <- ZIO
                  .fromOption(details.versions.find(_.id == versionId))
                  .orElseFail(DocumentError.NotFound(s"document $id has no version $versionId"))
                _        <- ctx.checkRead(details, version)
                editable <-
                  if version.status == DocumentStatus.Draft then
                    ctx.allowed(Action.EditDraft, DocumentApi.resource(p.tenantId, details, version))
                  else ZIO.succeed(false)
              yield AttachmentsView(
                version.id,
                version.label,
                version.status.toString,
                editable,
                settings.maxSizeBytes,
                version.attachments.map(view(id, _))
              )
            }
          yield result
      )

  val download: ZServerEndpoint[Env, ZioStreams] =
    SecuredApi.base.get
      .in(documents / documentId / "attachments" / attachmentId)
      .out(header[String]("Content-Type"))
      .out(header[String]("Content-Disposition"))
      .out(header[Long]("Content-Length"))
      .out(header[String]("ETag"))
      .out(header[String]("Repr-Digest").description("SHA-256 of the file (RFC 9530)"))
      .out(header[String]("X-Content-Type-Options"))
      .out(streamBinaryBody(ZioStreams)(CodecFormat.OctetStream()))
      .description(
        "Downloads an attachment, always as a file (Content-Disposition attachment), with its SHA-256 as " +
          "Repr-Digest and ETag; requires reading the version it belongs to. The stored object is checked " +
          "against the recorded SHA-256 before it is sent (500 `attachment_integrity` otherwise)."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, attachment) =>
          for
            found <- DocumentApi.inTransaction(p) { ctx =>
              for
                details <- ctx.service.get(DocumentId(id))
                pair    <- ZIO
                  .fromOption(
                    details.versions
                      .flatMap(v => v.attachments.find(_.id == attachment).map(v -> _))
                      .headOption
                  )
                  .orElseFail(DocumentError.NotFound(s"document $id has no attachment $attachment"))
                _ <- ctx.checkRead(details, pair._1)
              yield pair._2
            }
            stream <- ZIO.serviceWithZIO[AttachmentUploads](_.read(found)).mapError(toResponse)
            digest = Sha256.fromHex(found.sha256).fold(_ => "", d => s"sha-256=:${d.base64}:")
          yield (
            found.contentType,
            contentDisposition(found.name),
            found.size,
            s"\"${found.sha256}\"",
            digest,
            "nosniff",
            stream
          )
      )

  val rename: ZServerEndpoint[Env, Any] =
    SecuredApi.base.patch
      .in(documents / documentId / "attachments" / attachmentId)
      .in(jsonBody[RenameAttachment])
      .out(jsonBody[AttachmentView])
      .description(
        "Renames an attachment of the draft. 409 if the version is not a draft or the name is taken (names are " +
          "unique per version, ignoring case); 422 for an invalid name."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, attachment, req) =>
          for
            service <- ZIO.service[AttachmentService]
            name    <- ZIO
              .fromEither(AttachmentRules.normalizeName(req.name))
              .mapError(m => (StatusCode.UnprocessableEntity, ApiError("invalid_name", m)))
            settings <- ZIO.serviceWith[AttachmentUploads](_.settings)
            _        <- ZIO.foreachDiscard(AttachmentRules.blockedName(name, settings))(reason =>
              ZIO.fail((StatusCode.UnprocessableEntity, ApiError("attachment_blocked", reason)))
            )
            renamed <- DocumentApi.inTransaction(p) { ctx =>
              for
                draft <- editableDraft(ctx, p, DocumentId(id), Some(attachment))
                done  <- service.rename(draft, attachment, name, p.userId)
              yield done
            }
          yield view(id, renamed)
      )

  val remove: ZServerEndpoint[Env, Any] =
    SecuredApi.base.delete
      .in(documents / documentId / "attachments" / attachmentId)
      .out(statusCode(StatusCode.NoContent))
      .description(
        "Removes an attachment from the draft. Only the link to the draft is removed; the stored file stays for " +
          "the versions that reference it. 409 if the version is not a draft."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, attachment) =>
          for
            service <- ZIO.service[AttachmentService]
            _       <- DocumentApi.inTransaction(p) { ctx =>
              editableDraft(ctx, p, DocumentId(id), Some(attachment))
                .flatMap(draft => service.remove(draft, attachment, p.userId))
            }
          yield ()
      )

  def endpoints: List[ZServerEndpoint[Env, ZioStreams]] = List(upload, list, download, rename, remove)

  private val UploadPath = "/api/v1/documents/[0-9a-fA-F-]{36}/attachments/?".r

  /** Whether a request is an upload, the only request whose body may be streamed (see `Main`). */
  def isUpload(method: zio.http.Method, path: zio.http.Path): Boolean =
    method == zio.http.Method.POST && UploadPath.matches(path.encode)

  /** The open version of the document if it is a draft the caller may edit. A version in review or approval
    * is checked as if it were a draft, so that its author learns that it is frozen (409) rather than that
    * they may not edit it (403). With `attachment`, the attachment must belong to the draft; one of an
    * earlier version is frozen with it (409).
    */
  private def editableDraft(
      ctx: DocumentApi.Context,
      p: Principal,
      id: DocumentId,
      attachment: Option[UUID] = None
  ) =
    for
      details <- ctx.service.get(id)
      open    <- ZIO
        .fromOption(details.open)
        .orElseFail(DocumentError.Conflict(s"document ${id.value} has no open version"))
      _ <- ctx.check(
        Action.EditDraft,
        DocumentApi.resource(p.tenantId, details, open).copy(status = Some(DocumentStatus.Draft))
      )
      _ <- ZIO.foreachDiscard(attachment.filterNot(a => open.attachments.exists(_.id == a)))(a =>
        details.versions.find(_.attachments.exists(_.id == a)) match
          case Some(v) =>
            ZIO.fail(
              DocumentError.Conflict(
                s"the attachment belongs to version ${v.label}, which is ${v.status} and frozen"
              )
            )
          case None => ZIO.fail(DocumentError.NotFound(s"document ${id.value} has no attachment $a"))
      )
      _ <- ZIO.unless(open.status == DocumentStatus.Draft)(
        ZIO.fail(
          DocumentError.Conflict(
            s"version ${open.label} is ${open.status}; attachments only change while the version is a draft"
          )
        )
      )
    yield open

  private def addAttachment(
      p: Principal,
      id: DocumentId,
      contentType: Option[String],
      body: ZStream[Any, Throwable, Byte]
  ): ZIO[Env, (StatusCode, ApiError), AttachmentView] =
    for
      service <- ZIO.service[AttachmentService]
      uploads <- ZIO.service[AttachmentUploads]
      // Authorize before reading the body.
      _        <- DocumentApi.inTransaction(p)(ctx => editableDraft(ctx, p, id))
      boundary <- multipartBoundary(contentType)
      view     <- ZIO.scoped(
        for
          received <- receive(uploads, boundary, body).mapError(toResponse)
          (rawName, declared, spooled) = received
          name <- ZIO
            .fromEither(AttachmentRules.normalizeName(rawName))
            .mapError(m => (StatusCode.UnprocessableEntity, ApiError("invalid_name", m)))
          _        <- uploads.check(name, spooled).mapError(toResponse)
          existing <- DocumentApi.inTransaction(p)(_ => service.existingFile(id, spooled.sha256))
          file     <- existing match
            case Some(f) => ZIO.succeed((f, None))
            case None    =>
              for
                status <- uploads.scan(spooled).mapError(toResponse)
                now    <- Clock.instant
                fileId = UUID.randomUUID()
                kind   = AttachmentRules.contentType(declared, name)
                stored <- uploads.store(p.tenantId, id, fileId, spooled, kind).mapError(toResponse)
              yield (
                AttachmentFile(
                  fileId,
                  id,
                  spooled.sha256,
                  spooled.size,
                  kind,
                  stored.key,
                  stored.versionId,
                  status,
                  uploads.scannerName,
                  now,
                  p.userId
                ),
                Some(stored)
              )
          (candidate, fresh) = file
          added <- DocumentApi
            .inTransaction(p) { ctx =>
              editableDraft(ctx, p, id).flatMap(draft => service.add(draft, candidate, name, p.userId))
            }
            .tapError(_ => discard(uploads, fresh))
          (attachment, inserted) = added
          _ <- ZIO.unless(inserted)(discard(uploads, fresh))
        yield view(id.value, attachment)
      )
    yield view

  /** The boundary of a `multipart/form-data` request (415 otherwise). */
  private[app] def multipartBoundary(contentType: Option[String]): IO[(StatusCode, ApiError), String] =
    ZIO
      .fromOption(
        contentType
          .flatMap(v => zio.http.Header.ContentType.parse(v).toOption)
          .filter(_.mediaType.fullType == "multipart/form-data")
          .flatMap(_.boundary)
          .map(_.id)
      )
      .orElseFail(
        (
          StatusCode.UnsupportedMediaType,
          ApiError("multipart_required", "send the file as multipart/form-data")
        )
      )

  /** Removes an object stored by this request that is not referenced (failed link, concurrent same upload).
    */
  private[app] def discard(uploads: AttachmentUploads, stored: Option[ObjectVersion]): UIO[Unit] =
    ZIO.foreachDiscard(stored)(uploads.discard)

  /** Reads the multipart body up to the part `file` and spools that part; parts before it are skipped. The
    * whole body is limited to the size limit plus some overhead, so oversized requests fail while they
    * stream.
    */
  /** Largest accepted text part before the file of a form (DMS-127). */
  private[app] val MaxFormFieldBytes: Int = 256 * 1024

  /** Like [[receive]], also collecting the text parts sent before the part `file` (e.g. `values` as JSON, at
    * most [[MaxFormFieldBytes]] together), by name (DMS-127).
    */
  private[app] def receiveForm(
      uploads: AttachmentUploads,
      boundary: String,
      body: ZStream[Any, Throwable, Byte]
  ): ZIO[Scope, AttachmentError, (Map[String, String], String, Option[String], SpooledFile)] =
    final case class Form(
        fields: Map[String, Chunk[Byte]],
        current: Option[String],
        file: Option[MultipartStream.Event.Part]
    )
    val untilFile = ZSink.fold[MultipartStream.Event, Form](Form(Map.empty, None, None))(_.file.isEmpty) {
      case (s, p: MultipartStream.Event.Part) if p.name == "file" => s.copy(current = None, file = Some(p))
      case (s, p: MultipartStream.Event.Part)                     => s.copy(current = Some(p.name))
      case (s, MultipartStream.Event.Data(bytes))                 =>
        s.current.fold(s)(name =>
          val total = s.fields.values.map(_.size).sum + bytes.size
          // beyond the limit the field is cut, which makes it unreadable (checked below)
          if total > MaxFormFieldBytes then
            s.copy(fields = s.fields.updated(name, Chunk.empty), current = None)
          else s.copy(fields = s.fields.updated(name, s.fields.getOrElse(name, Chunk.empty) ++ bytes))
        )
      case (s, MultipartStream.Event.PartEnd) => s.copy(current = None)
    }
    val limit   = uploads.settings.maxSizeBytes + MultipartOverhead
    val limited = body.chunks
      .mapAccumZIO(0L) { (total, chunk) =>
        val next = total + chunk.size
        if next > limit then
          ZIO.fail(
            AttachmentUploads.AttachmentException(AttachmentError.TooLarge(uploads.settings.maxSizeBytes))
          )
        else ZIO.succeed((next, chunk))
      }
      .flattenChunks
    MultipartStream
      .parse(limited, boundary)
      .peel(untilFile)
      .mapError(readError)
      .flatMap {
        case (Form(fields, _, Some(part)), rest) =>
          val bytes = rest
            .takeWhile(_ != MultipartStream.Event.PartEnd)
            .collect { case MultipartStream.Event.Data(b) => b }
            .flattenChunks
          uploads
            .spool(bytes)
            .map(spooled =>
              (
                fields.map((k, v) => k -> String(v.toArray, StandardCharsets.UTF_8)),
                part.filename.getOrElse(""),
                part.contentType,
                spooled
              )
            )
        case _ => ZIO.fail(AttachmentError.Invalid("the request has no part named `file`"))
      }

  private[app] def receive(
      uploads: AttachmentUploads,
      boundary: String,
      body: ZStream[Any, Throwable, Byte]
  ): ZIO[Scope, AttachmentError, (String, Option[String], SpooledFile)] =
    val limit   = uploads.settings.maxSizeBytes + MultipartOverhead
    val limited = body.chunks
      .mapAccumZIO(0L) { (total, chunk) =>
        val next = total + chunk.size
        if next > limit then
          ZIO.fail(
            AttachmentUploads.AttachmentException(AttachmentError.TooLarge(uploads.settings.maxSizeBytes))
          )
        else ZIO.succeed((next, chunk))
      }
      .flattenChunks
    val untilFile = ZSink.fold[MultipartStream.Event, Option[MultipartStream.Event.Part]](None)(_.isEmpty) {
      case (_, p: MultipartStream.Event.Part) if p.name == "file" => Some(p)
      case (s, _)                                                 => s
    }
    MultipartStream
      .parse(limited, boundary)
      .peel(untilFile)
      .mapError(readError)
      .flatMap {
        case (Some(part), rest) =>
          val bytes = rest
            .takeWhile(_ != MultipartStream.Event.PartEnd)
            .collect { case MultipartStream.Event.Data(b) => b }
            .flattenChunks
          uploads.spool(bytes).map(spooled => (part.filename.getOrElse(""), part.contentType, spooled))
        case (None, _) => ZIO.fail(AttachmentError.Invalid("the request has no part named `file`"))
      }

  private def readError(e: Throwable): AttachmentError = e match
    case e: AttachmentUploads.AttachmentException => e.error
    case e: MultipartStream.Malformed             => AttachmentError.Invalid(e.getMessage)
    case e => AttachmentError.Invalid(s"the upload was interrupted: ${e.getMessage}")

  private def view(documentId: UUID, a: VersionAttachment): AttachmentView =
    AttachmentView(
      a.id,
      a.versionId,
      a.name,
      a.contentType,
      a.size,
      a.sha256,
      a.scanStatus.toString,
      a.addedBy.value,
      a.addedAt,
      a.basedOn,
      s"/api/v1/documents/$documentId/attachments/${a.id}"
    )

  /** `attachment` with an ASCII fallback name and the UTF-8 name (RFC 6266, RFC 8187). */
  private[app] def contentDisposition(name: String): String =
    val ascii =
      name.iterator.map(c => if c >= 0x20 && c < 0x7f && c != '"' && c != '\\' then c else '_').mkString
    val encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")
    s"""attachment; filename="$ascii"; filename*=UTF-8''$encoded"""

  def toResponse(error: AttachmentError): (StatusCode, ApiError) = error match
    case AttachmentError.TooLarge(_) =>
      (StatusCode.PayloadTooLarge, ApiError("attachment_too_large", error.describe))
    case AttachmentError.Blocked(_) =>
      (StatusCode.UnprocessableEntity, ApiError("attachment_blocked", error.describe))
    case AttachmentError.Invalid(_) =>
      (StatusCode.UnprocessableEntity, ApiError("invalid_upload", error.describe))
    case AttachmentError.Infected(_) =>
      (StatusCode.UnprocessableEntity, ApiError("attachment_infected", error.describe))
    case AttachmentError.ScannerUnavailable(_) =>
      (StatusCode.ServiceUnavailable, ApiError("scanner_unavailable", error.describe))
    case AttachmentError.StorageUnavailable(_) =>
      (StatusCode.ServiceUnavailable, ApiError("storage_unavailable", error.describe))
    case AttachmentError.Integrity(_) =>
      (StatusCode.InternalServerError, ApiError("attachment_integrity", error.describe))
    case AttachmentError.Storage(_) =>
      (StatusCode.InternalServerError, ApiError("storage_error", error.describe))
