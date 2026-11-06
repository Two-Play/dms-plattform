package dms.app

import com.augustnagro.magnum.sql
import dms.audit.*
import dms.authz.*
import dms.db.{Db, DbError, Jsonb}
import dms.documents.core.{DocumentError, DocumentService}
import dms.identity.{Authenticator, Principal}
import dms.kernel.DocumentId
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.{Instant, OffsetDateTime}
import java.util.UUID

/** An approval or an approving vote by someone who changed the version: valid when it was given, and a breach
  * of the four-eyes rule as it stands since ADR 0075. Votes that were invalidated are not findings.
  */
final case class FourEyesFindingView(
    documentId: UUID,
    versionId: UUID,
    title: String,
    versionLabel: String,
    /** The status of the version now. */
    versionStatus: String,
    classPath: List[String],
    unit: Option[String],
    owner: Option[String],
    /** `Approval`: the version was approved. `ApprovalVote`, `ReviewVote`: an approving vote that stands. */
    kind: String,
    /** Who approved or voted. */
    decidedBy: String,
    /** The person `decidedBy` stood in for. */
    actingFor: Option[String],
    /** Who of the two changed the version; both for a pair of findings. */
    editor: String,
    /** The step of a vote. */
    step: Option[String],
    /** The round of a vote. */
    round: Option[Int],
    decidedAt: Instant,
    /** When `editor` first changed the version, as far as the records tell (ADR 0075, decision 10). */
    firstChangedAt: Instant,
    /** Whether the review of the version still runs: a vote then counts towards its approval. */
    caseOpen: Boolean
) derives JsonCodec,
      Schema

/** The retroactive check of the four-eyes rule (business concept 2.4, DMS-163, ADR 0078): "Verletzungen
  * werden nicht nur verhindert, sondern bei Regeländerungen rückwirkend geprüft und berichtet." ADR 0075
  * widened the rule from the author to everyone who changed a version. Approvals given before stay valid and
  * votes in a running round stand until the version is returned; this report shows them to compliance
  * officers instead.
  *
  * The check reads recorded facts on request, like the rest of the cockpit (ADR 0036): nothing marks a
  * version, and the list stops growing by itself because the workflow and the database refuse new cases. A
  * review vote only counts if the rule applied to it: review steps follow the four-eyes setting of the class,
  * approval steps do not (ADR 0075, decision 6).
  *
  * The report names people, so reading it is audited; it is a control the concept asks for and shows
  * decisions on documents, not the use of the system (business concept 10.2).
  */
