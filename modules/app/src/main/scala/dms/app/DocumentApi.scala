package dms.app

import dms.archive.core.LegalHoldStore
import dms.authz.{
  AccessDenied,
  Action,
  Authorizer,
  Confidentiality,
  DocumentResource,
  Groups,
  Policy,
  Resource,
  Subject
}
import dms.db.{Db, DbError}
import dms.documents.*
import dms.documents.core.*
import dms.documents.core.MetadataJson.given
import dms.documents.metadata.{CoreFields, FieldIssue, IssueKind, MetadataProfile, MetadataValue}
import dms.identity.{Authenticator, Principal}
import dms.kernel.{DocumentId, TemplateId, UserId}
import dms.templates.{DeviationChange, Inline, PathStep, SectionId, TemplateContent}
import dms.workflow.{CaseDetails, StepKind}
import dms.workflow.core.{ApprovalWorkflow, Rules, WorkflowError}
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
    values: Map[String, MetadataValue],
    /** `Structured` (default): the content is the template content, edited in the platform. `File`: the
      * content is a file uploaded into the draft (`PUT /api/v1/documents/{id}/file`); the template supplies
      * class and fields.
      */
    kind: Option[String] = None
) derives JsonCodec,
      Schema

/** The uploaded original of a version of a file document (DMS-115). */
final case class VersionFileView(
    versionId: UUID,
    /** File name of the original. */
    name: String,
    /** Media type, from the upload or guessed from the name. */
    contentType: String,
    size: Long,
    /** SHA-256 (lowercase hex) of the original as uploaded; the approval binds it. */
    sha256: String,
    /** Clean (checked by the virus scanner) or NotScanned (no scanner configured). */
    scanStatus: String,
    uploadedBy: String,
    uploadedAt: Instant,
    /** The version whose file this version took over when it was started; empty after an upload into it. */
    basedOn: Option[UUID],
    /** Where to download the original unchanged. */
    downloadUrl: String,
    /** A PDF for reading in the browser: the archived rendition of an approved version, otherwise the
      * original converted on request.
      */
    previewUrl: String
) derives JsonCodec,
      Schema

