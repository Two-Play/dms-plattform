package dms.authz

import dms.kernel.{TenantId, UserId}

import java.time.Instant

/** Who of a tenant's users holds which business role assignments and access groups: the input to compute the
  * readers (the acknowledgement audience, ADR 0027) of many versions at once.
  */
final case class Population(
    tenant: TenantId,
    assignments: Map[UserId, Set[RoleAssignment]],
    groups: Map[UserId, Set[String]] = Map.empty
):
  def users: Set[UserId] = assignments.keySet

  def subject(user: UserId): Subject =
    Subject(user, tenant, assignments.getOrElse(user, Set.empty), groups.getOrElse(user, Set.empty))

/** The readers of document versions (`ReadPublished`, ADR 0027: everyone who may read a valid version must
  * acknowledge it), decided by [[Policy]] for a whole [[Population]] (compliance cockpit, ADR 0036).
  *
  * Evaluating the policy per user and version costs users × versions decisions. Users whose active
  * assignments (role, organizational unit, class; without the validity period) and groups are equal are
  * decided alike, except where the policy looks at the user id itself: the confidentiality rule lets the
  * version's author and owner read. So the policy is evaluated once per distinct profile and version with a
  * subject that is neither author nor owner, and author and owner are decided on their own. Tenants typically
  * have far fewer profiles than users, so the cost is profiles × versions plus one lookup per
  * acknowledgement.
  *
  * @param now
  *   the moment assignments must be active at
  */
final class Audience(population: Population, now: Instant):
  import Audience.*

  private val profileOf: Map[UserId, Profile] =
    population.assignments.map { (user, held) =>
      user -> Profile(
        held
          .filter(_.isActiveAt(now))
          .map(a => RoleAssignment(a.role, a.organizationalUnit, a.documentClass)),
        population.groups.getOrElse(user, Set.empty)
      )
    }

  private val sizes: Map[Profile, Int] = profileOf.values.groupMapReduce(identity)(_ => 1)(_ + _)

  private def decide(user: UserId, profile: Profile, resource: Resource): Boolean =
    Policy
      .decide(
        Subject(user, population.tenant, profile.assignments, profile.groups),
        Action.ReadPublished,
        resource,
        now
      )
      .isPermitted

  /** The readers of one version: how many there are and a test whether a user is one of them. */
  final class Readers private[Audience] (resource: Resource):
    private val permitted: Set[Profile] = sizes.keySet.filter(decide(Anonymous, _, resource))

    /** Author and owner: the confidentiality rule may let them read although their profile does not. */
    private val personal: Map[UserId, Boolean] =
      (resource.authorId.toSet ++ resource.ownerId.toSet)
        .flatMap(user => profileOf.get(user).map(profile => user -> decide(user, profile, resource)))
        .toMap

    def contains(user: UserId): Boolean =
      personal.get(user) match
        case Some(reads) => reads
        case None        => profileOf.get(user).exists(permitted.contains)

    val size: Int =
      permitted.toList.map(sizes).sum +
        personal.toList
          .map((user, reads) => (if reads then 1 else 0) - (if permitted(profileOf(user)) then 1 else 0))
          .sum

    /** All readers, sorted by user id; evaluates one lookup per user of the population. */
    def members: List[UserId] = population.users.filter(contains).toList.sortBy(_.value)

  def readers(resource: Resource): Readers = Readers(resource)

object Audience:
  private final case class Profile(assignments: Set[RoleAssignment], groups: Set[String])

  /** A subject that is neither author nor owner of any version: user ids are never empty. */
  private val Anonymous = UserId("")
