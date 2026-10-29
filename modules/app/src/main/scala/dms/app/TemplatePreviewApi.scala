package dms.app

import dms.authz.{Action, Resource}
import dms.documents.core.MetadataJson.given
import dms.documents.metadata.{FieldType, MetadataValue}
import dms.identity.Principal
import dms.kernel.TemplateId
import dms.templates.Language
import dms.templates.core.*
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.util.UUID

import MetadataSchemas.given

/** A field of the form for a document created from a template. */
final case class TemplateFieldView(
    /** Stable field key, used as key of `values` in the preview request. */
    key: String,
    label: String,
    description: Option[String],
    /** Type with its rules; the form validates against them. */
    fieldType: FieldType,
    multiple: Boolean,
    /** Needed before the document can be approved. */
    required: Boolean,
    /** The template refers to the field; other fields are document metadata only. */
    usedInTemplate: Boolean,
    /** Set when the field is filled by a reference field: the author picks an entry instead of typing. */
    reference: Option[FieldReferenceView]
) derives JsonCodec,
      Schema

/** The field takes over `attribute` of the entry picked for reference field `reference`. */
final case class FieldReferenceView(reference: String, attribute: String) derives JsonCodec, Schema

final case class ReferenceMappingView(attribute: String, field: String) derives JsonCodec, Schema

/** A reference field of the template (ADR 0023): pick an entry of `source`, e.g. a partner via
  * `GET /api/v1/partners`, and its attributes are taken over into `fields`.
  */
final case class ReferenceFieldView(
    key: String,
    label: String,
    /** Master data source, e.g. `partner-master`. */
    source: String,
    fields: List[ReferenceMappingView]
) derives JsonCodec,
      Schema

final case class TemplateFieldsView(
    templateId: UUID,
    version: Int,
    name: String,
    description: Option[String],
    documentClassId: UUID,
    documentClassVersion: Int,
    /** Fields the template uses first, then the other editable fields of the class profile. */
    fields: List[TemplateFieldView],
    /** Reference fields; the fields they fill are taken over, not entered. */
    references: List[ReferenceFieldView]
) derives JsonCodec,
      Schema

final case class PreviewRequest(
    /** Values entered so far, keyed by field key; fields without a value are missing. */
    values: Map[String, MetadataValue],
    /** Document language for formatting: `en` (default) or `de`. */
    language: Option[String]
) derives JsonCodec,
      Schema

final case class MissingValueView(field: String, label: String) derives JsonCodec, Schema

final case class PreviewIssueView(
    /** Field key; empty for a problem of the whole template. */
    field: Option[String],
    label: Option[String],
    /** Stable code, e.g. `out_of_range`, `pattern_mismatch`, `evaluation_failed`, `limit_exceeded`. */
    code: String,
    message: String
) derives JsonCodec,
      Schema

final case class PreviewView(
    /** HTML fragment (`article.dms-document`) marked "Draft – not valid"; all text is escaped. Style it with
      * the document stylesheet. Empty when the template could not be resolved (see `issues`).
      */
    html: String,
    /** Required fields without a value, in profile order. */
    missingRequired: List[MissingValueView],
    /** Values that were left out because they do not fit their field, and resolution problems. */
    issues: List[PreviewIssueView]
) derives JsonCodec,
      Schema

/** Creating documents from released templates (business concept 4.3, US-03.2): the form fields of a template
  * version and the live preview. Both require `ReadTemplateCatalog` (every business role) and only serve
  * released versions.
  */
object TemplatePreviewApi:
  import TemplateApi.{Env, authorized}

  private val templateId = path[UUID]("id").description("Template id")
  private val versionNo  = path[Int]("version").description("Template version number")
  private val base       = "api" / "v1" / "templates" / templateId / "versions" / versionNo

  val fields: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base / "fields")
      .out(jsonBody[TemplateFieldsView])
      .description(
        "Form fields for creating a document from a released template version: the editable fields of the " +
          "class profile with type, rules and requiredness. Other versions are not found (404)."
      )
      .tag("Templates")
      .serverLogic(p => (id, v) => released(p, id, v).map(fieldsView))

  val preview: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(base / "preview")
      .in(jsonBody[PreviewRequest])
      .out(jsonBody[PreviewView])
      .description(
        "Live preview while filling in a document (US-03.2): resolves the released version with the values " +
          "entered so far and returns HTML marked \"Draft – not valid\" with missing values marked. Values " +
          "that do not fit their field are left out and reported in `issues`; the resolver's limits apply."
      )
      .tag("Templates")
      .serverLogic(p =>
        (id, v, req) =>
          for
            language <- ZIO
              .fromOption(req.language.fold(Some(Language.default))(Language.parse))
              .orElseFail(
                (
                  StatusCode.UnprocessableEntity,
                  ApiError("invalid", s"unknown language '${req.language.getOrElse("")}'; use en or de")
                )
              )
            template <- released(p, id, v)
            // Resolve and render outside the transaction: it needs no database and must not hold a connection.
            result <- ZIO.attempt(Preview.run(template, req.values, language)).orDie
          yield PreviewView(
            result.html,
            result.missingRequired.map(m => MissingValueView(m.field, m.label)),
            result.issues.map(i => PreviewIssueView(i.field, i.label, i.code, i.message))
          )
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(fields, preview)

  /** The form fields and reference fields of a compiled version. */
  private[app] def fieldsView(t: PreviewTemplate): TemplateFieldsView =
    TemplateFieldsView(
      t.version.templateId.value,
      t.version.version,
      t.version.name,
      t.version.description,
      t.version.documentClassId,
      t.version.documentClassVersion,
      t.fields.map(f =>
        TemplateFieldView(
          f.key,
          f.label,
          f.description,
          f.fieldType,
          f.multiple,
          f.required,
          f.usedInTemplate,
          f.reference.map(r => FieldReferenceView(r.reference, r.attribute))
        )
      ),
      t.version.content.references.map(r =>
        ReferenceFieldView(
          r.key,
          r.label,
          r.source.key,
          r.fields.map(m => ReferenceMappingView(m.attribute, m.field))
        )
      )
    )

  private def released(principal: Principal, id: UUID, version: Int) =
    authorized(principal, Action.ReadTemplateCatalog, _ => ZIO.succeed(Resource(principal.tenantId)))(
      _.released(TemplateId(id), version)
    )
