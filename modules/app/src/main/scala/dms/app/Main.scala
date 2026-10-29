package dms.app

import dms.archive.core.LegalHoldService
import dms.audit.core.{AuditTrailLive, EvidenceReportStore, TsaTrust}
import dms.authz.{Authorizer, Groups, RoleAssignments}
import dms.db.Db
import dms.documents.core.{AcknowledgementService, DocumentClassService, DocumentService, ReviewService}
import dms.identity.{Authenticator, TenantDirectory, TokenVerifier}
import dms.integration.partners.PartnerDirectories
import dms.rendering.core.{VeraPdfValidator, WeasyPrintRenderer}
import dms.search.core.{OpenSearch, SearchIndex}
import dms.storage.ObjectStore
import dms.templates.core.TemplateService
import dms.workflow.core.{ApprovalWorkflow, Deputyships, FlowableEngine, WorkflowConfig}
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

  private type Env = DocumentClassApi.Env & TemplateApi.Env & DocumentApi.Env & WorkflowApi.Env &
    PartnerApi.Env & RenditionApi.Env & MeApi.Env & DeputyshipApi.Env & AcknowledgementApi.Env &
    SearchApi.Env & GroupApi.Env & ReviewApi.Env & LegalHoldApi.Env & ComplianceApi.Env &
    EvidenceReportApi.Env & CurrentDocumentApi.Env & WebhookApi.Env

  private val apiEndpoints: List[ZServerEndpoint[Env, Any]] =
    List(HealthApi.health.widen[Env], MeApi.me.widen[Env]) ++
      DocumentClassApi.endpoints.map(_.widen[Env]) ++
      TemplateApi.endpoints.map(_.widen[Env]) ++
      TemplateLifecycleApi.endpoints.map(_.widen[Env]) ++
      TemplatePreviewApi.endpoints.map(_.widen[Env]) ++
      TemplateImpactApi.endpoints.map(_.widen[Env]) ++
      // Before the document endpoints: `/documents/valid-at` must not be read as a document id.
      ValidAtApi.endpoints.map(_.widen[Env]) ++
      DocumentApi.endpoints.map(_.widen[Env]) ++
      CurrentDocumentApi.endpoints.map(_.widen[Env]) ++
      VersionCompareApi.endpoints.map(_.widen[Env]) ++
      RenditionApi.endpoints.map(_.widen[Env]) ++
      AcknowledgementApi.endpoints.map(_.widen[Env]) ++
      ReviewApi.endpoints.map(_.widen[Env]) ++
      WorkflowApi.endpoints.map(_.widen[Env]) ++
      DeputyshipApi.endpoints.map(_.widen[Env]) ++
      PartnerApi.endpoints.map(_.widen[Env]) ++
      SearchApi.endpoints.map(_.widen[Env]) ++
      GroupApi.endpoints.map(_.widen[Env]) ++
      LegalHoldApi.endpoints.map(_.widen[Env]) ++
      ComplianceApi.endpoints.map(_.widen[Env]) ++
      EvidenceReportApi.endpoints.map(_.widen[Env]) ++
      WebhookApi.endpoints.map(_.widen[Env])

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
          ObjectStore.live,
          RoleAssignments.postgres,
          Groups.postgres,
          Authorizer.layer,
          // Recording does not verify timestamps, so no TSA trust anchors are needed here; verification and
          // anchoring run with their own configuration (ADR 0017).
          ZLayer.succeed(TsaTrust(Nil)),
          AuditTrailLive.layer,
          DocumentClassService.layer,
          DocumentClassProfiles.layer,
          TemplateService.layer,
          DocumentTemplates.layer,
          DocumentService.configured(cfg.documents.settings),
          AcknowledgementService.layer,
          ReviewService.layer,
          LegalHoldService.layer,
          FlowableEngine.layer,
          WorkflowDocuments.layer,
          RoleDirectory.layer,
          WorkflowExtraReview.layer,
          ZLayer.succeed(
            WorkflowConfig(java.time.Duration.ofSeconds(cfg.workflow.reauthenticationSeconds.toLong))
          ),
          WorkflowRendition.layer,
          ApprovalWorkflow.layer,
          Deputyships.layer,
          PartnerDirectories.layer(cfg.partners),
          ZLayer.succeed(cfg.rendering),
          WeasyPrintRenderer.layer,
          VeraPdfValidator.layer,
          RenditionWiring.archive,
          DocumentRenditions.layer,
          RenditionWiring.jobs,
          ZLayer.succeed(cfg.search.openSearch),
          ZLayer.succeed(cfg.search.settings),
          ZLayer.succeed(cfg.webhooks.settings),
          OpenSearch.layer,
          SearchDocuments.layer,
          SearchIndex.layer,
          EvidenceReportApi.auditEvidence(cfg.audit),
          EvidenceReportStore.layer
        )
    yield ()
