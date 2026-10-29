package dms.identity

import com.augustnagro.magnum.*
import dms.db.{Db, DbError}
import dms.kernel.{TenantId, UserId}
import zio.*

import scala.annotation.unused

/** A user as the UI shows them: a display name instead of the bare user id (DMS-110). */
final case class UserInfo(id: UserId, displayName: String, email: Option[String])

enum UserDirectoryError:
  /** The identity provider could not be asked (unreachable, timeout, error status, unexpected answer). */
  case Unavailable(reason: String)

/** Resolves user ids to display names and finds users by name (port; adapter: [[ZitadelUserDirectory]]).
  *
  * Only users of the given ZITADEL organization are returned, so a tenant never learns names of another
  * tenant's users. Ids that are unknown in the organization are missing from the result.
  */
trait UserDirectory:
  def lookup(organizationId: String, ids: Set[UserId]): IO[UserDirectoryError, Map[UserId, UserInfo]]

  /** People of the organization whose name or email matches every word of `query` (DMS-111, people picker):
    * each word is the start of the first name, last name, display name, email or user name. Only active human
    * users (no service accounts), sorted by display name, at most `limit`. A directory that cannot search
    * fails, so the picker can say why it finds nobody.
    */
  def search(
      @unused organizationId: String,
      @unused query: UserQuery,
      @unused limit: Int
  ): IO[UserDirectoryError, List[UserInfo]] =
    ZIO.fail(UserDirectoryError.Unavailable(UserDirectory.CannotSearch))

/** A normalized search for people (DMS-111): lower-case words without surrounding blanks, at least one. Built
  * with [[UserQuery.parse]], which also bounds its size.
  */
final case class UserQuery private (words: List[String]):
  /** The words joined by one blank; the key of cached answers. */
  def text: String = words.mkString(" ")

object UserQuery:
  /** Characters a query needs at least (without blanks), so a single letter does not list half the tenant. */
  val MinLength = 2

  /** Characters a query may have at most. */
  val MaxLength = 100

  /** Words a query may have at most; more do not narrow down a person further. */
  val MaxWords = 4

  def parse(raw: String): Either[String, UserQuery] =
    val words = raw.trim.toLowerCase(java.util.Locale.ROOT).split("\\s+").iterator.filter(_.nonEmpty).toList
    if raw.length > MaxLength then Left(s"A search for people has at most $MaxLength characters.")
    else if words.mkString.length < MinLength then
      Left(s"A search for people needs at least $MinLength characters.")
    else if words.size > MaxWords then Left(s"A search for people has at most $MaxWords words.")
    else Right(UserQuery(words))

object UserDirectory:

  /** No directory configured: every id stays unresolved, the UI shows ids as before. Searching fails, so the
    * people picker can say why it finds nobody and offer to take a user id instead.
    */
  val none: UserDirectory = new UserDirectory:
    def lookup(organizationId: String, ids: Set[UserId]): IO[UserDirectoryError, Map[UserId, UserInfo]] =
      ZIO.succeed(Map.empty)
    override def search(
        @unused organizationId: String,
        @unused query: UserQuery,
        @unused limit: Int
    ): IO[UserDirectoryError, List[UserInfo]] =
      ZIO.fail(UserDirectoryError.Unavailable(NotConfigured))

  /** Reason of [[none]] when searching. */
  val NotConfigured = "no user directory is configured"

  /** Reason of a directory without search (e.g. a test double that only resolves ids). */
  val CannotSearch = "this user directory cannot search for people"

/** The ZITADEL organization of a tenant (`dms.tenant.zitadel_org_id`), the reverse of [[TenantDirectory]].
  * Read inside the tenant's own transaction, so row-level security applies.
  */
trait TenantOrganizations:
  def organizationOf(tenant: TenantId): IO[DbError, Option[String]]

object TenantOrganizations:
  final class Postgres(db: Db) extends TenantOrganizations:
    def organizationOf(tenant: TenantId): IO[DbError, Option[String]] =
      db.transact(tenant)(
        Db.sql(
          sql"select zitadel_org_id from dms.tenant where id = ${tenant.value}"
            .query[Option[String]]
            .run()
            .headOption
            .flatten
        )
      )

  val layer: ZLayer[Db, Nothing, TenantOrganizations] = ZLayer.fromFunction(Postgres(_))

  /** A fixed mapping, for tests. */
  def fixed(organizations: Map[TenantId, String]): TenantOrganizations = new TenantOrganizations:
    def organizationOf(tenant: TenantId): IO[DbError, Option[String]] = ZIO.succeed(organizations.get(tenant))
