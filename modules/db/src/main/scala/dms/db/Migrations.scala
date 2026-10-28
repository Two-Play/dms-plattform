package dms.db

import org.flywaydb.core.Flyway
import zio.*

/** Applies the Flyway migrations in `db/migration`. Runs as the schema owner, never as the application role.
  */
object Migrations:
  final case class Result(applied: Int, version: String)

  def migrate(url: String, user: String, password: String): Task[Result] =
    ZIO.attemptBlocking {
      val result = Flyway
        .configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .schemas("dms")
        .defaultSchema("dms")
        .load()
        .migrate()
      Result(result.migrationsExecuted, Option(result.targetSchemaVersion).getOrElse("none"))
    }
