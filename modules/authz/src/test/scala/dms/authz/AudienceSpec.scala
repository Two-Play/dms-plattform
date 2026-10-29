package dms.authz

import dms.documents.DocumentStatus
import dms.kernel.{TenantId, UserId}
import zio.test.*

import java.time.Instant

/** The readers of versions decided for a whole population at once (DMS-59, ADR 0036) agree with deciding
  * [[Policy]] user by user.
  */
object AudienceSpec extends ZIOSpecDefault:

  private val now    = Instant.parse("2026-11-02T09:00:00Z")
  private val tenant = TenantId.random()

  private val units   = List(None, Some("hr"), Some("finance"))
  private val classes = List(None, Some("policy"), Some("contract"))
  private val roles   = List(Role.Reader, Role.Owner, Role.TenantAdmin, Role.ComplianceOfficer)

  private val assignment: Gen[Any, RoleAssignment] =
    for
      role    <- Gen.fromIterable(roles)
      unit    <- Gen.fromIterable(units)
      cls     <- Gen.fromIterable(classes)
      expired <- Gen.int(0, 4).map(_ == 0)
    yield RoleAssignment(
      role,
      unit,
      cls,
      validUntil = Option.when(expired)(now.minusSeconds(60)),
      validFrom = if expired then now.minusSeconds(3600) else Instant.EPOCH
    )

  private val population: Gen[Any, Population] =
    Gen
      .listOfBounded(1, 40)(
        for
          held   <- Gen.setOfBounded(0, 3)(assignment)
          groups <- Gen.setOfBounded(0, 2)(Gen.fromIterable(List("board", "legal")))
        yield (held, groups)
      )
      .map(users =>
        val ids = users.indices.map(i => UserId(s"user-$i"))
        Population(tenant, ids.zip(users.map(_._1)).toMap, ids.zip(users.map(_._2)).toMap)
      )

  private def resource(users: Int): Gen[Any, Resource] =
    for
      unit    <- Gen.fromIterable(units)
      path    <- Gen.fromIterable(List(List("policy"), List("policy", "it"), List("contract")))
      level   <- Gen.fromIterable(Confidentiality.values.toList)
      author  <- Gen.int(0, users + 2)
      owner   <- Gen.option(Gen.int(0, users + 2))
      allowed <- Gen.setOfBounded(0, 1)(Gen.fromIterable(List("board", "legal")))
      status  <- Gen.fromIterable(
        List(DocumentStatus.Published, DocumentStatus.ReviewDue, DocumentStatus.Draft)
      )
    yield Resource(
      tenant,
      organizationalUnit = unit,
      documentClassPath = path,
      confidentiality = level,
      status = Some(status),
      authorId = Some(UserId(s"user-$author")),
      ownerId = owner.map(o => UserId(s"user-$o")),
      allowedGroups = allowed
    )

  private def expected(p: Population, r: Resource): Set[UserId] =
    p.users.filter(u => Policy.decide(p.subject(u), Action.ReadPublished, r, now).isPermitted)

  def spec = suite("Audience")(
    test("agrees with the policy decided per user") {
      check(population.flatMap(p => resource(p.users.size).map(p -> _))) { (p, r) =>
        val readers = Audience(p, now).readers(r)
        val want    = expected(p, r)
        assertTrue(
          readers.size == want.size,
          readers.members == want.toList.sortBy(_.value),
          p.users.forall(u => readers.contains(u) == want.contains(u)),
          !readers.contains(UserId("stranger"))
        )
      }
    } @@ TestAspect.samples(300),
    test("author and owner of a confidential version count although their profile alone would not") {
      val reader = Set(RoleAssignment(Role.Reader))
      val p      = Population(
        tenant,
        Map(
          UserId("a")     -> reader,
          UserId("o")     -> reader,
          UserId("x")     -> reader,
          UserId("admin") -> Set(RoleAssignment(Role.TenantAdmin))
        )
      )
      val secret = Resource(
        tenant,
        confidentiality = Confidentiality.Confidential,
        status = Some(DocumentStatus.Published),
        authorId = Some(UserId("a")),
        ownerId = Some(UserId("o"))
      )
      val readers = Audience(p, now).readers(secret)
      assertTrue(readers.size == 2, readers.members == List(UserId("a"), UserId("o")))
    },
    test("a recipient filter narrows the readers and agrees with the filter decided per user (DMS-100)") {
      val filters = Gen.oneOf(
        Gen.const(RecipientFilter.everyReader),
        Gen.setOfBounded(1, 2)(Gen.fromIterable(roles)).map(r => RecipientFilter(roles = r)),
        Gen
          .setOfBounded(1, 2)(Gen.fromIterable(List("board", "legal")))
          .map(g => RecipientFilter(groups = g)),
        Gen.setOfBounded(1, 2)(Gen.fromIterable(List("hr", "finance"))).map(u => RecipientFilter(units = u))
      )
      check(population.flatMap(p => resource(p.users.size).map(p -> _)), filters) { (pr, filter) =>
        val (p, r)     = pr
        val recipients = Audience(p, now).readers(r, filter)
        val want       = expected(p, r).filter(u => filter.admits(p.subject(u), now))
        assertTrue(
          recipients.size == want.size,
          recipients.members == want.toList.sortBy(_.value),
          want.subsetOf(expected(p, r))
        )
      }
    } @@ TestAspect.samples(300),
    test("recipients by role, group or unit; expired assignments and the tenant administrator do not count") {
      val p = Population(
        tenant,
        Map(
          // Tenant-wide readers who also hold a role restricted to their unit.
          UserId("hr-reader")  -> Set(RoleAssignment(Role.Reader), RoleAssignment(Role.Author, Some("hr"))),
          UserId("fin-reader") -> Set(
            RoleAssignment(Role.Reader),
            RoleAssignment(Role.Author, Some("finance"))
          ),
          UserId("owner")   -> Set(RoleAssignment(Role.Owner)),
          UserId("expired") -> Set(
            RoleAssignment(Role.Reader),
            RoleAssignment(Role.Owner, validUntil = Some(now.minusSeconds(1)))
          ),
          UserId("board") -> Set(RoleAssignment(Role.Reader)),
          UserId("admin") -> Set(RoleAssignment(Role.TenantAdmin))
        ),
        Map(UserId("board") -> Set("board"))
      )
      val policy                      = Resource(tenant, status = Some(DocumentStatus.Published))
      val audience                    = Audience(p, now)
      def members(f: RecipientFilter) = audience.readers(policy, f).members.map(_.value)
      assertTrue(
        members(RecipientFilter.everyReader) == List("board", "expired", "fin-reader", "hr-reader", "owner"),
        members(RecipientFilter(roles = Set(Role.Owner))) == List("owner"),
        members(RecipientFilter(units = Set("hr"))) == List("hr-reader"),
        members(RecipientFilter(groups = Set("board"), units = Set("finance"))) == List(
          "board",
          "fin-reader"
        ),
        members(RecipientFilter(roles = Set(Role.TenantAdmin))).isEmpty,
        RecipientFilter.of(Set("Owner", "Nonsense"), Set.empty, Set.empty) ==
          RecipientFilter(roles = Set(Role.Owner))
      )
    }
  )
