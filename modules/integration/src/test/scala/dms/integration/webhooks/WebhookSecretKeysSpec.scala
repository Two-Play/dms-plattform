package dms.integration.webhooks

import dms.kernel.TenantId
import zio.test.*

import java.util.{Base64, UUID}

/** Encryption of webhook signing secrets at rest (DMS-107). */
object WebhookSecretKeysSpec extends ZIOSpecDefault:

  private val tenant       = TenantId.random()
  private val subscription = UUID.randomUUID()
  private val secret       = WebhookSignature.newSecret()

  private def keys(spec: String): WebhookSecretKeys =
    WebhookSecretKeys.parse(spec).fold(e => throw AssertionError(e), identity)

  private val first  = WebhookSecretKeys.generate("2026-10")
  private val second = WebhookSecretKeys.generate("2027-01")

  def spec = suite("Webhook secret keys")(
    test("a locked secret opens again for its tenant and subscription only, and never shows the secret") {
      val ring    = keys(first)
      val locked  = ring.seal(secret, tenant, subscription)
      val again   = ring.seal(secret, tenant, subscription)
      val decoded = Base64.getDecoder.decode(locked.ciphertext)
      assertTrue(
        locked.keyId == "2026-10",
        ring.activeKeyId == "2026-10",
        !ring.isDevelopment,
        ring.open(locked, tenant, subscription) == Right(secret),
        // A fresh nonce per encryption: equal secrets give different ciphertexts.
        again.ciphertext != locked.ciphertext,
        !locked.ciphertext.contains(secret.stripPrefix("whsec_")),
        decoded.length == 12 + secret.length + 16,
        // Bound to the row: a ciphertext copied to another subscription or tenant does not open.
        ring.open(locked, tenant, UUID.randomUUID()).isLeft,
        ring.open(locked, TenantId.random(), subscription).isLeft,
        !locked.toString.contains(locked.ciphertext),
        !StoredSecret.Plain(secret).toString.contains(secret)
      )
    },
    test("altered ciphertexts and unknown keys are rejected with a message that names the key only") {
      val ring     = keys(first)
      val locked   = ring.seal(secret, tenant, subscription)
      val bytes    = Base64.getDecoder.decode(locked.ciphertext)
      val tampered = bytes.updated(bytes.length - 1, (bytes.last ^ 1).toByte)
      val altered  = locked.copy(ciphertext = Base64.getEncoder.encodeToString(tampered))
      val other    = keys(second)
      val unknown  = other.open(locked, tenant, subscription)
      assertTrue(
        ring.open(altered, tenant, subscription).isLeft,
        ring.open(locked.copy(ciphertext = "AAAA"), tenant, subscription).isLeft,
        unknown.left.exists(_.contains("2026-10")),
        !unknown.left.exists(_.contains(secret))
      )
    },
    test("rotation: older keys decrypt only, secrets of older keys and plain text need re-encryption") {
      val old     = keys(first)
      val rotated = keys(s"$second,$first")
      val locked  = old.seal(secret, tenant, subscription)
      val dev     = WebhookSecretKeys.development.seal(secret, tenant, subscription)
      assertTrue(
        rotated.activeKeyId == "2027-01",
        rotated.open(locked, tenant, subscription) == Right(secret),
        rotated.needsResealing(locked),
        !rotated.needsResealing(rotated.seal(secret, tenant, subscription)),
        rotated.needsResealing(StoredSecret.Plain(secret)),
        // Rows of the development key are re-encrypted after real keys are configured.
        rotated.open(dev, tenant, subscription) == Right(secret),
        rotated.keyIds == Set("2027-01", "2026-10", WebhookSecretKeys.DevelopmentKeyId)
      )
    },
    test("the configuration is checked; without keys the development key is active") {
      val material = first.split(":", 2)(1)
      assertTrue(
        WebhookSecretKeys.parse("").map(_.isDevelopment) == Right(true),
        WebhookSecretKeys.parse("  ").map(_.isDevelopment) == Right(true),
        WebhookSecretKeys.parse(s"$first, $second").map(_.activeKeyId) == Right("2026-10"),
        WebhookSecretKeys.parse("k1:dG9vIHNob3J0").isLeft,
        WebhookSecretKeys.parse(s"bad id:$material").isLeft,
        WebhookSecretKeys.parse(material).isLeft,
        WebhookSecretKeys.parse(s"dev:$material").isLeft,
        WebhookSecretKeys.parse(s"$first,$first").isLeft,
        // Key material never appears in the description of the ring or of the settings.
        !keys(first).toString.contains(material),
        !WebhookSettings(secretKeys = keys(first)).toString.contains(material)
      )
    }
  )
