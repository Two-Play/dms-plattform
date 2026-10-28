package dms.search.core

import dms.authz.*
import dms.documents.DocumentStatus
import dms.kernel.{TenantId, UserId}
import zio.test.*

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Generators of subjects and readable resources, shared with [[SearchIndexSpec]]. */
object AccessGen:
  val now: Instant = Instant.parse("2026-11-02T09:00:00Z")

  val tenant: TenantId      = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
  val otherTenant: TenantId = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000002"))

  val users: List[UserId]     = List("u1", "u2", "u3").map(UserId(_))
  val units: List[String]     = List("Legal", "HR", "IT")
  val groups: List[String]    = List("legal", "board", "hr")
  val classKeys: List[String] = List("contract", "service-contract", "nda", "policy", "hr-policy", "other")
  val classPaths: List[List[String]] = List(
    List("contract"),
    List("contract", "service-contract"),
    List("contract", "nda"),
    List("policy"),
    List("policy", "hr-policy")
  )

  private val day = 1L

  /** Validity windows around `now`, including the boundaries (valid from now: active; valid until now: not).
    */
  val validity: Gen[Any, (Instant, Option[Instant])] = Gen.elements(
    (Instant.EPOCH, None),
    (now.minus(day, ChronoUnit.DAYS), Some(now.plus(day, ChronoUnit.DAYS))),
    (now, None),
    (now.plus(day, ChronoUnit.DAYS), None),
    (Instant.EPOCH, Some(now.minus(day, ChronoUnit.DAYS))),
    (Instant.EPOCH, Some(now))
  )

  val assignment: Gen[Any, RoleAssignment] =
    for
      role  <- Gen.elements(Role.values.toList*)
      unit  <- Gen.option(Gen.elements(units*))
      cls   <- Gen.option(Gen.elements(classKeys*))
      valid <- validity
    yield RoleAssignment(role, unit, cls, valid._1, valid._2)

  def subsetOf[A](values: List[A]): Gen[Any, Set[A]] =
    Gen.listOfBounded(0, values.size)(Gen.elements(values*)).map(_.toSet)

  val subject: Gen[Any, Subject] =
    for
      user        <- Gen.elements(users*)
      assignments <- Gen.listOfBounded(0, 4)(assignment)
      groups      <- subsetOf(groups)
    yield Subject(user, tenant, assignments.toSet, groups)

  val readableStatus: Gen[Any, DocumentStatus] =
    Gen.elements(DocumentStatus.Published, DocumentStatus.ReviewDue, DocumentStatus.Superseded)

  val resource: Gen[Any, Resource] =
    for
      inTenant  <- Gen.int(1, 10).map(_ > 1)
      unit      <- Gen.option(Gen.elements(units*))
      classPath <- Gen.elements(classPaths*)
      level     <- Gen.elements(Confidentiality.values.toList*)
      status    <- readableStatus
      author    <- Gen.elements(users*)
      owner     <- Gen.option(Gen.elements(users*))
      allowed   <- subsetOf(groups)
    yield Resource(
      if inTenant then tenant else otherTenant,
      organizationalUnit = unit,
      documentClassPath = classPath,
      confidentiality = level,
      status = Some(status),
      authorId = Some(author),
      ownerId = owner,
      allowedGroups = allowed
    )

  def indexFields(resource: Resource): Map[String, Set[String]] =
    AccessFilter.fields(
      resource.tenantId.value.toString,
      resource.documentClassPath,
      AccessFilter.attributes(resource)
    )

/** The permission filter of the search grants exactly what [[Policy]] grants for reading indexed versions
  * (DMS-52): compared for generated subjects (roles, scopes, validity, groups) and versions (tenant,
  * organizational unit, class path, confidentiality, status, author, owner, allowed groups).
  */
object AccessFilterSpec extends ZIOSpecDefault:
  import AccessGen.*

  def spec = suite("AccessFilter")(
    test("matches ReadDocument of the policy for every indexed status") {
      check(subject, resource) { (s, r) =>
        val policy = Policy.decide(s, Action.ReadDocument, r, now).isPermitted
        val filter = AccessFilter.forSubject(s, now).matches(indexFields(r))
        assertTrue(policy == filter)
      }
    } @@ TestAspect.samples(5000),
    test("matches ReadPublished of the policy for valid versions") {
      check(subject, resource.filter(r => r.status.exists(_ != DocumentStatus.Superseded))) { (s, r) =>
        val policy = Policy.decide(s, Action.ReadPublished, r, now).isPermitted
        val filter = AccessFilter.forSubject(s, now).matches(indexFields(r))
        assertTrue(policy == filter)
      }
    } @@ TestAspect.samples(5000),
    test("generated cases cover both outcomes") {
      check(Gen.listOfN(400)(subject.zip(resource))) { cases =>
        val outcomes = cases.map((s, r) => Policy.decide(s, Action.ReadDocument, r, now).isPermitted).toSet
        assertTrue(outcomes == Set(true, false))
      }
    } @@ TestAspect.samples(5),
    test("nothing without an active business role; tenant administrators need one") {
      val admin   = Subject(users.head, tenant, Set(RoleAssignment(Role.TenantAdmin)))
      val nobody  = Subject(users.head, tenant, Set.empty)
      val expired = Subject(users.head, tenant, Set(RoleAssignment(Role.Reader, validUntil = Some(now))))
      assertTrue(
        AccessFilter.forSubject(admin, now) == Filter.Nothing,
        AccessFilter.forSubject(nobody, now) == Filter.Nothing,
        AccessFilter.forSubject(expired, now) == Filter.Nothing
      )
    },
    test("an unrestricted reader is filtered by tenant and confidentiality only") {
      val reader = Subject(users.head, tenant, Set(RoleAssignment(Role.Reader)), Set("legal"))
      assertTrue(
        AccessFilter.forSubject(reader, now) ==
          Filter.And(
            List(
              Filter.is("tenantId", tenant.value.toString),
              Filter.Or(
                List(
                  Filter.anyOf("confidentiality", Set("Public", "Internal")),
                  Filter.is("authorId", "u1"),
                  Filter.is("ownerId", "u1"),
                  Filter.anyOf("allowedGroups", Set("legal"))
                )
              )
            )
          )
      )
    },
    test("renders as an OpenSearch bool query") {
      val reader = Subject(
        users.head,
        tenant,
        Set(RoleAssignment(Role.Reader, Some("Legal"), Some("contract")))
      )
      val json = AccessFilter.forSubject(reader, now).toJson.toString
      assertTrue(
        json.contains(""""terms":{"organizationalUnit":["Legal"]}"""),
        json.contains(""""terms":{"classPath":["contract"]}"""),
        json.contains(""""minimum_should_match":1"""),
        Filter.Nothing.toJson.toString == """{"match_none":{}}""",
        Filter.anyOf("x", Nil).toJson.toString == """{"match_none":{}}"""
      )
    }
  )
