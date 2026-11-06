package dms.app

import dms.audit.*
import dms.authz.*
import dms.db.{Db, DbError, Jsonb}
import dms.documents.metadata.CoreFields
import dms.documents.core.{DocumentDetails, DocumentError, DocumentService}
import dms.documents.{DocumentRecord, DocumentVersion, Versioning}
import dms.identity.{Authenticator, Principal}
import dms.kernel.DocumentId
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.Instant
import java.util.UUID

/** A document whose owner or title differ from the version that last took effect (ADR 0084). */
final case class RecordDifferenceView(
    documentId: UUID,
    /** The version the document differs from: the last one in force. */
    versionId: UUID,
    versionLabel: String,
    /** The status of that version now. */
    versionStatus: String,
    classPath: List[String],
    unit: Option[String],
    /** Owner and title the document has. */
    documentOwner: Option[String],
    documentTitle: String,
    /** The owner the version names; absent if it is the document's. */
    versionOwner: Option[String],
    /** The title the version names; absent if it is the document's. */
    versionTitle: Option[String],
    /** The version in the workflow a correction waits for, if any. */
    inWorkflow: Option[String]
) derives JsonCodec,
      Schema

/** @param notCleared
  *   documents with a difference in the caller's scope they are not cleared for: left out, and someone
  *   cleared has to look at them. A number only.
  */
final case class RecordDifferencesView(differences: List[RecordDifferenceView], notCleared: Int)
    derives JsonCodec,
      Schema

/** An open version below the level of the version it started from (ADR 0084). */
final case class DraftBelowBasisView(
    documentId: UUID,
    versionId: UUID,
    versionLabel: String,
    /** Draft, InReview or InApproval. */
    versionStatus: String,
    title: String,
    classPath: List[String],
    unit: Option[String],
    author: String,
    owner: Option[String],
    /** The level access goes by now. */
    level: String,
    basisLabel: String,
    basisLevel: String,
    /** When the version was last changed. */
    changedAt: Instant
) derives JsonCodec,
      Schema

/** @param notCleared
  *   as in [[RecordDifferencesView]]
  */
final case class DraftsBelowBasisView(drafts: List[DraftBelowBasisView], notCleared: Int)
    derives JsonCodec,
      Schema

final case class CorrectRecord(
    /** The version the difference was seen on; 409 if that is no longer the last one in force. */
    versionId: UUID,
    /** Take the owner the version names. */
    owner: Boolean,
    /** Take the title the version names. */
    title: Boolean,
    /** Why; recorded in the audit trail. */
    reason: String
) derives JsonCodec,
      Schema

final case class RecordCorrectedView(documentId: UUID, owner: Option[String], title: String)
    derives JsonCodec,
      Schema

/** What records management keeps straight about documents (DMS-162, ADR 0084): owner and title that a draft
  * saved before ADR 0074 left on the document, with a correction per case, and open versions below their
  * basis.
  *
  * Records managers in scope, decided per document with `CorrectDocumentRecord` on the governing versions and
  * every version a row shows (ADR 0072): a row states the values of both. Documents someone is not cleared
  * for stay out and are counted, so that the list does not pass for complete.
  *
  * Both reports name people, so reading them is audited, as for the four-eyes report (ADR 0078).
  */
