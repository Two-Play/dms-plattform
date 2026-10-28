package dms.integration.webhooks

import com.sun.net.httpserver.HttpServer
import zio.*

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** A webhook receiver for tests on 127.0.0.1: records every request and answers with `status`. */
final class FakeReceiver private (server: HttpServer, val status: AtomicInteger, received: Ref[Vector[FakeReceiver.Request]]):
  def url: String = s"http://127.0.0.1:${server.getAddress.getPort}/hook"

  def requests: UIO[Vector[FakeReceiver.Request]] = received.get

  /** Answers 2xx from now on. */
  def up: UIO[Unit] = ZIO.succeed(status.set(204))

  /** Answers 503 from now on, like a receiver in maintenance. */
  def down: UIO[Unit] = ZIO.succeed(status.set(503))

object FakeReceiver:
  final case class Request(headers: Map[String, String], body: String):
    def header(name: String): String = headers.getOrElse(name.toLowerCase, "")

  def make: ZIO[Scope, Throwable, FakeReceiver] =
    for
      received <- Ref.make(Vector.empty[Request])
      status = AtomicInteger(204)
      server <- ZIO.acquireRelease(ZIO.attemptBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
          "/hook",
          exchange =>
            val body    = String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
            val headers = exchange.getRequestHeaders.asScala.map((k, v) => k.toLowerCase -> v.asScala.mkString(",")).toMap
            Unsafe.unsafe(implicit u =>
              Runtime.default.unsafe.run(received.update(_ :+ Request(headers, body))).getOrThrowFiberFailure()
            )
            exchange.sendResponseHeaders(status.get, -1)
            exchange.close()
        )
        server.start()
        server
      })(s => ZIO.succeed(s.stop(0)))
    yield FakeReceiver(server, status, received)
