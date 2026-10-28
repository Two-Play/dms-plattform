package dms.workflow.core

import org.flowable.common.engine.impl.cfg.{
  TransactionContext,
  TransactionContextFactory,
  TransactionListener,
  TransactionPropagation,
  TransactionState
}
import org.flowable.common.engine.impl.interceptor.{
  AbstractCommandInterceptor,
  Command,
  CommandConfig,
  CommandContext,
  CommandExecutor
}

import java.io.PrintWriter
import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource
import scala.collection.mutable.ListBuffer

/** Lets Flowable join the transaction of `dms.db.Db.transact` (ADR 0006 spike DMS-22, ADR 0024).
  *
  * This file is the only place that uses Flowable `impl` packages. It plays the role of Flowable's Spring
  * integration: every engine command runs on the JDBC connection of the caller's transaction, which is bound
  * to the executing thread for the duration of one engine call ([[SharedTransaction.joined]]). Flowable
  * neither commits nor closes that connection; `Db.transact` does. Covered by `SharedTransactionSpec`, which
  * must pass on every Flowable upgrade.
  *
  * Flowable's transaction listeners are collected per engine call and fired when the call returns:
  * `COMMITTING` and `COMMITTED` after success, `ROLLED_BACK` after a failure. They therefore fire before the
  * outer commit. That is safe as long as the async executor is off (ADR 0024); with timers it has to move to
  * a commit hook of `Db`.
  */
object SharedTransaction:

  /** The transaction of the current thread: a connection owned by someone else plus Flowable's state. */
  final class Joined(val connection: Connection):
    @volatile var rollbackOnly: Boolean = false
    private val listeners = ListBuffer.empty[(TransactionState, TransactionListener, CommandContext)]

    def add(state: TransactionState, listener: TransactionListener, context: CommandContext): Unit =
      listeners.synchronized { listeners += ((state, listener, context)); () }

    def fire(state: TransactionState): Unit =
      val matching = listeners.synchronized(listeners.filter(_._1 == state).toList)
      matching.foreach((_, listener, context) => listener.execute(context))

  private val current = new ThreadLocal[Joined]

  def bound: Option[Joined] = Option(current.get)

  /** Thrown when Flowable marked the transaction rollback-only but the failure was swallowed. */
  final class RollbackOnly
      extends RuntimeException("the workflow engine marked the transaction rollback-only")

  /** Runs `f` (engine calls) on `connection`, which belongs to the caller's open transaction. Fails if
    * Flowable marked the transaction rollback-only, so that the caller rolls back.
    */
  def joined[A](connection: Connection)(f: => A): A =
    val tx       = Joined(connection)
    val previous = current.get
    current.set(tx)
    try
      val result =
        try f
        catch
          case t: Throwable =>
            tx.rollbackOnly = true
            throw t
      if tx.rollbackOnly then throw RollbackOnly()
      tx.fire(TransactionState.COMMITTING)
      tx.fire(TransactionState.COMMITTED)
      result
    catch
      case t: Throwable =>
        try tx.fire(TransactionState.ROLLED_BACK)
        catch case r: Throwable => t.addSuppressed(r)
        throw t
    finally if previous == null then current.remove() else current.set(previous)

  private def unbound[A](f: => A): A =
    val previous = current.get
    current.remove()
    try f
    finally if previous != null then current.set(previous)

  /** A transaction of its own on a fresh connection, for engine work outside a request (engine boot). */
  private def ownTransaction[A](dataSource: DataSource)(f: => A): A =
    val connection = dataSource.getConnection
    try
      connection.setAutoCommit(false)
      val result =
        try joined(connection)(f)
        catch
          case t: Throwable =>
            connection.rollback()
            throw t
      connection.commit()
      result
    finally
      connection.setAutoCommit(true)
      connection.close()

  /** The data source handed to Flowable: the bound connection (commit, rollback and close suppressed), or a
    * pooled connection when nothing is bound (only for `NOT_SUPPORTED` commands such as the schema check).
    */
  final class BoundDataSource(target: DataSource) extends DataSource:
    def getConnection: Connection = bound match
      case Some(tx) => borrowed(tx.connection)
      case None     => target.getConnection

    def getConnection(user: String, password: String): Connection = getConnection

    private def borrowed(connection: Connection): Connection =
      Proxy
        .newProxyInstance(
          classOf[Connection].getClassLoader,
          Array(classOf[Connection]),
          new InvocationHandler:
            def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef = method.getName match
              case "close" | "commit" | "rollback" | "setAutoCommit" => null
              case _                                                 =>
                try method.invoke(connection, (if args == null then Array.empty[AnyRef] else args)*)
                catch case e: InvocationTargetException => throw e.getCause
        )
        .asInstanceOf[Connection]

    def getLogWriter: PrintWriter              = target.getLogWriter
    def setLogWriter(out: PrintWriter): Unit   = target.setLogWriter(out)
    def setLoginTimeout(seconds: Int): Unit    = target.setLoginTimeout(seconds)
    def getLoginTimeout: Int                   = target.getLoginTimeout
    def getParentLogger: Logger                = target.getParentLogger
    def unwrap[T](iface: Class[T]): T          = target.unwrap(iface)
    def isWrapperFor(iface: Class[?]): Boolean = target.isWrapperFor(iface)

  /** Joins the bound transaction (`REQUIRED`), suspends it (`NOT_SUPPORTED`) or opens one of its own. */
  final class Interceptor(dataSource: DataSource) extends AbstractCommandInterceptor:
    def execute[T](config: CommandConfig, command: Command[T], executor: CommandExecutor): T =
      config.getTransactionPropagation match
        case TransactionPropagation.REQUIRED if bound.isDefined =>
          next.execute(config, command, executor)
        case TransactionPropagation.NOT_SUPPORTED =>
          unbound(next.execute(config, command, executor))
        case _ =>
          unbound(ownTransaction(dataSource)(next.execute(config, command, executor)))

  /** Flowable's per-command transaction context: commit and rollback are decided by the owner of the
    * connection; listeners are deferred to the end of the engine call.
    */
  final class ContextFactory extends TransactionContextFactory:
    def openTransactionContext(context: CommandContext): TransactionContext = bound match
      case Some(tx) =>
        new TransactionContext:
          def commit(): Unit   = ()
          def rollback(): Unit = tx.rollbackOnly = true
          def addTransactionListener(state: TransactionState, listener: TransactionListener): Unit =
            tx.add(state, listener, context)
      case None =>
        // NOT_SUPPORTED commands run on an auto-commit connection; listeners fire right away.
        val own = Joined(null)
        new TransactionContext:
          def commit(): Unit =
            own.fire(TransactionState.COMMITTING)
            own.fire(TransactionState.COMMITTED)
          def rollback(): Unit =
            own.fire(TransactionState.ROLLINGBACK)
            own.fire(TransactionState.ROLLED_BACK)
          def addTransactionListener(state: TransactionState, listener: TransactionListener): Unit =
            own.add(state, listener, context)
