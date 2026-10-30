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
import dms.archive.core.{MaintenanceConfig, OrphanCleanupConfig}

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

/** Connection to ClamAV's `clamd` (DMS-117, ADR 0041 addendum). */
final case class ClamAvSettings(host: String, port: Int, timeoutSeconds: Int):
  def config: ClamAvConfig = ClamAvConfig(host.trim, port, timeoutSeconds.max(1))

/** Attachments of document versions (DMS-114, ADR 0041); the scanner also checks originals of file documents,
  * Word templates and upload previews.
  *
  * @param maxSizeMegabytes
  *   largest accepted file in MiB
  * @param blockedExtensions
  *   comma-separated file name extensions that are refused; empty: the built-in list of executable types
  * @param scanner
  *   virus scanner before an uploaded file is stored or processed: `none` (no-op adapter, files are stored as
  *   NotScanned) or `clamav` (`clamd` over TCP, DMS-117)
  * @param allowUnscannedInProduction
  *   lets the profile `production` start with scanner `none` (explicit opt-out, logged at start)
  */
final case class AttachmentsConfig(
    maxSizeMegabytes: Int,
    blockedExtensions: String,
    scanner: String,
    clamav: ClamAvSettings = ClamAvSettings("localhost", 3310, 60),
    allowUnscannedInProduction: Boolean = false
):
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

  /** The configured scanner. In the profile `production` the no-op scanner is refused (files would be stored
    * as `NotScanned`) unless [[allowUnscannedInProduction]] is set (DMS-117, ADR 0041 addendum).
    */
  def attachmentScanner(profile: Profile): Either[String, AttachmentScanner] =
    scanner.trim.toLowerCase(java.util.Locale.ROOT) match
      case "none" | "" if profile == Profile.Production && !allowUnscannedInProduction =>
        Left(
          "no virus scanner is configured for the production profile: set dms.attachments.scanner = clamav " +
            "(DMS_ATTACHMENTS_SCANNER) or, deliberately, dms.attachments.allowUnscannedInProduction = true"
        )
      case "none" | "" => Right(AttachmentScanner.None)
      case "clamav"    =>
        Either.cond(
          clamav.host.trim.nonEmpty && clamav.port > 0 && clamav.port < 65536,
          ClamAvScanner(clamav.config),
          s"invalid ClamAV address ${clamav.host}:${clamav.port} (dms.attachments.clamav)"
        )
      case other => Left(s"unknown attachment scanner '$other'; supported: none, clamav")

/** Where the application runs (DMS-117): `production` turns on the checks that refuse development defaults at
  * start, such as storing uploads without a virus scan.
  */
enum Profile:
  case Development, Production

object Profile:
  def parse(value: String): Either[String, Profile] =
    value.trim.toLowerCase(java.util.Locale.ROOT) match
      case "development" | "" => Right(Development)
      case "production"       => Right(Production)
      case other              => Left(s"unknown profile '$other'; supported: development, production")

/** Retention maintenance and clean-up (DMS-82, ADR 0054).
  *
  * @param maintenanceIntervalSeconds
  *   how often the maintenance of every tenant runs (compaction, discarded drafts, orphaned objects)
  * @param keepWorkingStatesOpen
  *   working states kept at least for a version still worked on
  * @param keepWorkingStatesClosed
  *   working states kept at least for a version no longer open
  * @param keepRecentWorkingStatesDays
  *   working states saved within this many days are never compacted
  * @param discardedContentGraceDays
  *   the content of a discarded draft is removed this many days after it was discarded
  * @param orphanGraceHours
  *   orphaned objects and files are removed only once they are older than this
  * @param sweepOrphans
  *   whether the maintenance removes orphaned objects (otherwise only on request)
  */
final case class RetentionConfig(
    maintenanceIntervalSeconds: Int = 86400,
    keepWorkingStatesOpen: Int = 10,
    keepWorkingStatesClosed: Int = 1,
    keepRecentWorkingStatesDays: Int = 7,
    discardedContentGraceDays: Int = 30,
    orphanGraceHours: Int = 168,
    sweepOrphans: Boolean = true
):
  def maintenance: MaintenanceConfig =
    MaintenanceConfig(
      interval = zio.Duration.fromSeconds(maintenanceIntervalSeconds.max(60).toLong),
      keepLatestOpen = keepWorkingStatesOpen.max(1),
      keepLatestClosed = keepWorkingStatesClosed.max(1),
      keepRecent = zio.Duration.fromSeconds(keepRecentWorkingStatesDays.max(0).toLong * 86400),
      discardedContentGrace = zio.Duration.fromSeconds(discardedContentGraceDays.max(0).toLong * 86400),
      sweepOrphans = sweepOrphans
    )

  def orphans: OrphanCleanupConfig =
    OrphanCleanupConfig(grace = zio.Duration.fromSeconds(orphanGraceHours.max(0).toLong * 3600))

final case class AppConfig(
    /** `development` (default) or `production` (DMS-117), see [[Profile]]. */
    profile: String,
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
    attachments: AttachmentsConfig,
    retention: RetentionConfig
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
