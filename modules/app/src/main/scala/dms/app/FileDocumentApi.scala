package dms.app

import dms.audit.*
import dms.authz.Action
import dms.db.{Db, Jsonb}
import dms.documents.*
import dms.documents.core.*
import dms.documents.core.MetadataJson.given
import dms.documents.metadata.MetadataValue
import dms.identity.{Principal, TenantOrganizations, UserDirectory}
import dms.kernel.{DocumentId, TemplateId, UserId}
import dms.rendering.ArchiveStandard
import dms.rendering.core.{ConversionTarget, DocumentConverter, RenderingError, RenditionArchive}
import dms.storage.Sha256
import dms.templates.{WordField, WordFieldSource, WordFields}
import dms.workflow.Approval
import sttp.capabilities.zio.ZioStreams
import sttp.model.{Header, MediaType, StatusCode}
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import sttp.tapir.{CodecFormat, Schema}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream

import java.nio.file.Files

import MetadataSchemas.given
import java.security.MessageDigest
import java.util.{HexFormat, UUID}

/** Documentation of the multipart upload of a file: one part `file`. */
final case class FileUpload(file: Array[Byte]) derives Schema

/** A field of a Word document with its value (DMS-115). */
final case class WordFieldView(
    /** Field key without the `dms:` prefix. */
    key: String,
    label: String,
    /** Text, Date or Number. */
    `type`: String,
    required: Boolean,
    /** Automatic (filled by the platform) or Manual (entered by the author). */
    source: String,
    /** Manual fields: the value entered (dates as yyyy-MM-dd). Automatic fields: the value the platform fills
      * now, if it has one.
      */
    value: Option[String]
) derives JsonCodec,
      Schema

/** The fields of a version's Word file and whether the caller may enter values. */
final case class WordFieldsView(
    versionId: UUID,
    version: String,
    status: String,
    /** True while the version is a draft and the caller may edit it. */
    editable: Boolean,
    fields: List[WordFieldView]
) derives JsonCodec,
      Schema

final case class WordValues(
    /** Values of manual fields by key; empty or missing values clear the field. */
    values: Map[String, String]
) derives JsonCodec,
      Schema

/** Request to create a document from a Word template: like `CreateDocument`, without kind. */
final case class CreateWordDocument(
    templateId: UUID,
    templateVersion: Int,
    title: String,
    values: Map[String, MetadataValue]
) derives JsonCodec,
      Schema

/** The originals of file document versions (DMS-115, ADR 0042) read back against their SHA-256 and converted
  * by the conversion worker; for approved versions their archived rendition.
  */
