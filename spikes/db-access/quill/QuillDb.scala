package spike.quill

import io.getquill.*
import io.getquill.jdbczio.Quill
import spike.common.*
import zio.*
import zio.json.ast.Json

import java.sql.{Connection, SQLException}
import java.util.UUID
import javax.sql.DataSource

final case class Document(id: UUID, tenantId: UUID, title: String, metadata: JsonbValue[Json])
final case class Job(id: Long, batch: String, status: String, claimedBy: Option[String], payload: JsonbValue[Json])
final case class AuditEvent(seq: Long, tenantId: UUID, action: String, details: JsonbValue[Json])

final case class Probe(pid: Int, setting: String)

type Ctx = Quill.Postgres[SnakeCase]

/** Repositories + the tenant-aware transaction helper on top of ZIO Quill. */
final class QuillDb(val ctx: Ctx):
  import ctx.*

  /** Quill has no "on transaction start" hook, so the tenant is set as the first statement. */
  def transact[R, A](tenant: Option[TenantId])(op: ZIO[R, DbError, A]): ZIO[R, DbError, A] =
    // ctx.transaction only accepts ZIO[R, Throwable, A], so typed errors are tunnelled through an exception.
    ctx.transaction((ZIO.foreachDiscard(tenant)(setTenant) *> op).mapError(Tunnel(_))).mapError {
      case Tunnel(e) => e
      case t         => DbError.from(t)
    }

  def setTenant(t: TenantId): IO[DbError, Unit] =
    lift1(run(sql"select set_config('app.tenant_id', ${lift(t.value.toString)}, true)".as[Query[String]])).unit

  /** Raw connection of the current transaction – via Quill's (public, but internal-looking) FiberRef. */
  def withConnection[A](f: Connection => A): IO[DbError, A] =
    dsDelegate.currentConnection.get.flatMap {
      case Some(con) => ZIO.attemptBlocking(f(con)).mapError(DbError.from)
      case None      => ZIO.fail(DbError.Other("XX000", "withConnection called outside of a transaction"))
    }

  /** Joins a transaction owned outside Quill (SharedTx / Flowable's Tx) by seeding Quill's own
    * connection FiberRef with the externally supplied Connection. Nested ctx.transaction calls then
    * see "already in a transaction" and neither commit nor close it.
    */
  def joined[R, A](op: ZIO[R, DbError, A]): ZIO[R, DbError, A] =
    SharedTx.ref.get.someOrFail(DbError.Other("XX000", "no shared transaction")).flatMap(con =>
      dsDelegate.currentConnection.locally(Some(con))(op))

  /** Alternative without touching the FiberRef: Quill's connection-level API. */
  def countDocumentsOn(con: Connection): IO[DbError, Long] =
    dsDelegate.underlying.run(query[Document].size).provideEnvironment(ZEnvironment(con)).mapError(DbError.from)

  /** Backend pid + current tenant GUC, used by the pool leak test. */
  def probe: IO[DbError, Probe] =
    lift1(run(sql"select pg_backend_pid() as pid, coalesce(current_setting('app.tenant_id', true), '') as setting".as[Query[Probe]])).map(_.head)

  /** Shows that `SET LOCAL x = ?` is rejected by PostgreSQL. */
  def setLocalWithBindParameter(t: TenantId): IO[DbError, Long] =
    lift1(run(sql"set local app.tenant_id = ${lift(t.value.toString)}".as[Action[Unit]]))

  /** Violates a DEFERRABLE INITIALLY DEFERRED unique constraint -> error only at COMMIT. */
  def insertDeferredDuplicate: IO[DbError, Long] =
    lift1(run(sql"insert into deferred_check (k) values (1), (1)".as[Action[Unit]]))

  // ---- documents ----
  def insertDocument(t: TenantId, title: String, metadata: Json): IO[DbError, UUID] =
    lift1(run(query[Document].insert(_.tenantId -> lift(t.value), _.title -> lift(title), _.metadata -> lift(JsonbValue(metadata))).returning(_.id)))

  def allDocuments: IO[DbError, List[Document]] = lift1(run(query[Document].sortBy(_.title)))

  def documentsByMetadata(key: String, value: String): IO[DbError, List[Document]] =
    lift1(run(query[Document].filter(d => sql"${d.metadata}->>${lift(key)}".as[String] == lift(value)).sortBy(_.title)))

  // ---- jobs ----
  def enqueue(batch: String, payload: Json): IO[DbError, Long] =
    lift1(run(query[Job].insert(_.batch -> lift(batch), _.payload -> lift(JsonbValue(payload))).returning(_.id)))

  def claimNext(batch: String, worker: String): IO[DbError, Option[Job]] =
    for
      locked <- lift1(run(sql"${query[Job].filter(j => j.batch == lift(batch) && j.status == "new").sortBy(_.id).take(1)} FOR UPDATE SKIP LOCKED".as[Query[Job]]))
      _      <- ZIO.foreachDiscard(locked)(j => lift1(run(query[Job].filter(_.id == lift(j.id)).update(_.status -> "claimed", _.claimedBy -> lift(Option(worker))))))
    yield locked.headOption

  // ---- audit ----
  def appendAudit(t: TenantId, action: String, details: Json): IO[DbError, Long] =
    lift1(run(query[AuditEvent].insert(_.tenantId -> lift(t.value), _.action -> lift(action), _.details -> lift(JsonbValue(details))).returning(_.seq)))
  def rewriteAudit(seq: Long, action: String): IO[DbError, Long] =
    lift1(run(query[AuditEvent].filter(_.seq == lift(seq)).update(_.action -> lift(action))))
  def deleteAudit(seq: Long): IO[DbError, Long] =
    lift1(run(query[AuditEvent].filter(_.seq == lift(seq)).delete))

  private def lift1[A](z: ZIO[Any, SQLException, A]): IO[DbError, A] = z.mapError(DbError.from)

final case class Tunnel(error: DbError) extends Exception(error.toString, null, false, false)

object QuillDb:
  val layer: ZLayer[DataSource, Nothing, QuillDb] =
    Quill.Postgres.fromNamingStrategy(SnakeCase) >>> ZLayer.fromFunction(QuillDb(_))