final case class UpdateDraft(
    /** Default: the current title. */
    title: Option[String],
    /** Replaces the entered values of the draft; default: they stay as they are. Values from business systems
      * are kept and cannot be changed here (see `/references`); computed values are recomputed.
      */
    values: Option[Map[String, MetadataValue]],
    /** Default: the current content. A locked section keeps the template's text unless it has an active
      * deviation request.
      */
    content: Option[TemplateContent],
    /** The `workingState` this change was made on. If the draft has moved on, the save fails with 409 instead
      * of overwriting what was saved since. Default: no check.
      */
    expectedWorkingState: Option[Int] = None
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

/** The withdrawal of an approved version before it took effect (DMS-143, ADR 0059). */
final case class WithdrawalView(
    at: Instant,
    by: String,
    reason: String,
    /** The approver `by` stood in for as deputy (DMS-145, ADR 0070). */
    actingFor: Option[String] = None
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
    /** The author's change summary, shown above version comparisons (DMS-54): what changed and why. Required
      * for every new version, Major and Minor (DMS-126); for Minor it also says why the change is editorial.
      */
    reason: Option[String],
    /** Whether publishing the version asks readers for a new acknowledgement. Default: yes for Major, the
      * configured default (`dms.documents.minorVersionsRequireAcknowledgement`, normally no) for Minor.
      */
    requiresAcknowledgement: Option[Boolean]
) derives JsonCodec,
      Schema

final case class UpdateChangeSummary(
    /** The author's change summary of the draft (DMS-103): what changed and why. Required for every version
      * with a predecessor (DMS-126); only the first version 1.0 of a document may have none (empty or missing
      * removes it there).
      */
    reason: Option[String]
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
    publishedAt: Option[Instant] = None,
    /** For a file document (DMS-115): the uploaded original of this version, empty before the first upload.
      */
    file: Option[VersionFileView] = None,
    /** Set if the version was withdrawn before it became valid, after its approval or because its rendition
      * failed for good in approval: it was never in force.
      */
    withdrawal: Option[WithdrawalView] = None,
    /** Everyone who started or changed this version, sorted: none of them approves it (ADR 0075). */
    editors: List[String] = Nil,
    /** The level access is decided on where the version states a lower one: an approved lowering takes effect
      * only with the publication of this version, and never if it is not published (ADR 0083). Empty
      * otherwise.
      */
    accessFloor: Option[String] = None
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
    current: DocumentVersionView,
    /** True while an active legal hold covers the document (DMS-56): it cannot be destroyed. */
    legalHold: Boolean = false,
    /** The active legal holds covering the document; only for callers who may manage legal holds of it. */
    legalHolds: Option[List[LegalHoldRefView]] = None,
    /** The document number (DMS-113), from the number range of the class or assigned manually. */
    documentNumber: Option[String] = None,
    /** True if the caller may assign a number manually now (`POST /api/v1/documents/{id}/number`). */
    canAssignNumber: Boolean = false,
    /** `Structured` (content from the template) or `File` (an uploaded original per version, DMS-115). */
    kind: String = "Structured",
    /** True if the caller may start the next version now (`POST /api/v1/documents/{id}/versions`, DMS-103).
      */
    canStartVersion: Boolean = false,
    /** For a caller who may start versions of this document: why a new version cannot be started now.
      * `OpenVersion`: a version is in progress (draft, review or approval); `ScheduledVersion`: an approved
      * version waits for its valid-from date. Empty if `canStartVersion` or if the caller may not start one.
      */
    startVersionBlocked: Option[String] = None,
    /** True if the caller may discard the open draft now (`POST /api/v1/documents/{id}/draft/discard`,
      * DMS-129): the open version is a draft that is not submitted, and the caller is its author, the
      * document's owner or a tenant administrator.
      */
    canDiscardDraft: Boolean = false,
    /** The version the caller may withdraw now (`POST /api/v1/documents/{id}/versions/{versionId}/withdraw`,
      * DMS-143): it waits for its valid-from date, or its publication failed for good (DMS-145), or it is in
      * approval and its rendition failed for good (DMS-163), and the caller holds the owner role or approved
      * it.
      */
    withdrawable: Option[WithdrawableVersionView] = None,
    /** True if the caller may change the open draft but voted on it, or holds an open task on it, in a step
      * that excludes authors: their first change costs them that vote and any vote on this version (DMS-163,
      * ADR 0078).
      */
    changeCostsVote: Boolean = false,
    /** The highest version number ever approved: new versions count on from it, also if that version was
      * withdrawn before it took effect, whose number is not given again.
      */
    latestReleasedVersion: Option[String] = None
) derives JsonCodec,
      Schema

/** The approved version the caller may withdraw now (DMS-143, ADR 0059; DMS-145, ADR 0061), for the dialog.
  */
final case class WithdrawableVersionView(
    versionId: UUID,
    version: String,
    validFrom: Option[LocalDate],
    /** The version that stays valid; empty if the document has none: it then stays without a valid version.
      */
    validVersion: Option[String],
    /** Whether every attempt to publish it failed: it can then be withdrawn after its valid-from date, too.
      */
    publicationFailed: Boolean,
    /** Whether every attempt to produce its archive PDF failed: it is then still in approval, and withdrawing
      * is the way out (DMS-163, ADR 0078).
      */
    renditionFailed: Boolean = false,
    /** The approver the caller would act for: their right rests on a vote cast as deputy (DMS-145, ADR 0070).
      */
    actingFor: Option[String] = None
) derives JsonCodec,
      Schema

final case class DiscardDraft(
    /** Why the draft is discarded, e.g. "created by accident"; optional, at most 4000 characters, recorded in
      * the audit trail.
      */
    reason: Option[String]
) derives JsonCodec,
      Schema

/** The outcome of discarding a draft (DMS-129). */
final case class DiscardedDraftView(
    documentId: UUID,
    versionId: UUID,
    /** The label the draft had, e.g. `2.0-draft.3`. */
    version: String,
    /** True if the draft was the document's first version, never approved: the document no longer appears in
      * lists, search, library or task inbox. False for a new version of an approved document, whose valid
      * version stays.
      */
    documentDiscarded: Boolean,
    /** The valid version of the document, if any: where readers continue. */
    validVersionId: Option[UUID],
    validVersion: Option[String],
    /** Attachment links of the draft that were removed. */
    removedAttachments: Int,
    /** Stored files no version references any more, released for deletion. */
    releasedFiles: Int,
    /** Unreferenced files kept because the document is under legal hold. */
    keptFiles: Int,
    /** Workflow tasks of the draft that were cancelled. */
    cancelledTasks: Int
) derives JsonCodec,
      Schema

final case class AssignDocumentNumber(
    /** Letters, digits, `.`, `_` and `-`, at most 64 characters, starting with a letter or digit. */
    number: String,
    /** Why the number is assigned manually; required, recorded in the audit trail. */
    reason: String
) derives JsonCodec,
      Schema

final case class DocumentNumberView(
    documentId: UUID,
    documentNumber: String,
    /** The number before, if the document had one. */
    previous: Option[String]
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
    mine: Boolean,
    /** The document number (DMS-113), if it has one. */
    documentNumber: Option[String] = None,
    /** `Structured` or `File` (DMS-115). */
    kind: String = "Structured",
    /** True while an active legal hold covers the document (DMS-106); which holds is not shown here. */
    legalHold: Boolean = false
) derives JsonCodec,
      Schema

/** Documents created from released templates (business concept 4.3) and their versions (5.1, 5.2).
  *
  * Creating requires `CreateFromTemplate` in the scope of the template's document class; reading requires
  * `ReadDocument` on a version (by status: drafts for authors and owners, published versions for readers);
  * changing and submitting a draft and requesting or withdrawing deviations require `EditDraft`; the
  * deviation report of a version requires `ReadDocument` on it. Submitting starts the review and approval
  * workflow of the document's class (module `workflow`); the participants of a version's workflow may read
  * it. The document view shows whether a legal hold covers the document (DMS-56).
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
          "Values are validated against the class profile (422 with issues); computed fields are evaluated. " +
          "With kind File the content is a file uploaded into the draft instead (PUT /api/v1/documents/{id}/file)."
      )
      .tag("Documents")
      .serverLogic(p =>
        req =>
          inTransaction(p) { ctx =>
            for
              context <- ctx.service.templateContext(TemplateId(req.templateId), req.templateVersion)
              (_, profile) = context
              values       = req.values.map((k, v) => k -> FieldEntry(v, ValueOrigin.Manual))
              kind <- ZIO
                .fromOption(
                  req.kind.fold(Some(DocumentKind.Structured))(k => DocumentKind.values.find(_.toString == k))
                )
                .orElseFail(DocumentError.Invalid(List("kind must be Structured or File")))
              _       <- ctx.check(Action.CreateFromTemplate, creationResource(p, profile, req.values))
              _       <- ctx.checkGroups(profile, req.values)
              created <- ctx.service.create(
                NewDocument(TemplateId(req.templateId), req.templateVersion, req.title, values, kind),
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
              readable <- ctx.visibleAll(recent)
              held     <- LegalHoldStore
                .heldAmong(readable.collect { case (d, _ :: _) => d.document.id }.toSet)
                .mapError(DocumentError.Database(_))
            yield readable.collect { case (details, versions @ (_ :: _)) =>
              val current = currentOf(versions)
              summaryView(details, current, p.userId).copy(legalHold = held.contains(details.document.id))
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
              current = currentOf(readable)
              holds   <- LegalHoldStore.activeHolds(details.document.id).mapError(DocumentError.Database(_))
              manage  <- ctx.allowed(Action.ManageRetention, resource(p, details, current))
              number  <- ctx.allowed(Action.AssignDocumentNumber, resource(p, details, current))
              start   <- startVersion(ctx, p, details)
              discard <- details.open.filter(_.status == DocumentStatus.Draft) match
                case Some(open) => ctx.allowed(Action.DiscardDraft, resource(p, details, open))
                case None       => ZIO.succeed(false)
              withdrawable <- withdrawableVersion(ctx, details)
              costsVote    <- changeCostsVote(ctx, details)
            yield documentView(
              details.document,
              details.profile,
              versionViewIn(details, ctx.service.settings)(current)
            ).copy(
              legalHold = holds.nonEmpty,
              legalHolds = Option.when(manage)(holds.map(LegalHoldApi.refView)),
              canAssignNumber = number && details.numberAssignable,
              canStartVersion = start.contains(None),
              startVersionBlocked = start.flatten,
              canDiscardDraft = discard,
              withdrawable = withdrawable,
              changeCostsVote = costsVote,
              latestReleasedVersion =
                details.versions.filter(_.releasedAt.isDefined).map(_.number).maxOption.map(_.show)
            )
          }
      )

  val assignNumber: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(documents / documentId / "number")
      .in(jsonBody[AssignDocumentNumber])
      .out(jsonBody[DocumentNumberView])
      .description(
        "Assigns a document number manually (DMS-113), e.g. one taken over from a previous system. The reason " +
          "is required (422) and recorded in the audit trail; the number must be free in the tenant (409). A " +
          "document without number gets one at any time except while a version is in review or approval; an " +
          "existing number can only be replaced while every version is a draft (409)."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, req) =>
          inTransaction(p) { ctx =>
            for
              details <- ctx.service.get(DocumentId(id))
              current <- ZIO
                .fromOption(details.open.orElse(details.versions.lastOption))
                .orElseFail(DocumentError.NotFound(s"document $id has no version"))
              _       <- ctx.check(Action.AssignDocumentNumber, resource(p, details, current))
              updated <- ctx.service.assignNumber(DocumentId(id), req.number, req.reason, p.userId)
            yield DocumentNumberView(id, updated.number.getOrElse(req.number.trim), details.document.number)
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
        "Changes the draft and saves it as the next working state. Locked sections without an active deviation " +
          "request and computed fields of the template cannot be changed (422); versions in review or later " +
          "cannot be changed (409), nor can a draft that has moved past `expectedWorkingState` (409). Values " +
          "taken over from a business system (origin System, e.g. the partner master) keep their origin; a " +
          "different value for them is rejected (422 `not_editable`). A first version sets owner and title of the " +
          "document; a later version keeps them in its values until it is published. A later version cannot be " +
          "less confidential than the version it started from without the additional approval (422 " +
          "`confidentiality_lowered`, also if the field " +
          "is left out), and only the document's owner can add access groups (422 `access_groups_widened`) or " +
          "move it to another organizational unit (422 `unit_moved`), one in which they can edit it (422 " +
          "`unit_out_of_reach`)."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, req) =>
          inTransaction(p) { ctx =>
            for
              details <- ctx.service.get(DocumentId(id))
              open    <- openVersion(details)
              _       <- ctx.check(Action.EditDraft, resource(p, details, open))
              _       <- ZIO.foreachDiscard(req.values)(ctx.checkGroups(details.profile, _))
              entered = req.values.fold(open.values.filter((_, e) => e.origin == ValueOrigin.Manual))(
                _.map((k, v) => k -> FieldEntry(v, ValueOrigin.Manual))
              )
              updated <- ctx.service.updateDraft(
                DocumentId(id),
                DraftChange(req.title, keepExternal(open) ++ entered, req.content, req.expectedWorkingState),
                p.userId
              )
              _ <- checkUnitReach(ctx.subject, details, open, updated)
            yield versionViewIn(details, ctx.service.settings)(updated)
          }
      )

  val submit: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(documents / documentId / "submit")
      .out(jsonBody[DocumentVersionView])
      .description(
        "Submits the draft for review and starts the workflow of the document class (or the next round after " +
          "a return). Blocked while fields required in review are missing (422 `incomplete` with issues), " +
          "while the content breaks the edit rules of the template (422 `unauthorized_deviations`: one issue " +
          "per violation, `field` is the section id, `label` its title, `code` the kind of violation) and while " +
          "a later version is less confidential than the version it started from (422 " +
          "`confidentiality_lowered`; a lower level is asked for at `POST …/confidentiality-lowering`)."
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
          "workflow of the class), Minor for an editorial correction (on submission the " +
          "owner confirms it in a shortened workflow, and an automatic comparison warns in `editorialCheck` if " +
          "more text changed than the configured threshold). Both need a change summary in `reason` (422 if " +
          "empty, DMS-126). 403 unless the caller may edit drafts and is cleared for the valid version: its " +
          "author, the document's owner or in one of its access groups, if it is confidential. 409 while a " +
          "version is open or an approved version waits for its valid-from date."
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
                .fromOption(Versioning.startingPoint(details.versions))
                .orElseFail(
                  DocumentError.Conflict("only a document with a valid version can get a new version")
                )
              _       <- ctx.check(Action.EditDraft, versionStart(p.tenantId, details, base))
              created <- ctx.service
                .newVersion(DocumentId(id), kind, req.reason, p.userId, req.requiresAcknowledgement)
            yield versionViewIn(details.copy(versions = details.versions :+ created), ctx.service.settings)(
              created
            )
          }
      )

  val updateChangeSummary: ZServerEndpoint[Env, Any] =
    SecuredApi.base.put
      .in(documents / documentId / "draft" / "change-summary")
      .in(jsonBody[UpdateChangeSummary])
      .out(jsonBody[DocumentVersionView])
      .description(
        "Changes the author's change summary of the open draft (DMS-103), shown in the version history and above " +
          "version comparisons. Requires EditDraft; only while the version is a draft (409 from submission on). " +
          "Every version with a predecessor, Major or Minor, needs a summary (422, DMS-126); only the first version " +
          "1.0 of a document may have none. At most 4000 characters (422). Content and values " +
          "are unchanged, so no working state is added. Recorded in the audit trail."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, req) =>
          inTransaction(p) { ctx =>
            for
              details <- ctx.service.get(DocumentId(id))
              open    <- openVersion(details)
              // In review, EditDraft means suggestions by reviewers; the summary stays the author's: readers of
              // a submitted version learn that it can no longer change (409).
              _ <-
                if Versioning.isEditable(open.status) then
                  ctx.check(Action.EditDraft, resource(p, details, open))
                else ctx.checkRead(details, open)
              updated <- ctx.service.updateChangeReason(DocumentId(id), req.reason, p.userId)
            yield versionViewIn(details, ctx.service.settings)(updated)
          }
      )

  val discardDraft: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(documents / documentId / "draft" / "discard")
      .in(jsonBody[DiscardDraft])
      .out(jsonBody[DiscardedDraftView])
      .description(
        "Discards the open draft (DMS-129), e.g. one created by accident; requires DiscardDraft (the draft's " +
          "author, the document's owner or a tenant administrator). Only a draft that is not submitted (409 " +
          "otherwise: submitted, approved and published versions are withdrawn or destroyed). The version is " +
          "kept as a record in status Discarded and is no longer readable. A first version takes the document " +
          "out of lists, search, library and task inbox; for a new version the valid version stays and a new " +
          "version can be started again. Attachment links and the original of the draft are removed; files no " +
          "version references any more are deleted unless the document is under legal hold. Pending workflow " +
          "tasks of the draft are cancelled. The optional reason (at most 4000 characters) is recorded in the " +
          "audit trail; an assigned document number stays reserved."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, req) =>
          inTransaction(p) { ctx =>
            for
              details <- ctx.service.get(DocumentId(id))
              _       <- ZIO.when(details.gone)(ZIO.fail(discardedDocument(id, details)))
              open    <- openVersion(details)
              // Decided as if the version were a draft: a reader of a submitted version learns that it can no
              // longer be discarded (409 from the service), everyone else gets 403.
              _ <-
                if open.status == DocumentStatus.Draft then
                  ctx.check(Action.DiscardDraft, resource(p, details, open))
                else ctx.checkRead(details, open)
              result    <- ctx.service.discardDraft(DocumentId(id), req.reason, p.userId)
              cancelled <- ctx.workflow.discard(result.version.id, p.userId)
            yield DiscardedDraftView(
              id,
              result.version.id,
              open.label,
              result.documentDiscarded,
              result.validVersion.map(_.id),
              result.validVersion.map(_.label),
              result.removedAttachments,
              result.releasedFiles.size,
              result.keptFiles.size,
              cancelled.size
            )
          }
      )

  val withdrawVersion: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(
        documents / documentId / "versions" / path[UUID]("versionId").description("Version id") / "withdraw"
      )
      .in(jsonBody[WithdrawVersion])
      .out(jsonBody[DocumentVersionView])
      .description(
        "Stops an approved version before its valid-from date (DMS-143), or later if its publication failed " +
          "for good (Failed, DMS-145), or a version in approval whose archive rendition failed for good (Failed, " +
          "DMS-163; its workflow case ends as Withdrawn): it goes to Withdrawn, is never published, the valid " +
          "version stays valid and the document can get a new version. Requires Withdraw: the owner role or having approved " +
          "this version. The reason is required (422), at most 4000 characters, and recorded in the audit " +
          "trail. A deputy who approved the version may withdraw it while the deputyship stands, recorded on " +
          "behalf of the approver. Author, approvers and owner are notified, readers are not. 409 with the code " +
          "`version_published` " +
          "if the publication got there first, `version_due` from its valid-from date on unless the publication " +
          "failed for good (also if a retry got there first), `version_withdrawn` " +
          "if it was withdrawn already, `version_not_approved` if it is not approved and its rendition did not " +
          "fail for good."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, versionId, req) =>
          inTransaction(p) { ctx =>
            for
              details <- ctx.service.get(DocumentId(id))
              _       <- ZIO.when(details.gone)(ZIO.fail(discardedDocument(id, details)))
              version <- ZIO
                .fromOption(details.versions.find(_.id == versionId))
                .orElseFail(DocumentError.NotFound(s"document $id has no version $versionId"))
              cases <- ctx.workflow.cases(details.document.id)
              approvers = approversOf(cases, versionId)
              // Decided as if the version were still approved: whoever may withdraw it learns why it did not work
              // (409 from the service, which decides under the lock of the version), everyone else gets 403.
              right     <- ctx.withdrawalRight(details, version, cases)
              actingFor <- right.fold(
                ctx
                  .check(Action.Withdraw, withdrawalResource(p.tenantId, details, version, approvers))
                  .as(None)
              )(ZIO.succeed(_))
              withdrawn <- ctx.service.withdrawApproved(versionId, req.reason, approvers, p.userId, actingFor)
              _         <- ctx.workflow.closeWithdrawn(versionId, p.userId, actingFor)
            yield versionViewIn(
              details.copy(versions = details.versions.map(v => if v.id == versionId then withdrawn else v)),
              ctx.service.settings
            )(withdrawn)
          }
      )

  private val deviationRequests = documents / documentId / "deviation-requests"

  val requestDeviation: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(deviationRequests)
      .in(jsonBody[RequestDeviation])
      .out(statusCode(StatusCode.Created).and(jsonBody[DeviationsView]))
      .description(
        "Records a deviation request for a locked section of the draft's template, which gives the version the " +
          "additional review step even before the text changes: with a reason, the section's " +
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
      updateChangeSummary,
      discardDraft,
      withdrawVersion,
      requestDeviation,
      withdrawDeviation,
      deviations,
      assignNumber
    )

  /** What a request may use inside its transaction. */
  private[app] final class Context(
      val service: DocumentService,
      val workflow: ApprovalWorkflow,
      authz: Authorizer,
      val subject: Subject
  ):
    def check(action: Action, resource: Resource): IO[AccessDenied, Unit] =
      authz.check(subject, action, resource).unit

    /** See [[Authorizer.checkCleared]]. */
    def checkCleared(action: Action, resource: Resource): IO[AccessDenied, Unit] =
      authz.checkCleared(subject, action, resource)

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

    /** See [[Delegation.withdrawalRight]]. */
    def withdrawalRight(
        details: DocumentDetails,
        v: DocumentVersion,
        cases: List[CaseDetails]
    ): IO[WorkflowError, Option[Option[UserId]]] =
      Delegation.withdrawalRight(authz.assignments, workflow, subject, details, v, cases)

    /** The decision for `action`, for filtering a long list at one moment in time. */
    def allows(action: Action): UIO[Resource => Boolean] =
      Clock.instant.map(now => r => Policy.decide(subject, action, r, now).isPermitted)

    /** Whether the caller must acknowledge the valid version `v`: they may read it (`ReadPublished`) and pass
      * the recipient filter its class set at publication (DMS-100, ADR 0048).
      */
    def isRecipient(
        details: DocumentDetails,
        v: DocumentVersion,
        filter: dms.authz.RecipientFilter
    ): UIO[Boolean] =
      Clock.instant.map(now =>
        filter.admits(subject, now) &&
          Policy
            .decide(subject, Action.ReadPublished, DocumentApi.resource(subject.tenantId, details, v), now)
            .isPermitted
      )

    /** Whether the caller passes a recipient filter (DMS-100); readership is decided separately. */
    def passes(filter: dms.authz.RecipientFilter): UIO[Boolean] = Clock.instant.map(filter.admits(subject, _))

    /** The versions the caller may read, possibly none. */
    def visible(details: DocumentDetails): IO[WorkflowError, List[DocumentVersion]] =
      visibleWith(details).map(_._1)

    /** [[visible]] for a whole list, reading the workflow cases of all its documents at once (DMS-60). */
    def visibleAll(
        all: List[DocumentDetails]
    ): IO[WorkflowError, List[(DocumentDetails, List[DocumentVersion])]] =
      workflow
        .casesOf(all.map(_.document.id))
        .flatMap(cases =>
          ZIO.foreach(all)(details =>
            visibleIn(details, cases.getOrElse(details.document.id, Nil)).map(r => details -> r._1)
          )
        )

    /** The versions the caller may read; denied (and logged) if there is none. A document whose versions were
      * all discarded (DMS-129) is not found.
      */
    def readable(
        details: DocumentDetails
    ): IO[AccessDenied | WorkflowError | DocumentError, List[DocumentVersion]] =
      for
        _      <- ZIO.when(details.gone)(ZIO.fail(discardedDocument(details.document.id.value, details)))
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
      workflow.cases(details.document.id).flatMap(visibleIn(details, _))

    private def visibleIn(
        details: DocumentDetails,
        cases: List[CaseDetails]
    ): IO[WorkflowError, (List[DocumentVersion], Map[UUID, Set[UserId]], Subject)] =
      val participants = participantsOf(cases)
      for
        actor    <- Delegation.subject(authz, subject, cases.flatMap(_.tasks))
        asDeputy <- Delegation.approvedAsDeputy(authz.assignments, workflow, subject, details, cases)
        now      <- Clock.instant
      yield (
        details.versions.filter(v =>
          asDeputy(v.id) ||
            Policy
              .decide(actor, Action.ReadDocument, resource(actor, details, v, participants), now)
              .isPermitted
        ),
        participants,
        actor
      )

    /** `ReadDocument` on one version; the participants of its workflow (e.g. the extra review step) and their
      * deputies (DMS-49) may read it, and a deputy who approved it while the deputyship stands (DMS-145).
      */
    def checkRead(details: DocumentDetails, v: DocumentVersion): IO[AccessDenied | WorkflowError, Unit] =
      for
        cases    <- workflow.cases(details.document.id)
        actor    <- Delegation.subject(authz, subject, cases.flatMap(_.tasks))
        asDeputy <- Delegation.approvedAsDeputy(authz.assignments, workflow, subject, details, cases)
        _        <- ZIO.unless(asDeputy(v.id))(
          authz.check(actor, Action.ReadDocument, resource(actor, details, v, participantsOf(cases)))
        )
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

  /** Whether the caller may start the next version of the document (DMS-103): `None` if not (no valid version
    * to start from, or not permitted: the caller would become the author of a draft), `Some(None)` if now,
    * `Some(Some(reason))` if permitted but blocked by the state of the document. Mirrors the checks of
    * `newVersion`, which decides again.
    */
  private def startVersion(
      ctx: Context,
      p: Principal,
      details: DocumentDetails
  ): UIO[Option[Option[String]]] =
    Versioning.startingPoint(details.versions) match
      case None       => ZIO.none
      case Some(base) =>
        ctx
          .allowed(Action.EditDraft, versionStart(p.tenantId, details, base))
          .map(allowed =>
            Option.when(allowed)(
              if details.open.isDefined then Some("OpenVersion")
              else if details.versions.exists(_.status == DocumentStatus.Approved) then
                Some("ScheduledVersion")
              else None
            )
          )

  /** The version a document is shown with: the open one, else the latest that took or can take effect. A
    * version withdrawn before it took effect (DMS-143) is shown only if nothing else is left.
    */
  private def currentOf(readable: List[DocumentVersion]): DocumentVersion =
    readable
      .find(v => Versioning.isOpen(v.status))
      .orElse(readable.filter(_.withdrawal.isEmpty).lastOption)
      .getOrElse(readable.last)

  /** Who approved a version: the voters of its approval steps and whom they stood in for, and the final
    * approver.
    */
  private[app] def approversOf(cases: List[CaseDetails], versionId: UUID): Set[UserId] =
    cases
      .filter(_.workflowCase.versionId == versionId)
      .flatMap { c =>
        val approvalTasks = c.tasks.filter(_.kind == StepKind.Approval).map(_.id).toSet
        c.votes
          .filter(v => approvalTasks(v.taskId) && v.decision.approves && v.invalidatedAt.isEmpty)
          .flatMap(v => v.voter :: v.actingFor.toList) ++
          c.approval.toList.flatMap(a => a.approvedBy :: a.actingFor.toList)
      }
      .toSet

  /** Whether `user` approved a version in person: with a vote of their own, or as final approver without
    * standing in for anyone.
    */
  private[app] def approvedInPerson(cases: List[CaseDetails], versionId: UUID, user: UserId): Boolean =
    cases.filter(_.workflowCase.versionId == versionId).exists { c =>
      val approvalTasks = c.tasks.filter(_.kind == StepKind.Approval).map(_.id).toSet
      c.votes.exists(v =>
        v.voter == user && v.actingFor.isEmpty && approvalTasks(v.taskId) && v.decision.approves &&
          v.invalidatedAt.isEmpty
      ) || c.approval.exists(a => a.approvedBy == user && a.actingFor.isEmpty)
    }

  /** The resource `Withdraw` decides on: the version as approved, with everyone who approved it. */
  private[app] def withdrawalResource(
      tenant: dms.kernel.TenantId,
      details: DocumentDetails,
      v: DocumentVersion,
      approvers: Set[UserId]
  ): Resource =
    resource(tenant, details, v).copy(
      status = Some(DocumentStatus.Approved),
      approvedBy = approvers ++ v.releasedBy,
      withdrawnBeforeValid = false
    )

  /** The version the caller may withdraw now, if any: an approved one, or one in approval whose rendition
    * failed for good (DMS-163, ADR 0078). Mirrors the checks of `withdrawVersion`, which decides again.
    */
  private def withdrawableVersion(
      ctx: Context,
      details: DocumentDetails
  ): IO[WorkflowError, Option[WithdrawableVersionView]] =
    details.versions.find(v =>
      v.status == DocumentStatus.Approved || v.status == DocumentStatus.InApproval
    ) match
      case None    => ZIO.none
      case Some(v) =>
        val inApproval = v.status == DocumentStatus.InApproval
        for
          cases      <- ctx.workflow.cases(details.document.id)
          now        <- Clock.instant
          right      <- ctx.withdrawalRight(details, v, cases)
          failed     <- ctx.service.publicationFailed(v.id).mapError(WorkflowDocuments.toWorkflowError)
          unrendered <- ZIO
            .when(inApproval)(ctx.service.renditionFailed(v.id))
            .mapBoth(WorkflowDocuments.toWorkflowError, _.contains(true))
          possible =
            if inApproval then unrendered else failed || !Publication.isDue(v.validFrom, details.zone, now)
        yield Option.when(right.isDefined && possible)(
          WithdrawableVersionView(
            v.id,
            v.label,
            v.validFrom,
            details.versions
              .filter(o => Versioning.isValid(o.status) && o.scope == v.scope)
              .lastOption
              .map(_.label),
            failed,
            unrendered,
            right.flatten.map(_.value)
          )
        )

  /** Whether the caller's first change of the open draft would cost them their vote (ADR 0078). */
  private def changeCostsVote(ctx: Context, details: DocumentDetails): IO[WorkflowError, Boolean] =
    details.open.filter(_.status == DocumentStatus.Draft) match
      case None        => ZIO.succeed(false)
      case Some(draft) =>
        for
          edit  <- ctx.allowed(Action.EditDraft, resource(ctx.subject.tenantId, details, draft))
          cases <- if edit then ctx.workflow.cases(details.document.id) else ZIO.succeed(Nil)
        yield cases
          .filter(_.workflowCase.versionId == draft.id)
          .exists(Rules.changeCostsVote(_, ctx.subject.userId, draft.authors))

  /** A document whose versions were all discarded (DMS-129) or destroyed (DMS-82) is not found. */
  private[app] def discardedDocument(id: UUID, details: DocumentDetails): DocumentError =
    DocumentError.NotFound(
      s"document $id does not exist (it was ${if details.destroyed then "destroyed" else "discarded"})"
    )

  private def openVersion(details: DocumentDetails): IO[DocumentError, DocumentVersion] =
    ZIO
      .fromOption(details.open)
      .orElseFail(DocumentError.Conflict(s"document ${details.document.id.value} has no open version"))

  /** For every way of saving a draft, after the save, whose transaction a failure undoes: the check before it
    * saw the draft where it stood, and where it goes decides who reads what it copied (ADR 0084, decision
    * 15).
    */
  private[app] def checkUnitReach(
      subject: Subject,
      details: DocumentDetails,
      open: DocumentVersion,
      updated: DocumentVersion
  ): IO[DocumentError, Unit] =
    val field = DocumentResource.OrganizationalUnitField
    ZIO.foreachDiscard(Versioning.reachedUnit(open, updated.metadata, details.versions))(unit =>
      Clock.instant.flatMap(now =>
        ZIO.unless(
          Policy
            .decide(subject, Action.EditDraft, resource(subject.tenantId, details, updated), now)
            .isPermitted
        )(
          ZIO.fail(
            DocumentError.InvalidValues(
              List(
                FieldIssue(
                  field,
                  details.profile.field(field).fold(field)(_.definition.label),
                  IssueKind.UnitOutOfReach(unit)
                )
              )
            )
          )
        )
      )
    )

  /** Values a business system set keep their origin until the system changes them. */
  private def keepExternal(v: DocumentVersion): Map[String, FieldEntry] =
    v.values.filter((_, e) =>
      e.origin match
        case ValueOrigin.System(source) => source != "dms"
        case _                          => false
    )

  private def owner(values: Map[String, MetadataValue]): Option[UserId] =
    values.get("owner").collect { case MetadataValue.PersonRef(u) => UserId(u) }

  /** The resource starting a new version from `base` is decided on: `base` as a draft, because that is what
    * the action creates. Author, owner and groups stay those of `base`: the draft holds its content, so the
    * caller needs clearance for it (DMS-158, ADR 0074). Becoming the draft's author must not count here.
    */
  private[app] def versionStart(
      tenant: dms.kernel.TenantId,
      details: DocumentDetails,
      base: DocumentVersion
  ): Resource =
    resource(tenant, details, base).copy(status = Some(DocumentStatus.Draft))

  /** The resource of a document to be created by the caller from a template of `profile`'s class. */
  private[app] def creationResource(
      p: Principal,
      profile: MetadataProfile,
      values: Map[String, MetadataValue]
  ) =
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

  /** The resource of what was written about lowering a version: requests and decisions stay at the highest
    * level one was made at, also after the lower level took effect (ADR 0083, decision 14). Only the level
    * differs from [[resource]]: the access groups stay in the values of the version.
    */
  private[app] def loweringResource(
      tenant: dms.kernel.TenantId,
      details: DocumentDetails,
      v: DocumentVersion
  ): Resource =
    val own = resource(tenant, details, v)
    // Only the highest level counts: whoever is cleared for it is cleared for the others.
    v.lowerings
      .map(_.from)
      .maxByOption(CoreFields.ConfidentialityLevels.indexOf)
      .fold(own)(level => own.copy(confidentiality = Confidentiality.valueOf(level)))

  /** The resources a right on the document as a whole is decided on: every governing version must permit it
    * (DMS-155, ADR 0072).
    */
  private[app] def governing(tenant: dms.kernel.TenantId, details: DocumentDetails): List[Resource] =
    Versioning.governing(details.versions).map(resource(tenant, details, _))

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

  private[app] def documentView(
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
      current,
      documentNumber = d.number,
      kind = d.kind.toString
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
      d.authorId == caller || v.createdBy == caller,
      d.number,
      d.kind.toString
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
      publishedAt = v.publishedAt,
      file = v.file.map(fileView(v.documentId.value, _)),
      withdrawal =
        v.withdrawal.map(w => WithdrawalView(w.at, w.by.value, w.reason, w.actingFor.map(_.value))),
      editors = v.authors.map(_.value).toList.sorted,
      accessFloor = v.accessFloor
    )

  private[app] def fileView(documentId: UUID, f: VersionFile): VersionFileView =
    val base = s"/api/v1/documents/$documentId/versions/${f.versionId}"
    VersionFileView(
      f.versionId,
      f.name,
      f.contentType,
      f.size,
      f.sha256,
      f.scanStatus.toString,
      f.setBy.value,
      f.setAt,
      f.basedOn,
      s"$base/file",
      s"$base/preview.pdf"
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
    Some(issues.map(IssueView.of))

  def toResponse(error: AccessDenied | DbError | DocumentError): (StatusCode, ApiError) = error match
    case AccessDenied(_, reason) =>
      (StatusCode.Forbidden, ApiError("forbidden", s"You are not allowed to do this: $reason."))
    case DocumentError.NotFound(message) => (StatusCode.NotFound, ApiError("not_found", message))
    case e @ DocumentError.Invalid(_)    => (StatusCode.UnprocessableEntity, ApiError("invalid", e.describe))
    case e @ DocumentError.UnauthorizedDeviations(deviations) =>
      val issues = deviations.map { d =>
        val section = d.violation.section.value
        IssueView(section, d.sectionTitle.getOrElse(section), d.violation.code, d.violation.reason)
      }
      (StatusCode.UnprocessableEntity, ApiError("unauthorized_deviations", e.describe, Some(issues)))
    case e @ DocumentError.InvalidValues(issues) =>
      (StatusCode.UnprocessableEntity, ApiError("invalid_values", e.describe, issueViews(issues)))
    case e @ DocumentError.Incomplete(_, issues) =>
      (StatusCode.UnprocessableEntity, ApiError("incomplete", e.describe, issueViews(issues)))
    case e @ DocumentError.Lifecycle(LifecycleError.SegregationOfDutiesViolated(_)) =>
      (StatusCode.Forbidden, ApiError("segregation_of_duties", e.describe))
    case e @ DocumentError.Lifecycle(_)  => (StatusCode.Conflict, ApiError("lifecycle", e.describe))
    case DocumentError.Conflict(message) => (StatusCode.Conflict, ApiError("conflict", message))
    case DocumentError.WithdrawalRefused(refusal, message) =>
      (StatusCode.Conflict, ApiError(refusal.code, message))
    case DocumentError.Database(e) => databaseError(e)
    case e: DbError                => databaseError(e)

  private def databaseError(error: DbError): (StatusCode, ApiError) = HttpHardening.databaseError(error)
