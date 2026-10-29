package dms.documents.core

import com.augustnagro.magnum.*
import dms.audit.*
import dms.db.{Db, Jsonb}
import dms.documents.*
import dms.kernel.{DocumentId, UserId}
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.util.{Locale, UUID}

/** Attachments of document versions (DMS-114, ADR 0041): linking stored files to the draft, renaming and
  * removing them, and reading them.
  *
  * Every method must run inside `Db.transact(tenant)`. Links change only while their version is a draft; the
  * draft's row is locked first, so a concurrent submission waits or is seen (the database guard of migration
  * V26 checks the same). Every change is recorded in the audit trail in the same transaction. Authorization
  * is the caller's job; the bytes are handled by [[AttachmentUploads]] outside the transaction.
  */
final class AttachmentService(audit: AuditTrail):
  import AttachmentService.*

  /** The file of the document with this content, if it was stored before: an upload of the same bytes reuses
    * it instead of storing them again.
    */
  def existingFile(document: DocumentId, sha256: String): IO[DocumentError, Option[AttachmentFile]] =
    sql(AttachmentStore.file(document, sha256))

  /** Links a file to the draft `draft` under `name`. The file is inserted unless the document already has a
    * file with the same content (a concurrent upload of the same bytes), which is used instead.
    *
    * @return
    *   the attachment and whether `file` was inserted (if not, its object is not referenced and can be
    *   removed)
    */
  def add(
      draft: DocumentVersion,
      file: AttachmentFile,
      name: String,
      actor: UserId
  ): IO[DocumentError, (VersionAttachment, Boolean)] =
    for
      _        <- lockDraft(draft)
      existing <- sql(AttachmentStore.ofVersion(draft.id))
      _        <- uniqueName(existing, name, None)
      inserted <- sql(AttachmentStore.insertFile(file))
      stored   <-
        if inserted then ZIO.succeed(file)
        else
          sql(AttachmentStore.file(draft.documentId, file.sha256)).someOrFail(conflict("the file vanished"))
      _ <- ZIO.foreachDiscard(existing.find(_.fileId == stored.id))(a =>
        ZIO.fail(DocumentError.Conflict(s"the same file is already attached as \"${a.name}\""))
      )
      id = UUID.randomUUID()
      _     <- sql(AttachmentStore.insertLink(id, draft.id, stored.id, name, actor, None))
      added <- sql(AttachmentStore.ofVersion(draft.id))
        .map(_.find(_.id == id))
        .someOrFail(conflict("not added"))
      _ <- record(
        actor,
        "document.attachment-added",
        draft,
        Json.Obj(
          "attachmentId" -> Json.Str(id.toString),
          "name"         -> Json.Str(name),
          "sha256"       -> Json.Str(stored.sha256),
          "size"         -> Json.Num(stored.size),
          "contentType"  -> Json.Str(stored.contentType),
          "scanStatus"   -> Json.Str(stored.scanStatus.toString),
          "scanner"      -> Json.Str(stored.scanner),
          "reused"       -> Json.Bool(!inserted)
        )
      )
    yield (added, inserted)

  /** Renames an attachment of the draft. Names are unique per version, ignoring case. */
  def rename(
      draft: DocumentVersion,
      attachmentId: UUID,
      name: String,
      actor: UserId
  ): IO[DocumentError, VersionAttachment] =
    for
      _        <- lockDraft(draft)
      existing <- sql(AttachmentStore.ofVersion(draft.id))
      current  <- ZIO
        .fromOption(existing.find(_.id == attachmentId))
        .orElseFail(notInDraft(draft, attachmentId))
      _ <- uniqueName(existing, name, Some(attachmentId))
      _ <- ZIO.when(current.name != name)(
        sql(AttachmentStore.rename(attachmentId, name)) *>
          record(
            actor,
            "document.attachment-renamed",
            draft,
            Json.Obj(
              "attachmentId" -> Json.Str(attachmentId.toString),
              "before"       -> Json.Str(current.name),
              "after"        -> Json.Str(name),
              "sha256"       -> Json.Str(current.sha256)
            )
          )
      )
    yield current.copy(name = name)

  /** Removes an attachment from the draft: only the link is deleted; the stored file stays, since other
    * versions of the document may reference it.
    */
  def remove(
      draft: DocumentVersion,
      attachmentId: UUID,
      actor: UserId
  ): IO[DocumentError, VersionAttachment] =
    for
      _        <- lockDraft(draft)
      existing <- sql(AttachmentStore.ofVersion(draft.id))
      current  <- ZIO
        .fromOption(existing.find(_.id == attachmentId))
        .orElseFail(notInDraft(draft, attachmentId))
      _ <- sql(AttachmentStore.remove(attachmentId))
      _ <- record(
        actor,
        "document.attachment-removed",
        draft,
        Json.Obj(
          "attachmentId" -> Json.Str(attachmentId.toString),
          "name"         -> Json.Str(current.name),
          "sha256"       -> Json.Str(current.sha256)
        )
      )
    yield current

  /** Locks the draft's row and checks that it is still the draft it was read as. */
  private def lockDraft(draft: DocumentVersion): IO[DocumentError, Unit] =
    sql(
      sql"select status from dms.document_version where id = ${draft.id} for update"
        .query[String]
        .run()
        .headOption
    ).flatMap {
      case Some(status) if status == DocumentStatus.Draft.toString && draft.status == DocumentStatus.Draft =>
        ZIO.unit
      case Some(status) =>
        ZIO.fail(
          DocumentError.Conflict(
            s"version ${draft.label} is $status; attachments only change while the version is a draft"
          )
        )
      case None => ZIO.fail(DocumentError.NotFound(s"document version ${draft.id} does not exist"))
    }

  private def uniqueName(
      existing: List[VersionAttachment],
      name: String,
      except: Option[UUID]
  ): IO[DocumentError, Unit] =
    val key = name.toLowerCase(Locale.ROOT)
    ZIO.foreachDiscard(existing.find(a => !except.contains(a.id) && a.name.toLowerCase(Locale.ROOT) == key))(
      a =>
        ZIO.fail(DocumentError.Conflict(s"an attachment named \"${a.name}\" already exists in this version"))
    )

  private def record(actor: UserId, action: String, v: DocumentVersion, details: Json.Obj) =
    audit
      .record(
        AuditEvent(
          Actor(actor.value, ActorKind.Person),
          action,
          AuditObject("document", Some(v.documentId.value), Some(v.label)),
          Channel.Api,
          Jsonb(details.merge(Json.Obj("versionId" -> Json.Str(v.id.toString))).toJson)
        )
      )
      .mapError(DocumentError.Database(_))
      .unit

object AttachmentService:
  val layer: URLayer[AuditTrail, AttachmentService] = ZLayer.fromFunction(AttachmentService(_))

  /** Object key of an attachment file: one object per stored file, below the tenant and the document. */
  def objectKey(tenant: dms.kernel.TenantId, document: DocumentId, fileId: UUID): String =
    s"attachments/${tenant.value}/${document.value}/$fileId"

  private def sql[A](f: DbTx ?=> A): IO[DocumentError, A] = Db.sql(f).mapError(DocumentError.Database(_))

  private def conflict(message: String) = DocumentError.Conflict(message)

  private def notInDraft(draft: DocumentVersion, attachmentId: UUID) =
    DocumentError.NotFound(s"version ${draft.label} has no attachment $attachmentId")
