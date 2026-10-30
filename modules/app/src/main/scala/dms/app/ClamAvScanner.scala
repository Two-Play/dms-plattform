package dms.app

import dms.documents.{AttachmentScanner, ScanError, ScanVerdict}
import zio.*

import java.io.{EOFException, IOException, InputStream, OutputStream}
import java.net.{InetSocketAddress, Socket, SocketTimeoutException}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Connection to `clamd` (DMS-117, ADR 0041 addendum).
  *
  * @param host
  *   host of `clamd`'s TCP socket
  * @param port
  *   its port (`TCPSocket`, default 3310)
  * @param timeoutSeconds
  *   connect and read timeout per scan; `clamd` answers after the whole file is scanned
  * @param chunkBytes
  *   size of the `INSTREAM` chunks sent
  */
final case class ClamAvConfig(host: String, port: Int, timeoutSeconds: Int = 60, chunkBytes: Int = 64 * 1024)

/** [[AttachmentScanner]] on `clamd` (ClamAV) over TCP with `INSTREAM` (DMS-117, ADR 0041).
  *
  * Protocol (clamd(8)): send `zINSTREAM\0`, then the file in chunks, each prefixed with its length as 4-byte
  * big-endian unsigned integer, and a zero-length chunk to finish; read the reply up to `\0`:
  *
  *   - `stream: OK` → [[ScanVerdict.Clean]]
  *   - `stream: <signature> FOUND` → [[ScanVerdict.Infected]]
  *   - anything else (`INSTREAM size limit exceeded. ERROR`, other errors), a refused or broken connection or
  *     a timeout → [[ScanError.Unavailable]]: the upload is refused (fail closed, 503).
  *
  * One connection per scan; `clamd` scans connections in parallel (`MaxThreads`). Its `StreamMaxLength` must
  * be at least the attachment limit (`dms.attachments.maxSizeMegabytes`), otherwise larger uploads are
  * refused as unavailable with that reason.
  */
final class ClamAvScanner(config: ClamAvConfig) extends AttachmentScanner:
  import ClamAvScanner.*

  val name: String = "clamav"

  def scan(file: Path, size: Long): IO[ScanError, ScanVerdict] =
    ZIO.scoped {
      for
        socket <- connect
        reply  <- ZIO
          .attemptBlockingInterrupt {
            val out = socket.getOutputStream
            val in  = socket.getInputStream
            // clamd may stop reading and answer early (e.g. size limit exceeded): read its reply anyway.
            val sent =
              try
                out.write(Command)
                val source = Files.newInputStream(file)
                try stream(source, out, config.chunkBytes)
                finally source.close()
                out.write(EndOfStream)
                out.flush()
                None
              catch case e: IOException => Some(e)
            readReply(in) match
              case Some(text)                   => text
              case scala.None if sent.isDefined => throw sent.get
              case scala.None => throw EOFException("clamd closed the connection without a reply")
          }
          .mapError(unavailable)
        verdict <- ZIO.fromEither(verdictOf(reply))
      yield verdict
    }

  /** `PING` → `PONG`: whether `clamd` answers, e.g. for a check at start. */
  def ping: IO[ScanError, Unit] =
    ZIO.scoped {
      for
        socket <- connect
        reply  <- ZIO
          .attemptBlockingInterrupt {
            socket.getOutputStream.write("zPING\u0000".getBytes(StandardCharsets.US_ASCII))
            socket.getOutputStream.flush()
            readReply(socket.getInputStream).getOrElse("")
          }
          .mapError(unavailable)
        _ <- ZIO.unless(reply == "PONG")(ZIO.fail(ScanError.Unavailable(s"clamd answered '$reply' to PING")))
      yield ()
    }

  private def connect: ZIO[Scope, ScanError, Socket] =
    ZIO
      .fromAutoCloseable(ZIO.attemptBlockingInterrupt {
        val socket  = Socket()
        val timeout = config.timeoutSeconds.max(1) * 1000
        try
          socket.connect(InetSocketAddress(config.host, config.port), timeout.min(10000))
          socket.setSoTimeout(timeout)
          socket
        catch
          case e: Throwable =>
            socket.close()
            throw e
      })
      .mapError(unavailable)

  private def unavailable(e: Throwable): ScanError = e match
    case _: SocketTimeoutException =>
      ScanError.Unavailable(
        s"clamd at ${config.host}:${config.port} did not answer within ${config.timeoutSeconds} s"
      )
    case other =>
      ScanError.Unavailable(
        s"clamd at ${config.host}:${config.port} is not reachable: ${other.getClass.getSimpleName}: ${other.getMessage}"
      )

object ClamAvScanner:
  private val Command     = "zINSTREAM\u0000".getBytes(StandardCharsets.US_ASCII)
  private val EndOfStream = Array[Byte](0, 0, 0, 0)

  /** Longest reply read; replies are short (`stream: <signature> FOUND`). */
  private val MaxReply = 4096

  private val Found = "^stream: (.+) FOUND$".r

  /** The verdict for a reply of `clamd` to `INSTREAM`. */
  def verdictOf(reply: String): Either[ScanError, ScanVerdict] =
    reply.trim match
      case "stream: OK"                                   => Right(ScanVerdict.Clean)
      case Found(signature)                               => Right(ScanVerdict.Infected(signature.trim))
      case other if other.contains("size limit exceeded") =>
        Left(
          ScanError.Unavailable(
            "the file is larger than clamd accepts (StreamMaxLength); raise it to the attachment limit"
          )
        )
      case other => Left(ScanError.Unavailable(s"clamd could not scan the file: $other"))

  private def stream(source: InputStream, out: OutputStream, chunkBytes: Int): Unit =
    val buffer = new Array[Byte](chunkBytes.max(1024))
    var n      = source.read(buffer)
    while n >= 0 do
      if n > 0 then
        out.write(ByteBuffer.allocate(4).putInt(n).array())
        out.write(buffer, 0, n)
      n = source.read(buffer)

  /** The reply up to `\0` (or the end of the connection); `None` if nothing was read. */
  private def readReply(in: InputStream): Option[String] =
    val reply = new java.io.ByteArrayOutputStream()
    var b     = in.read()
    while b > 0 && reply.size() < MaxReply do
      reply.write(b)
      b = in.read()
    Option.when(reply.size() > 0)(String(reply.toByteArray, StandardCharsets.UTF_8).trim)

  val layer: URLayer[ClamAvConfig, AttachmentScanner] = ZLayer.fromFunction(ClamAvScanner(_))
