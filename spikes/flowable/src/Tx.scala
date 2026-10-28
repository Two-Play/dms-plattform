package spike.flowable

import zio.*

import java.sql.Connection
import javax.sql.DataSource

enum TxError:
  case RolledBack(reason: String)
  case Db(cause: Throwable)

/** ZIO side of the shared transaction: the ExternalTx lives in a FiberRef and is bound to whatever
  * blocking-pool thread executes a step, so ZIO code can hop threads between steps.
  */
object Tx:
  private val ref: FiberRef[Option[ExternalTx]] = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(None))

  /** Run a blocking JDBC/Flowable call; joins the fiber's transaction if there is one. */
  def blocking[A](f: => A): Task[A] =
    ref.get.flatMap {
      case Some(tx) => ZIO.attemptBlocking(ExternalTx.withBound(tx)(f))
      case None     => ZIO.attemptBlocking(f)
    }

  /** Own JDBC access: requires a transaction. */
  def withConnection[A](f: Connection => A): Task[A] =
    ref.get.flatMap {
      case Some(tx) => ZIO.attemptBlocking(f(tx.connection))
      case None     => ZIO.dieMessage("withConnection outside Tx.transact")
    }

  /** Everything inside `zio` (own JDBC + Flowable commands) commits or rolls back together. */
  def transact[R, E, A](ds: DataSource)(zio: ZIO[R, E, A]): ZIO[R, E | TxError, A] =
    ZIO.acquireReleaseExitWith[R, E | TxError, ExternalTx](
      ZIO.attemptBlocking { val c = ds.getConnection; c.setAutoCommit(false); ExternalTx(c) }.mapError(TxError.Db(_))
    )((tx, exit) =>
      ZIO.attemptBlocking { if exit.isFailure then ExternalTx.withBound(tx)(tx.rollback()); tx.connection.setAutoCommit(true); tx.connection.close() }.orDie
    ) { tx =>
      ref.locally(Some(tx))(zio: ZIO[R, E | TxError, A]).flatMap { a =>
        ZIO
          .attemptBlocking(ExternalTx.withBound(tx)(tx.commit()))
          .mapError(e => TxError.RolledBack(e.getMessage))
          .as(a)
      }
    }
