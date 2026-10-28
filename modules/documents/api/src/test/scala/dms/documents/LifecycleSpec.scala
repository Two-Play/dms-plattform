package dms.documents

import dms.documents.DocumentStatus.*
import dms.documents.LifecycleEvent.*
import dms.kernel.UserId
import zio.test.*

object LifecycleSpec extends ZIOSpecDefault:
  private val author   = UserId("author-1")
  private val approver = UserId("approver-1")

  def spec = suite("Lifecycle")(
    test("regular path from draft to published") {
      val result = for
        s1 <- Lifecycle.transition(Draft, Submit)
        s2 <- Lifecycle.transition(s1, ReviewPassed)
        s3 <- Lifecycle.approve(s2, author, approver)
        s4 <- Lifecycle.transition(s3, Publish)
      yield s4
      assertTrue(result == Right(Published))
    },
    test("a draft cannot be published directly") {
      assertTrue(
        Lifecycle.transition(Draft, Publish) == Left(LifecycleError.TransitionNotAllowed(Draft, Publish))
      )
    },
    test("an author cannot approve their own version") {
      assertTrue(
        Lifecycle.approve(InApproval, author, author) == Left(
          LifecycleError.SegregationOfDutiesViolated(author)
        )
      )
    },
    test("recertification returns to published") {
      assertTrue(Lifecycle.transition(ReviewDue, Recertify) == Right(Published))
    },
    test("a valid version can be withdrawn without a successor; a superseded one cannot") {
      assertTrue(
        Lifecycle.transition(Published, Withdraw) == Right(Withdrawn),
        Lifecycle.transition(ReviewDue, Withdraw) == Right(Withdrawn),
        Lifecycle.transition(Superseded, Withdraw).isLeft,
        Lifecycle.transition(Approved, Withdraw).isLeft,
        Lifecycle.transition(Withdrawn, Archive) == Right(Archived)
      )
    },
    test("a legal hold prevents destruction") {
      assertTrue(
        Lifecycle.destroy(Archived, legalHold = true) == Left(LifecycleError.LegalHoldActive),
        Lifecycle.destroy(Archived, legalHold = false) == Right(Destroyed)
      )
    },
    test("no transition leaves destroyed") {
      assertTrue(Lifecycle.allowedEvents(Destroyed).isEmpty)
    }
  )
