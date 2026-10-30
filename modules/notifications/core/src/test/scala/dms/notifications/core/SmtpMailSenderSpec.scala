package dms.notifications.core

import dms.notifications.{Language, Mail}
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.util.Base64

/** The SMTP adapter against an in-process SMTP test double ([[FakeSmtpServer]]): envelope, headers
  * (Message-ID stays the same on every attempt, language, auto-generated), UTF-8 subject and text, and
  * failures of the server as typed errors for the retry.
  */
object SmtpMailSenderSpec extends ZIOSpecDefault:

  private def config(port: Int) =
    SmtpConfig("127.0.0.1", port, "none", None, None, "DMS <dms@example.org>", 5000)

  private val mail = Mail(
    "anna@example.org",
    "[DMS] Änderungen erbeten: Reiserichtlinie (2.0)",
    "Hallo Anna,\n\nBen hat Änderungen erbeten.\n\nÖffnen: https://dms.example.org/documents/1",
    "<3f2c@notifications.dms>",
    Language.De
  )

  /** The decoded subject of an RFC 2047 encoded-word header (`=?UTF-8?B?...?=` or `=?UTF-8?Q?...?=`). */
  private def subjectOf(data: String): String =
    val raw = data.linesIterator
      .dropWhile(!_.startsWith("Subject:"))
      .toList match
      case first :: rest => (first.stripPrefix("Subject:") :: rest.takeWhile(_.startsWith(" "))).mkString
      case Nil           => ""
    jakarta.mail.internet.MimeUtility.decodeText(raw.trim)

  private def bodyOf(data: String): String =
    val headers  = data.takeWhile(_ => true).split("\n\n", 2)
    val encoding = headers.head.linesIterator
      .find(_.toLowerCase.startsWith("content-transfer-encoding:"))
      .map(_.split(':')(1).trim.toLowerCase)
    val body = headers.lift(1).getOrElse("")
    encoding match
      case Some("base64")           => String(Base64.getMimeDecoder.decode(body), StandardCharsets.UTF_8)
      case Some("quoted-printable") =>
        String(
          jakarta.mail.internet.MimeUtility
            .decode(
              java.io.ByteArrayInputStream(body.replace("\n", "\r\n").getBytes(StandardCharsets.US_ASCII)),
              "quoted-printable"
            )
            .readAllBytes(),
          StandardCharsets.UTF_8
        )
      case _ => body

  def spec = suite("SmtpMailSender")(
    test("sends a UTF-8 plain-text email with a stable Message-ID and the language") {
      ZIO.scoped(
        for
          server <- ZIO.fromAutoCloseable(ZIO.succeed(FakeSmtpServer()))
          _      <- SmtpMailSender(config(server.port)).send(mail)
          got  = server.messages
          data = got.head.data
        yield assertTrue(
          got.size == 1,
          got.head.from == "<dms@example.org>",
          got.head.to == List("<anna@example.org>"),
          data.contains("Message-ID: <3f2c@notifications.dms>"),
          data.contains("Content-Language: de"),
          data.contains("Auto-Submitted: auto-generated"),
          subjectOf(data) == mail.subject,
          bodyOf(data).replace("\r\n", "\n").contains("Ben hat Änderungen erbeten."),
          bodyOf(data).contains("Öffnen: https://dms.example.org/documents/1")
        )
      )
    },
    test("a refused recipient and an unreachable server are errors to retry") {
      ZIO.scoped(
        for
          server  <- ZIO.fromAutoCloseable(ZIO.succeed(FakeSmtpServer(rejectRecipients = true)))
          refused <- SmtpMailSender(config(server.port)).send(mail).either
          closed  <- ZIO.succeed { val s = java.net.ServerSocket(0); val p = s.getLocalPort; s.close(); p }
          offline <- SmtpMailSender(config(closed)).send(mail).either
        yield assertTrue(
          refused.left.exists(_.startsWith("sending the email failed")),
          offline.left.exists(_.startsWith("sending the email failed")),
          !refused.left.exists(_.contains("Ben hat"))
        )
      )
    },
    test("no SMTP host means email is off") {
      assertTrue(!config(25).copy(host = " ").enabled, config(25).enabled)
    }
  ) @@ TestAspect.withLiveClock
