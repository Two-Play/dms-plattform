package dms.app

import dms.db.Db
import dms.documents.{DocumentStatus, DocumentVersion}
import dms.documents.core.DocumentError
import dms.identity.Principal
import dms.kernel.DocumentId
import dms.templates.{Limits, Section}
import dms.templates.core.ContentJson.given
import dms.templates.core.{Preview, ProfileSource, TemplateCompiler, TemplateService}
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.util.UUID

import TemplateSchemas.given

/** The locked sections of the template of a version, as they stand in the template. */
final case class StandardTextView(sections: List[Section]) derives JsonCodec, Schema

/** What the editor of a draft needs besides the draft itself (DMS-154, ADR 0071).
  *
  *   - `POST …/versions/{versionId}/preview`: the live preview of a draft from its own content, which can
  *     differ from the template once locked text changed, with the values entered so far.
  *   - `GET …/versions/{versionId}/standard-text`: the standard text of the locked sections, so an author can
  *     restore it exactly: only the same blocks end a deviation.
  *
  * Both require `ReadDocument` on the version.
  */
object DraftContentApi:
  type Env = DocumentApi.Env & TemplateService & ProfileSource

  private val version =
    "api" / "v1" / "documents" / path[UUID]("id").description("Document id") / "versions" /
      path[UUID]("versionId").description("Version id")

  val preview: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(version / "preview")
      .in(jsonBody[PreviewRequest])
      .out(jsonBody[PreviewView])
      .description(
        "Live preview while changing a draft: like the preview of a template, but from the saved content of " +
          "the draft, with the values entered so far. Nothing is stored. 409 if the version is not a draft. " +
          "Requires ReadDocument on the version."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, versionId, req) =>
          for
            language <- TemplatePreviewApi.previewLanguage(req)
            v        <- readable(p, id, versionId)
            _        <- ZIO.unless(v.status == DocumentStatus.Draft)(ZIO.fail(notADraft(v)))
            template <- withContentOf(p, v)
            // Resolve and render outside the transaction: it needs no database and must not hold a connection.
            result <- ZIO.attempt(Preview.run(template, req.values, language)).orDie
          yield TemplatePreviewApi.previewView(result)
      )

  val standardText: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(version / "standard-text")
      .out(jsonBody[StandardTextView])
      .description(
        "The locked sections of the template of a version, with the text they have there: what restores a " +
          "section that deviates. Requires ReadDocument on the version."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, versionId) =>
          DocumentApi.inTransaction(p) { ctx =>
            for
              details  <- ctx.service.get(DocumentId(id))
              v        <- find(details.versions, id, versionId)
              _        <- ctx.checkRead(details, v)
              sections <- ctx.service.standardSections(v)
            yield StandardTextView(sections)
          }
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(preview, standardText)

  private def readable(p: Principal, id: UUID, versionId: UUID) =
    DocumentApi.inTransaction(p) { ctx =>
      for
        details <- ctx.service.get(DocumentId(id))
        v       <- find(details.versions, id, versionId)
        _       <- ctx.checkRead(details, v)
      yield v
    }

  private def find(
      versions: List[DocumentVersion],
      id: UUID,
      versionId: UUID
  ): IO[DocumentError, DocumentVersion] =
    ZIO
      .fromOption(versions.find(_.id == versionId))
      .orElseFail(DocumentError.NotFound(s"document $id has no version $versionId"))

  private def notADraft(v: DocumentVersion): (StatusCode, ApiError) =
    (
      StatusCode.Conflict,
      ApiError("conflict", s"version ${v.label} is ${v.status}; only drafts have a live preview")
    )

  /** The template of the draft with the content of the draft in its place. */
  private def withContentOf(p: Principal, v: DocumentVersion) =
    for
      db       <- ZIO.service[Db]
      service  <- ZIO.service[TemplateService]
      template <- db
        .transact(p.tenantId)(service.compiled(v.templateId, v.templateVersion))
        .mapError(TemplateApi.toResponse)
      own <- ZIO
        .fromEither(TemplateCompiler.compile(v.content, template.compiled.schema, Limits.default))
        .mapError(issues =>
          (StatusCode.UnprocessableEntity, ApiError("invalid", issues.map(_.show).mkString("; ")))
        )
    yield template.copy(compiled = own)
end DraftContentApi
