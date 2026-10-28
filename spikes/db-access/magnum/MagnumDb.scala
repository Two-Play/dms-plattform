package spike.magnum

import com.augustnagro.magnum.*
import spike.common.*
import zio.*

import java.sql.{Connection, PreparedStatement, ResultSet, Types}
import javax.sql.DataSource

/** JSONB as raw JSON text; JSON library left out on purpose.
  * Opaque type on purpose: with `case class Jsonb(value: String)` the sql"..."
  * interpolator picked Magnum's `inline given derived[E: Mirror.Of]` (setString)
  * instead of the companion codec, see README "Problems".
  */
opaque type Jsonb = String
object Jsonb:
  def apply(json: String): Jsonb = json
  extension (j: Jsonb) def value: String = j
  given DbCodec[Jsonb] with
    def queryRepr: String   = "?"
    val cols: IArray[Int]   = IArray(Types.OTHER)
    def readSingle(rs: ResultSet, pos: Int): Jsonb = rs.getString(pos)
    def writeSingle(j: Jsonb, ps: PreparedStatement, pos: Int): Unit =
      val o = org.postgresql.util.PGobject()
      o.setType("jsonb"); o.setValue(j)
      ps.setObject(pos, o)

/** Thin ZIO wrapper around Magnum (the "100-200 lines" from ADR 0012; here ~50). */
final class MagnumDb(ds: DataSource):

  /** Variant 1: transaction as a ZIO scoped resource; the body is a ZIO effect
    * that may call [[MagnumDb.run]] several times and do other ZIO work in between.
    */
  def transact[R, A](tenant: Option[TenantId])(body: ZIO[R & DbTx, DbError, A]): ZIO[R, DbError, A] =
    ZIO.scoped[R] {
      for
        tx <- acquire(tenant)
        a  <- body.provideSomeLayer[R](ZLayer.succeed(tx))
        _  <- ZIO.attemptBlocking(tx.connection.commit()).mapError(DbError.from)
      yield a
    }

  /** Variant 2: idiomatic Magnum – whole transaction is one blocking block. */
  def transactBlocking[A](tenant: Option[TenantId])(f: DbTx ?=> A): IO[DbError, A] =
    ZIO.attemptBlocking(com.augustnagro.magnum.transact(ds) {
      tenant.foreach(MagnumDb.setTenant)
      f
    }).mapError(DbError.from)

  private def acquire(tenant: Option[TenantId]): ZIO[Scope, DbError, DbTx] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking {
        val con = ds.getConnection
        try
          con.setAutoCommit(false)
          val tx = MagnumBridge.dbTx(con)
          tenant.foreach(t => MagnumDb.setTenant(t)(using tx))
          tx
        catch
          case e: Throwable =>
            con.close()
            throw e
      }.mapError(DbError.from)
    )(tx =>
      ZIO.attemptBlocking {
        tx.connection.rollback() // no-op after a successful commit
        tx.connection.close()
      }.orDie
    )

object MagnumDb:
  val layer: ZLayer[DataSource, Nothing, MagnumDb] = ZLayer.fromFunction(MagnumDb(_))

  /** SET LOCAL cannot take a bind parameter; set_config(..., is_local = true) is equivalent. */
  def setTenant(t: TenantId)(using DbTx): Unit =
    sql"select set_config('app.tenant_id', ${t.value.toString}, true)".query[String].run()

  /** Runs blocking Magnum code against the transaction in the environment. */
  def inTx[A](f: DbTx ?=> A): ZIO[DbTx, DbError, A] =
    ZIO.serviceWithZIO[DbTx](tx => ZIO.attemptBlocking(f(using tx)).mapError(DbError.from))

  /** Joins a transaction owned outside Magnum (SharedTx / Flowable's Tx): the externally supplied
    * Connection is wrapped into a DbTx. Magnum never commits/closes it.
    */
  def joined[A](f: DbTx ?=> A): IO[DbError, A] =
    SharedTx.ref.get.someOrFail(DbError.Other("XX000", "no shared transaction")).flatMap(con =>
      ZIO.attemptBlocking(f(using MagnumBridge.dbTx(con))).mapError(DbError.from))

  /** Hands the raw JDBC connection of the current transaction to another component (Flowable). */
  def withConnection[A](f: Connection => A): ZIO[DbTx, DbError, A] =
    inTx(f(summon[DbTx].connection))
