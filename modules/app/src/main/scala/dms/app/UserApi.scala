package dms.app

import dms.db.DbError
import dms.identity.{Authenticator, Principal, TenantOrganizations, UserDirectory, UserDirectoryError}
import dms.kernel.UserId
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

/** A user id resolved for display (DMS-110). `displayName` is empty for ids that are unknown in the caller's
  * tenant; the UI then shows the id.
  */
final case class UserView(id: String, displayName: Option[String], email: Option[String])
    derives JsonCodec,
      Schema

/** User directory (DMS-110): display names for the user ids the API returns (authors, assignees, members,
  * ...). Every signed-in user may resolve ids, limited to users of the ZITADEL organization of their own
  * tenant. Names come from ZITADEL and are cached for a while (`dms.users.cacheSeconds`).
  */
object UserApi:
  type Env = Authenticator & TenantOrganizations & UserDirectory

  /** Ids per request. */
  val MaxIds = 200

  val users: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "users")
      .in(
        query[String]("ids").description(s"Comma-separated user ids (ZITADEL `sub`), at most $MaxIds")
      )
      .out(jsonBody[List[UserView]])
      .description(
        "Resolves user ids to display names and email addresses, in the order asked for and without duplicates. " +
          "Only users of the caller's tenant (its ZITADEL organization) are resolved; other ids have no " +
          "displayName. Answers 503 if the identity provider cannot be reached."
      )
      .tag("Identity")
      .serverLogic(principal => ids => resolve(principal, ids))

  val endpoints: List[ZServerEndpoint[Env, Any]] = List(users)

  /** Distinct, non-empty ids in the order given. */
  def parseIds(raw: String): Either[String, List[UserId]] =
    val ids = raw.split(',').iterator.map(_.trim).filter(_.nonEmpty).toList.distinct
    if ids.size > MaxIds then Left(s"At most $MaxIds user ids per request.")
    else if ids.exists(_.length > 200) then Left("A user id is too long.")
    else Right(ids.map(UserId(_)))

  private def resolve(
      principal: Principal,
      raw: String
  ): ZIO[TenantOrganizations & UserDirectory, (StatusCode, ApiError), List[UserView]] =
    for
      ids <- ZIO
        .fromEither(parseIds(raw))
        .mapError(message => (StatusCode.BadRequest, ApiError("invalid_ids", message)))
      organization <- ZIO
        .serviceWithZIO[TenantOrganizations](_.organizationOf(principal.tenantId))
        .mapError(toResponse)
      found <- organization match
        case Some(org) if ids.nonEmpty =>
          ZIO.serviceWithZIO[UserDirectory](_.lookup(org, ids.toSet)).mapError(toResponse)
        case _ => ZIO.succeed(Map.empty)
    yield ids.map(id =>
      found.get(id) match
        case Some(user) => UserView(id.value, Some(user.displayName), user.email)
        case None       => UserView(id.value, None, None)
    )

  def toResponse(error: DbError | UserDirectoryError): (StatusCode, ApiError) = error match
    case UserDirectoryError.Unavailable(reason) =>
      (
        StatusCode.ServiceUnavailable,
        ApiError("user_directory_unavailable", s"User names cannot be looked up right now: $reason")
      )
    case e: DbError => DocumentApi.toResponse(e)
