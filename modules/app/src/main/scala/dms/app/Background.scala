package dms.app

import dms.audit.core.*
import dms.db.Db
import dms.integration.{JobRunner, WorkerConfig}
import dms.storage.S3ObjectStore
import software.amazon.awssdk.services.s3.S3Client
import zio.*

import java.lang.management.ManagementFactory
import java.net.http.HttpClient

/** Background processing of this instance: the job runner and the scheduler of periodic audit jobs. */
object Background:

  def run(config: AppConfig): ZIO[Db & S3Client, Throwable, Nothing] =
    for
      db <- ZIO.service[Db]
      s3 <- ZIO.service[S3Client]
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
      runner = JobRunner(db, jobs.handlers, WorkerConfig(s"${workerName(config)}-jobs"))
      nothing <- runner.run.raceFirst(jobs.schedule)
    yield nothing

  private def workerName(config: AppConfig): String =
    config.worker.name.getOrElse(ManagementFactory.getRuntimeMXBean.getName) // "pid@host"
