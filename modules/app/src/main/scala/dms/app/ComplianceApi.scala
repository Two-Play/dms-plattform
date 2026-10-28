package dms.app

import dms.app.ComplianceCockpit.{Entry, Rate}
import dms.audit.*
import dms.authz.*
import dms.db.{Db, DbError, Jsonb}
import dms.documents.*
import dms.documents.core.*
import dms.identity.{Authenticator, Principal}
import dms.kernel.DocumentId
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.{Instant, LocalDate}
import java.util.UUID

/** Number of overdue reviews of an organizational unit (`unit` empty: versions without unit). */
final case class UnitCountView(unit: Option[String], count: Int) derives JsonCodec, Schema

/** Number of overdue reviews of a document owner (`owner` empty: documents without owner). */
final case class OwnerCountView(owner: Option[String], count: Int) derives JsonCodec, Schema

/** Acknowledgement figures of a set of versions: their summed audience (readers who must acknowledge), how
  * many of them acknowledged, how many did not, and the rate (0 to 1; empty without audience).
  */
final case class RateView(versions: Int, audience: Int, acknowledged: Int, open: Int, rate: Option[Double])
    derives JsonCodec,
      Schema

/** Acknowledgement figures of an organizational unit (`unit` empty: versions without unit). */
final case class UnitRateView(unit: Option[String], rate: RateView) derives JsonCodec, Schema

/** The key figures of the compliance cockpit (US-15.1) over the valid versions the caller may monitor. */
final case class ComplianceOverviewView(
    /** The tenant's date the figures refer to. */
    today: LocalDate,
    overdueReviews: Int,
    overdueByUnit: List[UnitCountView],
    overdueByOwner: List[OwnerCountView],
    acknowledgements: RateView,
    acknowledgementsByUnit: List[UnitRateView]
) derives JsonCodec,
      Schema

/** A valid version whose review date has been reached without recertification. */
final case class OverdueReviewView(
    documentId: UUID,
    versionId: UUID,
    title: String,
    versionLabel: String,
    classPath: List[String],
    unit: Option[String],
    owner: Option[String],
    /** Published, or ReviewDue once the review check ran. */
    status: String,
    dueOn: LocalDate,
    daysOverdue: Long
) derives JsonCodec,
      Schema

/** Acknowledgement figures of one valid version that requires acknowledgement; no names. */
final case class VersionRateView(
    documentId: UUID,
    versionId: UUID,
    title: String,
    versionLabel: String,
    classPath: List[String],
    unit: Option[String],
    owner: Option[String],
    publishedAt: Option[Instant],
    audience: Int,
    acknowledged: Int,
    open: Int,
    rate: Option[Double]
) derives JsonCodec,
      Schema

/** Who has not acknowledged a version yet (purpose-limited, ADR 0036). */
final case class OpenAcknowledgementsView(
    documentId: UUID,
    versionId: UUID,
    title: String,
    versionLabel: String,
    /** The purpose the caller stated; recorded in the audit trail with the reason. */
    purpose: String,
    audience: Int,
    acknowledged: Int,
    /** User ids of the readers who have not acknowledged the version, sorted. */
    open: List[String]
) derives JsonCodec,
      Schema

/** Compliance cockpit (US-15.1, DMS-59, ADR 0036): overdue reviews per organizational unit and owner and
  * acknowledgement rates per version and unit, for compliance officers, over the valid versions they may
  * monitor (`MonitorCompliance`: scope of their assignment and confidentiality). Every figure is computed on
  * request; the acknowledgement audience follows ADR 0027 (everyone who may read the version) and is decided
  * for the whole tenant at once with [[Audience]].
  *
  * Names of readers who have not acknowledged a version are purpose-limited (business concept 10.2): only
  * with `ViewAcknowledgementPersons` (compliance officers, the document's owner) and a stated purpose and
  * reason, recorded in the audit trail together with the access.
  */
