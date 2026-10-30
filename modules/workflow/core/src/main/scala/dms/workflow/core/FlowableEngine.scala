package dms.workflow.core

import dms.db.Db
import org.flowable.common.engine.api.{FlowableObjectNotFoundException, FlowableOptimisticLockingException}
import org.flowable.common.engine.impl.AbstractEngineConfiguration
import org.flowable.common.engine.impl.cfg.TransactionState
import org.flowable.common.engine.impl.history.HistoryLevel
import org.flowable.common.engine.impl.interceptor.CommandInterceptor
import org.flowable.common.engine.impl.persistence.StrongUuidGenerator
import org.flowable.engine.ProcessEngine
import org.flowable.engine.impl.cfg.StandaloneProcessEngineConfiguration
import org.postgresql.ds.PGSimpleDataSource
import zio.*

import java.util.UUID
import javax.sql.DataSource

/** The embedded Flowable process engine (ADR 0006, 0024).
  *
  * Its tables live in the PostgreSQL schema `flowable`, without row-level security; tenants are separated by
  * Flowable's `tenantId` on deployments, process instances and tasks, and only `workflow-core` touches the
  * engine. Engine calls join the caller's `Db.transact` connection, so workflow steps, document status
  * changes, votes, audit entries and outbox events commit or roll back together.
  *
  * Schema changes are not made at runtime (`databaseSchemaUpdate=false`): [[FlowableEngine.migrateSchema]]
  * creates or upgrades the tables as the schema owner, like the Flyway migrations.
  */
final class FlowableEngine(val engine: ProcessEngine):

  /** Runs engine calls inside the current `Db.transact`. Must not be used outside a transaction. The engine's
    * `COMMITTED` listeners run after the transaction committed, its `ROLLED_BACK` listeners after a rollback
    * (commit hook, DMS-87, ADR 0053).
    */
  def run[A](f: ProcessEngine => A): IO[WorkflowError, A] =
    Db.connection
      .mapError(WorkflowError.Database(_))
      .flatMap(connection =>
        ZIO
          .attemptBlocking(SharedTransaction.call(connection)(f(engine)))
          .mapError(FlowableEngine.toError)
      )
      .flatMap { (result, tx) =>
        val hooks =
          ZIO.when(tx.listensTo(TransactionState.COMMITTED))(
            Db.afterCommit(FlowableEngine.fire(tx, TransactionState.COMMITTED))
          ) *>
            ZIO.when(tx.listensTo(TransactionState.ROLLED_BACK))(
              Db.afterRollback(FlowableEngine.fire(tx, TransactionState.ROLLED_BACK))
            )
        hooks.mapBoth(WorkflowError.Database(_), _ => result)
      }

object FlowableEngine:
  val Schema = "flowable"

  /** Tables of the IDM engine and the event registry are not created: users and groups come from ZITADEL and
    * the role assignments, events from the outbox. Must be the same for schema migration and runtime.
    */
  private def configure(
      configuration: StandaloneProcessEngineConfiguration,
      dataSource: DataSource,
      name: String
  ) =
    configuration.setEngineName(name)
    configuration.setDataSource(dataSource)
    configuration.setDatabaseSchema(Schema)
    configuration.setDisableIdmEngine(true)
    configuration.setDisableEventRegistry(true)
    configuration.setIdGenerator(StrongUuidGenerator())
    configuration.setAsyncExecutorActivate(false)
    configuration.setHistoryLevel(HistoryLevel.AUDIT)
    configuration

  /** Builds the engine on the application's data source. Flowable's SQL is qualified with the schema through
    * the table prefix, so the application's connections keep their search path.
    */
  def build(target: DataSource): ProcessEngine =
    val configuration = new StandaloneProcessEngineConfiguration:
      override def createTransactionInterceptor(): CommandInterceptor =
        SharedTransaction.Interceptor(target)
    val _ = configure(configuration, SharedTransaction.BoundDataSource(target), s"dms-${UUID.randomUUID()}")
    configuration.setDatabaseTablePrefix(s"$Schema.")
    configuration.setTablePrefixIsSchema(true)
    configuration.setDatabaseSchemaUpdate(AbstractEngineConfiguration.DB_SCHEMA_UPDATE_FALSE)
    configuration.setTransactionsExternallyManaged(true)
    configuration.setTransactionContextFactory(SharedTransaction.ContextFactory())
    configuration.buildProcessEngine()

  val layer: ZLayer[DataSource, Throwable, FlowableEngine] =
    ZLayer.scoped(
      ZIO
        .acquireRelease(ZIO.serviceWithZIO[DataSource](ds => ZIO.attemptBlocking(build(ds))))(engine =>
          ZIO.attemptBlocking(engine.close()).orDie
        )
        .map(FlowableEngine(_))
    )

  /** Creates or upgrades Flowable's tables in schema `flowable` as the schema owner, on connections of its
    * own whose search path is `flowable`. Default privileges from migration V11 grant the application role
    * access to the new tables. Run after the Flyway migrations.
    */
  def migrateSchema(url: String, user: String, password: String): Task[Unit] =
    ZIO.attemptBlocking {
      val dataSource = PGSimpleDataSource()
      dataSource.setURL(url)
      dataSource.setUser(user)
      dataSource.setPassword(password)
      dataSource.setCurrentSchema(Schema)
      val configuration = configure(StandaloneProcessEngineConfiguration(), dataSource, "dms-schema")
      configuration.setDatabaseSchemaUpdate(AbstractEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
      configuration.buildProcessEngine().close()
    }

  /** Fires the listeners of `state` after the outer transaction ended; failures are logged. */
  private def fire(tx: SharedTransaction.Joined, state: TransactionState): UIO[Unit] =
    ZIO
      .attemptBlocking(tx.fire(state))
      .catchAll(e => ZIO.logWarning(s"A workflow engine listener ($state) failed: ${e.getMessage}"))

  private def toError(t: Throwable): WorkflowError = t match
    case e: WorkflowError.Failure              => e.error
    case e: FlowableOptimisticLockingException =>
      WorkflowError.Conflict(s"concurrent change: ${e.getMessage}")
    case e: FlowableObjectNotFoundException => WorkflowError.NotFound(e.getMessage)
    case e: SharedTransaction.RollbackOnly  => WorkflowError.Engine(e.getMessage)
    case e => WorkflowError.Engine(Option(e.getMessage).getOrElse(e.toString))
