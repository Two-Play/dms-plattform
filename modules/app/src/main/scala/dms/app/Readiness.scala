package dms.app

import com.augustnagro.magnum.*
import dms.db.Db
import dms.search.core.OpenSearch
import dms.storage.{ObjectStore, StorageError}
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/** State of a dependency: `up` or `down`. Required dependencies make the instance unavailable when down. */
final case class ComponentView(name: String, status: String, required: Boolean) derives JsonCodec, Schema

/** `ready`, `degraded` (an optional dependency is down), `unavailable` or `draining`. */
final case class ReadinessView(status: String, components: List[ComponentView]) derives JsonCodec, Schema

/** A dependency of the instance. A failed or slow check counts as down; `required` ones decide readiness. */
final case class ReadinessProbe(name: String, required: Boolean, check: UIO[Boolean])

/** Whether this instance should get traffic (DMS-60, ADR 0056). Unlike `/health`, which only says that the
  * process is alive, this asks the dependencies; it names them and nothing about them, as it needs no
  * sign-in. While shutting down it answers `draining`, so a load balancer takes the instance out before it
  * stops.
  */
final class Readiness(probes: List[ReadinessProbe], timeout: Duration, draining: Ref[Boolean]):

  /** Stops new traffic from arriving. */
  def drain: UIO[Unit] = draining.set(true)

  def state: UIO[ReadinessView] =
    for
      stopping   <- draining.get
      components <- ZIO.foreachPar(probes)(p =>
        p.check
          .timeout(timeout)
          .map(_.contains(true))
          .map(up => ComponentView(p.name, if up then "up" else "down", p.required))
      )
      down = components.filter(_.status == "down")
    yield ReadinessView(
      if stopping then "draining"
      else if down.exists(_.required) then "unavailable"
      else if down.nonEmpty then "degraded"
      else "ready",
      components
    )

object Readiness:
  def make(probes: List[ReadinessProbe], timeout: Duration = 2.seconds): UIO[Readiness] =
    Ref.make(false).map(Readiness(probes, timeout, _))

  private def httpProbe(name: String, url: String): ReadinessProbe =
    val client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build()
    val base   = url.stripSuffix("/")
    ReadinessProbe(
      name,
      required = false,
      ZIO
        .attemptBlocking(
          client
            .send(
              HttpRequest.newBuilder(URI(s"$base/health")).timeout(java.time.Duration.ofSeconds(2)).build(),
              HttpResponse.BodyHandlers.discarding()
            )
            .statusCode
        )
        .fold(_ => false, _ == 200)
    )

  /** Database and object store are required: without them nothing works. Search, rendering, conversion and
    * the virus scanner are optional: reading and approving go on, their work queues up or fails closed.
    */
  def live(
      config: AppConfig,
      scanner: dms.documents.AttachmentScanner
  ): URLayer[Db & ObjectStore & OpenSearch, Readiness] =
    ZLayer.fromZIO(
      for
        db     <- ZIO.service[Db]
        store  <- ZIO.service[ObjectStore]
        search <- ZIO.service[OpenSearch]
        clamd = scanner match
          case clamav: ClamAvScanner =>
            List(ReadinessProbe("virus-scanner", required = false, clamav.ping.fold(_ => false, _ => true)))
          case _ => Nil
        readiness <- make(
          List(
            ReadinessProbe(
              "database",
              required = true,
              db.transactSystem(Db.sql(sql"select 1".query[Int].run().nonEmpty)).fold(_ => false, identity)
            ),
            ReadinessProbe(
              "object-store",
              required = true,
              // An unknown key is the answer of a working store.
              store.head("readiness-probe", None).fold(_.isInstanceOf[StorageError.NotFound], _ => true)
            ),
            ReadinessProbe(
              "search",
              required = false,
              search.json("GET", "/_cluster/health").fold(_ => false, _ => true)
            ),
            httpProbe("rendering", config.rendering.url),
            httpProbe("conversion", config.conversion.url)
          ) ++ clamd
        )
      yield readiness
    )

object ReadinessApi:
  val ready: ZServerEndpoint[Readiness, Any] =
    endpoint.get
      .in("ready")
      .out(statusCode.and(jsonBody[ReadinessView]))
      .description(
        "Readiness check for load balancers: 200 while the instance can serve, also with an optional dependency " +
          "down (`degraded`); 503 without database or object store and while shutting down (`draining`)."
      )
      .tag("Operations")
      .zServerLogic(_ =>
        ZIO
          .serviceWithZIO[Readiness](_.state)
          .map(view =>
            (
              if view.status == "ready" || view.status == "degraded" then StatusCode.Ok
              else StatusCode.ServiceUnavailable,
              view
            )
          )
      )
