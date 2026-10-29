package dms.app

import dms.audit.*
import dms.db.{Db, DbError, Jsonb}
import dms.documents.metadata.MetadataValue
import dms.documents.core.{DocumentDetails, DocumentError}
import dms.documents.{DocumentVersion, Versioning}
import dms.identity.{Principal, TenantOrganizations, UserDirectory}
import dms.kernel.{DocumentId, UserId}
import dms.rendering.ArchiveStandard
import dms.rendering.core.{PdfJob, PdfRenderer, RenderingError, RenditionFacts}
import dms.templates.core.{ProfileSource, TemplateService}
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
  *
  * `changelog=true` appends the change history: the versions up to the printed one the caller may read, with
  * change summary, status dates and approver. Everything requires `ReadDocument` on the version.
  */
object VersionViewApi:
  type Env = DocumentApi.Env & TemplateService & ProfileSource & PdfRenderer & AuditTrail &
    TenantOrganizations & UserDirectory

  /** Audit action of a downloaded draft PDF. */
  val DraftPdfAction: String = "document.draft-pdf-downloaded"

  /** Audit action of a downloaded copy of an approved version. */
  val CopyPdfAction: String = "document.copy-pdf-downloaded"

  private val version =
    "api" / "v1" / "documents" / path[UUID]("id").description("Document id") / "versions" /
      path[UUID]("versionId").description("Version id")

  private val changelog =
    query[Option[Boolean]]("changelog").description("Append the change history (default false)")

  val html: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(version / "html")
      .out(jsonBody[VersionHtmlView])
      .description(
        "The content of a version resolved with its stored values, as HTML fragment for reading on screen. " +
          "Versions that were never approved carry the draft marking; approved versions are rendered from their " +
          "frozen content and values. Requires ReadDocument on the version."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, versionId) =>
          for
            found <- readable(p, id, versionId)
            (details, v) = found
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
        (id, versionId, changelog) => pdf(p, id, versionId, changelog.getOrElse(false), draft = true)
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
        (id, versionId, changelog) => pdf(p, id, versionId, changelog.getOrElse(false), draft = false)
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(html, draftPdf, copyPdf)

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
  )

  private def pdf(
      p: Principal,
      id: UUID,
      versionId: UUID,
      withChangelog: Boolean,
      draft: Boolean
  ): ZIO[Env, (StatusCode, ApiError), (String, Array[Byte])] =
    for
      found <- DocumentApi.inTransaction(p) { ctx =>
        for
          details <- ctx.service.get(DocumentId(id))
          v       <- find(details, versionId)
          _       <- ctx.checkRead(details, v)
          _       <-
            if draft then
              ZIO.unless(Versioning.isOpen(v.status))(
                ZIO.fail(
                  DocumentError.Conflict(
                    s"version ${v.label} is ${v.status}, not open; " +
                      (if DocumentRenditions.drafted(v) then "it cannot be printed"
                       else "download its archived rendition or a copy")
                  )
                )
              )
            else
              ZIO.when(DocumentRenditions.drafted(v))(
                ZIO.fail(
                  DocumentError.Conflict(s"version ${v.label} was never approved; print it as draft")
                )
              )
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
      rendering <- render(p, found.details, found.version)
      people = (
        Set(p.userId) ++ found.history.toList.flatten.flatMap(h =>
          Set(h.createdBy) ++ h.releasedBy ++ found.approvals
            .get(h.id)
            .toList
            .flatMap(a => a.approvedBy :: a.actingFor.toList)
        ) ++ found.approvals.get(found.version.id).toList.flatMap(a => a.approvedBy :: a.actingFor.toList) ++
          found.version.releasedBy
      )
      names <- displayNames(p, people)
      now   <- Clock.instant
      facts = printFacts(found, rendering, p.userId, names, now)
      page <- ZIO
        .fromEither(
          VersionPrintLayout.compose(rendering.page, facts, found.history.map(changelogOf(found, names)))
        )
        .mapError(m => toResponse(RenderingError.Invalid(m)))
      job = PdfJob(page, ArchiveStandard.default, identifier(found, now), Map("documentId" -> id.toString))
      rendered <- ZIO.serviceWithZIO[PdfRenderer](_.render(job)).mapError(toResponse)
      bytes = rendered.bytes.toArray
      _ <- audit(p, found.version, if draft then DraftPdfAction else CopyPdfAction, withChangelog, bytes)
      suffix = if draft then "draft" else "copy"
    yield (s"""attachment; filename="$id-${found.version.label}-$suffix.pdf"""", bytes)

  private def printFacts(
      found: Printable,
      rendering: DocumentRenditions.VersionRendering,
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
          language = rendering.language.code,
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
      language = rendering.language.code,
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
  private def displayNames(p: Principal, ids: Set[UserId]): URIO[Env, Map[UserId, String]] =
    (for
      organization <- ZIO.serviceWithZIO[TenantOrganizations](_.organizationOf(p.tenantId))
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
                      "versionId" -> Json.Str(v.id.toString),
                      "status"    -> Json.Str(v.status.toString),
                      "changelog" -> Json.Bool(withChangelog),
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
