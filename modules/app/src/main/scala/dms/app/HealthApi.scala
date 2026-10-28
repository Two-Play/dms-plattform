package dms.app

import sttp.tapir.{PublicEndpoint, Schema}
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.ZIO
import zio.json.JsonCodec

final case class Health(status: String, version: String) derives JsonCodec, Schema

object HealthApi:
  val endpointDefinition: PublicEndpoint[Unit, Unit, Health, Any] =
    endpoint.get
      .in("health")
      .out(jsonBody[Health])
      .description("Liveness check")
      .tag("Operations")

  val health: ZServerEndpoint[Any, Any] =
    endpointDefinition.zServerLogic(_ => ZIO.succeed(Health("ok", AppBuildInfo.version)))
