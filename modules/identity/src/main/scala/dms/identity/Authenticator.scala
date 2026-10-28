package dms.identity

import com.augustnagro.magnum.*
import dms.db.{Db, DbError}
import dms.kernel.TenantId
import zio.*

import java.time.{Duration as JDuration, Instant}
import java.util.UUID

/** Maps ZITADEL organizations to tenants. The lookup runs before a tenant is known, so it uses the
  * `security definer` function `dms.tenant_by_zitadel_org` instead of reading the RLS-protected table.
  */
trait TenantDirectory:
  def tenantFor(organizationId: String): IO[DbError, Option[TenantId]]

object TenantDirectory:
  final class Postgres(db: Db) extends TenantDirectory:
    def tenantFor(organizationId: String): IO[DbError, Option[TenantId]] =
      db.transactSystem(
        Db.sql(
          sql"select dms.tenant_by_zitadel_org($organizationId)".query[Option[UUID]].run().headOption.flatten
        )
      ).map(_.map(TenantId(_)))

  val layer: ZLayer[Db, Nothing, TenantDirectory] = ZLayer.fromFunction(Postgres(_))

/** Turns a bearer token into a [[Principal]]: verifies the token and resolves its organization to a tenant.
  */
final class Authenticator(verifier: TokenVerifier, tenants: TenantDirectory):

  def authenticate(token: String): IO[AuthError, Principal] =
    for
      verified <- verifier.verify(token)
      tenant   <- tenants
        .tenantFor(verified.organizationId)
        .orDieWith(e => RuntimeException(s"tenant lookup failed: $e"))
        .someOrFail(AuthError.UnknownOrganization(verified.organizationId))
    yield Principal(verified.userId, tenant, verified.roles, verified.authenticatedAt)

object Authenticator:
  val layer: ZLayer[TokenVerifier & TenantDirectory, Nothing, Authenticator] =
    ZLayer.fromFunction(Authenticator(_, _))

  /** Step-up check for sensitive actions such as approvals (business concept 6.1): the user must have signed
    * in within `maxAge`. The client re-authenticates with OIDC `max_age` or `prompt=login`.
    */
  def requireRecentSignIn(principal: Principal, maxAge: JDuration, now: Instant): Either[AuthError, Unit] =
    principal.authenticatedAt match
      case Some(at) if !at.plus(maxAge).isBefore(now) => Right(())
      case _                                          => Left(AuthError.ReauthenticationRequired)
