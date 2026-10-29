package dms.app

import dms.authz.DocumentResource
import dms.documents.metadata.{MetadataProfile, MetadataValue}
import dms.documents.{DocumentVersion, Publication, Versioning}
import dms.documents.core.{DocumentDetails, DocumentError, DocumentService}
import dms.kernel.{DocumentId, TenantId}
import dms.search.{SearchDocument, SearchError, SearchSource}
import dms.search.core.AccessFilter
import dms.templates.core.*
import dms.templates.{Block, Inline, Language, Limits, TemplateContent}
import zio.*

import java.time.Instant

/** Gives the search index (module `search`, ADR 0029) the documents to index: the indexable versions of a
  * document with their resolved content as plain text, their field values and the access attributes the
  * authorization policy decides on ([[DocumentResource]], so a hit and opening the version are decided
  * alike).
  */
final class SearchDocuments(documents: DocumentService, templates: TemplateService, profiles: ProfileSource)
    extends SearchSource:
  import SearchDocuments.*

  def changedDocuments(since: Option[Instant]): IO[SearchError, List[DocumentId]] =
    documents.changedDocuments(since).mapError(e => SearchError.Source(e.describe))

  def load(tenant: TenantId, id: DocumentId): IO[SearchError, List[SearchDocument]] =
    documents
      .get(id)
      .foldZIO(
        {
          case DocumentError.NotFound(_) => ZIO.succeed(Nil)
          case e                         => ZIO.fail(SearchError.Source(e.describe))
        },
        details =>
          ZIO.foreach(details.versions.filter(v => SearchDocument.isIndexed(v.status)))(v =>
            document(tenant, details, v)
          )
      )

  private def document(tenant: TenantId, details: DocumentDetails, v: DocumentVersion): UIO[SearchDocument] =
    val values   = v.metadata
    val resource = DocumentResource(tenant, details.document, classPath(details), v)
    text(details, v).map(content =>
      SearchDocument(
        tenantId = tenant,
        documentId = details.document.id,
        versionId = v.id,
        versionLabel = v.label,
        title = SearchDocuments.text(values, "title").getOrElse(details.document.title),
        text = (content :: fieldLines(details.profile, values)).filter(_.nonEmpty).mkString("\n\n"),
        classPath = classPath(details),
        status = v.status,
        validFrom = v.validFrom.orElse(Publication.validFrom(values)),
        validUntil = values.get("validUntil").collect { case MetadataValue.Date(d) => d },
        publishedAt = v.publishedAt,
        scope = Versioning.scopeKey(values).split('|').toList.filter(_.nonEmpty),
        language = SearchDocuments.text(values, "language"),
        // The document number (DMS-113) also for versions frozen before the document got one.
        metadata = values ++ details.document.number.map(n => "documentNumber" -> MetadataValue.Text(n)),
        access = AccessFilter.attributes(resource),
        changedAt = v.updatedAt
      )
    )

  /** The content resolved with the version's values, as plain text; the unresolved text if it cannot be
    * resolved (the version is indexed anyway).
    */
  private def text(details: DocumentDetails, v: DocumentVersion): UIO[String] =
    // A file document's content is its uploaded original (DMS-115): its name is indexed, not the template text.
    if details.document.kind == dms.documents.DocumentKind.File then ZIO.succeed(v.file.fold("")(_.name))
    else structuredText(details, v)

  private def structuredText(details: DocumentDetails, v: DocumentVersion): UIO[String] =
    val values   = v.metadata
    val language = SearchDocuments.text(values, "language").getOrElse(Language.default.code)
    val resolved =
      for
        template <- templates.version(v.templateId, v.templateVersion).mapError(_.describe)
        profile  <- profiles
          .profile(template.documentClassId, template.documentClassVersion)
          .mapError(_.describe)
        schema = ProfileSchema.from(profile)
        compiled <- ZIO
          .fromEither(TemplateCompiler.compile(v.content, schema, Limits.default))
          .mapError(_.map(_.show).mkString("; "))
        fieldValues = values.collect {
          case (key, value) if schema.variable(key).isDefined => key -> ProfileSchema.fieldValue(value)
        }
        lang = Language.parse(language.takeWhile(_ != '-')).getOrElse(Language.default)
        resolution <- ZIO.fromEither(Resolver.resolve(compiled, fieldValues, lang)).mapError(_.message)
      yield resolution.content.plainText
    resolved.catchAll(problem =>
      ZIO
        .logWarning(
          s"Search: content of document ${details.document.id.value} version ${v.label} not resolved ($problem); " +
            "indexing the unresolved text"
        )
        .as(unresolved(v.content))
    )

object SearchDocuments:
  val layer: URLayer[DocumentService & TemplateService & ProfileSource, SearchSource] =
    ZLayer.fromFunction(SearchDocuments(_, _, _))

  private def classPath(details: DocumentDetails): List[String] = details.profile.classPath.map(_.value)

  private def text(values: Map[String, MetadataValue], key: String): Option[String] =
    values.get(key).collect {
      case MetadataValue.Text(t)      => t
      case MetadataValue.Selection(c) => c
    }

  /** Field values as `label: value` lines, so a search also finds a document by e.g. its contract partner. */
  private def fieldLines(profile: MetadataProfile, values: Map[String, MetadataValue]): List[String] =
    def show(value: MetadataValue): List[String] = value match
      case MetadataValue.Text(t)             => List(t)
      case MetadataValue.Selection(c)        => List(c)
      case MetadataValue.Number(n)           => List(n.bigDecimal.toPlainString)
      case MetadataValue.Amount(n, currency) => List(s"${n.bigDecimal.toPlainString} $currency")
      case MetadataValue.Date(d)             => List(d.toString)
      case MetadataValue.Many(vs)            => vs.flatMap(show)
      case MetadataValue.YesNo(_) | MetadataValue.PersonRef(_) | MetadataValue.DocumentRef(_) => Nil
    profile.fields.flatMap(f =>
      values
        .get(f.key.value)
        .map(show)
        .filter(_.nonEmpty)
        .map(vs => s"${f.definition.label}: ${vs.mkString(", ")}")
    )

  private def unresolved(content: TemplateContent): String =
    TemplateContent
      .blocks(content)
      .flatMap {
        case Block.Heading(_, inlines) => Some(inlineText(inlines))
        case Block.Paragraph(inlines)  => Some(inlineText(inlines))
        case _                         => None
      }
      .mkString("\n")

  private def inlineText(inlines: List[Inline]): String =
    inlines.map {
      case Inline.Text(t, _)        => t
      case Inline.Placeholder(_, _) => ""
      case Inline.LineBreak         => "\n"
    }.mkString
