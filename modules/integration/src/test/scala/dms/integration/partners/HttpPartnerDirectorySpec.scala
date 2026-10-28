package dms.integration.partners

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import dms.kernel.TenantId
import zio.*
import zio.test.*

import java.net.{InetSocketAddress, ServerSocket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentLinkedQueue, Executors}
import scala.jdk.CollectionConverters.*

/** The HTTP adapter against a fake partner master (JDK HTTP server): answers, missing partners, an
  * unreachable host, timeouts, error statuses, payloads that do not fit, and the circuit breaker.
  */
object HttpPartnerDirectorySpec extends ZIOSpecDefault:

  private val tenant = TenantId.random()

  /** A fake partner master; `respond` maps the request path and query to status and body. */
  final class FakeServer(respond: String => (Int, String, Long)):
    val hits           = AtomicInteger(0)
    val requests       = ConcurrentLinkedQueue[(String, Option[String], Option[String])]()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.setExecutor(Executors.newCachedThreadPool())
    server.createContext(
      "/",
      (exchange: HttpExchange) =>
        hits.incrementAndGet()
        val uri    = exchange.getRequestURI
        val target = uri.getRawPath + Option(uri.getRawQuery).fold("")("?" + _)
        requests.add(
          (
            target,
            Option(exchange.getRequestHeaders.getFirst("X-Dms-Tenant")),
            Option(exchange.getRequestHeaders.getFirst("Authorization"))
          )
        )
        val (status, body, delayMillis) = respond(target)
        try
          if delayMillis > 0 then Thread.sleep(delayMillis)
          val bytes = body.getBytes(StandardCharsets.UTF_8)
          exchange.getResponseHeaders.add("Content-Type", "application/json")
          exchange.sendResponseHeaders(status, if bytes.isEmpty then -1 else bytes.length.toLong)
          if bytes.nonEmpty then exchange.getResponseBody.write(bytes)
        catch case _: Exception => ()
        finally exchange.close()
    )
    server.start()
    def url: String  = s"http://127.0.0.1:${server.getAddress.getPort}/api"
    def stop(): Unit = server.stop(0)

  private def withServer[A](respond: String => (Int, String, Long))(
      f: FakeServer => ZIO[Any, Any, A]
  ): ZIO[Scope, Any, A] =
    ZIO.acquireRelease(ZIO.succeed(FakeServer(respond)))(s => ZIO.succeed(s.stop())).flatMap(f)

  private def config(timeoutMillis: Int = 500, failureThreshold: Int = 3, openSeconds: Int = 30) =
    PartnerDirectoryConfig("http", None, timeoutMillis, Some("secret-token"), failureThreshold, openSeconds)

  private def directory(url: String, c: PartnerDirectoryConfig = config()) = HttpPartnerDirectory.make(c, url)

  private val nordlicht =
    """{"id":"P-10001","name":"Nordlicht Logistik GmbH","street":"Hafenstraße 12","postalCode":"20457",
      |"city":"Hamburg","country":"DE","vatId":"DE123456789"}""".stripMargin

  private def ok(body: String) = (200, body, 0L)

  def spec = suite("HttpPartnerDirectory")(
    test("searches and gets partners; sends tenant and token; a missing partner is None") {
      ZIO.scoped(withServer {
        case p if p.startsWith("/api/partners?") => ok(s"""{"items":[$nordlicht]}""")
        case "/api/partners/P-10001"             => ok(nordlicht)
        case _                                   => (404, "", 0L)
      } { server =>
        for
          d       <- directory(server.url)
          hits    <- d.search(tenant, "nord licht&co", 10)
          partner <- d.get(tenant, "P-10001")
          missing <- d.get(tenant, "P-1/../x")
          requests = server.requests.asScala.toList
        yield assertTrue(
          hits.map(_.id) == List("P-10001"),
          hits.head.address == PostalAddress(
            Some("Hafenstraße 12"),
            Some("20457"),
            Some("Hamburg"),
            Some("DE")
          ),
          partner.map(_.attribute("address")) == Some(Some("Hafenstraße 12, 20457 Hamburg, DE")),
          missing.isEmpty,
          requests.head._1 == "/api/partners?q=nord%20licht%26co&limit=10",
          requests(2)._1 == "/api/partners/P-1%2F..%2Fx",
          requests.forall(_._2.contains(tenant.value.toString)),
          requests.forall(_._3.contains("Bearer secret-token"))
        )
      })
    },
    test("an unreachable partner master is Unavailable") {
      for
        port <- ZIO.attemptBlocking {
          val socket = ServerSocket(0)
          try socket.getLocalPort
          finally socket.close()
        }
        d      <- directory(s"http://127.0.0.1:$port")
        result <- d.search(tenant, "nord", 10).either
      yield assertTrue(result.left.exists {
        case PartnerDirectoryError.Unavailable(reason) => reason.contains("not reachable")
        case _                                         => false
      })
    },
    test("a partner master that does not answer in time is Unavailable after the timeout") {
      ZIO.scoped(withServer(_ => (200, s"""{"items":[$nordlicht]}""", 3000L)) { server =>
        for
          d       <- directory(server.url, config(timeoutMillis = 200))
          started <- Clock.nanoTime
          result  <- d.search(tenant, "nord", 10).either
          elapsed <- Clock.nanoTime.map(n => (n - started) / 1000000)
        yield assertTrue(
          result.left.exists {
            case PartnerDirectoryError.Unavailable(reason) => reason.contains("did not answer within 200 ms")
            case _                                         => false
          },
          elapsed < 2000L
        )
      })
    } @@ TestAspect.withLiveClock,
    test("error statuses are Unavailable, payloads that do not fit are InvalidResponse") {
      ZIO.scoped(withServer {
        case p if p.startsWith("/api/partners?q=broken") => ok("""{"items":[{"name":"No id"}]}""")
        case p if p.startsWith("/api/partners?q=html")   => ok("<html>login</html>")
        case "/api/partners/P-1"                         => ok("""{"id":"","name":"Empty id"}""")
        case _                                           => (500, """{"error":"boom"}""", 0L)
      } { server =>
        for
          d      <- directory(server.url, config(failureThreshold = 100))
          broken <- d.search(tenant, "broken", 10).either
          html   <- d.search(tenant, "html", 10).either
          empty  <- d.get(tenant, "P-1").either
          failed <- d.search(tenant, "other", 10).either
        yield assertTrue(
          broken.left.exists(_.isInstanceOf[PartnerDirectoryError.InvalidResponse]),
          html.left.exists(_.isInstanceOf[PartnerDirectoryError.InvalidResponse]),
          empty.left.exists(_.isInstanceOf[PartnerDirectoryError.InvalidResponse]),
          failed == Left(PartnerDirectoryError.Unavailable("the partner master answered with HTTP 500"))
        )
      })
    },
    test("after repeated failures calls are rejected at once, then one trial call closes the breaker again") {
      val healthy = AtomicInteger(0)
      ZIO.scoped(withServer { _ =>
        if healthy.get() == 1 then ok(s"""{"items":[$nordlicht]}""") else (503, "", 0L)
      } { server =>
        for
          d      <- directory(server.url, config(failureThreshold = 2, openSeconds = 1))
          _      <- d.search(tenant, "nord", 10).either.repeatN(1)
          open   <- d.search(tenant, "nord", 10).either
          hits   <- ZIO.succeed(server.hits.get())
          _      <- ZIO.succeed(healthy.set(1)) *> ZIO.sleep(1100.millis)
          trial  <- d.search(tenant, "nord", 10).either
          closed <- d.search(tenant, "nord", 10).either
        yield assertTrue(
          hits == 2,
          open.left.exists {
            case PartnerDirectoryError.Unavailable(reason) => reason.contains("paused")
            case _                                         => false
          },
          trial.isRight,
          closed.isRight,
          server.hits.get() == 4
        )
      })
    } @@ TestAspect.withLiveClock,
    test("the configured directory: demo, http with a base URL, nothing else") {
      for
        demo    <- ZIO.scoped(PartnerDirectories.layer(config().copy(directory = "demo")).build).either
        noUrl   <- ZIO.scoped(PartnerDirectories.layer(config()).build).either
        badUrl  <- ZIO.scoped(PartnerDirectories.layer(config().copy(baseUrl = Some("ftp://x"))).build).either
        unknown <- ZIO.scoped(PartnerDirectories.layer(config().copy(directory = "ldap")).build).either
        http    <- ZIO
          .scoped(PartnerDirectories.layer(config().copy(baseUrl = Some("http://localhost:1"))).build)
          .either
      yield assertTrue(demo.isRight, noUrl.isLeft, badUrl.isLeft, unknown.isLeft, http.isRight)
    }
  ) @@ TestAspect.sequential

object InMemoryPartnerDirectorySpec extends ZIOSpecDefault:
  private val tenant = TenantId.random()

  def spec = suite("InMemoryPartnerDirectory")(
    test("finds partners by name and identifier, case-insensitive, by name, limited") {
      val d = InMemoryPartnerDirectory.demo
      for
        byName <- d.search(tenant, "LOGISTIK", 10)
        byId   <- d.search(tenant, "p-1000", 10)
        two    <- d.search(tenant, "p-", 2)
        get    <- d.get(tenant, "P-20002")
        none   <- d.get(tenant, "P-0")
      yield assertTrue(
        byName.map(_.id) == List("P-10001"),
        byId.map(_.name) == List("Beispiel Software AG", "Muster & Partner KG", "Nordlicht Logistik GmbH"),
        two.size == 2,
        get.exists(_.attribute("vatId").contains("CHE-123.456.789")),
        get.exists(_.attribute("unknown").isEmpty),
        none.isEmpty
      )
    },
    test("every attribute a reference field may take over is known") {
      val partner = InMemoryPartnerDirectory.demoPartners.head
      assertTrue(Partner.Attributes.forall(a => partner.attribute(a).isDefined))
    }
  )
