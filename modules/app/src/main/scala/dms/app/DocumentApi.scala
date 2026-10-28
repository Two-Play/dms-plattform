package dms.app

import dms.authz.{AccessDenied, Action, Authorizer, Confidentiality, Policy, Resource, Subject}
import dms.db.{Db, DbError}
import dms.documents.*
import dms.documents.core.*
import dms.documents.core.MetadataJson.given
import dms.documents.metadata.{FieldIssue, MetadataProfile, MetadataValue}
import dms.identity.{Authenticator, Principal}
import dms.kernel.{DocumentId, TemplateId, UserId}
import dms.templates.{DeviationChange, Inline, PathStep, SectionId, TemplateContent}
import dms.templates.core.ContentJson.given
import dms.templates.core.DeviationJson.given
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.generic.Configuration
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.time.Instant
import java.util.UUID
import scala.util.Try

import DeviationSchemas.given
import MetadataSchemas.given
import TemplateSchemas.given

final case class CreateDocument(
    templateId: UUID,
    /** A released version from the template catalog. */
    templateVersion: Int,
    title: String,
    /** Entered field values by field key; their origin is stored as manual. */
    values: Map[String, MetadataValue]
) derives JsonCodec,
      Schema

final case class UpdateDraft(
    /** Default: the current title. */
    title: Option[String],
    /** Replaces the entered values of the draft. Values from business systems are kept, computed values
      * recomputed.
      */
    values: Map[String, MetadataValue],
    /** Default: the current content. Locked sections must keep the template's text. */
    content: Option[TemplateContent]
) derives JsonCodec,
      Schema

/** A field value with its origin: `Manual`, `System` (with `source`, e.g. `dms` or `crm`) or `Computed`. */
final case class FieldValueView(value: MetadataValue, origin: String, source: Option[String])
    derives JsonCodec,
      Schema

final case class ClauseView(clauseKey: String, version: Int) derives JsonCodec, Schema

final case class RenditionView(objectKey: String, objectVersion: Option[String], sha256: String)
    derives JsonCodec,
      Schema

final case class DocumentVersionView(
    id: UUID,
    /** `1.1-draft.3` while open, `1.1` from approval on. */
    version: String,
    workingState: Int,
    /** DocumentStatus, e.g. Draft, InReview, Published. */
    status: String,
    /** Major or Minor. */
    changeKind: String,
    changeReason: Option[String],
    /** Validity scope derived from the scope field; empty: unrestricted. */
    scope: String,
    classVersion: Int,
    templateId: UUID,
    templateVersion: Int,
    content: TemplateContent,
    values: Map[String, FieldValueView],
    /** Clause versions the content references. */
    clauses: List[ClauseView],
    rendition: Option[RenditionView],
    /** Complete metadata as approved; empty before approval. */
    metadataSnapshot: Option[Map[String, MetadataValue]],
    createdBy: String,
    createdAt: Instant,
    updatedAt: Instant,
    releasedBy: Option[String],
    releasedAt: Option[Instant],
    /** True if the content has authorized deviations from locked template sections: the workflow adds the
      * additional review step. Details: `GET /api/v1/documents/{id}/versions/{versionId}/deviations`.
      */
    requiresExtraReview: Boolean
) derives JsonCodec,
      Schema

/** Tapir schemas for the deviation report; sum types carry a `type` discriminator like their JSON codecs. */
object DeviationSchemas:
  given Configuration              = Configuration.default.withDiscriminator("type")
  given Schema[Inline.Placeholder] = Schema.derived
  given Schema[PathStep]           = Schema.derived
  given Schema[DeviationChange]    = Schema.derived

final case class RequestDeviation(
    /** Id of a locked section of the template. */
    sectionId: String,
    /** Why the standard text has to change; required. */
    reason: String
) derives JsonCodec,
      Schema

final case class DeviationRequestView(
    id: UUID,
    sectionId: String,
    reason: String,
    requestedBy: String,
    requestedAt: Instant,
    /** The request of the previous version this one was carried over from. */
    basedOn: Option[UUID],
    active: Boolean,
    withdrawnBy: Option[String],
    withdrawnAt: Option[Instant]
) derives JsonCodec,
      Schema

