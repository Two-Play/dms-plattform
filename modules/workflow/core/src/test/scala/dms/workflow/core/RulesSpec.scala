package dms.workflow.core

import dms.documents.{DocumentStatus, LifecycleEvent}
import dms.kernel.UserId
import dms.workflow.*
import zio.test.*

import java.time.{Duration, Instant}

/** Pure rules: participant resolution with the four-eyes rule, resuming after a return, votes and step-up. */
object RulesSpec extends ZIOSpecDefault:

  private val anna  = UserId("anna")
  private val ben   = UserId("ben")
  private val clara = UserId("clara")

  private val definition = WorkflowDefinition(
    List(
      Step("formal", "Formal check", StepKind.Review, List(Lane("office", "Reviewer"))),
      Step(
        "review",
        "Review",
        StepKind.Review,
        List(Lane("department", "Reviewer"), Lane("legal", "Reviewer"))
      ),
      Step("approval", "Approval", StepKind.Approval, List(Lane("approver", "Approver")))
    ),
    resetTo = Some("review")
  )

  private val now    = Instant.parse("2026-10-28T12:00:00Z")
  private val window = Duration.ofMinutes(5)
  private val a      = "a" * 64
  private val b      = "b" * 64

  def spec = suite("Rules")(
    suite("participants and the four-eyes rule")(
      test("the author is not a participant; the next holders take the task") {
        assertTrue(Rules.eligible(List(clara, anna, ben), author = anna, fourEyes = true) == List(ben, clara))
      },
      test("if the author is the only holder, nobody is eligible and the task waits") {
        assertTrue(Rules.eligible(List(anna), author = anna, fourEyes = true).isEmpty)
      },
      test("without the four-eyes rule the author may take part") {
        assertTrue(Rules.eligible(List(ben, anna, anna), author = anna, fourEyes = false) == List(anna, ben))
      }
    ),
    suite("resuming a returned version")(
      test("unchanged content resumes at the step that returned it") {
        assertTrue(
          Rules.resume(definition, Some("approval"), a, a) == Rules.Resume("approval", contentChanged = false)
        )
      },
      test("changed content resets to the configured step") {
        assertTrue(
          Rules.resume(definition, Some("approval"), a, b) == Rules.Resume("review", contentChanged = true)
        )
      },
      test("changed content resets to the first step by default") {
        assertTrue(
          Rules.resume(definition.copy(resetTo = None), Some("approval"), a, b).step == "formal"
        )
      }
    ),
    suite("votes")(
      test("a plain approval needs no reason") {
        assertTrue(
          Rules.checkVote(StepKind.Review, VoteDecision.Approve, None, None, now, window).isRight
        )
      },
      test("every other decision needs a reason") {
        val results =
          List(VoteDecision.ApproveWithConditions, VoteDecision.RequestChanges, VoteDecision.Reject)
            .map(d => Rules.checkVote(StepKind.Review, d, Some("  "), None, now, window))
        assertTrue(results.forall(_.left.exists(_.isInstanceOf[WorkflowError.Invalid])))
      },
      test("an approval vote needs a sign-in within the window") {
        def vote(authTime: Option[Instant]) =
          Rules.checkVote(StepKind.Approval, VoteDecision.Approve, None, authTime, now, window)
        assertTrue(
          vote(Some(now.minusSeconds(60))).isRight,
          vote(Some(now.minusSeconds(301))) == Left(WorkflowError.ReauthenticationRequired),
          vote(None) == Left(WorkflowError.ReauthenticationRequired)
        )
      },
      test("review votes need no recent sign-in") {
        assertTrue(Rules.checkVote(StepKind.Review, VoteDecision.Approve, None, None, now, window).isRight)
      },
      test("a return from review requests changes, from approval it rejects") {
        assertTrue(
          Rules.returnEvent(DocumentStatus.InReview) == Right(LifecycleEvent.RequestChanges),
          Rules.returnEvent(DocumentStatus.InApproval) == Right(LifecycleEvent.Reject),
          Rules.returnEvent(DocumentStatus.Draft).isLeft
        )
      }
    ),
    suite("definitions")(
      test("the default workflow is valid") {
        assertTrue(WorkflowDefinition.problems(WorkflowDefinition.default).isEmpty)
      },
      test("a workflow needs an approval step after its reviews and unique keys") {
        val noApproval = WorkflowDefinition(List(definition.steps.head))
        val wrongOrder = WorkflowDefinition(definition.steps.reverse)
        val duplicate  = WorkflowDefinition(definition.steps :+ definition.steps.last)
        val unknown    = definition.copy(resetTo = Some("nope"))
        assertTrue(
          WorkflowDefinition.problems(noApproval).exists(_.contains("approval step")),
          WorkflowDefinition.problems(wrongOrder).exists(_.contains("before the approval")),
          WorkflowDefinition.problems(duplicate).exists(_.contains("unique")),
          WorkflowDefinition.problems(unknown).exists(_.contains("unknown step"))
        )
      },
      test("task keys round-trip") {
        assertTrue(Rules.parseTaskKey(Rules.taskKey("review", "legal")) == Some(("review", "legal")))
      }
    ),
    suite("generated BPMN")(
      test("one user task per lane, a resume gateway and a skippable conditional step") {
        val xml = Bpmn.render(
          "approval-contract",
          "Contract <approval>",
          definition.copy(steps =
            definition.steps.updated(0, definition.steps.head.copy(when = StepCondition.ExtraReviewRequired))
          )
        )
        assertTrue(
          xml.contains("""<userTask id="task.review.legal""""),
          xml.contains("""<userTask id="task.review.department""""),
          xml.contains("""<exclusiveGateway id="enter.formal" default="f.formal.skip"/>"""),
          xml.contains("${resumeAt == &apos;approval&apos;}"),
          xml.contains("Contract &lt;approval&gt;")
        )
      }
    )
  )
