package dms.app

import dms.authz.{AccessDenied, Action, Authorizer, DocumentResource, Groups, Policy, Resource, Subject}
import dms.db.{Db, DbError}
import dms.documents.*
import dms.documents.core.*
import dms.documents.core.MetadataJson.given
import dms.documents.metadata.{CoreFields, FieldIssue, IssueKind, MetadataProfile, MetadataValue}
import dms.identity.{Authenticator, Principal}
import dms.kernel.{DocumentId, TemplateId, UserId}
import dms.templates.{DeviationChange, Inline, PathStep, SectionId, TemplateContent}
import dms.workflow.CaseDetails
import dms.workflow.core.{ApprovalWorkflow, WorkflowError}
import dms.templates.core.ContentJson.given
import dms.templates.core.DeviationJson.given
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.generic.Configuration
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.time.{Instant, LocalDate}
import java.util.UUID

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
    /** Replaces the entered values of the draft. Values from business systems are kept and cannot be changed
      * here (see `/references`); computed values are recomputed.
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

/** The version readers of another version are pointed to (DMS-45): the one that replaced a superseded
  * version, or the approved version that replaces a valid one on its valid-from date.
  */
final case class SuccessorView(
    versionId: UUID,
    version: String,
    /** Published (or ReviewDue) if it is valid already, Approved if it is scheduled. */
    status: String,
    validFrom: Option[LocalDate],
    /** For a scheduled successor: when it becomes valid (00:00 of its valid-from date, tenant time zone). */
    publicationDue: Option[Instant]
) derives JsonCodec,
      Schema

/** The automatic comparison of an editorial (minor) version with the version it is based on (DMS-46). */
final case class EditorialCheckView(
    /** The released version compared with. */
    basedOn: String,
    /** Share of the text that changed, 0 to 1. */
    changedRatio: Double,
    /** Configured threshold (`dms.documents.editorialChangeThresholdPercent`). */
    threshold: Double,
    /** True: more text changed than an editorial correction usually does; check the classification. */
    exceedsThreshold: Boolean
) derives JsonCodec,
      Schema

final case class NewVersion(
    /** Major (change of content, full workflow) or Minor (editorial correction, shortened workflow). */
    changeKind: String,
    /** The author's change summary, shown above version comparisons (DMS-54). Required for Minor: why the
      * change is editorial; optional for Major.
      */
    reason: Option[String],
    /** Whether publishing the version asks readers for a new acknowledgement. Default: yes for Major, the
      * configured default (`dms.documents.minorVersionsRequireAcknowledgement`, normally no) for Minor.
      */
    requiresAcknowledgement: Option[Boolean]
) derives JsonCodec,
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
    /** The author's change summary given when the version was started. */
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
    requiresExtraReview: Boolean,
    /** Valid-from date: of the field while the version is open, frozen on approval. */
    validFrom: Option[LocalDate] = None,
    /** For an approved version: when it is published automatically (00:00 of its valid-from date in the
      * tenant's time zone).
      */
    publicationDue: Option[Instant] = None,
    /** For a superseded version the version that replaced it; for a valid version the approved version that
      * will replace it. Readers of the old version are pointed to the new one.
      */
    successor: Option[SuccessorView] = None,
    /** Whether publishing this version asks readers for a new acknowledgement (minor versions: no by
      * default).
      */
    requiresAcknowledgement: Boolean = true,
    /** For minor versions: the automatic comparison with the version they are based on. */
    editorialCheck: Option[EditorialCheckView] = None,
    /** When the version became valid (DMS-47). */
    publishedAt: Option[Instant] = None
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

/** A document in the document list with the version the caller sees (its open version if readable, otherwise
  * its latest readable version).
  */
final case class DocumentSummaryView(
    id: UUID,
    title: String,
    classPath: List[String],
    templateId: UUID,
    templateVersion: Int,
    authorId: String,
    ownerId: Option[String],
    versionId: UUID,
    /** `1.1-draft.3` while open, `1.1` from approval on. */
    version: String,
    /** DocumentStatus, e.g. Draft, InReview, Published. */
    status: String,
    updatedAt: Instant,
    /** True if the caller created the document or the shown version. */
    mine: Boolean
) derives JsonCodec,
      Schema

/** Documents created from released templates (business concept 4.3) and their versions (5.1, 5.2).
  *
  * Creating requires `CreateFromTemplate` in the scope of the template's document class; reading requires
  * `ReadDocument` on a version (by status: drafts for authors and owners, published versions for readers);
  * changing and submitting a draft and requesting or withdrawing deviations require `EditDraft`; the
  * deviation report of a version requires `ReadDocument` on it. Submitting starts the review and approval
  * workflow of the document's class (module `workflow`); the participants of a version's workflow may read
  * it.
  */