object ComplianceApi:
  type Env = Authenticator & Db & Authorizer & RoleAssignments & Groups & DocumentService &
    AcknowledgementService & AuditTrail

  /** Why names of readers are looked at (purpose limitation): escalating open acknowledgements to the
    * responsible management, or reminding the readers.
    */
  enum Purpose:
    case Escalation, Reminder

  object Purpose:
    def parse(value: String): Option[Purpose] = values.find(_.toString.equalsIgnoreCase(value.trim))

  val MaxReasonLength = 500

  /** The audit action recorded whenever names of readers were shown. */
  val PersonsViewed = "compliance.acknowledgement-persons-viewed"

  private val base = "api" / "v1" / "compliance"

  private val unitQuery = query[Option[String]]("unit").description(
    "Organizational unit; empty for versions without unit; absent for all"
  )

  val overview: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base / "overview")
      .out(jsonBody[ComplianceOverviewView])
      .description(
        "Key figures for compliance officers: the number of overdue reviews (review date reached, not " +
          "recertified) per organizational unit and owner, and the acknowledgement rate of the valid versions " +
          "that require acknowledgement, in total and per unit. Only versions within the caller's scope."
      )
      .tag("Compliance")
      .serverLogic(p =>
        _ =>
          cockpit(p, withRates = true) { (today, entries) =>
            val overdue          = entries.filter(e => ComplianceCockpit.overdue(e.version, today))
            val (versions, rate) = ComplianceCockpit.total(entries)
            ComplianceOverviewView(
              today,
              overdue.size,
              ComplianceCockpit.countBy(overdue)(_.unit).map(UnitCountView.apply),
              ComplianceCockpit.countBy(overdue)(_.owner).map(OwnerCountView.apply),
              rateView(versions, rate),
              ComplianceCockpit.ratesByUnit(entries).map((unit, n, r) => UnitRateView(unit, rateView(n, r)))
            )
          }
      )

  val overdueReviews: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base / "overdue-reviews")
      .in(unitQuery)
      .in(query[Option[String]]("owner").description("Owner's user id; empty for documents without owner"))
      .out(jsonBody[List[OverdueReviewView]])
      .description("Valid versions whose review is overdue, the longest overdue first")
      .tag("Compliance")
      .serverLogic(p =>
        (unit, owner) =>
          cockpit(p, withRates = false)((today, entries) =>
            ComplianceCockpit
              .overdueList(entries, today, unit, owner)
              .flatMap(e =>
                e.version.reviewDueOn.map(due =>
                  OverdueReviewView(
                    e.version.document.id.value,
                    e.version.versionId,
                    e.version.document.title,
                    e.version.label,
                    e.version.classPath,
                    e.unit,
                    e.owner,
                    e.version.status.toString,
                    due,
                    ComplianceCockpit.daysOverdue(e.version, today)
                  )
                )
              )
          )
      )

  val acknowledgements: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base / "acknowledgements")
      .in(unitQuery)
      .in(query[Option[UUID]]("document").description("Document id"))
      .out(jsonBody[List[VersionRateView]])
      .description(
        "Acknowledgement rate of each valid version that requires acknowledgement, the most open first; " +
          "superseded versions drop out. Figures only, no names."
      )
      .tag("Compliance")
      .serverLogic(p =>
        (unit, document) =>
          cockpit(p, withRates = true)((_, entries) =>
            ComplianceCockpit
              .acknowledgementList(entries, unit, document)
              .map((e, r) =>
                VersionRateView(
                  e.version.document.id.value,
                  e.version.versionId,
                  e.version.document.title,
                  e.version.label,
                  e.version.classPath,
                  e.unit,
                  e.owner,
                  e.version.publishedAt,
                  r.audience,
                  r.acknowledged,
                  r.open,
                  r.share
                )
              )
          )
      )

  val openAcknowledgements: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(
        base / "acknowledgements" / path[UUID]("documentId").description("Document id") /
          path[UUID]("versionId").description("Version id") / "open"
      )
      .in(query[Option[String]]("purpose").description("Required: escalation or reminder"))
      .in(
        query[Option[String]]("reason").description(
          s"Required: why the names are needed, up to $MaxReasonLength characters; recorded in the audit trail"
        )
      )
      .out(jsonBody[OpenAcknowledgementsView])
      .description(
        "Who has not acknowledged the version yet (purpose limitation, ADR 0036): for compliance officers in " +
          "scope and the document's owner, with a stated purpose and reason (403 purpose_required otherwise). " +
          "Every access is recorded in the audit trail with purpose and reason. 422 if the version does not " +
          "require acknowledgement."
      )
      .tag("Compliance")
      .serverLogic(p =>
        (documentId, versionId, purpose, reason) => persons(p, documentId, versionId, purpose, reason)
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] =
    List(overview, overdueReviews, acknowledgements, openAcknowledgements)

  private final case class PurposeRequired(message: String)

  private type Failure = DocumentError | AccessDenied | DbError | AcknowledgementError | PurposeRequired

  private def rateView(versions: Int, r: Rate) =
    RateView(versions, r.audience, r.acknowledged, r.open, r.share)

  private def resource(tenant: dms.kernel.TenantId, v: CurrentVersion): Resource =
    DocumentResource.of(
      tenant,
      v.document.ownerId,
      v.classPath,
      v.status,
      v.createdBy,
      v.releasedBy,
      v.metadata
    )

  /** The readers of the tenant: active role assignments and group memberships of every user. */
  private def audience(now: Instant, tenant: dms.kernel.TenantId) =
    for
      assignments <- ZIO.serviceWithZIO[RoleAssignments](_.activeAt(now))
      groups      <- ZIO.serviceWithZIO[Groups](_.currentMemberships)
    yield Audience(Population(tenant, assignments, groups), now)

  /** Runs `f` over the valid versions the caller may monitor, with acknowledgement rates if `withRates`;
    * denied unless the caller holds an active compliance officer assignment.
    */
  private def cockpit[A](p: Principal, withRates: Boolean)(
      f: (LocalDate, List[Entry]) => A
  ): ZIO[Env, (StatusCode, ApiError), A] =
    inTransaction(p) { subject =>
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
        documents <- ZIO.service[DocumentService]
        zone      <- documents.tenantZone
        valid     <- documents.currentlyValid
        visible = valid
          .map(v => v -> resource(p.tenantId, v))
          .filter((_, r) => Policy.decide(subject, Action.MonitorCompliance, r, now).isPermitted)
        entries <-
          if !withRates then ZIO.succeed(visible.map((v, _) => Entry(v, None)))
          else
            for
              readers      <- audience(now, p.tenantId)
              acknowledged <- ZIO.serviceWithZIO[AcknowledgementService](_.acknowledgersOfValid)
            yield visible.map { (v, r) =>
              Entry(
                v,
                Option.when(v.requiresAcknowledgement) {
                  val audience = readers.readers(r)
                  Rate(audience.size, acknowledged.getOrElse(v.versionId, Set.empty).count(audience.contains))
                }
              )
            }
      yield f(LocalDate.ofInstant(now, zone), entries)
    }

  private def persons(
      p: Principal,
      documentId: UUID,
      versionId: UUID,
      purpose: Option[String],
      reason: Option[String]
  ): ZIO[Env, (StatusCode, ApiError), OpenAcknowledgementsView] =
    inTransaction(p) { subject =>
      for
        stated <- ZIO
          .fromOption(purpose.flatMap(Purpose.parse))
          .orElseFail(
            PurposeRequired(
              "State the purpose (escalation or reminder) to see who has not acknowledged a version."
            )
          )
        why <- ZIO
          .fromOption(reason.map(_.trim).filter(r => r.nonEmpty && r.length <= MaxReasonLength))
          .orElseFail(
            PurposeRequired(
              s"State the reason (1 to $MaxReasonLength characters); it is recorded in the audit trail."
            )
          )
        documents <- ZIO.service[DocumentService]
        details   <- documents.get(DocumentId(documentId))
        version   <- ZIO
          .fromOption(details.versions.find(_.id == versionId))
          .orElseFail(DocumentError.NotFound(s"document $documentId has no version $versionId"))
        r = DocumentApi.resource(p.tenantId, details, version)
        _ <- ZIO.serviceWithZIO[Authorizer](_.check(subject, Action.ViewAcknowledgementPersons, r))
        _ <- ZIO.unless(Acknowledgements.requested(version))(
          ZIO.fail(
            AcknowledgementError.NotRequested(s"version ${version.label} does not ask for acknowledgement")
          )
        )
        now          <- Clock.instant
        readers      <- audience(now, p.tenantId).map(_.readers(r))
        acknowledged <- ZIO.serviceWithZIO[AcknowledgementService](_.acknowledgersOf(versionId))
        members = readers.members
        open    = members.filterNot(acknowledged.contains)
        _ <- ZIO.serviceWithZIO[AuditTrail](
          _.record(
            AuditEvent(
              Actor(p.userId.value, ActorKind.Person),
              PersonsViewed,
              AuditObject("document", Some(documentId), Some(version.label)),
              Channel.Api,
              Jsonb(
                Json
                  .Obj(
                    "versionId" -> Json.Str(versionId.toString),
                    "purpose"   -> Json.Str(stated.toString),
                    "open"      -> Json.Num(open.size)
                  )
                  .toJson
              ),
              Some(why)
            )
          )
        )
      yield OpenAcknowledgementsView(
        documentId,
        versionId,
        details.document.title,
        version.label,
        stated.toString,
        members.size,
        members.size - open.size,
        open.map(_.value)
      )
    }

  private def inTransaction[A](p: Principal)(
      f: Subject => ZIO[Env, Failure, A]
  ): ZIO[Env, (StatusCode, ApiError), A] =
    (for
      db     <- ZIO.service[Db]
      authz  <- ZIO.service[Authorizer]
      result <- db.transact(p.tenantId)(authz.subject(p).flatMap(f))
    yield result).mapError {
      case PurposeRequired(m)      => (StatusCode.Forbidden, ApiError("purpose_required", m))
      case e: AccessDenied         => DocumentApi.toResponse(e)
      case e: DbError              => DocumentApi.toResponse(e)
      case e: DocumentError        => DocumentApi.toResponse(e)
      case e: AcknowledgementError => AcknowledgementApi.toResponse(e)
    }
