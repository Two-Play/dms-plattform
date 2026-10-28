package dms.workflow.core

import dms.kernel.UserId
import dms.workflow.*
import zio.test.*

import java.time.{Duration, Instant}
import java.util.UUID

/** Pure rules of deputyships (DMS-49, ADR 0028): same authority, time limit, forwarding of tasks at read time
  * and the four-eyes rule.
  */
object DeputyRulesSpec extends ZIOSpecDefault:

  private val anna   = UserId("anna")  // principal
  private val ben    = UserId("ben")   // deputy
  private val clara  = UserId("clara") // author
  private val dora   = UserId("dora")  // other candidate
  private val now    = Instant.parse("2026-10-28T10:00:00Z")
  private val day    = Duration.ofDays(1)
  private val always = Instant.EPOCH

  private def held(
      role: String,
      ou: Option[String] = None,
      cls: Option[String] = None,
      until: Option[Instant] = None
  ) = HeldRole(role, ou, cls, always, until)

  private def request(
      roles: List[String] = Nil,
      from: Option[Instant] = None,
      until: Instant = now.plus(day.multipliedBy(14)),
      cls: Option[String] = None,
      deputy: UserId = ben
  ) = NewDeputyship(anna, deputy, from, until, roles, cls, Some("Vacation"))

  private def deputyship(
      from: Instant = now.minus(day),
      until: Instant = now.plus(day),
      roles: List[String] = List("Approver"),
      cls: Option[String] = None,
      principal: UserId = anna,
      deputy: UserId = ben,
      endedAt: Option[Instant] = None
  ) = Deputyship(
    UUID.randomUUID(),
    principal,
    deputy,
    from,
    until,
    roles,
    cls,
    None,
    principal,
    from,
    endedAt,
    endedAt.map(_ => principal)
  )

  private def deputies(
      deputyships: List[Deputyship],
      role: String = "Approver",
      candidates: List[UserId] = List(anna, dora),
      author: UserId = clara,
      classPath: List[String] = List("contract", "service-contract"),
      deputyRoles: Map[UserId, Set[String]] = Map(ben -> Set("Approver"), dora -> Set("Approver")),
      at: Instant = now
  ) = Rules.taskDeputies(role, candidates, author, classPath, deputyships, deputyRoles, at)

  def spec = suite("Deputy rules")(
    suite("same authority")(
      test("the roles default to the principal's workflow roles; the deputy must hold each of them") {
        val principal = List(held("Approver"), held("Reviewer", ou = Some("Legal")), held("Auditor"))
        assertTrue(
          Rules.checkDeputyship(request(), principal, List(held("Approver"), held("Reviewer")), now) ==
            Right(List("Reviewer", "Approver")),
          Rules.checkDeputyship(request(List("Approver")), principal, List(held("Approver")), now) ==
            Right(List("Approver")),
          Rules.checkDeputyship(request(), principal, List(held("Approver")), now) ==
            Left(List("deputy ben does not hold role Reviewer (same authority required)"))
        )
      },
      test("same authority is the same role: an approver of another unit or class may stand in") {
        val principal = List(held("Approver", ou = Some("Legal")))
        assertTrue(
          Rules.checkDeputyship(request(), principal, List(held("Approver", ou = Some("Sales"))), now) ==
            Right(List("Approver")),
          Rules.checkDeputyship(request(), principal, List(held("Approver", cls = Some("policy"))), now) ==
            Right(List("Approver")),
          Rules.checkDeputyship(request(), principal, List(held("Reviewer"), held("Reader")), now).isLeft
        )
      },
      test("roles the principal does not hold, that cannot be delegated or expire are rejected") {
        val expired = List(held("Approver", until = Some(now.minus(day))))
        val result  = (p: List[HeldRole], d: List[HeldRole], roles: List[String]) =>
          Rules.checkDeputyship(request(roles), p, d, now).left.getOrElse(Nil)
        assertTrue(
          result(List(held("Approver")), List(held("Approver")), List("Reviewer")) ==
            List("anna does not hold role Reviewer"),
          result(List(held("TenantAdmin")), List(held("TenantAdmin")), List("TenantAdmin")).head
            .startsWith("role TenantAdmin cannot be delegated"),
          result(List(held("Auditor")), Nil, Nil).head
            .startsWith("anna holds no role taking part in workflows"),
          result(List(held("Approver")), expired, List("Approver")) ==
            List("deputy ben does not hold role Approver (same authority required)"),
          // the deputy's role must still be valid when the period starts
          Rules
            .checkDeputyship(
              request(List("Approver"), from = Some(now.plus(day.multipliedBy(3)))),
              List(held("Approver")),
              List(held("Approver", until = Some(now.plus(day)))),
              now
            )
            .isLeft
        )
      },
      test("the period is limited, ends in the future and after its start; nobody is their own deputy") {
        val roles    = List(held("Approver"))
        val problems = (r: NewDeputyship) => Rules.checkDeputyship(r, roles, roles, now).left.getOrElse(Nil)
        assertTrue(
          problems(request(until = now.minusSeconds(1))).contains("the period must end in the future"),
          problems(request(from = Some(now.plus(day)), until = now.plusSeconds(60)))
            .contains("the period must end after it starts"),
          problems(request(until = now.plus(Duration.ofDays(400))))
            .contains("a deputyship lasts at most 366 days"),
          problems(request(deputy = anna)).contains("a person cannot be their own deputy"),
          problems(request(from = Some(now.plus(day)))).isEmpty
        )
      }
    ),
    suite("forwarding")(
      test("during the period the deputy may complete the principal's open tasks; before and after not") {
        val d = deputyship()
        assertTrue(
          deputies(List(d)) == List(TaskDeputy(ben, anna)),
          deputies(List(d), at = d.validFrom.minusSeconds(1)).isEmpty,
          deputies(List(d), at = d.validUntil).isEmpty,
          deputies(List(d.copy(endedAt = Some(now.minusSeconds(1)), endedBy = Some(anna)))).isEmpty,
          d.status(now) == DeputyshipStatus.Active,
          d.status(d.validFrom.minusSeconds(1)) == DeputyshipStatus.Scheduled,
          d.status(d.validUntil) == DeputyshipStatus.Ended
        )
      },
      test("only for covered roles and classes, and only while the deputy holds the role themselves") {
        val d = deputyship()
        assertTrue(
          deputies(List(d), role = "Reviewer").isEmpty,
          deputies(List(d.copy(documentClass = Some("contract")))) == List(TaskDeputy(ben, anna)),
          deputies(List(d.copy(documentClass = Some("policy")))).isEmpty,
          // same authority at use time: the deputy no longer holds the role
          deputies(List(d), deputyRoles = Map(ben -> Set("Reviewer"))).isEmpty,
          // a deputy of someone who is not a candidate gets nothing
          deputies(List(d), candidates = List(dora)).isEmpty,
          // owner confirmations are not forwarded (owner deputies: DMS-88)
          deputies(List(d), role = WorkflowDefinition.DocumentOwner).isEmpty
        )
      },
      test("four-eyes: a deputy never acts on their own version, and nobody stands in for the author") {
        val d = deputyship()
        assertTrue(
          deputies(List(d), author = ben).isEmpty,
          deputies(List(d), author = anna).isEmpty,
          // a candidate acts for themselves, not as deputy
          deputies(List(d), candidates = List(anna, ben)).isEmpty
        )
      },
      test("one entry per deputy, even if they stand in for several candidates") {
        val forAnna = deputyship()
        val forDora = deputyship(principal = dora)
        assertTrue(deputies(List(forDora, forAnna)) == List(TaskDeputy(ben, anna)))
      }
    )
  )
