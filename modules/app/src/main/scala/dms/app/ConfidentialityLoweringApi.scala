package dms.app

import dms.authz.{Action, Confidentiality, Policy}
import dms.documents.core.{DocumentDetails, DocumentError}
import dms.documents.metadata.CoreFields
import dms.documents.{ConfidentialityLowering, DocumentVersion, Versioning}
import dms.kernel.DocumentId
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.time.Instant
import java.util.UUID

final case class RequestLowering(
    /** The level asked for: below the level of the version the draft started from. */
    level: String,
    /** Why the version needs less protection; required. */
    reason: String
) derives JsonCodec,
      Schema

final case class LoweringView(
    id: UUID,
    /** The level the version stood at. */
    from: String,
    to: String,
    reason: String,
    requestedBy: String,
    requestedAt: Instant,
    /** Requested, Approved, Refused or Withdrawn. */
    status: String,
    /** Who approved or refused the request, or withdrew it. */
    decidedBy: Option[String],
    decidedAt: Option[Instant],
    decisionReason: Option[String],
    /** The candidate a deputy decided for. */
    actingFor: Option[String]
) derives JsonCodec,
      Schema

/** What a version says about lowering its confidentiality (ADR 0083). */
final case class LoweringsView(
    versionId: UUID,
    version: String,
    status: String,
    /** The level the version states. */
    level: String,
    /** The level access is decided on: until publication the old level of an approved lowering. */
    accessLevel: String,
    /** For a draft the level of the version it started from: below it the additional approval is needed.
      * Empty for a first version, which chooses freely, and for every other status.
      */
    basisLevel: Option[String],
    /** True if the caller may ask for a lower level now. */
    canRequest: Boolean,
    /** All requests of the version, oldest first. Empty for whoever is not cleared for the level they were
      * made at: a reason written at that level is not for readers of the lower one.
      */
    requests: List[LoweringView]
) derives JsonCodec,
      Schema

/** The additional approval for lowering the confidentiality of a later version (business concept 3.2,
  * DMS-162, ADR 0083).
  *
  *   - `POST …/confidentiality-lowering` and `…/withdraw`: asking and withdrawing on a draft, with
  *     `EditDraft`
  *   - `GET …/versions/{versionId}/confidentiality-lowering`: state and requests, with `ReadDocument`
  *
  * The decision is the vote on the task of the additional approval step (`POST
  * /api/v1/workflow/tasks/{id}/vote`).
  */
