package spike.common

import zio.*

import java.sql.Connection
import javax.sql.DataSource

/** Stand-in for the Flowable spike's `Tx`/`ExternalTx` (spikes/flowable/src): the transaction is owned
  * OUTSIDE the DB library – exactly one java.sql.Connection per transaction, kept in a FiberRef.
  * The question for each candidate: can its queries join this connection?
  */
object SharedTx:
  val ref: FiberRef[Option[Connection]] = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(None))

  def transact[R, A](ds: DataSource, tenant: Option[TenantId])(zio: ZIO[R, DbError, A]): ZIO[R, DbError, A] =
    ZIO.acquireReleaseExitWith(
      ZIO.attemptBlocking {
        val c = ds.getConnection
        c.setAutoCommit(false)
        tenant.foreach { t =>
          val ps = c.prepareStatement("select set_config('app.tenant_id', ?, true)")
          ps.setString(1, t.value.toString); ps.executeQuery().close()
        }
        c
      }.mapError(DbError.from)
    )((c: Connection, exit: Exit[Any, Any]) =>
      ZIO.attemptBlocking { if exit.isFailure then c.rollback(); c.setAutoCommit(true); c.close() }.orDie
    )(c => ref.locally(Some(c))(zio).flatMap(a => ZIO.attemptBlocking(c.commit()).mapError(DbError.from).as(a)))

  /** What Flowable does: plain JDBC on the bound connection. */
  def engineWrite(note: String): IO[DbError, Int] =
    ref.get.someOrFail(DbError.Other("XX000", "no shared transaction")).flatMap(c =>
      ZIO.attemptBlocking {
        val ps = c.prepareStatement("insert into engine_record (note) values (?)")
        ps.setString(1, note); ps.executeUpdate()
      }.mapError(DbError.from))

  def backendPid: IO[DbError, Int] =
    ref.get.someOrFail(DbError.Other("XX000", "no shared transaction")).flatMap(c =>
      ZIO.attemptBlocking { val rs = c.createStatement().executeQuery("select pg_backend_pid()"); rs.next(); rs.getInt(1) }
        .mapError(DbError.from))
