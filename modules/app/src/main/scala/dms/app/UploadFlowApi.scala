package dms.app

import dms.authz.{Action, Authorizer, Role, Subject}
import dms.db.Db
import dms.documents.{DocumentStatus, ScanStatus, Versioning, WordTemplateFile}
import dms.documents.core.{AttachmentUploads, DocumentClassService, DocumentJson, WordTemplateService}
import dms.documents.metadata.DocumentClassVersion
import dms.identity.Principal
import dms.templates.core.{TemplateDraft, TemplateService}
import dms.templates.{WordField, WordFieldSource}
import sttp.capabilities.zio.ZioStreams
import sttp.model.{Header, MediaType, StatusCode}
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import sttp.tapir.{CodecFormat, Schema}
import zio.*
import zio.json.*

import java.nio.file.Files
import java.util.UUID

/** Documentation of the multipart request of an upload preview: `values` (JSON object of the manual values by
  * key) before `file`.
  */
final case class UploadPreviewForm(values: String, file: Array[Byte]) derives Schema

/** A file analysed before it becomes a document or a template (DMS-127). Nothing is stored. */
final case class UploadAnalysisView(
    name: String,
    size: Long,
    sha256: String,
    /** A Word document (DOCX): only these can become Word templates. */
    word: Boolean,
    /** Whether fields are read and filled (Word, Excel, PowerPoint). */
    fillable: Boolean,
    /** Whether the platform filled the file before: its values were read from the custom XML part, the custom
      * document properties and the content controls.
      */
    filledBefore: Boolean,
    /** The fields found: manual fields with the value the file carries (if valid), automatic fields with the
      * value the platform fills now (the rest, e.g. the document number, once the document is created).
      */
    fields: List[WordFieldView]
) derives JsonCodec,
      Schema

/** The upload template of a document class: create the file document from it (DMS-127, ADR 0049). */
final case class UploadTemplateView(
    templateId: UUID,
    version: Int,
    name: String,
    documentClassId: UUID,
    documentClassVersion: Int
) derives JsonCodec,
      Schema

/** Request to make a Word template from the original of a document version (DMS-127). */
final case class TemplateFromVersion(
    name: String,
    description: Option[String] = None
) derives JsonCodec,
      Schema

/** Uploading a finished file and deciding at the end what it becomes (DMS-127, ADR 0049): a document of a
  * class (through the class's upload template, created on demand, then the normal lifecycle) or a Word
  * template (existing template endpoints, four-eyes release). The wizard analyses and previews the file
  * without storing it; the original is stored unchanged with its SHA-256 only when the document or template
  * is created.
  *
  *   - `GET /api/v1/uploads/classes`: the classes the caller may create documents in (author or owner role
  *     covering the class).
  *   - `POST /api/v1/uploads/classes/{classId}/template`: the upload template of the class, created and
  *     released by the platform on demand; then `POST /api/v1/documents` (kind File), `PUT …/file`,
  *     `PUT …/word-fields` as for every file document.
  *   - `POST /api/v1/uploads/fields`: the fields of a file with the values it carries (re-uploaded filled
  *     documents) and the automatic values known before creation.
  *   - `POST /api/v1/uploads/preview.pdf`: the file filled with the entered values, converted and marked.
  *   - `POST /api/v1/documents/{id}/versions/{versionId}/word-template`: "use as template": a new Word
  *     template draft with a copy of the version's original and its field definitions, without the values.
  */