object DocumentApi:
  type Env = Authenticator & Db & Authorizer & DocumentService & ApprovalWorkflow

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
              _       <- ctx.checkGroups(profile, req.values)
              created <- ctx.service.create(
                NewDocument(TemplateId(req.templateId), req.templateVersion, req.title, values),
                p.userId
              )
              (document, version) = created
            yield documentView(document, profile, versionView(version))
          }
      )

  /** Largest page of the document list. */
  val MaxListSize: Int = 200

  val list: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(documents)
      .in(
        query[Option[Int]]("limit").description(
          s"Most recently changed documents to look at, 1 to $MaxListSize; default 50"
        )
      )
      .out(jsonBody[List[DocumentSummaryView]])
      .description(
        "The most recently changed documents the caller may read, each with its open version if readable " +
          "(e.g. the caller's own drafts) or its latest readable version. Documents without a readable version " +
          "are left out, so the list can be shorter than `limit`."
      )
      .tag("Documents")
      .serverLogic(p =>
        limit =>
          inTransaction(p) { ctx =>
            for
              size <- ZIO
                .succeed(limit.getOrElse(50))
                .filterOrFail(l => l >= 1 && l <= MaxListSize)(
                  DocumentError.Invalid(List(s"limit must be between 1 and $MaxListSize"))
                )
              recent   <- ctx.service.recent(size)
              readable <- ZIO.foreach(recent)(details => ctx.visible(details).map(details -> _))
            yield readable.collect { case (details, versions @ (_ :: _)) =>
              val current = versions.find(v => Versioning.isOpen(v.status)).getOrElse(versions.last)
              summaryView(details, current, p.userId)
            }
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
            yield documentView(
              details.document,
              details.profile,
              versionViewIn(details, ctx.service.settings)(current)
            )
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
            for
              details  <- ctx.service.get(DocumentId(id))
              readable <- ctx.readable(details)
            yield readable.map(versionViewIn(details, ctx.service.settings))
          }
      )

  val updateDraft: ZServerEndpoint[Env, Any] =
    SecuredApi.base.put
      .in(documents / documentId / "draft")
      .in(jsonBody[UpdateDraft])
      .out(jsonBody[DocumentVersionView])
      .description(
        "Changes the draft and saves it as the next working state. Locked sections and computed fields of the " +
          "template cannot be changed (422); versions in review or later cannot be changed (409). Values taken " +
          "over from a business system (origin System, e.g. the partner master) keep their origin; a different " +
          "value for them is rejected (422 `not_editable`)."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, req) =>
          inTransaction(p) { ctx =>
            for
              details <- ctx.service.get(DocumentId(id))
              open    <- openVersion(details)
              _       <- ctx.check(Action.EditDraft, resource(p, details, open))
              _       <- ctx.checkGroups(details.profile, req.values)
              entered = req.values.map((k, v) => k -> FieldEntry(v, ValueOrigin.Manual))
              updated <- ctx.service.updateDraft(
                DocumentId(id),
                DraftChange(req.title, keepExternal(open) ++ entered, req.content),
                p.userId
              )
            yield versionViewIn(details, ctx.service.settings)(updated)
          }
      )

  val submit: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(documents / documentId / "submit")
      .out(jsonBody[DocumentVersionView])
      .description(
        "Submits the draft for review and starts the workflow of the document class (or the next round after " +
          "a return). Blocked while fields required in review are missing (422 with issues)."
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
              _         <- ctx.workflow.start(DocumentId(id), submitted.id, p.userId)
            yield versionViewIn(details, ctx.service.settings)(submitted)
          }
      )

  val newVersion: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(documents / documentId / "versions")
      .in(jsonBody[NewVersion])
      .out(statusCode(StatusCode.Created).and(jsonBody[DocumentVersionView]))
      .description(
        "Starts the next version from the valid version as a draft: Major for a change of content (full " +
          "workflow of the class), Minor for an editorial correction (reason required, 422; on submission the " +
          "owner confirms it in a shortened workflow, and an automatic comparison warns in `editorialCheck` if " +
          "more text changed than the configured threshold). 409 while a version is open or an approved version " +
          "waits for its valid-from date."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, req) =>
          inTransaction(p) { ctx =>
            for
              kind <- ZIO
                .fromOption(ChangeKind.values.find(_.toString == req.changeKind))
                .orElseFail(
                  DocumentError.Invalid(List(s"unknown change kind ${req.changeKind}; Major or Minor"))
                )
              details <- ctx.service.get(DocumentId(id))
              base    <- ZIO
                .fromOption(details.versions.filter(v => Versioning.isValid(v.status)).lastOption)
                .orElseFail(
                  DocumentError.Conflict("only a document with a valid version can get a new version")
                )
              // The caller becomes the author of a draft of this document.
              _ <- ctx.check(
                Action.EditDraft,
                resource(p, details, base)
                  .copy(status = Some(DocumentStatus.Draft), authorId = Some(p.userId))
              )
              created <- ctx.service
                .newVersion(DocumentId(id), kind, req.reason, p.userId, req.requiresAcknowledgement)
            yield versionViewIn(details.copy(versions = details.versions :+ created), ctx.service.settings)(
              created
            )
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
              _        <- ctx.checkRead(details, version)
              requests <- ctx.service.deviationRequests(versionId)
            yield deviationsView(version, requests)
          }
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] =
    List(
      create,
      list,
      get,
      versions,
      updateDraft,
      submit,
      newVersion,
      requestDeviation,
      withdrawDeviation,
      deviations
    )

  /** What a request may use inside its transaction. */
  private[app] final class Context(
      val service: DocumentService,
      val workflow: ApprovalWorkflow,
      authz: Authorizer,
      subject: Subject
  ):
    def check(action: Action, resource: Resource): IO[AccessDenied, Unit] =
      authz.check(subject, action, resource).unit

    /** Entered access groups (core field `allowedGroups`, ADR 0032) must exist in the tenant (422
      * `unknown_option` otherwise); their format is checked with the profile.
      */
    def checkGroups(profile: MetadataProfile, values: Map[String, MetadataValue]): IO[DocumentError, Unit] =
      val entered = DocumentResource.allowedGroups(values).filter(Groups.isValidKey)
      authz.groups.existing(entered).mapError(DocumentError.Database(_)).flatMap { known =>
        val label = profile.field(CoreFields.AllowedGroups).fold(CoreFields.AllowedGroups)(_.definition.label)
        val issues = (entered -- known).toList.sorted
          .map(key => FieldIssue(CoreFields.AllowedGroups, label, IssueKind.UnknownOption(key)))
        ZIO.unless(issues.isEmpty)(ZIO.fail(DocumentError.InvalidValues(issues))).unit
      }

    /** Whether the caller may do `action` on `resource`, without logging a denial: for filtering lists. */
    def allowed(action: Action, resource: Resource): UIO[Boolean] =
      Clock.instant.map(now => Policy.decide(subject, action, resource, now).isPermitted)

    /** The versions the caller may read, possibly none. */
    def visible(details: DocumentDetails): IO[WorkflowError, List[DocumentVersion]] =
      visibleWith(details).map(_._1)

    /** The versions the caller may read; denied (and logged) if there is none. */
    def readable(details: DocumentDetails): IO[AccessDenied | WorkflowError, List[DocumentVersion]] =
      for
        result <- visibleWith(details)
        (visible, participants, actor) = result
        _ <- ZIO.when(visible.isEmpty)(
          details.versions.lastOption.fold(ZIO.unit)(v =>
            authz.check(actor, Action.ReadDocument, resource(actor, details, v, participants)).unit
          )
        )
      yield visible

    /** The readable versions, the workflow participants and the subject they were decided for: the caller, as
      * deputy with the principal's scope where they may complete a task of the document (DMS-49).
      */
    private def visibleWith(
        details: DocumentDetails
    ): IO[WorkflowError, (List[DocumentVersion], Map[UUID, Set[UserId]], Subject)] =
      for
        cases <- workflow.cases(details.document.id)
        participants = participantsOf(cases)
        actor <- Delegation.subject(authz, subject, cases.flatMap(_.tasks))
        now   <- Clock.instant
      yield (
        details.versions.filter(v =>
          Policy
            .decide(actor, Action.ReadDocument, resource(actor, details, v, participants), now)
            .isPermitted
        ),
        participants,
        actor
      )

    /** `ReadDocument` on one version; the participants of its workflow (e.g. the extra review step) and their
      * deputies (DMS-49) may read it.
      */
    def checkRead(details: DocumentDetails, v: DocumentVersion): IO[AccessDenied | WorkflowError, Unit] =
      for
        cases <- workflow.cases(details.document.id)
        actor <- Delegation.subject(authz, subject, cases.flatMap(_.tasks))
        _     <- authz.check(actor, Action.ReadDocument, resource(actor, details, v, participantsOf(cases)))
      yield ()

    private def resource(
        s: Subject,
        details: DocumentDetails,
        v: DocumentVersion,
        participants: Map[UUID, Set[UserId]]
    ): Resource =
      DocumentApi.resource(s.tenantId, details, v, participants)

  private[app] def inTransaction[A](principal: Principal)(
      f: Context => ZIO[Any, DocumentError | AccessDenied | WorkflowError, A]
  ): ZIO[Db & Authorizer & DocumentService & ApprovalWorkflow, (StatusCode, ApiError), A] =
    (for
      db       <- ZIO.service[Db]
      authz    <- ZIO.service[Authorizer]
      service  <- ZIO.service[DocumentService]
      workflow <- ZIO.service[ApprovalWorkflow]
      result   <- db.transact(principal.tenantId)(
        authz.subject(principal).flatMap(subject => f(Context(service, workflow, authz, subject)))
      )
    yield result).mapError {
      case e: WorkflowError => WorkflowApi.toResponse(e)
      case e: AccessDenied  => toResponse(e)
      case e: DbError       => toResponse(e)
      case e: DocumentError => toResponse(e)
    }

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

  private def owner(values: Map[String, MetadataValue]): Option[UserId] =
    values.get("owner").collect { case MetadataValue.PersonRef(u) => UserId(u) }

  /** The resource of a document to be created by the caller from a template of `profile`'s class. */
  private def creationResource(p: Principal, profile: MetadataProfile, values: Map[String, MetadataValue]) =
    Resource(
      p.tenantId,
      organizationalUnit = DocumentResource.organizationalUnit(values),
      documentClassPath = profile.classPath.map(_.value),
      confidentiality = DocumentResource.confidentiality(values),
      status = Some(DocumentStatus.Draft),
      authorId = Some(p.userId),
      ownerId = owner(values),
      allowedGroups = DocumentResource.allowedGroups(values)
    )

  private def resource(p: Principal, details: DocumentDetails, v: DocumentVersion): Resource =
    resource(p.tenantId, details, v)

  /** Everyone the workflow of a version assigned to one of its tasks, by version id. */
  private[app] def participantsOf(cases: List[CaseDetails]): Map[UUID, Set[UserId]] =
    cases
      .map(c =>
        c.workflowCase.versionId -> c.tasks.flatMap(t => t.candidates ++ t.deputies.map(_.deputy)).toSet
      )
      .toMap

  private[app] def resource(
      tenant: dms.kernel.TenantId,
      details: DocumentDetails,
      v: DocumentVersion,
      participants: Map[UUID, Set[UserId]] = Map.empty
  ): Resource =
    DocumentResource(
      tenant,
      details.document,
      details.profile.classPath.map(_.value),
      v,
      participants.getOrElse(v.id, Set.empty)
    )

  /** The resource of a document version from its parts, e.g. for versions read without their content. */
  private[app] def versionResource(
      tenant: dms.kernel.TenantId,
      classPath: List[String],
      ownerId: Option[UserId],
      status: DocumentStatus,
      createdBy: UserId,
      values: Map[String, MetadataValue],
      releasedBy: Option[UserId]
  ): Resource =
    Resource(
      tenant,
      organizationalUnit = DocumentResource.organizationalUnit(values),
      documentClassPath = classPath,
      confidentiality = DocumentResource.confidentiality(values),
      status = Some(status),
      authorId = Some(createdBy),
      ownerId = ownerId,
      approvedBy = releasedBy.toSet,
      allowedGroups = DocumentResource.allowedGroups(values)
    )

  private def documentView(
      d: Document,
      profile: MetadataProfile,
      current: DocumentVersionView
  ): DocumentView =
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
      current
    )

  private def summaryView(details: DocumentDetails, v: DocumentVersion, caller: UserId): DocumentSummaryView =
    val d = details.document
    DocumentSummaryView(
      d.id.value,
      d.title,
      details.profile.classPath.map(_.value),
      d.templateId.value,
      d.templateVersion,
      d.authorId.value,
      d.ownerId.map(_.value),
      v.id,
      v.label,
      v.status.toString,
      v.updatedAt,
      d.authorId == caller || v.createdBy == caller
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
      v.requiresExtraReview,
      validFrom = v.validFrom.orElse(Publication.validFrom(v.metadata)),
      requiresAcknowledgement = v.requiresAcknowledgement,
      publishedAt = v.publishedAt
    )

  /** The view of a version with what readers need from the other versions of its document: when an approved
    * version is published, the successor of a superseded or valid version (DMS-45) and the editorial check of
    * a minor version (DMS-46).
    */
  private[app] def versionViewIn(details: DocumentDetails, settings: DocumentSettings)(
      v: DocumentVersion
  ): DocumentVersionView =
    versionView(v).copy(
      publicationDue = details.publicationDue(v),
      successor = details
        .successor(v)
        .map(s => SuccessorView(s.id, s.label, s.status.toString, s.validFrom, details.publicationDue(s))),
      editorialCheck = details
        .editorialCheck(v, settings.editorialChangeThreshold)
        .map(c => EditorialCheckView(c.basedOn, c.changedRatio, c.threshold, c.exceedsThreshold))
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
