package dms.templates.core

import dms.documents.metadata.*
import dms.templates.*

/** A field an author fills in when creating a document from a template.
  *
  * @param required
  *   needed before the document can be approved (the template's notion of required, [[ProfileSchema]])
  * @param usedInTemplate
  *   the template refers to the field in a placeholder, condition, repeat or computed field
  */
final case class TemplateField(
    key: String,
    label: String,
    description: Option[String],
    fieldType: FieldType,
    multiple: Boolean,
    required: Boolean,
    usedInTemplate: Boolean
)

/** A required value the author has not filled in yet. */
final case class MissingValue(field: String, label: String)

/** A problem with the given values or the resolution. `field` is empty for problems of the whole template,
  * e.g. an expression that failed at runtime. `code` is a stable code such as `out_of_range` (see
  * [[IssueKind]]), `evaluation_failed` or `limit_exceeded`.
  */
final case class PreviewIssue(field: Option[String], label: Option[String], code: String, message: String)

/** Result of a live preview.
  *
  * @param html
  *   the HTML fragment of [[HtmlRenderer.render]] with draft marking; empty when the resolution failed
  */
final case class PreviewResult(html: String, missingRequired: List[MissingValue], issues: List[PreviewIssue])

/** A released template version prepared for previews: compiled once against its class profile. Released
  * versions and class profile versions never change, so instances can be cached.
  */
final case class PreviewTemplate(
    version: TemplateVersion,
    profile: MetadataProfile,
    compiled: CompiledTemplate
):

  /** The fields an author can fill in (all but system fields), those the template uses first, each group in
    * profile order.
    */
  lazy val fields: List[TemplateField] =
    val used = Preview.usedFields(compiled)
    val all  = profile.fields.filter(_.definition.editable).map { f =>
      TemplateField(
        f.key.value,
        f.definition.label,
        f.definition.description,
        f.definition.fieldType,
        f.definition.multiple,
        f.isRequiredIn(ProfileSchema.RequiredFor),
        used.contains(f.key.value)
      )
    }
    all.filter(_.usedInTemplate) ++ all.filterNot(_.usedInTemplate)

  lazy val labels: Map[String, String] = profile.fields.map(f => f.key.value -> f.definition.label).toMap

  /** Keys of the fields the platform sets (not editable). */
  lazy val systemFields: Set[String] = profile.fields.filterNot(_.definition.editable).map(_.key.value).toSet

/** Live preview while an author fills in a document (US-03.2): resolves the template with the values entered
  * so far and renders it as HTML marked "Draft – not valid". Pure apart from the resolver's time limit.
  *
  * Values that do not fit their field definition (type, range, pattern, options) are left out and reported as
  * issues, so a half-typed value never breaks the preview; empty values count as missing.
  */
object Preview:

  def run(template: PreviewTemplate, values: Map[String, MetadataValue], language: Language): PreviewResult =
    val profile      = template.profile
    val valueIssues  = Validation.checkValues(profile, values)
    val systemKeys   = template.systemFields
    val systemIssues = values.keySet.intersect(systemKeys).toList.sorted.map { key =>
      PreviewIssue(Some(key), template.labels.get(key), "system_field", "The platform sets this field.")
    }
    val rejected = valueIssues.map(_.field).toSet ++ systemKeys
    val accepted = values.filter((key, value) => !rejected.contains(key) && !Validation.isEmpty(Some(value)))
    val fieldValues = accepted.map((key, value) => key -> ProfileSchema.fieldValue(value))
    val issues      = valueIssues.map(i =>
      PreviewIssue(Some(i.field), Some(i.label), i.kind.code, i.kind.message)
    ) ++ systemIssues
    Resolver.resolve(template.compiled, fieldValues, language) match
      case Right(resolution) =>
        val missing = resolution.missingRequired.filterNot(systemKeys.contains).map { key =>
          MissingValue(key, template.labels.getOrElse(key, key))
        }
        val html =
          HtmlRenderer.render(resolution.content, HtmlOptions.draft(language, template.labels, systemKeys))
        PreviewResult(html, missing, issues)
      case Left(error) =>
        val code = error match
          case ResolveError.LimitExceeded(_, _) => "limit_exceeded"
          case ResolveError.InvalidValues(_)    => "invalid_values"
          case ResolveError.Invalid(_)          => "invalid_template"
          case ResolveError.Evaluation(_, _)    => "evaluation_failed"
        PreviewResult("", Nil, issues :+ PreviewIssue(None, None, code, error.message))

  /** Names of profile fields the template refers to in placeholders and expressions. */
  def usedFields(compiled: CompiledTemplate): Set[String] =
    val placeholders = TemplateContent.placeholders(compiled.content).map(_.takeWhile(_ != '.'))
    val expressions  = compiled.expressions.values.flatMap(_.identifiers) ++
      compiled.computed.flatMap((_, e) => e.identifiers)
    val names = placeholders.toSet ++ expressions
    compiled.schema.variables.map(_.name).filter(names.contains).toSet
