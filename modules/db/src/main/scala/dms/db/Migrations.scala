package dms.db

import org.flywaydb.core.Flyway
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension
import zio.*

/** Applies the Flyway migrations in `db/migration`. Runs as the schema owner, never as the application role.
  *
  * Migrations run in a transaction, unless they build indexes on tables with data: see "Migrations without a
  * transaction" in `modules/db/README.md` (ADR 0066).
  */
object Migrations:
  final case class Result(applied: Int, version: String)

  val Location = "classpath:db/migration"

  def migrate(url: String, user: String, password: String): Task[Result] =
    ZIO.attemptBlocking {
      val result = flyway(url, user, password, Location).migrate()
      Result(result.migrationsExecuted, Option(result.targetSchemaVersion).getOrElse("none"))
    }

  /** The one Flyway configuration; `locations` differ only in the tests of the migration conventions.
    *
    * The advisory lock against parallel runs is held by the session. Flyway's default holds it in a
    * transaction that stays open during the migration, and `create index concurrently` waits for that
    * transaction: the migration would wait for itself, for ever.
    */
  private[db] def flyway(url: String, user: String, password: String, locations: String*): Flyway =
    val configuration = Flyway
      .configure()
      .dataSource(url, user, password)
      .locations(locations*)
      .schemas("dms")
      .defaultSchema("dms")
    configuration.getPluginRegister
      .getExact(classOf[PostgreSQLConfigurationExtension])
      .setTransactionalLock(false)
    configuration.load()
