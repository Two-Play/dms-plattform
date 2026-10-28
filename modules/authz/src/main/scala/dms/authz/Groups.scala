package dms.authz

import com.augustnagro.magnum.*
import dms.db.{Db, DbError, Jsonb}
import dms.kernel.UserId
import zio.*

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.UUID

/** An access group of a tenant (DMS-101, ADR 0032): confidential document versions name the groups whose
  * members may see them besides author and owner (core field `allowedGroups`).
  *
  * @param key
  *   stable key stored in document versions, lowercase letters, digits and hyphens
  */
final case class AccessGroup(key: String, name: String, createdBy: UserId, createdAt: Instant)

/** A current or ended membership of a user in an access group. */
final case class GroupMembership(
    id: UUID,
    groupKey: String,
    user: UserId,
    addedBy: UserId,
    addedAt: Instant,
    removedBy: Option[UserId],
    removedAt: Option[Instant]
):
  def isCurrent: Boolean = removedAt.isEmpty

/** Access groups and their members in the current tenant (`dms.access_group`, migration V17). Must run inside
  * `Db.transact(tenant)`; RLS limits rows to the tenant.
  */
trait Groups:
  /** All groups of the tenant, sorted by key. */
  def list: IO[DbError, List[AccessGroup]]

  def get(key: String): IO[DbError, Option[AccessGroup]]

  /** Creates a group; `false` if the key is taken. */
  def create(key: String, name: String, by: UserId): IO[DbError, Boolean]

  /** The keys among `keys` that name a group of the tenant. */
  def existing(keys: Set[String]): IO[DbError, Set[String]]

  /** Keys of the groups `user` currently belongs to. */
  def groupsOf(user: UserId): IO[DbError, Set[String]]

  /** Memberships of a group, current ones first, then ended ones, latest first. */
  def members(key: String): IO[DbError, List[GroupMembership]]

  /** Current members of any of `keys`. */
  def membersOfAny(keys: Set[String]): IO[DbError, Set[UserId]]

  /** Adds `user` to the group; `false` if they are a member already. */
  def addMember(key: String, user: UserId, by: UserId): IO[DbError, Boolean]

  /** Ends the current membership of `user`; `false` if there is none. */
  def removeMember(key: String, user: UserId, by: UserId, at: Instant): IO[DbError, Boolean]

object Groups:
  /** Format of group keys, also enforced by the database. */
  val KeyPattern: String = dms.documents.metadata.CoreFields.GroupKeyPattern

  def isValidKey(key: String): Boolean = key.matches(KeyPattern)

  object Postgres extends Groups:
    private type GroupRow  = (String, String, String, OffsetDateTime)
    private type MemberRow =
      (UUID, String, String, String, OffsetDateTime, Option[String], Option[OffsetDateTime])

    private def group(row: GroupRow): AccessGroup =
      AccessGroup(row._1, row._2, UserId(row._3), row._4.toInstant)

    def list: IO[DbError, List[AccessGroup]] =
      Db.sql(
        sql"select key, name, created_by, created_at from dms.access_group order by key"
          .query[GroupRow]
          .run()
          .toList
      ).map(_.map(group))

    def get(key: String): IO[DbError, Option[AccessGroup]] =
      Db.sql(
        sql"select key, name, created_by, created_at from dms.access_group where key = $key"
          .query[GroupRow]
          .run()
          .headOption
      ).map(_.map(group))

    def create(key: String, name: String, by: UserId): IO[DbError, Boolean] =
      Db.sql(
        sql"""insert into dms.access_group (tenant_id, key, name, created_by)
              values (dms.current_tenant(), $key, $name, ${by.value})
              on conflict (tenant_id, key) do nothing""".update.run() > 0
      )

    def existing(keys: Set[String]): IO[DbError, Set[String]] =
      if keys.isEmpty then ZIO.succeed(Set.empty)
      else
        Db.sql(
          sql"""select key from dms.access_group
                where key in (select jsonb_array_elements_text(${jsonArray(keys)}))"""
            .query[String]
            .run()
            .toSet
        )

    def groupsOf(user: UserId): IO[DbError, Set[String]] =
      Db.sql(
        sql"""select group_key from dms.access_group_member
              where user_id = ${user.value} and removed_at is null""".query[String].run().toSet
      )

    def members(key: String): IO[DbError, List[GroupMembership]] =
      Db.sql(
        sql"""select id, group_key, user_id, added_by, added_at, removed_by, removed_at
              from dms.access_group_member where group_key = $key
              order by removed_at is not null, removed_at desc, user_id""".query[MemberRow].run().toList
      ).map(_.map { case (id, key, user, by, at, removedBy, removedAt) =>
        GroupMembership(
          id,
          key,
          UserId(user),
          UserId(by),
          at.toInstant,
          removedBy.map(UserId(_)),
          removedAt.map(_.toInstant)
        )
      })

    def membersOfAny(keys: Set[String]): IO[DbError, Set[UserId]] =
      if keys.isEmpty then ZIO.succeed(Set.empty)
      else
        Db.sql(
          sql"""select distinct user_id from dms.access_group_member
                where group_key in (select jsonb_array_elements_text(${jsonArray(
              keys
            )})) and removed_at is null""".query[String].run().map(UserId(_)).toSet
        )

    def addMember(key: String, user: UserId, by: UserId): IO[DbError, Boolean] =
      Db.sql(
        sql"""insert into dms.access_group_member (tenant_id, group_key, user_id, added_by)
              values (dms.current_tenant(), $key, ${user.value}, ${by.value})
              on conflict (tenant_id, group_key, user_id) where removed_at is null do nothing""".update
          .run() > 0
      )

    def removeMember(key: String, user: UserId, by: UserId, at: Instant): IO[DbError, Boolean] =
      Db.sql(
        sql"""update dms.access_group_member
              set removed_by = ${by.value}, removed_at = ${OffsetDateTime.ofInstant(at, ZoneOffset.UTC)}
              where group_key = $key and user_id = ${user.value} and removed_at is null""".update.run() > 0
      )

    /** A JSON array of strings, for `jsonb_array_elements_text`. */
    private def jsonArray(values: Set[String]): Jsonb =
      def quote(v: String): String =
        v.flatMap {
          case '"'          => "\\\""
          case '\\'         => "\\\\"
          case c if c < ' ' => f"\\u${c.toInt}%04x"
          case c            => c.toString
        }
      Jsonb(values.toList.sorted.map(v => "\"" + quote(v) + "\"").mkString("[", ",", "]"))

  val postgres: ULayer[Groups] = ZLayer.succeed(Postgres)
