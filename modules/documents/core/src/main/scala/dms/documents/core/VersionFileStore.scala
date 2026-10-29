package dms.documents.core

import com.augustnagro.magnum.*
import dms.db.Jsonb
import dms.documents.core.DocumentJson.given
import dms.documents.{ScanStatus, VersionFile}
import dms.kernel.{DocumentId, UserId}
import dms.templates.WordField
import zio.json.*

import java.time.{OffsetDateTime, ZoneOffset}
import java.util.UUID

/** SQL for the uploaded originals of file document versions (migration V27, DMS-115). Runs inside `Db.sql`
  * within `Db.transact(tenant)`; row-level security limits all rows to the current tenant, and the guard
  * trigger rejects changes of versions that are not drafts of a file document.
  */
private[core] object VersionFileStore:

  final case class Row(
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
      setBy: String,
      setAt: OffsetDateTime,
      basedOn: Option[UUID],
      wordFields: Jsonb,
      wordValues: Jsonb
  ) derives DbCodec

  // The column list is repeated in each query: Magnum binds parameters only for literal sql"" fragments.

  /** The files of all versions of a document, by version. */
  def ofDocument(document: DocumentId)(using DbTx): Map[UUID, VersionFile] =
    sql"""select l.version_id, l.file_id, l.name, f.content_type, f.size_bytes, f.sha256, f.object_key,
                 f.object_version, f.scan_status, f.scanner, l.set_by, l.set_at, l.based_on, l.word_fields,
                 l.word_values
          from dms.document_version_file l
          join dms.document_attachment_file f on f.id = l.file_id
          where f.document_id = ${document.value}"""
      .query[Row]
      .run()
      .map(toFile)
      .map(f => f.versionId -> f)
      .toMap

  /** The file of a version, if one was uploaded or taken over. */
  def ofVersion(versionId: UUID)(using DbTx): Option[VersionFile] =
    sql"""select l.version_id, l.file_id, l.name, f.content_type, f.size_bytes, f.sha256, f.object_key,
                 f.object_version, f.scan_status, f.scanner, l.set_by, l.set_at, l.based_on, l.word_fields,
                 l.word_values
          from dms.document_version_file l
          join dms.document_attachment_file f on f.id = l.file_id
          where l.version_id = $versionId"""
      .query[Row]
      .run()
      .headOption
      .map(toFile)

  /** Sets the file of a draft with its fields and manual values, replacing a previous one. */
  def set(
      versionId: UUID,
      fileId: UUID,
      name: String,
      fields: List[WordField],
      values: Map[String, String],
      by: UserId,
      basedOn: Option[UUID]
  )(using DbTx): Unit =
    val _ = sql"""insert into dms.document_version_file
                    (version_id, tenant_id, file_id, name, word_fields, word_values, set_by, based_on)
                  values ($versionId, dms.current_tenant(), $fileId, $name, ${Jsonb(fields.toJson)},
                          ${Jsonb(values.toJson)}, ${by.value}, $basedOn)
                  on conflict (version_id) do update
                  set file_id = excluded.file_id, name = excluded.name, word_fields = excluded.word_fields,
                      word_values = excluded.word_values, set_at = now(), set_by = excluded.set_by,
                      based_on = excluded.based_on""".update.run()

  /** Stores the manual values of the fields of a draft's Word file. */
  def setValues(versionId: UUID, values: Map[String, String])(using DbTx): Boolean =
    sql"""update dms.document_version_file set word_values = ${Jsonb(values.toJson)}
          where version_id = $versionId""".update.run() == 1

  /** Takes the file of the previous version over to a new draft: same file, name, fields and values, who set
    * it when; `basedOn` names the previous version.
    */
  def carryOver(file: VersionFile)(using DbTx): Unit =
    val _ = sql"""insert into dms.document_version_file
                    (version_id, tenant_id, file_id, name, word_fields, word_values, set_at, set_by, based_on)
                  values (${file.versionId}, dms.current_tenant(), ${file.fileId}, ${file.name},
                          ${Jsonb(file.fields.toJson)}, ${Jsonb(file.values.toJson)},
                          ${OffsetDateTime.ofInstant(file.setAt, ZoneOffset.UTC)}, ${file.setBy.value},
                          ${file.basedOn})""".update.run()

  /** Removes the original from a draft (DMS-129, discarding it); the guard allows it only while the version
    * is a draft. Returns the file it pointed to.
    */
  def remove(versionId: UUID)(using DbTx): Option[UUID] =
    sql"""delete from dms.document_version_file where version_id = $versionId returning file_id"""
      .query[UUID]
      .run()
      .headOption

  private def toFile(r: Row): VersionFile =
    def read[A: JsonDecoder](json: Jsonb, what: String): A =
      json.value
        .fromJson[A]
        .fold(e => throw IllegalStateException(s"cannot read $what of version ${r.versionId}: $e"), identity)
    VersionFile(
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
      UserId(r.setBy),
      r.setAt.toInstant,
      r.basedOn,
      read[List[WordField]](r.wordFields, "the Word fields"),
      read[Map[String, String]](r.wordValues, "the Word values")
    )
