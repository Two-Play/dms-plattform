package dms.app

import com.augustnagro.magnum.sql
import dms.audit.*
import dms.db.queue.Outbox
import dms.db.{Db, DbError, Jsonb}
import dms.kernel.{DocumentId, UserId}
import dms.workflow.core.{ApprovalWorkflow, DeadlineAudience, DeadlineStage, WorkflowError}
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.{Instant, LocalDate, OffsetDateTime, ZoneOffset}
import java.util.UUID

/** Who a notice from the cockpit reaches (ADR 0077): the people of the review deadlines (ADR 0057) under the
  * tenant's deadline policy. `sender` is not an escalation target: an officer who is the only one in scope
  * reaches the tenant administrators, as a deadline does without any officer.
  */
trait ComplianceNoticeAudience:
  def of(
      document: DocumentId,
      version: UUID,
      escalate: Boolean,
      sender: UserId
  ): IO[WorkflowError, DeadlineAudience]

object ComplianceNoticeAudience:
  val layer: URLayer[ApprovalWorkflow, ComplianceNoticeAudience] =
    ZLayer.fromFunction((workflow: ApprovalWorkflow) =>
      new ComplianceNoticeAudience:
        def of(document: DocumentId, version: UUID, escalate: Boolean, sender: UserId) =
          val stage = if escalate then DeadlineStage.Escalated else DeadlineStage.Overdue
          workflow.defaultDeadlines.flatMap(
            workflow.ownerDeadlineAudience(document, version, stage, _, excluding = Set(sender))
          )
    )

/** Notices a compliance officer sends from the cockpit about an overdue review (DMS-108, ADR 0077).
  *
  * One notice per version and kind within [[Interval]], for all officers together, so neither a repeated
  * click nor a second officer reaches the same people again. Everything runs in the caller's transaction: the
  * row in `dms.compliance_notice`, the audit entry with the stated reason and the outbox event the
  * notifications module delivers. The reason stays in the audit trail; recipients get the fixed text of the
  * kind.
  */
object ComplianceNotices:

  val Reminder   = "reminder"
  val Escalation = "escalation"
  val Kinds      = Set(Reminder, Escalation)

  /** As for manual acknowledgement reminders (ADR 0048). */
  val Interval: java.time.Duration = java.time.Duration.ofHours(24)

  /** The outbox event and the audit action. */
  val Event  = "document.compliance-notice"
  val Action = "document.compliance-notice-sent"

  /** The version and what it is about. */
  final case class Target(document: DocumentId, version: UUID, label: String, dueOn: Option[LocalDate])

  /** When the last notice of each kind went out, by version. */
  def latest(versions: Set[UUID]): IO[DbError, Map[(UUID, String), Instant]] =
    if versions.isEmpty then ZIO.succeed(Map.empty)
    else
      Db.sql(
        sql"""select version_id, kind, max(sent_at) from dms.compliance_notice
              where version_id = any(${versions.toArray}) group by version_id, kind"""
          .query[(UUID, String, OffsetDateTime)]
          .run()
          .map((version, kind, at) => (version, kind) -> at.toInstant)
          .toMap
      )

  /** When the next notice of `kind` is possible, if it is not possible now. */
  def next(last: Option[Instant], now: Instant): Option[Instant] =
    last.map(_.plus(Interval)).filter(now.isBefore)

  /** Everyone a notice reaches, without the sender. */
  def people(audience: DeadlineAudience, sender: UserId): List[UserId] =
    (audience.recipients ++ audience.deputies.map(_.deputy) ++ audience.escalatedTo).distinct
      .filterNot(_ == sender)

  /** Sends the notice unless one of its kind went out within [[Interval]]; then `Left` says when it can.
    * Serialized per version and kind, so two clicks at once send one. Must run inside `Db.transact(tenant)`.
    */
  def send(
      target: Target,
      kind: String,
      audience: DeadlineAudience,
      sender: UserId,
      reason: String
  ): ZIO[AuditTrail, DbError, Either[Instant, Instant]] =
    val key = s"compliance-notice:${target.version}:$kind"
    for
      now <- Db.sql {
        val _ =
          sql"select 1 from (select pg_advisory_xact_lock(hashtextextended($key, 0))) l".query[Int].run()
        sql"select now()".query[OffsetDateTime].run().head.toInstant
      }
      last <- latest(Set(target.version)).map(_.get((target.version, kind)))
      sent <- next(last, now) match
        case Some(at) => ZIO.left(at)
        case None     => record(target, kind, audience, sender, reason, now).as(Right(now))
    yield sent

  private def record(
      target: Target,
      kind: String,
      audience: DeadlineAudience,
      sender: UserId,
      reason: String,
      now: Instant
  ): ZIO[AuditTrail, DbError, Unit] =
    val reached  = people(audience, sender)
    val at       = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
    val id       = UUID.randomUUID()
    val users    = (list: List[UserId]) => Json.Arr(list.filterNot(_ == sender).map(u => Json.Str(u.value))*)
    val document = target.document.value
    val event    = Json.Obj(
      "documentId" -> Json.Str(document.toString),
      "versionId"  -> Json.Str(target.version.toString),
      "version"    -> Json.Str(target.label),
      "kind"       -> Json.Str(kind),
      "dueOn"      -> target.dueOn.fold[Json](Json.Null)(d => Json.Str(d.toString)),
      "recipients" -> users(audience.recipients),
      "deputies"   -> Json.Arr(
        audience.deputies
          .filterNot(_.deputy == sender)
          .map(d =>
            Json.Obj("deputy" -> Json.Str(d.deputy.value), "principal" -> Json.Str(d.principal.value))
          )*
      ),
      "escalatedTo" -> users(audience.escalatedTo)
    )
    for
      _ <- Db.sql(
        sql"""insert into dms.compliance_notice
                (id, tenant_id, document_id, version_id, kind, sent_at, sent_by, recipients)
              values ($id, dms.current_tenant(), $document, ${target.version}, $kind, $at, ${sender.value},
                      ${reached.size})""".update.run()
      )
      _ <- ZIO.serviceWithZIO[AuditTrail](
        _.record(
          AuditEvent(
            Actor(sender.value, ActorKind.Person),
            Action,
            AuditObject("document", Some(document), Some(target.label)),
            Channel.Api,
            Jsonb(
              Json
                .Obj(
                  "versionId"  -> Json.Str(target.version.toString),
                  "kind"       -> Json.Str(kind),
                  "recipients" -> Json.Num(reached.size)
                )
                .toJson
            ),
            Some(reason)
          )
        )
      )
      _ <- Outbox.publish("document", document, Event, Jsonb(event.toJson))
    yield ()
