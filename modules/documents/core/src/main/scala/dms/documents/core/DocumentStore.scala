package dms.documents.core

import com.augustnagro.magnum.*
import dms.db.Jsonb
import dms.documents.*
import dms.documents.core.DocumentJson.given
import dms.documents.metadata.DocumentClassId
import dms.kernel.{DocumentId, TemplateId, UserId, VersionNo}
import dms.templates.{DeviationReport, SectionId, TemplateContent}
import zio.json.*

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.UUID

/** Reads and writes the structured content and the deviation report in the stored formats of the templates
  * module.
  */
trait ContentCodec:
  def encode(content: TemplateContent): String
  def decode(json: String): Either[String, TemplateContent]
  def encodeReport(report: DeviationReport): String
  def decodeReport(json: String): Either[String, DeviationReport]

/** SQL for documents, document versions (migration V9) and deviation requests (V10). Runs inside `Db.sql`
  * within `Db.transact(tenant)`; row-level security limits all rows to the current tenant. The guard triggers
  * reject changes to versions that are not drafts and status changes outside the lifecycle.
  */
private[core] object DocumentStore:

  private type DocumentRow = (UUID, UUID, Int, String, Option[String], String, UUID, Int, OffsetDateTime)

  final case class VersionRow(
      id: UUID,
      documentId: UUID,
      major: Int,
      minor: Int,
      workingState: Int,
      status: String,
      changeKind: String,
      changeReason: Option[String],
      scope: String,
      classVersion: Int,
      templateId: UUID,
      templateVersion: Int,
      content: Jsonb,
      fieldValues: Jsonb,
      metadataSnapshot: Option[Jsonb],
      renditionKey: Option[String],
      renditionVersion: Option[String],
      renditionSha256: Option[String],
      createdBy: String,
      createdAt: OffsetDateTime,
      updatedAt: OffsetDateTime,
      releasedBy: Option[String],
      releasedAt: Option[OffsetDateTime],
      deviationReport: Jsonb
  ) derives DbCodec

  final case class DeviationRequestRow(
      id: UUID,
      versionId: UUID,
      sectionId: String,
      reason: String,
      requestedBy: String,
      requestedAt: OffsetDateTime,
      basedOn: Option[UUID],
      withdrawnBy: Option[String],
      withdrawnAt: Option[OffsetDateTime]
  ) derives DbCodec

  def document(id: DocumentId)(using DbTx): Option[Document] =
    sql"""select id, class_id, class_version, title, owner_id, author_id, template_id, template_version, created_at
          from dms.document where id = ${id.value}""".query[DocumentRow].run().headOption.map(toDocument)

  def insertDocument(d: Document)(using DbTx): Unit =
    val _ =
      sql"""insert into dms.document (id, tenant_id, class_id, class_version, title, owner_id, author_id,
                                            template_id, template_version)
                  values (${d.id.value}, dms.current_tenant(), ${d.classId.value}, ${d.classVersion}, ${d.title},
                          ${d.ownerId.map(_.value)}, ${d.authorId.value}, ${d.templateId.value},
                          ${d.templateVersion})""".update.run()

  def updateDocument(id: DocumentId, title: String, owner: Option[UserId])(using DbTx): Unit =
    val _ = sql"""update dms.document set title = $title, owner_id = ${owner.map(_.value)}, updated_at = now()
                  where id = ${id.value}""".update.run()

  // The column list is repeated in each query: Magnum binds parameters only for literal sql"" fragments.

  /** All versions of a document, by number and creation. */
  def versions(id: DocumentId, codec: ContentCodec)(using DbTx): List[DocumentVersion] =
    sql"""select id, document_id, major, minor, working_state, status, change_kind, change_reason, scope,
                 class_version, template_id, template_version, content, field_values, metadata_snapshot,
                 rendition_key, rendition_version, rendition_sha256, created_by, created_at, updated_at,
                 released_by, released_at, deviation_report
          from dms.document_version where document_id = ${id.value} order by major, minor, created_at"""
      .query[VersionRow]
      .run()
      .map(toVersion(_, codec))
      .toList

  /** A version, locked for a change. */
  def lockVersion(id: UUID, codec: ContentCodec)(using DbTx): Option[DocumentVersion] =
    sql"""select id, document_id, major, minor, working_state, status, change_kind, change_reason, scope,
                 class_version, template_id, template_version, content, field_values, metadata_snapshot,
                 rendition_key, rendition_version, rendition_sha256, created_by, created_at, updated_at,
                 released_by, released_at, deviation_report
          from dms.document_version where id = $id for update"""
      .query[VersionRow]
      .run()
      .headOption
      .map(toVersion(_, codec))

  /** The open version of a document, locked for a change. */
  def lockOpenVersion(id: DocumentId, codec: ContentCodec)(using DbTx): Option[DocumentVersion] =
    sql"""select id, document_id, major, minor, working_state, status, change_kind, change_reason, scope,
                 class_version, template_id, template_version, content, field_values, metadata_snapshot,
                 rendition_key, rendition_version, rendition_sha256, created_by, created_at, updated_at,
                 released_by, released_at, deviation_report
          from dms.document_version
          where document_id = ${id.value} and status in ('Draft', 'InReview', 'InApproval') for update"""
      .query[VersionRow]
      .run()
      .headOption
      .map(toVersion(_, codec))

  def insertVersion(v: DocumentVersion, codec: ContentCodec)(using DbTx): Unit =
    val _ = sql"""insert into dms.document_version
                    (id, tenant_id, document_id, major, minor, working_state, status, change_kind, change_reason,
                     scope, class_version, template_id, template_version, content, field_values, created_by,
                     status_changed_by, deviation_report)
                  values (${v.id}, dms.current_tenant(), ${v.documentId.value}, ${v.number.major},
                          ${v.number.minor}, ${v.workingState}, ${v.status.toString}, ${v.changeKind.toString},
                          ${v.changeReason}, ${v.scope}, ${v.classVersion}, ${v.templateId.value},
                          ${v.templateVersion}, ${Jsonb(codec.encode(v.content))}, ${Jsonb(v.values.toJson)},
                          ${v.createdBy.value}, ${v.createdBy.value},
                          ${Jsonb(codec.encodeReport(v.deviations))})""".update.run()

  /** Saves a changed draft with its next working state; a stale rendition is dropped. */
  def updateDraft(v: DocumentVersion, codec: ContentCodec)(using DbTx): Boolean =
    sql"""update dms.document_version
          set content = ${Jsonb(codec.encode(v.content))}, field_values = ${Jsonb(v.values.toJson)},
              working_state = ${v.workingState}, scope = ${v.scope}, rendition_key = null,
              rendition_version = null, rendition_sha256 = null,
              deviation_report = ${Jsonb(codec.encodeReport(v.deviations))}, updated_at = now()
          where id = ${v.id} and status = 'Draft'""".update.run() == 1

  /** Stores the deviation report of a draft, e.g. after a deviation request. */
  def updateDeviationReport(id: UUID, report: DeviationReport, codec: ContentCodec)(using DbTx): Boolean =
    sql"""update dms.document_version
          set deviation_report = ${Jsonb(codec.encodeReport(report))}, updated_at = now()
          where id = $id and status = 'Draft'""".update.run() == 1

  /** All deviation requests of a version, active and withdrawn, oldest first. */
  def deviationRequests(versionId: UUID)(using DbTx): List[RecordedDeviationRequest] =
    sql"""select id, version_id, section_id, reason, requested_by, requested_at, based_on, withdrawn_by,
                 withdrawn_at
          from dms.document_deviation_request where version_id = $versionId order by requested_at, id"""
      .query[DeviationRequestRow]
      .run()
      .map(toDeviationRequest)
      .toList

  def insertDeviationRequest(r: RecordedDeviationRequest)(using DbTx): Unit =
    val _ = sql"""insert into dms.document_deviation_request
                    (id, tenant_id, version_id, section_id, reason, requested_by, requested_at, based_on)
                  values (${r.id}, dms.current_tenant(), ${r.versionId}, ${r.sectionId.value}, ${r.reason},
                          ${r.requestedBy.value}, ${OffsetDateTime.ofInstant(r.requestedAt, ZoneOffset.UTC)},
                          ${r.basedOn})""".update.run()

  def withdrawDeviationRequest(id: UUID, by: UserId, at: Instant)(using DbTx): Boolean =
    sql"""update dms.document_deviation_request
          set withdrawn_by = ${by.value}, withdrawn_at = ${OffsetDateTime.ofInstant(at, ZoneOffset.UTC)}
          where id = $id and withdrawn_at is null""".update.run() == 1

  def insertWorkingState(v: DocumentVersion, savedBy: UserId, codec: ContentCodec)(using DbTx): Unit =
    val _ = sql"""insert into dms.document_working_state
                    (tenant_id, version_id, working_state, content, field_values, saved_by)
                  values (dms.current_tenant(), ${v.id}, ${v.workingState}, ${Jsonb(codec.encode(v.content))},
                          ${Jsonb(v.values.toJson)}, ${savedBy.value})""".update.run()

  /** The highest working state of earlier (withdrawn) versions with the same number, for continuous counting.
    */
  def workingStates(document: DocumentId, number: VersionNo)(using DbTx): List[Int] =
    sql"""select working_state from dms.document_version
          where document_id = ${document.value} and major = ${number.major} and minor = ${number.minor}"""
      .query[Int]
      .run()
      .toList

  def changeStatus(
      id: UUID,
      from: DocumentStatus,
      to: DocumentStatus,
      actor: UserId,
      at: Instant,
      snapshot: Option[MetadataSnapshot]
  )(using DbTx): Boolean =
    val time = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
    snapshot match
      case Some(s) =>
        sql"""update dms.document_version
              set status = ${to.toString}, status_changed_at = $time, status_changed_by = ${actor.value},
                  metadata_snapshot = ${Jsonb(s.toJson)}, released_by = ${actor.value}, released_at = $time,
                  updated_at = $time
              where id = $id and status = ${from.toString}""".update.run() == 1
      case None =>
        sql"""update dms.document_version
              set status = ${to.toString}, status_changed_at = $time, status_changed_by = ${actor.value},
                  updated_at = $time
              where id = $id and status = ${from.toString}""".update.run() == 1

  def attachRendition(id: UUID, r: Rendition)(using DbTx): Boolean =
    sql"""update dms.document_version
          set rendition_key = ${r.objectKey}, rendition_version = ${r.objectVersion},
              rendition_sha256 = ${r.sha256}, updated_at = now()
          where id = $id and status in ('Draft', 'InReview', 'InApproval')""".update.run() == 1

  private def toDocument(row: DocumentRow): Document =
    val (id, classId, classVersion, title, owner, author, templateId, templateVersion, createdAt) = row
    Document(
      DocumentId(id),
      DocumentClassId(classId),
      classVersion,
      title,
      owner.map(UserId(_)),
      UserId(author),
      TemplateId(templateId),
      templateVersion,
      createdAt.toInstant
    )

  private def toVersion(r: VersionRow, codec: ContentCodec): DocumentVersion =
    def read[A: JsonDecoder](json: Jsonb, what: String): A =
      json.value
        .fromJson[A]
        .fold(e => throw IllegalStateException(s"cannot read $what of version ${r.id}: $e"), identity)
    DocumentVersion(
      r.id,
      DocumentId(r.documentId),
      VersionNo(r.major, r.minor),
      r.workingState,
      DocumentStatus.valueOf(r.status),
      ChangeKind.valueOf(r.changeKind),
      r.changeReason,
      r.scope,
      r.classVersion,
      TemplateId(r.templateId),
      r.templateVersion,
      codec
        .decode(r.content.value)
        .fold(e => throw IllegalStateException(s"cannot read the content of version ${r.id}: $e"), identity),
      read[Map[String, FieldEntry]](r.fieldValues, "field values"),
      r.metadataSnapshot.map(read[MetadataSnapshot](_, "metadata snapshot")),
      r.renditionKey.zip(r.renditionSha256).map((key, sha) => Rendition(key, r.renditionVersion, sha)),
      UserId(r.createdBy),
      r.createdAt.toInstant,
      r.updatedAt.toInstant,
      r.releasedBy.map(UserId(_)),
      r.releasedAt.map(_.toInstant),
      codec
        .decodeReport(r.deviationReport.value)
        .fold(
          e => throw IllegalStateException(s"cannot read the deviation report of version ${r.id}: $e"),
          identity
        )
    )

  private def toDeviationRequest(r: DeviationRequestRow): RecordedDeviationRequest =
    RecordedDeviationRequest(
      r.id,
      r.versionId,
      SectionId(r.sectionId),
      r.reason,
      UserId(r.requestedBy),
      r.requestedAt.toInstant,
      r.basedOn,
      r.withdrawnBy.map(UserId(_)),
      r.withdrawnAt.map(_.toInstant)
    )
