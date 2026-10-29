package dms.identity

import dms.kernel.UserId
import zio.*
import zio.json.*

import java.io.IOException
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Settings of the user directory (`dms.users`, DMS-110).
  *
  * @param url
  *   base URL of the ZITADEL API; defaults to the issuer (`dms.auth.issuer`)
  * @param tokenFile
  *   file with the personal access token of a ZITADEL service user that may read users (e.g. instance role
  *   `IAM_OWNER_VIEWER`, or `ORG_OWNER_VIEWER` in a single-organization setup). Read once at start-up.
  * @param token
  *   the token itself, if not from a file (e.g. from a secret store via environment variable)
  * @param timeoutMillis
  *   per request to ZITADEL, including connecting
  * @param cacheSeconds
  *   how long a resolved (or unknown) user is kept
  * @param cacheSize
  *   at most this many users are kept; the oldest entries go first
  * @param searchCacheSeconds
  *   how long the answer to a search for people is kept (DMS-111); short, so new colleagues show up soon
  *
  * Without `tokenFile` and `token` there is no directory: user ids stay unresolved.
  */
final case class UserDirectoryConfig(
    url: Option[String],
    tokenFile: Option[String],
    token: Option[String],
    timeoutMillis: Int,
    cacheSeconds: Int,
    cacheSize: Int,
    searchCacheSeconds: Int
)

/** JSON of ZITADEL's user service v2 (`POST /v2/users`, ListUsers), only the parts the directory reads. */
private[identity] object ZitadelJson:
  final case class Details(resourceOwner: Option[String] = None) derives JsonDecoder
  final case class Profile(
      givenName: Option[String] = None,
      familyName: Option[String] = None,
      displayName: Option[String] = None
  ) derives JsonDecoder
  final case class Email(email: Option[String] = None) derives JsonDecoder
  final case class Human(profile: Option[Profile] = None, email: Option[Email] = None) derives JsonDecoder
  final case class Machine(name: Option[String] = None) derives JsonDecoder
  final case class User(
      userId: String,
      details: Option[Details] = None,
      state: Option[String] = None,
      username: Option[String] = None,
      human: Option[Human] = None,
      machine: Option[Machine] = None
  ) derives JsonDecoder

  final case class ListUsersResponse(result: Option[List[User]] = None) derives JsonDecoder

  final case class Pagination(limit: Int, asc: Option[Boolean] = None) derives JsonEncoder
  final case class InUserIds(userIds: List[String]) derives JsonEncoder
  final case class OrganizationId(organizationId: String) derives JsonEncoder

  // Search for people (DMS-111): text queries on single fields, combined with OR per word.
  final case class FirstName(firstName: String, method: String) derives JsonEncoder
  final case class LastName(lastName: String, method: String) derives JsonEncoder
  final case class DisplayName(displayName: String, method: String) derives JsonEncoder
  final case class EmailAddress(emailAddress: String, method: String) derives JsonEncoder
  final case class UserName(userName: String, method: String) derives JsonEncoder
  final case class TextQuery(
      firstNameQuery: Option[FirstName] = None,
      lastNameQuery: Option[LastName] = None,
      displayNameQuery: Option[DisplayName] = None,
      emailQuery: Option[EmailAddress] = None,
      userNameQuery: Option[UserName] = None
  ) derives JsonEncoder
  final case class AnyOf(queries: List[TextQuery]) derives JsonEncoder
  final case class UserType(`type`: String) derives JsonEncoder
  final case class UserState(state: String) derives JsonEncoder
  final case class StateQuery(stateQuery: UserState) derives JsonEncoder
  final case class NotQuery(query: StateQuery) derives JsonEncoder

  final case class Query(
      inUserIdsQuery: Option[InUserIds] = None,
      organizationIdQuery: Option[OrganizationId] = None,
      typeQuery: Option[UserType] = None,
      notQuery: Option[NotQuery] = None,
      orQuery: Option[AnyOf] = None
  ) derives JsonEncoder
  final case class ListUsersRequest(
      query: Pagination,
      queries: List[Query],
      sortingColumn: Option[String] = None
  ) derives JsonEncoder

  private val StartsWith = "TEXT_QUERY_METHOD_STARTS_WITH_IGNORE_CASE"

  /** Active human users of the organization whose first name, last name, display name, email or user name
    * starts with each of the words; sorted by display name.
    */
  def searchRequest(organizationId: String, query: UserQuery, limit: Int): ListUsersRequest =
    val words = query.words.map(word =>
      Query(orQuery =
        Some(
          AnyOf(
            List(
              TextQuery(firstNameQuery = Some(FirstName(word, StartsWith))),
              TextQuery(lastNameQuery = Some(LastName(word, StartsWith))),
              TextQuery(displayNameQuery = Some(DisplayName(word, StartsWith))),
              TextQuery(emailQuery = Some(EmailAddress(word, StartsWith))),
              TextQuery(userNameQuery = Some(UserName(word, StartsWith)))
            )
          )
        )
      )
    )
    ListUsersRequest(
      Pagination(limit, asc = Some(true)),
      Query(organizationIdQuery = Some(OrganizationId(organizationId))) ::
        Query(typeQuery = Some(UserType("TYPE_HUMAN"))) ::
        Query(notQuery = Some(NotQuery(StateQuery(UserState("USER_STATE_INACTIVE"))))) ::
        words,
      sortingColumn = Some("USER_FIELD_NAME_DISPLAY_NAME")
    )

  /** States in which a user cannot be picked; ZITADEL filters inactive users already. */
  val Unpickable = Set("USER_STATE_INACTIVE", "USER_STATE_DELETED")

  private def text(value: Option[String]): Option[String] = value.map(_.trim).filter(_.nonEmpty)

  /** Display name, then given and family name, then the user name; machine users by their name. */
  def toUserInfo(user: User): Option[UserInfo] =
    val profile  = user.human.flatMap(_.profile)
    val fullName = text(
      Some(List(profile.flatMap(_.givenName), profile.flatMap(_.familyName)).flatMap(text).mkString(" "))
    )
    val name = text(profile.flatMap(_.displayName))
      .orElse(fullName)
      .orElse(text(user.machine.flatMap(_.name)))
      .orElse(text(user.username))
    name.map(UserInfo(UserId(user.userId), _, text(user.human.flatMap(_.email).flatMap(_.email))))