object RecordsApi:
  type Env = Authenticator & Db & Authorizer & DocumentService & AuditTrail

  /** The audit actions recorded whenever a report was read. */
  val DifferencesViewed = "records.document-differences-viewed"
  val DraftsViewed      = "records.drafts-below-basis-viewed"

  val differences: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "records" / "document-differences")
      .out(jsonBody[RecordDifferencesView])
      .description(
        "Documents whose owner or title differ from the version last in force (ADR 0084): what a draft saved " +
          "before ADR 0074 left behind. They stay as they are unless someone corrects them. For records managers, " +
          "decided per document on its governing versions and that version, so only documents within their scope " +
          "and clearance; `notCleared` counts those in scope left out. Reading the report is recorded in the " +
          "audit trail."
      )
      .tag("Records")
      .serverLogic(p =>
        _ => run(p)(subject => visibleDifferences(p, subject).tap(read(p, DifferencesViewed, _)))
      )

  val drafts: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "records" / "drafts-below-basis")
      .out(jsonBody[DraftsBelowBasisView])
      .description(
        "Open versions whose level is below that of the version they started from (ADR 0084), by the level " +
          "access goes by. Rights as for `document-differences`. Reading the report is recorded in the audit trail."
      )
      .tag("Records")
      .serverLogic(p => _ => run(p)(subject => visibleDrafts(p, subject).tap(read(p, DraftsViewed, _))))

  val correct: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in("api" / "v1" / "documents" / path[UUID]("id").description("Document id") / "record-correction")
      .in(jsonBody[CorrectRecord])
      .out(jsonBody[RecordCorrectedView])
      .description(
        "Gives the document the owner, the title or both that its last version in force names (ADR 0084), with " +
          "a reason, recorded in the audit trail as `document.record-corrected` with before and after. No other " +
          "value can be set. Tells both owners. 403 as for the report. 409 while a version is in the workflow, or " +
          "if `versionId` is no longer the last one in force or the chosen value no longer differs. 422 without " +
          "reason or value."
      )
      .tag("Records")
      .serverLogic(p =>
        (id, req) =>
          run(p) { subject =>
            for
              service <- ZIO.service[DocumentService]
              authz   <- ZIO.service[Authorizer]
              details <- service.get(DocumentId(id))
              shown = details.versions.filter(_.id == req.versionId)
              _ <- ZIO.foreachDiscard(reach(p, details, shown))(
                authz.check(subject, Action.CorrectDocumentRecord, _)
              )
              corrected <- service.correctRecord(
                DocumentId(id),
                req.versionId,
                req.owner,
                req.title,
                req.reason,
                p.userId
              )
            yield RecordCorrectedView(id, corrected.ownerId.map(_.value), corrected.title)
          }
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(differences, drafts, correct)

  private type Failure = DocumentError | AccessDenied | DbError

  private def run[A](p: Principal)(f: Subject => ZIO[Env, Failure, A]): ZIO[Env, (StatusCode, ApiError), A] =
    (for
      db     <- ZIO.service[Db]
      authz  <- ZIO.service[Authorizer]
      result <- db.transact(p.tenantId)(authz.subject(p).flatMap(f))
    yield result).mapError {
      case e: AccessDenied  => DocumentApi.toResponse(e)
      case e: DbError       => DocumentApi.toResponse(e)
      case e: DocumentError => DocumentApi.toResponse(e)
    }

  /** The resources a row of `details` is decided on: what speaks for the document, and what the row shows. */
  private def reach(p: Principal, details: DocumentDetails, shown: List[DocumentVersion]): List[Resource] =
    (Versioning.governing(details.versions) ++ shown)
      .distinctBy(_.id)
      .map(DocumentApi.resource(p.tenantId, details, _))

  /** What `subject` may see of `rows`, and how many in their scope stay hidden for lack of clearance. */
  private def visible[A](
      subject: Subject,
      rows: List[A],
      resources: A => List[Resource]
  ): ZIO[Any, AccessDenied, (List[A], Int)] =
    for
      now <- Clock.instant
      _   <- ZIO.unless(subject.assignments.exists(a => a.role == Role.RecordsManager && a.isActiveAt(now)))(
        ZIO.fail(
          AccessDenied(Action.CorrectDocumentRecord, "these reports require the records manager role")
        )
      )
      inScope = rows.filter(
        resources(_).forall(r => Policy.rolesInScope(subject, r, now).contains(Role.RecordsManager))
      )
      (permitted, hidden) = inScope.partition(
        resources(_).forall(r => Policy.decide(subject, Action.CorrectDocumentRecord, r, now).isPermitted)
      )
    yield (permitted, hidden.size)

  private[app] def visibleDifferences(
      p: Principal,
      subject: Subject
  ): ZIO[Env, Failure, RecordDifferencesView] =
    for
      rows <- ZIO.serviceWithZIO[DocumentService](_.recordDifferences)
      seen <- visible(subject, rows, (d, f) => reach(p, d, List(f.version)))
      (permitted, notCleared) = seen
    yield RecordDifferencesView(
      permitted
        .map { (details, f) =>
          RecordDifferenceView(
            details.document.id.value,
            f.version.id,
            f.version.label,
            f.version.status.toString,
            details.profile.classPath.map(_.value),
            CoreFields.organizationalUnit(f.version.metadata),
            details.document.ownerId.map(_.value),
            details.document.title,
            f.owner.map(_.value),
            f.title,
            DocumentRecord.inWorkflow(details.versions).map(_.label)
          )
        }
        .sortBy(v => (v.documentTitle, v.documentId)),
      notCleared
    )

  private[app] def visibleDrafts(p: Principal, subject: Subject): ZIO[Env, Failure, DraftsBelowBasisView] =
    for
      rows <- ZIO.serviceWithZIO[DocumentService](_.draftsBelowBasis)
      seen <- visible(subject, rows, (d, f) => reach(p, d, List(f.version, f.basis)))
      (permitted, notCleared) = seen
    yield DraftsBelowBasisView(
      permitted
        .map { (details, f) =>
          val v = f.version
          DraftBelowBasisView(
            details.document.id.value,
            v.id,
            v.label,
            v.status.toString,
            v.namedTitle.getOrElse(details.document.title),
            details.profile.classPath.map(_.value),
            CoreFields.organizationalUnit(v.metadata),
            v.createdBy.value,
            details.document.ownerId.map(_.value),
            CoreFields.confidentialityLevel(v.accessMetadata),
            f.basis.label,
            CoreFields.confidentialityLevel(f.basis.accessMetadata),
            v.updatedAt
          )
        }
        .sortBy(_.changedAt),
      notCleared
    )

  private def read(p: Principal, action: String, report: RecordDifferencesView | DraftsBelowBasisView) =
    val (rows, notCleared) = report match
      case r: RecordDifferencesView => (r.differences.size, r.notCleared)
      case r: DraftsBelowBasisView  => (r.drafts.size, r.notCleared)
    ZIO
      .serviceWithZIO[AuditTrail](
        _.record(
          AuditEvent(
            Actor(p.userId.value, ActorKind.Person),
            action,
            AuditObject("records-report", None, None),
            Channel.Api,
            Jsonb(Json.Obj("rows" -> Json.Num(rows), "notCleared" -> Json.Num(notCleared)).toJson)
          )
        )
      )
      .unit
