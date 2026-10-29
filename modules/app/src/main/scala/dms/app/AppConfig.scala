package dms.app

import dms.db.DbConfig
import dms.documents.{AttachmentScanner, AttachmentSettings}
import dms.documents.core.DocumentSettings
import dms.identity.{AuthConfig, UserDirectoryConfig}
import dms.integration.partners.PartnerDirectoryConfig
import dms.integration.webhooks.{WebhookSecretKeys, WebhookSettings}
import dms.rendering.core.{ConverterConfig, RendererConfig}
import dms.search.core.{OpenSearchConfig, SearchSettings}
import dms.storage.StorageConfig
import zio.Config
import zio.config.magnolia.deriveConfig

final case class HttpConfig(port: Int)

/** Credentials of the schema owner, used only by [[Migrate]]. */
final case class MigrationConfig(user: String, password: String)

/** Periodic audit work (DMS-75, ADR 0017).
  *
  * @param tsaTrustedCertificates
  *   PEM files of the TSA's CA; without them daily RFC 3161 anchoring is disabled and only checkpoints run
  */
final case class AuditConfig(
    checkpointIntervalSeconds: Int,
    anchorHourUtc: Int,
    tsaUrl: String,
    tsaTrustedCertificates: List[String]
)

/** @param name
  *   worker identity owning job leases; defaults to host name and process id
  */
final case class WorkerSettings(name: Option[String])

/** Review and approval workflows (ADR 0024).
  *
  * @param reauthenticationSeconds
  *   step-up window for approval votes: the token's `auth_time` must not be older (DMS-41)
  * @param deputyshipCheckSeconds
  *   how often begun and expired deputyship periods are looked up to notify principal and deputy (DMS-99)
  */
final case class WorkflowSettings(reauthenticationSeconds: Int, deputyshipCheckSeconds: Int)

/** Documents: editorial versions (DMS-46), scheduled publication (DMS-45, ADR 0026) and periodic review
  * (DMS-55, ADR 0030).
  *
  * @param editorialChangeThresholdPercent
  *   the automatic comparison warns when an editorial (minor) version changes more than this share of the
  *   text
  * @param minorVersionsRequireAcknowledgement
  *   whether publishing a minor version asks readers for a new acknowledgement by default
  * @param publicationCheckSeconds
  *   how often approved versions are looked up to schedule their publication jobs
  * @param reviewCheckSeconds
  *   how often the periodic review check runs per tenant: review tasks, review dates reached (US-07.1)
  * @param acknowledgementReminderDays
  *   days after publication until open acknowledgements are reminded of, and between reminders, where neither
  *   the class nor the tenant sets it (DMS-100); 0: no automatic reminders
  * @param acknowledgementReminderCheckSeconds
  *   how often the reminder check runs per tenant
  */
final case class DocumentsConfig(
    editorialChangeThresholdPercent: Int,
    minorVersionsRequireAcknowledgement: Boolean,
    publicationCheckSeconds: Int,
    reviewCheckSeconds: Int,
    acknowledgementReminderDays: Int = AcknowledgementReminderConfig.DefaultDays,
    acknowledgementReminderCheckSeconds: Int = 3600
):
  def settings: DocumentSettings =
    DocumentSettings(editorialChangeThresholdPercent / 100.0, minorVersionsRequireAcknowledgement)

  def reminders: AcknowledgementReminderConfig =
    AcknowledgementReminderConfig(
      acknowledgementReminderDays.max(0).min(dms.documents.AcknowledgementSettings.MaxReminderDays),
      zio.Duration.fromSeconds(acknowledgementReminderCheckSeconds.max(1).toLong)
    )

/** Search index in OpenSearch (DMS-51, ADR 0029).
  *
  * @param syncIntervalSeconds
  *   how often every tenant's index is synchronized with the documents changed since the previous run
  */
final case class SearchConfig(
    url: String,
    indexPrefix: String,
    replicas: Int,
    timeoutSeconds: Int,
    syncIntervalSeconds: Int,
    username: Option[String],
    password: Option[String]
):
  def openSearch: OpenSearchConfig =
    OpenSearchConfig(url, indexPrefix, replicas, timeoutSeconds, username, password)
  def settings: SearchSettings =
    SearchSettings(syncInterval = zio.Duration.fromSeconds(syncIntervalSeconds.toLong))

