package dms.workflow.core

import com.augustnagro.magnum.*
import dms.db.Jsonb
import dms.kernel.UserId
import dms.workflow.Deputyship
import zio.json.*

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.UUID

/** SQL for `dms.deputyship` (migration V15, DMS-49). Runs inside `Db.sql` within `Db.transact(tenant)`;
  * row-level security limits all rows to the current tenant.
  */
private[core] object DeputyStore:

  final case class Row(
      id: UUID,
      principalId: String,
      deputyId: String,
      validFrom: OffsetDateTime,
      validUntil: OffsetDateTime,
      roles: Jsonb,
      documentClass: Option[String],
      reason: Option[String],
      createdBy: String,
      createdAt: OffsetDateTime,
      endedAt: Option[OffsetDateTime],
      endedBy: Option[String]
  ) derives DbCodec:
    def toDeputyship: Deputyship = Deputyship(
      id,
      UserId(principalId),
      UserId(deputyId),
      validFrom.toInstant,
      validUntil.toInstant,
      roles.value.fromJson[List[String]].getOrElse(Nil),
      documentClass,
      reason,
      UserId(createdBy),
      createdAt.toInstant,
      endedAt.map(_.toInstant),
      endedBy.map(UserId(_))
    )

  // The column list is repeated in each query: Magnum binds parameters only for literal sql"" fragments.

  def insert(d: Deputyship)(using DbTx): Unit =
    val _ = sql"""insert into dms.deputyship
                    (id, tenant_id, principal_id, deputy_id, valid_from, valid_until, roles, document_class, reason,
                     created_by, created_at)
                  values (${d.id}, dms.current_tenant(), ${d.principal.value}, ${d.deputy.value},
                          ${offset(d.validFrom)}, ${offset(d.validUntil)}, ${Jsonb(d.roles.toJson)},
                          ${d.documentClass}, ${d.reason}, ${d.createdBy.value}, ${offset(
        d.createdAt
      )})""".update
      .run()

  /** A deputyship, locked for the rest of the transaction. */
  def lock(id: UUID)(using DbTx): Option[Deputyship] =
    sql"""select id, principal_id, deputy_id, valid_from, valid_until, roles, document_class, reason, created_by,
                 created_at, ended_at, ended_by
          from dms.deputyship where id = $id for update"""
      .query[Row]
      .run()
      .headOption
      .map(_.toDeputyship)

  /** Deputyships in which `user` is principal or deputy, latest period first. */
  def involving(user: UserId)(using DbTx): List[Deputyship] =
    sql"""select id, principal_id, deputy_id, valid_from, valid_until, roles, document_class, reason, created_by,
                 created_at, ended_at, ended_by
          from dms.deputyship where principal_id = ${user.value} or deputy_id = ${user.value}
          order by valid_from desc, id"""
      .query[Row]
      .run()
      .toList
      .map(_.toDeputyship)

  def ofPrincipal(user: UserId)(using DbTx): List[Deputyship] =
    sql"""select id, principal_id, deputy_id, valid_from, valid_until, roles, document_class, reason, created_by,
                 created_at, ended_at, ended_by
          from dms.deputyship where principal_id = ${user.value} order by valid_from desc, id"""
      .query[Row]
      .run()
      .toList
      .map(_.toDeputyship)

  def all(using DbTx): List[Deputyship] =
    sql"""select id, principal_id, deputy_id, valid_from, valid_until, roles, document_class, reason, created_by,
                 created_at, ended_at, ended_by
          from dms.deputyship order by valid_from desc, id"""
      .query[Row]
      .run()
      .toList
      .map(_.toDeputyship)

  /** Deputyships of any of `principals` that are active at `now`. */
  def activeForPrincipals(principals: List[UserId], now: Instant)(using DbTx): List[Deputyship] =
    val at = offset(now)
    sql"""select id, principal_id, deputy_id, valid_from, valid_until, roles, document_class, reason, created_by,
                 created_at, ended_at, ended_by
          from dms.deputyship
          where principal_id in (select jsonb_array_elements_text(${ids(principals)}))
            and valid_from <= $at and valid_until > $at and (ended_at is null or ended_at > $at)
          order by deputy_id, principal_id"""
      .query[Row]
      .run()
      .toList
      .map(_.toDeputyship)

  /** Deputyships in which `deputy` stands in for someone at `now`. */
  def activeForDeputy(deputy: UserId, now: Instant)(using DbTx): List[Deputyship] =
    val at = offset(now)
    sql"""select id, principal_id, deputy_id, valid_from, valid_until, roles, document_class, reason, created_by,
                 created_at, ended_at, ended_by
          from dms.deputyship
          where deputy_id = ${deputy.value}
            and valid_from <= $at and valid_until > $at and (ended_at is null or ended_at > $at)
          order by principal_id"""
      .query[Row]
      .run()
      .toList
      .map(_.toDeputyship)

  /** Whether `principal` already has `deputy` for a period overlapping `from` to `until`. */
  def overlaps(principal: UserId, deputy: UserId, from: Instant, until: Instant)(using DbTx): Boolean =
    val start = offset(from)
    val end   = offset(until)
    sql"""select count(*) from dms.deputyship
          where principal_id = ${principal.value} and deputy_id = ${deputy.value}
            and valid_from < $end and least(valid_until, coalesce(ended_at, valid_until)) > $start"""
      .query[Long]
      .run()
      .head > 0

  /** Ends a deputyship at `at`; returns false if it was already ended. */
  def end(id: UUID, at: Instant, by: UserId)(using DbTx): Boolean =
    sql"""update dms.deputyship set ended_at = ${offset(at)}, ended_by = ${by.value}
          where id = $id and ended_at is null""".update.run() > 0

  private def ids(users: List[UserId]): Jsonb = Jsonb(users.map(_.value).distinct.toJson)

  private def offset(i: Instant): OffsetDateTime = OffsetDateTime.ofInstant(i, ZoneOffset.UTC)
