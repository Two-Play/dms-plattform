package dms.workflow.core

import com.augustnagro.magnum.DbTx
import dms.audit.*
import dms.db.queue.Outbox
import dms.db.{Db, DbError, Jsonb}
import dms.kernel.UserId
import dms.workflow.*
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.Instant
import java.util.UUID

/** Deputies of workflow participants (business concept 6.1, US-10.1, DMS-49, ADR 0028): time-limited
  * deputyships between persons with the same authority. While one is active, [[ApprovalWorkflow]] offers the
  * principal's open tasks to the deputy as well and records the deputy's votes "on behalf of" the principal.
  *
  * Every method must run inside `Db.transact(tenant)`. Authorization of the caller (own deputyships, tenant
  * administrators for others) is the caller's job.
  */
final class Deputyships(audit: AuditTrail, directory: ParticipantDirectory):
  import Deputyships.*

  /** Creates a deputyship after checking the period and that the deputy holds the principal's roles with a
    * covering scope ([[Rules.checkDeputyship]]). A second deputyship for the same pair and an overlapping
    * period is a conflict. Recorded as `deputyship.created`; outbox event `workflow.deputyship-created` to
    * principal and deputy.
    */
  def create(request: NewDeputyship, actor: UserId): IO[WorkflowError, Deputyship] =
    for
      now            <- Clock.instant
      principalRoles <- directory.heldRoles(request.principal)
      deputyRoles    <- directory.heldRoles(request.deputy)
      roles          <- ZIO
        .fromEither(Rules.checkDeputyship(request, principalRoles, deputyRoles, now))
        .mapError(WorkflowError.Invalid(_))
      from = request.validFrom.getOrElse(now)
      overlapping <- sql(DeputyStore.overlaps(request.principal, request.deputy, from, request.validUntil))
      _           <- ZIO.when(overlapping)(
        ZIO.fail(
          WorkflowError.Conflict(
            s"${request.deputy.value} is already deputy of ${request.principal.value} in this period"
          )
        )
      )
      deputyship = Deputyship(
        UUID.randomUUID(),
        request.principal,
        request.deputy,
        from,
        request.validUntil,
        roles,
        request.documentClass.map(_.trim),
        request.reason.map(_.trim).filter(_.nonEmpty),
        actor,
        now,
        None,
        None
      )
      _ <- sql(DeputyStore.insert(deputyship)).mapError {
        case WorkflowError.Database(DbError.Other("23503", _)) =>
          WorkflowError.Invalid(List(s"document class ${request.documentClass.getOrElse("")} does not exist"))
        case other => other
      }
      _ <- publish(deputyship, "workflow.deputyship-created")
      _ <- record(actor, "deputyship.created", deputyship, deputyship.reason)
    yield deputyship

  /** Ends a deputyship now. Unfinished tasks are the principal's alone again from then on. Recorded as
    * `deputyship.ended`; outbox event `workflow.deputyship-ended`.
    */
  def end(id: UUID, actor: UserId): IO[WorkflowError, Deputyship] =
    for
      found <- get(id)
      now   <- Clock.instant
      _     <- ZIO.when(found.status(now) == DeputyshipStatus.Ended)(
        ZIO.fail(WorkflowError.Conflict(s"deputyship $id has already ended"))
      )
      _     <- sql(DeputyStore.end(id, now, actor))
      ended <- get(id)
      _     <- publish(ended, "workflow.deputyship-ended")
      _     <- record(actor, "deputyship.ended", ended, None)
    yield ended

  /** A deputyship, locked for the rest of the transaction. */
  def get(id: UUID): IO[WorkflowError, Deputyship] =
    sql(DeputyStore.lock(id)).someOrFail(WorkflowError.NotFound(s"deputyship $id does not exist"))

  /** Deputyships in which `user` is principal or deputy, latest period first. */
  def involving(user: UserId): IO[WorkflowError, List[Deputyship]] = sql(DeputyStore.involving(user))

  /** Deputyships of `principal`, latest period first. */
  def ofPrincipal(principal: UserId): IO[WorkflowError, List[Deputyship]] =
    sql(DeputyStore.ofPrincipal(principal))

  /** All deputyships of the tenant, latest period first. */
  def all: IO[WorkflowError, List[Deputyship]] = sql(DeputyStore.all)

  /** Notices due at `now` (DMS-99): scheduled periods that have begun and periods that ran out. */
  def dueNotices(now: Instant): IO[WorkflowError, List[(UUID, DeputyshipNotice)]] =
    sql {
      DeputyStore.startedWithoutNotice(now).map(_ -> DeputyshipNotice.Started) ++
        DeputyStore.endedByPeriodWithoutNotice(now).map(_ -> DeputyshipNotice.EndedByPeriod)
    }

  /** Publishes the notice for a deputyship once: records it in `dms.deputyship_notice` and writes the outbox
    * event (`workflow.deputyship-started` or `workflow.deputyship-ended-by-period`) to principal and deputy.
    * `false` if the notice was published already or is not due (any more) at `now`.
    */
  def notice(id: UUID, kind: DeputyshipNotice, now: Instant): IO[WorkflowError, Boolean] =
    for
      d <- get(id)
      due = kind match
        case DeputyshipNotice.Started       => d.isActiveAt(now)
        case DeputyshipNotice.EndedByPeriod => d.endedAt.isEmpty && !now.isBefore(d.validUntil)
      published <-
        if !due then ZIO.succeed(false)
        else
          sql(DeputyStore.insertNotice(id, kind.key))
            .tap(inserted => ZIO.when(inserted)(publish(d, kind.eventType)))
    yield published

  private def publish(d: Deputyship, eventType: String): IO[WorkflowError, Unit] =
    Outbox
      .publish(
        "workflow",
        d.id,
        eventType,
        Jsonb(
          json(d)
            .merge(Json.Obj("recipients" -> Json.Arr(Json.Str(d.principal.value), Json.Str(d.deputy.value))))
            .toJson
        )
      )
      .mapError(WorkflowError.Database(_))
      .unit

  private def record(
      actor: UserId,
      action: String,
      d: Deputyship,
      reason: Option[String]
  ): IO[WorkflowError, Unit] =
    audit
      .record(
        AuditEvent(
          Actor(actor.value, ActorKind.Person),
          action,
          AuditObject("deputyship", Some(d.id)),
          Channel.Api,
          Jsonb(json(d).toJson),
          reason
        )
      )
      .mapError(WorkflowError.Database(_))
      .unit

