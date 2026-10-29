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

/** Who of the readers of a version must acknowledge it (the recipients, DMS-100, ADR 0048): readers who hold
  * an active assignment of one of `roles` (in any scope), are a current member of one of the access `groups`,
  * or hold an active assignment restricted to one of the organizational `units`. Without restriction every
  * reader is a recipient (ADR 0027). Only readers can be recipients: the filter narrows `ReadPublished`, it
  * never widens it.
  */
final case class RecipientFilter(
    roles: Set[Role] = Set.empty,
    groups: Set[String] = Set.empty,
    units: Set[String] = Set.empty
):
  def isEveryReader: Boolean = roles.isEmpty && groups.isEmpty && units.isEmpty

  /** Whether a user with these active assignments and group memberships passes the filter. */
  def admits(active: Set[RoleAssignment], memberOf: Set[String]): Boolean =
    isEveryReader ||
      active.exists(a => roles.contains(a.role) || a.organizationalUnit.exists(units.contains)) ||
      memberOf.exists(groups.contains)

  /** Whether `subject` passes the filter at `now` (readership is decided separately by the policy). */
  def admits(subject: Subject, now: Instant): Boolean =
    admits(subject.assignments.filter(_.isActiveAt(now)), subject.groups)

object RecipientFilter:
  val everyReader: RecipientFilter = RecipientFilter()

  /** The filter of stored role names; names that are not business roles are ignored. */
  def of(roles: Set[String], groups: Set[String], units: Set[String]): RecipientFilter =
    RecipientFilter(roles.flatMap(r => Role.values.find(_.toString == r)), groups, units)

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

  /** The readers of one version (who pass the recipient filter): how many there are and a test whether a user
    * is one of them.
    */
  final class Readers private[Audience] (resource: Resource, filter: RecipientFilter):
    private def admitted(profile: Profile) = filter.admits(profile.assignments, profile.groups)

    private val permitted: Set[Profile] =
      sizes.keySet.filter(p => admitted(p) && decide(Anonymous, p, resource))

    /** Author and owner: the confidentiality rule may let them read although their profile does not. */
    private val personal: Map[UserId, Boolean] =
      (resource.authorId.toSet ++ resource.ownerId.toSet)
        .flatMap(user =>
          profileOf.get(user).map(profile => user -> (admitted(profile) && decide(user, profile, resource)))
        )
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

  /** The readers of one version who pass `filter` (the recipients of its acknowledgement, ADR 0048). */
  def readers(resource: Resource, filter: RecipientFilter = RecipientFilter.everyReader): Readers =
    Readers(resource, filter)

object Audience:
  private final case class Profile(assignments: Set[RoleAssignment], groups: Set[String])

  /** A subject that is neither author nor owner of any version: user ids are never empty. */
  private val Anonymous = UserId("")
