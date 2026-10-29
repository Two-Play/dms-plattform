package dms.app

import dms.audit.AuditTrail
import dms.db.Db
import dms.documents.metadata.{MetadataProfile, MetadataValue, Validation}
import dms.documents.{DocumentStatus, DocumentVersion, Rendition}
import dms.documents.core.{DocumentDetails, DocumentService}
import dms.kernel.UserId
import dms.rendering.RenditionRequest
import dms.rendering.core.*
import dms.templates.core.*
import dms.templates.{Language, Limits, ResolvedContent, TemplateSchema}
import dms.workflow.core.{ApprovalRendition, ApprovalWorkflow, ReviewSubject, WorkflowError}
import zio.*

import java.time.Instant
import java.util.UUID

/** The workflow's last approving vote requests the archive rendition (DMS-44): enqueues the rendering job in
  * the vote's transaction. The owner (or, without owner, the author) is notified if rendering fails.
  */
object WorkflowRendition extends ApprovalRendition:

  def request(
      caseId: UUID,
      subject: ReviewSubject,
      approver: UserId,
      approvedAt: Instant,
      authenticatedAt: Instant,
      onBehalfOf: Option[UserId]
  ): IO[WorkflowError, Unit] =
    RenditionJobs
      .enqueue(
        RenditionRequest(
          subject.documentId,
          subject.versionId,
          caseId,
          approver,
          approvedAt,
          authenticatedAt,
          List(subject.owner.getOrElse(subject.author)),
          onBehalfOf
        )
      )
      .mapError(WorkflowError.Database(_))

  val layer: ULayer[ApprovalRendition] = ZLayer.succeed(WorkflowRendition)

/** Gives rendering (module `rendering`) the page of a version and completes its approval: the content is
  * resolved with the version's field values and printed as the stand-alone HTML page of the templates module;
  * the stored rendition is attached through [[DocumentService]] and the approval completed through
  * [[ApprovalWorkflow]].
  */
final class DocumentRenditions(
    documents: DocumentService,
    templates: TemplateService,
    profiles: ProfileSource,
    workflow: ApprovalWorkflow
) extends RenditionSource:

  def load(request: RenditionRequest): IO[RenderingError, Option[RenditionPage]] =
    for
      details <- documents.get(request.documentId).mapError(e => RenderingError.Invalid(e.describe))
      version <- ZIO
        .fromOption(details.versions.find(_.id == request.versionId))
        .orElseFail(RenderingError.Invalid(s"document version ${request.versionId} does not exist"))
      page <- version.status match
        case DocumentStatus.InApproval => page(details, version, request).asSome
        case _ if version.releasedAt.isDefined && version.rendition.isDefined => ZIO.none
        case other                                                            =>
          ZIO.fail(
            RenderingError.Invalid(s"version ${version.label} is $other and does not wait for its rendition")
          )
    yield page

  def complete(request: RenditionRequest, rendition: Rendition): IO[RenderingError, Unit] =
    for
      _ <- documents
        .attachRendition(request.versionId, rendition, request.approver)
        .mapError(e => RenderingError.Invalid(e.describe))
      _ <- workflow
        .completeApproval(request.caseId, request.approver, request.authenticatedAt)
        .mapError(e => RenderingError.Invalid(e.describe))
    yield ()

  private def page(
      details: DocumentDetails,
      v: DocumentVersion,
      request: RenditionRequest
  ): IO[RenderingError, RenditionPage] =
    val values = v.metadata
    DocumentRenditions.html(templates, profiles, details, v).map { (html, lang) =>
      RenditionPage(
        html,
        RenditionFacts(
          documentId = details.document.id.value.toString,
          title = DocumentRenditions.title(details, v),
          version = v.number.show,
          validFrom = values.get("validFrom").collect { case MetadataValue.Date(d) => d },
          approvedAt = request.approvedAt,
          approvedBy = request.approver.value,
          language = lang.code,
          onBehalfOf = request.onBehalfOf.map(_.value)
        )
      )
    }

