package dms.documents.core

import com.augustnagro.magnum.*
import dms.audit.*
import dms.db.queue.Outbox
import dms.db.{Db, DbError, Jsonb}
import dms.documents.*
import dms.kernel.{DocumentId, UserId}
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.temporal.ChronoUnit
import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.UUID

enum AcknowledgementError:
  /** The version does not ask for acknowledgement: it is not valid or does not require it. */
  case NotRequested(message: String)

  /** The reader did not open the version before acknowledging it. */
  case NotOpened(message: String)

  /** The reader acknowledged the version before. */
  case AlreadyAcknowledged(message: String)

  /** A manual reminder of the same version was sent less than [[AcknowledgementReminders.ManualInterval]]
    * ago; the next one is possible at `nextAt`.
    */
  case ReminderTooSoon(message: String, nextAt: Instant)

  /** The acknowledgement settings violate a rule; `problems` are meant for display. */
  case Invalid(problems: List[String])

  case Database(error: DbError)

  def describe: String = this match
    case NotRequested(m)        => m
    case NotOpened(m)           => m
    case AlreadyAcknowledged(m) => m
    case ReminderTooSoon(m, _)  => m
    case Invalid(problems)      => problems.mkString("; ")
    case Database(e)            => s"database error: $e"

/** What a reader did with a version: when they opened it first and their acknowledgement, if any. */
final case class AcknowledgementStatus(
    versionId: UUID,
    openedAt: Option[Instant],
    acknowledgement: Option[Acknowledgement]
)

/** Acknowledgement settings of a class (`classId` set) or the tenant default, with their last change. */
final case class StoredAcknowledgementSettings(
    classId: Option[UUID],
    settings: AcknowledgementSettings,
    updatedAt: Instant,
    updatedBy: UserId
)

/** A version whose open acknowledgements are reminded of. */
final case class ReminderTarget(documentId: DocumentId, versionId: UUID, versionLabel: String, title: String)

/** Acknowledgement of valid versions by their readers (business concept 6.3, US-06.2, ADR 0027).
  *
  * A reader first opens the version ([[markOpened]], recorded once), then acknowledges it ([[acknowledge]],
  * once). Both are insert-only (migration V15). Only valid versions that require acknowledgement ask for it
  * ([[Acknowledgements.requested]]); when a version is superseded, its open acknowledgements end and its
  * successor asks on its own if it requires acknowledgement.
  *
  * Every method must run inside `Db.transact(tenant)`. Who must acknowledge (the readers of the version) is
  * decided by the caller with the authorization policy; the service records what the caller confirmed. The
  * first opening and every acknowledgement are recorded in the audit trail, acknowledgements also in the
  * outbox (`document.acknowledged`), in the same transaction.
  */
