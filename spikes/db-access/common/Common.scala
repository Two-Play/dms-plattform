package spike.common

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import zio.*

import java.sql.SQLException
import java.util.UUID

/** Typed DB errors every candidate maps to (criterion 4). */
enum DbError:
  /** Trigger raised an exception (SQLSTATE P0001), e.g. append-only audit. */
  case AppendOnlyViolation(message: String)
  /** Missing privilege or RLS WITH CHECK violation (SQLSTATE 42501). */
  case PermissionDenied(message: String)
  case Other(sqlState: String, message: String)
  case Unexpected(cause: Throwable)

object DbError:
  /** Walks the cause chain (libraries wrap SQLException) and maps by SQLSTATE. */
  def from(t: Throwable): DbError =
    Iterator.iterate(t)(_.getCause).takeWhile(_ != null).collectFirst { case e: SQLException => e } match
      case Some(e) if e.getSQLState == "P0001" => AppendOnlyViolation(e.getMessage)
      case Some(e) if e.getSQLState == "42501" => PermissionDenied(e.getMessage)
      case Some(e)                             => Other(e.getSQLState, e.getMessage)
      case None                                => Unexpected(t)

final case class TenantId(value: UUID)
object TenantId:
  def random(): TenantId = TenantId(UUID.randomUUID())

object Pools:
  val url = sys.env.getOrElse("SPIKE_JDBC_URL", "jdbc:postgresql://localhost:5433/spike_db_access")

  /** Pool for the non-superuser app role (RLS applies). */
  def app(size: Int = 4): ZLayer[Any, Throwable, HikariDataSource] =
    ZLayer.scoped(ZIO.fromAutoCloseable(ZIO.attemptBlocking(make("spike_db_access_app", "spike", size))))

  /** Superuser pool for assertions that must bypass RLS. */
  def admin: ZLayer[Any, Throwable, AdminDs] =
    ZLayer.scoped(ZIO.fromAutoCloseable(ZIO.attemptBlocking(make("dms", "dms-lokal", 2))).map(AdminDs(_)))

  def make(user: String, password: String, size: Int): HikariDataSource =
    val c = HikariConfig()
    c.setJdbcUrl(url)
    c.setUsername(user)
    c.setPassword(password)
    c.setMaximumPoolSize(size)
    c.setSchema("spike") // all spike tables live in schema `spike`
    c.setPoolName(s"spike-$user-$size")
    HikariDataSource(c)

final case class AdminDs(ds: HikariDataSource):
  /** Counts rows bypassing RLS (superuser). */
  def count(sqlText: String, params: Any*): Task[Long] = ZIO.attemptBlocking {
    val con = ds.getConnection
    try
      val ps = con.prepareStatement(sqlText)
      params.zipWithIndex.foreach((p, i) => ps.setObject(i + 1, p))
      val rs = ps.executeQuery(); rs.next(); rs.getLong(1)
    finally con.close()
  }
