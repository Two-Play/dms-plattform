package dms.app

import dms.authz.{AccessDenied, Action, Authorizer, ConfigurationArea, Resource, Subject}
import dms.db.Db
import dms.identity.{Authenticator, Principal}
import dms.kernel.UserId
import dms.workflow.{Deputyship, NewDeputyship}
import dms.workflow.core.{Deputyships, WorkflowError}
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.time.Instant
import java.util.UUID

final case class CreateDeputyship(
    /** Whose deputy; default: the caller. Tenant administrators may set deputies for others. */
    principal: Option[String],
    /** User id of the deputy; must hold the principal's roles with a covering scope. */
    deputy: String,
    /** Start of the period; default: now. */
    validFrom: Option[Instant],
    /** End of the period (required, at most 366 days after the start). */
    validUntil: Instant,
    /** Lane roles covered (Author, Owner, Reviewer, Approver); default: all of them the principal holds. */
    roles: Option[List[String]],
    /** Restricts the deputyship to documents of this class and its subclasses. */
    documentClass: Option[String],
    /** e.g. "Vacation". */
    reason: Option[String]
) derives JsonCodec,
      Schema

final case class DeputyshipView(
    id: UUID,
    principal: String,
    deputy: String,
    validFrom: Instant,
    validUntil: Instant,
    roles: List[String],
    documentClass: Option[String],
    reason: Option[String],
    createdBy: String,
    createdAt: Instant,
    /** Set when the deputyship was ended early. */
    endedAt: Option[Instant],
    endedBy: Option[String],
    /** Scheduled, Active or Ended. */
    status: String
) derives JsonCodec,
      Schema

/** Deputies of workflow participants (business concept 6.1, US-10.1, DMS-49, ADR 0028).
  *
  * Everyone signed in manages their own deputyships (as principal); the deputy may also end one. Tenant
  * administrators (`Configure` on `Workflows`) manage the deputyships of others and list all of them.
  */
object DeputyshipApi:
  type Env = Authenticator & Db & Authorizer & Deputyships

  private val base = "api" / "v1" / "deputyships"

  val create: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(base)
      .in(jsonBody[CreateDeputyship])
      .out(statusCode(StatusCode.Created).and(jsonBody[DeputyshipView]))
      .description(
        "Sets a deputy for a limited period (e.g. a vacation): during the period the deputy may complete the " +
          "principal's open review and approval tasks, recorded as \"deputy on behalf of principal\". The " +
          "deputy must hold the principal's roles with a scope covering the principal's (422 otherwise); the " +
          "same pair with an overlapping period is a conflict (409)."
      )
      .tag("Workflow")
      .serverLogic(p =>
        req =>
          inTransaction(p) { ctx =>
            val principal = req.principal.map(_.trim).filter(_.nonEmpty).map(UserId(_)).getOrElse(p.userId)
            for
              _       <- ZIO.unless(principal == p.userId)(ctx.administer(p))
              created <- ctx.deputyships.create(
                NewDeputyship(
                  principal,
                  UserId(req.deputy.trim),
                  req.validFrom,
                  req.validUntil,
                  req.roles.getOrElse(Nil),
                  req.documentClass,
                  req.reason
                ),
                p.userId
              )
              now <- Clock.instant
            yield view(created, now)
          }
      )

  val list: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base)
      .in(query[Option[String]]("principal").description("Deputyships of this principal (others: admins)"))
      .in(query[Option[Boolean]]("all").description("All deputyships of the tenant (admins)"))
      .out(jsonBody[List[DeputyshipView]])
      .description(
        "The caller's deputyships as principal or deputy, latest period first; tenant administrators may " +
          "list those of a principal or all of them"
      )
      .tag("Workflow")
      .serverLogic(p =>
        (principal, all) =>
          inTransaction(p) { ctx =>
            val of = principal.map(_.trim).filter(_.nonEmpty).map(UserId(_))
            for
              found <- (all.contains(true), of) match
                case (true, _)                         => ctx.administer(p) *> ctx.deputyships.all
                case (false, Some(u)) if u != p.userId => ctx.administer(p) *> ctx.deputyships.ofPrincipal(u)
                case (false, Some(u))                  => ctx.deputyships.ofPrincipal(u)
                case (false, None)                     => ctx.deputyships.involving(p.userId)
              now <- Clock.instant
            yield found.map(view(_, now))
          }
      )

  val end: ZServerEndpoint[Env, Any] =
    SecuredApi.base.delete
      .in(base / path[UUID]("id").description("Deputyship id"))
      .out(jsonBody[DeputyshipView])
      .description(
        "Ends a deputyship now (kept for the history). Unfinished tasks are the principal's alone again. " +
          "For the principal, the deputy and tenant administrators; an ended deputyship is a conflict (409)."
      )
      .tag("Workflow")
      .serverLogic(p =>
        id =>
          inTransaction(p) { ctx =>
            for
              found <- ctx.deputyships.get(id)
              _     <- ZIO.unless(found.principal == p.userId || found.deputy == p.userId)(ctx.administer(p))
              ended <- ctx.deputyships.end(id, p.userId)
              now   <- Clock.instant
            yield view(ended, now)
          }
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(create, list, end)

  private final class Context(val deputyships: Deputyships, authz: Authorizer, subject: Subject):
    /** Managing the deputyships of others is workflow configuration (tenant administrators). */
    def administer(p: Principal): IO[AccessDenied, Unit] =
      authz
        .check(
          subject,
          Action.Configure,
          Resource(p.tenantId, configurationArea = Some(ConfigurationArea.Workflows))
        )
        .unit

  private def inTransaction[A](principal: Principal)(
      f: Context => ZIO[Any, WorkflowError | AccessDenied, A]
  ): ZIO[Db & Authorizer & Deputyships, (StatusCode, ApiError), A] =
    (for
      db          <- ZIO.service[Db]
      authz       <- ZIO.service[Authorizer]
      deputyships <- ZIO.service[Deputyships]
      result      <- db.transact(principal.tenantId)(
        authz.subject(principal).flatMap(subject => f(Context(deputyships, authz, subject)))
      )
    yield result).mapError(WorkflowApi.toResponse)

  def view(d: Deputyship, now: Instant): DeputyshipView =
    DeputyshipView(
      d.id,
      d.principal.value,
      d.deputy.value,
      d.validFrom,
      d.validUntil,
      d.roles,
      d.documentClass,
      d.reason,
      d.createdBy.value,
      d.createdAt,
      d.endedAt,
      d.endedBy.map(_.value),
      d.status(now).toString
    )
