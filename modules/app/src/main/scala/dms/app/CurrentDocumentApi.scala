package dms.app

import dms.authz.Action
import dms.db.{Db, DbError}
import dms.documents.CurrentVersion.Resolution
import dms.documents.metadata.MetadataValue
import dms.documents.{CurrentVersion, DocumentStatus, DocumentVersion, Validity}
import dms.documents.core.{DocumentDetails, DocumentError}
import dms.identity.Principal
import dms.kernel.DocumentId
import dms.rendering.core.{RenderingError, RenditionArchive}
import dms.storage.Sha256
import dms.templates.core.{ProfileSource, TemplateService}
import sttp.model.{Header, MediaType, StatusCode}
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import sttp.tapir.{EndpointOutput, Schema}
import zio.*
import zio.json.*

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.{Instant, LocalDate}
import java.util.{HexFormat, UUID}

import dms.documents.core.MetadataJson.given
import MetadataSchemas.given

/** Links of the current version: the stable addresses, and the rendition of exactly this version. */
final case class CurrentLinks(
    /** Stable address of the metadata (this resource). */
    self: String,
    /** Stable address of the archive rendition (PDF/A) of whichever version is current. */
    pdf: String,
    /** Stable address of the HTML page of whichever version is current. */
    html: String,
    /** The rendition of this version; stays the same when a successor becomes valid. */
    versionRendition: String
) derives JsonCodec,
      Schema

/** The currently valid version of a document (US-14.1, DMS-58). */
final case class CurrentVersionView(
    documentId: UUID,
    versionId: UUID,
    /** Version number, e.g. `2.0`. */
    version: String,
    title: String,
    classPath: List[String],
    /** Published, or ReviewDue (still valid while its periodic review is due). */
    status: String,
    /** Validity scope of the version; empty: unrestricted. */
    scope: String,
    validFrom: Option[LocalDate],
    /** When the version became valid: 00:00 of its valid-from date in the tenant's time zone, or its
      * publication if that was later.
      */
    validSince: Option[Instant],
    /** SHA-256 of the archive rendition (PDF/A). */
    renditionSha256: Option[String],
    /** The metadata of the version as approved. */
    metadata: Map[String, MetadataValue],
    links: CurrentLinks,
    /** The document number (DMS-113); `/api/v1/documents/by-number/{number}/current` resolves it. */
    documentNumber: Option[String] = None
) derives JsonCodec,
      Schema

/** Stable addresses of the currently valid version of a document (US-14.1, DMS-58, ADR 0035): an intranet
  * links them once and always gets the version that is valid now in the requested scope, resolved at request
  * time from PostgreSQL (ADR 0021, 0026), so a scheduled publication switches them at 00:00 of the valid-from
  * date without any cache to invalidate.
  *
  *   - `GET /api/v1/documents/{id}/current` metadata (JSON), `/current.pdf` the archive rendition (PDF/A),
  *     `/current.html` the HTML page; `?scope=` picks the validity scope.
  *   - `ETag` and `Cache-Control: private, no-cache`: clients may keep a copy but revalidate on every use
  *     (`If-None-Match` answers 304), so they never show an outdated version.
  *   - 404 if no version is valid or the caller may not read it (existence is not revealed); 410 if the last
  *     valid version was withdrawn without a successor; 409 if the document has valid versions for several
  *     scopes and none was given.
  *
  * Access needs `ReadDocument` like every read: a technical user (e.g. the intranet's ZITADEL machine user)
  * with the Reader role scoped to the released classes reads exactly those, and confidential documents only
  * as member of an allowed group.
  */
