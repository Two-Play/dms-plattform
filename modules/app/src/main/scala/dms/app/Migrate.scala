package dms.app

import dms.db.Migrations
import zio.*
import zio.config.typesafe.TypesafeConfigProvider
import zio.logging.backend.SLF4J

/** Runs database migrations as the schema owner. A separate command so that migrations do not run on every
  * instance start.
  */
object Migrate extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >+>
      Runtime.setConfigProvider(TypesafeConfigProvider.fromResourcePath())

  override def run: ZIO[Any, Throwable, Unit] =
    for
      config <- ZIO.config(MigrateConfig.config)
      result <- Migrations.migrate(config.database.url, config.migration.user, config.migration.password)
      _      <- ZIO.logInfo(s"Applied ${result.applied} migration(s), schema now at ${result.version}")
    yield ()
