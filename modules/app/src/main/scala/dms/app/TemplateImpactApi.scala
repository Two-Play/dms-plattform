package dms.app

import dms.authz.{AccessDenied, Action, Authorizer, Policy, Resource, Subject}
import dms.db.{Db, DbError}
import dms.documents.core.{DocumentError, DocumentService}
import dms.documents.{OpenTemplateUse, StatusUsage, TemplateUsage}
import dms.kernel.TemplateId
import dms.templates.TemplateVersion
import dms.templates.core.{TemplateError, TemplateService}
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.time.Instant
import java.util.UUID

/** Document versions in one status. */
final case class StatusCountView(
    /** Document status, e.g. Draft, Published, Superseded. */
    status: String,
    /** Number of document versions in this status. */
    versions: Int,
    /** Number of documents with a version in this status. */
    documents: Int
) derives JsonCodec,
      Schema

/** The document versions bound to one earlier template version. */
final case class TemplateVersionUseView(templateVersion: Int, documents: Int, byStatus: List[StatusCountView])
    derives JsonCodec,
      Schema

/** An open document version on an earlier template version: title and author only, no content. */
final case class OpenDraftView(
    documentId: UUID,
    versionId: UUID,
    /** Document title; [[TemplateImpactApi.MaskedTitle]] if `titleMasked`. */
    title: String,
    /** True if the version is confidential or strictly confidential and the caller may not read it: the title
      * is replaced (DMS-98).
      */
    titleMasked: Boolean,
    /** User id of the author of the open version. */
    author: String,
    /** Version label, e.g. `2.0-draft.3`. */
    version: String,
    /** Draft, InReview or InApproval. */
    status: String,
    templateVersion: Int,
    updatedAt: Instant
) derives JsonCodec,
      Schema

/** Impact analysis of a template version (US-01.2). */
final case class TemplateImpactView(
    templateId: UUID,
    version: Int,
    /** Status of the analysed template version. */
    status: String,
    /** Documents with a version on an earlier version of this template. */
    documents: Int,
    /** Document versions per status over all earlier template versions. */
    byStatus: List[StatusCountView],
    /** Document versions per earlier template version and status, oldest template version first. */
    byVersion: List[TemplateVersionUseView],
    /** Open document versions (Draft, InReview, InApproval) on earlier template versions, most recently
      * changed first.
      */
    openDrafts: List[OpenDraftView],
    /** Always true: releasing this version changes no existing document. */
    existingDocumentsUnchanged: Boolean,
    explanation: String
) derives JsonCodec,
      Schema

/** Impact analysis before releasing a template version (US-01.2, DMS-50): how documents use the earlier
  * versions of the template. The template module does not depend on documents-core, so the analysis combines
  * both services here, in one transaction of the caller's tenant. Requires `EditTemplates`, which every
  * template designer (and therefore every possible releaser) holds.
  */
object TemplateImpactApi:
  type Env = TemplateApi.Env & DocumentService

  /** Why a release changes no existing document; returned with every analysis. */
  val Explanation: String =
    "Documents stay bound to the template version they were created from; new versions of a document keep the " +
      "template version of the version they are based on. Releasing this version changes no existing document: " +
      "approved and valid documents remain unchanged, and open drafts continue on their template version. Only " +
      "documents created from the catalog after the release use the new version."

  /** Shown instead of the title of a confidential or strictly confidential version the caller may not read.
    */
  val MaskedTitle: String = "Confidential document"

  private val templateId = path[UUID]("id").description("Template id")
  private val versionNo  = path[Int]("version").description("Template version number")

  val impact: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "templates" / templateId / "versions" / versionNo / "impact")
      .out(jsonBody[TemplateImpactView])
      .description(
        "Impact analysis before releasing a template version: document versions per status bound to earlier " +
          "versions of the template (in total and per template version) and the open drafts with title and " +
          "author only. Requires EditTemplates (template designer). Works for a version in any status."
      )
      .tag("Templates")
      .serverLogic(p =>
        (id, v) =>
          (for
            db        <- ZIO.service[Db]
            authz     <- ZIO.service[Authorizer]
            templates <- ZIO.service[TemplateService]
            documents <- ZIO.service[DocumentService]
            result    <- db.transact(p.tenantId)(
              for
                subject <- authz.subject(p)
                _       <- authz.check(subject, Action.EditTemplates, Resource(p.tenantId))
                version <- templates.version(TemplateId(id), v)
                usage   <- documents.templateUsage(TemplateId(id), v)
                masked  <- maskedVersions(p.tenantId, subject, documents, usage.openVersions)
              yield view(version, usage, masked)
            )
          yield result).mapError(toResponse)
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(impact)

  private def toResponse(
      error: AccessDenied | DbError | TemplateError | DocumentError
  ): (StatusCode, ApiError) =
    error match
      case e: DocumentError => DocumentApi.toResponse(e)
      case e: TemplateError => TemplateApi.toResponse(e)
      case e: AccessDenied  => TemplateApi.toResponse(e)
      case e: DbError       => TemplateApi.toResponse(e)

  /** The restricted open versions (confidentiality at least `Confidential`) whose title the caller may not
    * see: those they may not read (`ReadDocument`, which for these levels needs author, owner or an allowed
    * group). Workflow participation is not considered, so a reviewer who could open the version through their
    * task may still see it masked here; the analysis errs on the side of hiding.
    */
  private def maskedVersions(
      tenant: dms.kernel.TenantId,
      subject: Subject,
      documents: DocumentService,
      open: List[OpenTemplateUse]
  ): IO[DocumentError, Set[UUID]] =
    ZIO
      .foreach(open.filter(_.restricted))(o =>
        for
          details <- documents.get(o.documentId)
          now     <- Clock.instant
          readable = details.versions
            .find(_.id == o.versionId)
            .exists(v =>
              Policy
                .decide(subject, Action.ReadDocument, DocumentApi.resource(tenant, details, v), now)
                .isPermitted
            )
        yield Option.unless(readable)(o.versionId)
      )
      .map(_.flatten.toSet)

  private[app] def view(
      version: TemplateVersion,
      usage: TemplateUsage,
      masked: Set[UUID] = Set.empty
  ): TemplateImpactView =
    def statusCounts(counts: List[StatusUsage]): List[StatusCountView] =
      counts.map(c => StatusCountView(c.status.toString, c.versions, c.documents))
    TemplateImpactView(
      version.templateId.value,
      version.version,
      version.status.toString,
      usage.documents,
      statusCounts(usage.byStatus),
      usage.byVersion.map(u =>
        TemplateVersionUseView(u.templateVersion, u.documents, statusCounts(u.byStatus))
      ),
      usage.openVersions.map(o =>
        OpenDraftView(
          o.documentId.value,
          o.versionId,
          if masked.contains(o.versionId) then MaskedTitle else o.title,
          masked.contains(o.versionId),
          o.authorId.value,
          o.label,
          o.status.toString,
          o.templateVersion,
          o.updatedAt
        )
      ),
      existingDocumentsUnchanged = true,
      Explanation
    )
