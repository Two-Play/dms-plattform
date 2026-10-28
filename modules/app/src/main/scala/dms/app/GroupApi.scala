package dms.app

import dms.audit.*
import dms.authz.{
  AccessDenied,
  AccessGroup,
  Action,
  Authorizer,
  ConfigurationArea,
  GroupMembership,
  Groups,
  Resource
}
import dms.db.{Db, DbError, Jsonb}
import dms.identity.{Authenticator, Principal}
import dms.kernel.UserId
import dms.workflow.core.{ApprovalWorkflow, WorkflowError}
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.Instant

final case class CreateGroup(
    /** Stable key stored in document versions: lowercase letters, digits and hyphens, at most 63 characters.
      */
    key: String,
    name: String
) derives JsonCodec,
      Schema

final case class RenameGroup(name: String) derives JsonCodec, Schema

final case class GroupView(key: String, name: String) derives JsonCodec, Schema

final case class GroupMemberView(
    userId: String,
    addedBy: String,
    addedAt: Instant,
    /** Set when the membership ended (kept for the history of permissions). */
    removedBy: Option[String],
    removedAt: Option[Instant]
) derives JsonCodec,
      Schema

/** Access groups for confidential documents (business concept 2.3, DMS-101, ADR 0032).
  *
  * A confidential or strictly confidential version names the groups (core field `allowedGroups`) whose
  * members may see it besides its author and owner. Everyone signed in lists the groups to pick them; tenant
  * administrators (`Configure` on `Groups`) create groups and manage their members. Changes of memberships
  * take effect on the next request and are recorded in the audit trail. In the same transaction, the open and
  * waiting workflow tasks of confidential versions naming the group get their participants resolved again
  * (DMS-105), so a new member takes part in running reviews and a former member no longer does.
  */
