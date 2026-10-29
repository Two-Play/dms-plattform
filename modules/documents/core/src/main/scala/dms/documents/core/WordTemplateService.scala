package dms.documents.core

import com.augustnagro.magnum.*
import dms.audit.*
import dms.db.{Db, Jsonb}
import dms.documents.core.DocumentJson.given
import dms.documents.{ScanStatus, WordTemplateFile}
import dms.kernel.{TemplateId, UserId}
import dms.templates.WordField
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.OffsetDateTime
import java.util.UUID

/** Word templates (DMS-115, ADR 0042): the Word file of a template version and its field definitions
  * (migration V27). A template version with a Word file is a Word template; its lifecycle (test, four-eyes
  * release) is that of every template. The file and the definitions change only while the version is a draft
  * (database guard); whoever changes them counts as an editor of the version, so they cannot release it.
  *
  * Every method must run inside `Db.transact(tenant)`. Authorization is the caller's job.
  */
final class WordTemplateService(audit: AuditTrail):
  import WordTemplateService.*

  /** The Word file of a template version, if it is a Word template. */
  def get(template: TemplateId, version: Int): IO[DocumentError, Option[WordTemplateFile]] =
    sql(Store.get(template.value, version))

  /** The template versions of the tenant that are Word templates. */
  def wordVersions: IO[DocumentError, Set[(UUID, Int)]] = sql(Store.versions)

  /** Sets (or replaces) the Word file of a draft template version with the definitions of its fields. */
  def set(file: WordTemplateFile, actor: UserId): IO[DocumentError, WordTemplateFile] =
    for
      previous <- lockDraft(file.templateId, file.version)
      _        <- sql {
        Store.upsert(file)
        Store.addEditor(file.templateId, file.version, actor)
      }
      _ <- record(
        actor,
        "template.word-file-uploaded",
        file,
        Json.Obj(
          "name"           -> Json.Str(file.name),
          "sha256"         -> Json.Str(file.sha256),
          "size"           -> Json.Num(file.size),
          "fields"         -> Json.Arr(file.fields.map(f => Json.Str(f.key))*),
          "previousSha256" -> previous.fold(Json.Null)(p => Json.Str(p.sha256))
        )
      )
    yield file

  /** Changes the definitions of the fields of a draft's Word file (label, type, required, source). The keys
    * are those found in the file; they change only with a new file.
    */
  def setFields(
      template: TemplateId,
      version: Int,
      fields: List[WordField],
      actor: UserId
  ): IO[DocumentError, WordTemplateFile] =
    for
      current <- lockDraft(template.value, version).someOrFail(
        DocumentError.NotFound(s"template ${template.value} version $version has no Word file")
      )
      _ <- ZIO.unless(fields.map(_.key).sorted == current.fields.map(_.key).sorted)(
        ZIO.fail(
          DocumentError.Invalid(
            List("the field keys are those of the Word file: " + current.fields.map(_.key).mkString(", "))
          )
        )
      )
      _ <- ZIO.foreachDiscard(fields.find(_.label.trim.isEmpty))(f =>
        ZIO.fail(DocumentError.Invalid(List(s"${f.key}: the label must not be empty")))
      )
      ordered = current.fields.flatMap(c => fields.find(_.key == c.key))
      updated = current.copy(fields = ordered, setBy = actor)
      _ <- sql {
        Store.upsert(updated)
        Store.addEditor(template.value, version, actor)
      }
      _ <- record(
        actor,
        "template.word-fields-changed",
        updated,
        Json.Obj("fields" -> Json.Arr(ordered.map(f => Json.Str(s"${f.key}:${f.source}:${f.fieldType}"))*))
      )
    yield updated

  /** Takes the Word file of `from` over to the new draft `to` of the same template (no bytes are copied). */
  def carryOver(template: TemplateId, from: Int, to: Int, actor: UserId): IO[DocumentError, Unit] =
    sql(Store.get(template.value, from)).flatMap {
      case None       => ZIO.unit
      case Some(file) => sql(Store.upsert(file.copy(version = to, setBy = actor))).unit
    }

  /** The Word file of a draft version, locked; fails unless the version is a draft. */
  private def lockDraft(template: UUID, version: Int): IO[DocumentError, Option[WordTemplateFile]] =
    sql(
      sql"""select status from dms.template_version where template_id = $template and version = $version
            for update""".query[String].run().headOption
    ).flatMap {
      case Some("Draft") => sql(Store.get(template, version))
      case Some(status)  =>
        ZIO.fail(
          DocumentError.Conflict(
            s"template version $version is $status; its Word file changes only as a draft"
          )
        )
      case None => ZIO.fail(DocumentError.NotFound(s"template $template has no version $version"))
    }

  private def record(actor: UserId, action: String, file: WordTemplateFile, details: Json.Obj) =
    audit
      .record(
        AuditEvent(
          Actor(actor.value, ActorKind.Person),
          action,
          AuditObject("template", Some(file.templateId), Some(file.version.toString)),
          Channel.Api,
          Jsonb(details.toJson)
        )
      )
      .mapError(DocumentError.Database(_))
      .unit

