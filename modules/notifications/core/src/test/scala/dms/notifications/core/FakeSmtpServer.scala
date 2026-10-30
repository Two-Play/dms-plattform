package dms.notifications.core

import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import java.net.{InetAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** A minimal SMTP server for tests (no Mailpit needed): accepts connections on a free local port, speaks
  * enough SMTP (RFC 5321: EHLO/HELO, MAIL, RCPT, DATA, RSET, NOOP, QUIT) for Jakarta Mail and keeps every
  * message with its envelope. `rejectRecipients` answers RCPT with 550, like a relay refusing an address.
  */
final class FakeSmtpServer(rejectRecipients: Boolean = false) extends AutoCloseable:
  final case class Received(from: String, to: List[String], data: String)

  private val socket   = ServerSocket(0, 50, InetAddress.getLoopbackAddress)
  private val received = ConcurrentLinkedQueue[Received]()

  val port: Int = socket.getLocalPort

  def messages: List[Received] = received.asScala.toList

  private val acceptor = Thread.ofVirtual().start(() => accept())

  private def accept(): Unit =
    try
      while !socket.isClosed do
        val client = socket.accept()
        val _      = Thread.ofVirtual().start(() => serve(client))
    catch case _: java.io.IOException => ()

  private def serve(client: Socket): Unit =
    try
      val in  = BufferedReader(InputStreamReader(client.getInputStream, StandardCharsets.UTF_8))
      val out = PrintWriter(client.getOutputStream, true, StandardCharsets.UTF_8)
      def reply(line: String): Unit =
        out.print(s"$line\r\n")
        out.flush()
      reply("220 fake.smtp ESMTP ready")
      var from = ""
      var to   = List.empty[String]
      var open = true
      while open do
        val line = Option(in.readLine()).getOrElse("QUIT")
        val verb = line.takeWhile(_ != ' ').toUpperCase
        verb match
          case "EHLO" =>
            reply("250-fake.smtp")
            reply("250-8BITMIME")
            reply("250 SMTPUTF8")
          case "HELO" => reply("250 fake.smtp")
          case "MAIL" =>
            from = line.dropWhile(_ != ':').drop(1).trim
            to = Nil
            reply("250 OK")
          case "RCPT" =>
            if rejectRecipients then reply("550 5.1.1 mailbox unavailable")
            else
              to = to :+ line.dropWhile(_ != ':').drop(1).trim
              reply("250 OK")
          case "DATA" =>
            reply("354 end data with <CR><LF>.<CR><LF>")
            val data = Iterator
              .continually(in.readLine())
              .takeWhile(l => l != null && l != ".")
              .map(l => if l.startsWith("..") then l.drop(1) else l)
              .mkString("\n")
            received.add(Received(from, to, data))
            reply("250 OK queued")
          case "RSET" =>
            from = ""
            to = Nil
            reply("250 OK")
          case "NOOP" => reply("250 OK")
          case "QUIT" =>
            reply("221 bye")
            open = false
          case _ => reply("502 command not implemented")
    catch case _: java.io.IOException => ()
    finally client.close()

  def close(): Unit =
    socket.close()
    acceptor.join(1000)
