package dms.app

import dms.integration.webhooks.WebhookSettings

/** What the profile `production` says about a configuration (DMS-60, ADR 0056). */
final case class ProductionReport(refused: List[String], warnings: List[String])

/** The checks of the profile `production`: development defaults that would silently weaken a promise of the
  * platform refuse the start, gaps an installation may have on purpose are warned about at every start. All
  * problems are reported at once, by the name of the setting and never with its value. The virus scanner is
  * checked with the attachments (ADR 0041).
  */
object ProductionProfile:
  /** The credentials compose.yaml and application.conf use for local development. */
  private val DevelopmentDatabasePassword = "dms-app-local"
  private val DevelopmentStorageKeys      = Set("dms-local", "dms-local-secret")

  private val DevelopmentTsa = "freetsa.org"

  private def isHttps(url: String): Boolean =
    url.trim.toLowerCase(java.util.Locale.ROOT).startsWith("https://")

  private def isLocal(url: String): Boolean =
    val host = scala.util.Try(java.net.URI(url.trim).getHost).toOption.flatMap(Option(_)).getOrElse("")
    host == "localhost" || host == "127.0.0.1" || host == "[::1]"

  def check(config: AppConfig, webhooks: WebhookSettings): ProductionReport =
    def refuse(problem: Boolean, message: String) = Option.when(problem)(message)

    val audit   = config.audit
    val noTsa   = audit.tsaTrustedCertificates.isEmpty || audit.tsaUrl.contains(DevelopmentTsa)
    val smtp    = config.notifications.smtp
    val search  = config.search
    val refused = List(
      refuse(
        webhooks.secretKeys.isDevelopment,
        "dms.webhooks.secretKeys: webhook secrets would be encrypted with the publicly known development key; " +
          "set DMS_WEBHOOKS_SECRET_KEYS_FILE or DMS_WEBHOOKS_SECRET_KEYS (ADR 0035)"
      ),
      refuse(
        webhooks.allowedHosts.nonEmpty,
        "dms.webhooks.allowedHosts: exempts hosts from the protection against requests into the own network; " +
          "development only (use allowPrivateNetworks for receivers in the company network)"
      ),
      refuse(
        !isHttps(config.auth.issuer),
        "dms.auth.issuer: must be an https address, tokens are verified with the keys fetched from it"
      ),
      refuse(
        config.auth.clientId.forall(_.trim.isEmpty),
        "dms.auth.clientId: without the OIDC client id approval votes cannot prove a recent sign-in (ADR 0024)"
      ),
      refuse(
        config.database.password == DevelopmentDatabasePassword,
        "dms.database.password: is the password of local development"
      ),
      refuse(
        DevelopmentStorageKeys(config.storage.accessKey) || DevelopmentStorageKeys(config.storage.secretKey),
        "dms.storage.accessKey, secretKey: are the keys of local development"
      ),
      refuse(
        !config.conversion.outboundBlocked,
        "dms.conversion.outboundBlocked: documents can make the conversion worker load external content; give " +
          "it a network without outbound access and confirm with DMS_CONVERSION_OUTBOUND_BLOCKED=true (ADR 0076)"
      ),
      refuse(
        !config.retention.objectLock.enabled,
        "dms.retention.objectLock.enabled: approved versions need an object store with Object Lock (ADR 0016, 0055)"
      ),
      refuse(
        noTsa && !audit.allowUnanchoredInProduction,
        "dms.audit: daily anchoring needs a contractually bound TSA and its trusted certificates " +
          "(tsaUrl, tsaTrustedCertificates) or, deliberately, allowUnanchoredInProduction = true (ADR 0017)"
      ),
      refuse(
        !isHttps(search.url) || search.username.forall(_.trim.isEmpty) || search.password.forall(_.isEmpty),
        "dms.search: the index holds the text of documents; it needs an https address and credentials " +
          "(DMS_SEARCH_URL, DMS_SEARCH_USERNAME, DMS_SEARCH_PASSWORD)"
      ),
      refuse(
        smtp.enabled && smtp.security.trim.equalsIgnoreCase("none"),
        "dms.notifications.smtp.security: none sends emails unencrypted; use starttls or tls"
      ),
      refuse(
        smtp.enabled && (!isHttps(config.notifications.appBaseUrl) || isLocal(
          config.notifications.appBaseUrl
        )),
        "dms.notifications.appBaseUrl: links in emails must point to the https address of the application"
      )
    ).flatten

    val warnings = List(
      refuse(
        !smtp.enabled,
        "dms.notifications.smtp.host: not set, notifications are shown in the application only (ADR 0052)"
      ),
      refuse(
        noTsa && audit.allowUnanchoredInProduction,
        "dms.audit: daily anchoring is switched off deliberately, the audit trail has checkpoints only"
      ),
      refuse(
        config.partners.directory.trim.equalsIgnoreCase("demo"),
        "dms.partners.directory: demo offers fictitious partners; do not release templates with a partner " +
          "reference before the partner master is connected (DMS-89)"
      ),
      refuse(
        config.users.token.forall(_.trim.isEmpty) && config.users.tokenFile.forall(_.trim.isEmpty),
        "dms.users: no token for the user directory, user ids are not resolved and people cannot be searched"
      ),
      refuse(search.replicas == 0, "dms.search.replicas: 0, the index has no copy on another node"),
      refuse(
        !isHttps(config.storage.endpoint),
        "dms.storage.endpoint: not https, contents travel unencrypted to the object store"
      )
    ).flatten

    ProductionReport(refused, warnings)
