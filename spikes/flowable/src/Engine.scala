package spike.flowable

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.flowable.common.engine.impl.AbstractEngineConfiguration
import org.flowable.common.engine.impl.interceptor.CommandInterceptor
import org.flowable.common.engine.impl.persistence.StrongUuidGenerator
import org.flowable.engine.{ProcessEngine, ProcessEngineConfiguration}
import org.flowable.engine.impl.cfg.StandaloneProcessEngineConfiguration

import javax.sql.DataSource

object Engine:
  val JdbcUrl  = sys.env.getOrElse("SPIKE_JDBC_URL", "jdbc:postgresql://localhost:5433/spike_flowable")
  val User     = "dms"
  val Password = "dms-lokal"
  val FlowableSchema = "flowable"

  def dataSource(): HikariDataSource =
    val c = HikariConfig()
    c.setJdbcUrl(JdbcUrl)
    c.setUsername(User)
    c.setPassword(Password)
    c.setMaximumPoolSize(10)
    // Flowable's DDL/SQL is unqualified -> search_path decides where its ACT_* tables live.
    // Our business tables are schema-qualified (public.*), so they are unaffected.
    c.setSchema(FlowableSchema)
    c.setPoolName("spike")
    HikariDataSource(c)

  /** Standalone configuration (no Spring). `sharedTx = true` installs the shared-transaction glue. */
  def build(ds: DataSource, sharedTx: Boolean, asyncExecutor: Boolean = true): ProcessEngine =
    val cfg =
      if sharedTx then
        new StandaloneProcessEngineConfiguration:
          override def createTransactionInterceptor(): CommandInterceptor = JdbcTransactionInterceptor(ds)
      else StandaloneProcessEngineConfiguration()

    cfg.setEngineName(if sharedTx then "shared-tx" else "plain")
    cfg.setDataSource(if sharedTx then BoundDataSource(ds) else ds)
    cfg.setDatabaseSchema(FlowableSchema)
    cfg.setDatabaseSchemaUpdate(AbstractEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
    // Users/groups come from our identity module, not Flowable IDM; event registry (FLW_*) is unused.
    cfg.setDisableIdmEngine(sys.env.get("SPIKE_FULL_ENGINE").isEmpty)
    cfg.setDisableEventRegistry(sys.env.get("SPIKE_FULL_ENGINE").isEmpty)
    cfg.setIdGenerator(StrongUuidGenerator()) // avoid DbIdGenerator's REQUIRES_NEW id-block transactions
    cfg.setAsyncExecutorActivate(asyncExecutor)
    // Acquisition polling interval (default 10 s). Short for the test so the PT3S timer fires promptly.
    cfg.getAsyncExecutorConfiguration.setDefaultTimerJobAcquireWaitTime(java.time.Duration.ofMillis(500))
    cfg.getAsyncExecutorConfiguration.setDefaultAsyncJobAcquireWaitTime(java.time.Duration.ofMillis(500))
    cfg.setHistoryLevel(org.flowable.common.engine.impl.history.HistoryLevel.AUDIT)
    if sharedTx then
      cfg.setTransactionsExternallyManaged(true) // MyBatis ManagedTransaction: no commit/close on the connection
      cfg.setTransactionContextFactory(ExternalTransactionContextFactory())
    cfg.buildProcessEngine()
