package dms.app

import dms.archive.core.LegalHoldSync
import dms.audit.core.*
import dms.db.Db
import dms.authz.{Groups, RoleAssignments}
import dms.documents.core.{
  AcknowledgementService,
  DocumentService,
  FileCleanupJobs,
  PublicationJobs,
  PublicationScheduleConfig,
  ReviewJobs,
  ReviewScheduleConfig,
  ReviewService
}
import dms.integration.webhooks.{WebhookJobs, WebhookSecretMigration, WebhookSender, WebhookSettings}
import dms.integration.{JobRunner, OutboxRelay, WorkerConfig}
import dms.rendering.RenditionRequest
import dms.rendering.core.RenditionJobs
import dms.search.core.{SearchIndex, SearchJobs}
import dms.storage.S3ObjectStore
import dms.workflow.core.{DeputyshipJobs, DeputyshipScheduleConfig, Deputyships}
import software.amazon.awssdk.services.s3.S3Client
import zio.*

import java.lang.management.ManagementFactory
import java.net.http.HttpClient

/** Background processing of this instance: the job runner (audit, publication, review, rendition, search,
  * legal hold, file clean-up, deputyship and acknowledgement reminder jobs), the outbox relay and the schedulers
  * of periodic audit jobs, scheduled publications, the periodic review check, the search synchronization, the
  * deputyship check and the acknowledgement reminder check.
  *
  * The outbox relay hands every event to its subscribers, which turn the events they need into jobs; an event
  * no subscriber needs is marked delivered. Subscribers: the search index (DMS-51, ADR 0029) and the webhooks
  * (DMS-58, ADR 0035), which record a delivery and a job per subscription, so a receiver that is down never
  * holds up the relay.
  */
object Background:

  def run(
      config: AppConfig
  ): ZIO[
    Db & S3Client & DocumentService & ReviewService & RenditionJobs & SearchIndex & Deputyships &
      WebhookSettings & WebhookSender & AcknowledgementService & RoleAssignments & Groups,
    Throwable,
    Nothing
  ] =
    for
      db        <- ZIO.service[Db]
      s3        <- ZIO.service[S3Client]
      documents <- ZIO.service[DocumentService]
      reviews   <- ZIO.service[ReviewService]
      rendering <- ZIO.service[RenditionJobs]
      search    <- ZIO.service[SearchIndex]
      deputies  <- ZIO.service[Deputyships]
      hooks     <- ZIO.service[WebhookSettings]
      sender    <- ZIO.service[WebhookSender]
      acks      <- ZIO.service[AcknowledgementService]
      roles     <- ZIO.service[RoleAssignments]
      groups    <- ZIO.service[Groups]
      _         <- secureWebhookSecrets(db, hooks)
      tsa = TsaConfig(config.audit.tsaUrl, trustedCertificates = config.audit.tsaTrustedCertificates)
      trust <- TsaTrust.fromConfig(tsa).mapError(e => RuntimeException(e.message))
      trail       = AuditTrailLive(db, trust)
      checkpoints = AuditCheckpoints(db, trail, S3ObjectStore(s3, config.storage.bucket), CheckpointConfig())
      anchoring   = Option.when(trust.anchors.nonEmpty)(
        AuditAnchoring(db, HttpTimestampAuthority(tsa, HttpClient.newHttpClient()), trust)
      )
      _ <- ZIO
        .logWarning("Audit anchoring is disabled: no trusted TSA certificates configured (dms.audit)")
        .when(anchoring.isEmpty)
      jobs = AuditJobs(
        db,
        anchoring,
        checkpoints,
        AuditScheduleConfig(config.audit.checkpointIntervalSeconds.seconds, config.audit.anchorHourUtc)
      )
      publications = PublicationJobs(
        db,
        documents,
        PublicationScheduleConfig(config.documents.publicationCheckSeconds.seconds)
      )
      reviewJobs = ReviewJobs(db, reviews, ReviewScheduleConfig(config.documents.reviewCheckSeconds.seconds))
      searchJobs = SearchJobs(db, search, config.search.settings)
      legalHolds = LegalHoldSync(db, S3ObjectStore(s3, config.storage.bucket))
      // Objects of files released by discarded drafts (DMS-129, ADR 0050).
      fileCleanup = FileCleanupJobs(db, S3ObjectStore(s3, config.storage.bucket))
      webhooks    = WebhookJobs(db, DocumentWebhookEvents(documents), sender, hooks)
      deputyJobs  = DeputyshipJobs(
        db,
        deputies,
        DeputyshipScheduleConfig(config.workflow.deputyshipCheckSeconds.seconds)
      )
      reminders = AcknowledgementReminderJobs(db, documents, acks, roles, groups, config.documents.reminders)
      runner    = JobRunner(
        db,
        jobs.handlers ++ publications.handlers ++ reviewJobs.handlers ++ searchJobs.handlers ++
          legalHolds.handlers ++ fileCleanup.handlers ++ webhooks.handlers ++ deputyJobs.handlers ++
          reminders.handlers +
          (RenditionRequest.JobKind -> rendering.handle),
        WorkerConfig(s"${workerName(config)}-jobs")
      )
      relay = OutboxRelay(
        db,
        event => searchJobs.deliver(event) *> webhooks.deliver(event),
        WorkerConfig(s"${workerName(config)}-outbox")
      )
      nothing <- runner.run
        .raceFirst(relay.run)
        .raceFirst(jobs.schedule)
        .raceFirst(publications.schedule)
        .raceFirst(reviewJobs.schedule)
        .raceFirst(searchJobs.schedule)
        .raceFirst(deputyJobs.schedule)
        .raceFirst(reminders.schedule)
    yield nothing

  /** Encrypts plain-text webhook secrets and re-encrypts those of older keys with the active key (DMS-107). A
    * failure is logged and does not stop the instance: deliveries still read every configured key.
    */
  private def secureWebhookSecrets(db: Db, settings: WebhookSettings): UIO[Unit] =
    ZIO
      .logWarning(
        "Webhook secrets are encrypted with the publicly known development key: configure " +
          "DMS_WEBHOOKS_SECRET_KEYS or DMS_WEBHOOKS_SECRET_KEYS_FILE in production (ADR 0035)"
      )
      .when(settings.secretKeys.isDevelopment) *>
      WebhookSecretMigration
        .run(db, settings.secretKeys)
        .flatMap(outcome =>
          ZIO
            .logInfo(
              s"Webhook secrets: ${outcome.resealed} encrypted with key ${settings.secretKeys.activeKeyId}"
            )
            .when(outcome.resealed > 0) *>
            ZIO
              .logError(
                s"Webhook secrets: ${outcome.unreadable} cannot be decrypted with the configured keys; " +
                  "configure the missing key or rotate those secrets"
              )
              .when(outcome.unreadable > 0)
        )
        // Only the kind of error: a database message could quote row data.
        .catchAll(e => ZIO.logError(s"Webhook secrets could not be re-encrypted: ${e.productPrefix}"))
        .unit

  private def workerName(config: AppConfig): String =
    config.worker.name.getOrElse(ManagementFactory.getRuntimeMXBean.getName) // "pid@host"