object WordTemplateService:
  val layer: URLayer[AuditTrail, WordTemplateService] = ZLayer.fromFunction(WordTemplateService(_))

  /** Object key of a template's Word file: one object per upload, below the tenant and the template. */
  def objectKey(tenant: dms.kernel.TenantId, template: UUID, fileId: UUID): String =
    s"templates/${tenant.value}/$template/$fileId.docx"

  private def sql[A](f: DbTx ?=> A): IO[DocumentError, A] = Db.sql(f).mapError(DocumentError.Database(_))

  private object Store:
    final case class Row(
        templateId: UUID,
        version: Int,
        name: String,
        sha256: String,
        sizeBytes: Long,
        objectKey: String,
        objectVersion: String,
        scanStatus: String,
        fields: Jsonb,
        setBy: String,
        setAt: OffsetDateTime
    ) derives DbCodec

    def get(template: UUID, version: Int)(using DbTx): Option[WordTemplateFile] =
      sql"""select template_id, version, name, sha256, size_bytes, object_key, object_version, scan_status, fields,
                   set_by, set_at
            from dms.template_word_file where template_id = $template and version = $version"""
        .query[Row]
        .run()
        .headOption
        .map(toFile)

    def versions(using DbTx): Set[(UUID, Int)] =
      sql"select template_id, version from dms.template_word_file".query[(UUID, Int)].run().toSet

    def upsert(f: WordTemplateFile)(using DbTx): Unit =
      val _ = sql"""insert into dms.template_word_file
                      (template_id, version, tenant_id, name, sha256, size_bytes, object_key, object_version,
                       scan_status, fields, set_by)
                    values (${f.templateId}, ${f.version}, dms.current_tenant(), ${f.name}, ${f.sha256}, ${f.size},
                            ${f.objectKey}, ${f.objectVersion}, ${f.scanStatus.toString}, ${Jsonb(
          f.fields.toJson
        )},
                            ${f.setBy.value})
                    on conflict (template_id, version) do update
                    set name = excluded.name, sha256 = excluded.sha256, size_bytes = excluded.size_bytes,
                        object_key = excluded.object_key, object_version = excluded.object_version,
                        scan_status = excluded.scan_status, fields = excluded.fields, set_at = now(),
                        set_by = excluded.set_by""".update.run()

    /** Records `editor` as editor of the draft (four-eyes release, V8), like a content change does. */
    def addEditor(template: UUID, version: Int, editor: UserId)(using DbTx): Unit =
      val _ = sql"""update dms.template_version
                    set edited_by = case when created_by = ${editor.value} or ${editor.value} = any (edited_by)
                                         then edited_by else array_append(edited_by, ${editor.value}) end,
                        updated_at = now()
                    where template_id = $template and version = $version and status = 'Draft'""".update.run()

    private def toFile(r: Row): WordTemplateFile =
      WordTemplateFile(
        r.templateId,
        r.version,
        r.name,
        r.sha256,
        r.sizeBytes,
        r.objectKey,
        r.objectVersion,
        ScanStatus.valueOf(r.scanStatus),
        r.fields.value
          .fromJson[List[WordField]]
          .fold(
            e => throw IllegalStateException(s"cannot read the fields of template ${r.templateId}: $e"),
            identity
          ),
        UserId(r.setBy),
        r.setAt.toInstant
      )
