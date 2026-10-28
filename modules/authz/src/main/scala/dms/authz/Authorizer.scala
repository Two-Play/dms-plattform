package dms.authz

import com.augustnagro.magnum.*
import dms.db.{Db, DbError}
import dms.identity.Principal
import dms.kernel.UserId
import zio.*

import java.time.{Instant, OffsetDateTime}
import java.util.UUID

final case class AccessDenied(action: Action, reason: String)

/** Role assignments of the current tenant. Must run inside `Db.transact(tenant)`; RLS limits rows to the
  * tenant.
  */
trait RoleAssignments:
  def forUser(user: UserId): IO[DbError, Set[RoleAssignment]]
  def grant(user: UserId, assignment: RoleAssignment, grantedBy: UserId): IO[DbError, UUID]
  def revoke(id: UUID, at: Instant): IO[DbError, Boolean]

  /** Every assignment of `role` in the tenant with its holder, including expired ones (callers check
    * [[RoleAssignment.isActiveAt]] and the scope).
    */
  def holders(role: Role): IO[DbError, List[(UserId, RoleAssignment)]]

object RoleAssignments:
  object Postgres extends RoleAssignments:
    private type Row = (String, Option[String], Option[String], OffsetDateTime, Option[OffsetDateTime])

    def forUser(user: UserId): IO[DbError, Set[RoleAssignment]] =
      Db.sql(
        sql"""select role, organizational_unit, document_class, valid_from, valid_until
              from dms.role_assignment where user_id = ${user.value}""".query[Row].run()
      ).map(_.map { case (role, ou, cls, from, until) =>
        RoleAssignment(Role.valueOf(role), ou, cls, from.toInstant, until.map(_.toInstant))
      }.toSet)

    def grant(user: UserId, assignment: RoleAssignment, grantedBy: UserId): IO[DbError, UUID] =
      Db.sql(
        sql"""insert into dms.role_assignment
                (tenant_id, user_id, role, organizational_unit, document_class, valid_from, valid_until, granted_by)
              values (dms.current_tenant(), ${user.value}, ${assignment.role.toString}, ${assignment.organizationalUnit},
                      ${assignment.documentClass}, ${toOffset(assignment.validFrom)},
                      ${assignment.validUntil.map(toOffset)}, ${grantedBy.value})
              returning id""".query[UUID].run().head
      )

    def revoke(id: UUID, at: Instant): IO[DbError, Boolean] =
      Db.sql(
        sql"""update dms.role_assignment set valid_until = ${toOffset(at)}
              where id = $id and (valid_until is null or valid_until > ${toOffset(at)})""".update.run() > 0
      )

    def holders(role: Role): IO[DbError, List[(UserId, RoleAssignment)]] =
      Db.sql(
        sql"""select user_id, organizational_unit, document_class, valid_from, valid_until
              from dms.role_assignment where role = ${role.toString} order by user_id"""
          .query[(String, Option[String], Option[String], OffsetDateTime, Option[OffsetDateTime])]
          .run()
          .toList
      ).map(_.map { case (user, ou, cls, from, until) =>
        UserId(user) -> RoleAssignment(role, ou, cls, from.toInstant, until.map(_.toInstant))
      })

    private def toOffset(i: Instant): OffsetDateTime = OffsetDateTime.ofInstant(i, java.time.ZoneOffset.UTC)

  val postgres: ULayer[RoleAssignments] = ZLayer.succeed(Postgres)

/** Applies [[Policy]] for the signed-in caller and logs every denial with its reason. */
final class Authorizer(assignments: RoleAssignments, val groups: Groups):

  /** Builds the subject from the principal, its role assignments and its current access group memberships
    * (ADR 0032). Runs inside the caller's transaction.
    */
  def subject(principal: Principal): IO[DbError, Subject] =
    for
      held     <- assignments.forUser(principal.userId)
      memberOf <- groups.groupsOf(principal.userId)
    yield Subject(principal.userId, principal.tenantId, held, memberOf)

  /** `subject` acting as deputy of `principal` (DMS-49, ADR 0028): extended by the principal's assignments of
    * `roles`, so the deputy acts within the principal's scope. Callers use it only for the document of a task
    * the deputy may complete during an active deputyship; [[Policy]] still checks validity and scope of each
    * assignment.
    */
  def onBehalfOf(subject: Subject, principal: UserId, roles: Set[Role]): IO[DbError, Subject] =
    assignments
      .forUser(principal)
      .map(held =>
        subject.copy(assignments = subject.assignments ++ held.filter(a => roles.contains(a.role)))
      )

  def check(subject: Subject, action: Action, resource: Resource): IO[AccessDenied, Set[Condition]] =
    Clock.instant.flatMap { now =>
      Policy.decide(subject, action, resource, now) match
        case Decision.Permit(conditions) => ZIO.succeed(conditions)
        case Decision.Deny(reason)       =>
          ZIO.logAnnotate(
            LogAnnotation("tenant", subject.tenantId.value.toString),
            LogAnnotation("user", subject.userId.value),
            LogAnnotation("action", action.toString)
          )(ZIO.logWarning(s"Access denied: $reason")) *> ZIO.fail(AccessDenied(action, reason))
    }

object Authorizer:
  val layer: URLayer[RoleAssignments & Groups, Authorizer] = ZLayer.fromFunction(Authorizer(_, _))
