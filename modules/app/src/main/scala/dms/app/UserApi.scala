package dms.app

import dms.authz.{AccessDenied, Action, Authorizer, Resource}
import dms.db.{Db, DbError}
import dms.identity.{
  Authenticator,
  Principal,
  TenantOrganizations,
  UserDirectory,
  UserDirectoryError,
  UserInfo,
  UserQuery,
  UserSearchPage
}
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

/** A person found by the people picker (DMS-111): the user id to store, the name and email to show. */
final case class UserMatch(id: String, displayName: String, email: Option[String]) derives JsonCodec, Schema

/** One page of a search for people (DMS-125). `total` counts the hits of the whole search, also those beyond
  * the reach of paging; `nextOffset` is the `offset` of the next page and missing on the last one.
  */
final case class UserMatchPage(items: List[UserMatch], total: Int, nextOffset: Option[Int])
    derives JsonCodec,
      Schema

/** User directory (DMS-110): display names for the user ids the API returns (authors, assignees, members,
  * ...). Every signed-in user may resolve ids, limited to users of the ZITADEL organization of their own
  * tenant. Names come from ZITADEL and are cached for a while (`dms.users.cacheSeconds`).
  *
  * The people picker (DMS-111, ADR 0037 addendum) searches the same directory by name or email; it needs a
  * role in the tenant (`Action.SearchUsers`) and returns at most [[MaxResults]] people of the caller's tenant
  * per page, and the first [[MaxReach]] hits of a search at most (DMS-125, ADR 0067).
  */
object UserApi:
  type Env = Authenticator & TenantOrganizations & UserDirectory & Db & Authorizer

  /** Ids per request. */
  val MaxIds = 200

  /** People per search, at most; the default is [[DefaultResults]]. */
  val MaxResults     = 20
  val DefaultResults = 10

  /** Hits of one search that paging reaches: the picker finds people, it does not list the directory. */
  val MaxReach = 200

  val search: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "users" / "search")
      .in(
        query[String]("q").description(
          s"Name or email: every word must be part of the first name, last name, display name, email or " +
            s"user name, in any case, with exact characters (no typo tolerance); ${UserQuery.MinLength} to ${UserQuery.MaxLength} characters, at most " +
            s"${UserQuery.MaxWords} words"
        )
      )
      .in(
        query[Option[Int]]("offset").description(
          s"Hits to skip, 0 to ${MaxReach - 1} (default 0); take it from `nextOffset` of the page before"
        )
      )
      .in(
        query[Option[Int]]("limit").description(s"People at most, 1 to $MaxResults (default $DefaultResults)")
      )
      .out(jsonBody[UserMatchPage])
      .description(
        "Finds active people of the caller's tenant (its ZITADEL organization) by name or email, for pickers " +
          "of group members, deputies and person fields; sorted by display name, one page at a time, the " +
          s"first $MaxReach hits at most. Requires a role in the tenant. Answers 503 if the identity provider cannot be reached or no user directory is configured."
      )
      .tag("Identity")
      .serverLogic(principal => (q, offset, limit) => find(principal, q, offset, limit))

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

  val endpoints: List[ZServerEndpoint[Env, Any]] = List(search, users)

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

  /** The requested number of people, if it is in range. */
  def parseLimit(limit: Option[Int]): Either[String, Int] = limit match
    case None                                 => Right(DefaultResults)
    case Some(n) if n >= 1 && n <= MaxResults => Right(n)
    case Some(_)                              => Left(s"The limit must be between 1 and $MaxResults.")

  /** The requested start of the page, if paging reaches it. */
  def parseOffset(offset: Option[Int]): Either[String, Int] = offset match
    case None                              => Right(0)
    case Some(n) if n >= 0 && n < MaxReach => Right(n)
    case Some(_)                           => Left(s"The offset must be between 0 and ${MaxReach - 1}.")

  private def find(
      principal: Principal,
      raw: String,
      rawOffset: Option[Int],
      rawLimit: Option[Int]
  ): ZIO[Db & Authorizer & TenantOrganizations & UserDirectory, (StatusCode, ApiError), UserMatchPage] =
    for
      query <- ZIO
        .fromEither(UserQuery.parse(raw))
        .mapError(message => (StatusCode.BadRequest, ApiError("invalid_query", message)))
      offset <- ZIO
        .fromEither(parseOffset(rawOffset))
        .mapError(message => (StatusCode.BadRequest, ApiError("invalid_offset", message)))
      limit <- ZIO
        .fromEither(parseLimit(rawLimit))
        .mapError(message => (StatusCode.BadRequest, ApiError("invalid_limit", message)))
      db    <- ZIO.service[Db]
      authz <- ZIO.service[Authorizer]
      _     <- db
        .transact(principal.tenantId)(
          authz
            .subject(principal)
            .flatMap(s => authz.check(s, Action.SearchUsers, Resource(principal.tenantId)))
        )
        .mapError(toResponse)
      organization <- ZIO
        .serviceWithZIO[TenantOrganizations](_.organizationOf(principal.tenantId))
        .mapError(toResponse)
      found <- organization match
        case Some(org) =>
          ZIO.serviceWithZIO[UserDirectory](_.search(org, query, offset, limit)).mapError(toResponse)
        case None => ZIO.succeed(UserSearchPage.empty)
    yield UserMatchPage(
      found.users.take(limit.min(MaxReach - offset)).map(matchView),
      found.total,
      found.nextOffset.filter(_ < MaxReach)
    )

  private def matchView(user: UserInfo): UserMatch = UserMatch(user.id.value, user.displayName, user.email)

  def toResponse(error: AccessDenied | DbError | UserDirectoryError): (StatusCode, ApiError) = error match
    case UserDirectoryError.Unavailable(reason) =>
      (
        StatusCode.ServiceUnavailable,
        ApiError("user_directory_unavailable", s"User names cannot be looked up right now: $reason")
      )
    case e: AccessDenied => DocumentApi.toResponse(e)
    case e: DbError      => DocumentApi.toResponse(e)
