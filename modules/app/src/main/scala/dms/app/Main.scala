package dms.app

import dms.archive.core.{DestructionService, LegalHoldService, ObjectCleanup}
import dms.audit.AuditTrail
import dms.audit.core.{AuditTrailLive, EvidenceReportStore, TsaTrust}
import dms.authz.{Authorizer, Groups, RoleAssignments}
import dms.db.Db
import dms.documents.core.{
  AcknowledgementService,
  AttachmentService,
  AttachmentUploads,
  WordTemplateService,
  DocumentClassService,
  DocumentService,
  NumberRangeService,
  ReviewService
}
import dms.identity.{Authenticator, TenantDirectory, TenantOrganizations, TokenVerifier, UserDirectories}
import dms.integration.partners.PartnerDirectories
import dms.integration.webhooks.{HttpWebhookSender, WebhookSender}
import dms.rendering.core.{LibreOfficeConverter, VeraPdfValidator, WeasyPrintRenderer}
import dms.search.core.{OpenSearch, SearchIndex}
import dms.storage.ObjectStore
import dms.templates.core.TemplateService
import dms.workflow.core.{ApprovalWorkflow, Deputyships, FlowableEngine, WorkflowConfig}
import sttp.capabilities.zio.ZioStreams
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.tapir.ztapir.*
import zio.*
import zio.config.typesafe.TypesafeConfigProvider
import zio.http.{Handler, Request, Response, Server, Status}
import zio.json.*
import zio.logging.backend.SLF4J

