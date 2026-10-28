package dms.app

import dms.db.Migrations
import dms.workflow.core.FlowableEngine
import zio.*
import zio.config.typesafe.TypesafeConfigProvider
import zio.logging.backend.SLF4J

/** Runs database migrations as the schema owner: Flyway, then the workflow engine's schema. A separate
  * command so that migrations do not run on every instance start.
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
      // Flowable's own tables in schema flowable (ADR 0024), created or upgraded by the schema owner.
      _ <- FlowableEngine.migrateSchema(config.database.url, config.migration.user, config.migration.password)
      _ <- ZIO.logInfo("Workflow engine schema is up to date")
    yield ()
