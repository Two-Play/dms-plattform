package dms.integration.partners

import dms.kernel.TenantId
import zio.*
import zio.json.*

import java.io.IOException
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets

/** Settings of the partner master (`dms.partners`, ADR 0023).
  *
  * @param directory
  *   `demo` (in-memory demo partners, for development) or `http` (the partner master's HTTP API at `baseUrl`)
  * @param baseUrl
  *   e.g. `https://crm.example.com/api`; the adapter calls `<baseUrl>/partners?q=&limit=` and
  *   `<baseUrl>/partners/{id}`
  * @param timeoutMillis
  *   per request, including connecting; an author waits at most this long
  * @param apiToken
  *   sent as bearer token if set
  * @param failureThreshold
  *   failures in a row after which calls are rejected at once ...
  * @param openSeconds
  *   ... for this many seconds, then one trial call is let through
  */
final case class PartnerDirectoryConfig(
    directory: String,
    baseUrl: Option[String],
    timeoutMillis: Int,
    apiToken: Option[String],
    failureThreshold: Int,
    openSeconds: Int
)

/** JSON of the partner master's HTTP API (the contract the adapter expects, see the module README). */
private[partners] object PartnerJson:
  final case class Entry(
      id: String,
      name: String,
      street: Option[String] = None,
      postalCode: Option[String] = None,
      city: Option[String] = None,
      country: Option[String] = None,
      vatId: Option[String] = None
  ) derives JsonDecoder:
    def toPartner: Either[String, Partner] =
      Either.cond(
        id.trim.nonEmpty && name.trim.nonEmpty,
        Partner(id, name, PostalAddress(street, postalCode, city, country), vatId),
        "a partner without id or name"
      )

  final case class Page(items: List[Entry]) derives JsonDecoder

/** [[PartnerDirectory]] over the partner master's HTTP API, with a timeout per request and a
  * [[CircuitBreaker]]. Every failure is typed: unreachable, timeout and error statuses are `Unavailable`, a
  * payload that does not fit is `InvalidResponse`.
  *
  * The tenant is sent as header `X-Dms-Tenant`, so one partner master service can serve several tenants.
  */
final class HttpPartnerDirectory(
    baseUrl: URI,
    timeout: Duration,
    apiToken: Option[String],
    client: HttpClient,
    breaker: CircuitBreaker
) extends PartnerDirectory:
  import HttpPartnerDirectory.*
  import PartnerDirectoryError.*

  def search(tenant: TenantId, query: String, limit: Int): IO[PartnerDirectoryError, List[Partner]] =
    val path = s"partners?q=${encode(query.trim)}&limit=${limit.max(1)}"
    call(tenant, path) { case (200, body) =>
      body
        .fromJson[PartnerJson.Page]
        .flatMap(page => traverse(page.items.map(_.toPartner)))
        .map(_.take(limit.max(1)))
        .left
        .map(e => InvalidResponse(s"the partner master answered with an unexpected search result: $e"))
    }

  def get(tenant: TenantId, id: String): IO[PartnerDirectoryError, Option[Partner]] =
    call(tenant, s"partners/${encode(id)}") {
      case (404, _)    => Right(None)
      case (200, body) =>
        body
          .fromJson[PartnerJson.Entry]
          .flatMap(_.toPartner)
          .map(Some(_))
          .left
          .map(e => InvalidResponse(s"the partner master answered with an unexpected partner: $e"))
    }

  private def call[A](tenant: TenantId, path: String)(
      handle: PartialFunction[(Int, String), Either[PartnerDirectoryError, A]]
  ): IO[PartnerDirectoryError, A] =
    val request = apiToken
      .foldLeft(
        HttpRequest
          .newBuilder(baseUrl.resolve(path))
          .timeout(java.time.Duration.ofMillis(timeout.toMillis))
          .header("Accept", "application/json")
          .header("X-Dms-Tenant", tenant.value.toString)
          .GET()
      )((b, token) => b.header("Authorization", s"Bearer $token"))
      .build()
    val exchange = ZIO
      .fromCompletableFuture(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
      .mapError {
        case _: HttpTimeoutException => timedOut
        case e: IOException          => Unavailable(s"the partner master is not reachable (${describe(e)})")
        case e                       => Unavailable(s"the call to the partner master failed (${describe(e)})")
      }
      // The request timeout covers the exchange; this one also bounds a stuck future.
      .timeoutFail(timedOut)(timeout.plusMillis(250))
      .flatMap { response =>
        val status = response.statusCode()
        ZIO.fromEither(
          handle
            .lift((status, response.body()))
            .getOrElse(Left(Unavailable(s"the partner master answered with HTTP $status")))
        )
      }
    breaker(exchange)(
      _ => true,
      Unavailable("the partner master failed repeatedly; calls are paused briefly")
    )

  private def timedOut =
    Unavailable(s"the partner master did not answer within ${timeout.toMillis} ms")

object HttpPartnerDirectory:

  def make(config: PartnerDirectoryConfig, baseUrl: String): Task[HttpPartnerDirectory] =
    for
      uri <- ZIO.attempt(URI.create(if baseUrl.endsWith("/") then baseUrl else s"$baseUrl/"))
      _   <- ZIO.unless(Set("http", "https").contains(Option(uri.getScheme).getOrElse("")))(
        ZIO.fail(IllegalArgumentException(s"dms.partners.baseUrl must be an http(s) URL: $baseUrl"))
      )
      timeout = Duration.fromMillis(config.timeoutMillis.max(1).toLong)
      client  = HttpClient
        .newBuilder()
        .connectTimeout(java.time.Duration.ofMillis(timeout.toMillis))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
      breaker <- CircuitBreaker.make(config.failureThreshold, Duration.fromSeconds(config.openSeconds.toLong))
    yield HttpPartnerDirectory(uri, timeout, config.apiToken.filter(_.nonEmpty), client, breaker)

  private def encode(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")

  private def describe(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

  private def traverse[A](items: List[Either[String, A]]): Either[String, List[A]] =
    items.foldRight(Right(Nil): Either[String, List[A]])((item, acc) => item.flatMap(a => acc.map(a :: _)))

/** The configured [[PartnerDirectory]]. */
object PartnerDirectories:
  def layer(config: PartnerDirectoryConfig): TaskLayer[PartnerDirectory] =
    ZLayer.fromZIO(config.directory match
      case "demo" =>
        ZIO.logWarning("Partner master: demo partners in memory (dms.partners.directory = demo)") *>
          ZIO.succeed(InMemoryPartnerDirectory.demo)
      case "http" =>
        ZIO
          .fromOption(config.baseUrl.filter(_.trim.nonEmpty))
          .orElseFail(IllegalArgumentException("dms.partners.baseUrl is required for directory = http"))
          .flatMap(url => HttpPartnerDirectory.make(config, url.trim))
      case other =>
        ZIO.fail(IllegalArgumentException(s"unknown dms.partners.directory '$other'; use demo or http")))
