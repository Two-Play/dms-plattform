package dms.app

import dms.authz.{Audience, Groups, Population, RecipientFilter, RoleAssignments}
import dms.db.DbError
import dms.documents.VersionAudience
import dms.kernel.TenantId
import zio.*

import java.time.Instant

/** The recipients of an acknowledgement (DMS-100, ADR 0048): the readers of a valid version (ADR 0027) who
  * pass the recipient filter its class set when it was published. Versions without a recorded filter (valid
  * before migration V29 and superseded since) ask every reader.
  */
object Recipients:

  /** The filter of a version's recorded settings; every reader without settings. */
  def filterOf(audience: Option[VersionAudience]): RecipientFilter =
    audience.fold(RecipientFilter.everyReader)(a =>
      RecipientFilter.of(a.recipients.roles, a.recipients.groups, a.recipients.units)
    )

  /** The readers of the tenant at `now`: active role assignments and group memberships of every user, for
    * deciding the readers of many versions at once ([[Audience]]).
    */
  def population(now: Instant, tenant: TenantId): ZIO[RoleAssignments & Groups, DbError, Audience] =
    for
      assignments <- ZIO.serviceWithZIO[RoleAssignments](_.activeAt(now))
      groups      <- ZIO.serviceWithZIO[Groups](_.currentMemberships)
    yield Audience(Population(tenant, assignments, groups), now)