/** Webhooks for connected systems (DMS-58, DMS-107, ADR 0035).
  *
  * @param allowedHosts
  *   development only: comma-separated hosts that may use http and private or loopback addresses (SSRF
  *   exemption), e.g. `localhost`
  * @param allowPrivateNetworks
  *   lets subscriptions target private networks (https still required), for receivers in the company network
  * @param timeoutSeconds
  *   per delivery attempt
  * @param maxAttempts
  *   attempts per delivery before it is dead; retries back off exponentially up to one hour
  * @param secretKeys
  *   key ring that encrypts the signing secrets at rest: `id:base64key,…`, the first entry active (see
  *   [[dms.integration.webhooks.WebhookSecretKeys.parse]]); empty: the development key
  * @param secretKeysFile
  *   file with the key ring in the same format (e.g. a mounted secret); takes precedence over `secretKeys`
  */
final case class WebhooksConfig(
    allowedHosts: String,
    allowPrivateNetworks: Boolean,
    timeoutSeconds: Int,
    maxAttempts: Int,
    secretKeys: String,
    secretKeysFile: Option[String]
):
  /** The settings, or why the key ring is invalid or its file unreadable (never the key material). */
  def settings: Either[String, WebhookSettings] =
    val ring = secretKeysFile.map(_.trim).filter(_.nonEmpty) match
      case Some(file) =>
        scala.util
          .Try(java.nio.file.Files.readString(java.nio.file.Path.of(file)))
          .toEither
          .left
          .map(_ => s"the webhook secret key file $file cannot be read")
      case None => Right(secretKeys)
    ring
      .flatMap(WebhookSecretKeys.parse)
      .map(keys =>
        WebhookSettings(
          allowedHosts.split(',').map(_.trim).filter(_.nonEmpty).toSet,
          allowPrivateNetworks,
          zio.Duration.fromSeconds(timeoutSeconds.toLong),
          maxAttempts.max(1),
          keys
        )
      )

/** Attachments of document versions (DMS-114, ADR 0041).
  *
  * @param maxSizeMegabytes
  *   largest accepted file in MiB
  * @param blockedExtensions
  *   comma-separated file name extensions that are refused; empty: the built-in list of executable types
  * @param scanner
  *   virus scanner before an attachment becomes available: `none` (no-op adapter, files are stored as
  *   NotScanned); see ADR 0041 for plugging in ClamAV
  */
final case class AttachmentsConfig(maxSizeMegabytes: Int, blockedExtensions: String, scanner: String):
  def settings: AttachmentSettings =
    val configured =
      blockedExtensions
        .split(',')
        .map(_.trim.stripPrefix(".").toLowerCase(java.util.Locale.ROOT))
        .filter(_.nonEmpty)
    AttachmentSettings(
      maxSizeMegabytes.max(1).toLong * 1024 * 1024,
      if configured.isEmpty then AttachmentSettings.DefaultBlockedExtensions else configured.toSet
    )

  def attachmentScanner: Either[String, AttachmentScanner] =
    scanner.trim.toLowerCase(java.util.Locale.ROOT) match
      case "none" | "" => Right(AttachmentScanner.None)
      case other       => Left(s"unknown attachment scanner '$other'; supported: none")

final case class AppConfig(
    http: HttpConfig,
    auth: AuthConfig,
    database: DbConfig,
    migration: MigrationConfig,
    storage: StorageConfig,
    audit: AuditConfig,
    worker: WorkerSettings,
    workflow: WorkflowSettings,
    documents: DocumentsConfig,
    partners: PartnerDirectoryConfig,
    rendering: RendererConfig,
    conversion: ConverterConfig,
    search: SearchConfig,
    webhooks: WebhooksConfig,
    users: UserDirectoryConfig,
    attachments: AttachmentsConfig
)

object AppConfig:
  val config: Config[AppConfig] = deriveConfig[AppConfig].nested("dms")

/** The subset [[SearchRebuild]] needs: storage and the user directory for the text of Word originals
  * (DMS-121).
  */
final case class SearchRebuildConfig(
    database: DbConfig,
    search: SearchConfig,
    storage: StorageConfig,
    auth: AuthConfig,
    users: UserDirectoryConfig
)

object SearchRebuildConfig:
  val config: Config[SearchRebuildConfig] = deriveConfig[SearchRebuildConfig].nested("dms")

/** The subset [[Migrate]] needs, so migrations run without auth or HTTP settings. */
final case class MigrateConfig(database: DbConfig, migration: MigrationConfig)

object MigrateConfig:
  val config: Config[MigrateConfig] = deriveConfig[MigrateConfig].nested("dms")