object DocumentRenditions:
  val layer: URLayer[DocumentService & TemplateService & ProfileSource & ApprovalWorkflow, RenditionSource] =
    ZLayer.fromFunction(DocumentRenditions(_, _, _, _))

  /** The title of a version: its `title` field, else the document's title. */
  def title(details: DocumentDetails, v: DocumentVersion): String =
    text(v.metadata, "title").getOrElse(details.document.title)

  /** The stand-alone HTML page of a version (templates module): its content resolved with its field values,
    * in its language. The archive rendition is printed from it; the stable address of the current version
    * (DMS-58) serves it as HTML. Runs inside `Db.transact(tenant)`.
    */
  def html(
      templates: TemplateService,
      profiles: ProfileSource,
      details: DocumentDetails,
      v: DocumentVersion
  ): IO[RenderingError, (String, Language)] =
    for
      prepared <- prepare(templates, profiles, v)
      (profile, schema, compiled, lang) = prepared
      fieldValues                       = v.metadata.collect {
        case (key, value) if schema.variable(key).isDefined => key -> ProfileSchema.fieldValue(value)
      }
      resolution <- ZIO
        .fromEither(Resolver.resolve(compiled, fieldValues, lang))
        .mapError(e => invalid(v, e.message))
      labels = profile.fields.map(f => f.key.value -> f.definition.label).toMap
    yield (
      HtmlRenderer.renderDocument(
        resolution.content,
        HtmlOptions(lang, labels, None, Some(title(details, v)))
      ),
      lang
    )

  /** A version resolved for reading on screen or printing on demand (DMS-112), with its rendering options. */
  final case class VersionRendering(content: ResolvedContent, options: HtmlOptions):
    /** The HTML fragment (one `article.dms-document`) for the web page. */
    def fragment: String = HtmlRenderer.render(content, options)

    /** The stand-alone page with the document stylesheet, for the rendering worker. */
    def page: String = HtmlRenderer.renderDocument(content, options)

    def language: Language = options.language

  /** Whether a version shows the draft marking: it was never approved (draft, in review or approval, or a
    * draft that was withdrawn). Approved versions show their frozen content without marking.
    */
  def drafted(v: DocumentVersion): Boolean = v.releasedAt.isEmpty

  /** The content of any version resolved with its stored field values, in its language (DMS-112, ADR 0039).
    *
    * Versions that were never approved are rendered like the live preview (US-03.2): values that do not fit
    * their field definition and blank values are left out (shown as missing), fields the platform sets are
    * marked neutrally while they have no value, and the page carries the draft marking "Draft – not valid".
    * Approved versions are rendered from their frozen version package (content and values) without marking,
    * as printed into the archive rendition. Runs inside `Db.transact(tenant)`.
    */
  def view(
      templates: TemplateService,
      profiles: ProfileSource,
      details: DocumentDetails,
      v: DocumentVersion
  ): IO[RenderingError, VersionRendering] =
    for
      prepared <- prepare(templates, profiles, v)
      (profile, schema, compiled, lang) = prepared
      draft                             = drafted(v)
      rejected                          =
        if draft then Validation.checkValues(profile, v.metadata).map(_.field).toSet else Set.empty[String]
      fieldValues = v.metadata.collect {
        case (key, value)
            if schema.variable(key).isDefined && !rejected
              .contains(key) && !Validation.isEmpty(Some(value)) =>
          key -> ProfileSchema.fieldValue(value)
      }
      resolution <- ZIO
        .fromEither(Resolver.resolve(compiled, fieldValues, lang))
        .mapError(e => invalid(v, e.message))
      labels       = profile.fields.map(f => f.key.value -> f.definition.label).toMap
      systemFields = profile.fields.filterNot(_.definition.editable).map(_.key.value).toSet
    yield VersionRendering(
      resolution.content,
      HtmlOptions(
        lang,
        labels,
        Option.when(draft)(HtmlOptions.draftLabel(lang)),
        Some(title(details, v)),
        systemFields
      )
    )

  /** The class profile of the version's template, the schema, the compiled content and the language. */
  private def prepare(
      templates: TemplateService,
      profiles: ProfileSource,
      v: DocumentVersion
  ): IO[RenderingError, (MetadataProfile, TemplateSchema, CompiledTemplate, Language)] =
    val language = text(v.metadata, "language").getOrElse(Language.default.code)
    for
      template <- templates.version(v.templateId, v.templateVersion).mapError(e => invalid(v, e.describe))
      profile  <- profiles
        .profile(template.documentClassId, template.documentClassVersion)
        .mapError(e => invalid(v, e.describe))
      schema = ProfileSchema.from(profile)
      compiled <- ZIO
        .fromEither(TemplateCompiler.compile(v.content, schema, Limits.default))
        .mapError(issues => invalid(v, issues.map(_.show).mkString("; ")))
    yield (
      profile,
      schema,
      compiled,
      Language.parse(language.takeWhile(_ != '-')).getOrElse(Language.default)
    )

  private def invalid(v: DocumentVersion, message: String): RenderingError =
    RenderingError.Invalid(s"version ${v.label} cannot be rendered: $message")

  private def text(values: Map[String, MetadataValue], key: String): Option[String] =
    values.get(key).collect {
      case MetadataValue.Text(t)      => t
      case MetadataValue.Selection(c) => c
    }

/** The rendering job of the application: the worker over HTTP, veraPDF and the object store (ADR 0025). */
object RenditionWiring:
  val archive: URLayer[dms.storage.ObjectStore, RenditionArchive] =
    ZLayer.fromFunction(RenditionArchive.objectStore)

  val jobs: URLayer[
    Db & RenditionSource & PdfRenderer & PdfValidator & RenditionArchive & AuditTrail,
    RenditionJobs
  ] = ZLayer.fromFunction(
    (
        db: Db,
        source: RenditionSource,
        renderer: PdfRenderer,
        validator: PdfValidator,
        archive: RenditionArchive,
        audit: AuditTrail
    ) => RenditionJobs(db, source, renderer, validator, archive, audit)
  )
