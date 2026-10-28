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

  def authenticate(token: String): IO[AuthError, Principal] = authenticate(token, None)

  /** Like [[authenticate]], additionally checking the OIDC ID token sent for step-up (DMS-86). An invalid ID
    * token does not fail the request; it only provides no sign-in time ([[IdTokenCheck.Rejected]]).
    */
  def authenticate(token: String, idToken: Option[String]): IO[AuthError, Principal] =
    for
      verified <- verifier.verify(token)
      tenant   <- tenants
        .tenantFor(verified.organizationId)
        .orDieWith(e => RuntimeException(s"tenant lookup failed: $e"))
        .someOrFail(AuthError.UnknownOrganization(verified.organizationId))
      check <- idToken.filter(_.nonEmpty) match
        case None     => ZIO.succeed(IdTokenCheck.NotSent)
        case Some(id) =>
          verifier
            .verifyIdToken(id, token, verified.userId)
            .map(IdTokenCheck.Accepted(_))
            .catchAll {
              case AuthError.InvalidToken(reason) =>
                ZIO.logWarning(s"ID token rejected: $reason").as(IdTokenCheck.Rejected(reason))
              case other => ZIO.succeed(IdTokenCheck.Rejected(other.toString))
            }
      fromIdToken = check match
        case IdTokenCheck.Accepted(at) => Some(at)
        case _                         => None
      authenticatedAt = (verified.authenticatedAt.toList ++ fromIdToken).maxOption
    yield Principal(verified.userId, tenant, verified.roles, authenticatedAt, check)

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
