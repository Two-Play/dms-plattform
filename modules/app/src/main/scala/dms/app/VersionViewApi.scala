package dms.app

import dms.audit.*
import dms.db.{Db, DbError, Jsonb}
import dms.documents.metadata.MetadataValue
import dms.documents.core.{DocumentDetails, DocumentError}
import dms.documents.{DocumentKind, DocumentVersion, Versioning}
import dms.identity.{Principal, TenantOrganizations, UserDirectory}
import dms.kernel.{DocumentId, UserId}
import dms.rendering.ArchiveStandard
import dms.rendering.core.{PdfJob, PdfRenderer, RenderingError, RenditionFacts}
import dms.templates.core.{HtmlOptions, HtmlRenderer, ProfileSource, TemplateService}
import dms.templates.{Language, ResolvedContent}
import dms.workflow.Approval
import sttp.model.{Header, MediaType, StatusCode}
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.{HexFormat, UUID}

/** The rendered content of a version for the document page (DMS-112). */
final case class VersionHtmlView(
    versionId: UUID,
    /** `1.1-draft.3` while open, `1.1` from approval on. */
    version: String,
    status: String,
    /** "Draft – not valid" (in the document language) for versions that were never approved; shown as banner
      * and watermark in the HTML. Empty for approved versions, which are rendered from their frozen package.
      */
    draftMarking: Option[String],
    /** Language of the document, e.g. `de`. */
    language: String,
    /** HTML fragment: one `article.dms-document`, styled by the shared document stylesheet (`document.css`).
      * All text is escaped; no scripts, ids, inline styles or external references.
      */
    html: String
) derives JsonCodec,
      Schema

/** Reading and printing any version on demand (DMS-112, ADR 0039).
  *
  *   - `GET …/versions/{versionId}/html`: the content of the version resolved with its stored values, as HTML
  *     fragment. Versions that were never approved are rendered like the live preview with the draft marking;
  *     approved versions from their frozen version package without marking.
  *   - `GET …/versions/{versionId}/draft.pdf?changelog=`: a PDF of an open version (draft, in review, in
  *     approval) printed by the rendering worker on demand, marked "Draft – not valid" in the header and as
  *     watermark on every page. Not validated, not stored, not archived; every download is audited
  *     (`document.draft-pdf-downloaded`, with the SHA-256 of the bytes handed out).
  *   - `GET …/versions/{versionId}/copy.pdf?changelog=`: a copy of an approved version with the header and
  *     footer of its archive rendition and the note that the archived rendition is authoritative. Meant for
  *     the change history appendix: the archived PDF/A is never altered. Audited as
  *     `document.copy-pdf-downloaded`.
  *   - `GET …/versions/{versionId}/document.docx?changelog=&attachments=`: a structured version as Word
  *     document, converted by the conversion worker from the same page (DMS-115). Audited as
  *     `document.docx-downloaded`.
  *
  * `changelog=true` appends the change history: the versions up to the printed one the caller may read, with
  * change summary, status dates and approver; `attachments=true` the list of attachments (name, size,
  * SHA-256, DMS-115). For file documents (DMS-115) the PDFs are the converted original (draft) or the
  * archived rendition (copy), marked on every page, with the appendices printed by the rendering worker and
  * appended. Everything requires `ReadDocument` on the version.
  */
