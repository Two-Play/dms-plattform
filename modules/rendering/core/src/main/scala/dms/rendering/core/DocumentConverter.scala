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

  /** What the first bytes of a file must look like to be what its extension says (DMS-121). */
  private enum Signature(val describe: String):
    case Zip      extends Signature("a ZIP package (Office Open XML or OpenDocument)")
    case Ole      extends Signature("a Microsoft Office binary file (OLE compound document)")
    case OleOrRtf extends Signature("a Microsoft Office binary file (OLE compound document) or RTF")
    case Pdf      extends Signature("a PDF file")
    case Rtf      extends Signature("an RTF file")
    case Text     extends Signature("a text file")

  private val signatures: Map[String, Signature] =
    List("docx", "docm", "dotx", "dotm", "xlsx", "xlsm", "xltx", "pptx", "ppsx", "potx", "vsdx")
      .map(_ -> Signature.Zip)
      .toMap ++
      List("odt", "ott", "ods", "ots", "odp", "otp", "odg", "otg").map(_ -> Signature.Zip) ++
      List("dot", "xls", "xlt", "ppt", "pps", "pot", "vsd").map(_ -> Signature.Ole) ++
      // Word can save RTF content under .doc; such files are common in older stocks.
      Map("doc" -> Signature.OleOrRtf, "pdf" -> Signature.Pdf, "rtf" -> Signature.Rtf) ++
      List("txt", "csv", "html", "htm").map(_ -> Signature.Text)

  private val ZipMagic = Array[Byte](0x50, 0x4b, 0x03, 0x04)
  private val OleMagic = Array(0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1).map(_.toByte)
  private val RtfMagic = "{\\rtf".getBytes(StandardCharsets.US_ASCII)
  private val PdfMagic = "%PDF-".getBytes(StandardCharsets.US_ASCII)
  private val Utf16Le  = Array(0xff, 0xfe).map(_.toByte)
  private val Utf16Be  = Array(0xfe, 0xff).map(_.toByte)

  /** Whether the first bytes of a file (at least its first KiB, if it is that long) fit the extension of
    * `name` (DMS-121), checked before an upload is accepted and handed to the import filter the extension
    * selects: ZIP for Office Open XML and OpenDocument, OLE for the binary Office formats, `%PDF-` within the
    * first KiB for PDF, `{\rtf` for RTF; text formats must neither contain NUL bytes (unless they start with
    * a UTF-16 byte order mark) nor start like one of the binary formats. `Left` says what was expected.
    * Extensions that are not convertible are not checked here.
    */
  def contentMatches(name: String, head: Array[Byte]): Either[String, Unit] =
    val dot                        = name.lastIndexOf('.')
    val ext                        = if dot > 0 then name.substring(dot + 1).toLowerCase(Locale.ROOT) else ""
    def starts(magic: Array[Byte]) =
      head.length >= magic.length && head.take(magic.length).sameElements(magic)
    def binary = starts(ZipMagic) || starts(OleMagic) || starts(PdfMagic)
    def pdf    = head.take(1024 + PdfMagic.length).indexOfSlice(PdfMagic.toSeq) >= 0
    def text   = !binary && (starts(Utf16Le) || starts(Utf16Be) || !head.contains(0.toByte))
    signatures.get(ext).fold(Right(())) { expected =>
      val fits = expected match
        case Signature.Zip      => starts(ZipMagic)
        case Signature.Ole      => starts(OleMagic)
        case Signature.OleOrRtf => starts(OleMagic) || starts(RtfMagic)
        case Signature.Pdf      => pdf
        case Signature.Rtf      => starts(RtfMagic)
        case Signature.Text     => text
      Either.cond(
        fits,
        (),
        s"the content of $name does not match its extension .$ext: expected ${expected.describe}"
      )
    }

  /** Whether the worker can convert a file of this name into PDF/A. */
  def convertible(name: String): Boolean =
    val dot = name.lastIndexOf('.')
    dot > 0 && ConvertibleExtensions.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))

/** @param url
  *   base URL of the conversion worker, e.g. `http://localhost:9181`
  * @param previewCacheMegabytes
  *   in-memory budget for converted previews of file versions (DMS-121, ADR 0044); 0 disables the cache
  */
final case class ConverterConfig(url: String, timeoutSeconds: Int = 120, previewCacheMegabytes: Int = 64)

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
