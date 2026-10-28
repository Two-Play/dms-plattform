package dms.app

import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import zio.*
import zio.config.typesafe.TypesafeConfigProvider
import zio.http.Server
import zio.logging.backend.SLF4J

/** API entry point. Database migrations run separately via [[Migrate]]. */
object Main extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >+>
      Runtime.setConfigProvider(TypesafeConfigProvider.fromResourcePath())

  private val serverEndpoints = List(HealthApi.health)

  private val docs =
    SwaggerInterpreter().fromServerEndpoints[Task](serverEndpoints, "DMS Platform", AppBuildInfo.version)

  private val routes = ZioHttpInterpreter().toHttp(serverEndpoints ++ docs)

  override def run: ZIO[Any, Throwable, Unit] =
    for
      cfg <- ZIO.config(AppConfig.config)
      _   <- ZIO.logInfo(s"DMS platform ${AppBuildInfo.version} starting on port ${cfg.http.port}")
      _   <- Server.serve(routes).provide(Server.defaultWithPort(cfg.http.port))
    yield ()