/** API entry point. Database migrations run separately via [[Migrate]]. */
object Main extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >+>
      Runtime.setConfigProvider(TypesafeConfigProvider.fromResourcePath())

  private[app] type Env = DocumentClassApi.Env & TemplateApi.Env & DocumentApi.Env & WorkflowApi.Env &
    PartnerApi.Env & RenditionApi.Env & MeApi.Env & DeputyshipApi.Env & AcknowledgementApi.Env &
    SearchApi.Env & GroupApi.Env & ReviewApi.Env & LegalHoldApi.Env & ComplianceApi.Env &
    EvidenceReportApi.Env & CurrentDocumentApi.Env & WebhookApi.Env & UserApi.Env & VersionViewApi.Env &
    NumberRangeApi.Env & AttachmentApi.Env & LibraryApi.Env & FileDocumentApi.Env & WordTemplateApi.Env &
    AcknowledgementOverviewApi.Env & AcknowledgementSettingsApi.Env & RetentionApi.Env

  private[app] val apiEndpoints: List[ZServerEndpoint[Env, ZioStreams]] =
    List(HealthApi.health.widen[Env], MeApi.me.widen[Env]) ++
      UserApi.endpoints.map(_.widen[Env]) ++
      DocumentClassApi.endpoints.map(_.widen[Env]) ++
      AcknowledgementSettingsApi.endpoints.map(_.widen[Env]) ++
      NumberRangeApi.endpoints.map(_.widen[Env]) ++
      TemplateApi.endpoints.map(_.widen[Env]) ++
      TemplateLifecycleApi.endpoints.map(_.widen[Env]) ++
      TemplatePreviewApi.endpoints.map(_.widen[Env]) ++
      TemplateImpactApi.endpoints.map(_.widen[Env]) ++
      WordTemplateApi.endpoints.map(_.widen[Env]) ++
      // Before the document endpoints: `/documents/valid-at` must not be read as a document id.
      ValidAtApi.endpoints.map(_.widen[Env]) ++
      DocumentApi.endpoints.map(_.widen[Env]) ++
      AttachmentApi.endpoints.map(_.widen[Env]) ++
      FileDocumentApi.endpoints.map(_.widen[Env]) ++
      UploadFlowApi.endpoints.map(_.widen[Env]) ++
      CurrentDocumentApi.endpoints.map(_.widen[Env]) ++
      LibraryApi.endpoints.map(_.widen[Env]) ++
      VersionCompareApi.endpoints.map(_.widen[Env]) ++
      RenditionApi.endpoints.map(_.widen[Env]) ++
      VersionViewApi.endpoints.map(_.widen[Env]) ++
      AcknowledgementApi.endpoints.map(_.widen[Env]) ++
      AcknowledgementOverviewApi.endpoints.map(_.widen[Env]) ++
      ReviewApi.endpoints.map(_.widen[Env]) ++
      WorkflowApi.endpoints.map(_.widen[Env]) ++
      DeputyshipApi.endpoints.map(_.widen[Env]) ++
      PartnerApi.endpoints.map(_.widen[Env]) ++
      SearchApi.endpoints.map(_.widen[Env]) ++
      GroupApi.endpoints.map(_.widen[Env]) ++
      LegalHoldApi.endpoints.map(_.widen[Env]) ++
      RetentionApi.endpoints.map(_.widen[Env]) ++
      ComplianceApi.endpoints.map(_.widen[Env]) ++
      EvidenceReportApi.endpoints.map(_.widen[Env]) ++
      WebhookApi.endpoints.map(_.widen[Env])

  private val docs =
    SwaggerInterpreter()
      .fromEndpoints[Task](apiEndpoints.map(_.endpoint), "DMS Platform", AppBuildInfo.version)

  private val routes =
    (ZioHttpInterpreter().toHttp(apiEndpoints) ++ ZioHttpInterpreter().toHttp(docs))
      .transform(limitStreamedBodies)

  /** Only attachment uploads and uploads of file documents' originals may send a body larger than
    * [[AggregatedBodyBytes]]: the server streams such bodies instead of aggregating them, and every other
    * endpoint would collect them in memory. Refused with 413.
    */
  def limitStreamedBodies[R](
      handler: Handler[R, Response, Request, Response]
  ): Handler[R, Response, Request, Response] =
    Handler.fromFunctionHandler[Request] { request =>
      if request.body.isComplete || AttachmentApi.isUpload(request.method, request.path) ||
        FileDocumentApi.isUpload(request.method, request.path) ||
        WordTemplateApi.isUpload(request.method, request.path) ||
        UploadFlowApi.isUpload(request.method, request.path)
      then handler
      else
        Handler.succeed(
          Response
            .json(
              ApiError("body_too_large", s"request bodies are limited to $AggregatedBodyBytes bytes").toJson
            )
            .status(Status.RequestEntityTooLarge)
        )
    }

  /** Request bodies up to this size are aggregated in memory; larger ones are streamed. */
  val AggregatedBodyBytes: Int = 1024 * 1024

  /** Warns at start about uploads without a virus scan and an unreachable `clamd` (DMS-117). Uploads fail
    * closed while `clamd` does not answer, so the start is not refused.
    */
  private def checkScanner(profile: Profile, scanner: dms.documents.AttachmentScanner): UIO[Unit] =
    scanner match
      case clamav: ClamAvScanner =>
        clamav.ping.foldZIO(
          e => ZIO.logWarning(s"Virus scanner: $e; uploads are refused (503) until clamd answers"),
          _ => ZIO.logInfo("Virus scanner: clamd answers; uploads are scanned with ClamAV")
        )
      case _ if profile == Profile.Production =>
        ZIO.logWarning(
          "Virus scanner: none in the production profile (dms.attachments.allowUnscannedInProduction); uploads are " +
            "stored as NotScanned (ADR 0041)"
        )
      case _ =>
        ZIO.logWarning(
          "Virus scanner: none; uploads are stored as NotScanned (development profile, ADR 0041)"
        )

  override def run: ZIO[Any, Throwable, Unit] =
    for
      cfg     <- ZIO.config(AppConfig.config)
      profile <- ZIO.fromEither(Profile.parse(cfg.profile)).mapError(IllegalArgumentException(_))
      scanner <- ZIO
        .fromEither(cfg.attachments.attachmentScanner(profile))
        .mapError(IllegalArgumentException(_))
      _        <- checkScanner(profile, scanner)
      webhooks <- ZIO.fromEither(cfg.webhooks.settings).mapError(IllegalArgumentException(_))
      _        <- ZIO.logInfo(s"DMS platform ${AppBuildInfo.version} starting on port ${cfg.http.port}")
      _        <- (Server.serve(routes) <&> Background.run(cfg))
        .provide(
          // Small bodies are aggregated as before; larger ones (attachment uploads) are streamed, so they are never
          // held in memory as a whole (DMS-114).
          Server.defaultWith(_.port(cfg.http.port).hybridRequestStreaming(Main.AggregatedBodyBytes)),
          ZLayer.succeed(cfg.auth),
          TokenVerifier.layer,
          Db.dataSource(cfg.database),
          Db.layer,
          TenantDirectory.layer,
          Authenticator.layer,
          TenantOrganizations.layer,
          UserDirectories.layer(cfg.users, cfg.auth.issuer),
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
          NumberRangeService.layer,
          DocumentClassProfiles.layer,
          TemplateService.layer,
          DocumentTemplates.layer,
          DocumentService.configured(cfg.documents.settings),
          AcknowledgementService.layer,
          ZLayer.succeed(cfg.documents.reminders),
          ReviewService.layer,
          LegalHoldService.layer,
          DestructionService.layer,
          ZLayer.fromFunction((db: Db, store: ObjectStore, audit: AuditTrail) =>
            ObjectCleanup(db, store, audit, cfg.retention.orphans)
          ),
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
          ZLayer.succeed(cfg.conversion),
          LibreOfficeConverter.layer,
          VeraPdfValidator.layer,
          RenditionWiring.archive,
          DocumentRenditions.withNames,
          RenditionWiring.jobs,
          ZLayer.succeed(cfg.search.openSearch),
          ZLayer.succeed(cfg.search.settings),
          ZLayer.succeed(webhooks),
          ZLayer.succeed[WebhookSender](HttpWebhookSender(webhooks)),
          OpenSearch.layer,
          SearchDocuments.withOriginals,
          SearchIndex.layer,
          ZLayer.succeed(cfg.attachments.settings),
          ZLayer.succeed(scanner),
          AttachmentService.layer,
          AttachmentUploads.layer,
          FileVersions.configured(cfg.conversion.previewCacheMegabytes.toLong * 1024 * 1024),
          WordTemplateService.layer,
          EvidenceReportApi.auditEvidence(cfg.audit),
          EvidenceReportStore.layer
        )
    yield ()