object CurrentDocumentApi:
  type Env = DocumentApi.Env & RenditionArchive & TemplateService & ProfileSource

  private val documentId = path[UUID]("id").description("Document id")
  private val base       = "api" / "v1" / "documents" / documentId

  private val scope =
    query[Option[String]]("scope").description(
      "Validity scope, e.g. DE. Without it: the version without restriction, or the only valid one."
    )

  private val ifNoneMatch =
    header[Option[String]]("If-None-Match")
      .description("ETag of a copy the client holds; 304 if it is current")

  /** Clients may store a copy but must revalidate it before every use. Private: the answer depends on the
    * caller's permissions.
    */
  val CacheControl = "private, no-cache"

  /** The HTML page is self-contained; it may load nothing and run no script. */
  val ContentSecurityPolicy = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'"

  /** 200 with `body`, or 304 without body for `None`. */
  private def conditional[A](body: EndpointOutput[A]): EndpointOutput.OneOf[Option[A], Option[A]] =
    oneOf[Option[A]](
      oneOfVariantValueMatcher(StatusCode.Ok, body.map(a => Option(a))(_.get)) { case Some(_) => true },
      oneOfVariantValueMatcher(StatusCode.NotModified, emptyOutputAs(Option.empty[A])) { case None => true }
    )

  val metadata: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base / "current")
      .in(scope)
      .in(ifNoneMatch)
      .out(header[String]("ETag"))
      .out(header("Cache-Control", CacheControl))
      .out(conditional(jsonBody[CurrentVersionView]))
      .description(
        "Stable address of the version of a document that is valid now (US-14.1): resolved on every request, " +
          "so it switches when a successor becomes valid. 404 if none is valid or the caller may not read it, " +
          "410 if it was withdrawn without successor, 409 if a scope is needed. Revalidate with If-None-Match."
      )
      .tag("Current documents")
      .serverLogic(p =>
        (id, scope, etags) =>
          current(p, id, scope).map { (details, v) =>
            val view = viewOf(details, v)
            val tag  = etag(view.toJson.getBytes(StandardCharsets.UTF_8))
            (tag, Option.unless(matches(etags, tag))(view))
          }
      )

  val pdf: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base / "current.pdf")
      .in(scope)
      .in(ifNoneMatch)
      .out(header[String]("ETag"))
      .out(header("Cache-Control", CacheControl))
      .out(header[String]("Content-Location").description("The rendition of exactly this version"))
      .out(
        conditional(
          header(Header.contentType(MediaType.ApplicationPdf))
            .and(header[String]("Content-Disposition"))
            .and(header[String]("Repr-Digest").description("SHA-256 of the PDF (RFC 9530)"))
            .and(byteArrayBody)
        )
      )
      .description(
        "Stable address of the archive rendition (PDF/A) of the version that is valid now. The ETag is the " +
          "SHA-256 of the PDF; 404 also if the valid version has no rendition."
      )
      .tag("Current documents")
      .serverLogic(p =>
        (id, scope, etags) =>
          for
            found <- current(p, id, scope)
            (details, v) = found
            rendition <- ZIO
              .fromOption(v.rendition)
              .orElseFail(notFound(s"version ${v.label} of document $id has no rendition"))
            tag      = s"\"${rendition.sha256}\""
            location = s"/api/v1/documents/$id/versions/${v.id}/rendition"
            body <-
              if matches(etags, tag) then ZIO.none
              else
                ZIO
                  .serviceWithZIO[RenditionArchive](_.read(rendition))
                  .mapError(RenditionApi.toResponse)
                  .map { bytes =>
                    val digest = Sha256.fromHex(rendition.sha256).fold(_ => "", d => s"sha-256=:${d.base64}:")
                    Some(
                      (
                        s"""inline; filename="${details.document.id.value}-${v.label}.pdf"""",
                        digest,
                        bytes.toArray
                      )
                    )
                  }
          yield (tag, location, body)
      )

  val html: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base / "current.html")
      .in(scope)
      .in(ifNoneMatch)
      .out(header[String]("ETag"))
      .out(header("Cache-Control", CacheControl))
      .out(conditional(header("Content-Security-Policy", ContentSecurityPolicy).and(htmlBodyUtf8)))
      .description(
        "Stable address of the HTML page of the version that is valid now: its content resolved with its field " +
          "values, as printed into the archive rendition. Self-contained (no scripts, no external resources)."
      )
      .tag("Current documents")
      .serverLogic(p =>
        (id, scope, etags) =>
          for
            found <- current(p, id, scope)
            (details, v) = found
            page <- rendered(p, details, v)
            tag = etag(page.getBytes(StandardCharsets.UTF_8))
          yield (tag, Option.unless(matches(etags, tag))(page))
      )

  val byNumber: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "documents" / "by-number" / path[String]("number").description("Document number"))
      .in("current")
      .in(scope)
      .in(ifNoneMatch)
      .out(header[String]("ETag"))
      .out(header("Cache-Control", CacheControl))
      .out(conditional(jsonBody[CurrentVersionView]))
      .description(
        "Stable address by document number (DMS-113): the same as `/documents/{id}/current` for the document " +
          "with this number. 404 if no document has the number or the caller may not read a valid version."
      )
      .tag("Current documents")
      .serverLogic(p =>
        (number, scope, etags) =>
          DocumentApi
            .inTransaction(p)(_.service.findByNumber(number))
            .flatMap(ZIO.fromOption(_).orElseFail(notFound(s"no document with number $number you may read")))
            .flatMap(id =>
              current(p, id.value, scope).mapError {
                case (StatusCode.NotFound, _) =>
                  notFound(s"no document with number $number you may read")
                case other => other
              }
            )
            .map { (details, v) =>
              val view = viewOf(details, v)
              val tag  = etag(view.toJson.getBytes(StandardCharsets.UTF_8))
              (tag, Option.unless(matches(etags, tag))(view))
            }
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(byNumber, metadata, pdf, html)

  /** The current version the caller may read, resolved now. Versions the caller may not read do not count, as
    * if they did not exist; the withdrawn version is reported only to callers who could read it while it was
    * valid.
    */
  private def current(
      p: Principal,
      id: UUID,
      scope: Option[String]
  ): ZIO[Env, (StatusCode, ApiError), (DocumentDetails, DocumentVersion)] =
    DocumentApi
      .inTransaction(p) { ctx =>
        for
          details <- ctx.service.get(DocumentId(id)).mapError {
            case DocumentError.NotFound(_) => DocumentError.NotFound(missing(id))
            case e                         => e
          }
          readable <- ZIO.filter(details.versions)(v =>
            ctx.allowed(
              Action.ReadDocument,
              DocumentApi.resource(p.tenantId, details, v.copy(status = DocumentStatus.Published))
            )
          )
        yield (details, CurrentVersion.resolve(readable.filter(_.publishedAt.isDefined), scope))
      }
      .flatMap {
        case (details, Resolution.Found(v)) => ZIO.succeed((details, v))
        case (_, Resolution.NotFound)       => ZIO.fail(notFound(missing(id)))
        case (_, Resolution.Withdrawn(v))   =>
          ZIO.fail(
            (
              StatusCode.Gone,
              ApiError("withdrawn", s"document $id has no valid version: version ${v.label} was withdrawn")
            )
          )
        case (_, Resolution.Ambiguous(scopes)) =>
          ZIO.fail(
            (
              StatusCode.Conflict,
              ApiError(
                "scope_required",
                s"document $id is valid in several scopes; choose one with ?scope=: ${scopes.mkString(", ")}"
              )
            )
          )
      }

  private def rendered(
      p: Principal,
      details: DocumentDetails,
      v: DocumentVersion
  ): ZIO[Env, (StatusCode, ApiError), String] =
    (for
      db        <- ZIO.service[Db]
      templates <- ZIO.service[TemplateService]
      profiles  <- ZIO.service[ProfileSource]
      page      <- db.transact(p.tenantId)(DocumentRenditions.html(templates, profiles, details, v))
    yield page._1).mapError {
      case e: RenderingError => (StatusCode.InternalServerError, ApiError("rendering_failed", e.describe))
      case e: DbError        => DocumentApi.toResponse(e)
    }

  private def viewOf(details: DocumentDetails, v: DocumentVersion): CurrentVersionView =
    val id    = details.document.id.value
    val query = Option(v.scope)
      .filter(_.nonEmpty)
      .fold("")(s => s"?scope=${URLEncoder.encode(s, StandardCharsets.UTF_8)}")
    val stable = s"/api/v1/documents/$id"
    CurrentVersionView(
      id,
      v.id,
      v.label,
      DocumentRenditions.title(details, v),
      details.profile.classPath.map(_.value),
      v.status.toString,
      v.scope,
      v.validFrom,
      v.publishedAt.map(Validity.effectiveStart(v.validFrom, _, details.zone)),
      v.rendition.map(_.sha256),
      v.metadataSnapshot.fold(v.metadata)(_.values),
      CurrentLinks(
        s"$stable/current$query",
        s"$stable/current.pdf$query",
        s"$stable/current.html$query",
        s"$stable/versions/${v.id}/rendition"
      ),
      details.document.number
    )

  private def missing(id: UUID) = s"document $id has no valid version you may read"

  private def notFound(message: String): (StatusCode, ApiError) =
    (StatusCode.NotFound, ApiError("not_found", message))

  /** A strong ETag from the representation's bytes. */
  private def etag(bytes: Array[Byte]): String =
    "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).take(32) + "\""

  /** Whether an `If-None-Match` header names `tag` (weak comparison, RFC 9110 13.1.2). */
  private[app] def matches(header: Option[String], tag: String): Boolean =
    header.exists(
      _.split(',').map(_.trim.stripPrefix("W/")).exists(t => t == "*" || t == tag)
    )
