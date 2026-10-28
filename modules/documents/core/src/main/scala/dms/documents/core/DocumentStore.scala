package dms.documents.core

import com.augustnagro.magnum.*
import dms.db.Jsonb
import dms.documents.*
import dms.documents.core.DocumentJson.given
import dms.documents.metadata.DocumentClassId
import dms.kernel.{DocumentId, TemplateId, UserId, VersionNo}
import dms.templates.{DeviationReport, SectionId, TemplateContent}
import zio.json.*

import java.time.{Instant, LocalDate, OffsetDateTime, ZoneId, ZoneOffset}
import java.util.UUID

/** Reads and writes the structured content and the deviation report in the stored formats of the templates
  * module.
  */
trait ContentCodec:
  def encode(content: TemplateContent): String
  def decode(json: String): Either[String, TemplateContent]
  def encodeReport(report: DeviationReport): String
  def decodeReport(json: String): Either[String, DeviationReport]

/** SQL for documents, document versions (migrations V9, V12) and deviation requests (V10). Runs inside
  * `Db.sql` within `Db.transact(tenant)`; row-level security limits all rows to the current tenant. The guard
  * triggers reject changes to versions that are not drafts and status changes outside the lifecycle.
  */
private[core] object DocumentStore:

  /** PostgreSQL `date` columns (valid-from date, migration V12). */
  private given DbCodec[LocalDate] =
    // Null-safe: the Option codec reads the value first and checks for SQL NULL afterwards.
    DbCodec[java.sql.Date].biMap(d => if d == null then null else d.toLocalDate, java.sql.Date.valueOf)

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
      deviationReport: Jsonb,
      validFrom: Option[LocalDate],
      supersededBy: Option[UUID],
      requiresAcknowledgement: Boolean,
      publishedAt: Option[OffsetDateTime]
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

  /** The documents of the tenant, the most recently changed first (by their latest version). */
  def recentDocuments(limit: Int)(using DbTx): List[Document] =
    sql"""select d.id, d.class_id, d.class_version, d.title, d.owner_id, d.author_id, d.template_id,
                 d.template_version, d.created_at
          from dms.document d
          order by greatest(d.updated_at,
                            (select max(v.updated_at) from dms.document_version v where v.document_id = d.id))
                   desc, d.id
          limit $limit""".query[DocumentRow].run().map(toDocument).toList

  /** Ids of the documents changed at or after `since`: the document row or one of its versions (DMS-51, for
    * the search index); without `since` every document that has a released version.
    */
  def changedDocumentIds(since: Option[Instant])(using DbTx): List[DocumentId] =
    since
      .fold(
        sql"""select d.id from dms.document d
              where exists (select 1 from dms.document_version v
                            where v.document_id = d.id and v.released_at is not null)
              order by d.id""".query[UUID].run()
      ) { at =>
        val time = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
        sql"""select d.id from dms.document d
              where d.updated_at >= $time
                 or exists (select 1 from dms.document_version v
                            where v.document_id = d.id and v.updated_at >= $time)
              order by d.id""".query[UUID].run()
      }
      .map(DocumentId(_))
      .toList

  /** Document versions on the versions of `template` before `before`, per template version and status and per
    * status alone (template version `None`): the number of versions and of distinct documents.
    */
  def templateUsageCounts(template: TemplateId, before: Int)(using
      DbTx
  ): List[(Option[Int], String, Long, Long)] =
    sql"""select template_version, status, count(*), count(distinct document_id)
          from dms.document_version
          where template_id = ${template.value} and template_version < $before
          group by grouping sets ((template_version, status), (status))"""
      .query[(Option[Int], String, Long, Long)]
      .run()
      .toList

  /** Distinct documents with a version on a version of `template` before `before`, per template version and
    * in total (template version `None`).
    */
  def templateUsageDocuments(template: TemplateId, before: Int)(using DbTx): List[(Option[Int], Long)] =
    sql"""select template_version, count(distinct document_id) from dms.document_version
          where template_id = ${template.value} and template_version < $before
          group by grouping sets ((template_version), ())"""
      .query[(Option[Int], Long)]
      .run()
      .toList

  private type OpenUseRow = (UUID, UUID, String, String, Int, Int, Int, String, Int, OffsetDateTime)

  /** Open versions on a version of `template` before `before` with the document title, the most recently
    * changed first.
    */
  def openTemplateUses(template: TemplateId, before: Int)(using DbTx): List[OpenTemplateUse] =
    sql"""select v.document_id, v.id, d.title, v.created_by, v.major, v.minor, v.working_state, v.status,
                 v.template_version, v.updated_at
          from dms.document_version v join dms.document d on d.id = v.document_id
          where v.template_id = ${template.value} and v.template_version < $before
            and v.status in ('Draft', 'InReview', 'InApproval')
          order by v.updated_at desc, v.id"""
      .query[OpenUseRow]
      .run()
      .map { case (documentId, id, title, author, major, minor, workingState, status, templateVersion, at) =>
        val s = DocumentStatus.valueOf(status)
        OpenTemplateUse(
          DocumentId(documentId),
          id,
          title,
          UserId(author),
          Versioning.label(VersionNo(major, minor), s, workingState),
          s,
          templateVersion,
          at.toInstant
        )
      }
      .toList

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
                 released_by, released_at, deviation_report, valid_from, superseded_by, requires_acknowledgement,
                 published_at
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
                 released_by, released_at, deviation_report, valid_from, superseded_by, requires_acknowledgement,
                 published_at
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
                 released_by, released_at, deviation_report, valid_from, superseded_by, requires_acknowledgement,
                 published_at
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
                     status_changed_by, deviation_report, requires_acknowledgement)
                  values (${v.id}, dms.current_tenant(), ${v.documentId.value}, ${v.number.major},
                          ${v.number.minor}, ${v.workingState}, ${v.status.toString}, ${v.changeKind.toString},
                          ${v.changeReason}, ${v.scope}, ${v.classVersion}, ${v.templateId.value},
                          ${v.templateVersion}, ${Jsonb(codec.encode(v.content))}, ${Jsonb(v.values.toJson)},
                          ${v.createdBy.value}, ${v.createdBy.value},
                          ${Jsonb(codec.encodeReport(v.deviations))},
                          ${v.requiresAcknowledgement})""".update.run()

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

  /** Changes the status of a version. On approval (`snapshot`) the metadata snapshot, the approval and the
    * valid-from date are recorded; on supersession the successor (`supersededBy`).
    */
  def changeStatus(
      id: UUID,
      from: DocumentStatus,
      to: DocumentStatus,
      actor: UserId,
      at: Instant,
      snapshot: Option[MetadataSnapshot],
      validFrom: Option[LocalDate] = None,
      supersededBy: Option[UUID] = None
  )(using DbTx): Boolean =
    val time = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
    snapshot match
      case Some(s) =>
        sql"""update dms.document_version
              set status = ${to.toString}, status_changed_at = $time, status_changed_by = ${actor.value},
                  metadata_snapshot = ${Jsonb(s.toJson)}, released_by = ${actor.value}, released_at = $time,
                  valid_from = $validFrom, updated_at = $time
              where id = $id and status = ${from.toString}""".update.run() == 1
      case None =>
        supersededBy match
          case Some(successor) =>
            sql"""update dms.document_version
                  set status = ${to.toString}, status_changed_at = $time, status_changed_by = ${actor.value},
                      superseded_by = $successor, updated_at = $time
                  where id = $id and status = ${from.toString}""".update.run() == 1
          case None =>
            sql"""update dms.document_version
                  set status = ${to.toString}, status_changed_at = $time, status_changed_by = ${actor.value},
                      updated_at = $time
                  where id = $id and status = ${from.toString}""".update.run() == 1

  /** The time zone of the current tenant (migration V12); scheduled publications switch at 00:00 there. */
  def tenantZone(using DbTx): ZoneId =
    sql"select time_zone from dms.tenant where id = dms.current_tenant()"
      .query[String]
      .run()
      .headOption
      .map(ZoneId.of)
      .getOrElse(Publication.DefaultZone)

  /** Approved versions waiting for publication, with their document and valid-from date, earliest first. */
  def scheduled(using DbTx): List[(UUID, UUID, Option[LocalDate])] =
    sql"""select id, document_id, valid_from from dms.document_version
          where status = 'Approved' order by valid_from nulls first, id"""
      .query[(UUID, UUID, Option[LocalDate])]
      .run()
      .toList

  /** A version that was in force at some time, with its document and what bounds its validity period: its own
    * valid-from date and publication and those of its successor (DMS-53).
    */
  final case class ValidityRow(
      documentId: UUID,
      classId: UUID,
      classVersion: Int,
      title: String,
      ownerId: Option[String],
      authorId: String,
      templateId: UUID,
      templateVersion: Int,
      documentCreatedAt: OffsetDateTime,
      versionId: UUID,
      major: Int,
      minor: Int,
      status: String,
      scope: String,
      fieldValues: Jsonb,
      createdBy: String,
      releasedBy: Option[String],
      releasedAt: Option[OffsetDateTime],
      validFrom: Option[LocalDate],
      publishedAt: OffsetDateTime,
      successorValidFrom: Option[LocalDate],
      successorPublishedAt: Option[OffsetDateTime]
  ) derives DbCodec

  /** Candidates for the versions in force at `at`: published before `at` and not replaced by a successor that
    * was published before `at` and whose valid-from date lies more than a day before `at` (any time zone).
    * These are necessary conditions only; the exact period is decided with [[Validity]] by the caller, so
    * that the rule exists once. Destroyed versions and versions that were never published are left out.
    */
  def validityCandidates(at: Instant)(using DbTx): List[ValidityRow] =
    val time     = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
    val dayFloor = time.toLocalDate.minusDays(1)
    sql"""select d.id, d.class_id, d.class_version, d.title, d.owner_id, d.author_id, d.template_id,
                 d.template_version, d.created_at, v.id, v.major, v.minor, v.status, v.scope, v.field_values,
                 v.created_by, v.released_by, v.released_at, v.valid_from, v.published_at, s.valid_from,
                 s.published_at
          from dms.document_version v
          join dms.document d on d.id = v.document_id
          left join dms.document_version s on s.id = v.superseded_by
          where v.status in ('Published', 'ReviewDue', 'Superseded', 'Archived')
            and v.published_at is not null
            and v.published_at <= $time
            and (s.id is null or s.published_at > $time or s.valid_from >= $dayFloor)
          order by d.title, d.id, v.scope, v.major, v.minor"""
      .query[ValidityRow]
      .run()
      .toList

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
        ),
      r.validFrom,
      r.supersededBy,
      r.requiresAcknowledgement,
      r.publishedAt.map(_.toInstant)
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
