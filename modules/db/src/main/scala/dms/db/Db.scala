package dms.db

import com.augustnagro.magnum.{DbTx, MagnumBridge}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import dms.kernel.TenantId
import zio.*

import java.sql.Connection
import javax.sql.DataSource

final case class DbConfig(url: String, user: String, password: String, poolSize: Int = 10)

/** Owns database transactions (ADR 0012).
  *
  * One JDBC connection per transaction, held in a FiberRef so that Magnum queries and Flowable (ADR 0006)
  * join the same transaction. The tenant is set with `set_config('app.tenant_id', ?, true)`, which is
  * transaction-local; row-level security policies fail closed when it is missing (ADR 0014).
  *
  * Rule: do not fork fibers inside a transaction. Forked fibers inherit the connection, and JDBC connections
  * are not safe for concurrent use.
  *
  * Side effects that must only happen once the transaction is durable (or only after it was rolled back) are
  * registered with [[Db.afterCommit]] / [[Db.afterRollback]] (DMS-87, ADR 0053), e.g. Flowable's transaction
  * listeners. They run after the connection is released, in registration order; their failures are logged and
  * never change the outcome of the transaction.
  */
final class Db(dataSource: DataSource):

  /** Runs `body` in a transaction for `tenant`. Commits on success, rolls back on any failure or
    * interruption.
    */
  def transact[R, E, A](tenant: TenantId)(body: ZIO[R, E, A]): ZIO[R, E | DbError, A] =
    run(Some(tenant))(body)

  /** Transaction without tenant context, for system work on tables without row-level security. Accessing a
    * tenant-scoped table inside it fails with [[DbError.TenantContextMissing]].
    */
  def transactSystem[R, E, A](body: ZIO[R, E, A]): ZIO[R, E | DbError, A] =
    run(None)(body)

  private def run[R, E, A](tenant: Option[TenantId])(body: ZIO[R, E, A]): ZIO[R, E | DbError, A] =
    val acquire: ZIO[R, E | DbError, Connection] = open(tenant)
    Ref.make(Db.Hooks.empty).flatMap { hooks =>
      ZIO.acquireReleaseExitWith(acquire)((connection, exit) =>
        close(connection, exit.isSuccess) *> hooks.get.flatMap(_.run(committed = exit.isSuccess))
      ) { connection =>
        Db.current.locally(Some(connection))(Db.hooks.locally(Some(hooks))(body)).flatMap { a =>
          ZIO.attemptBlocking(connection.commit()).mapError(DbError.from).as(a)
        }
      }
    }

  private def open(tenant: Option[TenantId]): IO[DbError, Connection] =
    ZIO
      .attemptBlocking {
        val connection = dataSource.getConnection
        try
          connection.setAutoCommit(false)
          tenant.foreach { t =>
            val statement = connection.prepareStatement("select set_config('app.tenant_id', ?, true)")
            try
              statement.setString(1, t.value.toString)
              statement.executeQuery().close()
            finally statement.close()
          }
          connection
        catch
          case e: Throwable =>
            connection.close()
            throw e
      }
      .mapError(DbError.from)

  private def close(connection: Connection, committed: Boolean): UIO[Unit] =
    ZIO.attemptBlocking {
      try if !committed then connection.rollback()
      finally connection.close()
    }.orDie

object Db:
  /** The connection of the current transaction, if any. */
  val current: FiberRef[Option[Connection]] = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(None))

  /** Effects of the current transaction to run after its commit or its rollback. */
  private[db] final case class Hooks(afterCommit: Vector[UIO[Unit]], afterRollback: Vector[UIO[Unit]]):
    def run(committed: Boolean): UIO[Unit] =
      ZIO.foreachDiscard(if committed then afterCommit else afterRollback)(
        _.catchAllCause(cause =>
          ZIO.logWarningCause(
            s"A hook after the ${if committed then "commit" else "rollback"} of a transaction failed",
            cause
          )
        )
      )

  private[db] object Hooks:
    val empty: Hooks = Hooks(Vector.empty, Vector.empty)

  private val hooks: FiberRef[Option[Ref[Hooks]]] = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(None))

  /** Runs `effect` once the current transaction has committed (not if it rolls back). Fails outside a
    * transaction. The effect runs without the transaction's connection; it must not assume the caller's fiber
    * context beyond that.
    */
  def afterCommit(effect: UIO[Unit]): IO[DbError, Unit] =
    hooks.get
      .someOrFail(DbError.NoTransaction)
      .flatMap(_.update(h => h.copy(afterCommit = h.afterCommit :+ effect)))

  /** Runs `effect` once the current transaction has rolled back (also when its commit failed). */
  def afterRollback(effect: UIO[Unit]): IO[DbError, Unit] =
    hooks.get
      .someOrFail(DbError.NoTransaction)
      .flatMap(_.update(h => h.copy(afterRollback = h.afterRollback :+ effect)))

  /** Runs Magnum code on the current transaction's connection, on the blocking thread pool. */
  def sql[A](f: DbTx ?=> A): IO[DbError, A] =
    connection.flatMap(c => ZIO.attemptBlocking(f(using MagnumBridge.dbTx(c))).mapError(DbError.from))

  /** The raw connection of the current transaction, for components that join it (Flowable). */
  def connection: IO[DbError, Connection] =
    current.get.someOrFail(DbError.NoTransaction)

  def dataSource(config: DbConfig): ZLayer[Any, Throwable, DataSource] =
    ZLayer.scoped(ZIO.fromAutoCloseable(ZIO.attemptBlocking {
      val hikari = HikariConfig()
      hikari.setJdbcUrl(config.url)
      hikari.setUsername(config.user)
      hikari.setPassword(config.password)
      hikari.setMaximumPoolSize(config.poolSize)
      hikari.setAutoCommit(true)
      hikari.setPoolName("dms")
      HikariDataSource(hikari)
    }))

  val layer: ZLayer[DataSource, Nothing, Db] = ZLayer.fromFunction(Db(_))
