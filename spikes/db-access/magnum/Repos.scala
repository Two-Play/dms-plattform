package spike.magnum

import com.augustnagro.magnum.*
import spike.common.TenantId

import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
final case class Document(@Id id: UUID, tenantId: UUID, title: String, metadata: Jsonb) derives DbCodec

final case class DocumentCreator(tenantId: UUID, title: String, metadata: Jsonb) derives DbCodec

final case class Job(id: Long, batch: String, status: String, claimedBy: Option[String], payload: Jsonb)
    derives DbCodec

/** Typical queries, as they would look in a repository implementation. */
object DocumentRepo:
  private val repo = Repo[DocumentCreator, Document, UUID]

  def insert(t: TenantId, title: String, metadata: Jsonb)(using DbCon): Document =
    repo.insertReturning(DocumentCreator(t.value, title, metadata))

  def findAll()(using DbCon): Vector[Document] = repo.findAll

  def byMetadata(key: String, value: String)(using DbCon): Vector[Document] =
    sql"""select id, tenant_id, title, metadata from document
          where metadata->>$key = $value order by title""".query[Document].run()

object JobRepo:
  def enqueue(batch: String, payload: Jsonb)(using DbCon): Long =
    sql"insert into job (batch, payload) values ($batch, $payload) returning id".query[Long].run().head

  /** Claims one job; concurrent callers skip rows locked by others. */
  def claimNext(batch: String, worker: String)(using DbTx): Option[Job] =
    sql"""update job set status = 'claimed', claimed_by = $worker
          where id = (select id from job where batch = $batch and status = 'new'
                      order by id limit 1 for update skip locked)
          returning id, batch, status, claimed_by, payload""".query[Job].run().headOption

object AuditRepo:
  def append(t: TenantId, action: String, details: Jsonb)(using DbCon): Long =
    sql"insert into audit_event (tenant_id, action, details) values (${t.value}, $action, $details) returning seq"
      .query[Long].run().head

  def rewrite(seq: Long, action: String)(using DbCon): Int =
    sql"update audit_event set action = $action where seq = $seq".update.run()

  def delete(seq: Long)(using DbCon): Int =
    sql"delete from audit_event where seq = $seq".update.run()
