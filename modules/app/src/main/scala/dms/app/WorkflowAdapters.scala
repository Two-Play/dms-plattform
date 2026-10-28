package dms.app

import dms.authz.{Policy, Resource, Role, RoleAssignments, Subject}
import dms.documents.core.DocumentJson.given
import dms.documents.core.{DocumentDetails, DocumentError, DocumentService}
import dms.documents.metadata.MetadataValue
import dms.documents.{DocumentVersion, LifecycleEvent}
import dms.kernel.{DocumentId, TenantId, UserId}
import dms.templates.core.ContentJson
import dms.workflow.core.*
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Gives the workflow (module `workflow`) access to documents: the version under review with its content
  * checksum, and lifecycle transitions through [[DocumentService]].
  */
final class WorkflowDocuments(service: DocumentService) extends DocumentGateway:

  def subject(documentId: DocumentId, versionId: UUID): IO[WorkflowError, ReviewSubject] =
    service.get(documentId).mapError(WorkflowDocuments.toWorkflowError).flatMap { details =>
      ZIO
        .fromOption(details.versions.find(_.id == versionId))
        .orElseFail(WorkflowError.NotFound(s"document version $versionId does not exist"))
        .map(WorkflowDocuments.subject(details, _))
    }

  def advance(
      documentId: DocumentId,
      versionId: UUID,
      event: LifecycleEvent,
      actor: UserId
  ): IO[WorkflowError, ReviewSubject] =
    service.changeStatus(versionId, event, actor).mapError(WorkflowDocuments.toWorkflowError) *>
      subject(documentId, versionId)

object WorkflowDocuments:
  val layer: URLayer[DocumentService, DocumentGateway] = ZLayer.fromFunction(WorkflowDocuments(_))

  def subject(details: DocumentDetails, v: DocumentVersion): ReviewSubject =
    ReviewSubject(
      details.document.id,
      v.id,
      v.label,
      v.status,
      details.profile.classPath.map(_.value),
      v.metadata.get("organizationalUnit").collect {
        case MetadataValue.Text(t)      => t
        case MetadataValue.Selection(c) => c
      },
      v.createdBy,
      details.document.ownerId,
      contentSha256(v),
      v.changeKind
    )

  /** SHA-256 (lowercase hex) over the canonical JSON of the content and the field values with their origin
    * (DMS-41). Canonical: object keys sorted, no whitespace, so equal content gives an equal checksum.
    */
  def contentSha256(v: DocumentVersion): String =
    val content =
      ContentJson.encodeStored(v.content).fromJson[Json].fold(e => throw IllegalStateException(e), identity)
    val values = v.values.toJsonAST.fold(e => throw IllegalStateException(e), identity)
    val digest = MessageDigest
      .getInstance("SHA-256")
      .digest(canonical(Json.Obj("content" -> content, "values" -> values)).getBytes(StandardCharsets.UTF_8))
    digest.map(b => f"$b%02x").mkString

  private def canonical(json: Json): String = json match
    case Json.Obj(fields) =>
      fields.toList
        .sortBy(_._1)
        .map((k, v) => s"${Json.Str(k).toJson}:${canonical(v)}")
        .mkString("{", ",", "}")
    case Json.Arr(items) => items.map(canonical).mkString("[", ",", "]")
    case other           => other.toJson

  def toWorkflowError(error: DocumentError): WorkflowError =
    val (_, body) = DocumentApi.toResponse(error)
    error match
      case DocumentError.NotFound(m) => WorkflowError.NotFound(m)
      case _                         => WorkflowError.Document(body.code, body.message)

/** Participants from role assignments (business concept 2.3, 6.1): holders of a role whose assignment is
  * active and whose scope (organizational unit, document class subtree) covers the document.
  */
final class RoleDirectory(assignments: RoleAssignments) extends ParticipantDirectory:

  def holders(role: String, subject: ReviewSubject): IO[WorkflowError, List[UserId]] =
    for
      r <- ZIO
        .attempt(Role.valueOf(role))
        .orElseFail(WorkflowError.Invalid(List(s"unknown role $role")))
      all <- assignments.holders(r).mapError(WorkflowError.Database(_))
      now <- Clock.instant
      // Row-level security already limits the rows to the tenant; the tenant id only satisfies the types.
      resource = Resource(
        RoleDirectory.AnyTenant,
        organizationalUnit = subject.organizationalUnit,
        documentClassPath = subject.classPath
      )
    yield all
      .filter((user, a) =>
        Policy.rolesInScope(Subject(user, RoleDirectory.AnyTenant, Set(a)), resource, now)(r)
      )
      .map(_._1)
      .distinct
      .sortBy(_.value)

  def tenantAdmins: IO[WorkflowError, List[UserId]] =
    for
      all <- assignments.holders(Role.TenantAdmin).mapError(WorkflowError.Database(_))
      now <- Clock.instant
    yield all.collect { case (user, a) if a.isActiveAt(now) => user }.distinct.sortBy(_.value)

object RoleDirectory:
  private val AnyTenant = TenantId(UUID(0L, 0L))

  val layer: URLayer[RoleAssignments, ParticipantDirectory] = ZLayer.fromFunction(RoleDirectory(_))

/** Whether a version needs the extra review step of its class (deviations from locked standard text, business
  * concept 4.1). Reads the deviation report of the documents module (DMS-79).
  */
final class WorkflowExtraReview(service: DocumentService) extends ExtraReviewCheck:
  def required(documentId: DocumentId, versionId: UUID): IO[WorkflowError, Boolean] =
    service
      .get(documentId)
      .mapBoth(
        WorkflowDocuments.toWorkflowError,
        _.versions.find(_.id == versionId).exists(_.requiresExtraReview)
      )

object WorkflowExtraReview:
  val layer: URLayer[DocumentService, ExtraReviewCheck] = ZLayer.fromFunction(WorkflowExtraReview(_))
