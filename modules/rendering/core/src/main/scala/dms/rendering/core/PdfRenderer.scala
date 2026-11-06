package dms.rendering.core

import dms.db.DbError
import dms.rendering.PdfVariant
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

  /** The original is too large or too complex to fill its fields (DMS-160, ADR 0076). */
  case TooComplex(message: String)

  /** The document is too long for the rendering worker: over its page limit, or more than its memory holds
    * (DMS-171, ADR 0081). Fails the same way on every attempt.
    */
  case TooLong(message: String)

  /** The worker ended the render at its time limit (DMS-171, ADR 0081). */
  case TimeLimit(message: String)

  /** A stored rendition does not match its checksum. */
  case Integrity(message: String)

  /** The rendition exists, but the four-eyes rule refuses the approval it would complete (DMS-163, ADR 0078):
    * the last approver created or changed the version. A retry cannot help.
    */
  case Refused(message: String)

  case Database(error: DbError)

  /** A stable code of the kind of failure, for clients that explain it (DMS-93): `unavailable`, `rejected`,
    * `not_archivable`, `invalid`, `too_complex`, `too_long`, `time_limit`, `integrity`,
    * `segregation_of_duties` or `database`.
    */
  def code: String = this match
    case Unavailable(_)   => "unavailable"
    case Rejected(_)      => "rejected"
    case NotArchivable(_) => "not_archivable"
    case Invalid(_)       => "invalid"
    case TooComplex(_)    => "too_complex"
    case TooLong(_)       => RenderingError.TooLongCode
    case TimeLimit(_)     => RenderingError.TimeLimitCode
    case Integrity(_)     => "integrity"
    case Refused(_)       => RenderingError.SegregationOfDuties
    case Database(_)      => "database"

  def describe: String = this match
    case Unavailable(m)        => s"rendering unavailable: $m"
    case Rejected(m)           => s"the rendering worker rejected the page: $m"
    case NotArchivable(report) =>
      s"the PDF is not valid ${report.standard.label} (${report.validator}): " +
        report.failures.take(5).mkString("; ")
    case Invalid(m)    => m
    case TooComplex(m) => m
    case TooLong(m)    => m
    case TimeLimit(m)  => m
    case Integrity(m)  => m
    case Refused(m)    => m
    case Database(e)   => s"database error: $e"

object RenderingError:
  /** The code of [[Refused]]: the one failure a retry can never cure. */
  val SegregationOfDuties: String = "segregation_of_duties"

  /** The codes of [[TooLong]] and [[TimeLimit]], the same in the answer of the rendering worker. */
  val TooLongCode: String   = "too_long"
  val TimeLimitCode: String = "time_limit"

  /** Codes that end a job on the first failure (ADR 0081): the worker's limits refuse the same page on every
    * attempt. The same list as `dms.job_failure_is_final`, which decides it for the job queue.
    */
  val NoRetry: Set[String] = Set(TooLongCode, TimeLimitCode)

/** A page to print.
  *
  * @param html
  *   the complete page: content, stylesheet, page rules and metadata; no external references
  * @param variant
  *   PDF/A for everything archived, plain for drafts printed on demand
  * @param identifier
  *   the PDF file identifier (32 hex characters), stable per version
  * @param xmp
  *   custom XMP properties (`documentId`, `version`, `validFrom`); a plain PDF has no XMP packet
  */
final case class PdfJob(html: String, variant: PdfVariant, identifier: String, xmp: Map[String, String])

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

/** Prints HTML as PDF (ADR 0008, 0025, 0073). */
trait PdfRenderer:
  def render(job: PdfJob): IO[RenderingError, RenderedPdf]

/** @param url
  *   base URL of the rendering worker, e.g. `http://localhost:9180`
  * @param timeoutSeconds
  *   how long to wait for the worker, a waiting turn included; above the worker's own time limit
  *   (`DMS_RENDER_TIME_LIMIT_SECONDS`, ADR 0081), so that a render at the limit is reported as such
  * @param parallelJobs
  *   rendition jobs at a time per application instance (ADR 0079). A worker prints one page at a time: more
  *   jobs than worker replicas only wait in the worker, and run into `timeoutSeconds` there.
  */
final case class RendererConfig(url: String, timeoutSeconds: Int = 150, parallelJobs: Int = 1)

/** The rendering worker (`workers/rendering`, WeasyPrint) over HTTP: `POST /render`. */
final class WeasyPrintRenderer(client: HttpClient, config: RendererConfig) extends PdfRenderer:
  import WeasyPrintRenderer.*

  private val endpoint = URI.create(config.url.stripSuffix("/") + "/render")

  def render(job: PdfJob): IO[RenderingError, RenderedPdf] =
    val body    = Request(job.html, job.variant.worker, job.identifier, job.xmp).toJson
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
        def message = errorBody(response).fold(s"HTTP ${response.statusCode()}")(_.error)
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
          case 400 | 422 =>
            ZIO.fail(
              errorBody(response).flatMap(_.code) match
                case Some(RenderingError.TooLongCode)   => RenderingError.TooLong(message)
                case Some(RenderingError.TimeLimitCode) => RenderingError.TimeLimit(message)
                case _                                  => RenderingError.Rejected(message)
            )
          case _ => ZIO.fail(RenderingError.Unavailable(message))
      }

object WeasyPrintRenderer:
  private final case class Request(
      html: String,
      pdfVariant: String,
      identifier: String,
      xmp: Map[String, String]
  ) derives JsonEncoder

  /** @param code
    *   for a limit of the worker: `too_long` or `time_limit`
    */
  private final case class ErrorBody(error: String, code: Option[String]) derives JsonDecoder

  private def errorBody(response: HttpResponse[Array[Byte]]): Option[ErrorBody] =
    String(response.body(), StandardCharsets.UTF_8).fromJson[ErrorBody].toOption

  private def header(response: HttpResponse[?], name: String): Option[String] =
    val value = response.headers().firstValue(name)
    if value.isPresent then Some(value.get) else None

  val layer: URLayer[RendererConfig, PdfRenderer] = ZLayer.fromFunction((config: RendererConfig) =>
    WeasyPrintRenderer(HttpClient.newBuilder().connectTimeout(JDuration.ofSeconds(5)).build(), config)
  )