object UploadFlowApi:
  type Env = FileDocumentApi.Env & WordTemplateApi.Env

  private val uploads  = "api" / "v1" / "uploads"
  private val language = query[Option[String]]("language")
    .description("Document language of dates and labels: de or en (default)")

  /** Roles that may create documents. */
  private val Creating: Set[Role] = Set(Role.Author, Role.Owner)

  /** Roles that may use the upload: create documents or templates. */
  private val Uploading: Set[Role] = Creating + Role.TemplateDesigner

  val classes: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(uploads / "classes")
      .out(jsonBody[List[TemplateClassView]])
      .description(
        "The document classes an uploaded file can become a document of: the latest version of every class " +
          "the caller holds the author or owner role for (tenant-wide or restricted to the class or an " +
          "ancestor), with label path, sorted by path."
      )
      .tag("Uploads")
      .serverLogic(p =>
        _ =>
          for found <- inTransaction(p)((subject, classes, now) =>
              classes.listClasses.mapError(e => Failure.Invalid(e.toString)).map { all =>
                val keys = keyPaths(all)
                all.filter(c => mayCreateIn(subject, keys.getOrElse(c.classId.value, Nil), now))
              }
            )
          yield WordTemplateApi.classViews(found)
      )

  val classTemplate: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(uploads / "classes" / path[UUID]("classId").description("Document class id") / "template")
      .out(jsonBody[UploadTemplateView])
      .description(
        "The upload template of a document class for its latest version (DMS-127, ADR 0049): a template with " +
          "empty structured content from which file documents of the class are created (POST " +
          "/api/v1/documents with kind File), supplying the metadata form and the number range. Created and " +
          "released by the platform on first use and when the class has a new version; not listed in the " +
          "template catalog. Requires the author or owner role for the class; 404 for unknown classes."
      )
      .tag("Uploads")
      .serverLogic(p =>
        classId =>
          for found <- inTransaction(p) { (subject, classes, now) =>
              for
                all <- classes.listClasses.mapError(e => Failure.Invalid(e.toString))
                cls <- ZIO
                  .fromOption(all.find(_.classId.value == classId))
                  .orElseFail(Failure.NotFound(s"document class $classId does not exist"))
                _ <- ZIO.unless(mayCreateIn(subject, keyPaths(all).getOrElse(classId, Nil), now))(
                  ZIO.fail(
                    Failure.Forbidden("creating documents of this class requires the author or owner role")
                  )
                )
                template <- ZIO
                  .serviceWithZIO[TemplateService](
                    _.fileUploadTemplate(classId, cls.version, s"Upload: ${cls.label}", p.userId)
                  )
                  .mapError(e => Failure.Invalid(e.describe))
              yield template
            }
          yield UploadTemplateView(
            found.templateId.value,
            found.version,
            found.name,
            found.documentClassId,
            found.documentClassVersion
          )
      )

  val analyse: ZServerEndpoint[Env, ZioStreams] =
    SecuredApi.base.post
      .in(uploads / "fields")
      .in(language)
      .in(header[Option[String]]("Content-Type"))
      .in(
        streamBody(ZioStreams)(summon[Schema[FileUpload]], CodecFormat.MultipartFormData())
          .description("multipart/form-data with one part `file`")
      )
      .out(jsonBody[UploadAnalysisView])
      .description(
        "Analyses a file before it becomes a document or a template (DMS-127); nothing is stored. The fields " +
          "of a Word document (content controls, {{key}} placeholders) or of an Excel/PowerPoint file " +
          "({{key}} placeholders) with defaults; manual fields with the value the file carries (a document " +
          "filled by the platform keeps its values in a custom XML part and custom properties), automatic " +
          "fields with the value known before creation (author, title, version, status). Requires the author, " +
          "owner or template designer role. 422 like the upload of an original (blocked, not convertible, " +
          "content not fitting the extension, unreadable), 413 above the size limit."
      )
      .tag("Uploads")
      .serverLogic(p =>
        (lang, contentType, body) =>
          for
            _        <- requireUploader(p)
            boundary <- AttachmentApi.multipartBoundary(contentType)
            view     <- ZIO.scoped(
              for
                received <- receive(boundary, body)
                (_, name, spooled) = received
                bytes    <- ZIO.attemptBlocking(Files.readAllBytes(spooled.path)).orDie
                fields   <- FileDocumentApi.scanned(name, spooled, Nil)
                found    <- recovered(name, bytes, fields)
                provided <- provisional(p, name, languageOf(lang))
              yield UploadAnalysisView(
                name,
                spooled.size,
                spooled.sha256,
                OfficeFields.format(name).contains(OfficeFields.Format.Docx),
                OfficeFields.fillable(name),
                found._1,
                fields.map(f =>
                  fieldView(
                    f,
                    if f.source == WordFieldSource.Manual then found._2.get(f.key)
                    else provided.get(f.key).map(_.printed)
                  )
                )
              )
            )
          yield view
      )

  val preview: ZServerEndpoint[Env, ZioStreams] =
    SecuredApi.base.post
      .in(uploads / "preview.pdf")
      .in(language)
      .in(header[Option[String]]("Content-Type"))
      .in(
        streamBody(ZioStreams)(summon[Schema[UploadPreviewForm]], CodecFormat.MultipartFormData())
          .description(
            "multipart/form-data: part `values` (JSON object of the manual values by key, optional) before " +
              "part `file`"
          )
      )
      .out(header(Header.contentType(MediaType.ApplicationPdf)))
      .out(header("Cache-Control", "private, no-store"))
      .out(header("X-Content-Type-Options", "nosniff"))
      .out(byteArrayBody)
      .description(
        "A PDF preview of a file before it becomes a document (DMS-127): the fields filled with the entered " +
          "manual values and the automatic values known before creation, converted by the conversion worker " +
          "and marked as preview on every page; the file is virus-scanned before it is converted. Not " +
          "stored, not audited. 422 for invalid values or files (as for the upload of an original, " +
          "`attachment_infected` included), 503 if the conversion worker is unavailable. Requires the " +
          "author, owner or template designer role."
      )
      .tag("Uploads")
      .serverLogic(p =>
        (lang, contentType, body) =>
          for
            _        <- requireUploader(p)
            boundary <- AttachmentApi.multipartBoundary(contentType)
            pdf      <- ZIO.scoped(
              for
                received <- receive(boundary, body)
                (form, name, spooled) = received
                values <- ZIO
                  .fromEither(form.get("values").filter(_.trim.nonEmpty).fold(Right(Map.empty))(parseValues))
                  .mapError(m => (StatusCode.UnprocessableEntity, ApiError("invalid", m)))
                bytes  <- ZIO.attemptBlocking(Files.readAllBytes(spooled.path)).orDie
                fields <- FileDocumentApi.scanned(name, spooled, Nil)
                // the conversion worker gets only files the virus scanner let through, as for stored originals
                _ <- ZIO.serviceWithZIO[AttachmentUploads](_.scan(spooled)).mapError(AttachmentApi.toResponse)
                _ <- ZIO
                  .fromEither {
                    val problems = dms.templates.WordFields
                      .check(
                        fields,
                        values.filter(_._2.trim.nonEmpty),
                        complete = false,
                        DocumentJson.readRows
                      )
                    Either.cond(problems.isEmpty, (), problems.mkString("; "))
                  }
                  .mapError(m => (StatusCode.UnprocessableEntity, ApiError("invalid", m)))
                lang2 = languageOf(lang)
                provided <- provisional(p, name, lang2)
                filled   <- ZIO
                  .fromEither(
                    OfficeFields.fill(name, bytes, previewValues(fields, values, provided, lang2))
                  )
                  .mapError(m => VersionViewApi.toResponse(dms.rendering.core.RenderingError.Invalid(m)))
                pdf <- ZIO
                  .serviceWithZIO[FileVersions](_.toPdf(Chunk.fromArray(filled), name))
                  .mapError(VersionViewApi.toResponse)
                stamped <- ZIO
                  .fromEither(PdfComposer.stamp(pdf.toArray, List(previewMarking(lang2)), emphasize = true))
                  .mapError(m => VersionViewApi.toResponse(dms.rendering.core.RenderingError.Invalid(m)))
              yield stamped
            )
          yield pdf
      )

  val templateFromVersion: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(
        "api" / "v1" / "documents" / path[UUID]("id").description("Document id") / "versions" /
          path[UUID]("versionId").description("Version id") / "word-template"
      )
      .in(jsonBody[TemplateFromVersion])
      .out(statusCode(StatusCode.Created).and(jsonBody[WordTemplateView]))
      .description(
        "Use as template (DMS-127): a new Word template whose draft version 1 has a copy of the version's " +
          "original (unchanged) and its field definitions, without the entered values, for the latest version " +
          "of the document's class. Testing and the four-eyes release follow as for every template; the caller " +
          "is its creator. Requires reading the version and the template designer role (EditTemplates); 409 " +
          "if the original is not a Word document (DOCX)."
      )
      .tag("Uploads")
      .serverLogic(p => (id, versionId, req) => useAsTemplate(p, id, versionId, req))

  def endpoints: List[ZServerEndpoint[Env, ZioStreams]] =
    List(classes, classTemplate, analyse, preview, templateFromVersion)

  private val UploadPath = "/api/v1/uploads/(fields|preview\\.pdf)/?".r

  /** Whether a request uploads a file to analyse or preview; its body may be streamed (see `Main`). */
  def isUpload(method: zio.http.Method, path: zio.http.Path): Boolean =
    method == zio.http.Method.POST && UploadPath.matches(path.encode)

  // Helpers

  private enum Failure:
    case Forbidden(message: String)
    case NotFound(message: String)
    case Invalid(message: String)

  private def response(f: Failure | dms.db.DbError): (StatusCode, ApiError) = f match
    case Failure.Forbidden(m) =>
      (StatusCode.Forbidden, ApiError("forbidden", s"You are not allowed to do this: $m."))
    case Failure.NotFound(m) => (StatusCode.NotFound, ApiError("not_found", m))
    case Failure.Invalid(m)  => (StatusCode.UnprocessableEntity, ApiError("invalid", m))
    case e: dms.db.DbError   => DocumentApi.toResponse(e)

  /** Runs `body` in a transaction of the caller's tenant with their subject. */
  private def inTransaction[A](p: Principal)(
      body: (Subject, DocumentClassService, java.time.Instant) => ZIO[TemplateService, Failure, A]
  ): ZIO[Env, (StatusCode, ApiError), A] =
    (for
      db      <- ZIO.service[Db]
      authz   <- ZIO.service[Authorizer]
      classes <- ZIO.service[DocumentClassService]
      now     <- Clock.instant
      result  <- db.transact(p.tenantId)(authz.subject(p).flatMap(s => body(s, classes, now)))
    yield result).mapError(response)

  private def requireUploader(p: Principal): ZIO[Env, (StatusCode, ApiError), Unit] =
    inTransaction(p)((subject, _, now) =>
      ZIO
        .unless(subject.assignments.exists(a => a.isActiveAt(now) && Uploading(a.role)))(
          ZIO.fail(Failure.Forbidden("uploading requires the author, owner or template designer role"))
        )
        .unit
    )

  /** Whether the subject holds the author or owner role for a class with the key path `keys` (root first),
    * whatever the organizational unit (the unit is checked when the document is created).
    */
  private[app] def mayCreateIn(subject: Subject, keys: List[String], now: java.time.Instant): Boolean =
    keys.nonEmpty && subject.assignments.exists(a =>
      a.isActiveAt(now) && Creating(a.role) && a.documentClass.forall(keys.contains)
    )

  /** The class keys from the root to each class, by class id. */
  private def keyPaths(all: List[DocumentClassVersion]): Map[UUID, List[String]] =
    val byId = all.map(c => c.classId.value -> c).toMap
    def path(c: DocumentClassVersion, seen: Set[UUID]): List[String] =
      c.parent
        .flatMap(ref => byId.get(ref.id.value))
        .filterNot(parent => seen.contains(parent.classId.value))
        .fold(Nil)(parent => path(parent, seen + c.classId.value)) :+ c.key.value
    all.map(c => c.classId.value -> path(c, Set.empty)).toMap

  private def receive(boundary: String, body: zio.stream.ZStream[Any, Throwable, Byte]) =
    for
      uploads  <- ZIO.service[AttachmentUploads]
      received <- AttachmentApi.receiveForm(uploads, boundary, body).mapError(AttachmentApi.toResponse)
      (form, rawName, _, spooled) = received
      name <- FileDocumentApi.validName(rawName)
      _    <- uploads.check(name, spooled).mapError(AttachmentApi.toResponse)
      _    <- FileDocumentApi.convertible(name)
      _    <- FileDocumentApi.contentMatches(name, spooled)
    yield (form, name, spooled)

  /** Whether the platform filled the file before, and the valid values it carries for manual fields. */
  private[app] def recovered(
      name: String,
      bytes: Array[Byte],
      fields: List[WordField]
  ): IO[(StatusCode, ApiError), (Boolean, Map[String, String])] =
    ZIO
      .fromEither(OfficeFields.recover(name, bytes))
      .mapBoth(
        m => (StatusCode.UnprocessableEntity, ApiError("invalid_docx", m)),
        r => (r.filledByPlatform, OfficeFields.pick(fields, r, DocumentJson.readRows))
      )

  private def languageOf(lang: Option[String]): String =
    lang.map(_.trim.toLowerCase(java.util.Locale.ROOT)).filter(_.startsWith("de")).fold("en")(_ => "de")

  /** The automatic values known before the document exists: author (the caller), title (the file name),
    * version and status of a new draft.
    */
  private def provisional(
      p: Principal,
      name: String,
      language: String
  ): URIO[Env, Map[String, FieldValue]] =
    VersionViewApi.displayNames(p, Set(p.userId)).map { names =>
      val title = name.lastIndexOf('.') match
        case i if i > 0 => name.substring(0, i)
        case _          => name
      Map(
        "author"  -> FieldValue.Text(names.getOrElse(p.userId, p.userId.value)),
        "title"   -> FieldValue.Text(title),
        "version" -> FieldValue.Text(Versioning.label(Versioning.first, DocumentStatus.Draft, 1)),
        "status"  -> FieldValue.Text(VersionPrintLayout.statusLabel(DocumentStatus.Draft.toString, language))
      )
    }

  /** The values filled into the preview: automatic ones known now, manual ones as entered. */
  private def previewValues(
      fields: List[WordField],
      values: Map[String, String],
      provided: Map[String, FieldValue],
      language: String
  ): Map[String, FieldValue] =
    WordFilling.manualValues(fields, values, language) ++
      fields.filter(_.source == WordFieldSource.Automatic).flatMap(f => provided.get(f.key).map(f.key -> _))

  private def parseValues(json: String): Either[String, Map[String, String]] =
    json.fromJson[Map[String, String]].left.map(e => s"values: a JSON object of strings by key ($e)")

  private def previewMarking(language: String): String =
    if language == "de" then "VORSCHAU – noch nicht angelegt, nicht gültig"
    else "PREVIEW – not created yet, not valid"

  private def fieldView(f: WordField, value: Option[String]): WordFieldView =
    WordFieldView(
      f.key,
      f.label,
      f.fieldType.toString,
      f.required,
      f.source.toString,
      value,
      f.options,
      f.columns.map(c => WordColumnView(c.key, c.label, c.fieldType.toString, c.required, c.options)),
      f.warning
    )

  private def useAsTemplate(
      p: Principal,
      id: UUID,
      versionId: UUID,
      req: TemplateFromVersion
  ): ZIO[Env, (StatusCode, ApiError), WordTemplateView] =
    for
      _ <- ZIO.when(req.name.trim.isEmpty)(
        ZIO.fail((StatusCode.UnprocessableEntity, ApiError("invalid", "the name must not be empty")))
      )
      found <- FileDocumentApi.readable(p, id, versionId)
      (details, v, _, _) = found
      file <- ZIO
        .fromOption(v.file.filter(_.isWord))
        .orElseFail(
          (StatusCode.Conflict, ApiError("conflict", s"version ${v.label} has no Word document (DOCX)"))
        )
      // The template binds the latest version of the document's class; designers may not read the class
      // configuration, so it is read here.
      classes <- ZIO.service[DocumentClassService]
      created <- WordTemplateApi.inTransaction(p, Action.EditTemplates)(
        for
          latest <- classes.listClasses
            .mapError(e => dms.documents.core.DocumentError.Invalid(List(e.toString)))
          cls <- ZIO
            .fromOption(latest.find(_.classId == details.document.classId))
            .orElseFail(dms.documents.core.DocumentError.NotFound("the document's class vanished"))
          template <- ZIO.serviceWithZIO[TemplateService](
            _.create(
              TemplateDraft(
                req.name.trim,
                req.description.map(_.trim).filter(_.nonEmpty),
                cls.classId.value,
                cls.version,
                WordTemplateApi.EmptyContent
              ),
              p.userId
            )
          )
        yield template
      )
      uploads <- ZIO.service[AttachmentUploads]
      bytes   <- uploads
        .readBytes(file.objectKey, file.objectVersion, file.sha256)
        .mapError(AttachmentApi.toResponse)
      stored <- uploads
        .storeBytesAt(
          WordTemplateService.objectKey(p.tenantId, created.templateId.value, UUID.randomUUID()),
          bytes,
          VersionViewApi.DocxMediaType
        )
        .mapError(AttachmentApi.toResponse)
      now  <- Clock.instant
      view <- WordTemplateApi
        .inTransaction(p, Action.EditTemplates)(
          ZIO.serviceWithZIO[WordTemplateService](
            _.set(
              WordTemplateFile(
                created.templateId.value,
                created.version,
                file.name,
                file.sha256,
                file.size,
                stored.key,
                stored.versionId,
                if file.scanStatus == ScanStatus.Clean then ScanStatus.Clean else ScanStatus.NotScanned,
                file.fields,
                p.userId,
                now
              ),
              p.userId
            )
          ) *> WordTemplateApi.viewOf(created.templateId.value, created.version)
        )
        .tapError(_ => uploads.discard(stored))
    yield view
