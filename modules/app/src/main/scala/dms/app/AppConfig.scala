package dms.app

import dms.db.DbConfig
import dms.identity.AuthConfig
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

final case class AppConfig(
    http: HttpConfig,
    auth: AuthConfig,
    database: DbConfig,
    migration: MigrationConfig,
    storage: StorageConfig,
    audit: AuditConfig,
    worker: WorkerSettings,
    workflow: WorkflowSettings
)

object AppConfig:
  val config: Config[AppConfig] = deriveConfig[AppConfig].nested("dms")

/** The subset [[Migrate]] needs, so migrations run without auth or HTTP settings. */
final case class MigrateConfig(database: DbConfig, migration: MigrationConfig)

object MigrateConfig:
  val config: Config[MigrateConfig] = deriveConfig[MigrateConfig].nested("dms")
