package dms.app

import dms.authz.{AccessDenied, Action, Authorizer, Resource}
import dms.db.{Db, DbError}
import dms.documents.WordTemplateFile
import dms.documents.core.{AttachmentUploads, DocumentClassService, DocumentError, WordTemplateService}
import dms.identity.Principal
import dms.kernel.TemplateId
import dms.storage.Sha256
import dms.templates.core.{TemplateDraft, TemplateError, TemplateService}
import dms.templates.{TemplateContent, TemplateStatus, WordField, WordFieldSource, WordFieldType}
import sttp.capabilities.zio.ZioStreams
import sttp.model.StatusCode
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import sttp.tapir.{CodecFormat, Schema}
import zio.*
import zio.json.JsonCodec
import zio.stream.ZStream

import java.time.Instant
import java.util.UUID

/** Definition of a column of a Rows field of a Word template (DMS-122). */
final case class WordColumnDefinition(
    /** Column key, as found in the repeating section of the Word file. */
    key: String,
    label: String,
    /** Text, Date, Number, Checkbox or Choice. */
    `type`: String,
    /** Required in every row before submission. */
    required: Boolean,
    /** Allowed values of a Choice column. */
    options: Option[List[String]] = None
) derives JsonCodec,
      Schema

/** Definition of a field of a Word template (DMS-115, DMS-122). */
final case class WordFieldDefinition(
    /** Field key without the `dms:` prefix, as found in the Word file. */
    key: String,
    /** Label in the form. */
    label: String,
    /** Text, Date, Number, Checkbox, Choice or Rows (repeating sections only). */
    `type`: String,
    /** Required before submission (manual fields). */
    required: Boolean,
    /** Automatic (filled by the platform from the system value or metadata field with this key) or Manual. */
    source: String,
    /** Allowed values of a Choice field: the items of a drop-down list, or defined by the designer. */
    options: Option[List[String]] = None,
    /** Columns of a Rows field; their keys are those of the Word file. */
    columns: Option[List[WordColumnDefinition]] = None,
    /** Read-only: what the platform cannot fill as the Word file shows it. */
    warning: Option[String] = None
) derives JsonCodec,
      Schema

/** The Word file of a template version with its field definitions (DMS-115). */
final case class WordTemplateView(
    templateId: UUID,
    version: Int,
    /** Status of the template version: Draft, Testing, Released, Deprecated or Archived. */
    status: String,
    /** Name of the template version. */
    templateName: String,
    /** File name of the Word file. */
    name: String,
    size: Long,
    sha256: String,
    scanStatus: String,
    fields: List[WordFieldDefinition],
    uploadedBy: String,
    uploadedAt: Instant,
    downloadUrl: String
) derives JsonCodec,
      Schema

final case class WordFieldDefinitions(fields: List[WordFieldDefinition]) derives JsonCodec, Schema

/** A document class a template can be created for (DMS-121): its latest profile version, read-only. */
final case class TemplateClassView(
    id: UUID,
    key: String,
    /** Latest profile version; templates bind it. */
    version: Int,
    label: String,
    description: Option[String],
    /** Labels of the ancestors and the class itself, root first, e.g. `Contract › Service contract`. */
    path: List[String]
) derives JsonCodec,
      Schema

/** Word templates (DMS-115, ADR 0042): template versions with a Word file whose fields (content controls,
  * `{{key}}` placeholders) are filled for each document. Created like every template (name, class), then the
  * DOCX is uploaded into the draft; the field definitions (label, type, required, automatic or manual) are
  * edited in the draft; testing and the four-eyes release are those of every template. Administration
  * requires `EditTemplates`, the list of released Word templates `ReadTemplateCatalog`.
  */
