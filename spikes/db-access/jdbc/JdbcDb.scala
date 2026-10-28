package spike.jdbc

import spike.common.*
import zio.*

import java.sql.{Connection, PreparedStatement, ResultSet}
import java.util.UUID
import javax.sql.DataSource
import scala.util.Using

/** Baseline: plain JDBC with the smallest useful helpers. */
final class JdbcDb(ds: DataSource):
  def transact[R, A](tenant: Option[TenantId])(body: ZIO[R & Connection, DbError, A]): ZIO[R, DbError, A] =
    ZIO.scoped[R] {
      for
        con <- ZIO.acquireRelease(ZIO.attemptBlocking {
                 val c = ds.getConnection
                 c.setAutoCommit(false)
                 tenant.foreach(t => JdbcDb.query(c, "select set_config('app.tenant_id', ?, true)", t.value.toString)(_.getString(1)))
                 c
               }.mapError(DbError.from))(c => ZIO.attemptBlocking { c.rollback(); c.close() }.orDie)
        a   <- body.provideSomeLayer[R](ZLayer.succeed(con))
        _   <- ZIO.attemptBlocking(con.commit()).mapError(DbError.from)
      yield a
    }

object JdbcDb:
  def run[A](f: Connection => A): ZIO[Connection, DbError, A] =
    ZIO.serviceWithZIO[Connection](c => ZIO.attemptBlocking(f(c)).mapError(DbError.from))

  def query[A](c: Connection, sql: String, params: Any*)(read: ResultSet => A): Vector[A] =
    Using.resource(c.prepareStatement(sql)) { ps =>
      bind(ps, params)
      Using.resource(ps.executeQuery()) { rs =>
        val b = Vector.newBuilder[A]
        while rs.next() do b += read(rs)
        b.result()
      }
    }

  def update(c: Connection, sql: String, params: Any*): Int =
    Using.resource(c.prepareStatement(sql)) { ps => bind(ps, params); ps.executeUpdate() }

  private def bind(ps: PreparedStatement, params: Seq[Any]): Unit =
    params.zipWithIndex.foreach {
      case (Jsonb(json), i) =>
        val o = org.postgresql.util.PGobject(); o.setType("jsonb"); o.setValue(json); ps.setObject(i + 1, o)
      case (p, i) => ps.setObject(i + 1, p)
    }

final case class Jsonb(value: String)
final case class Document(id: UUID, tenantId: UUID, title: String, metadata: Jsonb)

object DocumentRepo:
  private def read(rs: ResultSet) =
    Document(rs.getObject(1, classOf[UUID]), rs.getObject(2, classOf[UUID]), rs.getString(3), Jsonb(rs.getString(4)))

  def insert(t: TenantId, title: String, metadata: Jsonb)(c: Connection): UUID =
    JdbcDb.query(c, "insert into document (tenant_id, title, metadata) values (?, ?, ?) returning id", t.value, title, metadata)(
      _.getObject(1, classOf[UUID])).head

  def findAll(c: Connection): Vector[Document] =
    JdbcDb.query(c, "select id, tenant_id, title, metadata from document order by title")(read)

  def byMetadata(key: String, value: String)(c: Connection): Vector[Document] =
    JdbcDb.query(c, "select id, tenant_id, title, metadata from document where metadata->>? = ? order by title", key, value)(read)
