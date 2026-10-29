package dms.identity

import com.augustnagro.magnum.*
import dms.db.{Db, DbError}
import dms.kernel.{TenantId, UserId}
import zio.*

/** A user as the UI shows them: a display name instead of the bare user id (DMS-110). */
final case class UserInfo(id: UserId, displayName: String, email: Option[String])

enum UserDirectoryError:
  /** The identity provider could not be asked (unreachable, timeout, error status, unexpected answer). */
  case Unavailable(reason: String)

/** Resolves user ids to display names (port; adapter: [[ZitadelUserDirectory]]).
  *
  * Only users of the given ZITADEL organization are returned, so a tenant never learns names of another
  * tenant's users. Ids that are unknown in the organization are missing from the result.
  */
trait UserDirectory:
  def lookup(organizationId: String, ids: Set[UserId]): IO[UserDirectoryError, Map[UserId, UserInfo]]

object UserDirectory:

  /** No directory configured: every id stays unresolved, the UI shows ids as before. */
  val none: UserDirectory = new UserDirectory:
    def lookup(organizationId: String, ids: Set[UserId]): IO[UserDirectoryError, Map[UserId, UserInfo]] =
      ZIO.succeed(Map.empty)

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
