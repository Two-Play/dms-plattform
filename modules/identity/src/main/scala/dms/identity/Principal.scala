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

/** Outcome of checking the OIDC ID token a client sends for step-up-protected requests (DMS-86, ADR 0024
  * addendum). ZITADEL access tokens carry no `auth_time`; the ID token of the same session does.
  */
enum IdTokenCheck:
  /** The request carried no ID token. */
  case NotSent

  /** The ID token is valid for this client and user; `authenticatedAt` is its `auth_time`. */
  case Accepted(authenticatedAt: Instant)

  /** The ID token was sent but not accepted, e.g. expired, for another client or another user. */
  case Rejected(reason: String)

/** The authenticated caller of a request, bound to exactly one tenant.
  *
  * @param authenticatedAt
  *   time of the last sign-in: the later of the access token's and the accepted ID token's `auth_time`
  * @param idToken
  *   what became of the ID token sent with the request, used to explain a failed step-up
  */
final case class Principal(
    userId: UserId,
    tenantId: TenantId,
    roles: Set[String],
    authenticatedAt: Option[Instant],
    idToken: IdTokenCheck = IdTokenCheck.NotSent
):
  def hasRole(role: String): Boolean = roles.contains(role)

enum AuthError:
  case MissingToken
  case InvalidToken(reason: String)

  /** The token is valid, but its ZITADEL organization is not mapped to a tenant. */
  case UnknownOrganization(organizationId: String)

  /** A sensitive action needs a sign-in more recent than the token's `auth_time` (step-up). */
  case ReauthenticationRequired
