package dms.documents

import dms.kernel.UserId
import zio.*

import java.nio.file.Path
import java.time.Instant
import java.util.{Locale, UUID}

/** Result of the virus scan of an attachment before it becomes available (DMS-114, ADR 0041). */
enum ScanStatus:
  /** A scanner checked the file and found nothing. */
  case Clean

  /** No scanner is configured (no-op adapter); the file was stored unchecked. */
  case NotScanned

/** An attachment of a document version (DMS-114, ADR 0041): a stored file linked to the version under a name.
  * Links change only while the version is a draft; a new version takes the links over without copying bytes.
  *
  * @param id
  *   the link; identifies the attachment in the API
  * @param fileId
  *   the stored file (one per document and SHA-256)
  * @param basedOn
  *   the link of the previous version this one was taken over from
  */
final case class VersionAttachment(
    id: UUID,
    versionId: UUID,
    fileId: UUID,
    name: String,
    contentType: String,
    size: Long,
    sha256: String,
    objectKey: String,
    objectVersion: String,
    scanStatus: ScanStatus,
    scanner: String,
    addedBy: UserId,
    addedAt: Instant,
    basedOn: Option[UUID]
)

/** What a virus scanner says about a file. */
enum ScanVerdict:
  case Clean
  case NotScanned

  /** The file must not be stored; `signature` names what was found. */
  case Infected(signature: String)

enum ScanError:
  /** The scanner could not be asked (unreachable, timeout); the upload is refused (fail closed). */
  case Unavailable(message: String)

/** Port for a virus scanner (DMS-114, ADR 0041). Called once per upload with the complete file, before the
  * file is written to object storage and linked to a version. Implementations: [[AttachmentScanner.None]]
  * (no-op); a ClamAV adapter would send the file to `clamd` with `INSTREAM` (see ADR 0041).
  */
trait AttachmentScanner:
  /** Recorded with every stored file, e.g. `none` or `clamav`. */
  def name: String

  def scan(file: Path, size: Long): IO[ScanError, ScanVerdict]

object AttachmentScanner:
  /** No scanner configured: every file is stored as [[ScanStatus.NotScanned]]. */
  val None: AttachmentScanner = new AttachmentScanner:
    val name: String                                             = "none"
    def scan(file: Path, size: Long): IO[ScanError, ScanVerdict] = ZIO.succeed(ScanVerdict.NotScanned)

/** Upload rules for attachments (DMS-114): size limit and the blocklist of executable types.
  *
  * @param maxSizeBytes
  *   largest accepted file
  * @param blockedExtensions
  *   lowercase file name extensions without dot that are refused, e.g. `exe`
  */
final case class AttachmentSettings(maxSizeBytes: Long, blockedExtensions: Set[String])

object AttachmentSettings:
  /** Executables, scripts and installers of Windows, macOS, Linux and the JVM. */
  val DefaultBlockedExtensions: Set[String] = Set(
    "exe",
    "com",
    "bat",
    "cmd",
    "scr",
    "pif",
    "msi",
    "msp",
    "msc",
    "dll",
    "cpl",
    "sys",
    "drv",
    "ocx",
    "vbs",
    "vbe",
    "js",
    "jse",
    "wsf",
    "wsh",
    "ws",
    "ps1",
    "psm1",
    "psd1",
    "hta",
    "lnk",
    "reg",
    "inf",
    "scf",
    "jar",
    "class",
    "sh",
    "bash",
    "zsh",
    "csh",
    "ksh",
    "command",
    "app",
    "dmg",
    "pkg",
    "deb",
    "rpm",
    "apk",
    "appimage",
    "run",
    "bin",
    "elf",
    "so",
    "dylib",
    "vb",
    "gadget",
    "application",
    "appref-ms",
    "xbap"
  )

  val default: AttachmentSettings = AttachmentSettings(50L * 1024 * 1024, DefaultBlockedExtensions)

/** Pure checks of attachment names and content (DMS-114). */
object AttachmentRules:

  /** Longest accepted name, in characters. */
  val MaxNameLength: Int = 255

  /** The name to store: the last path segment of what the client sent, trimmed. Left: why it is not
    * acceptable (empty, too long, control characters).
    */
  def normalizeName(raw: String): Either[String, String] =
    val name = raw.split("[/\\\\]").lastOption.getOrElse("").trim
    if name.isEmpty || name == "." || name == ".." then Left("the attachment needs a file name")
    else if name.length > MaxNameLength then Left(s"the file name is longer than $MaxNameLength characters")
    else if name.exists(Character.isISOControl) then Left("the file name contains control characters")
    else Right(name)

  /** The lowercase extension of a name without the dot, if any. */
  def extensionOf(name: String): Option[String] =
    val dot = name.lastIndexOf('.')
    Option.when(dot > 0 && dot < name.length - 1)(name.substring(dot + 1).toLowerCase(Locale.ROOT))

  /** Why a name is blocked: its extension is on the blocklist. A double extension such as `invoice.pdf.exe`
    * counts by its last extension.
    */
  def blockedName(name: String, settings: AttachmentSettings): Option[String] =
    extensionOf(name)
      .filter(settings.blockedExtensions.contains)
      .map(e => s"files of type .$e are not accepted")

  /** Why the first bytes of a file are blocked: they start an executable (Windows PE `MZ`, ELF, Mach-O, Java
    * class), whatever the name says.
    */
  def blockedContent(head: Chunk[Byte]): Option[String] =
    def startsWith(bytes: Int*): Boolean =
      head.length >= bytes.length && bytes.zipWithIndex.forall((b, i) => (head(i) & 0xff) == b)
    if startsWith(0x4d, 0x5a) then Some("Windows executables are not accepted")
    else if startsWith(0x7f, 0x45, 0x4c, 0x46) then Some("ELF executables are not accepted")
    else if startsWith(0xfe, 0xed, 0xfa, 0xce) || startsWith(0xfe, 0xed, 0xfa, 0xcf) ||
      startsWith(0xce, 0xfa, 0xed, 0xfe) || startsWith(0xcf, 0xfa, 0xed, 0xfe)
    then Some("Mach-O executables are not accepted")
    else if startsWith(0xca, 0xfe, 0xba, 0xbe) then
      Some("Java class files and universal binaries are not accepted")
    else None

  /** A storable media type: the declared one if it is a plain `type/subtype`, otherwise the one the JDK
    * associates with the name, otherwise `application/octet-stream`. Parameters are dropped.
    */
  def contentType(declared: Option[String], name: String): String =
    val plain = declared
      .map(_.split(';').head.trim.toLowerCase(Locale.ROOT))
      .filter(_.matches("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*"))
      .filterNot(_ == Octet)
    plain
      .orElse(Option(java.net.URLConnection.guessContentTypeFromName(name)).map(_.toLowerCase(Locale.ROOT)))
      .getOrElse(Octet)

  private val Octet = "application/octet-stream"
