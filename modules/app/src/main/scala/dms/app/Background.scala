package dms.app

import dms.audit.core.*
import dms.db.Db
import dms.documents.core.{DocumentService, PublicationJobs, PublicationScheduleConfig}
import dms.integration.{JobRunner, WorkerConfig}
import dms.rendering.RenditionRequest
import dms.rendering.core.RenditionJobs
import dms.storage.S3ObjectStore
import software.amazon.awssdk.services.s3.S3Client
import zio.*

import java.lang.management.ManagementFactory
import java.net.http.HttpClient

/** Background processing of this instance: the job runner (audit, publication and rendition jobs) and the
  * schedulers of periodic audit jobs and of scheduled publications.
  */
object Background:

  def run(config: AppConfig): ZIO[Db & S3Client & DocumentService & RenditionJobs, Throwable, Nothing] =
    for
      db        <- ZIO.service[Db]
      s3        <- ZIO.service[S3Client]
      documents <- ZIO.service[DocumentService]
      rendering <- ZIO.service[RenditionJobs]
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
      runner = JobRunner(
        db,
        jobs.handlers ++ publications.handlers + (RenditionRequest.JobKind -> rendering.handle),
        WorkerConfig(s"${workerName(config)}-jobs")
      )
      nothing <- runner.run.raceFirst(jobs.schedule).raceFirst(publications.schedule)
    yield nothing

  private def workerName(config: AppConfig): String =
    config.worker.name.getOrElse(ManagementFactory.getRuntimeMXBean.getName) // "pid@host"
