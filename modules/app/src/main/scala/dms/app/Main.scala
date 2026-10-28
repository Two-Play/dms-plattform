package dms.app

import dms.audit.core.{AuditTrailLive, TsaTrust}
import dms.authz.{Authorizer, RoleAssignments}
import dms.db.Db
import dms.documents.core.DocumentClassService
import dms.identity.{Authenticator, TenantDirectory, TokenVerifier}
import dms.storage.ObjectStore
import dms.templates.core.TemplateService
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

  private type Env = DocumentClassApi.Env & TemplateApi.Env

  private val apiEndpoints: List[ZServerEndpoint[Env, Any]] =
    List(HealthApi.health.widen[Env], MeApi.me.widen[Env]) ++
      DocumentClassApi.endpoints.map(_.widen[Env]) ++
      TemplateApi.endpoints.map(_.widen[Env]) ++
      TemplateLifecycleApi.endpoints.map(_.widen[Env]) ++
      TemplatePreviewApi.endpoints.map(_.widen[Env])

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
          ObjectStore.client,
          RoleAssignments.postgres,
          Authorizer.layer,
          // Recording does not verify timestamps, so no TSA trust anchors are needed here; verification and
          // anchoring run with their own configuration (ADR 0017).
          ZLayer.succeed(TsaTrust(Nil)),
          AuditTrailLive.layer,
          DocumentClassService.layer,
          DocumentClassProfiles.layer,
          TemplateService.layer
        )
    yield ()