object GroupApi:
  type Env = Authenticator & Db & Authorizer & AuditTrail & ApprovalWorkflow

  private val base     = "api" / "v1" / "groups"
  private val groupKey = path[String]("key").description("Group key")

  val list: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base)
      .out(jsonBody[List[GroupView]])
      .description(
        "The access groups of the tenant, sorted by key, e.g. to pick the allowed groups of a document"
      )
      .tag("Groups")
      .serverLogic(p => _ => inTransaction(p)(ctx => ctx.groups.list.map(_.map(view))))

  val create: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(base)
      .in(jsonBody[CreateGroup])
      .out(statusCode(StatusCode.Created).and(jsonBody[GroupView]))
      .description(
        "Creates an access group (tenant administrators). The key must be lowercase letters, digits and hyphens " +
          "(422); a taken key is a conflict (409). Groups are not deleted, since versions keep naming them."
      )
      .tag("Groups")
      .serverLogic(p =>
        req =>
          inTransaction(p) { ctx =>
            val key  = req.key.trim
            val name = req.name.trim
            for
              _ <- ctx.administer(p)
              _ <- ZIO.unless(Groups.isValidKey(key))(
                ZIO.fail(invalid(s"the key '$key' must match ${Groups.KeyPattern}"))
              )
              _ <- ZIO.unless(name.nonEmpty && name.length <= 200)(
                ZIO.fail(invalid("the name must have 1 to 200 characters"))
              )
              created <- ctx.groups.create(key, name, p.userId).mapError(Failure.Database(_))
              _       <- ZIO.unless(created)(ZIO.fail(Failure.Conflict(s"the group $key exists already")))
              _       <- ctx
                .record(p.userId, "group.created", Json.Obj("key" -> Json.Str(key), "name" -> Json.Str(name)))
            yield GroupView(key, name)
          }
      )

  val rename: ZServerEndpoint[Env, Any] =
    SecuredApi.base.put
      .in(base / groupKey)
      .in(jsonBody[RenameGroup])
      .out(jsonBody[GroupView])
      .description(
        "Changes the display name of an access group (tenant administrators); the key stays, since versions " +
          "name it. 404 for an unknown group, 422 for a blank name."
      )
      .tag("Groups")
      .serverLogic(p =>
        (key, req) =>
          inTransaction(p) { ctx =>
            val name = req.name.trim
            for
              _ <- ctx.administer(p)
              _ <- ZIO.unless(name.nonEmpty && name.length <= 200)(
                ZIO.fail(invalid("the name must have 1 to 200 characters"))
              )
              before <- ctx.existing(key)
              _      <- ZIO.when(before.name != name)(
                ctx.groups.rename(key, name).mapError(Failure.Database(_)) *>
                  ctx.record(
                    p.userId,
                    "group.renamed",
                    Json.Obj(
                      "key"      -> Json.Str(key),
                      "name"     -> Json.Str(name),
                      "previous" -> Json.Str(before.name)
                    )
                  )
              )
            yield GroupView(key, name)
          }
      )

  val members: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base / groupKey / "members")
      .out(jsonBody[List[GroupMemberView]])
      .description("Members of an access group, current ones first, then ended ones (tenant administrators)")
      .tag("Groups")
      .serverLogic(p =>
        key =>
          inTransaction(p) { ctx =>
            for
              _     <- ctx.administer(p)
              _     <- ctx.existing(key)
              found <- ctx.groups.members(key).mapError(Failure.Database(_))
            yield found.map(memberView)
          }
      )

  val addMember: ZServerEndpoint[Env, Any] =
    SecuredApi.base.put
      .in(base / groupKey / "members" / path[String]("userId").description("ZITADEL user id"))
      .out(jsonBody[List[GroupMemberView]])
      .description(
        "Adds a user to an access group (tenant administrators); idempotent. The user sees the confidential " +
          "documents of the group from the next request on; open and waiting review and approval tasks of " +
          "confidential versions naming the group get their participants resolved again in the same " +
          "transaction (DMS-105). Returns the members."
      )
      .tag("Groups")
      .serverLogic(p =>
        (key, user) =>
          inTransaction(p) { ctx =>
            val member = UserId(user.trim)
            for
              _     <- ctx.administer(p)
              _     <- ZIO.when(member.value.isEmpty)(ZIO.fail(invalid("a user id is required")))
              _     <- ctx.existing(key)
              added <- ctx.groups.addMember(key, member, p.userId).mapError(Failure.Database(_))
              _     <- ZIO.when(added)(
                ctx.record(p.userId, "group.member-added", membership(key, member)) *> ctx.reresolve(key, p)
              )
              found <- ctx.groups.members(key).mapError(Failure.Database(_))
            yield found.map(memberView)
          }
      )

  val removeMember: ZServerEndpoint[Env, Any] =
    SecuredApi.base.delete
      .in(base / groupKey / "members" / path[String]("userId").description("ZITADEL user id"))
      .out(jsonBody[List[GroupMemberView]])
      .description(
        "Ends the membership of a user in an access group (tenant administrators); the row is kept for the " +
          "history. Open and waiting review and approval tasks of confidential versions naming the group get " +
          "their participants resolved again in the same transaction (DMS-105). 404 if the user is no current " +
          "member. Returns the members."
      )
      .tag("Groups")
      .serverLogic(p =>
        (key, user) =>
          inTransaction(p) { ctx =>
            val member = UserId(user.trim)
            for
              _       <- ctx.administer(p)
              _       <- ctx.existing(key)
              now     <- Clock.instant
              removed <- ctx.groups.removeMember(key, member, p.userId, now).mapError(Failure.Database(_))
              _       <- ZIO.unless(removed)(
                ZIO.fail(Failure.NotFound(s"${member.value} is no member of the group $key"))
              )
              _     <- ctx.record(p.userId, "group.member-removed", membership(key, member))
              _     <- ctx.reresolve(key, p)
              found <- ctx.groups.members(key).mapError(Failure.Database(_))
            yield found.map(memberView)
          }
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] =
    List(list, create, rename, members, addMember, removeMember)

  private enum Failure:
    case Invalid(message: String)
    case NotFound(message: String)
    case Conflict(message: String)
    case Database(error: DbError)
    case Workflow(error: WorkflowError)

  private def invalid(message: String): Failure = Failure.Invalid(message)

  private final class Context(
      authz: Authorizer,
      audit: AuditTrail,
      workflow: ApprovalWorkflow,
      subject: dms.authz.Subject
  ):
    val groups: Groups = authz.groups

    /** Resolves the participants of open tasks of confidential versions naming the group again (DMS-105). */
    def reresolve(key: String, p: Principal): IO[Failure, Unit] =
      workflow.resolveParticipantsOfGroup(key, p.userId).mapError(Failure.Workflow(_)).unit

    /** Managing groups is tenant configuration. */
    def administer(p: Principal): IO[AccessDenied, Unit] =
      authz
        .check(
          subject,
          Action.Configure,
          Resource(p.tenantId, configurationArea = Some(ConfigurationArea.Groups))
        )
        .unit

    def existing(key: String): IO[Failure, AccessGroup] =
      groups
        .get(key)
        .mapError(Failure.Database(_))
        .someOrFail(Failure.NotFound(s"the group $key does not exist"))

    def record(actor: UserId, action: String, details: Json.Obj): IO[Failure, Unit] =
      audit
        .record(
          AuditEvent(
            Actor(actor.value, ActorKind.Person),
            action,
            AuditObject("access-group"),
            Channel.Api,
            Jsonb(details.toJson)
          )
        )
        .mapError(Failure.Database(_))
        .unit

  private def membership(key: String, user: UserId): Json.Obj =
    Json.Obj("key" -> Json.Str(key), "userId" -> Json.Str(user.value))

  private def inTransaction[A](principal: Principal)(
      f: Context => ZIO[Any, Failure | AccessDenied | DbError, A]
  ): ZIO[Db & Authorizer & AuditTrail & ApprovalWorkflow, (StatusCode, ApiError), A] =
    (for
      db       <- ZIO.service[Db]
      authz    <- ZIO.service[Authorizer]
      audit    <- ZIO.service[AuditTrail]
      workflow <- ZIO.service[ApprovalWorkflow]
      result   <- db.transact(principal.tenantId)(
        authz.subject(principal).flatMap(subject => f(Context(authz, audit, workflow, subject)))
      )
    yield result).mapError {
      case Failure.Invalid(m)  => (StatusCode.UnprocessableEntity, ApiError("invalid", m))
      case Failure.NotFound(m) => (StatusCode.NotFound, ApiError("not_found", m))
      case Failure.Conflict(m) => (StatusCode.Conflict, ApiError("conflict", m))
      case Failure.Database(e) => DocumentApi.toResponse(e)
      case Failure.Workflow(e) => WorkflowApi.toResponse(e)
      case e: AccessDenied     => DocumentApi.toResponse(e)
      case e: DbError          => DocumentApi.toResponse(e)
    }

  private def view(g: AccessGroup): GroupView = GroupView(g.key, g.name)

  private def memberView(m: GroupMembership): GroupMemberView =
    GroupMemberView(m.user.value, m.addedBy.value, m.addedAt, m.removedBy.map(_.value), m.removedAt)
