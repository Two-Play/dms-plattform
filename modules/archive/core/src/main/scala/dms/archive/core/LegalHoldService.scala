package dms.archive.core

import com.augustnagro.magnum.*
import dms.archive.*
import dms.audit.*
import dms.db.queue.{JobQueue, Outbox}
import dms.db.{Db, DbError, Jsonb}
import dms.kernel.{DocumentId, UserId}
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.OffsetDateTime
import java.util.UUID

/** Legal holds (business concept 7.4, US-08.1, ADR 0031): setting a hold for a matter, adding documents to it
  * and releasing it, each with a reason.
  *
  * Every method must run inside `Db.transact(tenant)`. Authorization (records managers only) and resolving a
  * search result into documents are the caller's part; the service records what the caller decided. Every
  * change is written to the audit trail (one entry for the hold and one per document, so the document's
  * history shows it) and to the outbox, and enqueues the synchronization of the object legal hold of the
  * documents' renditions ([[LegalHoldSync]]), all in the same transaction.
  */
final class LegalHoldService(audit: AuditTrail):
  import LegalHoldService.*

  /** Sets a new hold and adds `documents` to it (possibly none: documents can be added later). */
  def create(
      hold: NewLegalHold,
      documents: List[DocumentId],
      source: HoldSource,
      actor: UserId
  ): IO[LegalHoldError, LegalHold] =
    for
      valid <- validate(hold)
      id = LegalHoldId.random()
      _ <- sql(
        sql"""insert into dms.legal_hold (id, tenant_id, name, matter_reference, reason, created_by)
              values (${id.value}, dms.current_tenant(), ${valid.name}, ${valid.matterReference}, ${valid.reason},
                      ${actor.value})""".update.run()
      )
      added <- insertDocuments(id, documents.distinct, valid.reason, source, actor)
      _     <- record(
        actor,
        "legal-hold.created",
        AuditObject("legal-hold", Some(id.value)),
        valid.reason,
        Json.Obj(
          "name"            -> Json.Str(valid.name),
          "matterReference" -> valid.matterReference.fold[Json](Json.Null)(Json.Str(_)),
          "source"          -> Json.Str(source.toString),
          "documentIds"     -> ids(added)
        )
      )
      _ <- documentEntries(actor, "document.legal-hold-set", id, added, valid.reason, source)
      _ <- publish(
        id,
        LegalHoldEvents.Created,
        Json.Obj(
          "holdId"          -> Json.Str(id.value.toString),
          "name"            -> Json.Str(valid.name),
          "matterReference" -> valid.matterReference.fold[Json](Json.Null)(Json.Str(_)),
          "reason"          -> Json.Str(valid.reason),
          "documentIds"     -> ids(added)
        )
      )
      _       <- enqueueSync(added)
      created <- get(id)
    yield created

  /** Adds documents to an active hold. Documents the hold covers already are skipped. Returns the documents
    * added now.
    */
  def addDocuments(
      id: LegalHoldId,
      documents: List[DocumentId],
      reason: String,
      source: HoldSource,
      actor: UserId
  ): IO[LegalHoldError, List[DocumentId]] =
    for
      why  <- requireReason(reason)
      hold <- get(id)
      _    <- ZIO.foreachDiscard(hold.release)(_ =>
        ZIO.fail(LegalHoldError.Released(s"legal hold ${id.value} is released; documents cannot be added"))
      )
      added <- insertDocuments(id, documents.distinct, why, source, actor)
      _     <- ZIO.when(added.nonEmpty)(
        record(
          actor,
          "legal-hold.documents-added",
          AuditObject("legal-hold", Some(id.value)),
          why,
          Json.Obj("source" -> Json.Str(source.toString), "documentIds" -> ids(added))
        ) *>
          documentEntries(actor, "document.legal-hold-set", id, added, why, source) *>
          publish(
            id,
            LegalHoldEvents.DocumentsAdded,
            Json.Obj(
              "holdId"      -> Json.Str(id.value.toString),
              "reason"      -> Json.Str(why),
              "documentIds" -> ids(added)
            )
          ) *>
          enqueueSync(added)
      )
    yield added

  /** Releases an active hold. Its documents stay listed with it; those no other active hold covers are no
    * longer under legal hold.
    */
  def release(id: LegalHoldId, reason: String, actor: UserId): IO[LegalHoldError, LegalHold] =
    for
      why  <- requireReason(reason)
      hold <- get(id)
      _    <- ZIO.foreachDiscard(hold.release)(r =>
        ZIO.fail(LegalHoldError.Released(s"legal hold ${id.value} was released at ${r.at} already"))
      )
      updated <- sql(
        sql"""update dms.legal_hold set released_at = now(), released_by = ${actor.value}, release_reason = $why
              where id = ${id.value} and released_at is null""".update.run()
      )
      _ <- ZIO.when(updated == 0)(
        ZIO.fail(LegalHoldError.Released(s"legal hold ${id.value} was released concurrently"))
      )
      members   <- documents(id).map(_.map(_.documentId))
      stillHeld <- sql(heldDocuments).map(_.toSet)
      freed = members.filterNot(stillHeld.contains)
      _ <- record(
        actor,
        "legal-hold.released",
        AuditObject("legal-hold", Some(id.value)),
        why,
        Json.Obj("documentIds" -> ids(members), "releasedDocumentIds" -> ids(freed))
      )
      _ <- ZIO.foreachDiscard(members)(d =>
        record(
          actor,
          "document.legal-hold-released",
          AuditObject("document", Some(d.value)),
          why,
          Json.Obj(
            "holdId"         -> Json.Str(id.value.toString),
            "underLegalHold" -> Json.Bool(stillHeld.contains(d))
          )
        )
      )
      _ <- publish(
        id,
        LegalHoldEvents.Released,
        Json.Obj(
          "holdId"              -> Json.Str(id.value.toString),
          "reason"              -> Json.Str(why),
          "documentIds"         -> ids(members),
          "releasedDocumentIds" -> ids(freed)
        )
      )
      _        <- enqueueSync(freed)
      released <- get(id)
    yield released

  /** A hold, or `NotFound`. */
  def get(id: LegalHoldId): IO[LegalHoldError, LegalHold] =
    sql(holds(id = Some(id.value)).headOption)
      .someOrFail(LegalHoldError.NotFound(s"legal hold ${id.value} does not exist"))

  /** The holds of the tenant, the latest first: active ones, released ones, or (None) all. */
  def list(active: Option[Boolean]): IO[LegalHoldError, List[LegalHold]] =
    sql(holds(active = active))

  /** The documents a hold covers, in the order they were added. */
  def documents(id: LegalHoldId): IO[LegalHoldError, List[HeldDocument]] =
    sql(
      sql"""select hold_id, document_id, added_via, reason, added_by, added_at
            from dms.legal_hold_document where hold_id = ${id.value}
            order by added_at, document_id"""
        .query[(UUID, UUID, String, String, String, OffsetDateTime)]
        .run()
        .toList
        .map((hold, doc, via, reason, by, at) =>
          HeldDocument(
            LegalHoldId(hold),
            DocumentId(doc),
            HoldSource.valueOf(via),
            reason,
            UserId(by),
            at.toInstant
          )
        )
    )

  private def insertDocuments(
      id: LegalHoldId,
      documents: List[DocumentId],
      reason: String,
      source: HoldSource,
      actor: UserId
  ): IO[LegalHoldError, List[DocumentId]] =
    ZIO
      .foreach(documents)(d =>
        sql(
          sql"""insert into dms.legal_hold_document (tenant_id, hold_id, document_id, added_via, reason, added_by)
                values (dms.current_tenant(), ${id.value}, ${d.value}, ${source.toString}, $reason, ${actor.value})
                on conflict (hold_id, document_id) do nothing
                returning document_id""".query[UUID].run().headOption.map(DocumentId(_))
        )
      )
      .map(_.flatten)

  private def documentEntries(
      actor: UserId,
      action: String,
      id: LegalHoldId,
      documents: List[DocumentId],
      reason: String,
      source: HoldSource
  ): IO[LegalHoldError, Unit] =
    ZIO.foreachDiscard(documents)(d =>
      record(
        actor,
        action,
        AuditObject("document", Some(d.value)),
        reason,
        Json.Obj("holdId" -> Json.Str(id.value.toString), "source" -> Json.Str(source.toString))
      )
    )

  private def record(
      actor: UserId,
      action: String,
      target: AuditObject,
      reason: String,
      details: Json.Obj
  ): IO[LegalHoldError, Unit] =
    audit
      .record(
        AuditEvent(
          Actor(actor.value, ActorKind.Person),
          action,
          target,
          Channel.Api,
          Jsonb(details.toJson),
          reason = Some(reason)
        )
      )
      .mapError(LegalHoldError.Database(_))
      .unit

