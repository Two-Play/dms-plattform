package dms.rendering.core

import dms.db.DbError
import dms.rendering.ArchiveStandard
import zio.*
import zio.json.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration as JDuration

/** Why a rendition could not be produced. */
enum RenderingError:
  /** The rendering worker or the object storage could not be reached or failed unexpectedly. */
  case Unavailable(message: String)

  /** The worker refused the page, e.g. because it references an external resource. */
  case Rejected(message: String)

  /** The PDF does not pass validation against the archive standard. */
  case NotArchivable(report: ValidationReport)

  /** The version cannot be rendered, e.g. it is not waiting for a rendition or its content does not resolve.
    */
  case Invalid(message: String)

  /** A stored rendition does not match its checksum. */
  case Integrity(message: String)

  case Database(error: DbError)

  /** A stable code of the kind of failure, for clients that explain it (DMS-93): `unavailable`, `rejected`,
    * `not_archivable`, `invalid`, `integrity` or `database`.
    */
  def code: String = this match
    case Unavailable(_)   => "unavailable"
    case Rejected(_)      => "rejected"
    case NotArchivable(_) => "not_archivable"
    case Invalid(_)       => "invalid"
    case Integrity(_)     => "integrity"
    case Database(_)      => "database"

  def describe: String = this match
    case Unavailable(m)        => s"rendering unavailable: $m"
    case Rejected(m)           => s"the rendering worker rejected the page: $m"
    case NotArchivable(report) =>
      s"the PDF is not valid ${report.standard.label} (${report.validator}): " +
        report.failures.take(5).mkString("; ")
    case Invalid(m)   => m
    case Integrity(m) => m
    case Database(e)  => s"database error: $e"

/** A page to print as PDF/A.
  *
  * @param html
  *   the complete page: content, stylesheet, page rules and metadata; no external references
  * @param identifier
  *   the PDF file identifier (32 hex characters), stable per version
  * @param xmp
  *   custom XMP properties (`documentId`, `version`, `validFrom`)
  */
final case class PdfJob(html: String, standard: ArchiveStandard, identifier: String, xmp: Map[String, String])

/** Which worker produced a file, for reproducibility (DMS-93, ADR 0055).
  *
  * @param image
  *   the worker's image reference with digest, as configured for the worker (`DMS_WORKER_IMAGE`)
  * @param build
  *   the worker's build fingerprint: SHA-256 over its installed packages and code, computed at start
  */
final case class WorkerIdentity(image: Option[String], build: Option[String])

object WorkerIdentity:
  val unknown: WorkerIdentity = WorkerIdentity(None, None)

  /** From the response headers `X-Worker-Image` and `X-Worker-Build`. */
  def from(response: HttpResponse[?]): WorkerIdentity =
    def header(name: String): Option[String] =
      val value = response.headers().firstValue(name)
      if value.isPresent then Some(value.get).map(_.trim).filter(v => v.nonEmpty && v != "unknown") else None
    WorkerIdentity(header("X-Worker-Image"), header("X-Worker-Build"))

/** @param renderer
  *   versions of the renderer that produced the bytes (worker, WeasyPrint, pydyf)
  */
final case class RenderedPdf(
    bytes: Chunk[Byte],
    pages: Option[Int],
    renderer: String,
    worker: WorkerIdentity = WorkerIdentity.unknown
)

/** Prints HTML as PDF/A (ADR 0008, 0025). */
trait PdfRenderer:
  def render(job: PdfJob): IO[RenderingError, RenderedPdf]

/** @param url
  *   base URL of the rendering worker, e.g. `http://localhost:9180`
  */
final case class RendererConfig(url: String, timeoutSeconds: Int = 60)

/** The rendering worker (`workers/rendering`, WeasyPrint) over HTTP: `POST /render`. */
final class WeasyPrintRenderer(client: HttpClient, config: RendererConfig) extends PdfRenderer:
  import WeasyPrintRenderer.*

  private val endpoint = URI.create(config.url.stripSuffix("/") + "/render")

  def render(job: PdfJob): IO[RenderingError, RenderedPdf] =
    val body    = Request(job.html, job.standard.variant, job.identifier, job.xmp).toJson
    val request = HttpRequest
      .newBuilder(endpoint)
      .timeout(JDuration.ofSeconds(config.timeoutSeconds.toLong))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
      .build()
    ZIO
      .attemptBlockingInterrupt(client.send(request, HttpResponse.BodyHandlers.ofByteArray()))
      .mapError(e => RenderingError.Unavailable(s"${e.getClass.getSimpleName}: ${e.getMessage}"))
      .flatMap { response =>
        val bytes   = Chunk.fromArray(response.body())
        def message = String(response.body(), StandardCharsets.UTF_8)
          .fromJson[ErrorBody]
          .fold(_ => s"HTTP ${response.statusCode()}", _.error)
        response.statusCode() match
          case 200 =>
            ZIO.succeed(
              RenderedPdf(
                bytes,
                header(response, "X-Pages").flatMap(_.toIntOption),
                header(response, "X-Renderer").getOrElse("unknown"),
                WorkerIdentity.from(response)
              )
            )
          case 400 | 422 => ZIO.fail(RenderingError.Rejected(message))
          case _         => ZIO.fail(RenderingError.Unavailable(message))
      }

object WeasyPrintRenderer:
  private final case class Request(
      html: String,
      pdfVariant: String,
      identifier: String,
      xmp: Map[String, String]
  ) derives JsonEncoder

  private final case class ErrorBody(error: String) derives JsonDecoder

  private def header(response: HttpResponse[?], name: String): Option[String] =
    val value = response.headers().firstValue(name)
    if value.isPresent then Some(value.get) else None

  val layer: URLayer[RendererConfig, PdfRenderer] = ZLayer.fromFunction((config: RendererConfig) =>
    WeasyPrintRenderer(HttpClient.newBuilder().connectTimeout(JDuration.ofSeconds(5)).build(), config)
  )
