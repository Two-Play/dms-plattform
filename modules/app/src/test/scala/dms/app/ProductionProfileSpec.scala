package dms.app

import com.typesafe.config.ConfigFactory
import dms.integration.webhooks.WebhookSecretKeys
import zio.*
import zio.config.typesafe.TypesafeConfigProvider
import zio.test.*

/** The checks of the profile `production` (DMS-60, ADR 0056), on the configuration of application.conf. */
object ProductionProfileSpec extends ZIOSpecDefault:

  private val secret = "s3cr3t-value"

  /** A complete production configuration; `changes` override it, as an operator's settings would. */
  private def configured(changes: String) =
    val production = s"""
      dms.auth { issuer = "https://id.example.com", audience = "project", clientId = "web" }
      dms.database.password = "$secret"
      dms.storage { endpoint = "https://s3.example.com", accessKey = "$secret", secretKey = "$secret" }
      dms.audit { tsaUrl = "https://tsa.example.com", tsaTrustedCertificates = ["/etc/dms/tsa.pem"] }
      dms.search { url = "https://search.example.com", username = "dms", password = "$secret", replicas = 1 }
      dms.partners.directory = "http"
      dms.users.token = "$secret"
      dms.webhooks.secretKeys = "${WebhookSecretKeys.generate("2026-11")}"
      dms.notifications { appBaseUrl = "https://dms.example.com", smtp { host = "mail.example.com" } }
      """
    val config = ConfigFactory
      .parseString(changes)
      .withFallback(ConfigFactory.parseString(production))
      .withFallback(ConfigFactory.parseResources("application.conf"))
      .resolve()
    TypesafeConfigProvider.fromTypesafeConfig(config).load(AppConfig.config)

  private def report(changes: String = "") =
    for
      config   <- configured(changes)
      webhooks <- ZIO.fromEither(config.webhooks.settings).mapError(m => RuntimeException(m))
    yield ProductionProfile.check(config, webhooks)

  private def refusedFor(key: String, changes: String) =
    report(changes).map(r => assertTrue(r.refused.exists(_.startsWith(key))))

  private def warnsAbout(key: String, changes: String) =
    report(changes).map(r => assertTrue(r.refused.isEmpty, r.warnings.exists(_.startsWith(key))))

  def spec = suite("Production profile")(
    test("a complete configuration starts without refusals or warnings") {
      report().map(r => assertTrue(r.refused.isEmpty, r.warnings.isEmpty))
    },
    test("refuses the webhook development key") {
      refusedFor("dms.webhooks.secretKeys", """dms.webhooks.secretKeys = """"")
    },
    test("refuses hosts exempted from the protection of webhooks") {
      refusedFor("dms.webhooks.allowedHosts", """dms.webhooks.allowedHosts = "localhost"""")
    },
    test("refuses an issuer without https") {
      refusedFor("dms.auth.issuer", """dms.auth.issuer = "http://localhost:8085"""")
    },
    test("refuses the credentials of local development") {
      for
        database <- refusedFor("dms.database.password", """dms.database.password = "dms-app-local"""")
        storage  <- refusedFor("dms.storage.accessKey", """dms.storage.secretKey = "dms-local-secret"""")
      yield database && storage
    },
    test("refuses an object store without Object Lock") {
      refusedFor("dms.retention.objectLock.enabled", "dms.retention.objectLock.enabled = false")
    },
    test("refuses missing TSA trust and the development TSA, unless switched off deliberately") {
      for
        trust <- refusedFor("dms.audit", "dms.audit.tsaTrustedCertificates = []")
        tsa   <- refusedFor("dms.audit", """dms.audit.tsaUrl = "https://freetsa.org/tsr"""")
        off   <- warnsAbout(
          "dms.audit",
          "dms.audit { tsaTrustedCertificates = [], allowUnanchoredInProduction = true }"
        )
      yield trust && tsa && off
    },
    test("refuses a search index without TLS or credentials") {
      for
        plain <- refusedFor("dms.search", """dms.search.url = "http://search:9200"""")
        open  <- refusedFor("dms.search", """dms.search.password = """"")
      yield plain && open
    },
    test("refuses unencrypted email and links to a development address") {
      for
        none <- refusedFor("dms.notifications.smtp.security", """dms.notifications.smtp.security = "none"""")
        link <- refusedFor(
          "dms.notifications.appBaseUrl",
          """dms.notifications.appBaseUrl = "https://localhost:5173""""
        )
      yield none && link
    },
    test("only warns about what an installation may do without on purpose") {
      for
        smtp     <- warnsAbout("dms.notifications.smtp.host", """dms.notifications.smtp.host = """"")
        partners <- warnsAbout("dms.partners.directory", """dms.partners.directory = "demo"""")
        replicas <- warnsAbout("dms.search.replicas", "dms.search.replicas = 0")
        storage  <- warnsAbout("dms.storage.endpoint", """dms.storage.endpoint = "http://s3:9000"""")
      yield smtp && partners && replicas && storage
    },
    test("reports every problem at once and never a value") {
      for
        r <- report(s"""
          dms.auth.issuer = "http://$secret.example.com"
          dms.webhooks.secretKeys = ""
          dms.search.url = "http://$secret:9200"
          """)
        messages = r.refused ++ r.warnings
      yield assertTrue(r.refused.size == 3, !messages.exists(_.contains(secret)))
    }
  )