final class AcknowledgementService(audit: AuditTrail):
  import AcknowledgementService.*

  /** Records that `reader` opened `version`, the first time only. Returns when they opened it first, or
    * `None` without recording anything if the version does not ask for acknowledgement.
    */
  def markOpened(version: DocumentVersion, reader: UserId): IO[AcknowledgementError, Option[Instant]] =
    if !Acknowledgements.requested(version) then ZIO.none
    else
      for
        now   <- databaseNow
        first <- sql(
          sql"""insert into dms.document_version_opened (tenant_id, version_id, user_id, opened_at)
                values (dms.current_tenant(), ${version.id}, ${reader.value}, ${utc(now)})
                on conflict (version_id, user_id) do nothing
                returning opened_at""".query[OffsetDateTime].run().headOption.map(_.toInstant)
        )
        openedAt <- first match
          case Some(at) =>
            record(reader, "document.opened", version, Json.Obj("openedAt" -> Json.Str(at.toString))).as(at)
          case None => sql(openedAt(version.id, reader)).someOrElse(now)
      yield Some(openedAt)

  /** Records that `reader` acknowledges `version`. The version must still be valid and require
    * acknowledgement (checked under a lock against concurrent supersession), the reader must have opened it
    * before, and may acknowledge it only once.
    */
  def acknowledge(version: DocumentVersion, reader: UserId): IO[AcknowledgementError, Acknowledgement] =
    for
      current <- sql(
        sql"""select status, requires_acknowledgement from dms.document_version where id = ${version.id}
              for share""".query[(String, Boolean)].run().headOption
      )
      _ <- ZIO.unless(current.exists((status, requires) => valid(status) && requires))(
        ZIO.fail(
          AcknowledgementError.NotRequested(
            s"version ${version.label} of document ${version.documentId.value} does not ask for acknowledgement"
          )
        )
      )
      earlier <- sql(acknowledgementOf(version.id, reader))
      _       <- ZIO.foreachDiscard(earlier)(a =>
        ZIO.fail(
          AcknowledgementError.AlreadyAcknowledged(
            s"version ${a.versionLabel} was acknowledged at ${a.acknowledgedAt}"
          )
        )
      )
      opened <- sql(openedAt(version.id, reader)).someOrFail(
        AcknowledgementError.NotOpened(s"version ${version.label} has to be opened before it is acknowledged")
      )
      now <- databaseNow
      acknowledgement = Acknowledgement(
        UUID.randomUUID(),
        version.documentId,
        version.id,
        version.label,
        reader,
        opened,
        if now.isBefore(opened) then opened else now
      )
      _ <- sql(insert(acknowledgement)).mapError {
        case AcknowledgementError.Database(DbError.UniqueViolation(_, _)) =>
          AcknowledgementError.AlreadyAcknowledged(s"version ${version.label} was acknowledged already")
        case other => other
      }
      _ <- record(
        reader,
        "document.acknowledged",
        version,
        Json.Obj(
          "acknowledgementId" -> Json.Str(acknowledgement.id.toString),
          "openedAt"          -> Json.Str(acknowledgement.openedAt.toString),
          "acknowledgedAt"    -> Json.Str(acknowledgement.acknowledgedAt.toString)
        )
      )
      _ <- Outbox
        .publish(
          "document",
          version.documentId.value,
          "document.acknowledged",
          Jsonb(
            Json
              .Obj(
                "documentId"     -> Json.Str(version.documentId.value.toString),
                "versionId"      -> Json.Str(version.id.toString),
                "version"        -> Json.Str(acknowledgement.versionLabel),
                "userId"         -> Json.Str(reader.value),
                "acknowledgedAt" -> Json.Str(acknowledgement.acknowledgedAt.toString)
              )
              .toJson
          )
        )
        .mapError(AcknowledgementError.Database(_))
    yield acknowledgement

  /** When `reader` opened the version first and their acknowledgement of it. */
  def status(versionId: UUID, reader: UserId): IO[AcknowledgementError, AcknowledgementStatus] =
    sql(AcknowledgementStatus(versionId, openedAt(versionId, reader), acknowledgementOf(versionId, reader)))

  /** The valid versions of the tenant that ask for acknowledgement and that `reader` has not acknowledged,
    * the most recently published first, with their document. Whether the reader is among their readers is the
    * caller's decision.
    */
  def pending(reader: UserId): IO[AcknowledgementError, List[(DocumentId, UUID)]] =
    sql(
      sql"""select v.document_id, v.id
            from dms.document_version v
            where v.status in ('Published', 'ReviewDue')
              and v.requires_acknowledgement
              and not exists (select 1 from dms.document_acknowledgement a
                              where a.version_id = v.id and a.user_id = ${reader.value})
            order by v.published_at desc nulls last, v.id"""
        .query[(UUID, UUID)]
        .run()
        .toList
        .map((d, v) => (DocumentId(d), v))
    )

  /** When `reader` opened each valid version first; versions they did not open are missing. */
  def openedValid(reader: UserId): IO[AcknowledgementError, Map[UUID, Instant]] =
    sql(
      sql"""select o.version_id, o.opened_at
            from dms.document_version_opened o
            join dms.document_version v on v.id = o.version_id
            where o.user_id = ${reader.value} and v.status in ('Published', 'ReviewDue')"""
        .query[(UUID, OffsetDateTime)]
        .run()
        .map((v, at) => v -> at.toInstant)
        .toMap
    )

  /** Who acknowledged each valid version that requires acknowledgement (compliance cockpit, DMS-59); versions
    * nobody acknowledged are missing. Acknowledgements of superseded versions do not count for their
    * successor.
    */
  def acknowledgersOfValid: IO[AcknowledgementError, Map[UUID, Set[UserId]]] =
    sql(
      sql"""select a.version_id, a.user_id
            from dms.document_acknowledgement a
            join dms.document_version v on v.id = a.version_id
            where v.status in ('Published', 'ReviewDue') and v.requires_acknowledgement"""
        .query[(UUID, String)]
        .run()
        .toList
        .groupMap(_._1)(r => UserId(r._2))
        .view
        .mapValues(_.toSet)
        .toMap
    )

  /** Who acknowledged the version. */
  def acknowledgersOf(versionId: UUID): IO[AcknowledgementError, Set[UserId]] =
    sql(
      sql"select user_id from dms.document_acknowledgement where version_id = $versionId"
        .query[String]
        .run()
        .map(UserId(_))
        .toSet
    )

  /** The acknowledgements of `reader`, the latest first. */
  def acknowledgementsOf(reader: UserId): IO[AcknowledgementError, List[Acknowledgement]] =
    sql(
      sql"""select id, document_id, version_id, version_label, user_id, opened_at, acknowledged_at
            from dms.document_acknowledgement where user_id = ${reader.value}
            order by acknowledged_at desc, id""".query[Row].run().map(toAcknowledgement).toList
    )

  /** The acknowledgements of a version, the earliest first (evidence reports, DMS-57). */
  def acknowledgementsOfVersion(versionId: UUID): IO[AcknowledgementError, List[Acknowledgement]] =
    sql(
      sql"""select id, document_id, version_id, version_label, user_id, opened_at, acknowledged_at
            from dms.document_acknowledgement where version_id = $versionId
            order by acknowledged_at, id""".query[Row].run().map(toAcknowledgement).toList
    )

  /** The acknowledgement settings of the tenant (DMS-100, ADR 0048): the tenant default first, then those of
    * classes.
    */
  def settings: IO[AcknowledgementError, List[StoredAcknowledgementSettings]] =
    sql(
      sql"""select class_id, array_to_json(recipient_roles)::text, array_to_json(recipient_groups)::text,
                   array_to_json(recipient_units)::text, reminder_days, updated_at, updated_by
            from dms.acknowledgement_settings
            order by class_id nulls first""".query[SettingsRow].run().toList.map(toStored)
    )

  /** Replaces the acknowledgement settings of a class (`classId`) or the tenant default (`None`). Versions
    * published before keep the settings they were published with.
    */
  def changeSettings(
      classId: Option[UUID],
      settings: AcknowledgementSettings,
      actor: UserId
  ): IO[AcknowledgementError, StoredAcknowledgementSettings] =
    val problems = settings.problems
    for
      _ <- ZIO.unless(problems.isEmpty)(ZIO.fail(AcknowledgementError.Invalid(problems)))
      r = settings.recipients
      stored <- sql(
        sql"""insert into dms.acknowledgement_settings
                (tenant_id, class_id, recipient_roles, recipient_groups, recipient_units, reminder_days, updated_by)
              values (dms.current_tenant(), $classId, array(select jsonb_array_elements_text(${json(
            r.roles
          )})),
                      array(select jsonb_array_elements_text(${json(r.groups)})),
                      array(select jsonb_array_elements_text(${json(r.units)})), ${settings.reminderDays},
                      ${actor.value})
              on conflict (tenant_id, class_id) do update
                set recipient_roles = excluded.recipient_roles, recipient_groups = excluded.recipient_groups,
                    recipient_units = excluded.recipient_units, reminder_days = excluded.reminder_days,
                    updated_at = now(), updated_by = excluded.updated_by
              returning class_id, array_to_json(recipient_roles)::text, array_to_json(recipient_groups)::text,
                        array_to_json(recipient_units)::text, reminder_days, updated_at, updated_by"""
          .query[SettingsRow]
          .run()
          .head
      ).map(toStored)
      _ <- recordSettings(actor, "acknowledgement-settings.changed", classId, settingsJson(settings))
    yield stored

  /** Removes the settings of a class, which then inherits again, or the tenant default. `false` if there were
    * none.
    */
  def removeSettings(classId: Option[UUID], actor: UserId): IO[AcknowledgementError, Boolean] =
    for
      removed <- sql(
        sql"delete from dms.acknowledgement_settings where class_id is not distinct from $classId".update
          .run() > 0
      )
      _ <- ZIO.when(removed)(recordSettings(actor, "acknowledgement-settings.removed", classId, Json.Obj()))
    yield removed

  /** The settings that applied when each of the versions became valid; versions without a record (never valid
    * while requiring acknowledgement, or valid before migration V29 and superseded since) are missing.
    */
  def audiences(versionIds: Set[UUID]): IO[AcknowledgementError, Map[UUID, VersionAudience]] =
    if versionIds.isEmpty then ZIO.succeed(Map.empty)
    else
      sql(
        sql"""select version_id, source, source_class_id, array_to_json(recipient_roles)::text,
                     array_to_json(recipient_groups)::text, array_to_json(recipient_units)::text, reminder_days,
                     recorded_at
              from dms.version_acknowledgement_audience
              where version_id::text in (select jsonb_array_elements_text(${json(
            versionIds.map(_.toString)
          )}))"""
          .query[AudienceRow]
          .run()
          .map(toAudience)
          .map(a => a.versionId -> a)
          .toMap
      )

  /** The settings of every version that is valid and requires acknowledgement now. */
  def audiencesOfValid: IO[AcknowledgementError, Map[UUID, VersionAudience]] =
    sql(
      sql"""select a.version_id, a.source, a.source_class_id, array_to_json(a.recipient_roles)::text,
                   array_to_json(a.recipient_groups)::text, array_to_json(a.recipient_units)::text,
                   a.reminder_days, a.recorded_at
            from dms.version_acknowledgement_audience a
            join dms.document_version v on v.id = a.version_id
            where v.status in ('Published', 'ReviewDue') and v.requires_acknowledgement"""
        .query[AudienceRow]
        .run()
        .map(toAudience)
        .map(a => a.versionId -> a)
        .toMap
    )

  /** When the last manual reminder of each version was sent; versions never reminded manually are missing. */
  def lastManualReminders(versionIds: Set[UUID]): IO[AcknowledgementError, Map[UUID, Instant]] =
    if versionIds.isEmpty then ZIO.succeed(Map.empty)
    else
      sql(
        sql"""select version_id, max(sent_at) from dms.acknowledgement_reminder
              where kind = 'manual'
                and version_id::text in (select jsonb_array_elements_text(${json(
            versionIds.map(_.toString)
          )}))
              group by version_id"""
          .query[(UUID, OffsetDateTime)]
          .run()
          .map((v, at) => v -> at.toInstant)
          .toMap
      )

  /** Reminds `readers` (those who have not acknowledged the version; decided by the caller) of the version on
    * behalf of `actor`, at most once per [[AcknowledgementReminders.ManualInterval]] and version (checked
    * under a transaction lock on the version against concurrent requests). Recorded per reader, in the audit
    * trail (number of readers, no names) and in the outbox (`document.acknowledgement-reminder`, for the
    * notifications module). Returns when the reminder was sent.
    */
  def remindManually(
      target: ReminderTarget,
      readers: List[UserId],
      actor: UserId
  ): IO[AcknowledgementError, Instant] =
    for
      _ <- sql(
        sql"select 1 from (select pg_advisory_xact_lock(hashtextextended(${target.versionId.toString}, 48))) l"
          .query[Int]
          .run()
      )
      now  <- databaseNow
      last <- lastManualReminders(Set(target.versionId)).map(_.get(target.versionId))
      next = last.map(_.plus(AcknowledgementReminders.ManualInterval)).filter(now.isBefore)
      _ <- ZIO.foreachDiscard(next)(at =>
        ZIO.fail(
          AcknowledgementError.ReminderTooSoon(
            s"version ${target.versionLabel} was reminded of less than 24 hours ago; next reminder possible at $at",
            at
          )
        )
      )
      _ <- sql(
        readers.distinct.foreach(reader =>
          sql"""insert into dms.acknowledgement_reminder (tenant_id, version_id, user_id, kind, sent_at, sent_by)
                values (dms.current_tenant(), ${target.versionId}, ${reader.value}, 'manual', ${utc(now)},
                        ${actor.value})""".update.run()
        )
      )
      _ <- reminded(target, readers.distinct, "manual", None, actor)
    yield now

  /** Sends the automatic reminder `round` to those of `readers` who did not get it yet (idempotent: recorded
    * once per version, reader and round). Returns the readers reminded now.
    */
  def remindAutomatically(
      target: ReminderTarget,
      readers: List[UserId],
      round: Int
  ): IO[AcknowledgementError, List[UserId]] =
    for
      now   <- databaseNow
      fresh <- sql(
        readers.distinct.flatMap(reader =>
          sql"""insert into dms.acknowledgement_reminder (tenant_id, version_id, user_id, kind, round, sent_at)
                values (dms.current_tenant(), ${target.versionId}, ${reader.value}, 'automatic', $round,
                        ${utc(now)})
                on conflict (version_id, user_id, round) where kind = 'automatic' do nothing
                returning user_id""".query[String].run().map(UserId(_))
        )
      )
      _ <- ZIO.unless(fresh.isEmpty)(reminded(target, fresh, "automatic", Some(round), ReminderActor))
    yield fresh

  /** When the recipients of each version were first asked to acknowledge it (DMS-130); versions without
    * requests are missing.
    */
  def requests(versionIds: Set[UUID]): IO[AcknowledgementError, Map[UUID, Map[UserId, Instant]]] =
    if versionIds.isEmpty then ZIO.succeed(Map.empty)
    else
      sql(
        sql"""select version_id, user_id, requested_at from dms.acknowledgement_request
              where version_id::text in (select jsonb_array_elements_text(${json(
            versionIds.map(_.toString)
          )}))"""
          .query[(UUID, String, OffsetDateTime)]
          .run()
          .groupMap(_._1)((_, user, at) => UserId(user) -> at.toInstant)
          .view
          .mapValues(_.toMap)
          .toMap
      )

  /** Versions valid before notifications were delivered (migration V34) whose recipients are not recorded as
    * asked yet; see [[request]].
    */
  def requestBaselines: IO[AcknowledgementError, Set[UUID]] =
    sql(sql"select version_id from dms.acknowledgement_request_baseline".query[UUID].run().toSet)

  /** Records `readers` as asked to acknowledge the version at `at`, unless they were asked before, and
    * returns those recorded now. With `notify` the request goes to the outbox
    * (`document.acknowledgement-requested`, for the notifications module) and the audit trail (number of
    * readers); without, it is recorded silently and the version's baseline mark is removed (recipients of
    * versions valid before V34 count as asked at publication).
    */
  def request(
      target: ReminderTarget,
      readers: List[UserId],
      at: Instant,
      notify: Boolean
  ): IO[AcknowledgementError, List[UserId]] =
    for
      fresh <- sql(
        readers.distinct.flatMap(reader =>
          sql"""insert into dms.acknowledgement_request (tenant_id, version_id, user_id, requested_at, notified)
                values (dms.current_tenant(), ${target.versionId}, ${reader.value}, ${utc(at)}, $notify)
                on conflict (version_id, user_id) do nothing
                returning user_id""".query[String].run().map(UserId(_))
        )
      )
      _ <- ZIO.unless(notify)(
        sql(
          sql"delete from dms.acknowledgement_request_baseline where version_id = ${target.versionId}".update
            .run()
        )
      )
      _ <- ZIO.when(notify && fresh.nonEmpty)(
        audit
          .record(
            AuditEvent(
              Actor(ReminderActor.value, ActorKind.Technical),
              RequestAction,
              AuditObject("document", Some(target.documentId.value), Some(target.versionLabel)),
              Channel.Automation,
              Jsonb(
                Json
                  .Obj("versionId" -> Json.Str(target.versionId.toString), "readers" -> Json.Num(fresh.size))
                  .toJson
              )
            )
          )
          .mapError(AcknowledgementError.Database(_)) *>
          Outbox
            .publish(
              "document",
              target.documentId.value,
              RequestEvent,
              Jsonb(
                Json
                  .Obj(
                    "documentId" -> Json.Str(target.documentId.value.toString),
                    "versionId"  -> Json.Str(target.versionId.toString),
                    "version"    -> Json.Str(target.versionLabel),
                    "userIds"    -> Json.Arr(Chunk.fromIterable(fresh.map(u => Json.Str(u.value))))
                  )
                  .toJson
              )
            )
            .mapError(AcknowledgementError.Database(_))
      )
    yield fresh

  private def reminded(
      target: ReminderTarget,
      readers: List[UserId],
      kind: String,
      round: Option[Int],
      actor: UserId
  ) =
    val technical = actor == ReminderActor
    val details   = Json.Obj(
      (List(
        "versionId" -> Json.Str(target.versionId.toString),
        "kind"      -> Json.Str(kind),
        "readers"   -> Json.Num(readers.size)
      ) ++ round.map(r => "round" -> Json.Num(r)))*
    )
    val payload = Json.Obj(
      (List(
        "documentId" -> Json.Str(target.documentId.value.toString),
        "versionId"  -> Json.Str(target.versionId.toString),
        "version"    -> Json.Str(target.versionLabel),
        "title"      -> Json.Str(target.title),
        "kind"       -> Json.Str(kind),
        "userIds"    -> Json.Arr(Chunk.fromIterable(readers.map(u => Json.Str(u.value))))
      ) ++ round.map(r => "round" -> Json.Num(r)))*
    )
    for
      _ <- audit
        .record(
          AuditEvent(
            Actor(actor.value, if technical then ActorKind.Technical else ActorKind.Person),
            ReminderAction,
            AuditObject("document", Some(target.documentId.value), Some(target.versionLabel)),
            if technical then Channel.Automation else Channel.Api,
            Jsonb(details.toJson)
          )
        )
        .mapError(AcknowledgementError.Database(_))
      _ <- Outbox
        .publish("document", target.documentId.value, ReminderEvent, Jsonb(payload.toJson))
        .mapError(AcknowledgementError.Database(_))
    yield ()

  private def recordSettings(actor: UserId, action: String, classId: Option[UUID], details: Json.Obj) =
    audit
      .record(
        AuditEvent(
          Actor(actor.value, ActorKind.Person),
          action,
          AuditObject(classId.fold("tenant")(_ => "document-class"), classId, None),
          Channel.Api,
          Jsonb(details.toJson)
        )
      )
      .mapError(AcknowledgementError.Database(_))
      .unit

  private def record(reader: UserId, action: String, v: DocumentVersion, details: Json.Obj) =
    audit
      .record(
        AuditEvent(
          Actor(reader.value, ActorKind.Person),
          action,
          AuditObject("document", Some(v.documentId.value), Some(v.label)),
          Channel.Api,
          Jsonb(details.merge(Json.Obj("versionId" -> Json.Str(v.id.toString))).toJson)
        )
      )
      .mapError(AcknowledgementError.Database(_))
      .unit

