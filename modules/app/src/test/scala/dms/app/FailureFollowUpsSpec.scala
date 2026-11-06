package dms.app

import dms.db.DbError
import dms.documents.{DocumentStatus, LifecycleError, LifecycleEvent}
import dms.documents.core.{DocumentError, PublicationJobs}
import dms.notifications.core.NotificationTemplates
import dms.rendering.ArchiveStandard
import dms.rendering.core.{RenderingError, RenditionJobs, ValidationReport}
import zio.test.*

import java.time.{Duration, Instant}

/** The reminder days and the failure codes of ADR 0064, without a database. */
object FailureFollowUpsSpec extends ZIOSpecDefault:

  private val failedAt = Instant.parse("2026-11-05T10:00:00Z")

  private def after(days: Long, seconds: Long = 0) =
    failedAt.plus(Duration.ofDays(days)).plusSeconds(seconds)

  private val renderingErrors: List[RenderingError] = List(
    RenderingError.Unavailable("down"),
    RenderingError.Rejected("external resource"),
    RenderingError.NotArchivable(ValidationReport(ArchiveStandard.PdfA2b, compliant = false, "veraPDF", Nil)),
    RenderingError.Invalid("no content"),
    RenderingError.TooComplex("too large"),
    RenderingError.TooLong("about 900 pages"),
    RenderingError.TimeLimit("took longer than 120 seconds"),
    RenderingError.Integrity("checksum"),
    RenderingError.Refused("the last approver changed the version"),
    RenderingError.Database(DbError.NoTransaction)
  )

  private val publicationErrors: List[DbError | DocumentError] = List(
    DbError.NoTransaction,
    DocumentError.NotFound("no version"),
    DocumentError.Invalid(List("a problem")),
    DocumentError.InvalidValues(Nil),
    DocumentError.Incomplete(DocumentStatus.Published, Nil),
    DocumentError.Lifecycle(LifecycleError.LegalHoldActive),
    DocumentError.Lifecycle(
      LifecycleError.TransitionNotAllowed(DocumentStatus.Draft, LifecycleEvent.Publish)
    ),
    DocumentError.Conflict("the scope is taken"),
    DocumentError.Database(DbError.NoTransaction)
  )

  /** What the job runner and the database write themselves. */
  private val otherErrors = List(
    "timed out after 270 s",
    "lease expired after the last attempt",
    "[internal] java.lang.RuntimeException: boom",
    "[invalid] invalid rendition job payload: .versionId(missing)"
  )

  def spec = suite("Follow-ups of failed jobs")(
    test("the round is how many reminder days have come") {
      val days = List(3, 7)
      assertTrue(
        FailureFollowUps.dueRound(failedAt, after(3, -1), days).isEmpty,
        FailureFollowUps.dueRound(failedAt, after(3), days).contains(1),
        FailureFollowUps.dueRound(failedAt, after(7, -1), days).contains(1),
        FailureFollowUps.dueRound(failedAt, after(7), days).contains(2),
        FailureFollowUps.dueRound(failedAt, after(100), days).contains(2),
        FailureFollowUps.dueRound(failedAt, after(100), Nil).isEmpty
      )
    },
    test("every code the platform can emit has a reason") {
      val texts = renderingErrors.map(RenditionJobs.jobError) ++
        publicationErrors.map(PublicationJobs.jobError) ++ otherErrors
      val codes = texts.map(RenditionJobs.parseJobError(_)._1)
      assertTrue(
        codes.forall(_.isDefined),
        codes.flatten.forall(NotificationTemplates.FailureReasons.contains),
        // each reason is in use: the table holds no code nobody emits
        NotificationTemplates.FailureReasons.keySet == codes.flatten.toSet
      )
    },
    test("a timeout reads the same in the status as in the notice") {
      assertTrue(
        RenditionJobs.parseJobError("timed out after 270 s") == (Some("timeout"), "timed out after 270 s"),
        RenditionJobs.parseJobError("something else") == (None, "something else")
      )
    }
  )