object VersionViewApi:
  type Env = DocumentApi.Env & TemplateService & ProfileSource & PdfRenderer & AuditTrail &
    TenantOrganizations & UserDirectory & FileVersions

  /** Audit action of a downloaded draft PDF. */
  val DraftPdfAction: String = "document.draft-pdf-downloaded"

  /** Audit action of a downloaded copy of an approved version. */
  val CopyPdfAction: String = "document.copy-pdf-downloaded"

  /** Audit action of a Word export of a structured version (DMS-115). */
  val DocxAction: String = "document.docx-downloaded"

  private val version =
    "api" / "v1" / "documents" / path[UUID]("id").description("Document id") / "versions" /
      path[UUID]("versionId").description("Version id")

  private val changelog =
    query[Option[Boolean]]("changelog").description("Append the change history (default false)")

  private val attachmentList =
    query[Option[Boolean]]("attachments").description(
      "Append the list of attachments with name, size and SHA-256 (default false)"
    )

  val html: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(version / "html")
      .out(jsonBody[VersionHtmlView])
      .description(
        "The content of a version resolved with its stored values, as HTML fragment for reading on screen. " +
          "Versions that were never approved carry the draft marking; approved versions are rendered from their " +
          "frozen content and values. 409 for file documents (see …/preview.pdf). Requires ReadDocument on the version."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, versionId) =>
          for
            found <- readable(p, id, versionId)
            (details, v) = found
            _ <- ZIO.when(details.document.kind == DocumentKind.File)(
              ZIO.fail(
                (
                  StatusCode.Conflict,
                  ApiError("conflict", s"document $id is a file document; read it as PDF (…/preview.pdf)")
                )
              )
            )
            rendering <- render(p, details, v)
          yield VersionHtmlView(
            v.id,
            v.label,
            v.status.toString,
            rendering.options.draftMarking,
            rendering.language.code,
            rendering.fragment
          )
      )

  private def pdfEndpoint(file: String) =
    SecuredApi.base.get
      .in(version / file)
      .in(changelog)
      .in(attachmentList)
      .out(header(Header.contentType(MediaType.ApplicationPdf)))
      .out(header[String]("Content-Disposition"))
      .out(header("Cache-Control", "private, no-store"))
      .out(byteArrayBody)
      .tag("Documents")

  val draftPdf: ZServerEndpoint[Env, Any] =
    pdfEndpoint("draft.pdf")
      .description(
        "A PDF of an open version (draft, in review, in approval), printed on demand from its saved state and " +
          "marked \"Draft – not valid\" in the header and as watermark on every page. Not archived and not " +
          "stored; the download is audited. 409 for approved versions (download their rendition). " +
          "Requires ReadDocument on the version."
      )
      .serverLogic(p =>
        (id, versionId, changelog, attachments) =>
          pdf(p, id, versionId, changelog.getOrElse(false), attachments.getOrElse(false), draft = true)
      )

  val copyPdf: ZServerEndpoint[Env, Any] =
    pdfEndpoint("copy.pdf")
      .description(
        "A copy of an approved version printed on demand, e.g. with the change history appended; header and " +
          "footer as in the archive rendition plus the note that the archived rendition is authoritative. The " +
          "archived PDF/A is not altered. Not stored; the download is audited. 409 for versions that were never " +
          "approved. Requires ReadDocument on the version."
      )
      .serverLogic(p =>
        (id, versionId, changelog, attachments) =>
          pdf(p, id, versionId, changelog.getOrElse(false), attachments.getOrElse(false), draft = false)
      )

  /** Media type of Word documents (Office Open XML). */
  val DocxMediaType: String = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

  val docx: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(version / "document.docx")
      .in(changelog)
      .in(attachmentList)
      .out(header[String]("Content-Type"))
      .out(header[String]("Content-Disposition"))
      .out(header("Cache-Control", "private, no-store"))
      .out(byteArrayBody)
      .description(
        "A structured version as Word document (DOCX), converted on demand by the conversion worker from the " +
          "same page as the PDFs: open versions are marked \"Draft – not valid\", approved versions name the " +
          "archived rendition as authoritative. Not stored; the download is audited. 409 for file documents " +
          "(download the original) and for withdrawn drafts, 503 without conversion worker. Requires " +
          "ReadDocument on the version."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, versionId, changelog, attachments) =>
          docxExport(p, id, versionId, changelog.getOrElse(false), attachments.getOrElse(false))
            .map((disposition, bytes) => (DocxMediaType, disposition, bytes))
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(html, draftPdf, copyPdf, docx)

  /** The document and the version, if the caller may read it. */
  private def readable(
      p: Principal,
      id: UUID,
      versionId: UUID
  ): ZIO[Env, (StatusCode, ApiError), (DocumentDetails, DocumentVersion)] =
    DocumentApi.inTransaction(p) { ctx =>
      for
        details <- ctx.service.get(DocumentId(id))
        v       <- find(details, versionId)
        _       <- ctx.checkRead(details, v)
      yield (details, v)
    }

  private def find(details: DocumentDetails, versionId: UUID): IO[DocumentError, DocumentVersion] =
    ZIO
      .fromOption(details.versions.find(_.id == versionId))
      .orElseFail(DocumentError.NotFound(s"document ${details.document.id.value} has no version $versionId"))

  private def render(
      p: Principal,
      details: DocumentDetails,
      v: DocumentVersion
  ): ZIO[Env, (StatusCode, ApiError), DocumentRenditions.VersionRendering] =
    (for
      db        <- ZIO.service[Db]
      templates <- ZIO.service[TemplateService]
      profiles  <- ZIO.service[ProfileSource]
      rendering <- db.transact(p.tenantId)(DocumentRenditions.view(templates, profiles, details, v))
    yield rendering).mapError {
      case e: RenderingError => toResponse(e)
      case e: DbError        => DocumentApi.toResponse(e)
    }

  private final case class Printable(
      details: DocumentDetails,
      version: DocumentVersion,
      history: Option[List[DocumentVersion]],
      approvals: Map[UUID, Approval]
  ):
    def file: Boolean = details.document.kind == DocumentKind.File

  /** The document, the version (if the caller may read it and `admissible` accepts it), the readable versions
    * up to it for the change history and the workflow approvals.
    */
  private def printable(
      p: Principal,
      id: UUID,
      versionId: UUID,
      withChangelog: Boolean
  )(admissible: (DocumentDetails, DocumentVersion) => IO[DocumentError, Unit]) =
    DocumentApi.inTransaction(p) { ctx =>
      for
        details <- ctx.service.get(DocumentId(id))
        v       <- find(details, versionId)
        _       <- ctx.checkRead(details, v)
        _       <- admissible(details, v)
        visible <- if withChangelog then ctx.readable(details).map(Some(_)) else ZIO.none
        cases   <- ctx.workflow.cases(details.document.id)
        position = details.versions.map(_.id).zipWithIndex.toMap
        upTo     = position.getOrElse(v.id, Int.MaxValue)
      yield Printable(
        details,
        v,
        visible.map(_.filter(r => position.getOrElse(r.id, Int.MaxValue) <= upTo)),
        cases.flatMap(_.approval).map(a => a.versionId -> a).toMap
      )
    }

  /** Header facts, change history and list of attachments of a printed version. */
  private def printed(
      p: Principal,
      found: Printable,
      language: Language,
      withAttachments: Boolean
  ): URIO[Env, (PrintFacts, Option[List[ChangelogEntry]], Option[List[AttachmentEntry]])] =
    val people = (
      Set(p.userId) ++ found.history.toList.flatten.flatMap(h =>
        Set(h.createdBy) ++ h.releasedBy ++ found.approvals
          .get(h.id)
          .toList
          .flatMap(a => a.approvedBy :: a.actingFor.toList)
      ) ++ found.approvals.get(found.version.id).toList.flatMap(a => a.approvedBy :: a.actingFor.toList) ++
        found.version.releasedBy
    )
    for
      names <- displayNames(p, people)
      now   <- Clock.instant
    yield (
      printFacts(found, language, p.userId, names, now),
      found.history.map(changelogOf(found, names)),
      Option.when(withAttachments)(
        found.version.attachments.map(a => AttachmentEntry(a.name, a.size, a.sha256))
      )
    )

  private def pdf(
      p: Principal,
      id: UUID,
      versionId: UUID,
      withChangelog: Boolean,
      withAttachments: Boolean,
      draft: Boolean
  ): ZIO[Env, (StatusCode, ApiError), (String, Array[Byte])] =
    for
      found <- printable(p, id, versionId, withChangelog) { (_, v) =>
        if draft then
          ZIO
            .unless(Versioning.isOpen(v.status))(
              ZIO.fail(
                DocumentError.Conflict(
                  s"version ${v.label} is ${v.status}, not open; " +
                    (if DocumentRenditions.drafted(v) then "it cannot be printed"
                     else "download its archived rendition or a copy")
                )
              )
            )
            .unit
        else
          ZIO
            .when(DocumentRenditions.drafted(v))(
              ZIO.fail(DocumentError.Conflict(s"version ${v.label} was never approved; print it as draft"))
            )
            .unit
      }
      rendering <- if found.file then ZIO.none else render(p, found.details, found.version).asSome
      language = rendering.fold(DocumentRenditions.language(found.version))(_.language)
      extras <- printed(p, found, language, withAttachments)
      (facts, history, attachments) = extras
      bytes <- rendering match
        case Some(r) =>
          for
            page <- ZIO
              .fromEither(VersionPrintLayout.compose(r.page, facts, history, attachments))
              .mapError(m => toResponse(RenderingError.Invalid(m)))
            rendered <- ZIO
              .serviceWithZIO[PdfRenderer](_.render(job(page, found, facts)))
              .mapError(toResponse)
          yield rendered.bytes.toArray
        case None => filePdf(p, found, facts, language, history, attachments, draft)
      _ <- audit(
        p,
        found.version,
        if draft then DraftPdfAction else CopyPdfAction,
        withChangelog,
        withAttachments,
        bytes
      )
      suffix = if draft then "draft" else "copy"
    yield (s"""attachment; filename="$id-${found.version.label}-$suffix.pdf"""", bytes)

  private def job(page: String, found: Printable, facts: PrintFacts): PdfJob =
    PdfJob(
      page,
      ArchiveStandard.default,
      identifier(found, facts.generatedAt),
      Map("documentId" -> found.details.document.id.value.toString)
    )

  /** The PDF of a file document version (DMS-115): its original converted (draft) or its archived rendition
    * (copy), marked on every page, followed by the appendices printed by the rendering worker.
    */
  private def filePdf(
      p: Principal,
      found: Printable,
      facts: PrintFacts,
      language: Language,
      history: Option[List[ChangelogEntry]],
      attachments: Option[List[AttachmentEntry]],
      draft: Boolean
  ): ZIO[Env, (StatusCode, ApiError), Array[Byte]] =
    val failed = (m: String) => toResponse(RenderingError.Invalid(m))
    val lines  = List(VersionPrintLayout.marking(facts), VersionPrintLayout.identification(facts)) ++
      VersionPrintLayout.footer(facts).split('\n').toList
    for
      base <- FileDocumentApi.pdfOf(
        p,
        found.details,
        found.version,
        found.approvals.get(found.version.id),
        archived = !draft
      )
      stamped <- ZIO.fromEither(PdfComposer.stamp(base.toArray, lines, emphasize = draft)).mapError(failed)
      result  <-
        if history.isEmpty && attachments.isEmpty then ZIO.succeed(stamped)
        else
          val empty = HtmlRenderer.renderDocument(
            ResolvedContent(Nil),
            HtmlOptions(
              language,
              draftMarking = Option.when(draft)(HtmlOptions.draftLabel(language)),
              title = Some(facts.title)
            )
          )
          for
            page <- ZIO
              .fromEither(VersionPrintLayout.compose(empty, facts, history, attachments, startsPage = true))
              .mapError(failed)
            appendix <- ZIO
              .serviceWithZIO[PdfRenderer](_.render(job(page, found, facts)))
              .mapError(toResponse)
            merged <- ZIO.fromEither(PdfComposer.append(stamped, appendix.bytes.toArray)).mapError(failed)
          yield merged
    yield result

  /** A structured version as Word document (DMS-115): the page of the version with the marking at the top and
    * the appendices, converted by the conversion worker.
    */
  private def docxExport(
      p: Principal,
      id: UUID,
      versionId: UUID,
      withChangelog: Boolean,
      withAttachments: Boolean
  ): ZIO[Env, (StatusCode, ApiError), (String, Array[Byte])] =
    for
      found <- printable(p, id, versionId, withChangelog) { (details, v) =>
        ZIO
          .when(details.document.kind == DocumentKind.File)(
            ZIO.fail(DocumentError.Conflict(s"document $id is a file document; download its original"))
          )
          .unit *>
          ZIO
            .when(!Versioning.isOpen(v.status) && DocumentRenditions.drafted(v))(
              ZIO.fail(DocumentError.Conflict(s"version ${v.label} is ${v.status} and was never approved"))
            )
            .unit
      }
      rendering <- render(p, found.details, found.version)
      extras    <- printed(p, found, rendering.language, withAttachments)
      (facts, history, attachments) = extras
      page <- ZIO
        .fromEither(VersionPrintLayout.compose(rendering.page, facts, history, attachments))
        .map(VersionPrintLayout.wordPage(_, facts))
        .mapError(m => toResponse(RenderingError.Invalid(m)))
      bytes <- ZIO
        .serviceWithZIO[FileVersions](_.docx(page, s"$id-${found.version.label}.html"))
        .mapError(toResponse)
        .map(_.toArray)
      _ <- audit(p, found.version, DocxAction, withChangelog, withAttachments, bytes)
    yield (s"""attachment; filename="$id-${found.version.label}.docx"""", bytes)

  private def printFacts(
      found: Printable,
      language: Language,
      caller: UserId,
      names: Map[UserId, String],
      now: java.time.Instant
  ): PrintFacts =
    val v    = found.version
    val name = (u: UserId) => names.getOrElse(u, u.value)
    val copy = Option.unless(DocumentRenditions.drafted(v)) {
      val approval = found.approvals.get(v.id)
      PrintFacts.Copy(
        RenditionFacts(
          documentId = found.details.document.id.value.toString,
          title = DocumentRenditions.title(found.details, v),
          version = v.label,
          validFrom = v.validFrom,
          approvedAt = approval.map(_.approvedAt).orElse(v.releasedAt).getOrElse(now),
          approvedBy = approval.map(_.approvedBy).orElse(v.releasedBy).map(name).getOrElse("–"),
          language = language.code,
          onBehalfOf = approval.flatMap(_.actingFor).map(name)
        ),
        v.rendition.map(_.sha256)
      )
    }
    PrintFacts(
      documentId = found.details.document.id.value.toString,
      documentNumber = v.metadata.get("documentNumber").collect { case MetadataValue.Text(n) => n },
      title = DocumentRenditions.title(found.details, v),
      version = v.label,
      language = language.code,
      generatedAt = now,
      generatedBy = name(caller),
      zone = found.details.zone,
      copyOf = copy
    )

  private def changelogOf(found: Printable, names: Map[UserId, String])(
      versions: List[DocumentVersion]
  ): List[ChangelogEntry] =
    val name = (u: UserId) => names.getOrElse(u, u.value)
    versions.map { h =>
      val approval = found.approvals.get(h.id)
      ChangelogEntry(
        version = h.label,
        changeKind = h.changeKind.toString,
        changeReason = h.changeReason,
        status = h.status.toString,
        createdAt = h.createdAt,
        createdBy = name(h.createdBy),
        approvedAt = h.releasedAt.flatMap(r => approval.map(_.approvedAt).orElse(Some(r))),
        approvedBy = approval.map(_.approvedBy).orElse(h.releasedBy).map(name),
        actingFor = approval.flatMap(_.actingFor).map(name),
        validFrom = h.validFrom,
        publishedAt = h.publishedAt
      )
    }

  /** Display names from the user directory (DMS-110); ids stay as they are when it cannot be asked. */
  private[app] def displayNames(
      p: Principal,
      ids: Set[UserId]
  ): URIO[TenantOrganizations & UserDirectory, Map[UserId, String]] = displayNames(p.tenantId, ids)

  private[app] def displayNames(
      tenant: dms.kernel.TenantId,
      ids: Set[UserId]
  ): URIO[TenantOrganizations & UserDirectory, Map[UserId, String]] =
    (for
      organization <- ZIO.serviceWithZIO[TenantOrganizations](_.organizationOf(tenant))
      found        <- organization.fold(ZIO.succeed(Map.empty))(org =>
        ZIO.serviceWithZIO[UserDirectory](_.lookup(org, ids))
      )
    yield found.map((id, info) => id -> info.displayName))
      .catchAll(e => ZIO.logWarning(s"User names for a printed version not resolved: $e").as(Map.empty))

  /** A PDF file identifier per download (32 hex characters). */
  private def identifier(found: Printable, now: java.time.Instant): String =
    val digest = MessageDigest
      .getInstance("SHA-256")
      .digest(s"${found.version.id}:${found.version.label}:$now".getBytes(StandardCharsets.UTF_8))
    HexFormat.of().formatHex(digest).take(32)

  private def audit(
      p: Principal,
      v: DocumentVersion,
      action: String,
      withChangelog: Boolean,
      withAttachments: Boolean,
      bytes: Array[Byte]
  ): ZIO[Env, (StatusCode, ApiError), Unit] =
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
                      "versionId"   -> Json.Str(v.id.toString),
                      "status"      -> Json.Str(v.status.toString),
                      "changelog"   -> Json.Bool(withChangelog),
                      "attachments" -> Json.Bool(withAttachments),
                      "sha256"      -> Json.Str(sha256),
                      "bytes"       -> Json.Num(bytes.length)
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

  def toResponse(error: RenderingError): (StatusCode, ApiError) = error match
    case RenderingError.Unavailable(m) =>
      (
        StatusCode.ServiceUnavailable,
        ApiError("rendering_unavailable", s"The PDF cannot be produced right now: $m")
      )
    case e @ (RenderingError.Invalid(_) | RenderingError.Rejected(_)) =>
      (StatusCode.UnprocessableEntity, ApiError("rendering_failed", e.describe))
    case RenderingError.Database(e) => DocumentApi.toResponse(e)
    case e => (StatusCode.InternalServerError, ApiError("rendering_failed", e.describe))