object AcknowledgementService:
  val layer: URLayer[AuditTrail, AcknowledgementService] = ZLayer.fromFunction(AcknowledgementService(_))

  /** The platform as actor of automatic reminders; recorded as technical actor. */
  val ReminderActor: UserId = UserId("system:acknowledgement-reminder")

  /** Audit action of a reminder of open acknowledgements (DMS-100). */
  val ReminderAction = "document.acknowledgement-reminded"

  /** Outbox event of a reminder, for the notifications module; names the reminded readers. */
  val ReminderEvent = "document.acknowledgement-reminder"

  /** Audit action of asking recipients for their acknowledgement (DMS-130). */
  val RequestAction = "document.acknowledgement-requested"

  /** Outbox event asking recipients for their acknowledgement, for the notifications module (DMS-130). */
  val RequestEvent = "document.acknowledgement-requested"

  /** Records the acknowledgement settings that apply to `versionId` of class `classId` now (DMS-100, ADR
    * 0048). Called when a version that requires acknowledgement becomes valid, in the same transaction.
    * Idempotent: the first record is kept.
    */
  def recordAudience(versionId: UUID, classId: UUID)(using DbTx): Unit =
    val chain =
      sql"""with recursive chain (id, parent_id, depth) as (
              select id, parent_id, 0 from dms.document_class where id = $classId
              union all
              select c.id, c.parent_id, chain.depth + 1
              from dms.document_class c join chain on c.id = chain.parent_id
            )
            select s.class_id, array_to_json(s.recipient_roles)::text, array_to_json(s.recipient_groups)::text,
                   array_to_json(s.recipient_units)::text, s.reminder_days, s.updated_at, s.updated_by
            from chain join dms.acknowledgement_settings s on s.class_id = chain.id
            order by chain.depth""".query[SettingsRow].run().toList.map(toStored)
    val tenant =
      sql"""select class_id, array_to_json(recipient_roles)::text, array_to_json(recipient_groups)::text,
                   array_to_json(recipient_units)::text, reminder_days, updated_at, updated_by
            from dms.acknowledgement_settings where class_id is null"""
        .query[SettingsRow]
        .run()
        .headOption
        .map(toStored)
    val (source, sourceClass, settings) =
      VersionAudience.resolve(chain.flatMap(s => s.classId.map(_ -> s.settings)), tenant.map(_.settings))
    val r = settings.recipients
    val _ =
      sql"""insert into dms.version_acknowledgement_audience
              (version_id, tenant_id, source, source_class_id, recipient_roles, recipient_groups, recipient_units,
               reminder_days)
            values ($versionId, dms.current_tenant(), ${source.toString.toLowerCase}, $sourceClass,
                    array(select jsonb_array_elements_text(${json(r.roles)})),
                    array(select jsonb_array_elements_text(${json(r.groups)})),
                    array(select jsonb_array_elements_text(${json(r.units)})), ${settings.reminderDays})
            on conflict (version_id) do nothing""".update.run()

  private type SettingsRow = (Option[UUID], String, String, String, Option[Int], OffsetDateTime, String)
  private type AudienceRow = (UUID, String, Option[UUID], String, String, String, Option[Int], OffsetDateTime)

  private def json(values: Set[String]): Jsonb = Jsonb(values.toList.sorted.toJson)

  private def strings(json: String): Set[String] = json.fromJson[List[String]].fold(_ => Nil, identity).toSet

  private def toStored(r: SettingsRow): StoredAcknowledgementSettings =
    val (cls, roles, groups, units, days, at, by) = r
    StoredAcknowledgementSettings(
      cls,
      AcknowledgementSettings(
        AcknowledgementRecipients(strings(roles), strings(groups), strings(units)),
        days
      ),
      at.toInstant,
      UserId(by)
    )

  private def toAudience(r: AudienceRow): VersionAudience =
    val (version, source, cls, roles, groups, units, days, at) = r
    VersionAudience(
      version,
      AudienceSource.values.find(_.toString.equalsIgnoreCase(source)).getOrElse(AudienceSource.Default),
      cls,
      AcknowledgementRecipients(strings(roles), strings(groups), strings(units)),
      days,
      at.toInstant
    )

  private def settingsJson(s: AcknowledgementSettings): Json.Obj =
    def list(values: Set[String]) = Json.Arr(Chunk.fromIterable(values.toList.sorted.map(Json.Str(_))))
    Json.Obj(
      "roles"        -> list(s.recipients.roles),
      "groups"       -> list(s.recipients.groups),
      "units"        -> list(s.recipients.units),
      "reminderDays" -> s.reminderDays.fold(Json.Null)(Json.Num(_))
    )

  private type Row = (UUID, UUID, UUID, String, String, OffsetDateTime, OffsetDateTime)

  private def valid(status: String): Boolean =
    DocumentStatus.values.find(_.toString == status).exists(Versioning.isValid)

  private def sql[A](f: DbTx ?=> A): IO[AcknowledgementError, A] =
    Db.sql(f).mapError(AcknowledgementError.Database(_))

  /** The current time at the precision PostgreSQL stores (microseconds). */
  private val databaseNow: UIO[Instant] = Clock.instant.map(_.truncatedTo(ChronoUnit.MICROS))

  private def utc(i: Instant): OffsetDateTime = OffsetDateTime.ofInstant(i, ZoneOffset.UTC)

  private def openedAt(versionId: UUID, reader: UserId)(using DbTx): Option[Instant] =
    sql"""select opened_at from dms.document_version_opened
          where version_id = $versionId and user_id = ${reader.value}"""
      .query[OffsetDateTime]
      .run()
      .headOption
      .map(_.toInstant)

  private def acknowledgementOf(versionId: UUID, reader: UserId)(using DbTx): Option[Acknowledgement] =
    sql"""select id, document_id, version_id, version_label, user_id, opened_at, acknowledged_at
          from dms.document_acknowledgement where version_id = $versionId and user_id = ${reader.value}"""
      .query[Row]
      .run()
      .headOption
      .map(toAcknowledgement)

  private def insert(a: Acknowledgement)(using DbTx): Unit =
    val _ = sql"""insert into dms.document_acknowledgement
                    (id, tenant_id, document_id, version_id, version_label, user_id, opened_at, acknowledged_at)
                  values (${a.id}, dms.current_tenant(), ${a.documentId.value}, ${a.versionId}, ${a.versionLabel},
                          ${a.userId.value}, ${utc(a.openedAt)}, ${utc(a.acknowledgedAt)})""".update.run()

  private def toAcknowledgement(r: Row): Acknowledgement =
    val (id, document, version, label, user, opened, acknowledged) = r
    Acknowledgement(
      id,
      DocumentId(document),
      version,
      label,
      UserId(user),
      opened.toInstant,
      acknowledged.toInstant
    )
