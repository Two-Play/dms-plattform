package dms.app

import dms.integration.webhooks.WebhookSecretKeys
import zio.*
import zio.test.*

import java.nio.file.Files

/** The webhook configuration with its key ring (DMS-107). */
object WebhooksConfigSpec extends ZIOSpecDefault:

  private def config(keys: String = "", file: Option[String] = None) =
    WebhooksConfig("localhost", allowPrivateNetworks = false, 10, 12, keys, file)

  def spec = suite("Webhook configuration")(
    test("the key ring comes from the setting or a file; errors never quote key material") {
      val key = WebhookSecretKeys.generate("2026-10")
      for
        file <- ZIO.attemptBlocking {
          val f = Files.createTempFile("webhook-keys", ".txt")
          Files.writeString(f, s"$key\n")
          f
        }
        fromFile = config(file = Some(file.toString)).settings
        _ <- ZIO.attemptBlocking(Files.delete(file))
      yield assertTrue(
        config().settings.map(_.secretKeys.isDevelopment) == Right(true),
        config(key).settings.map(_.secretKeys.activeKeyId) == Right("2026-10"),
        config(key).settings.map(_.allowedHosts) == Right(Set("localhost")),
        fromFile.map(_.secretKeys.activeKeyId) == Right("2026-10"),
        config(file = Some("/nonexistent/keys")).settings.isLeft,
        config("2026-10:c2hvcnQ=").settings.left.exists(!_.contains("c2hvcnQ="))
      )
    }
  )
