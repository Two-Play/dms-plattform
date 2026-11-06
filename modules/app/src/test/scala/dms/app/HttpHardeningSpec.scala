package dms.app

import dms.db.DbError
import dms.db.queue.QueueDepth
import sttp.model.StatusCode
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.*
import zio.http.{Handler, Method, Request, Response, Routes, Status, URL, handler}
import zio.json.*
import zio.test.*

/** Security headers, failures without internals and readiness (DMS-60, ADR 0056). */
object HttpHardeningSpec extends ZIOSpecDefault:

  private def header(response: Response, name: String) = response.headers.get(name)

  private object Locale:
    def withDefault[A](locale: java.util.Locale)(work: => A): A =
      val before = java.util.Locale.getDefault
      java.util.Locale.setDefault(locale)
      try work
      finally java.util.Locale.setDefault(before)

  private val routes = Routes(
    Method.GET / "plain"   -> handler(Response.text("ok")),
    Method.GET / "own"     -> handler(Response.text("ok").addHeader("Cache-Control", "private, no-cache")),
    Method.GET / "docs"    -> handler(Response.text("ui")),
    Method.GET / "failure" -> Handler.fail(Response.status(Status.Forbidden))
  ).transform(HttpHardening.securityHeaders)

  private def get(path: String) =
    ZIO.scoped(routes.runZIO(Request.get(URL.decode(path).toOption.get)))

  private def probe(name: String, required: Boolean, up: Boolean) =
    ReadinessProbe(name, required, ZIO.succeed(up))

  private def ready(probes: ReadinessProbe*) =
    Readiness.make(probes.toList).flatMap { readiness =>
      ZIO
        .scoped(
          ZioHttpInterpreter()
            .toHttp(ReadinessApi.ready)
            .runZIO(Request.get(URL.decode("/ready").toOption.get))
            .provideSomeEnvironment[Scope](_ ++ ZEnvironment(readiness))
        )
        .flatMap(r => r.body.asString.map(b => (r.status, b.fromJson[ReadinessView])).orDie)
    }

  def spec = suite("HTTP hardening")(
    test("every response carries the security headers") {
      for
        ok     <- get("/plain")
        denied <- get("/failure")
      yield assertTrue(
        HttpHardening.SecurityHeaders.forall((name, value) => header(ok, name).contains(value)),
        header(denied, "X-Content-Type-Options").contains("nosniff"),
        denied.status == Status.Forbidden
      )
    },
    test("headers a response sets itself win") {
      get("/own").map(r => assertTrue(header(r, "Cache-Control").contains("private, no-cache")))
    },
    test("the API documentation may load its scripts") {
      get("/docs").map(r =>
        assertTrue(
          header(r, "Content-Security-Policy").isEmpty,
          header(r, "X-Content-Type-Options").contains("nosniff")
        )
      )
    },
    test("database failures tell the client a reference, not the driver's message") {
      val (status, error) =
        HttpHardening.databaseError(DbError.Other("23503", "insert into dms.secret_table failed for ACME"))
      assertTrue(
        status == StatusCode.InternalServerError,
        error.code == "database_error",
        error.message.contains("reference"),
        !error.message.contains("secret_table"),
        !error.message.contains("ACME")
      )
    },
    test("an overloaded database answers 503, which clients may retry") {
      val (status, error) = HttpHardening.databaseError(DbError.Unavailable("pool dms exhausted"))
      assertTrue(
        status == StatusCode.ServiceUnavailable,
        error.code == "unavailable",
        !error.message.contains("pool")
      )
    },
    test("ready while required and optional dependencies answer") {
      ready(probe("database", required = true, up = true), probe("search", required = false, up = true)).map(
        r => assertTrue(r._1 == Status.Ok, r._2.map(_.status) == Right("ready"))
      )
    },
    test("an optional dependency down is degraded and keeps the traffic") {
      ready(probe("database", required = true, up = true), probe("search", required = false, up = false)).map(
        r =>
          assertTrue(
            r._1 == Status.Ok,
            r._2.map(_.status) == Right("degraded"),
            r._2.map(_.components.filter(_.status == "down").map(_.name)) == Right(List("search"))
          )
      )
    },
    test("a required dependency down, or one that does not answer in time, takes the instance out") {
      for
        down <- ready(probe("database", required = true, up = false))
        slow <- Readiness
          .make(List(ReadinessProbe("database", required = true, ZIO.never)), 10.millis)
          .flatMap(_.state)
      yield assertTrue(
        down._1 == Status.ServiceUnavailable,
        down._2.map(_.status) == Right("unavailable"),
        slow.status == "unavailable"
      )
    } @@ TestAspect.withLiveClock,
    test(
      "deliveries, renditions and reminder checks have workers of their own, the rest shares the regular one"
    ) {
      val lanes = Background.jobLanes(
        Map(
          "notifications.deliver"                    -> 1,
          "webhook.deliver"                          -> 2,
          "document.render-rendition"                -> 3,
          "document.publish"                         -> 4,
          "documents.acknowledgement-reminder-check" -> 5
        )
      )
      assertTrue(
        lanes.bulk.keySet == Set("notifications.deliver", "webhook.deliver"),
        lanes.renditions.keySet == Set("document.render-rendition"),
        lanes.reminders.keySet == Set("documents.acknowledgement-reminder-check"),
        lanes.regular.keySet == Set("document.publish")
      )
    },
    test("the metrics are in the text format, with a point whatever the language of the instance") {
      val text = Locale.withDefault(java.util.Locale.GERMANY)(
        MetricsApi.render(
          List(QueueDepth("due", 3, 12.5), QueueDepth("delayed", 1, 0), QueueDepth("running", 2, 7))
        )
      )
      assertTrue(
        text.linesIterator.toList.filterNot(_.startsWith("#")) == List(
          """dms_rendering_queue_depth{state="due"} 3""",
          """dms_rendering_queue_depth{state="delayed"} 1""",
          """dms_rendering_queue_depth{state="running"} 2""",
          "dms_rendering_queue_oldest_seconds 12.500"
        ),
        text.linesIterator.count(_.startsWith("# TYPE")) == 2,
        text.endsWith("\n")
      )
    },
    test("a draining instance is taken out although everything answers") {
      for
        readiness <- Readiness.make(List(probe("database", required = true, up = true)))
        before    <- readiness.state
        _         <- readiness.drain
        after     <- readiness.state
      yield assertTrue(before.status == "ready", after.status == "draining")
    }
  )