/** [[UserDirectory]] over ZITADEL's user service v2: one `POST /v2/users` per batch of at most
  * [[ZitadelUserDirectory.BatchSize]] ids, filtered by the ids and the organization. Answers of users of
  * another organization are dropped as well, should the filter ever be ignored.
  */
final class ZitadelUserDirectory(baseUrl: URI, token: String, timeout: Duration, client: HttpClient)
    extends UserDirectory:
  import ZitadelJson.*
  import ZitadelUserDirectory.*

  def lookup(organizationId: String, ids: Set[UserId]): IO[UserDirectoryError, Map[UserId, UserInfo]] =
    ZIO
      .foreach(ids.toList.map(_.value).sorted.grouped(BatchSize).toList)(batch => list(organizationId, batch))
      .map(_.flatten.map(u => u.id -> u).toMap)

  override def search(
      organizationId: String,
      query: UserQuery,
      limit: Int
  ): IO[UserDirectoryError, List[UserInfo]] =
    if limit <= 0 then ZIO.succeed(Nil)
    else
      listUsers(searchRequest(organizationId, query, limit)).map(
        _.filter(_.details.flatMap(_.resourceOwner).contains(organizationId))
          .filter(_.human.isDefined)
          .filterNot(_.state.exists(Unpickable.contains))
          .flatMap(toUserInfo)
          .sortBy(u => (u.displayName.toLowerCase(java.util.Locale.ROOT), u.id.value))
          .take(limit)
      )

  private def list(organizationId: String, ids: List[String]): IO[UserDirectoryError, List[UserInfo]] =
    listUsers(
      ListUsersRequest(
        Pagination(ids.size),
        List(
          Query(inUserIdsQuery = Some(InUserIds(ids))),
          Query(organizationIdQuery = Some(OrganizationId(organizationId)))
        )
      )
    ).map(
      _.filter(u => ids.contains(u.userId))
        .filter(_.details.flatMap(_.resourceOwner).contains(organizationId))
        .flatMap(toUserInfo)
    )

  private def listUsers(listRequest: ListUsersRequest): IO[UserDirectoryError, List[User]] =
    val body    = listRequest.toJson
    val request = HttpRequest
      .newBuilder(baseUrl.resolve("v2/users"))
      .timeout(java.time.Duration.ofMillis(timeout.toMillis))
      .header("Accept", "application/json")
      .header("Content-Type", "application/json")
      .header("Authorization", s"Bearer $token")
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
      .build()
    ZIO
      .fromCompletableFuture(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
      .mapError {
        case _: HttpTimeoutException => timedOut
        case e: IOException => UserDirectoryError.Unavailable(s"ZITADEL is not reachable (${describe(e)})")
        case e              => UserDirectoryError.Unavailable(s"the call to ZITADEL failed (${describe(e)})")
      }
      .timeoutFail(timedOut)(timeout.plusMillis(250))
      .flatMap { response =>
        response.statusCode() match
          case 200 =>
            ZIO
              .fromEither(response.body().fromJson[ListUsersResponse])
              .mapError(e =>
                UserDirectoryError.Unavailable(s"ZITADEL answered with an unexpected user list: $e")
              )
              .map(_.result.getOrElse(Nil))
          case status => ZIO.fail(UserDirectoryError.Unavailable(s"ZITADEL answered with HTTP $status"))
      }

  private def timedOut =
    UserDirectoryError.Unavailable(s"ZITADEL did not answer within ${timeout.toMillis} ms")

object ZitadelUserDirectory:
  /** Ids per request to ZITADEL. */
  val BatchSize = 100

  private def describe(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

  def make(baseUrl: String, token: String, timeout: Duration): Task[ZitadelUserDirectory] =
    for
      uri <- ZIO.attempt(URI.create(if baseUrl.endsWith("/") then baseUrl else s"$baseUrl/"))
      _   <- ZIO.unless(Set("http", "https").contains(Option(uri.getScheme).getOrElse("")))(
        ZIO.fail(IllegalArgumentException(s"the ZITADEL URL of the user directory must be http(s): $baseUrl"))
      )
      client = HttpClient
        .newBuilder()
        .connectTimeout(java.time.Duration.ofMillis(timeout.toMillis))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    yield ZitadelUserDirectory(uri, token, timeout, client)

/** Keeps resolved and unknown users per organization for a while, bounded in size, so pages listing the same
  * people do not ask ZITADEL again. Answers to searches for people (DMS-111) are kept per organization, query
  * and limit for `searchTtl`, at most [[CachedUserDirectory.MaxSearches]] of them; the people found are kept
  * as resolved users as well, so their names show without another request. Failures are not cached.
  */
final class CachedUserDirectory(
    underlying: UserDirectory,
    ttl: Duration,
    maxEntries: Int,
    searchTtl: Duration,
    entries: Ref.Synchronized[Map[(String, UserId), CachedUserDirectory.Entry]],
    searches: Ref.Synchronized[Map[(String, String, Int), CachedUserDirectory.Found]]
) extends UserDirectory:
  import CachedUserDirectory.{Entry, Found, MaxSearches}

  def lookup(organizationId: String, ids: Set[UserId]): IO[UserDirectoryError, Map[UserId, UserInfo]] =
    for
      now    <- Clock.instant
      cached <- entries.get
      fresh = ids.flatMap(id =>
        cached.get((organizationId, id)).filter(_.expiresAt.isAfter(now)).map(id -> _)
      )
      missing = ids -- fresh.map(_._1)
      fetched <-
        if missing.isEmpty then ZIO.succeed(Map.empty) else underlying.lookup(organizationId, missing)
      _ <- ZIO.when(missing.nonEmpty)(remember(organizationId, missing.map(id => id -> fetched.get(id)), now))
    yield fresh.flatMap((id, entry) => entry.user.map(id -> _)).toMap ++ fetched

  override def search(
      organizationId: String,
      query: UserQuery,
      limit: Int
  ): IO[UserDirectoryError, List[UserInfo]] =
    val key = (organizationId, query.text, limit)
    for
      now    <- Clock.instant
      cached <- searches.get.map(_.get(key).filter(_.expiresAt.isAfter(now)))
      found  <- cached match
        case Some(hit) => ZIO.succeed(hit.users)
        case None      =>
          for
            users <- underlying.search(organizationId, query, limit)
            _     <- searches.update { current =>
              val merged = current.filter(_._2.expiresAt.isAfter(now)) +
                (key -> Found(users, now.plusMillis(searchTtl.toMillis)))
              if merged.size <= MaxSearches then merged
              else merged.toList.sortBy(_._2.expiresAt).takeRight(MaxSearches).toMap
            }
            _ <- remember(organizationId, users.map(u => u.id -> Some(u)).toSet, now)
          yield users
    yield found

  private def remember(
      organizationId: String,
      users: Set[(UserId, Option[UserInfo])],
      now: java.time.Instant
  ) =
    entries.update { current =>
      val expiresAt = now.plusMillis(ttl.toMillis)
      val added     = users.map((id, user) => (organizationId, id) -> Entry(user, expiresAt))
      val merged    = current.filter(_._2.expiresAt.isAfter(now)) ++ added
      if merged.size <= maxEntries then merged
      else merged.toList.sortBy(_._2.expiresAt).takeRight(maxEntries).toMap
    }

  /** Number of kept entries, for tests and diagnostics. */
  def size: UIO[Int] = entries.get.map(_.size)

  /** Number of kept search answers, for tests and diagnostics. */
  def searchesKept: UIO[Int] = searches.get.map(_.size)

object CachedUserDirectory:
  final case class Entry(user: Option[UserInfo], expiresAt: java.time.Instant)
  final case class Found(users: List[UserInfo], expiresAt: java.time.Instant)

  /** Search answers kept at most; a picker asks one per typed prefix. */
  val MaxSearches = 1000

  def make(
      underlying: UserDirectory,
      ttl: Duration,
      maxEntries: Int,
      searchTtl: Duration = 1.minute
  ): UIO[CachedUserDirectory] =
    for
      entries  <- Ref.Synchronized.make(Map.empty[(String, UserId), Entry])
      searches <- Ref.Synchronized.make(Map.empty[(String, String, Int), Found])
    yield CachedUserDirectory(underlying, ttl, maxEntries.max(1), searchTtl, entries, searches)

object UserDirectories:

  /** The configured directory: ZITADEL with cache if a token is configured, otherwise [[UserDirectory.none]].
    */
  def layer(config: UserDirectoryConfig, issuer: String): TaskLayer[UserDirectory] =
    ZLayer.fromZIO(
      for
        fromFile <- ZIO.foreach(config.tokenFile.map(_.trim).filter(_.nonEmpty))(path =>
          ZIO
            .attemptBlocking(Files.readString(Path.of(path)).trim)
            .mapError(e =>
              IllegalArgumentException(s"cannot read dms.users.tokenFile '$path': ${describe(e)}")
            )
        )
        token = config.token.map(_.trim).filter(_.nonEmpty).orElse(fromFile.filter(_.nonEmpty))
        directory <- token match
          case None =>
            ZIO
              .logWarning(
                "User directory: no ZITADEL token configured (dms.users); user ids are not resolved"
              )
              .as(UserDirectory.none)
          case Some(t) =>
            val url = config.url.map(_.trim).filter(_.nonEmpty).getOrElse(issuer)
            for
              zitadel <- ZitadelUserDirectory.make(
                url,
                t,
                Duration.fromMillis(config.timeoutMillis.max(1).toLong)
              )
              cached <- CachedUserDirectory.make(
                zitadel,
                Duration.fromSeconds(config.cacheSeconds.max(0).toLong),
                config.cacheSize,
                Duration.fromSeconds(config.searchCacheSeconds.max(0).toLong)
              )
              _ <- ZIO.logInfo(s"User directory: ZITADEL at $url, cached for ${config.cacheSeconds} s")
            yield cached
      yield directory
    )

  private def describe(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)
