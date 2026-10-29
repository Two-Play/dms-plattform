package dms.integration.webhooks

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Signatures of webhook requests following the Standard Webhooks specification (ADR 0035):
  *
  *   - `webhook-id`: the delivery id, the same on every attempt; receivers deduplicate with it
  *   - `webhook-timestamp`: seconds since the epoch at sending; receivers reject old timestamps (replay)
  *   - `webhook-signature`: `v1,<base64 HMAC-SHA256 of "<id>.<timestamp>.<body>">`, keyed with the
  *     base64-decoded part of the secret after `whsec_`
  */
object WebhookSignature:
  val IdHeader        = "webhook-id"
  val TimestampHeader = "webhook-timestamp"
  val SignatureHeader = "webhook-signature"

  private val Prefix = "whsec_"
  private val random = SecureRandom()

  /** A new secret: 32 random bytes as `whsec_<base64>`. */
  def newSecret(): String =
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Prefix + Base64.getEncoder.encodeToString(bytes)

  /** The `webhook-signature` header value. */
  def sign(secret: String, id: String, timestamp: Long, body: String): String =
    "v1," + Base64.getEncoder.encodeToString(hmac(key(secret), s"$id.$timestamp.$body"))

  /** The three Standard Webhooks headers of a request. */
  def headers(secret: String, id: String, timestamp: Long, body: String): List[(String, String)] =
    List(
      IdHeader        -> id,
      TimestampHeader -> timestamp.toString,
      SignatureHeader -> sign(secret, id, timestamp, body)
    )

  /** Checks a `webhook-signature` header (space-separated `v1,<sig>` entries) as a receiver would; compares
    * in constant time.
    */
  def verify(secret: String, id: String, timestamp: Long, body: String, header: String): Boolean =
    val expected = sign(secret, id, timestamp, body).getBytes(StandardCharsets.US_ASCII)
    header
      .split(' ')
      .exists(candidate => MessageDigest.isEqual(candidate.getBytes(StandardCharsets.US_ASCII), expected))

  private def key(secret: String): Array[Byte] =
    Base64.getDecoder.decode(secret.stripPrefix(Prefix))

  private def hmac(key: Array[Byte], content: String): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(content.getBytes(StandardCharsets.UTF_8))