object FourEyesReportApi:
  type Env = Authenticator & Db & Authorizer & DocumentService & AuditTrail

  /** The audit action recorded whenever the findings were read. */
  val FindingsViewed = "compliance.four-eyes-findings-viewed"

  val Approval     = "Approval"
  val ApprovalVote = "ApprovalVote"
  val ReviewVote   = "ReviewVote"

  val findings: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "compliance" / "four-eyes-findings")
      .out(jsonBody[List[FourEyesFindingView]])
      .description(
        "Retroactive check of the four-eyes rule (business concept 2.4, ADR 0078): approvals and approving votes " +
          "that stand although the person changed the version, the newest first. Review votes count if the " +
          "four-eyes setting of the class applied to them. They stay valid; the report shows them for a decision. " +
          "For compliance officers, decided per version with MonitorCompliance, so only versions within their " +
          "scope and clearance. Reading the report is recorded in the audit trail."
      )
      .tag("Compliance")
      .serverLogic(p => _ => report(p))

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(findings)

  /** A finding as the database states it. */
  private[app] final case class Row(
      documentId: UUID,
      versionId: UUID,
      kind: String,
      decidedBy: String,
      actingFor: Option[String],
      editor: String,
      step: Option[String],
      round: Option[Int],
      decidedAt: Instant,
      firstChangedAt: Instant,
      caseOpen: Boolean
  )

  /** The last approving vote and the approval it led to are one decision: the approval stands for both. */
  private[app] def withoutDecidedVotes(rows: List[Row]): List[Row] =
    val approved = rows.filter(_.kind == Approval).map(r => (r.versionId, r.decidedBy, r.editor)).toSet
    rows.filterNot(r => r.kind == ApprovalVote && approved((r.versionId, r.decidedBy, r.editor)))

  private[app] type Failure = DocumentError | AccessDenied | DbError

  private def report(p: Principal): ZIO[Env, (StatusCode, ApiError), List[FourEyesFindingView]] =
    (for
      db     <- ZIO.service[Db]
      authz  <- ZIO.service[Authorizer]
      result <- db.transact(p.tenantId)(
        authz.subject(p).flatMap(subject => visibleFindings(p, subject).tap(recordReading(p, _)))
      )
    yield result).mapError {
      case e: AccessDenied  => DocumentApi.toResponse(e)
      case e: DbError       => DocumentApi.toResponse(e)
      case e: DocumentError => DocumentApi.toResponse(e)
    }

  /** The findings `subject` may see. Whoever calls this records it: reading and exporting are told apart. */
  private[app] def visibleFindings(
      p: Principal,
      subject: Subject
  ): ZIO[Env, Failure, List[FourEyesFindingView]] =
    for
      now <- Clock.instant
      _   <- ZIO.unless(
        subject.assignments.exists(a => a.role == Role.ComplianceOfficer && a.isActiveAt(now))
      )(
        ZIO.fail(
          AccessDenied(
            Action.MonitorCompliance,
            "the compliance cockpit requires the compliance officer role"
          )
        )
      )
      rows      <- facts.map(withoutDecidedVotes)
      documents <- ZIO.serviceWithZIO[DocumentService](
        _.getAll(rows.map(r => DocumentId(r.documentId)).distinct)
      )
    yield rows.flatMap { r =>
      for
        details <- documents.get(DocumentId(r.documentId))
        version <- details.versions.find(_.id == r.versionId)
        resource = DocumentApi.resource(p.tenantId, details, version)
        if Policy.decide(subject, Action.MonitorCompliance, resource, now).isPermitted
      yield FourEyesFindingView(
        r.documentId,
        r.versionId,
        details.document.title,
        version.label,
        version.status.toString,
        details.profile.classPath.map(_.value),
        DocumentResource.organizationalUnit(version.metadata),
        details.document.ownerId.map(_.value),
        r.kind,
        r.decidedBy,
        r.actingFor,
        r.editor,
        r.step,
        r.round,
        r.decidedAt,
        r.firstChangedAt,
        r.caseOpen
      )
    }

  private def recordReading(p: Principal, findings: List[FourEyesFindingView]): ZIO[Env, DbError, Unit] =
    ZIO
      .serviceWithZIO[AuditTrail](
        _.record(
          AuditEvent(
            Actor(p.userId.value, ActorKind.Person),
            FindingsViewed,
            AuditObject("compliance-report", None, Some("four-eyes")),
            Channel.Api,
            Jsonb(Json.Obj("findings" -> Json.Num(findings.size)).toJson)
          )
        )
      )
      .unit

  /** Everything the rule as it stands now would have refused: the approvals of a version, and the approving
    * votes that stand, by someone on its list of editors. In both, a deputy's principal counts like the
    * deputy.
    */
  private def facts: IO[DbError, List[Row]] =
    Db.sql(
      sql"""select v.document_id, v.id, 'Approval', v.released_by, a.acting_for, e.user_id, null::text, null::int,
                   coalesce(a.approved_at, v.released_at), e.first_changed_at, false
            from dms.document_version v
            left join dms.workflow_approval a on a.version_id = v.id
            join dms.document_version_editor e
              on e.version_id = v.id and e.user_id in (v.released_by, a.acting_for)
            where v.released_by is not null
            union all
            select v.document_id, v.id,
                   case t.step_kind when 'Approval' then 'ApprovalVote' else 'ReviewVote' end,
                   w.voter, w.acting_for, e.user_id, w.step_key, w.round, w.voted_at, e.first_changed_at,
                   c.status in ('Running', 'Returned', 'Rendering')
            from dms.workflow_vote w
            join dms.workflow_task t on t.id = w.task_id
            join dms.workflow_case c on c.id = w.case_id
            join dms.document_version v on v.id = w.version_id
            join dms.document_version_editor e
              on e.version_id = v.id and e.user_id in (w.voter, w.acting_for)
            where w.decision in ('Approve', 'ApproveWithConditions') and w.invalidated_at is null
              and (t.step_kind = 'Approval' or c.four_eyes)
            order by 9 desc, 2, 3, 6"""
        .query[
          (
              UUID,
              UUID,
              String,
              String,
              Option[String],
              String,
              Option[String],
              Option[Int],
              OffsetDateTime,
              OffsetDateTime,
              Boolean
          )
        ]
        .run()
        .toList
        .map { case (document, version, kind, by, actingFor, editor, step, round, at, changed, open) =>
          Row(
            document,
            version,
            kind,
            by,
            actingFor,
            editor,
            step,
            round,
            at.toInstant,
            changed.toInstant,
            open
          )
        }
    )
