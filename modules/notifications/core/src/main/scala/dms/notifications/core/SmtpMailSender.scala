package dms.notifications.core

import dms.notifications.{Mail, MailSender}
import jakarta.mail.internet.{InternetAddress, MimeMessage}
import jakarta.mail.{Authenticator, Message, PasswordAuthentication, Session, Transport}
import zio.*

import java.util.Properties

/** SMTP server for notification emails (ADR 0052).
  *
  * @param host
  *   empty: no email is sent (in-app notifications only)
  * @param security
  *   `starttls` (upgrade a plain connection, port 587), `tls` (implicit TLS, port 465) or `none` (only for a
  *   local test server such as Mailpit)
  * @param from
  *   sender address, e.g. `DMS <dms@example.org>`
  */
final case class SmtpConfig(
    host: String,
    port: Int,
    security: String,
    username: Option[String],
    password: Option[String],
    from: String,
    timeoutMillis: Int
):
  def enabled: Boolean = host.trim.nonEmpty

/** Sends plain-text UTF-8 emails with Jakarta Mail (Eclipse Angus). A new connection per email: notification
  * volume is low and the job queue already serializes and retries.
  */
final class SmtpMailSender(config: SmtpConfig) extends MailSender:

  private val session: Session =
    val props = Properties()
    props.put("mail.transport.protocol", "smtp")
    props.put("mail.smtp.host", config.host.trim)
    props.put("mail.smtp.port", config.port.toString)
    props.put("mail.smtp.connectiontimeout", config.timeoutMillis.toString)
    props.put("mail.smtp.timeout", config.timeoutMillis.toString)
    props.put("mail.smtp.writetimeout", config.timeoutMillis.toString)
    config.security.trim.toLowerCase match
      case "tls" =>
        props.put("mail.smtp.ssl.enable", "true")
        props.put("mail.smtp.ssl.checkserveridentity", "true")
      case "none" => ()
      case _      =>
        props.put("mail.smtp.starttls.enable", "true")
        props.put("mail.smtp.starttls.required", "true")
        props.put("mail.smtp.ssl.checkserveridentity", "true")
    val credentials = for
      user     <- config.username.map(_.trim).filter(_.nonEmpty)
      password <- config.password
    yield (user, password)
    credentials match
      case Some((user, password)) =>
        props.put("mail.smtp.auth", "true")
        Session.getInstance(
          props,
          new Authenticator:
            override def getPasswordAuthentication: PasswordAuthentication =
              PasswordAuthentication(user, password)
        )
      case None => Session.getInstance(props)

  def send(mail: Mail): IO[String, Unit] =
    ZIO
      .attemptBlocking {
        val message = new MimeMessage(session):
          // The same id on every attempt of a delivery, so duplicates of a retry can be recognized.
          override def updateMessageID(): Unit = setHeader("Message-ID", mail.messageId)
        message.setFrom(InternetAddress(config.from, true))
        message.setRecipient(Message.RecipientType.TO, InternetAddress(mail.to, true))
        message.setSubject(mail.subject, "UTF-8")
        message.setText(mail.text, "UTF-8", "plain")
        message.setHeader("Content-Language", mail.language.code)
        message.setHeader("Auto-Submitted", "auto-generated")
        message.setSentDate(java.util.Date())
        Transport.send(message)
      }
      // The kind of failure and the first line of its message; never the mail text.
      .mapError(e => s"sending the email failed: ${e.getClass.getSimpleName}: ${firstLine(e.getMessage)}")

  private def firstLine(message: String): String =
    Option(message).flatMap(_.linesIterator.nextOption()).getOrElse("").take(200)
