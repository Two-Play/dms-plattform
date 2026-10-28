package dms.app

import dms.authz.{Groups, RoleAssignments}
import dms.db.Db
import dms.identity.{AuthError, Authenticator, IdTokenCheck, Principal}
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.time.Instant

/** Error body of every endpoint. `issues` lists field problems for validation errors, e.g. the required
  * fields that block a submission.
  */
final case class ApiError(code: String, message: String, issues: Option[List[IssueView]] = None)
    derives JsonCodec,
      Schema

/** A business role assignment of the caller in the tenant (`dms.role_assignment`, ADR 0018).
  *
  * `organizationalUnit` and `documentClass` restrict the role (empty: the whole tenant); the class
  * restriction covers the class and its subclasses.
  */
final case class RoleAssignmentView(
    role: String,
    organizationalUnit: Option[String],
    documentClass: Option[String],
    validFrom: Instant,
    validUntil: Option[Instant]
) derives JsonCodec,
      Schema

final case class Me(
    userId: String,
    tenantId: String,
    /** Business roles of the caller's currently active role assignments, sorted (DMS-96). */
    roles: List[String],
    /** The currently active role assignments with their restrictions, sorted by role. */
    assignments: List[RoleAssignmentView],
    /** ZITADEL project roles from the access token (coarse application roles, ADR 0005). */
    projectRoles: List[String],
    /** Keys of the access groups the caller currently belongs to, sorted; members of a group see the
      * confidential documents that name it (DMS-101, ADR 0032).
      */
    groups: List[String]
) derives JsonCodec,
      Schema

/** Base for endpoints that require a ZITADEL access token. */
object SecuredApi:

  /** Header carrying the OIDC ID token of the current session for step-up-protected requests (DMS-86). */
  val IdTokenHeader = "X-DMS-Id-Token"

  val base
      : ZPartialServerEndpoint[Authenticator, String, Principal, Unit, (StatusCode, ApiError), Unit, Any] =
    endpoint
      .securityIn(auth.bearer[String]())
      .errorOut(statusCode.and(jsonBody[ApiError]))
      .zServerSecurityLogic(token =>
        ZIO.serviceWithZIO[Authenticator](_.authenticate(token)).mapError(toResponse)
      )

  /** Like [[base]], additionally reading the ID token from [[IdTokenHeader]]: ZITADEL access tokens carry no
    * `auth_time`, so the sign-in time for step-up comes from the ID token (ADR 0024 addendum DMS-86).
    */
  val stepUp: ZPartialServerEndpoint[
    Authenticator,
    (String, Option[String]),
    Principal,
    Unit,
    (StatusCode, ApiError),
    Unit,
    Any
  ] =
    endpoint
      .securityIn(auth.bearer[String]())
      .securityIn(
        header[Option[String]](IdTokenHeader).description(
          "OIDC ID token of the current session. Required for actions that need a recent sign-in (step-up): " +
            "its auth_time must be within dms.workflow.reauthenticationSeconds."
        )
      )
      .errorOut(statusCode.and(jsonBody[ApiError]))
      .zServerSecurityLogic((token, idToken) =>
        ZIO.serviceWithZIO[Authenticator](_.authenticate(token, idToken)).mapError(toResponse)
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

  /** Explains a failed step-up from what became of the ID token the request carried. */
  def reauthenticationMessage(principal: Principal): String = principal.idToken match
    case IdTokenCheck.NotSent =>
      s"Sign in again to confirm this action and send the ID token of that sign-in in header $IdTokenHeader."
    case IdTokenCheck.Rejected(reason) =>
      s"The ID token in header $IdTokenHeader was not accepted ($reason). Sign in again to confirm this action."
    case IdTokenCheck.Accepted(_) =>
      "Your last sign-in is too long ago to confirm this action. Sign in again."

  /** Replaces the generic message of a `reauthentication_required` response by [[reauthenticationMessage]].
    */
  def explainReauthentication(principal: Principal)(
      response: (StatusCode, ApiError)
  ): (StatusCode, ApiError) =
    response match
      case (status, error) if error.code == "reauthentication_required" =>
        (status, error.copy(message = reauthenticationMessage(principal)))
      case other => other

object MeApi:
  type Env = Authenticator & Db & RoleAssignments & Groups

  val me: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "me")
      .out(jsonBody[Me])
      .description(
        "The signed-in user, their tenant, their currently active business role assignments (roles and " +
          "restrictions from the tenant's role administration) and their access groups"
      )
      .tag("Identity")
      .serverLogic(principal => _ => me(principal))

  private def me(principal: Principal): ZIO[Db & RoleAssignments & Groups, (StatusCode, ApiError), Me] =
    for
      db          <- ZIO.service[Db]
      assignments <- ZIO.service[RoleAssignments]
      groups      <- ZIO.service[Groups]
      now         <- Clock.instant
      loaded      <- db
        .transact(principal.tenantId)(
          assignments.forUser(principal.userId) <*> groups.groupsOf(principal.userId)
        )
        .mapError(DocumentApi.toResponse)
      (held, memberOf) = loaded
      active           = held.filter(_.isActiveAt(now)).toList
      views            = active
        .map(a =>
          RoleAssignmentView(
            a.role.toString,
            a.organizationalUnit,
            a.documentClass,
            a.validFrom,
            a.validUntil
          )
        )
        .sortBy(v => (v.role, v.organizationalUnit, v.documentClass, v.validFrom))
    yield Me(
      principal.userId.value,
      principal.tenantId.value.toString,
      views.map(_.role).distinct,
      views,
      principal.roles.toList.sorted,
      memberOf.toList.sorted
    )
