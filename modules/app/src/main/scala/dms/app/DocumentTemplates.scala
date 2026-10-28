package dms.app

import dms.documents.core.{DocumentError, TemplateSource}
import dms.documents.metadata.MetadataValue
import dms.kernel.TemplateId
import dms.templates.*
import dms.templates.core.*
import zio.*

/** Gives the documents module access to templates (module `templates`): template versions, the content check
  * against the class profile, computed fields and the stored content format.
  */
final class DocumentTemplates(service: TemplateService, profiles: ProfileSource) extends TemplateSource:
  import DocumentTemplates.*

  def version(id: TemplateId, version: Int): IO[DocumentError, TemplateVersion] =
    service.version(id, version).mapError(toDocumentError)

  def check(template: TemplateVersion, content: TemplateContent): IO[DocumentError, List[String]] =
    service
      .check(template.documentClassId, template.documentClassVersion, content)
      .mapBoth(toDocumentError, _.map(_.show))

  def computed(
      template: TemplateVersion,
      content: TemplateContent,
      values: Map[String, MetadataValue]
  ): IO[DocumentError, Map[String, MetadataValue]] =
    evaluate(template, content, values)(Resolver.computedValues)
      .map(_.flatMap((key, value) => metadataValue(value).map(key -> _)))

  def missingComputed(
      template: TemplateVersion,
      content: TemplateContent,
      values: Map[String, MetadataValue]
  ): IO[DocumentError, Map[String, List[String]]] =
    evaluate(template, content, values)(Resolver.missingComputed)
      .map(_.map(m => m.key -> m.missingInputs).toMap)

  /** Compiles `content` against the profile the template was designed against and evaluates its computed
    * fields with the profile values among `values` (computed values are left out).
    */
  private def evaluate[A](
      template: TemplateVersion,
      content: TemplateContent,
      values: Map[String, MetadataValue]
  )(
      f: (CompiledTemplate, Map[String, FieldValue]) => Either[ResolveError, A]
  ): IO[DocumentError, A] =
    for
      profile <- profiles
        .profile(template.documentClassId, template.documentClassVersion)
        .mapError(toDocumentError)
      schema = ProfileSchema.from(profile)
      compiled <- ZIO
        .fromEither(TemplateCompiler.compile(content, schema, Limits.default))
        .mapError(issues => DocumentError.Invalid(issues.map(_.show)))
      fieldValues = values.collect {
        case (key, value) if schema.variable(key).isDefined => key -> ProfileSchema.fieldValue(value)
      }
      result <- ZIO
        .fromEither(f(compiled, fieldValues))
        .mapError(e => DocumentError.Invalid(List(s"a computed field cannot be evaluated: ${e.message}")))
    yield result

  def encode(content: TemplateContent): String              = ContentJson.encodeStored(content)
  def decode(json: String): Either[String, TemplateContent] = ContentJson.decodeStored(json)

  def encodeReport(report: DeviationReport): String               = DeviationJson.encodeStored(report)
  def decodeReport(json: String): Either[String, DeviationReport] = DeviationJson.decodeStored(json)

object DocumentTemplates:
  val layer: URLayer[TemplateService & ProfileSource, TemplateSource] =
    ZLayer.fromFunction(DocumentTemplates(_, _))

  /** A computed value as a metadata value; structured results (records) are only used in placeholders and are
    * not stored.
    */
  def metadataValue(value: FieldValue): Option[MetadataValue] = value match
    case FieldValue.Text(v)      => Some(MetadataValue.Text(v))
    case FieldValue.Number(v)    => Some(MetadataValue.Number(v))
    case FieldValue.Amount(v, c) => Some(MetadataValue.Amount(v, c))
    case FieldValue.Date(v)      => Some(MetadataValue.Date(v))
    case FieldValue.YesNo(v)     => Some(MetadataValue.YesNo(v))
    case FieldValue.ListOf(vs)   =>
      val converted = vs.flatMap(metadataValue)
      Option.when(converted.size == vs.size)(MetadataValue.Many(converted))
    case FieldValue.Record(_) => None

  private def toDocumentError(error: TemplateError): DocumentError = error match
    case TemplateError.NotFound(m)           => DocumentError.NotFound(m)
    case e @ TemplateError.InvalidContent(_) => DocumentError.Invalid(List(e.describe))
    case TemplateError.Invalid(problems)     => DocumentError.Invalid(problems)
    case e @ TemplateError.Lifecycle(_)      => DocumentError.Conflict(e.describe)
    case TemplateError.Conflict(m)           => DocumentError.Conflict(m)
    case TemplateError.Database(e)           => DocumentError.Database(e)
