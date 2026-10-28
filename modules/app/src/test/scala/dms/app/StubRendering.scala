package dms.app

import dms.audit.AuditTrail
import dms.db.{Db, DbError}
import dms.integration.{JobRunner, WorkerConfig}
import dms.rendering.{ArchiveStandard, RenditionRequest}
import dms.rendering.core.*
import zio.*

/** Runs the due rendering jobs (DMS-44) with stand-ins for the rendering worker, veraPDF and object storage,
  * for specs about the workflow around the approval. Real rendering is covered by `RenditionApiSpec`.
  */
object StubRendering:

  private val renderer: PdfRenderer =
    job => ZIO.succeed(RenderedPdf(Chunk.fromArray(job.html.getBytes), Some(1), "stub"))

  private val validator: PdfValidator = (_, standard: ArchiveStandard) =>
    ZIO.succeed(ValidationReport(standard, compliant = true, "stub", Nil))

  /** Processes the due rendering jobs; returns how many ran. */
  def runPending(db: Db): ZIO[RenditionSource & AuditTrail, DbError, Int] =
    for
      source  <- ZIO.service[RenditionSource]
      audit   <- ZIO.service[AuditTrail]
      archive <- RenditionArchive.inMemory
      jobs = RenditionJobs(db, source, renderer, validator, archive, audit)
      processed <- JobRunner(
        db,
        Map(RenditionRequest.JobKind -> jobs.handle),
        WorkerConfig("stub-rendering")
      ).runOnce
    yield processed
