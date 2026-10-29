package dms.documents.core

import com.augustnagro.magnum.*
import dms.documents.{ScanStatus, VersionAttachment}
import dms.kernel.{DocumentId, UserId}

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.UUID

/** A stored attachment file (migration V26): one object per document and SHA-256. */
final case class AttachmentFile(
    id: UUID,
    documentId: DocumentId,
    sha256: String,
    size: Long,
    contentType: String,
    objectKey: String,
    objectVersion: String,
    scanStatus: ScanStatus,
    scanner: String,
    scannedAt: Instant,
    createdBy: UserId
)

/** SQL for attachment files and their links to versions (migration V26). Runs inside `Db.sql` within
  * `Db.transact(tenant)`; row-level security limits all rows to the current tenant, and the guard trigger
  * rejects link changes of versions that are not drafts.
  */
private[core] object AttachmentStore:

  final case class LinkRow(
      id: UUID,
      versionId: UUID,
      fileId: UUID,
      name: String,
      contentType: String,
      sizeBytes: Long,
      sha256: String,
      objectKey: String,
      objectVersion: String,
      scanStatus: String,
      scanner: String,
      addedBy: String,
      addedAt: OffsetDateTime,
      basedOn: Option[UUID]
  ) derives DbCodec

  final case class FileRow(
      id: UUID,
      documentId: UUID,
      sha256: String,
      sizeBytes: Long,
      contentType: String,
      objectKey: String,
      objectVersion: String,
      scanStatus: String,
      scanner: String,
      scannedAt: OffsetDateTime,
      createdBy: String
  ) derives DbCodec

  // The column list is repeated in each query: Magnum binds parameters only for literal sql"" fragments.

  /** The attachments of all versions of a document, by version, each in the order they were added. */
  def ofDocument(document: DocumentId)(using DbTx): Map[UUID, List[VersionAttachment]] =
    sql"""select a.id, a.version_id, a.file_id, a.name, f.content_type, f.size_bytes, f.sha256, f.object_key,
                 f.object_version, f.scan_status, f.scanner, a.added_by, a.added_at, a.based_on
          from dms.document_version_attachment a
          join dms.document_attachment_file f on f.id = a.file_id
          where f.document_id = ${document.value}
          order by a.added_at, a.id"""
      .query[LinkRow]
      .run()
      .map(toAttachment)
      .toList
      .groupBy(_.versionId)

  /** The attachments of a version in the order they were added. */
  def ofVersion(versionId: UUID)(using DbTx): List[VersionAttachment] =
    sql"""select a.id, a.version_id, a.file_id, a.name, f.content_type, f.size_bytes, f.sha256, f.object_key,
                 f.object_version, f.scan_status, f.scanner, a.added_by, a.added_at, a.based_on
          from dms.document_version_attachment a
          join dms.document_attachment_file f on f.id = a.file_id
          where a.version_id = $versionId
          order by a.added_at, a.id"""
      .query[LinkRow]
      .run()
      .map(toAttachment)
      .toList

  /** The file of a document with this content, if it was stored before. */
  def file(document: DocumentId, sha256: String)(using DbTx): Option[AttachmentFile] =
    sql"""select id, document_id, sha256, size_bytes, content_type, object_key, object_version, scan_status,
                 scanner, scanned_at, created_by
          from dms.document_attachment_file where document_id = ${document.value} and sha256 = $sha256"""
      .query[FileRow]
      .run()
      .headOption
      .map(toFile)

  /** Inserts a file unless the document already has one with the same content (a concurrent upload). Returns
    * whether this file was inserted.
    */
  def insertFile(f: AttachmentFile)(using DbTx): Boolean =
    sql"""insert into dms.document_attachment_file
            (id, tenant_id, document_id, sha256, size_bytes, content_type, object_key, object_version,
             scan_status, scanner, scanned_at, created_by)
          values (${f.id}, dms.current_tenant(), ${f.documentId.value}, ${f.sha256}, ${f.size}, ${f.contentType},
                  ${f.objectKey}, ${f.objectVersion}, ${f.scanStatus.toString}, ${f.scanner},
                  ${OffsetDateTime.ofInstant(f.scannedAt, ZoneOffset.UTC)}, ${f.createdBy.value})
          on conflict (document_id, sha256) do nothing""".update.run() == 1

  def insertLink(id: UUID, versionId: UUID, fileId: UUID, name: String, by: UserId, basedOn: Option[UUID])(
      using DbTx
  ): Unit =
    val _ = sql"""insert into dms.document_version_attachment
                    (id, tenant_id, version_id, file_id, name, added_by, based_on)
                  values ($id, dms.current_tenant(), $versionId, $fileId, $name, ${by.value}, $basedOn)""".update
      .run()

  /** Takes a link of the previous version over to a new draft: same file, name and who added it when;
    * `basedOn` names the original link.
    */
  def carryOver(link: VersionAttachment)(using DbTx): Unit =
    val _ = sql"""insert into dms.document_version_attachment
                    (id, tenant_id, version_id, file_id, name, added_at, added_by, based_on)
                  values (${link.id}, dms.current_tenant(), ${link.versionId}, ${link.fileId}, ${link.name},
                          ${OffsetDateTime.ofInstant(link.addedAt, ZoneOffset.UTC)}, ${link.addedBy.value},
                          ${link.basedOn})""".update.run()

  def rename(id: UUID, name: String)(using DbTx): Boolean =
    sql"update dms.document_version_attachment set name = $name where id = $id".update.run() == 1

  def remove(id: UUID)(using DbTx): Boolean =
    sql"delete from dms.document_version_attachment where id = $id".update.run() == 1

  /** Removes every attachment link of a draft (DMS-129, discarding it); the guard allows it only while the
    * version is a draft. Returns the files the links pointed to.
    */
  def removeAllOf(versionId: UUID)(using DbTx): List[UUID] =
    sql"""delete from dms.document_version_attachment where version_id = $versionId returning file_id"""
      .query[UUID]
      .run()
      .toList

  /** The file if no version references it any more, neither as attachment nor as original. */
  def unreferenced(id: UUID)(using DbTx): Option[AttachmentFile] =
    sql"""select id, document_id, sha256, size_bytes, content_type, object_key, object_version, scan_status,
                 scanner, scanned_at, created_by
          from dms.document_attachment_file f
          where f.id = $id
            and not exists (select 1 from dms.document_version_attachment a where a.file_id = f.id)
            and not exists (select 1 from dms.document_version_file v where v.file_id = f.id)"""
      .query[FileRow]
      .run()
      .headOption
      .map(toFile)

  /** Deletes a file nothing references any more (DMS-129); the guard refuses it under legal hold. */
  def deleteFile(id: UUID)(using DbTx): Boolean =
    sql"delete from dms.document_attachment_file where id = $id".update.run() == 1

  /** Whether an active legal hold covers the document (ADR 0031). */
  def underLegalHold(document: DocumentId)(using DbTx): Boolean =
    sql"select dms.document_under_legal_hold(${document.value})".query[Boolean].run().head

  /** Whether a file row (still or again) points at this object. */
  def objectReferenced(objectKey: String, objectVersion: String)(using DbTx): Boolean =
    sql"""select exists (select 1 from dms.document_attachment_file
                          where object_key = $objectKey and object_version = $objectVersion)"""
      .query[Boolean]
      .run()
      .head

  private def toAttachment(r: LinkRow): VersionAttachment =
    VersionAttachment(
      r.id,
      r.versionId,
      r.fileId,
      r.name,
      r.contentType,
      r.sizeBytes,
      r.sha256,
      r.objectKey,
      r.objectVersion,
      ScanStatus.valueOf(r.scanStatus),
      r.scanner,
      UserId(r.addedBy),
      r.addedAt.toInstant,
      r.basedOn
    )

  private def toFile(r: FileRow): AttachmentFile =
    AttachmentFile(
      r.id,
      dms.kernel.DocumentId(r.documentId),
      r.sha256,
      r.sizeBytes,
      r.contentType,
      r.objectKey,
      r.objectVersion,
      ScanStatus.valueOf(r.scanStatus),
      r.scanner,
      r.scannedAt.toInstant,
      UserId(r.createdBy)
    )
