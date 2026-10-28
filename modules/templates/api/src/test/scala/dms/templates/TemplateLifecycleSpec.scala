package dms.templates

import dms.kernel.{TemplateId, UserId}
import zio.test.*

import java.time.Instant
import java.util.UUID

object TemplateLifecycleSpec extends ZIOSpecDefault:
  import TemplateStatus.*

  private val creator = UserId("designer-1")
  private val editor  = UserId("designer-2")
  private val other   = UserId("designer-3")

  private def version(status: TemplateStatus) = TemplateVersion(
    TemplateId.random(),
    1,
    "Contract",
    None,
    UUID.randomUUID(),
    1,
    status,
    TemplateContent(Nil),
    creator,
    Instant.EPOCH,
    editedBy = List(editor)
  )

  def spec = suite("TemplateLifecycle")(
    test("allowed transitions: forward through the lifecycle and from testing back to draft") {
      assertTrue(
        TemplateLifecycle.allowedTargets(Draft) == Set(Testing),
        TemplateLifecycle.allowedTargets(Testing) == Set(Draft, Released),
        TemplateLifecycle.allowedTargets(Released) == Set(Deprecated),
        TemplateLifecycle.allowedTargets(Deprecated) == Set(Archived),
        TemplateLifecycle.allowedTargets(Archived).isEmpty
      )
    },
    test("only drafts are editable; drafts and versions in testing are open") {
      assertTrue(
        TemplateStatus.values.filter(TemplateLifecycle.isEditable).toList == List(Draft),
        TemplateStatus.values.filter(TemplateLifecycle.isOpen).toList == List(Draft, Testing)
      )
    },
    test("releasing needs someone who neither created nor changed the version") {
      assertTrue(
        TemplateLifecycle.transition(version(Testing), Released, creator) ==
          Left(TemplateLifecycleError.SegregationOfDutiesViolated(creator)),
        TemplateLifecycle.transition(version(Testing), Released, editor) ==
          Left(TemplateLifecycleError.SegregationOfDutiesViolated(editor)),
        TemplateLifecycle.transition(version(Testing), Released, other) == Right(Released),
        TemplateLifecycle.transition(version(Testing), Draft, creator) == Right(Draft)
      )
    },
    test("skipping a step or going back from released is not allowed") {
      assertTrue(
        TemplateLifecycle.transition(version(Draft), Released, other) ==
          Left(TemplateLifecycleError.TransitionNotAllowed(Draft, Released)),
        TemplateLifecycle.transition(version(Released), Draft, other) ==
          Left(TemplateLifecycleError.TransitionNotAllowed(Released, Draft)),
        TemplateLifecycle.transition(version(Released), Archived, other).isLeft
      )
    }
  )
