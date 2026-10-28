package dms.identity

import dms.kernel.{TenantId, UserId}

import java.time.Instant

/** A verified access token, before tenant resolution. */
final case class VerifiedToken(
    userId: UserId,
    organizationId: String,
    roles: Set[String],
    authenticatedAt: Option[Instant]
)

/** The authenticated caller of a request, bound to exactly one tenant. */
final case class Principal(
    userId: UserId,
    tenantId: TenantId,
    roles: Set[String],
    authenticatedAt: Option[Instant]
):
  def hasRole(role: String): Boolean = roles.contains(role)

enum AuthError:
  case MissingToken
  case InvalidToken(reason: String)

  /** The token is valid, but its ZITADEL organization is not mapped to a tenant. */
  case UnknownOrganization(organizationId: String)

  /** A sensitive action needs a sign-in more recent than the token's `auth_time` (step-up). */
  case ReauthenticationRequired
