package dms.workflow.core

import dms.documents.{DocumentStatus, LifecycleEvent}
import dms.kernel.UserId
import dms.workflow.*
import zio.test.*

import java.time.{Duration, Instant}
import java.util.UUID

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
        assertTrue(
          Rules.eligible(List(clara, anna, ben), Set(anna), excludeAuthors = true) == List(ben, clara)
        )
      },
      test("if the author is the only holder, nobody is eligible and the task waits") {
        assertTrue(Rules.eligible(List(anna), Set(anna), excludeAuthors = true).isEmpty)
      },
      test("without the four-eyes rule the author may take part") {
        assertTrue(
          Rules.eligible(List(ben, anna, anna), Set(anna), excludeAuthors = false) == List(anna, ben)
        )
      },
      test("whoever changed the version counts like the author; with only them the task waits (DMS-159)") {
        assertTrue(
          Rules.eligible(List(clara, anna, ben), Set(anna, ben), excludeAuthors = true) == List(clara),
          Rules.eligible(List(anna, ben), Set(anna, ben), excludeAuthors = true).isEmpty
        )
      },
      test("reviews follow the four-eyes setting, approvals leave the authors out whatever it says") {
        assertTrue(
          Rules.excludesAuthors(fourEyes = true, StepKind.Review),
          !Rules.excludesAuthors(fourEyes = false, StepKind.Review),
          Rules.excludesAuthors(fourEyes = true, StepKind.Approval),
          Rules.excludesAuthors(fourEyes = false, StepKind.Approval)
        )
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
      test("a voter who changed the version since resets it, even with the same content (DMS-159)") {
        assertTrue(
          Rules.resume(definition, Some("approval"), a, a, voterEdited = true) ==
            Rules.Resume("review", contentChanged = false, voterEdited = true),
          Rules.resume(definition, Some("approval"), a, a, voterEdited = true).reset,
          !Rules.resume(definition, Some("approval"), a, a).reset
        )
      },
      test("only approving votes that stand count against someone who changed the version (DMS-159)") {
        def voted(
            voter: UserId,
            decision: VoteDecision = VoteDecision.Approve,
            step: String = "review",
            invalidated: Option[Instant] = None,
            actingFor: Option[UserId] = None
        ) = Vote(
          UUID.randomUUID(),
          UUID.randomUUID(),
          "task",
          UUID.randomUUID(),
          1,
          step,
          "lane",
          voter,
          decision,
          None,
          a,
          None,
          now,
          invalidated,
          actingFor
        )
        def edited(votes: Vote*)(fourEyes: Boolean = true) =
          Rules.voterEdited(votes.toList, Set(anna), fourEyes, definition)
        assertTrue(
          edited(voted(anna))(),
          edited(voted(ben, actingFor = Some(anna)))(),
          edited(voted(anna, step = "approval"))(fourEyes = false),
          !edited(voted(ben))(),
          !edited(voted(anna, VoteDecision.RequestChanges))(),
          !edited(voted(anna, invalidated = Some(now)))(),
          !edited(voted(anna))(fourEyes = false)
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
    suite("the additional approval for a lower confidentiality (ADR 0083)")(
      test("it comes last, once, as an approval of the approver role, also on the editorial path") {
        val lowered = WorkflowDefinition.withLowering(definition)
        val minor   = WorkflowDefinition.withLowering(WorkflowDefinition.editorial)
        assertTrue(
          lowered.steps.last.key == WorkflowDefinition.LoweringStep,
          lowered.steps.last.kind == StepKind.Approval,
          lowered.steps.last.lanes.map(_.role) == List("Approver"),
          WorkflowDefinition.withLowering(lowered) == lowered,
          WorkflowDefinition.withoutLowering(lowered) == definition,
          minor.steps.map(_.key) == List("editorial-confirmation", WorkflowDefinition.LoweringStep)
        )
      },
      test("whoever changed the version takes no part, even with the four-eyes rule off") {
        val kind = WorkflowDefinition.loweringStep.kind
        assertTrue(
          Rules.excludesAuthors(fourEyes = false, kind),
          Rules.eligible(
            List(UserId("editor"), UserId("second")),
            Set(UserId("editor")),
            Rules.excludesAuthors(fourEyes = false, kind)
          ) == List(UserId("second"))
        )
      },
      test("no deputy stands in for an editor, and no editor stands in for a candidate") {
        val second = UserId("second")
        val editor = UserId("editor")
        val roles  = Map(editor -> Set("Approver"), UserId("deputy") -> Set("Approver"))
        def deputyship(principal: UserId, deputy: UserId) =
          Deputyship(
            UUID.randomUUID(),
            principal,
            deputy,
            now.minusSeconds(60),
            now.plusSeconds(60),
            List("Approver"),
            None,
            None,
            principal,
            now.minusSeconds(60),
            None,
            None
          )
        assertTrue(
          Rules
            .taskDeputies(
              "Approver",
              List(second),
              Set(editor),
              Nil,
              List(deputyship(second, editor)),
              roles,
              now
            )
            .isEmpty,
          Rules
            .taskDeputies(
              "Approver",
              List(editor),
              Set(editor),
              Nil,
              List(deputyship(editor, UserId("deputy"))),
              roles,
              now
            )
            .isEmpty
        )
      },
      test("a version whose request was refused goes on from the step before it") {
        val resume = Rules.resume(definition, Some(WorkflowDefinition.LoweringStep), a, a)
        assertTrue(resume == Rules.Resume("approval", contentChanged = false))
      },
      test("a request after a return keeps the place of the return, a new one comes back to the step") {
        val lowered = WorkflowDefinition.withLowering(definition)
        assertTrue(
          Rules.resume(lowered, Some("approval"), a, a).step == "approval",
          Rules.resume(lowered, Some(WorkflowDefinition.LoweringStep), a, a).step ==
            WorkflowDefinition.LoweringStep,
          Rules.resume(lowered, Some(WorkflowDefinition.LoweringStep), a, b).step == "review"
        )
      },
      test("a class cannot define a step of that name; the BPMN of the definition has its task") {
        val own = definition.copy(steps = definition.steps :+ WorkflowDefinition.loweringStep)
        val xml = Bpmn.render("p", "p", WorkflowDefinition.withLowering(definition))
        assertTrue(
          WorkflowDefinition.problems(own).exists(_.contains("reserved")),
          WorkflowDefinition.problems(WorkflowDefinition.withLowering(definition)).nonEmpty,
          xml.contains(Rules.taskKey(WorkflowDefinition.LoweringStep, "approver"))
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
      test("the editorial path is one approval step in which the document owner confirms (DMS-46)") {
        val editorial = WorkflowDefinition.editorial
        assertTrue(
          WorkflowDefinition.problems(editorial).isEmpty,
          editorial.steps.map(_.kind) == List(StepKind.Approval),
          editorial.steps.flatMap(_.lanes).map(_.role) == List(WorkflowDefinition.DocumentOwner)
        )
      },
      test("the owner confirms an editorial version unless they wrote it or there is no owner") {
        val owner  = UserId("owner-1")
        val author = UserId("author-1")
        assertTrue(
          Rules.ownerConfirmation(Some(owner), Set(author)) == List(owner),
          Rules.ownerConfirmation(Some(author), Set(author)).isEmpty,
          Rules.ownerConfirmation(None, Set(author)).isEmpty,
          // An owner who changed the version does not confirm it either (DMS-159).
          Rules.ownerConfirmation(Some(owner), Set(author, owner)).isEmpty
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
