package dms.documents.core

import com.augustnagro.magnum.*
import dms.documents.{NumberPattern, NumberRange}
import dms.kernel.DocumentId

import java.time.OffsetDateTime
import java.util.UUID

/** SQL for number ranges, their counters, class assignments and document numbers (migration V25). Runs inside
  * `Db.sql` within `Db.transact(tenant)`; row-level security limits all rows to the current tenant.
  */
private[core] object NumberRangeStore:

  private type RangeRow =
    (UUID, String, String, String, Long, Boolean, String, OffsetDateTime, OffsetDateTime)

  def ranges()(using DbTx): List[NumberRange] =
    sql"""select id, key, name, pattern, start_value, yearly_reset, created_by, created_at, updated_at
          from dms.number_range order by key""".query[RangeRow].run().map(toRange).toList

  def range(id: UUID)(using DbTx): Option[NumberRange] =
    sql"""select id, key, name, pattern, start_value, yearly_reset, created_by, created_at, updated_at
          from dms.number_range where id = $id""".query[RangeRow].run().headOption.map(toRange)

  def insertRange(r: NumberRange)(using DbTx): Unit =
    val _ = sql"""insert into dms.number_range
                    (id, tenant_id, key, name, pattern, start_value, yearly_reset, created_by, updated_by)
                  values (${r.id}, dms.current_tenant(), ${r.key}, ${r.name}, ${r.pattern.show}, ${r.startValue},
                          ${r.yearlyReset}, ${r.createdBy}, ${r.createdBy})""".update.run()

  def updateRange(r: NumberRange, by: String)(using DbTx): Unit =
    val _ = sql"""update dms.number_range
                  set name = ${r.name}, pattern = ${r.pattern.show}, start_value = ${r.startValue},
                      yearly_reset = ${r.yearlyReset}, updated_by = $by, updated_at = now()
                  where id = ${r.id}""".update.run()

  /** The stored next counter value of a range in a period, without locking. */
  def counter(range: UUID, period: Int)(using DbTx): Option[Long] =
    sql"""select next_value from dms.number_range_counter where range_id = $range and period = $period"""
      .query[Long]
      .run()
      .headOption

  /** The stored next counter value of a range in a period, locked until the transaction ends; creates the row
    * (with `start`) if the period has none yet. Concurrent transactions wait here, so each gets its own
    * value.
    */
  def lockCounter(range: UUID, period: Int, start: Long)(using DbTx): Long =
    val _ = sql"""insert into dms.number_range_counter (tenant_id, range_id, period, next_value)
                  values (dms.current_tenant(), $range, $period, $start)
                  on conflict (range_id, period) do nothing""".update.run()
    sql"""select next_value from dms.number_range_counter where range_id = $range and period = $period
          for update""".query[Long].run().head

  def setCounter(range: UUID, period: Int, next: Long)(using DbTx): Unit =
    val _ = sql"""update dms.number_range_counter set next_value = $next
                  where range_id = $range and period = $period""".update.run()

  /** Assignments by class id: `Some(range)` or `None` for an explicit "no number range". */
  def assignments()(using DbTx): Map[UUID, Option[UUID]] =
    sql"""select class_id, range_id from dms.document_class_number_range"""
      .query[(UUID, Option[UUID])]
      .run()
      .toMap

  def assign(classId: UUID, range: Option[UUID], by: String)(using DbTx): Unit =
    val _ = sql"""insert into dms.document_class_number_range (tenant_id, class_id, range_id, assigned_by)
                  values (dms.current_tenant(), $classId, $range, $by)
                  on conflict (class_id) do update
                  set range_id = excluded.range_id, assigned_by = excluded.assigned_by, assigned_at = now()""".update
      .run()

  def unassign(classId: UUID)(using DbTx): Boolean =
    sql"""delete from dms.document_class_number_range where class_id = $classId""".update.run() > 0

  /** Class ids by key with their parent, to resolve inherited assignments. */
  def classTree()(using DbTx): List[(UUID, String, Option[UUID])] =
    sql"""select id, key, parent_id from dms.document_class"""
      .query[(UUID, String, Option[UUID])]
      .run()
      .toList

  /** The document that has `number`, if any. */
  def documentWithNumber(number: String)(using DbTx): Option[DocumentId] =
    sql"""select id from dms.document where document_number = $number"""
      .query[UUID]
      .run()
      .headOption
      .map(DocumentId(_))

  def setDocumentNumber(id: DocumentId, number: String)(using DbTx): Unit =
    val _ = sql"""update dms.document set document_number = $number, updated_at = now()
                  where id = ${id.value}""".update.run()

  private def toRange(row: RangeRow): NumberRange =
    val (id, key, name, pattern, start, yearly, createdBy, createdAt, updatedAt) = row
    NumberRange(
      id,
      key,
      name,
      NumberPattern
        .parse(pattern)
        .fold(
          e => throw IllegalStateException(s"stored pattern of number range $key: ${e.mkString("; ")}"),
          identity
        ),
      start,
      yearly,
      createdBy,
      createdAt.toInstant,
      updatedAt.toInstant
    )
