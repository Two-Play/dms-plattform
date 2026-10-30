package dms.app

import zio.*

import java.io.{ByteArrayOutputStream, DataInputStream}
import java.net.{InetAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/** An in-process stand-in for `clamd` speaking `INSTREAM` and `PING` over TCP (DMS-117), for fast specs of
  * the ClamAV adapter and of the upload paths with it.
  *
  * Files containing [[FakeClamd.Marker]] are reported as infected with [[FakeClamd.Signature]]. `mode`
  * switches the behaviour: answer normally, refuse the stream as too large, answer nothing (timeout) or close
  * the connection at once.
  */
final class FakeClamd private (server: ServerSocket, val mode: AtomicReference[FakeClamd.Mode]):
  import FakeClamd.*

  def port: Int = server.getLocalPort

  def close(): Unit = server.close()

  /** The bytes of the last streamed file, reassembled from its chunks. */
  val lastFile: AtomicReference[Array[Byte]] = AtomicReference(Array.emptyByteArray)

  private def serve(): Unit =
    while !server.isClosed do
      try
        val socket = server.accept()
        val thread = Thread(() => handle(socket))
        thread.setDaemon(true)
        thread.start()
      catch case _: java.io.IOException => ()

  private def handle(socket: Socket): Unit =
    try
      val in      = DataInputStream(socket.getInputStream)
      val out     = socket.getOutputStream
      val command = readCommand(in)
      mode.get() match
        case Mode.Close  => ()
        case Mode.Silent =>
          Thread.sleep(5000)
        case Mode.TooLarge if command == "zINSTREAM" =>
          out.write("INSTREAM size limit exceeded. ERROR\u0000".getBytes(StandardCharsets.US_ASCII))
        case _ =>
          command match
            case "zPING"     => out.write("PONG\u0000".getBytes(StandardCharsets.US_ASCII))
            case "zINSTREAM" =>
              val file = ByteArrayOutputStream()
              var size = in.readInt()
              while size > 0 do
                val chunk = new Array[Byte](size)
                in.readFully(chunk)
                file.write(chunk)
                size = in.readInt()
              lastFile.set(file.toByteArray)
              val text  = String(file.toByteArray, StandardCharsets.ISO_8859_1)
              val reply = if text.contains(Marker) then s"stream: $Signature FOUND" else "stream: OK"
              out.write((reply + "\u0000").getBytes(StandardCharsets.US_ASCII))
            case other => out.write(s"UNKNOWN COMMAND $other\u0000".getBytes(StandardCharsets.US_ASCII))
      out.flush()
    catch case _: Exception => ()
    finally socket.close()

  private def readCommand(in: DataInputStream): String =
    val command = ByteArrayOutputStream()
    var b       = in.read()
    while b > 0 do
      command.write(b)
      b = in.read()
    String(command.toByteArray, StandardCharsets.US_ASCII)

object FakeClamd:
  enum Mode:
    case Answer, TooLarge, Silent, Close

  /** Contained in a file to be reported as infected; not a real signature. */
  val Marker: String = "DMS-TEST-MALWARE-MARKER"

  val Signature: String = "Dms-Test-Signature"

  /** A started fake on a free loopback port, stopped with the scope. */
  val make: ZIO[Scope, Throwable, FakeClamd] =
    ZIO.acquireRelease(ZIO.attemptBlocking {
      val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress)
      val fake   = FakeClamd(server, AtomicReference(Mode.Answer))
      val thread = Thread(() => fake.serve())
      thread.setDaemon(true)
      thread.start()
      fake
    })(fake => ZIO.attemptBlocking(fake.close()).orDie)