object ConfidentialityLoweringApi:
  type Env = DocumentApi.Env

  private val documents = "api" / "v1" / "documents"
  private val document  = documents / path[UUID]("id").description("Document id")
  private val lowering  = document / "confidentiality-lowering"

  val request: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(lowering)
      .in(jsonBody[RequestLowering])
      .out(statusCode(StatusCode.Created).and(jsonBody[LoweringsView]))
      .description(
        "Asks to lower the confidentiality of a later version below the level of the version it started from. " +
          "The draft stays at its level and can be worked on; at submission its workflow gets the additional " +
          "approval step, in which an approver cleared for the level, who neither started nor changed the " +
          "version, decides. The level changes with that approval and takes effect for access with the " +
          "publication of this version. Asking counts as a change of the draft (ADR 0075). 422 `invalid` for a " +
          "first version, a level that needs no approval, or an empty reason; 409 while a request is open or " +
          "the version is not a draft. Requires EditDraft."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, req) =>
          DocumentApi.inTransaction(p) { ctx =>
            for
              details <- ctx.service.get(DocumentId(id))
              open    <- openVersion(details)
              _       <- ctx.check(Action.EditDraft, DocumentApi.resource(p.tenantId, details, open))
              updated <- ctx.service.requestLowering(DocumentId(id), req.level.trim, req.reason, p.userId)
              view    <- view(ctx, withVersion(details, updated), updated)
            yield view
          }
      )

  val withdraw: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(lowering / "withdraw")
      .out(jsonBody[LoweringsView])
      .description(
        "Withdraws the open request while the version is a draft; the version goes on at its level, without " +
          "the additional approval step. 409 without an open request or if the version is not a draft. " +
          "Requires EditDraft."
      )
      .tag("Documents")
      .serverLogic(p =>
        id =>
          DocumentApi.inTransaction(p) { ctx =>
            for
              details <- ctx.service.get(DocumentId(id))
              open    <- openVersion(details)
              _       <- ctx.check(Action.EditDraft, DocumentApi.resource(p.tenantId, details, open))
              updated <- ctx.service.withdrawLowering(DocumentId(id), p.userId)
              view    <- view(ctx, withVersion(details, updated), updated)
            yield view
          }
      )

  val state: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(document / "versions" / path[UUID]("versionId").description("Version id"))
      .in("confidentiality-lowering")
      .out(jsonBody[LoweringsView])
      .description(
        "The level of a version, the level access is decided on and the requests to lower it. Whoever is not " +
          "cleared for the level a request was made at does not get the requests, also after the lower level " +
          "took effect. Requires ReadDocument on the version."
      )
      .tag("Documents")
      .serverLogic(p =>
        (id, versionId) =>
          DocumentApi.inTransaction(p) { ctx =>
            for
              details <- ctx.service.get(DocumentId(id))
              _       <- ZIO.when(details.gone)(ZIO.fail(DocumentApi.discardedDocument(id, details)))
              version <- ZIO
                .fromOption(details.versions.find(_.id == versionId))
                .orElseFail(DocumentError.NotFound(s"document $id has no version $versionId"))
              _    <- ctx.checkRead(details, version)
              view <- view(ctx, details, version)
            yield view
          }
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(request, withdraw, state)

  private def openVersion(details: DocumentDetails): IO[DocumentError, DocumentVersion] =
    ZIO
      .fromOption(details.open)
      .orElseFail(DocumentError.Conflict(s"document ${details.document.id.value} has no open version"))

  private def withVersion(details: DocumentDetails, v: DocumentVersion): DocumentDetails =
    details.copy(versions = details.versions.map(o => if o.id == v.id then v else o))

  private def view(
      ctx: DocumentApi.Context,
      details: DocumentDetails,
      v: DocumentVersion
  ): UIO[LoweringsView] =
    val resource = DocumentApi.resource(ctx.subject.tenantId, details, v)
    val basis    = Option
      .when(Versioning.isEditable(v.status))(Versioning.basis(v, details.versions))
      .flatten
      .map(b => CoreFields.confidentialityLevel(b.accessMetadata))
    ctx.allowed(Action.EditDraft, resource).map { mayEdit =>
      // Only the highest level counts: whoever is cleared for it is cleared for the others.
      val highest = v.lowerings.map(_.from).maxByOption(CoreFields.ConfidentialityLevels.indexOf)
      val cleared = highest.forall(level =>
        Policy.clearedFor(ctx.subject, resource.copy(confidentiality = Confidentiality.valueOf(level)))
      )
      LoweringsView(
        v.id,
        v.label,
        v.status.toString,
        CoreFields.confidentialityLevel(v.metadata),
        CoreFields.confidentialityLevel(v.accessMetadata),
        basis,
        mayEdit && v.requestedLowering.isEmpty && basis.exists(_ != CoreFields.ConfidentialityLevels.head),
        if cleared then v.lowerings.map(lowering) else Nil
      )
    }

  private def lowering(l: ConfidentialityLowering): LoweringView =
    LoweringView(
      l.id,
      l.from,
      l.to,
      l.reason,
      l.requestedBy.value,
      l.requestedAt,
      l.status.toString,
      l.decidedBy.map(_.value),
      l.decidedAt,
      l.decisionReason,
      l.actingFor.map(_.value)
    )
end ConfidentialityLoweringApi