final class FileVersions(archive: RenditionArchive, converter: DocumentConverter):

  /** The bytes of the original, checked against the recorded SHA-256. */
  def original(file: VersionFile): IO[RenderingError, Chunk[Byte]] =
    archive.read(Rendition(file.objectKey, Some(file.objectVersion), file.sha256))

  /** A PDF of `bytes`: unchanged if it is a PDF, otherwise converted on request (never stored). */
  def toPdf(bytes: Chunk[Byte], name: String): IO[RenderingError, Chunk[Byte]] =
    if DocumentConverter.isPdf(bytes) then ZIO.succeed(bytes)
    else converter.convert(bytes, name, ConversionTarget.pdf(ArchiveStandard.default)).map(_.bytes)

  /** The archived rendition of an approved version, if it has one. */
  def archived(v: DocumentVersion): Option[IO[RenderingError, Chunk[Byte]]] =
    v.rendition.filter(_ => v.releasedAt.isDefined).map(archive.read)

  /** Converts an HTML page into a Word document (DOCX). */
  def docx(html: String, name: String): IO[RenderingError, Chunk[Byte]] =
    converter
      .convert(
        Chunk.fromArray(html.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        name,
        ConversionTarget.Docx
      )
      .map(_.bytes)

object FileVersions:
  val layer: URLayer[RenditionArchive & DocumentConverter, FileVersions] =
    ZLayer.fromFunction(FileVersions(_, _))

  /** The original of a version with its Word fields filled (DMS-115): automatic fields from the platform
    * (with the approval, if any), manual fields as entered. Other files unchanged.
    */
  def filled(
      details: DocumentDetails,
      v: DocumentVersion,
      names: Map[UserId, String],
      approval: Option[WordFilling.ApprovalFacts]
  ): ZIO[FileVersions, RenderingError, Chunk[Byte]] =
    for
      file <- ZIO.fromOption(v.file).orElseFail(RenderingError.Invalid(s"version ${v.label} has no file yet"))
      bytes <- ZIO.serviceWithZIO[FileVersions](_.original(file))
      out   <-
        if file.isWord && file.fields.nonEmpty then
          ZIO
            .fromEither(WordDocx.fill(bytes.toArray, WordFilling.values(details, v, file, names, approval)))
            .mapBoth(m => RenderingError.Invalid(s"version ${v.label}: $m"), Chunk.fromArray)
        else ZIO.succeed(bytes)
    yield out

  /** The approval to print for a version: the workflow approval (deputy included), else the release. */
  def approvalOf(v: DocumentVersion, approval: Option[Approval]): Option[WordFilling.ApprovalFacts] =
    approval
      .filter(_ => v.releasedAt.isDefined)
      .map(a => WordFilling.ApprovalFacts(a.approvedAt, a.approvedBy, a.actingFor))
      .orElse(v.releasedAt.zip(v.releasedBy).map((at, by) => WordFilling.ApprovalFacts(at, by, None)))

/** File documents (DMS-115, ADR 0042): the uploaded original of a version, its Word fields, the filled Word
  * document and a PDF preview.
  *
  *   - `POST /api/v1/documents/word`: creates a file document from a released Word template; its draft starts
  *     with a copy of the template's Word file and the template's field definitions.
  *   - `PUT /api/v1/documents/{id}/file`: uploads the original into the draft (`EditDraft`), replacing the
  *     previous file as the next working state. Streamed like attachment uploads (size limit while receiving,
  *     blocklist, virus scan, stored once per document and SHA-256); only types the conversion worker
  *     converts into PDF/A are accepted. The fields of a DOCX are read from its content controls and
  *     `{{key}}` placeholders; definitions of keys known before (from the template or the previous file) are
  *     kept.
  *   - `GET …/versions/{versionId}/word-fields`, `PUT /api/v1/documents/{id}/word-fields`: the fields with
  *     their values; manual values are entered into the draft.
  *   - `GET …/versions/{versionId}/file`: the original unchanged; `GET …/versions/{versionId}/filled.docx`
  *     the Word document with its fields filled (audited like a draft PDF).
  *   - `GET …/versions/{versionId}/preview.pdf`: the archived rendition of an approved version, otherwise the
  *     filled original converted on request with the draft marking on every page. Not audited, like reading.
  */
object FileDocumentApi:
  type Env = DocumentApi.Env & AttachmentUploads & FileVersions & WordTemplateService & AuditTrail &
    TenantOrganizations & UserDirectory

  /** Audit action of a downloaded filled Word document. */
  val FilledDocxAction: String = "document.filled-docx-downloaded"

  private val documents  = "api" / "v1" / "documents"
  private val documentId = path[UUID]("id").description("Document id")
  private val version    =
    documents / documentId / "versions" / path[UUID]("versionId").description("Version id")

  val createFromWord: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(documents / "word")
      .in(jsonBody[CreateWordDocument])
      .out(statusCode(StatusCode.Created).and(jsonBody[DocumentView]))
      .description(
        "Creates a file document from a released Word template version: version 1.0 as a draft whose original is " +
          "a copy of the template's Word file with the template's field definitions. Values as for creating a " +
          "document; 409 if the template version has no Word file."
      )
      .tag("Documents")
      .serverLogic(p => req => createWordDocument(p, req))

  val upload: ZServerEndpoint[Env, ZioStreams] =
    SecuredApi.base.put
      .in(documents / documentId / "file")
      .in(header[Option[String]]("Content-Type"))
      .in(
        streamBody(ZioStreams)(summon[Schema[FileUpload]], CodecFormat.MultipartFormData())
          .description("multipart/form-data with one part `file`; its file name is kept with the original")
      )
      .out(jsonBody[DocumentVersionView])
      .description(
        "Uploads the original of a file document into its draft (the next working state), replacing the previous " +
          "file. Stored unchanged with its SHA-256, which the approval binds. The fields of a Word document (DOCX) " +
          "are read from its content controls (tag = field key, `dms:` prefix = automatic) and `{{key}}` " +
          "placeholders. 409 for structured documents and if the version is not a draft, 413 above the size " +
          "limit of attachments, 422 for blocked types, unreadable DOCX and types that cannot be converted into " +
          "PDF/A (`file_not_convertible`), 422 `attachment_infected`, 503 if the virus scanner or object storage " +
          "is unavailable."
      )
      .tag("Documents")
      .serverLogic(p => (id, contentType, body) => setFile(p, DocumentId(id), contentType, body))

  val fields: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(version / "word-fields")
      .out(jsonBody[WordFieldsView])
      .description(
        "The fields of the version's Word file with their values: manual fields as entered, automatic fields as " +
          "the platform fills them now. Requires reading the version; 404 without file."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, versionId) =>
          for
            found <- readable(p, id, versionId)
            (details, v, editable, approval) = found
            file <- ZIO
              .fromOption(v.file)
              .orElseFail((StatusCode.NotFound, ApiError("not_found", s"version ${v.label} has no file")))
            approvalFacts = FileVersions.approvalOf(v, approval)
            names <- VersionViewApi.displayNames(p, WordFilling.people(details, v, approvalFacts))
            auto = WordFilling.automatic(details, v, names, approvalFacts)
          yield WordFieldsView(
            v.id,
            v.label,
            v.status.toString,
            editable,
            file.fields.map(f =>
              WordFieldView(
                f.key,
                f.label,
                f.fieldType.toString,
                f.required,
                f.source.toString,
                if f.source == WordFieldSource.Manual then file.values.get(f.key) else auto.get(f.key)
              )
            )
          )
      )

  val setValues: ZServerEndpoint[Env, Any] =
    SecuredApi.base.put
      .in(documents / documentId / "word-fields")
      .in(jsonBody[WordValues])
      .out(jsonBody[DocumentVersionView])
      .description(
        "Enters the values of the manual fields of the draft's Word file (the next working state). 422 for unknown " +
          "keys and invalid values (dates yyyy-MM-dd, numbers); required fields are checked on submission. 409 if " +
          "the version is not a draft."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, req) =>
          DocumentApi.inTransaction(p) { ctx =>
            for
              _       <- editableDraft(ctx, p, DocumentId(id))
              updated <- ctx.service.setWordValues(DocumentId(id), req.values, p.userId)
            yield DocumentApi.versionView(updated)
          }
      )

  val download: ZServerEndpoint[Env, ZioStreams] =
    SecuredApi.base.get
      .in(version / "file")
      .out(header[String]("Content-Type"))
      .out(header[String]("Content-Disposition"))
      .out(header[Long]("Content-Length"))
      .out(header[String]("ETag"))
      .out(header[String]("Repr-Digest").description("SHA-256 of the original (RFC 9530)"))
      .out(header[String]("X-Content-Type-Options"))
      .out(streamBinaryBody(ZioStreams)(CodecFormat.OctetStream()))
      .description(
        "Downloads the original of a file document version unchanged (e.g. the Word file as uploaded), as " +
          "attachment with its SHA-256 as Repr-Digest and ETag; requires reading the version. 404 if the version " +
          "has no file. The stored object is checked against the recorded SHA-256 before it is sent."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, versionId) =>
          for
            found <- readable(p, id, versionId)
            file  <- ZIO
              .fromOption(found._2.file)
              .orElseFail(
                (StatusCode.NotFound, ApiError("not_found", s"version ${found._2.label} has no file"))
              )
            stream <- ZIO.serviceWithZIO[AttachmentUploads](_.read(file)).mapError(AttachmentApi.toResponse)
            digest = Sha256.fromHex(file.sha256).fold(_ => "", d => s"sha-256=:${d.base64}:")
          yield (
            file.contentType,
            AttachmentApi.contentDisposition(file.name),
            file.size,
            s"\"${file.sha256}\"",
            digest,
            "nosniff",
            stream
          )
      )

  val filledDocx: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(version / "filled.docx")
      .out(header[String]("Content-Type"))
      .out(header[String]("Content-Disposition"))
      .out(header("Cache-Control", "private, no-store"))
      .out(header("X-Content-Type-Options", "nosniff"))
      .out(byteArrayBody)
      .description(
        "The Word document of a version with its fields filled: automatic fields from the platform (with the " +
          "approval once approved), manual fields as entered. The editable artifact; filled on request, not " +
          "stored, the download is audited with the SHA-256 of the bytes. 409 if the original is not a DOCX. " +
          "Requires reading the version."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, versionId) =>
          for
            found <- readable(p, id, versionId)
            (details, v, _, approval) = found
            file <- ZIO
              .fromOption(v.file.filter(_.isWord))
              .orElseFail((StatusCode.Conflict, ApiError("conflict", s"version ${v.label} has no Word file")))
            approvalFacts = FileVersions.approvalOf(v, approval)
            names <- VersionViewApi.displayNames(p, WordFilling.people(details, v, approvalFacts))
            bytes <- FileVersions.filled(details, v, names, approvalFacts).mapError(VersionViewApi.toResponse)
            _     <- audit(p, v, FilledDocxAction, bytes.toArray)
          yield (VersionViewApi.DocxMediaType, AttachmentApi.contentDisposition(file.name), bytes.toArray)
      )

  val preview: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(version / "preview.pdf")
      .out(header(Header.contentType(MediaType.ApplicationPdf)))
      .out(header[String]("Content-Disposition"))
      .out(header("Cache-Control", "private, no-store"))
      .out(header("X-Content-Type-Options", "nosniff"))
      .out(byteArrayBody)
      .description(
        "A PDF of a file document version for reading in the browser: the archived rendition of an approved " +
          "version; otherwise the original (a Word file with its fields filled) converted on request with the " +
          "draft marking on every page. Not stored. 409 for structured documents (see …/html), 404 without file, " +
          "503 if the conversion worker is unavailable. Requires ReadDocument on the version."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, versionId) =>
          for
            found <- readable(p, id, versionId)
            (details, v, _, approval) = found
            _ <- ZIO.unless(details.document.kind == DocumentKind.File)(
              ZIO.fail(
                (
                  StatusCode.Conflict,
                  ApiError("conflict", s"document $id is structured; read its content as HTML")
                )
              )
            )
            _ <- ZIO.when(v.file.isEmpty)(
              ZIO.fail((StatusCode.NotFound, ApiError("not_found", s"version ${v.label} has no file yet")))
            )
            bytes   <- pdfOf(p, details, v, approval, archived = true)
            stamped <-
              if DocumentRenditions.drafted(v) then
                ZIO
                  .fromEither(PdfComposer.stamp(bytes.toArray, draftLines(details, v), emphasize = true))
                  .mapError(m => VersionViewApi.toResponse(RenderingError.Invalid(m)))
              else ZIO.succeed(bytes.toArray)
          yield (s"""inline; filename="$id-${v.label}.pdf"""", stamped)
      )

  def endpoints: List[ZServerEndpoint[Env, ZioStreams]] =
    List(createFromWord, upload, fields, setValues, download, filledDocx, preview)

  private val UploadPath = "/api/v1/documents/[0-9a-fA-F-]{36}/file/?".r

  /** Whether a request uploads the original of a file document; its body may be streamed (see `Main`). */
  def isUpload(method: zio.http.Method, path: zio.http.Path): Boolean =
    method == zio.http.Method.PUT && UploadPath.matches(path.encode)

  /** The PDF of a file version: with `archived` the archived rendition of an approved version, otherwise (and
    * for versions without rendition) the filled original converted on request.
    */
  private[app] def pdfOf(
      p: Principal,
      details: DocumentDetails,
      v: DocumentVersion,
      approval: Option[Approval],
      archived: Boolean
  ): ZIO[FileVersions & TenantOrganizations & UserDirectory, (StatusCode, ApiError), Chunk[Byte]] =
    ZIO.serviceWith[FileVersions](_.archived(v)).flatMap {
      case Some(stored) if archived => stored.mapError(VersionViewApi.toResponse)
      case _                        =>
        val approvalFacts = FileVersions.approvalOf(v, approval)
        for
          names  <- VersionViewApi.displayNames(p, WordFilling.people(details, v, approvalFacts))
          filled <- FileVersions.filled(details, v, names, approvalFacts).mapError(VersionViewApi.toResponse)
          name = v.file.fold("original")(_.name)
          pdf <- ZIO.serviceWithZIO[FileVersions](_.toPdf(filled, name)).mapError(VersionViewApi.toResponse)
        yield pdf
    }

  /** The draft marking and identification written on every page of an unapproved file version. */
  private[app] def draftLines(details: DocumentDetails, v: DocumentVersion): List[String] =
    val facts = PrintFacts(
      documentId = details.document.id.value.toString,
      documentNumber = details.document.number,
      title = DocumentRenditions.title(details, v),
      version = v.label,
      language = DocumentRenditions.language(v).code,
      generatedAt = java.time.Instant.EPOCH,
      generatedBy = "",
      zone = details.zone
    )
    List(VersionPrintLayout.marking(facts), VersionPrintLayout.identification(facts))

  /** The document, the version if the caller may read it, whether the caller may edit it (a draft) and its
    * workflow approval.
    */
  private def readable(p: Principal, id: UUID, versionId: UUID) =
    DocumentApi.inTransaction(p) { ctx =>
      for
        details <- ctx.service.get(DocumentId(id))
        v       <- ZIO
          .fromOption(details.versions.find(_.id == versionId))
          .orElseFail(DocumentError.NotFound(s"document $id has no version $versionId"))
        _        <- ctx.checkRead(details, v)
        editable <-
          if v.status == DocumentStatus.Draft then
            ctx.allowed(Action.EditDraft, DocumentApi.resource(p.tenantId, details, v))
          else ZIO.succeed(false)
        cases <- ctx.workflow.cases(details.document.id)
      yield (details, v, editable, cases.flatMap(_.approval).find(_.versionId == v.id))
    }

  /** The draft of the file document if the caller may edit it; a version in review or approval is checked as
    * if it were a draft, so that its author learns that it is frozen (409) rather than forbidden (403).
    */
  private def editableDraft(ctx: DocumentApi.Context, p: Principal, id: DocumentId) =
    for
      details <- ctx.service.get(id)
      _       <- ZIO.unless(details.document.kind == DocumentKind.File)(
        ZIO.fail(DocumentError.Conflict(s"document ${id.value} is not a file document"))
      )
      open <- ZIO
        .fromOption(details.open)
        .orElseFail(DocumentError.Conflict(s"document ${id.value} has no open version"))
      _ <- ctx.check(
        Action.EditDraft,
        DocumentApi.resource(p.tenantId, details, open).copy(status = Some(DocumentStatus.Draft))
      )
      _ <- ZIO.unless(open.status == DocumentStatus.Draft)(
        ZIO.fail(
          DocumentError.Conflict(
            s"version ${open.label} is ${open.status}; the file only changes while the version is a draft"
          )
        )
      )
    yield open

  private def setFile(
      p: Principal,
      id: DocumentId,
      contentType: Option[String],
      body: ZStream[Any, Throwable, Byte]
  ): ZIO[Env, (StatusCode, ApiError), DocumentVersionView] =
    for
      service <- ZIO.service[DocumentService]
      uploads <- ZIO.service[AttachmentUploads]
      // Authorize before reading the body.
      draft    <- DocumentApi.inTransaction(p)(ctx => editableDraft(ctx, p, id))
      boundary <- AttachmentApi.multipartBoundary(contentType)
      view     <- ZIO.scoped(
        for
          received <- AttachmentApi.receive(uploads, boundary, body).mapError(AttachmentApi.toResponse)
          (rawName, declared, spooled) = received
          name     <- validName(rawName)
          _        <- uploads.check(name, spooled).mapError(AttachmentApi.toResponse)
          _        <- convertible(name)
          fields   <- scanned(name, spooled, draft.file.map(_.fields).getOrElse(Nil))
          existing <- DocumentApi.inTransaction(p)(_ => service.attachmentFile(id, spooled.sha256))
          file     <- existing match
            case Some(f) => ZIO.succeed((f, None))
            case None    =>
              for
                status <- uploads.scan(spooled).mapError(AttachmentApi.toResponse)
                now    <- Clock.instant
                fileId = UUID.randomUUID()
                kind   = AttachmentRules.contentType(declared, name)
                stored <- uploads
                  .store(p.tenantId, id, fileId, spooled, kind)
                  .mapError(AttachmentApi.toResponse)
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
          updated <- DocumentApi
            .inTransaction(p) { ctx =>
              editableDraft(ctx, p, id).flatMap(_ => service.setFile(id, candidate, name, fields, p.userId))
            }
            .tapError(_ => AttachmentApi.discard(uploads, fresh))
          (version, inserted) = updated
          _ <- ZIO.unless(inserted)(AttachmentApi.discard(uploads, fresh))
        yield DocumentApi.versionView(version)
      )
    yield view

  private[app] def validName(raw: String): IO[(StatusCode, ApiError), String] =
    ZIO
      .fromEither(AttachmentRules.normalizeName(raw))
      .mapError(m => (StatusCode.UnprocessableEntity, ApiError("invalid_name", m)))

  private def convertible(name: String): IO[(StatusCode, ApiError), Unit] =
    ZIO
      .unless(DocumentConverter.convertible(name))(
        ZIO.fail(
          (
            StatusCode.UnprocessableEntity,
            ApiError(
              "file_not_convertible",
              "files of this type cannot be converted into PDF/A for the archive; supported: " +
                DocumentConverter.ConvertibleExtensions.toList.sorted.mkString(", ")
            )
          )
        )
      )
      .unit

  /** The fields of a received DOCX, with the definitions of `previous` for keys known before; none for other
    * files.
    */
  private[app] def scanned(
      name: String,
      spooled: SpooledFile,
      previous: List[WordField]
  ): IO[(StatusCode, ApiError), List[WordField]] =
    if !name.toLowerCase(java.util.Locale.ROOT).endsWith(".docx") then ZIO.succeed(Nil)
    else
      ZIO
        .attemptBlocking(Files.readAllBytes(spooled.path))
        .orDie
        .flatMap(bytes =>
          ZIO
            .fromEither(WordDocx.scan(bytes))
            .mapError(m => (StatusCode.UnprocessableEntity, ApiError("invalid_docx", m)))
        )
        .map(WordFields.merge(_, previous))

  private def createWordDocument(
      p: Principal,
      req: CreateWordDocument
  ): ZIO[Env, (StatusCode, ApiError), DocumentView] =
    for
      words   <- ZIO.service[WordTemplateService]
      uploads <- ZIO.service[AttachmentUploads]
      created <- DocumentApi.inTransaction(p) { ctx =>
        for
          context <- ctx.service.templateContext(TemplateId(req.templateId), req.templateVersion)
          (_, profile) = context
          word <- words
            .get(TemplateId(req.templateId), req.templateVersion)
            .someOrFail(
              DocumentError.Conflict(
                s"template ${req.templateId} version ${req.templateVersion} has no Word file"
              )
            )
          _ <- ctx.check(Action.CreateFromTemplate, DocumentApi.creationResource(p, profile, req.values))
          _ <- ctx.checkGroups(profile, req.values)
          created <- ctx.service.create(
            NewDocument(
              TemplateId(req.templateId),
              req.templateVersion,
              req.title,
              req.values.map((k, v) => k -> FieldEntry(v, ValueOrigin.Manual)),
              DocumentKind.File
            ),
            p.userId
          )
        yield (created._1, word)
      }
      (document, word) = created
      // The template's Word file becomes the original of the draft: a copy per document, so that retention and
      // legal holds follow the document (ADR 0041).
      bytes <- uploads
        .readBytes(word.objectKey, word.objectVersion, word.sha256)
        .mapError(AttachmentApi.toResponse)
      fileId = UUID.randomUUID()
      stored <- uploads
        .storeBytes(p.tenantId, document.id, fileId, bytes, VersionViewApi.DocxMediaType)
        .mapError(AttachmentApi.toResponse)
      now <- Clock.instant
      file = AttachmentFile(
        fileId,
        document.id,
        word.sha256,
        word.size,
        VersionViewApi.DocxMediaType,
        stored.key,
        stored.versionId,
        word.scanStatus,
        s"template ${word.templateId} v${word.version}",
        now,
        p.userId
      )
      view <- DocumentApi.inTransaction(p) { ctx =>
        for
          _       <- ctx.service.setFile(document.id, file, word.name, word.fields, p.userId)
          details <- ctx.service.get(document.id)
          current <- ZIO.fromOption(details.open).orElseFail(DocumentError.NotFound("the draft vanished"))
        yield DocumentApi
          .documentView(details.document, details.profile, DocumentApi.versionView(current))
      }
    yield view

  private def audit(
      p: Principal,
      v: DocumentVersion,
      action: String,
      bytes: Array[Byte]
  ): ZIO[Db & AuditTrail, (StatusCode, ApiError), Unit] =
    val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    ZIO
      .serviceWithZIO[Db](
        _.transact(p.tenantId)(
          ZIO.serviceWithZIO[AuditTrail](
            _.record(
              AuditEvent(
                Actor(p.userId.value, ActorKind.Person),
                action,
                AuditObject("document", Some(v.documentId.value), Some(v.label)),
                Channel.Api,
                Jsonb(
                  Json
                    .Obj(
                      "versionId" -> Json.Str(v.id.toString),
                      "status"    -> Json.Str(v.status.toString),
                      "sha256"    -> Json.Str(sha256),
                      "bytes"     -> Json.Num(bytes.length)
                    )
                    .toJson
                )
              )
            )
          )
        )
      )
      .mapError(DocumentApi.toResponse)
      .unit
