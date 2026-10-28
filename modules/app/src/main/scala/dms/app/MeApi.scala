package dms.app

import dms.identity.{AuthError, Authenticator, Principal}
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.ZIO
import zio.json.JsonCodec

final case class ApiError(code: String, message: String) derives JsonCodec, Schema

final case class Me(userId: String, tenantId: String, roles: List[String]) derives JsonCodec, Schema

/** Base for endpoints that require a ZITADEL access token. */
object SecuredApi:
  val base
      : ZPartialServerEndpoint[Authenticator, String, Principal, Unit, (StatusCode, ApiError), Unit, Any] =
    endpoint
      .securityIn(auth.bearer[String]())
      .errorOut(statusCode.and(jsonBody[ApiError]))
      .zServerSecurityLogic(token =>
        ZIO.serviceWithZIO[Authenticator](_.authenticate(token)).mapError(toResponse)
      )

  def toResponse(error: AuthError): (StatusCode, ApiError) = error match
    case AuthError.MissingToken =>
      (StatusCode.Unauthorized, ApiError("missing_token", "Sign in to continue."))
    case AuthError.InvalidToken(_) =>
      (StatusCode.Unauthorized, ApiError("invalid_token", "The access token is invalid or expired."))
    case AuthError.UnknownOrganization(_) =>
      (
        StatusCode.Forbidden,
        ApiError("unknown_organization", "Your organization is not registered as a tenant.")
      )
    case AuthError.ReauthenticationRequired =>
      (
        StatusCode.Unauthorized,
        ApiError("reauthentication_required", "Sign in again to confirm this action.")
      )

object MeApi:
  val me: ZServerEndpoint[Authenticator, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "me")
      .out(jsonBody[Me])
      .description("The signed-in user and their tenant")
      .tag("Identity")
      .serverLogic(principal =>
        _ =>
          ZIO.succeed(
            Me(principal.userId.value, principal.tenantId.value.toString, principal.roles.toList.sorted)
          )
      )
