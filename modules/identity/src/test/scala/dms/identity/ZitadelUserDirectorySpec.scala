package dms.identity

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import dms.kernel.UserId
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentLinkedQueue, Executors}
import scala.jdk.CollectionConverters.*

/** The ZITADEL adapter of the user directory against a fake ZITADEL (JDK HTTP server), and the cache in front
  * of it.
  */
object ZitadelUserDirectorySpec extends ZIOSpecDefault:

  private val org   = "org-1"
  private val token = "directory-token"

  /** A fake ZITADEL user service v2; `users` are the users it knows, as JSON objects of ListUsers. */
  final class FakeZitadel(users: List[String], status: Int = 200, delayMillis: Long = 0):
    val hits           = AtomicInteger(0)
    val requests       = ConcurrentLinkedQueue[(String, String, Option[String])]()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.setExecutor(Executors.newCachedThreadPool())
    server.createContext(
      "/",
      (exchange: HttpExchange) =>
        hits.incrementAndGet()
        val body = String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        requests.add(
          (exchange.getRequestURI.getPath, body, Option(exchange.getRequestHeaders.getFirst("Authorization")))
        )
        try
          if delayMillis > 0 then Thread.sleep(delayMillis)
          val ids = body
            .fromJson[Json]
            .toOption
            .flatMap(_.asObject)
            .flatMap(_.get("queries"))
            .flatMap(_.asArray)
            .toList
            .flatten
            .flatMap(
              _.asObject.flatMap(_.get("inUserIdsQuery")).flatMap(_.asObject).flatMap(_.get("userIds"))
            )
            .flatMap(_.asArray.toList.flatten.flatMap(_.asString))
            .toSet
          // Like ZITADEL: filters by id (the organization filter is left to the adapter's own check here).
          val answer = users.filter(u => ids.exists(id => u.contains(s"\"userId\":\"$id\"")))
          val bytes  = s"""{"details":{"totalResult":"${answer.size}"},"result":[${answer.mkString(",")}]}"""
            .getBytes(StandardCharsets.UTF_8)
          exchange.getResponseHeaders.add("Content-Type", "application/json")
          exchange.sendResponseHeaders(status, bytes.length.toLong)
          exchange.getResponseBody.write(bytes)
        catch case _: Exception => ()
        finally exchange.close()
    )
    server.start()
    def url: String  = s"http://127.0.0.1:${server.getAddress.getPort}"
    def stop(): Unit = server.stop(0)

  private def withZitadel[A](users: List[String], status: Int = 200, delayMillis: Long = 0)(
      f: FakeZitadel => ZIO[Any, Any, A]
  ): ZIO[Scope, Any, A] =
    ZIO
      .acquireRelease(ZIO.succeed(FakeZitadel(users, status, delayMillis)))(s => ZIO.succeed(s.stop()))
      .flatMap(f)

  private def human(id: String, first: String, family: String, display: String = "", owner: String = org) =
    s"""{"userId":"$id","details":{"resourceOwner":"$owner"},"username":"user-$id","human":{"profile":""" +
      s"""{"givenName":"$first","familyName":"$family","displayName":"$display"},"email":{"email":"$id@example.com"}}}"""

  private def machine(id: String, name: String) =
    s"""{"userId":"$id","details":{"resourceOwner":"$org"},"username":"svc-$id","machine":{"name":"$name"}}"""

  private def directory(url: String, timeoutMillis: Long = 1000) =
    ZitadelUserDirectory.make(url, token, Duration.fromMillis(timeoutMillis))

  private val known = List(
    human("1", "Anna", "Author", "Anna Author"),
    human("2", "Rita", "Reviewer"),
    machine("3", "Intranet"),
    human("9", "Other", "Tenant", "Other Tenant", owner = "org-2")
  )

  def spec = suite("ZitadelUserDirectory")(
    test("resolves names of the organization's users; unknown ids and other organizations stay out") {
      ZIO.scoped(withZitadel(known) { zitadel =>
        for
          d     <- directory(zitadel.url)
          found <- d.lookup(org, Set("1", "2", "3", "9", "404").map(UserId(_)))
          (path, body, auth) = zitadel.requests.asScala.head
        yield assertTrue(
          found.view.mapValues(_.displayName).toMap == Map(
            UserId("1") -> "Anna Author",
            UserId("2") -> "Rita Reviewer",
            UserId("3") -> "Intranet"
          ),
          found(UserId("1")).email.contains("1@example.com"),
          found(UserId("3")).email.isEmpty,
          path == "/v2/users",
          auth.contains(s"Bearer $token"),
          body.contains(s""""organizationIdQuery":{"organizationId":"$org"}"""),
          body.contains(""""inUserIdsQuery":{"userIds":["1","2","3","404","9"]}""")
        )
      })
    },
    test("asks in batches of at most 100 ids") {
      ZIO.scoped(withZitadel(known) { zitadel =>
        for
          d     <- directory(zitadel.url)
          found <- d.lookup(org, ((1 to 250).map(_.toString).toSet + "1").map(UserId(_)))
        yield assertTrue(zitadel.hits.get == 3, found.size == 3)
      })
    },
    test("an error status, a timeout and an unreachable ZITADEL are Unavailable") {
      for
        status <- ZIO.scoped(
          withZitadel(known, status = 403)(z =>
            directory(z.url).flatMap(_.lookup(org, Set(UserId("1")))).flip
          )
        )
        slow <- ZIO.scoped(
          withZitadel(known, delayMillis = 800)(z =>
            directory(z.url, timeoutMillis = 200).flatMap(_.lookup(org, Set(UserId("1")))).flip
          )
        )
        down <- directory("http://127.0.0.1:1").flatMap(_.lookup(org, Set(UserId("1")))).flip
      yield assertTrue(
        status == UserDirectoryError.Unavailable("ZITADEL answered with HTTP 403"),
        slow.toString.contains("did not answer within 200 ms"),
        down.toString.contains("not reachable")
      )
    },
    suite("CachedUserDirectory")(
      test("keeps resolved and unknown users until they expire; failures are not kept") {
        val calls   = Unsafe.unsafe(implicit u => Ref.unsafe.make(List.empty[Set[UserId]]))
        val failing = Unsafe.unsafe(implicit u => Ref.unsafe.make(false))
        val inner   = new UserDirectory:
          def lookup(organizationId: String, ids: Set[UserId]) =
            calls.update(_ :+ ids) *> failing.get.flatMap(fail =>
              if fail then ZIO.fail(UserDirectoryError.Unavailable("down"))
              else ZIO.succeed(ids.filter(_ == UserId("1")).map(id => id -> UserInfo(id, "Anna", None)).toMap)
            )
        for
          cached <- CachedUserDirectory.make(inner, 10.minutes, 100)
          first  <- cached.lookup(org, Set(UserId("1"), UserId("2")))
          again  <- cached.lookup(org, Set(UserId("1"), UserId("2")))
          other  <- cached.lookup("org-2", Set(UserId("1")))
          _      <- TestClock.adjust(11.minutes)
          _      <- failing.set(true)
          failed <- cached.lookup(org, Set(UserId("1"))).either
          _      <- failing.set(false)
          later  <- cached.lookup(org, Set(UserId("1")))
          asked  <- calls.get
        yield assertTrue(
          first.keySet == Set(UserId("1")),
          again == first,
          other.keySet == Set(UserId("1")),
          failed.isLeft,
          later.keySet == Set(UserId("1")),
          asked == List(
            Set(UserId("1"), UserId("2")),
            Set(UserId("1")),
            Set(UserId("1")),
            Set(UserId("1"))
          )
        )
      },
      test("keeps at most the configured number of users") {
        val inner = new UserDirectory:
          def lookup(organizationId: String, ids: Set[UserId]) =
            ZIO.succeed(ids.map(id => id -> UserInfo(id, id.value, None)).toMap)
        for
          cached <- CachedUserDirectory.make(inner, 10.minutes, 2)
          _      <- cached.lookup(org, Set(UserId("1"), UserId("2"), UserId("3")))
          size   <- cached.size
        yield assertTrue(size == 2)
      }
    )
  )
