package dms.integration.webhooks

import dms.kernel.TenantId
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.util.UUID

/** Test events for administrators (DMS-107): checks that a receiver is reachable and verifies signatures
  * before real events arrive.
  */
final class WebhookPing(sender: WebhookSender, settings: WebhookSettings):

  /** Sends a signed test event (`webhook.test`) to a subscription's receiver right away, without delivery log
    * or retries (DMS-107): the URL is checked like before every delivery, the secret is decrypted, and the
    * outcome is returned. Runs outside transactions.
    */
  def send(
      tenant: TenantId,
      subscription: UUID,
      url: String,
      secret: StoredSecret
  ): UIO[PingResult] =
    for
      started <- Clock.nanoTime
      now     <- Clock.instant
      id   = s"test_${UUID.randomUUID()}"
      body = Json
        .Obj(
          "type"      -> Json.Str(WebhookEventType.Test),
          "timestamp" -> Json.Str(now.toString),
          "data"      -> Json.Obj(
            "subscriptionId" -> Json.Str(subscription.toString),
            "message"        -> Json.Str("Test event of the DMS platform; no document changed."),
            "payloadVersion" -> Json.Num(WebhookJobs.PayloadVersion)
          )
        )
        .toJson
      answer <- (for
        plain <- ZIO.fromEither(settings.secretKeys.open(secret, tenant, subscription))
        uri   <- ZIO.fromEither(UrlGuard.parse(url, settings)).tap(UrlGuard.check(_, settings))
        code  <- sender.send(
          uri,
          WebhookSignature
            .headers(plain, id, now.getEpochSecond, body) :+ ("User-Agent" -> WebhookJobs.UserAgent),
          body
        )
      yield code).either
      ended <- Clock.nanoTime
    yield
      val millis = (ended - started) / 1_000_000
      answer match
        case Right(code) if code >= 200 && code < 300 =>
          PingResult(id, delivered = true, Some(code), None, millis)
        case Right(code) =>
          PingResult(id, delivered = false, Some(code), Some(s"the receiver answered HTTP $code"), millis)
        case Left(error) => PingResult(id, delivered = false, None, Some(error), millis)

/** Outcome of a test event (DMS-107).
  *
  * @param webhookId
  *   the `webhook-id` sent (`test_<uuid>`)
  * @param statusCode
  *   the receiver's HTTP status, if it answered
  * @param error
  *   why it was not delivered: blocked target, unreachable receiver, non-2xx answer, unreadable secret
  */
final case class PingResult(
    webhookId: String,
    delivered: Boolean,
    statusCode: Option[Int],
    error: Option[String],
    durationMillis: Long
)
