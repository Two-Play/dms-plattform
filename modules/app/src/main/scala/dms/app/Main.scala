package dms.app

import dms.db.Db
import dms.identity.{Authenticator, TenantDirectory, TokenVerifier}
import dms.storage.ObjectStore
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.tapir.ztapir.*
import zio.*
import zio.config.typesafe.TypesafeConfigProvider
import zio.http.Server
import zio.logging.backend.SLF4J

/** API entry point. Database migrations run separately via [[Migrate]]. */
object Main extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >+>
      Runtime.setConfigProvider(TypesafeConfigProvider.fromResourcePath())

  private val apiEndpoints: List[ZServerEndpoint[Authenticator, Any]] =
    List(HealthApi.health.widen[Authenticator], MeApi.me)

  private val docs =
    SwaggerInterpreter()
      .fromEndpoints[Task](apiEndpoints.map(_.endpoint), "DMS Platform", AppBuildInfo.version)

  private val routes =
    ZioHttpInterpreter().toHttp(apiEndpoints) ++ ZioHttpInterpreter().toHttp(docs)

  override def run: ZIO[Any, Throwable, Unit] =
    for
      cfg <- ZIO.config(AppConfig.config)
      _   <- ZIO.logInfo(s"DMS platform ${AppBuildInfo.version} starting on port ${cfg.http.port}")
      _   <- (Server.serve(routes) <&> Background.run(cfg))
        .provide(
          Server.defaultWithPort(cfg.http.port),
          ZLayer.succeed(cfg.auth),
          TokenVerifier.layer,
          Db.dataSource(cfg.database),
          Db.layer,
          TenantDirectory.layer,
          Authenticator.layer,
          ZLayer.succeed(cfg.storage),
          ObjectStore.client
        )
    yield ()
