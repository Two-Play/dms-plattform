package dms.templates.core

import com.augustnagro.magnum.*
import dms.db.Jsonb
import dms.kernel.{TemplateId, UserId}
import dms.templates.*
import zio.json.*

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.UUID

/** SQL for templates (migration V8). Runs inside `Db.sql` within `Db.transact(tenant)`; row-level security
  * limits all rows to the current tenant. The guard trigger rejects changes to versions that are not drafts.
  */
private[core] object TemplateStore:

  private type Row = (
      UUID,
      Int,
      String,
      Option[String],
      UUID,
      Int,
      String,
      Jsonb,
      String,
      OffsetDateTime,
      Jsonb,
      Option[String],
      Option[OffsetDateTime]
  )

  // The column list is repeated in each query: Magnum binds parameters only for literal sql"" fragments.

  def versions(id: TemplateId)(using DbTx): List[TemplateVersion] =
    sql"""select template_id, version, name, description, document_class_id, document_class_version, status, content,
                 created_by, created_at, to_jsonb(edited_by), released_by, released_at
          from dms.template_version where template_id = ${id.value} order by version"""
      .query[Row]
      .run()
      .map(toVersion)
      .toList

  def version(id: TemplateId, version: Int, forUpdate: Boolean = false)(using DbTx): Option[TemplateVersion] =
    val query =
      if forUpdate then
        sql"""select template_id, version, name, description, document_class_id, document_class_version, status, content,
                 created_by, created_at, to_jsonb(edited_by), released_by, released_at
              from dms.template_version where template_id = ${id.value} and version = $version for update"""
      else
        sql"""select template_id, version, name, description, document_class_id, document_class_version, status, content,
                 created_by, created_at, to_jsonb(edited_by), released_by, released_at
              from dms.template_version where template_id = ${id.value} and version = $version"""
    query.query[Row].run().headOption.map(toVersion)

  /** The latest version of every template, without the upload templates of classes (V31). */
  def latestVersions()(using DbTx): List[TemplateVersion] =
    sql"""select distinct on (template_id) template_id, version, name, description, document_class_id, document_class_version, status, content,
                 created_by, created_at, to_jsonb(edited_by), released_by, released_at
          from dms.template_version v
          where not exists (select 1 from dms.template t
                            where t.id = v.template_id and t.file_upload_class_id is not null)
          order by template_id, version desc"""
      .query[Row]
      .run()
      .map(toVersion)
      .toList

  /** All released versions, by name and version, without the upload templates of classes (V31). */
  def released()(using DbTx): List[TemplateVersion] =
    sql"""select template_id, version, name, description, document_class_id, document_class_version, status, content,
                 created_by, created_at, to_jsonb(edited_by), released_by, released_at
          from dms.template_version v
          where status = 'Released'
            and not exists (select 1 from dms.template t
                            where t.id = v.template_id and t.file_upload_class_id is not null)
          order by name, template_id, version"""
      .query[Row]
      .run()
      .map(toVersion)
      .toList

  /** The upload template of a class (V31, DMS-127), if it exists. */
  def fileUploadTemplate(classId: UUID)(using DbTx): Option[TemplateId] =
    sql"""select id from dms.template where file_upload_class_id = $classId"""
      .query[UUID]
      .run()
      .headOption
      .map(TemplateId(_))

  /** Inserts the upload template of a class unless another transaction did; whether it was inserted. */
  def insertFileUploadTemplate(id: TemplateId, classId: UUID, createdBy: UserId)(using DbTx): Boolean =
    sql"""insert into dms.template (id, tenant_id, created_by, file_upload_class_id)
          values (${id.value}, dms.current_tenant(), ${createdBy.value}, $classId)
          on conflict (tenant_id, file_upload_class_id) where file_upload_class_id is not null
          do nothing""".update.run() == 1

  /** Inserts a version that is released at once (upload templates, V31); whether it was inserted. */
  def insertReleasedVersion(v: TemplateVersion, releasedBy: UserId, at: Instant)(using DbTx): Boolean =
    val time = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
    sql"""insert into dms.template_version
            (tenant_id, template_id, version, name, description, document_class_id, document_class_version,
             status, content, content_format, created_by, status_changed_by, status_changed_at, released_by,
             released_at)
          values (dms.current_tenant(), ${v.templateId.value}, ${v.version}, ${v.name}, ${v.description},
                  ${v.documentClassId}, ${v.documentClassVersion}, 'Released',
                  ${Jsonb(ContentJson.encodeStored(v.content))}, ${ContentJson.FormatVersion},
                  ${v.createdBy.value}, ${releasedBy.value}, $time, ${releasedBy.value}, $time)
          on conflict (template_id, version) do nothing""".update.run() == 1

  def insertTemplate(id: TemplateId, createdBy: UserId)(using DbTx): Unit =
    val _ = sql"""insert into dms.template (id, tenant_id, created_by)
                  values (${id.value}, dms.current_tenant(), ${createdBy.value})""".update.run()

  def insertVersion(v: TemplateVersion)(using DbTx): Unit =
    val _ = sql"""insert into dms.template_version
                    (tenant_id, template_id, version, name, description, document_class_id, document_class_version,
                     status, content, content_format, created_by, status_changed_by)
                  values (dms.current_tenant(), ${v.templateId.value}, ${v.version}, ${v.name}, ${v.description},
                          ${v.documentClassId}, ${v.documentClassVersion}, ${v.status.toString},
                          ${Jsonb(ContentJson.encodeStored(v.content))}, ${ContentJson.FormatVersion},
                          ${v.createdBy.value}, ${v.createdBy.value})""".update.run()

  /** Changes a draft; records `editor` as contributor unless they are already one. */
  def updateDraft(v: TemplateVersion, editor: UserId)(using DbTx): Boolean =
    sql"""update dms.template_version
          set name = ${v.name}, description = ${v.description}, document_class_id = ${v.documentClassId},
              document_class_version = ${v.documentClassVersion},
              content = ${Jsonb(
        ContentJson.encodeStored(v.content)
      )}, content_format = ${ContentJson.FormatVersion},
              edited_by = case when created_by = ${editor.value} or ${editor.value} = any (edited_by) then edited_by
                               else array_append(edited_by, ${editor.value}) end,
              updated_at = now()
          where template_id = ${v.templateId.value} and version = ${v.version} and status = 'Draft'""".update
      .run() == 1

  def changeStatus(
      id: TemplateId,
      version: Int,
      from: TemplateStatus,
      to: TemplateStatus,
      actor: UserId,
      at: Instant
  )(using DbTx): Boolean =
    val time = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
    if to == TemplateStatus.Released then
      sql"""update dms.template_version
            set status = ${to.toString}, status_changed_at = $time, status_changed_by = ${actor.value},
                released_by = ${actor.value}, released_at = $time, updated_at = $time
            where template_id = ${id.value} and version = $version and status = ${from.toString}""".update
        .run() == 1
    else
      sql"""update dms.template_version
            set status = ${to.toString}, status_changed_at = $time, status_changed_by = ${actor.value},
                updated_at = $time
            where template_id = ${id.value} and version = $version and status = ${from.toString}""".update
        .run() == 1

  private def toVersion(row: Row): TemplateVersion =
    val (
      id,
      version,
      name,
      description,
      classId,
      classVersion,
      status,
      content,
      createdBy,
      createdAt,
      editedBy,
      releasedBy,
      releasedAt
    ) = row
    TemplateVersion(
      TemplateId(id),
      version,
      name,
      description,
      classId,
      classVersion,
      TemplateStatus.valueOf(status),
      ContentJson
        .decodeStored(content.value)
        .fold(e => throw IllegalStateException(s"content of template $id version $version: $e"), identity),
      UserId(createdBy),
      createdAt.toInstant,
      releasedBy.map(UserId(_)),
      releasedAt.map(_.toInstant),
      editedBy.value
        .fromJson[List[String]]
        .fold(e => throw IllegalStateException(s"edited_by of template $id: $e"), _.map(UserId(_)))
    )
