#!/usr/bin/env python3
"""Generates 10 repositories (5 typical queries each) per candidate for the compile-time benchmark."""
import pathlib

ROOT = pathlib.Path(__file__).parent
N = 10

def magnum():
    d = ROOT / "magnum"; d.mkdir(exist_ok=True)
    (d / "project.scala").write_text(
        "//> using scala 3.9.0\n//> using jvm temurin:21\n//> using dep com.augustnagro::magnum:1.3.1\n"
        "//> using dep dev.zio::zio:2.1.27\n//> using dep org.postgresql:postgresql:42.7.14\n//> using dep com.zaxxer:HikariCP:7.1.0\n"
        "//> using file ../../common/Common.scala\n//> using file ../../common/SharedTx.scala\n//> using file ../../magnum/MagnumDb.scala\n//> using file ../../magnum/MagnumBridge.scala\n")
    for i in range(N):
        (d / f"Repo{i}.scala").write_text(f"""package bench.magnum
import com.augustnagro.magnum.*
import spike.magnum.Jsonb
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
final case class Entity{i}(@Id id: UUID, tenantId: UUID, name: String, status: String, size: Long, meta: Jsonb) derives DbCodec
final case class Entity{i}Creator(tenantId: UUID, name: String, status: String, size: Long, meta: Jsonb) derives DbCodec

object Repo{i}:
  private val repo = Repo[Entity{i}Creator, Entity{i}, UUID]
  def insert(e: Entity{i}Creator)(using DbCon): Entity{i} = repo.insertReturning(e)
  def byId(id: UUID)(using DbCon): Option[Entity{i}] = repo.findById(id)
  def byStatus(status: String, limit: Int)(using DbCon): Vector[Entity{i}] =
    sql"select id, tenant_id, name, status, size, meta from entity{i} where status = $status order by name limit $limit".query[Entity{i}].run()
  def byMeta(key: String, value: String)(using DbCon): Vector[Entity{i}] =
    sql"select id, tenant_id, name, status, size, meta from entity{i} where meta->>$key = $value".query[Entity{i}].run()
  def rename(id: UUID, name: String)(using DbCon): Int =
    sql"update entity{i} set name = $name where id = $id".update.run()
""")

def quill():
    d = ROOT / "quill"; d.mkdir(exist_ok=True)
    (d / "project.scala").write_text(
        "//> using scala 3.9.0\n//> using jvm temurin:21\n//> using dep io.getquill::quill-jdbc-zio:4.8.6\n"
        "//> using dep dev.zio::zio:2.1.27\n//> using dep org.postgresql:postgresql:42.7.14\n//> using dep com.zaxxer:HikariCP:7.1.0\n"
        "//> using file ../../common/Common.scala\n//> using file ../../common/SharedTx.scala\n//> using file ../../quill/QuillDb.scala\n")
    for i in range(N):
        (d / f"Repo{i}.scala").write_text(f"""package bench.quill
import io.getquill.*
import spike.quill.Ctx
import zio.*
import zio.json.ast.Json
import java.sql.SQLException
import java.util.UUID

final case class Entity{i}(id: UUID, tenantId: UUID, name: String, status: String, size: Long, meta: JsonbValue[Json])

final class Repo{i}(val ctx: Ctx):
  import ctx.*
  def insert(e: Entity{i}): IO[SQLException, UUID] =
    run(query[Entity{i}].insert(_.tenantId -> lift(e.tenantId), _.name -> lift(e.name), _.status -> lift(e.status), _.size -> lift(e.size), _.meta -> lift(e.meta)).returning(_.id))
  def byId(id: UUID): IO[SQLException, Option[Entity{i}]] = run(query[Entity{i}].filter(_.id == lift(id))).map(_.headOption)
  def byStatus(status: String, limit: Int): IO[SQLException, List[Entity{i}]] =
    run(query[Entity{i}].filter(_.status == lift(status)).sortBy(_.name).take(lift(limit)))
  def byMeta(key: String, value: String): IO[SQLException, List[Entity{i}]] =
    run(query[Entity{i}].filter(e => sql"${{e.meta}}->>${{lift(key)}}".as[String] == lift(value)))
  def rename(id: UUID, name: String): IO[SQLException, Long] =
    run(query[Entity{i}].filter(_.id == lift(id)).update(_.name -> lift(name)))
""")

def jdbc():
    d = ROOT / "jdbc"; d.mkdir(exist_ok=True)
    (d / "project.scala").write_text(
        "//> using scala 3.9.0\n//> using jvm temurin:21\n"
        "//> using dep dev.zio::zio:2.1.27\n//> using dep org.postgresql:postgresql:42.7.14\n//> using dep com.zaxxer:HikariCP:7.1.0\n"
        "//> using file ../../common/Common.scala\n//> using file ../../common/SharedTx.scala\n//> using file ../../jdbc/JdbcDb.scala\n")
    for i in range(N):
        (d / f"Repo{i}.scala").write_text(f"""package bench.jdbc
import spike.jdbc.{{JdbcDb, Jsonb}}
import java.sql.{{Connection, ResultSet}}
import java.util.UUID

final case class Entity{i}(id: UUID, tenantId: UUID, name: String, status: String, size: Long, meta: Jsonb)

object Repo{i}:
  private val cols = "id, tenant_id, name, status, size, meta"
  private def read(rs: ResultSet) = Entity{i}(rs.getObject(1, classOf[UUID]), rs.getObject(2, classOf[UUID]), rs.getString(3), rs.getString(4), rs.getLong(5), Jsonb(rs.getString(6)))
  def insert(e: Entity{i})(c: Connection): UUID =
    JdbcDb.query(c, "insert into entity{i} (tenant_id, name, status, size, meta) values (?, ?, ?, ?, ?) returning id", e.tenantId, e.name, e.status, e.size, e.meta)(_.getObject(1, classOf[UUID])).head
  def byId(id: UUID)(c: Connection): Option[Entity{i}] = JdbcDb.query(c, s"select $cols from entity{i} where id = ?", id)(read).headOption
  def byStatus(status: String, limit: Int)(c: Connection): Vector[Entity{i}] =
    JdbcDb.query(c, s"select $cols from entity{i} where status = ? order by name limit ?", status, limit)(read)
  def byMeta(key: String, value: String)(c: Connection): Vector[Entity{i}] =
    JdbcDb.query(c, s"select $cols from entity{i} where meta->>? = ?", key, value)(read)
  def rename(id: UUID, name: String)(c: Connection): Int = JdbcDb.update(c, "update entity{i} set name = ? where id = ?", name, id)
""")

magnum(); quill(); jdbc()
