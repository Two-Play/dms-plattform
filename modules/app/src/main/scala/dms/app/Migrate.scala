package dms.app

import org.flywaydb.core.Flyway
import zio.*
import zio.config.typesafe.TypesafeConfigProvider
import zio.logging.backend.SLF4J

/** Runs database migrations. A separate command so that migrations do not run on every instance start. */
object Migrate extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >+>
      Runtime.setConfigProvider(TypesafeConfigProvider.fromResourcePath())

  override def run: ZIO[Any, Throwable, Unit] =
    for
      db     <- ZIO.config(AppConfig.config).map(_.database)
      result <- ZIO.attemptBlocking(
        Flyway
          .configure()
          .dataSource(db.url, db.user, db.password)
          .locations("classpath:db/migration")
          .load()
          .migrate()
      )
      _ <- ZIO.logInfo(
        s"Applied ${result.migrationsExecuted} migration(s), schema now at ${result.targetSchemaVersion}"
      )
    yield ()