object WordTemplateApi:
  type Env = TemplateApi.Env & WordTemplateService & AttachmentUploads & DocumentClassService

  private val admin      = "api" / "v1" / "admin" / "templates"
  private val templateId = path[UUID]("id").description("Template id")
  private val versionNo  = path[Int]("version").description("Template version number")
  private val word       = admin / templateId / "versions" / versionNo / "word"

  val list: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "admin" / "word-templates")
      .out(jsonBody[List[WordTemplateView]])
      .description("The Word files of all template versions that are Word templates, in any status")
      .tag("Templates")
      .serverLogic(p => _ => inTransaction(p, Action.EditTemplates)(all(_ => true)))

  val classes: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "admin" / "template-classes")
      .out(jsonBody[List[TemplateClassView]])
      .description(
        "The document classes templates can be created for (latest profile version, label and path), read-only, " +
          "sorted by path: for designers choosing the class of a new (Word) template without the configuration " +
          "of document classes. Requires EditTemplates."
      )
      .tag("Templates")
      .serverLogic(p =>
        _ =>
          (for
            db      <- ZIO.service[Db]
            authz   <- ZIO.service[Authorizer]
            classes <- ZIO.service[DocumentClassService]
            latest  <- db.transact(p.tenantId)(
              authz.subject(p).flatMap(authz.check(_, Action.EditTemplates, Resource(p.tenantId))) *>
                classes.listClasses
            )
          yield classViews(latest)).mapError(DocumentClassApi.toResponse)
      )

  /** The classes with the labels of their ancestors, sorted by that path. */
  private[app] def classViews(
      latest: List[dms.documents.metadata.DocumentClassVersion]
  ): List[TemplateClassView] =
    val byId = latest.map(c => c.classId -> c).toMap
    def path(c: dms.documents.metadata.DocumentClassVersion, seen: Set[UUID]): List[String] =
      c.parent
        .flatMap(ref => byId.get(ref.id))
        .filterNot(parent => seen.contains(parent.classId.value))
        .fold(Nil)(parent => path(parent, seen + c.classId.value)) :+ c.label
    latest
      .map(c =>
        TemplateClassView(c.classId.value, c.key.value, c.version, c.label, c.description, path(c, Set.empty))
      )
      .sortBy(_.path.mkString(" › ").toLowerCase(java.util.Locale.ROOT))

  val catalog: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "word-templates")
      .out(jsonBody[List[WordTemplateView]])
      .description(
        "Released Word templates for authors, with their fields: create documents from them with " +
          "POST /api/v1/documents/word."
      )
      .tag("Templates")
      .serverLogic(p =>
        _ => inTransaction(p, Action.ReadTemplateCatalog)(all(_.status == TemplateStatus.Released))
      )

  val get: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(word)
      .out(jsonBody[WordTemplateView])
      .description("The Word file of a template version; 404 if it is not a Word template")
      .tag("Templates")
      .serverLogic(p => (id, version) => inTransaction(p, Action.EditTemplates)(viewOf(id, version)))

  val upload: ZServerEndpoint[Env, ZioStreams] =
    SecuredApi.base.put
      .in(word)
      .in(header[Option[String]]("Content-Type"))
      .in(
        streamBody(ZioStreams)(summon[Schema[FileUpload]], CodecFormat.MultipartFormData())
          .description("multipart/form-data with one part `file`: the DOCX")
      )
      .out(jsonBody[WordTemplateView])
      .description(
        "Uploads the Word file (DOCX) of a draft template version, which makes it a Word template, or replaces " +
          "it. Its fields are read from the content controls (tag = key; `dms:` prefix = automatic) and " +
          "`{{key}}` placeholders; definitions of keys known before are kept. 409 unless the version is a draft, " +
          "422 for files that are not a readable DOCX or are blocked, 413 above the size limit of attachments."
      )
      .tag("Templates")
      .serverLogic(p => (id, version, contentType, body) => setFile(p, id, version, contentType, body))

  val setFields: ZServerEndpoint[Env, Any] =
    SecuredApi.base.put
      .in(word / "fields")
      .in(jsonBody[WordFieldDefinitions])
      .out(jsonBody[WordTemplateView])
      .description(
        "Changes the field definitions of a draft Word template: label, type (Text, Date, Number, Checkbox, " +
          "Choice with options; Rows only for repeating sections, with the columns' label, type, required and " +
          "options), required and " +
          "source (Automatic, Manual). The keys are those of the Word file. 409 unless the version is a draft."
      )
      .tag("Templates")
      .serverLogic(p =>
        (id, version, req) =>
          for
            fields <- ZIO.foreach(req.fields)(definition).mapError(invalid)
            view   <- inTransaction(p, Action.EditTemplates)(
              ZIO.serviceWithZIO[WordTemplateService](
                _.setFields(TemplateId(id), version, fields, p.userId)
              ) *> viewOf(id, version)
            )
          yield view
      )

  val download: ZServerEndpoint[Env, ZioStreams] =
    SecuredApi.base.get
      .in(word / "file")
      .out(header[String]("Content-Type"))
      .out(header[String]("Content-Disposition"))
      .out(header[Long]("Content-Length"))
      .out(header[String]("ETag"))
      .out(header[String]("Repr-Digest").description("SHA-256 of the file (RFC 9530)"))
      .out(header[String]("X-Content-Type-Options"))
      .out(streamBinaryBody(ZioStreams)(CodecFormat.OctetStream()))
      .description("Downloads the Word file of a template version (to adapt it in Word and upload it again)")
      .tag("Templates")
      .serverLogic(p =>
        (id, version) =>
          for
            file    <- inTransaction(p, Action.EditTemplates)(found(id, version))
            uploads <- ZIO.service[AttachmentUploads]
            bytes   <- uploads
              .readBytes(file.objectKey, file.objectVersion, file.sha256)
              .mapError(AttachmentApi.toResponse)
            digest = Sha256.fromHex(file.sha256).fold(_ => "", d => s"sha-256=:${d.base64}:")
          yield (
            VersionViewApi.DocxMediaType,
            AttachmentApi.contentDisposition(file.name),
            file.size,
            s"\"${file.sha256}\"",
            digest,
            "nosniff",
            ZStream.fromChunk(bytes)
          )
      )

  val newVersion: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(admin / templateId / "word-versions")
      .out(statusCode(StatusCode.Created).and(jsonBody[WordTemplateView]))
      .description(
        "Starts the next version of a Word template as a draft: name, description and class of the latest " +
          "version, its Word file and field definitions taken over (no bytes copied). 409 while another version " +
          "is in Draft or Testing or if the latest version has no Word file."
      )
      .tag("Templates")
      .serverLogic(p =>
        id =>
          inTransaction(p, Action.EditTemplates)(
            for
              templates <- ZIO.service[TemplateService]
              words     <- ZIO.service[WordTemplateService]
              latest    <- templates.versions(TemplateId(id)).map(_.maxBy(_.version))
              _         <- words
                .get(TemplateId(id), latest.version)
                .someOrFail(
                  DocumentError.Conflict(s"template $id version ${latest.version} has no Word file")
                )
              next <- templates.newVersion(
                TemplateId(id),
                TemplateDraft(
                  latest.name,
                  latest.description,
                  latest.documentClassId,
                  latest.documentClassVersion,
                  latest.content
                ),
                p.userId
              )
              _    <- words.carryOver(TemplateId(id), latest.version, next.version, p.userId)
              view <- viewOf(id, next.version)
            yield view
          )
      )

  def endpoints: List[ZServerEndpoint[Env, ZioStreams]] =
    List(list, classes, catalog, get, upload, setFields, download, newVersion)

  /** Empty structured content of a Word template version (its content is the Word file). */
  val EmptyContent: TemplateContent = TemplateContent(Nil)

  private val UploadPath = "/api/v1/admin/templates/[0-9a-fA-F-]{36}/versions/[0-9]+/word/?".r

  /** Whether a request uploads a Word template; its body may be streamed (see `Main`). */
  def isUpload(method: zio.http.Method, path: zio.http.Path): Boolean =
    method == zio.http.Method.PUT && UploadPath.matches(path.encode)

  private[app] type Work[A] =
    ZIO[TemplateService & WordTemplateService, DocumentError | TemplateError | AccessDenied, A]

  private[app] def inTransaction[A](p: Principal, action: Action)(
      body: Work[A]
  ): ZIO[Env, (StatusCode, ApiError), A] =
    (for
      db     <- ZIO.service[Db]
      authz  <- ZIO.service[Authorizer]
      result <- db.transact(p.tenantId)(
        for
          subject <- authz.subject(p)
          _       <- authz.check(subject, action, Resource(p.tenantId))
          a       <- body
        yield a
      )
    yield result).mapError {
      case e: DocumentError => DocumentApi.toResponse(e)
      case e: TemplateError => TemplateApi.toResponse(e)
      case e: AccessDenied  => TemplateApi.toResponse(e)
      case e: DbError       => TemplateApi.toResponse(e)
    }

  private def all(keep: dms.templates.TemplateVersion => Boolean): Work[List[WordTemplateView]] =
    for
      words    <- ZIO.service[WordTemplateService]
      versions <- words.wordVersions
      views    <- ZIO.foreach(versions.toList.sorted)((id, version) => viewIf(id, version, keep))
    yield views.flatten.sortBy(v => (v.templateName.toLowerCase, v.templateId, v.version))

  private def found(id: UUID, version: Int): Work[WordTemplateFile] =
    ZIO
      .serviceWithZIO[WordTemplateService](_.get(TemplateId(id), version))
      .someOrFail(DocumentError.NotFound(s"template $id version $version is not a Word template"))

  private[app] def viewOf(id: UUID, version: Int): Work[WordTemplateView] =
    viewIf(id, version, _ => true).someOrFail(
      DocumentError.NotFound(s"template $id version $version is not a Word template")
    )

  private def viewIf(
      id: UUID,
      version: Int,
      keep: dms.templates.TemplateVersion => Boolean
  ): Work[Option[WordTemplateView]] =
    for
      file     <- ZIO.serviceWithZIO[WordTemplateService](_.get(TemplateId(id), version))
      template <- ZIO.serviceWithZIO[TemplateService](_.version(TemplateId(id), version))
    yield file.filter(_ => keep(template)).map(f => view(f, template))

  private def view(f: WordTemplateFile, t: dms.templates.TemplateVersion): WordTemplateView =
    WordTemplateView(
      f.templateId,
      f.version,
      t.status.toString,
      t.name,
      f.name,
      f.size,
      f.sha256,
      f.scanStatus.toString,
      f.fields.map(definitionView),
      f.setBy.value,
      f.setAt,
      s"/api/v1/admin/templates/${f.templateId}/versions/${f.version}/word/file"
    )

  private[app] def definitionView(f: WordField): WordFieldDefinition =
    WordFieldDefinition(
      f.key,
      f.label,
      f.fieldType.toString,
      f.required,
      f.source.toString,
      Option.when(f.options.nonEmpty)(f.options),
      Option.when(f.columns.nonEmpty)(
        f.columns.map(c =>
          WordColumnDefinition(
            c.key,
            c.label,
            c.fieldType.toString,
            c.required,
            Option.when(c.options.nonEmpty)(c.options)
          )
        )
      ),
      f.warning
    )

  private def fieldType(key: String, name: String): IO[String, WordFieldType] =
    ZIO.fromOption(WordFieldType.values.find(_.toString == name)).orElseFail(s"$key: unknown type")

  private def definition(d: WordFieldDefinition): IO[String, WordField] =
    for
      kind   <- fieldType(d.key, d.`type`)
      source <- ZIO
        .fromOption(WordFieldSource.values.find(_.toString == d.source))
        .orElseFail(s"${d.key}: unknown source")
      columns <- ZIO.foreach(d.columns.getOrElse(Nil))(c =>
        fieldType(s"${d.key}.${c.key}", c.`type`).map(t =>
          WordField(c.key, c.label.trim, t, c.required, WordFieldSource.Manual, c.options.getOrElse(Nil))
        )
      )
    yield WordField(d.key, d.label.trim, kind, d.required, source, d.options.getOrElse(Nil), columns)

  private def invalid(message: String) = (StatusCode.UnprocessableEntity, ApiError("invalid", message))

  private def setFile(
      p: Principal,
      id: UUID,
      version: Int,
      contentType: Option[String],
      body: ZStream[Any, Throwable, Byte]
  ): ZIO[Env, (StatusCode, ApiError), WordTemplateView] =
    for
      uploads <- ZIO.service[AttachmentUploads]
      // Authorize and check the draft before reading the body.
      previous <- inTransaction(p, Action.EditTemplates)(
        for
          template <- ZIO.serviceWithZIO[TemplateService](_.version(TemplateId(id), version))
          _        <- ZIO.unless(template.status == TemplateStatus.Draft)(
            ZIO.fail(DocumentError.Conflict(s"template version $version is ${template.status}, not a draft"))
          )
          file <- ZIO.serviceWithZIO[WordTemplateService](_.get(TemplateId(id), version))
        yield file
      )
      boundary <- AttachmentApi.multipartBoundary(contentType)
      view     <- ZIO.scoped(
        for
          received <- AttachmentApi.receive(uploads, boundary, body).mapError(AttachmentApi.toResponse)
          (rawName, _, spooled) = received
          name <- FileDocumentApi.validName(rawName)
          _    <- uploads.check(name, spooled).mapError(AttachmentApi.toResponse)
          _    <- ZIO.unless(name.toLowerCase(java.util.Locale.ROOT).endsWith(".docx"))(
            ZIO.fail(invalid("a Word template is a Word document (.docx)"))
          )
          fields <- FileDocumentApi.scanned(name, spooled, previous.map(_.fields).getOrElse(Nil))
          status <- uploads.scan(spooled).mapError(AttachmentApi.toResponse)
          stored <- uploads
            .storeAt(
              WordTemplateService.objectKey(p.tenantId, id, UUID.randomUUID()),
              spooled,
              VersionViewApi.DocxMediaType
            )
            .mapError(AttachmentApi.toResponse)
          now  <- Clock.instant
          view <- inTransaction(p, Action.EditTemplates)(
            ZIO.serviceWithZIO[WordTemplateService](
              _.set(
                WordTemplateFile(
                  id,
                  version,
                  name,
                  spooled.sha256,
                  spooled.size,
                  stored.key,
                  stored.versionId,
                  status,
                  fields,
                  p.userId,
                  now
                ),
                p.userId
              )
            ) *> viewOf(id, version)
          ).tapError(_ => uploads.discard(stored))
        yield view
      )
    yield view
