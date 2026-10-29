package dms.rendering.core

import dms.rendering.ArchiveStandard
import zio.*
import zio.json.*

import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets
import java.time.Duration as JDuration
import java.util.Locale

/** What the conversion worker produces (DMS-115, ADR 0042). */
enum ConversionTarget(val code: String):
  /** PDF/A-2b, the archive standard of renditions (ADR 0025). */
  case PdfA2b extends ConversionTarget("pdf/a-2b")

  /** Office Open XML text document (Word). */
  case Docx extends ConversionTarget("docx")

object ConversionTarget:
  /** The PDF target for an archive standard. */
  def pdf(standard: ArchiveStandard): ConversionTarget = standard match
    case ArchiveStandard.PdfA2b => PdfA2b

/** A converted file.
  *
  * @param converter
  *   versions of the converter that produced the bytes (worker, LibreOffice)
  */
final case class ConvertedFile(bytes: Chunk[Byte], converter: String)

/** Converts office files into PDF/A and HTML pages into Word documents (DMS-115, ADR 0042): the conversion
  * worker (`workers/conversion`, LibreOffice) over HTTP.
  */
trait DocumentConverter:
  /** Converts `bytes` named `name` (the extension selects the import filter) into `target`. */
  def convert(bytes: Chunk[Byte], name: String, target: ConversionTarget): IO[RenderingError, ConvertedFile]

object DocumentConverter:

  /** No converter configured: every conversion fails as unavailable. */
  val unavailable: DocumentConverter = (_, _, _) =>
    ZIO.fail(RenderingError.Unavailable("no conversion worker is configured"))

  /** Whether a file is a PDF by its first bytes. */
  def isPdf(bytes: Chunk[Byte]): Boolean =
    bytes.length >= 5 && String(bytes.take(5).toArray, StandardCharsets.US_ASCII) == "%PDF-"

  /** File name extensions the conversion worker accepts (LibreOffice import filters it is tested with). */
  val ConvertibleExtensions: Set[String] = Set(
    "doc",
    "docx",
    "docm",
    "dot",
    "dotx",
    "dotm",
    "odt",
    "ott",
    "rtf",
    "txt",
    "html",
    "htm",
    "xls",
    "xlsx",
    "xlsm",
    "xlt",
    "xltx",
    "ods",
    "ots",
    "csv",
    "ppt",
    "pptx",
    "pps",
    "ppsx",
    "pot",
    "potx",
    "odp",
    "otp",
    "odg",
    "otg",
    "vsd",
    "vsdx",
    "pdf"
  )

  /** Whether the worker can convert a file of this name into PDF/A. */
  def convertible(name: String): Boolean =
    val dot = name.lastIndexOf('.')
    dot > 0 && ConvertibleExtensions.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))

/** @param url
  *   base URL of the conversion worker, e.g. `http://localhost:9181`
  */
final case class ConverterConfig(url: String, timeoutSeconds: Int = 120)

/** The conversion worker (`workers/conversion`) over HTTP: `POST /convert?target=…&name=…` with the file as
  * body.
  */
final class LibreOfficeConverter(client: HttpClient, config: ConverterConfig) extends DocumentConverter:
  import LibreOfficeConverter.*

  def convert(bytes: Chunk[Byte], name: String, target: ConversionTarget): IO[RenderingError, ConvertedFile] =
    val query =
      s"target=${encode(target.code)}&name=${encode(name)}"
    val request = HttpRequest
      .newBuilder(URI.create(s"${config.url.stripSuffix("/")}/convert?$query"))
      .timeout(JDuration.ofSeconds(config.timeoutSeconds.toLong))
      .header("Content-Type", "application/octet-stream")
      .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toArray))
      .build()
    ZIO
      .attemptBlockingInterrupt(client.send(request, HttpResponse.BodyHandlers.ofByteArray()))
      .mapError(e =>
        RenderingError.Unavailable(s"conversion worker: ${e.getClass.getSimpleName}: ${e.getMessage}")
      )
      .flatMap { response =>
        def message = String(response.body(), StandardCharsets.UTF_8)
          .fromJson[ErrorBody]
          .fold(_ => s"HTTP ${response.statusCode()}", _.error)
        response.statusCode() match
          case 200 =>
            val converter = response.headers().firstValue("X-Converter")
            ZIO.succeed(
              ConvertedFile(
                Chunk.fromArray(response.body()),
                if converter.isPresent then converter.get else "unknown"
              )
            )
          case 400 | 413 | 415 | 422 => ZIO.fail(RenderingError.Rejected(message))
          case _                     => ZIO.fail(RenderingError.Unavailable(s"conversion worker: $message"))
      }

object LibreOfficeConverter:
  private final case class ErrorBody(error: String) derives JsonDecoder

  private def encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

  val layer: URLayer[ConverterConfig, DocumentConverter] = ZLayer.fromFunction((config: ConverterConfig) =>
    LibreOfficeConverter(HttpClient.newBuilder().connectTimeout(JDuration.ofSeconds(5)).build(), config)
  )
