package dms.app

import dms.db.DbConfig
import zio.Config
import zio.config.magnolia.deriveConfig

final case class HttpConfig(port: Int)

/** Credentials of the schema owner, used only by [[Migrate]]. */
final case class MigrationConfig(user: String, password: String)

final case class AppConfig(http: HttpConfig, database: DbConfig, migration: MigrationConfig)

object AppConfig:
  val config: Config[AppConfig] = deriveConfig[AppConfig].nested("dms")
