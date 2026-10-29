package dms.integration.webhooks

import zio.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.nio.charset.StandardCharsets

/** Sends one webhook request. Fails with a description if the receiver cannot be reached; returns the HTTP
  * status otherwise.
  */
trait WebhookSender:
  def send(url: URI, headers: List[(String, String)], body: String): IO[String, Int]

/** [[WebhookSender]] with the JDK HTTP client: POST with a timeout, redirects are not followed (a redirect
  * could lead past the SSRF check), the response body is discarded.
  */
final class HttpWebhookSender(client: HttpClient, timeout: Duration) extends WebhookSender:

  def send(url: URI, headers: List[(String, String)], body: String): IO[String, Int] =
    val request = headers
      .foldLeft(HttpRequest.newBuilder(url).timeout(timeout.asJava))((b, h) => b.header(h._1, h._2))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
      .build()
    ZIO
      .fromCompletableFuture(client.sendAsync(request, HttpResponse.BodyHandlers.discarding()))
      .map(_.statusCode())
      .mapError {
        case _: HttpTimeoutException => s"no answer within ${timeout.toMillis} ms"
        case e => s"${url.getHost} not reachable: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"
      }

object HttpWebhookSender:
  def apply(settings: WebhookSettings): HttpWebhookSender =
    new HttpWebhookSender(
      HttpClient
        .newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(settings.timeout.asJava)
        .build(),
      settings.timeout
    )