object LegalHoldService:
  val layer: URLayer[AuditTrail, LegalHoldService] = ZLayer.fromFunction(LegalHoldService(_))

  val MaxNameLength: Int   = 200
  val MaxReasonLength: Int = 4000

  private type HoldRow =
    (
        UUID,
        String,
        Option[String],
        String,
        String,
        OffsetDateTime,
        Option[OffsetDateTime],
        Option[String],
        Option[String],
        Long
    )

  private def sql[A](f: DbTx ?=> A): IO[LegalHoldError, A] =
    Db.sql(f).mapError(LegalHoldError.Database(_))

  private def validate(hold: NewLegalHold): IO[LegalHoldError, NewLegalHold] =
    val name     = hold.name.trim
    val matter   = hold.matterReference.map(_.trim).filter(_.nonEmpty)
    val reason   = hold.reason.trim
    val problems =
      Option.when(name.isEmpty)("name is required").toList ++
        Option.when(name.length > MaxNameLength)(s"name is longer than $MaxNameLength characters") ++
        Option.when(matter.exists(_.length > MaxNameLength))(
          s"matterReference is longer than $MaxNameLength characters"
        ) ++ reasonProblems(reason)
    if problems.isEmpty then ZIO.succeed(NewLegalHold(name, matter, reason))
    else ZIO.fail(LegalHoldError.Invalid(problems))

  private def requireReason(reason: String): IO[LegalHoldError, String] =
    val trimmed = reason.trim
    reasonProblems(trimmed) match
      case Nil      => ZIO.succeed(trimmed)
      case problems => ZIO.fail(LegalHoldError.Invalid(problems))

  private def reasonProblems(reason: String): List[String] =
    Option.when(reason.isEmpty)("reason is required").toList ++
      Option.when(reason.length > MaxReasonLength)(s"reason is longer than $MaxReasonLength characters")

  private def ids(documents: List[DocumentId]): Json =
    Json.Arr(Chunk.fromIterable(documents.map(d => Json.Str(d.value.toString))))

  private def publish(id: LegalHoldId, event: String, payload: Json.Obj): IO[LegalHoldError, Unit] =
    Outbox
      .publish("legal-hold", id.value, event, Jsonb(payload.toJson))
      .mapError(LegalHoldError.Database(_))
      .unit

  private def enqueueSync(documents: List[DocumentId]): IO[LegalHoldError, Unit] =
    ZIO
      .foreachDiscard(documents)(d =>
        JobQueue.enqueue(
          LegalHoldEvents.SyncJobKind,
          Jsonb(Json.Obj("documentId" -> Json.Str(d.value.toString)).toJson)
        )
      )
      .mapError(LegalHoldError.Database(_))

  /** The documents an active hold covers. */
  private[core] def heldDocuments(using DbTx): List[DocumentId] =
    sql"""select distinct m.document_id
          from dms.legal_hold_document m
          join dms.legal_hold h on h.id = m.hold_id
          where h.released_at is null""".query[UUID].run().toList.map(DocumentId(_))

  /** Holds, the latest first, restricted to one hold, to active or released ones, or to those covering a
    * document.
    */
  private[core] def holds(
      id: Option[UUID] = None,
      active: Option[Boolean] = None,
      document: Option[UUID] = None
  )(using DbTx): List[LegalHold] =
    sql"""select h.id, h.name, h.matter_reference, h.reason, h.created_by, h.created_at, h.released_at,
                 h.released_by, h.release_reason,
                 (select count(*) from dms.legal_hold_document m where m.hold_id = h.id)
          from dms.legal_hold h
          where (cast($id as uuid) is null or h.id = cast($id as uuid))
            and (cast($active as boolean) is null or (h.released_at is null) = cast($active as boolean))
            and (cast($document as uuid) is null
                 or exists (select 1 from dms.legal_hold_document m
                            where m.hold_id = h.id and m.document_id = cast($document as uuid)))
          order by h.created_at desc, h.id""".query[HoldRow].run().toList.map(toHold)

  private def toHold(r: HoldRow): LegalHold =
    val (id, name, matter, reason, by, at, releasedAt, releasedBy, releaseReason, count) = r
    LegalHold(
      LegalHoldId(id),
      name,
      matter,
      reason,
      UserId(by),
      at.toInstant,
      for
        a <- releasedAt
        b <- releasedBy
        w <- releaseReason
      yield HoldRelease(UserId(b), a.toInstant, w),
      count.toInt
    )

/** [[LegalHoldLookup]] on the tables of migration V19. */
object LegalHoldStore extends LegalHoldLookup:
  import LegalHoldService.{heldDocuments, holds}

  def isUnderLegalHold(document: DocumentId): IO[DbError, Boolean] =
    Db.sql(
      sql"select dms.document_under_legal_hold(${document.value})".query[Boolean].run().head
    )

  def heldAmong(documents: Set[DocumentId]): IO[DbError, Set[DocumentId]] =
    if documents.isEmpty then ZIO.succeed(Set.empty)
    else Db.sql(heldDocuments.filter(documents.contains).toSet)

  def activeHolds(document: DocumentId): IO[DbError, List[LegalHold]] =
    Db.sql(holds(active = Some(true), document = Some(document.value)).reverse)

  val layer: ULayer[LegalHoldLookup] = ZLayer.succeed(LegalHoldStore)
