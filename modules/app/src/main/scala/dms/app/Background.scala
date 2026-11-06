package dms.app

import dms.archive.core.{LegalHoldSync, ObjectCleanup, ObjectRetentionSync, RetentionMaintenance}
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
import dms.notifications.core.NotificationJobs
import dms.rendering.RenditionRequest
import dms.rendering.core.RenditionJobs
import dms.search.core.{SearchIndex, SearchJobs}
import dms.storage.S3ObjectStore
import dms.workflow.core.{
  ApprovalWorkflow,
  DeadlineJobs,
  DeadlineScheduleConfig,
  DeputyshipJobs,
  DeputyshipScheduleConfig,
  Deputyships
}
import software.amazon.awssdk.services.s3.S3Client
import zio.*

import java.lang.management.ManagementFactory
import java.net.http.HttpClient

/** Background processing of this instance: the job runner (audit, publication, review, rendition, search,
  * legal hold, object retention, file clean-up, deputyship, acknowledgement reminder, notification and task
  * deadline jobs), the outbox relay and the schedulers of periodic audit jobs, scheduled publications, the
  * periodic review check, the search synchronization, the deputyship check, the acknowledgement reminder
  * check, the notification clean-up, the deadline check of workflow tasks (DMS-87) and periodic review tasks
  * (DMS-141) and the reminder check of failed jobs (DMS-146).
  *
  * The outbox relay hands every event to its subscribers, which turn the events they need into jobs; an event
  * no subscriber needs is marked delivered. Subscribers: the search index (DMS-51, ADR 0029) and the webhooks
  * (DMS-58, ADR 0035), which record a delivery and a job per subscription, so a receiver that is down never
  * holds up the relay, the notifications (DMS-130, ADR 0052), which record a delivery and a job per recipient
  * and channel, the acknowledgement reminders, which check a tenant at once when a version that requires
  * acknowledgement became valid, and the audit entry of a publication that failed for good (DMS-146), first
  * so that a later subscriber's failure cannot hold it up.
  */
