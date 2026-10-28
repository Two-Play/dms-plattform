package dms.app

import dms.documents.core.DocumentError
import dms.kernel.DocumentId
import dms.rendering.core.{RenderingError, RenditionArchive}
import dms.storage.Sha256
import sttp.model.{Header, MediaType, StatusCode}
import sttp.tapir.ztapir.*
import zio.*

import java.util.UUID

/** Download of the archive rendition of a version (DMS-44): the PDF/A with its SHA-256. Requires
  * `ReadDocument` on the version, like reading the version itself. The bytes are checked against the stored
  * SHA-256 before they are sent.
  */
object RenditionApi:
  type Env = DocumentApi.Env & RenditionArchive

  val download: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(
        "api" / "v1" / "documents" / path[UUID]("id").description("Document id") / "versions" /
          path[UUID]("versionId").description("Version id") / "rendition"
      )
      .out(header(Header.contentType(MediaType.ApplicationPdf)))
      .out(header[String]("Content-Disposition"))
      .out(header[String]("ETag"))
      .out(header[String]("Repr-Digest").description("SHA-256 of the PDF (RFC 9530)"))
      .out(byteArrayBody)
      .description(
        "The archive rendition (PDF/A) of a version, produced on approval. 404 if the version has none; the SHA-256 " +
          "is sent as Repr-Digest and ETag."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, versionId) =>
          for
            found <- DocumentApi.inTransaction(p) { ctx =>
              for
                details <- ctx.service.get(DocumentId(id))
                version <- ZIO
                  .fromOption(details.versions.find(_.id == versionId))
                  .orElseFail(DocumentError.NotFound(s"document $id has no version $versionId"))
                _         <- ctx.checkRead(details, version)
                rendition <- ZIO
                  .fromOption(version.rendition)
                  .orElseFail(DocumentError.NotFound(s"version ${version.label} has no rendition"))
              yield (version.label, rendition)
            }
            (label, rendition) = found
            bytes <- ZIO.serviceWithZIO[RenditionArchive](_.read(rendition)).mapError(toResponse)
            digest = Sha256.fromHex(rendition.sha256).fold(_ => "", d => s"sha-256=:${d.base64}:")
          yield (
            s"""attachment; filename="$id-$label.pdf"""",
            s"\"${rendition.sha256}\"",
            digest,
            bytes.toArray
          )
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(download)

  def toResponse(error: RenderingError): (StatusCode, ApiError) = error match
    case RenderingError.Unavailable(m) =>
      (
        StatusCode.ServiceUnavailable,
        ApiError("storage_unavailable", s"The rendition cannot be read now: $m")
      )
    case e =>
      (StatusCode.InternalServerError, ApiError("rendition_integrity", e.describe))
