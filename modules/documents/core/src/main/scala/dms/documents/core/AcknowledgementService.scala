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

  case Database(error: DbError)

  def describe: String = this match
    case NotRequested(m)        => m
    case NotOpened(m)           => m
    case AlreadyAcknowledged(m) => m
    case Database(e)            => s"database error: $e"

/** What a reader did with a version: when they opened it first and their acknowledgement, if any. */
final case class AcknowledgementStatus(
    versionId: UUID,
    openedAt: Option[Instant],
    acknowledgement: Option[Acknowledgement]
)

/** Acknowledgement of valid versions by their readers (business concept 6.3, US-06.2, ADR 0027).
  *
  * A reader first opens the version ([[markOpened]], recorded once), then acknowledges it ([[acknowledge]],
  * once). Both are insert-only (migration V14). Only valid versions that require acknowledgement ask for it
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

  /** The acknowledgements of `reader`, the latest first. */
  def acknowledgementsOf(reader: UserId): IO[AcknowledgementError, List[Acknowledgement]] =
    sql(
      sql"""select id, document_id, version_id, version_label, user_id, opened_at, acknowledged_at
            from dms.document_acknowledgement where user_id = ${reader.value}
            order by acknowledged_at desc, id""".query[Row].run().map(toAcknowledgement).toList
    )

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
