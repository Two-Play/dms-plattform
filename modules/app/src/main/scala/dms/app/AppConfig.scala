package dms.app

import dms.db.DbConfig
import dms.documents.core.DocumentSettings
import dms.identity.AuthConfig
import dms.integration.partners.PartnerDirectoryConfig
import dms.rendering.core.RendererConfig
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
  */
final case class WorkflowSettings(reauthenticationSeconds: Int)

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
  */
final case class DocumentsConfig(
    editorialChangeThresholdPercent: Int,
    minorVersionsRequireAcknowledgement: Boolean,
    publicationCheckSeconds: Int,
    reviewCheckSeconds: Int
):
  def settings: DocumentSettings =
    DocumentSettings(editorialChangeThresholdPercent / 100.0, minorVersionsRequireAcknowledgement)

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
    search: SearchConfig
)

object AppConfig:
  val config: Config[AppConfig] = deriveConfig[AppConfig].nested("dms")

/** The subset [[SearchRebuild]] needs. */
final case class SearchRebuildConfig(database: DbConfig, search: SearchConfig)

object SearchRebuildConfig:
  val config: Config[SearchRebuildConfig] = deriveConfig[SearchRebuildConfig].nested("dms")

/** The subset [[Migrate]] needs, so migrations run without auth or HTTP settings. */
final case class MigrateConfig(database: DbConfig, migration: MigrationConfig)

object MigrateConfig:
  val config: Config[MigrateConfig] = deriveConfig[MigrateConfig].nested("dms")
