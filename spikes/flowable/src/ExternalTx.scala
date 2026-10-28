package spike.flowable

import org.flowable.common.engine.impl.cfg.{TransactionContext, TransactionContextFactory, TransactionListener, TransactionPropagation, TransactionState}
import org.flowable.common.engine.impl.interceptor.{AbstractCommandInterceptor, Command, CommandConfig, CommandContext, CommandExecutor}

import java.io.PrintWriter
import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource
import scala.collection.mutable.ListBuffer

/** One JDBC transaction (one physical connection, autoCommit = false) that can be bound to the current thread.
  * Both our own JDBC code and Flowable (via [[BoundDataSource]]) use the bound connection, so they share the
  * transaction. Flowable's transaction listeners are deferred until the real commit/rollback.
  */
final class ExternalTx(val connection: Connection):
  @volatile var rollbackOnly: Boolean = false
  @volatile var completed: Boolean    = false
  private val listeners = ListBuffer.empty[(TransactionState, TransactionListener, CommandContext)]

  def addListener(state: TransactionState, l: TransactionListener, ctx: CommandContext): Unit =
    listeners.synchronized(listeners += ((state, l, ctx)))

  def fireAll(states: TransactionState*): Unit = states.foreach(fire)

  private def fire(state: TransactionState): Unit =
    val ls = listeners.synchronized(listeners.filter(_._1 == state).toList)
    ls.foreach((_, l, ctx) => l.execute(ctx))

  def commit(): Unit =
    if rollbackOnly then
      rollback()
      throw IllegalStateException("transaction was marked rollback-only by Flowable")
    else
      fire(TransactionState.COMMITTING)
      connection.commit()
      completed = true
      // Listeners like "async job added -> hand to executor" must only see committed data.
      ExternalTx.withUnbound(fire(TransactionState.COMMITTED))

  def rollback(): Unit = if !completed then
    completed = true
    fire(TransactionState.ROLLINGBACK)
    connection.rollback()
    ExternalTx.withUnbound(fire(TransactionState.ROLLED_BACK))

object ExternalTx:
  private val current = new ThreadLocal[ExternalTx]

  def bound: Option[ExternalTx] = Option(current.get)

  /** Runs `f` with `tx` bound to the current thread (restores the previous binding afterwards). */
  def withBound[A](tx: ExternalTx)(f: => A): A =
    val prev = current.get
    current.set(tx)
    try f
    finally current.set(prev)

  def withUnbound[A](f: => A): A =
    val prev = current.get
    current.remove()
    try f
    finally current.set(prev)

  /** Opens a new transaction on a fresh pooled connection, runs `f`, commits or rolls back, closes. */
  def runInNew[A](ds: DataSource)(f: ExternalTx => A): A =
    val conn = ds.getConnection
    conn.setAutoCommit(false)
    val tx = ExternalTx(conn)
    try
      val result =
        try withBound(tx)(f(tx))
        catch
          case t: Throwable =>
            try withBound(tx)(tx.rollback())
            catch case r: Throwable => t.addSuppressed(r)
            throw t
      withBound(tx)(tx.commit())
      result
    finally
      conn.setAutoCommit(true)
      conn.close()

/** DataSource handed to Flowable: returns the thread-bound transactional connection (close() suppressed),
  * or a plain pooled connection if nothing is bound (only happens for NOT_SUPPORTED commands, e.g. schema DDL).
  */
final class BoundDataSource(target: DataSource) extends DataSource:
  def getConnection: Connection = ExternalTx.bound match
    case Some(tx) => nonClosing(tx.connection)
    case None     => target.getConnection
  def getConnection(u: String, p: String): Connection = getConnection

  private def nonClosing(c: Connection): Connection =
    Proxy
      .newProxyInstance(
        classOf[Connection].getClassLoader,
        Array(classOf[Connection]),
        new InvocationHandler:
          def invoke(proxy: Any, m: Method, args: Array[AnyRef]): AnyRef = m.getName match
            case "close"                         => null // owned by ExternalTx
            case "commit" | "rollback"           => null // owned by ExternalTx
            case "setAutoCommit"                 => null
            case _ =>
              try m.invoke(c, (if args == null then Array.empty[AnyRef] else args)*)
              catch case e: java.lang.reflect.InvocationTargetException => throw e.getCause
      )
      .asInstanceOf[Connection]

  def getLogWriter: PrintWriter                  = target.getLogWriter
  def setLogWriter(out: PrintWriter): Unit       = target.setLogWriter(out)
  def setLoginTimeout(seconds: Int): Unit        = target.setLoginTimeout(seconds)
  def getLoginTimeout: Int                       = target.getLoginTimeout
  def getParentLogger: Logger                    = target.getParentLogger
  def unwrap[T](iface: Class[T]): T              = target.unwrap(iface)
  def isWrapperFor(iface: Class[?]): Boolean     = target.isWrapperFor(iface)

/** Plays the role Spring's SpringTransactionInterceptor plays: joins the thread-bound transaction (REQUIRED)
  * or opens/commits a new one. Used by every Flowable command, including the async executor's job threads.
  */
final class JdbcTransactionInterceptor(ds: DataSource) extends AbstractCommandInterceptor:
  def execute[T](config: CommandConfig, command: Command[T], executor: CommandExecutor): T =
    config.getTransactionPropagation match
      case TransactionPropagation.REQUIRED if ExternalTx.bound.isDefined => next.execute(config, command, executor)
      case TransactionPropagation.NOT_SUPPORTED => ExternalTx.withUnbound(next.execute(config, command, executor))
      case _ /* REQUIRED without tx, REQUIRES_NEW */ =>
        ExternalTx.runInNew(ds)(_ => next.execute(config, command, executor))

/** Flowable's per-command TransactionContext: commit/rollback are decided by ExternalTx, listeners deferred. */
final class ExternalTransactionContextFactory extends TransactionContextFactory:
  def openTransactionContext(ctx: CommandContext): TransactionContext = ExternalTx.bound match
    case Some(tx) =>
      new TransactionContext:
        def commit(): Unit   = () // real commit happens in ExternalTx.commit()
        def rollback(): Unit = tx.rollbackOnly = true
        def addTransactionListener(state: TransactionState, l: TransactionListener): Unit = tx.addListener(state, l, ctx)
    case None => // NOT_SUPPORTED commands (schema management): auto-commit connection, fire listeners inline
      val own = ExternalTx(null)
      new TransactionContext:
        def commit(): Unit   = own.fireAll(TransactionState.COMMITTING, TransactionState.COMMITTED)
        def rollback(): Unit = own.fireAll(TransactionState.ROLLINGBACK, TransactionState.ROLLED_BACK)
        def addTransactionListener(state: TransactionState, l: TransactionListener): Unit = own.addListener(state, l, ctx)