/** A change of the standard text of a locked section. */
final case class DeviationView(
    section: String,
    /** Block path inside the section, e.g. `[{"type":"Block","index":2},{"type":"Item","index":1}]`. */
    path: List[PathStep],
    /** Index of the first changed word of a heading or paragraph. */
    token: Option[Int],
    /** Readable location, e.g. `liability › block 2 › word 5`. */
    location: String,
    change: DeviationChange,
    /** True if the section has an active deviation request. */
    authorized: Boolean,
    reasons: List[String]
) derives JsonCodec,
      Schema

/** The deviation report of a version with its deviation requests, for authors and reviewers. */
final case class DeviationsView(
    versionId: UUID,
    version: String,
    status: String,
    requiresExtraReview: Boolean,
    /** All requests of the version, including withdrawn ones, oldest first. */
    requests: List[DeviationRequestView],
    deviations: List[DeviationView]
) derives JsonCodec,
      Schema

final case class DocumentView(
    id: UUID,
    classId: UUID,
    classVersion: Int,
    classPath: List[String],
    title: String,
    ownerId: Option[String],
    authorId: String,
    templateId: UUID,
    templateVersion: Int,
    createdAt: Instant,
    /** The open version if the caller may read it, otherwise the latest readable version. */
    current: DocumentVersionView
) derives JsonCodec,
      Schema

/** Documents created from released templates (business concept 4.3) and their versions (5.1, 5.2).
  *
  * Creating requires `CreateFromTemplate` in the scope of the template's document class; reading requires
  * `ReadDocument` on a version (by status: drafts for authors and owners, published versions for readers);
  * changing and submitting a draft and requesting or withdrawing deviations require `EditDraft`; the
  * deviation report of a version requires `ReadDocument` on it.
  */
