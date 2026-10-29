package dms.app

import dms.authz.DocumentResource
import dms.documents.metadata.{MetadataProfile, MetadataValue}
import dms.documents.{DocumentVersion, Publication, Versioning}
import dms.documents.core.{DocumentDetails, DocumentError, DocumentService}
import dms.identity.{TenantOrganizations, UserDirectory}
import dms.kernel.{DocumentId, TenantId}
import dms.search.{SearchDocument, SearchError, SearchSource}
import dms.rendering.core.RenderingError
import dms.search.core.AccessFilter
import dms.templates.core.*
import dms.templates.{Block, Inline, Language, Limits, TemplateContent}
import zio.*

import java.time.Instant

/** Gives the search index (module `search`, ADR 0029) the documents to index: the indexable versions of a
  * document with their resolved content as plain text, their field values and the access attributes the
  * authorization policy decides on ([[DocumentResource]], so a hit and opening the version are decided
  * alike). File documents (DMS-115, DMS-121): the file name and, for Word originals, the text of the document
  * with its fields filled ([[SearchDocuments.Originals]]).
  */
final class SearchDocuments(
    documents: DocumentService,
    templates: TemplateService,
    profiles: ProfileSource,
    originals: SearchDocuments.Originals = SearchDocuments.Originals.none
) extends SearchSource:
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
    // A file document's content is its uploaded original (DMS-115): its name and, for Word documents, its
    // filled text (DMS-121) are indexed, not the template text.
    if details.document.kind == dms.documents.DocumentKind.File then
      v.file.fold(ZIO.succeed("")) { file =>
        originals
          .text(details, v)
          .map(text => (file.name :: text.toList).filter(_.nonEmpty).mkString("\n\n"))
      }
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

  /** With the text of Word originals (DMS-121). */
  val withOriginals: URLayer[
    DocumentService & TemplateService & ProfileSource & FileVersions & TenantOrganizations & UserDirectory,
    SearchSource
  ] = ZLayer.fromZIO(
    for
      documents <- ZIO.service[DocumentService]
      templates <- ZIO.service[TemplateService]
      profiles  <- ZIO.service[ProfileSource]
      env       <- ZIO.environment[FileVersions & TenantOrganizations & UserDirectory]
    yield SearchDocuments(documents, templates, profiles, Originals.filledWord(env))
  )

  /** The text of the original of a file version for the index, if it has one that can be read as text. */
  trait Originals:
    def text(details: DocumentDetails, v: DocumentVersion): UIO[Option[String]]

  object Originals:
    /** Only file names are indexed. */
    val none: Originals = (_, _) => ZIO.none

    /** Word originals (DOCX) with their fields filled as approved (automatic values, manual values, the
      * release), as text of all paragraphs including headers and footers; other formats (PDF, spreadsheets,
      * ...) give none, so only their name is indexed. Reading runs in the indexer's transaction, like the
      * rendering job reads the original; a failure is logged and the version indexed without the text.
      */
    def filledWord(env: ZEnvironment[FileVersions & TenantOrganizations & UserDirectory]): Originals =
      (details, v) =>
        v.file.filter(_.isWord) match
          case None    => ZIO.none
          case Some(_) =>
            val approval = FileVersions.approvalOf(v, None)
            (for
              tenant <- DocumentRenditions.currentTenant
              names  <- VersionViewApi.displayNames(tenant, WordFilling.people(details, v, approval))
              filled <- FileVersions.filled(details, v, names, approval)
              text   <- ZIO.fromEither(WordDocx.text(filled.toArray)).mapError(m => RenderingError.Invalid(m))
            yield Option(text).filter(_.nonEmpty))
              .provideEnvironment(env)
              .catchAll(e =>
                ZIO
                  .logWarning(
                    s"Search: text of the original of document ${details.document.id.value} version ${v.label} " +
                      s"not read ($e); indexing its name only"
                  )
                  .as(None)
              )

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
