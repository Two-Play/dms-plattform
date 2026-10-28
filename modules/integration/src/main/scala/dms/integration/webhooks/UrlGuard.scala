package dms.integration.webhooks

import zio.*

import java.net.{Inet4Address, Inet6Address, InetAddress, URI, UnknownHostException}
import scala.util.Try

/** Protection against server-side request forgery for webhook targets (ADR 0035): an administrator must not
  * be able to make the platform call internal services (cloud metadata, databases, admin consoles).
  *
  *   - The URL must be absolute `https` without user info; `http` only for [[WebhookSettings.allowedHosts]].
  *   - Every address the host resolves to must be public: loopback, private, link-local (e.g. the cloud
  *     metadata address 169.254.169.254), carrier-grade NAT, unique local, multicast, documentation and
  *     reserved ranges are blocked, unless the host is allowed or private networks are allowed.
  *
  * The check runs when a subscription is saved and again before every attempt (DNS can change). A host whose
  * DNS answer changes between the check and the connection (DNS rebinding) is not caught; an egress firewall
  * is the complementary measure (see ADR 0035).
  */
object UrlGuard:

  /** Checks the form of a URL. */
  def parse(url: String, settings: WebhookSettings): Either[String, URI] =
    for
      uri <- Try(URI(url.trim)).toEither.left.map(_ => s"'$url' is not a valid URL")
      _ <- Either.cond(uri.isAbsolute && uri.getHost != null, (), s"'$url' must be an absolute URL with host")
      host   = uri.getHost
      scheme = Option(uri.getScheme).map(_.toLowerCase).getOrElse("")
      _ <- Either.cond(uri.getRawUserInfo == null, (), "the URL must not contain user info")
      _ <- Either.cond(
        scheme == "https" || (scheme == "http" && settings.isAllowedHost(host)),
        (),
        s"the URL must use https (http only for the allowed development hosts)"
      )
      _ <- Either.cond(uri.getRawFragment == null, (), "the URL must not contain a fragment")
    yield uri

  /** Resolves the host and checks every address. */
  def check(uri: URI, settings: WebhookSettings): IO[String, Unit] =
    val host = uri.getHost
    if settings.isAllowedHost(host) then ZIO.unit
    else
      ZIO
        .attemptBlocking(InetAddress.getAllByName(host).toList)
        .mapError {
          case _: UnknownHostException => s"the host $host cannot be resolved"
          case e                       => s"the host $host cannot be resolved: ${e.getMessage}"
        }
        .flatMap { addresses =>
          val blocked =
            addresses.filterNot(a => (settings.allowPrivateNetworks && isPrivate(a)) || isPublic(a))
          ZIO
            .unless(blocked.isEmpty)(
              ZIO.fail(
                s"the host $host resolves to a non-public address (${blocked.map(_.getHostAddress).mkString(", ")})"
              )
            )
            .unit
        }

  /** Parses and checks a URL. */
  def validate(url: String, settings: WebhookSettings): IO[String, URI] =
    ZIO.fromEither(parse(url, settings)).tap(check(_, settings))

  /** Addresses of private networks (RFC 1918, unique local IPv6), which
    * [[WebhookSettings.allowPrivateNetworks]] opens. Loopback, link-local and metadata addresses are never
    * private in this sense.
    */
  def isPrivate(address: InetAddress): Boolean = address match
    case a: Inet4Address => a.isSiteLocalAddress
    case a: Inet6Address => (a.getAddress()(0) & 0xfe) == 0xfc
    case _               => false

  /** Whether an address is a public unicast address. */
  def isPublic(address: InetAddress): Boolean =
    !(address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
      address.isSiteLocalAddress || address.isMulticastAddress) && (address match
      case a: Inet4Address => !reservedV4(a.getAddress.map(_ & 0xff))
      case a: Inet6Address => !reservedV6(a.getAddress.map(_ & 0xff))
      case _               => false)

  private def reservedV4(b: Array[Int]): Boolean =
    b(0) == 0 ||                                                // "this" network
      (b(0) == 100 && (b(1) & 0xc0) == 64) ||                   // carrier-grade NAT 100.64.0.0/10
      (b(0) == 192 && b(1) == 0 && (b(2) == 0 || b(2) == 2)) || // IETF 192.0.0.0/24, TEST-NET-1
      (b(0) == 198 && (b(1) & 0xfe) == 18) ||                   // benchmarking 198.18.0.0/15
      (b(0) == 198 && b(1) == 51 && b(2) == 100) ||             // TEST-NET-2
      (b(0) == 203 && b(1) == 0 && b(2) == 113) ||              // TEST-NET-3
      b(0) >= 224                                               // multicast, reserved, broadcast

  private def reservedV6(b: Array[Int]): Boolean =
    (b(0) & 0xfe) == 0xfc ||                                            // unique local fc00::/7
      (b(0) == 0x20 && b(1) == 0x01 && b(2) == 0x0d && b(3) == 0xb8) || // documentation 2001:db8::/32
      (b(0) == 0x00 && b(1) == 0x64 && b(2) == 0xff && b(3) == 0x9b) || // NAT64 64:ff9b::/96
      (b(0) == 0x20 && b(1) == 0x02) ||                                 // 6to4 2002::/16 (embeds IPv4)
      b.take(12).forall(_ == 0)                                         // IPv4-compatible ::a.b.c.d