object Background:

  def run(
      config: AppConfig
  ): ZIO[
    Db & S3Client & DocumentService & ReviewService & RenditionJobs & SearchIndex & Deputyships &
      WebhookSettings & WebhookSender & AcknowledgementService & RoleAssignments & Groups & NotificationJobs &
      ApprovalWorkflow,
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
      notices   <- ZIO.service[NotificationJobs]
      workflow  <- ZIO.service[ApprovalWorkflow]
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
        AuditEvidenceLive(db, trail, Some(checkpoints)),
        AuditScheduleConfig(
          config.audit.checkpointIntervalSeconds.seconds,
          config.audit.anchorHourUtc,
          (config.audit.anchorHourUtc + 1) % 24
        )
      )
      publications = PublicationJobs(
        db,
        documents,
        PublicationScheduleConfig(config.documents.publicationCheckSeconds.seconds)
      )
      reviewJobs = ReviewJobs(db, reviews, ReviewScheduleConfig(config.documents.reviewCheckSeconds.seconds))
      searchJobs = SearchJobs(db, search, config.search.settings)
      legalHolds = LegalHoldSync(db, S3ObjectStore(s3, config.storage.bucket))
      policy <- ZIO.fromEither(config.retention.objectLock.policy).mapError(IllegalArgumentException(_))
      // Object Lock retention of the objects of approved versions (DMS-93, ADR 0055).
      retention = ObjectRetentionSync(db, S3ObjectStore(s3, config.storage.bucket), policy, trail)
      // Objects of files released by discarded drafts (DMS-129, ADR 0050).
      fileCleanup = FileCleanupJobs(db, S3ObjectStore(s3, config.storage.bucket))
      // Objects released by destructions and orphaned objects; compaction and discarded drafts (DMS-82, ADR 0054).
      objectCleanup = ObjectCleanup(
        db,
        S3ObjectStore(s3, config.storage.bucket),
        trail,
        config.retention.orphans
      )
      maintenance = RetentionMaintenance(db, trail, objectCleanup, config.retention.maintenance)
      webhooks    = WebhookJobs(db, DocumentWebhookEvents(documents), sender, hooks)
      deputyJobs  = DeputyshipJobs(
        db,
        deputies,
        DeputyshipScheduleConfig(config.workflow.deputyshipCheckSeconds.seconds)
      )
      deadlineJobs = DeadlineJobs(
        db,
        workflow,
        DeadlineScheduleConfig(config.workflow.deadlineCheckSeconds.max(1).seconds),
        List(ReviewDeadlines(reviews, documents, workflow))
      )
      reminders = AcknowledgementReminderJobs(db, documents, acks, roles, groups, config.documents.reminders)
      failures  = FailureFollowUps(db, trail, config.notifications.failureReminders)
      lanes     = jobLanes(
        jobs.handlers ++ publications.handlers ++ reviewJobs.handlers ++ searchJobs.handlers ++
          legalHolds.handlers ++ retention.handlers ++ fileCleanup.handlers ++ webhooks.handlers ++
          deputyJobs.handlers ++ reminders.handlers ++ objectCleanup.handlers ++ maintenance.handlers ++
          notices.handlers ++ deadlineJobs.handlers ++ failures.handlers +
          (RenditionRequest.JobKind -> rendering.handle)
      )
      runner     = JobRunner(db, lanes.regular, WorkerConfig(s"${workerName(config)}-jobs"))
      bulkRunner = JobRunner(db, lanes.bulk, WorkerConfig(s"${workerName(config)}-bulk"))
      // One job per claim: what is claimed waits for this runner, while another one may have nothing to do.
      renditions = List.tabulate(config.rendering.parallelJobs.max(1))(i =>
        JobRunner(
          db,
          lanes.renditions,
          WorkerConfig(s"${workerName(config)}-renditions-$i", batchSize = 1)
        ).run
      )
      reminderRunner = JobRunner(
        db,
        lanes.reminders,
        WorkerConfig(s"${workerName(config)}-reminders", batchSize = 1)
      )
      relay = OutboxRelay(
        db,
        event =>
          failures.deliver(event) *> searchJobs.deliver(event) *> webhooks.deliver(event) *>
            notices.deliver(event) *> reminders.deliver(event),
        WorkerConfig(s"${workerName(config)}-outbox")
      )
      nothing <- runner.run
        .raceFirst(bulkRunner.run)
        .raceFirst(renditions.reduce[UIO[Nothing]]((a, b) => a.raceFirst(b)))
        .raceFirst(reminderRunner.run)
        .raceFirst(relay.run)
        .raceFirst(jobs.schedule)
        .raceFirst(publications.schedule)
        .raceFirst(reviewJobs.schedule)
        .raceFirst(searchJobs.schedule)
        .raceFirst(deputyJobs.schedule)
        .raceFirst(reminders.schedule)
        .raceFirst(maintenance.schedule)
        .raceFirst(notices.schedule)
        .raceFirst(deadlineJobs.schedule)
        .raceFirst(failures.schedule)
    yield nothing

  /** Encrypts plain-text webhook secrets and re-encrypts those of older keys with the active key (DMS-107). A
    * failure is logged and does not stop the instance: deliveries still read every configured key.
    */
  /** Kinds that fan out to many recipients: one publication makes a delivery per reader. */
  private[app] val BulkJobKinds: Set[String] = Set(NotificationJobs.DeliverKind, WebhookJobs.DeliverKind)

  /** The handlers of each lane of jobs. A lane has a worker of its own: thousands of deliveries never hold up
    * a rendition or a publication behind them (business concept 14.2, ADR 0056). Renditions take seconds each
    * and the reminder checks of all tenants take a good part of every hour at full volume, so neither waits
    * behind other jobs nor holds them up (ADR 0079).
    */
  private[app] final case class JobLanes[H](
      regular: Map[String, H],
      bulk: Map[String, H],
      renditions: Map[String, H],
      reminders: Map[String, H]
  )

  private[app] def jobLanes[H](handlers: Map[String, H]): JobLanes[H] =
    def only(kinds: String => Boolean) = handlers.filter((kind, _) => kinds(kind))
    val renditions                     = Set(RenditionRequest.JobKind)
    val reminders                      = Set(AcknowledgementReminderJobs.CheckKind)
    JobLanes(
      only(kind => !BulkJobKinds(kind) && !renditions(kind) && !reminders(kind)),
      only(BulkJobKinds),
      only(renditions),
      only(reminders)
    )

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
