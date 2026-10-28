package dms.app

import zio.Config
import zio.config.magnolia.deriveConfig

final case class HttpConfig(port: Int)
final case class DatabaseConfig(url: String, user: String, password: String)
final case class AppConfig(http: HttpConfig, database: DatabaseConfig)

object AppConfig:
  val config: Config[AppConfig] = deriveConfig[AppConfig].nested("dms")