/** Notices published by [[DeputyshipJobs]] when a period begins or runs out (DMS-99). */
enum DeputyshipNotice(val key: String, val eventType: String):
  case Started       extends DeputyshipNotice("started", "workflow.deputyship-started")
  case EndedByPeriod extends DeputyshipNotice("ended-by-period", "workflow.deputyship-ended-by-period")

object Deputyships:
  val layer: URLayer[AuditTrail & ParticipantDirectory, Deputyships] =
    ZLayer.fromFunction(Deputyships(_, _))

  private def sql[A](f: DbTx ?=> A): IO[WorkflowError, A] = Db.sql(f).mapError(WorkflowError.Database(_))

  private def json(d: Deputyship): Json.Obj =
    Json.Obj(
      "deputyshipId"  -> Json.Str(d.id.toString),
      "principal"     -> Json.Str(d.principal.value),
      "deputy"        -> Json.Str(d.deputy.value),
      "validFrom"     -> Json.Str(d.validFrom.toString),
      "validUntil"    -> Json.Str(d.validUntil.toString),
      "roles"         -> Json.Arr(d.roles.map(Json.Str(_))*),
      "documentClass" -> d.documentClass.fold(Json.Null)(Json.Str(_)),
      "endedAt"       -> d.endedAt.fold(Json.Null)(t => Json.Str(t.toString))
    )