object DocumentApi:
  type Env = Authenticator & Db & Authorizer & DocumentService

  private val documents  = "api" / "v1" / "documents"
  private val documentId = path[UUID]("id").description("Document id")

  val create: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(documents)
      .in(jsonBody[CreateDocument])
      .out(statusCode(StatusCode.Created).and(jsonBody[DocumentView]))
      .description(
        "Creates a document from a released template version: version 1.0 as a draft with the template content. " +
          "Values are validated against the class profile (422 with issues); computed fields are evaluated."
      )
      .tag("Documents")
      .serverLogic(p =>
        req =>
          inTransaction(p) { ctx =>
            for
              context <- ctx.service.templateContext(TemplateId(req.templateId), req.templateVersion)
              (_, profile) = context
              values       = req.values.map((k, v) => k -> FieldEntry(v, ValueOrigin.Manual))
              _       <- ctx.check(Action.CreateFromTemplate, creationResource(p, profile, req.values))
              created <- ctx.service.create(
                NewDocument(TemplateId(req.templateId), req.templateVersion, req.title, values),
                p.userId
              )
              (document, version) = created
            yield documentView(document, profile, version)
          }
      )

  val get: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(documents / documentId)
      .out(jsonBody[DocumentView])
      .description("A document with its open version, or its latest version the caller may read")
      .tag("Documents")
      .serverLogic(p =>
        id =>
          inTransaction(p) { ctx =>
            for
              details  <- ctx.service.get(DocumentId(id))
              readable <- ctx.readable(details)
              current = readable.find(v => Versioning.isOpen(v.status)).getOrElse(readable.last)
            yield documentView(details.document, details.profile, current)
          }
      )

  val versions: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(documents / documentId / "versions")
      .out(jsonBody[List[DocumentVersionView]])
      .description("The versions of a document the caller may read, oldest first")
      .tag("Documents")
      .serverLogic(p =>
        id =>
          inTransaction(p) { ctx =>
            ctx.service.get(DocumentId(id)).flatMap(ctx.readable).map(_.map(versionView))
          }
      )

  val updateDraft: ZServerEndpoint[Env, Any] =
    SecuredApi.base.put
      .in(documents / documentId / "draft")
      .in(jsonBody[UpdateDraft])
      .out(jsonBody[DocumentVersionView])
      .description(
        "Changes the draft and saves it as the next working state. Locked sections and computed fields of the " +
          "template cannot be changed (422); versions in review or later cannot be changed (409)."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, req) =>
          inTransaction(p) { ctx =>
            for
              details <- ctx.service.get(DocumentId(id))
              open    <- openVersion(details)
              _       <- ctx.check(Action.EditDraft, resource(p, details, open))
              entered = req.values.map((k, v) => k -> FieldEntry(v, ValueOrigin.Manual))
              updated <- ctx.service.updateDraft(
                DocumentId(id),
                DraftChange(req.title, keepExternal(open) ++ entered, req.content),
                p.userId
              )
            yield versionView(updated)
          }
      )

  val submit: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(documents / documentId / "submit")
      .out(jsonBody[DocumentVersionView])
      .description(
        "Submits the draft for review. Blocked while fields required in review are missing (422 with issues)."
      )
      .tag("Documents")
      .serverLogic(p =>
        id =>
          inTransaction(p) { ctx =>
            for
              details   <- ctx.service.get(DocumentId(id))
              open      <- openVersion(details)
              _         <- ctx.check(Action.EditDraft, resource(p, details, open))
              submitted <- ctx.service.submit(DocumentId(id), p.userId)
            yield versionView(submitted)
          }
      )

  private val deviationRequests = documents / documentId / "deviation-requests"

  val requestDeviation: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(deviationRequests)
      .in(jsonBody[RequestDeviation])
      .out(statusCode(StatusCode.Created).and(jsonBody[DeviationsView]))
      .description(
        "Records a deviation request for a locked section of the draft's template: with a reason, the section's " +
          "standard text may be changed; the change then needs the additional review step. One active request " +
          "per section (409); unknown or editable sections and empty reasons are rejected (422). Returns the " +
          "draft's deviation report."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, req) =>
          inTransaction(p) { ctx =>
            for
              details <- ctx.service.get(DocumentId(id))
              open    <- openVersion(details)
              _       <- ctx.check(Action.EditDraft, resource(p, details, open))
              result  <- ctx.service.requestDeviation(
                DocumentId(id),
                SectionId(req.sectionId.trim),
                req.reason,
                p.userId
              )
              requests <- ctx.service.deviationRequests(result._2.id)
            yield deviationsView(result._2, requests)
          }
      )

  val withdrawDeviation: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(deviationRequests / path[UUID]("requestId").description("Deviation request id") / "withdraw")
      .out(jsonBody[DeviationsView])
      .description(
        "Withdraws a deviation request while the version is a draft. A section that still deviates then blocks " +
          "submission until its standard text is restored or the deviation is requested again. Returns the " +
          "draft's deviation report."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, requestId) =>
          inTransaction(p) { ctx =>
            for
              details  <- ctx.service.get(DocumentId(id))
              open     <- openVersion(details)
              _        <- ctx.check(Action.EditDraft, resource(p, details, open))
              updated  <- ctx.service.withdrawDeviation(DocumentId(id), requestId, p.userId)
              requests <- ctx.service.deviationRequests(updated.id)
            yield deviationsView(updated, requests)
          }
      )

  val deviations: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(
        documents / documentId / "versions" / path[UUID]("versionId").description("Version id") / "deviations"
      )
      .out(jsonBody[DeviationsView])
      .description(
        "The deviation report of a version (changes of locked template sections with location, change and the " +
          "author's reasons) and its deviation requests, e.g. for the additional review step"
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, versionId) =>
          inTransaction(p) { ctx =>
            for
              details <- ctx.service.get(DocumentId(id))
              version <- ZIO
                .fromOption(details.versions.find(_.id == versionId))
                .orElseFail(DocumentError.NotFound(s"document $id has no version $versionId"))
              _        <- ctx.check(Action.ReadDocument, resource(p, details, version))
              requests <- ctx.service.deviationRequests(versionId)
            yield deviationsView(version, requests)
          }
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] =
    List(create, get, versions, updateDraft, submit, requestDeviation, withdrawDeviation, deviations)

  /** What a request may use inside its transaction. */
  private final class Context(val service: DocumentService, authz: Authorizer, subject: Subject):
    def check(action: Action, resource: Resource): IO[AccessDenied, Unit] =
      authz.check(subject, action, resource).unit

    /** The versions the caller may read; denied (and logged) if there is none. */
    def readable(details: DocumentDetails): IO[AccessDenied, List[DocumentVersion]] =
      for
        now <- Clock.instant
        visible = details.versions.filter(v =>
          Policy.decide(subject, Action.ReadDocument, resource(subject, details, v), now).isPermitted
        )
        _ <- ZIO.when(visible.isEmpty)(
          details.versions.lastOption.fold(ZIO.unit)(v =>
            check(Action.ReadDocument, resource(subject, details, v))
          )
        )
      yield visible

    private def resource(s: Subject, details: DocumentDetails, v: DocumentVersion): Resource =
      DocumentApi.resource(s.tenantId, details, v)

  private def inTransaction[A](principal: Principal)(
      f: Context => ZIO[Any, DocumentError | AccessDenied, A]
  ): ZIO[Db & Authorizer & DocumentService, (StatusCode, ApiError), A] =
    (for
      db      <- ZIO.service[Db]
      authz   <- ZIO.service[Authorizer]
      service <- ZIO.service[DocumentService]
      result  <- db.transact(principal.tenantId)(
        authz.subject(principal).flatMap(subject => f(Context(service, authz, subject)))
      )
    yield result).mapError(toResponse)

  private def openVersion(details: DocumentDetails): IO[DocumentError, DocumentVersion] =
    ZIO
      .fromOption(details.open)
      .orElseFail(DocumentError.Conflict(s"document ${details.document.id.value} has no open version"))

  /** Values a business system set keep their origin until the system changes them. */
  private def keepExternal(v: DocumentVersion): Map[String, FieldEntry] =
    v.values.filter((_, e) =>
      e.origin match
        case ValueOrigin.System(source) => source != "dms"
        case _                          => false
    )

  private def text(values: Map[String, MetadataValue], key: String): Option[String] =
    values.get(key).collect {
      case MetadataValue.Text(t)      => t
      case MetadataValue.Selection(c) => c
    }

  private def confidentiality(values: Map[String, MetadataValue]): Confidentiality =
    text(values, "confidentiality")
      .flatMap(c => Try(Confidentiality.valueOf(c)).toOption)
      .getOrElse(Confidentiality.Internal)

  private def owner(values: Map[String, MetadataValue]): Option[UserId] =
    values.get("owner").collect { case MetadataValue.PersonRef(u) => UserId(u) }

  /** The resource of a document to be created by the caller from a template of `profile`'s class. */
  private def creationResource(p: Principal, profile: MetadataProfile, values: Map[String, MetadataValue]) =
    Resource(
      p.tenantId,
      organizationalUnit = text(values, "organizationalUnit"),
      documentClassPath = profile.classPath.map(_.value),
      confidentiality = confidentiality(values),
      status = Some(DocumentStatus.Draft),
      authorId = Some(p.userId),
      ownerId = owner(values)
    )

  private def resource(p: Principal, details: DocumentDetails, v: DocumentVersion): Resource =
    resource(p.tenantId, details, v)

  private[app] def resource(
      tenant: dms.kernel.TenantId,
      details: DocumentDetails,
      v: DocumentVersion
  ): Resource =
    val values = v.metadata
    Resource(
      tenant,
      organizationalUnit = text(values, "organizationalUnit"),
      documentClassPath = details.profile.classPath.map(_.value),
      confidentiality = confidentiality(values),
      status = Some(v.status),
      authorId = Some(v.createdBy),
      ownerId = details.document.ownerId,
      approvedBy = v.releasedBy.toSet
    )

  private def documentView(d: Document, profile: MetadataProfile, current: DocumentVersion): DocumentView =
    DocumentView(
      d.id.value,
      d.classId.value,
      d.classVersion,
      profile.classPath.map(_.value),
      d.title,
      d.ownerId.map(_.value),
      d.authorId.value,
      d.templateId.value,
      d.templateVersion,
      d.createdAt,
      versionView(current)
    )

  private[app] def versionView(v: DocumentVersion): DocumentVersionView =
    DocumentVersionView(
      v.id,
      v.label,
      v.workingState,
      v.status.toString,
      v.changeKind.toString,
      v.changeReason,
      v.scope,
      v.classVersion,
      v.templateId.value,
      v.templateVersion,
      v.content,
      v.values.map((k, e) =>
        k -> (e.origin match
          case ValueOrigin.Manual         => FieldValueView(e.value, "Manual", None)
          case ValueOrigin.System(source) => FieldValueView(e.value, "System", Some(source))
          case ValueOrigin.Computed       => FieldValueView(e.value, "Computed", None))
      ),
      v.clauses.map(ClauseView(_, _)),
      v.rendition.map(r => RenditionView(r.objectKey, r.objectVersion, r.sha256)),
      v.metadataSnapshot.map(_.values),
      v.createdBy.value,
      v.createdAt,
      v.updatedAt,
      v.releasedBy.map(_.value),
      v.releasedAt,
      v.requiresExtraReview
    )

  private[app] def deviationsView(
      v: DocumentVersion,
      requests: List[RecordedDeviationRequest]
  ): DeviationsView =
    DeviationsView(
      v.id,
      v.label,
      v.status.toString,
      v.requiresExtraReview,
      requests.map(r =>
        DeviationRequestView(
          r.id,
          r.sectionId.value,
          r.reason,
          r.requestedBy.value,
          r.requestedAt,
          r.basedOn,
          r.active,
          r.withdrawnBy.map(_.value),
          r.withdrawnAt
        )
      ),
      v.deviations.deviations.map(d =>
        DeviationView(
          d.section.value,
          d.location.path,
          d.location.token,
          d.location.show,
          d.change,
          d.authorized,
          d.reasons
        )
      )
    )

  private def issueViews(issues: List[FieldIssue]): Option[List[IssueView]] =
    Some(issues.map(i => IssueView(i.field, i.label, i.kind.code, i.kind.message)))

  def toResponse(error: AccessDenied | DbError | DocumentError): (StatusCode, ApiError) = error match
    case AccessDenied(_, reason) =>
      (StatusCode.Forbidden, ApiError("forbidden", s"You are not allowed to do this: $reason."))
    case DocumentError.NotFound(message) => (StatusCode.NotFound, ApiError("not_found", message))
    case e @ DocumentError.Invalid(_)    => (StatusCode.UnprocessableEntity, ApiError("invalid", e.describe))
    case e @ DocumentError.InvalidValues(issues) =>
      (StatusCode.UnprocessableEntity, ApiError("invalid_values", e.describe, issueViews(issues)))
    case e @ DocumentError.Incomplete(_, issues) =>
      (StatusCode.UnprocessableEntity, ApiError("incomplete", e.describe, issueViews(issues)))
    case e @ DocumentError.Lifecycle(LifecycleError.SegregationOfDutiesViolated(_)) =>
      (StatusCode.Forbidden, ApiError("segregation_of_duties", e.describe))
    case e @ DocumentError.Lifecycle(_)  => (StatusCode.Conflict, ApiError("lifecycle", e.describe))
    case DocumentError.Conflict(message) => (StatusCode.Conflict, ApiError("conflict", message))
    case DocumentError.Database(e)       => databaseError(e)
    case e: DbError                      => databaseError(e)

  private def databaseError(error: DbError): (StatusCode, ApiError) =
    (
      StatusCode.InternalServerError,
      ApiError("database_error", s"The request could not be completed ($error).")
    )
