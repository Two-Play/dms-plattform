package dms.app

import dms.app.ComplianceCockpit.{Entry, Rate}
import dms.audit.*
import dms.authz.*
import dms.db.{Db, DbError, Jsonb}
import dms.documents.*
import dms.documents.core.*
import dms.identity.{Authenticator, Principal, TenantOrganizations, UserDirectory}
import dms.kernel.{DocumentId, UserId}
import dms.workflow.core.WorkflowError
import sttp.model.{Header, MediaType, StatusCode}
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets
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
    acknowledgementsByUnit: List[UnitRateView],
    /** Documents with a valid version that have no owner (business concept 16). */
    documentsWithoutOwner: Int,
    /** Valid versions that deviate from locked sections of their template, and their deviations in total. */
    versionsWithDeviations: Int,
    deviations: Int
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
    daysOverdue: Long,
    /** When the next reminder to the owner is possible; empty: now. */
    nextReminderAt: Option[Instant] = None,
    /** When the next escalation is possible; empty: now. */
    nextEscalationAt: Option[Instant] = None
) derives JsonCodec,
      Schema

/** A document with a valid version but without owner: nobody answers for it. */
final case class UnownedDocumentView(
    documentId: UUID,
    versionId: UUID,
    title: String,
    versionLabel: String,
    classPath: List[String],
    unit: Option[String],
    publishedAt: Option[Instant]
) derives JsonCodec,
      Schema

/** A valid version that deviates from locked sections of its template (standard clauses, ADR 0022). */
final case class DeviationFigureView(
    documentId: UUID,
    versionId: UUID,
    title: String,
    versionLabel: String,
    classPath: List[String],
    unit: Option[String],
    owner: Option[String],
    deviations: Int
) derives JsonCodec,
      Schema

/** What a compliance officer sends about an overdue review. Both are optional in the JSON so that a missing
  * one is answered as such rather than as a decoding failure.
  */
final case class ComplianceNoticeRequest(
    /** `reminder` (the owner and their deputies) or `escalation` (also the escalation targets). */
    kind: Option[String] = None,
    /** Why it is sent, 1 to 500 characters; recorded in the audit trail, not sent to anyone. */
    reason: Option[String] = None
) derives JsonCodec,
      Schema

/** A notice that was sent. */
final case class ComplianceNoticeView(
    versionId: UUID,
    kind: String,
    sentAt: Instant,
    /** How many people it reaches. */
    recipients: Int,
    /** When the next notice of this kind about the version is possible. */
    nextNoticeAt: Instant
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
    rate: Option[Double],
    /** When the next manual reminder of the readers is possible; empty: now. */
    nextReminderAt: Option[Instant] = None
) derives JsonCodec,
      Schema

/** The stated purpose and reason for looking at named people. They travel in the request body, never in the
  * URL, so they stay out of access logs, browser history and referrers (DMS-144, ADR 0060). Both are optional
  * in the JSON so that a missing one answers 403 `purpose_required` rather than a decoding failure.
  */
final case class PersonsAccessRequest(
    /** `escalation` or `reminder`. */
    purpose: Option[String] = None,
    /** Why the names are needed, 1 to 500 characters; recorded in the audit trail. */
    reason: Option[String] = None
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
  *
  * From the cockpit a compliance officer also reminds the owner of an overdue review or escalates it, at most
  * once per version, kind and 24 hours ([[ComplianceNotices]]), and exports every list as CSV: the same
  * decisions as the screen, built on the server so that the export is recorded in the audit trail (ADR 0077).
  */
object ComplianceApi:
  type Env = Authenticator & Db & Authorizer & RoleAssignments & Groups & DocumentService &
    AcknowledgementService & AuditTrail & ComplianceNoticeAudience & TenantOrganizations & UserDirectory

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

  /** The audit action recorded whenever a list left the cockpit as a file. */
  val ListExported = "compliance.list-exported"

  /** The lists that can be exported, by the name in the path. */
  private val OverdueReviews       = "overdue-reviews"
  private val AcknowledgementRates = "acknowledgements"
  private val WithoutOwner         = "documents-without-owner"
  private val Deviations           = "deviations"
  private val FourEyesFindings     = "four-eyes-findings"

  private val base = "api" / "v1" / "compliance"

  private val unitQuery = query[Option[String]]("unit").description(
    "Organizational unit; empty for versions without unit; absent for all"
  )

  private val language = query[Option[String]]("lang").description("Language of the column names: de or en")

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
          cockpit(p, Needs(rates = true, deviations = true)) { (_, today, entries) =>
            val overdue          = entries.filter(e => ComplianceCockpit.overdue(e.version, today))
            val (versions, rate) = ComplianceCockpit.total(entries)
            val deviating        = entries.filter(_.deviations > 0)
            ComplianceOverviewView(
              today,
              overdue.size,
              ComplianceCockpit.countBy(overdue)(_.unit).map(UnitCountView.apply),
              ComplianceCockpit.countBy(overdue)(_.owner).map(OwnerCountView.apply),
              rateView(versions, rate),
              ComplianceCockpit.ratesByUnit(entries).map((unit, n, r) => UnitRateView(unit, rateView(n, r))),
              ComplianceCockpit.withoutOwner(entries, None).size,
              deviating.size,
              deviating.map(_.deviations).sum
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
          cockpit(p, Needs(notices = true))((now, today, entries) =>
            overdueViews(now, today, entries, unit, owner)
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
          cockpit(p, Needs(rates = true))((now, _, entries) => rateViews(now, entries, unit, document))
      )

  val documentsWithoutOwner: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base / WithoutOwner)
      .in(unitQuery)
      .out(jsonBody[List[UnownedDocumentView]])
      .description("Documents with a valid version in the caller's scope that have no owner, by title")
      .tag("Compliance")
      .serverLogic(p => unit => cockpit(p, Needs())((_, _, entries) => unownedViews(entries, unit)))

  val deviations: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base / Deviations)
      .in(unitQuery)
      .in(query[Option[String]]("owner").description("Owner's user id; empty for documents without owner"))
      .out(jsonBody[List[DeviationFigureView]])
      .description(
        "Valid versions in the caller's scope that deviate from locked sections of their template (standard " +
          "clauses), the most deviations first."
      )
      .tag("Compliance")
      .serverLogic(p =>
        (unit, owner) =>
          cockpit(p, Needs(deviations = true))((_, _, entries) => deviationViews(entries, unit, owner))
      )

  val openAcknowledgements: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(
        base / "acknowledgements" / path[UUID]("documentId").description("Document id") /
          path[UUID]("versionId").description("Version id") / "open"
      )
      .in(
        jsonBody[PersonsAccessRequest].description(
          s"Required: purpose (escalation or reminder) and reason (up to $MaxReasonLength characters; recorded " +
            "in the audit trail). A body, not query parameters, so they stay out of access logs (ADR 0060)."
        )
      )
      .out(jsonBody[OpenAcknowledgementsView])
      .description(
        "Who has not acknowledged the version yet (purpose limitation, ADR 0036): for compliance officers in " +
          "scope and the document's owner, with a stated purpose and reason (403 purpose_required otherwise). " +
          "Purpose and reason are sent in the request body (POST) and recorded in the audit trail with every " +
          "access, which is why this is not a GET. 422 if the version does not " +
          "require acknowledgement."
      )
      .tag("Compliance")
      .serverLogic(p =>
        (documentId, versionId, body) => persons(p, documentId, versionId, body.purpose, body.reason)
      )

  val openAcknowledgementsExport: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(
        base / "acknowledgements" / path[UUID]("documentId").description("Document id") /
          path[UUID]("versionId").description("Version id") / "open" / "export"
      )
      .in(
        jsonBody[PersonsAccessRequest].description(
          s"Required: purpose (escalation or reminder) and reason (up to $MaxReasonLength characters), as for the list."
        )
      )
      .in(language)
      .out(header(Header.contentType(MediaType.TextCsv.charset(StandardCharsets.UTF_8))))
      .out(header[String]("Content-Disposition"))
      .out(byteArrayBody)
      .description(
        "The list of who has not acknowledged the version as CSV for spreadsheets (user id and display name): the " +
          "same rights, purpose and reason as the list, and recorded in the audit trail like every access to it."
      )
      .tag("Compliance")
      .serverLogic(p =>
        (documentId, versionId, body, lang) =>
          for
            view  <- persons(p, documentId, versionId, body.purpose, body.reason, exported = true)
            names <- VersionViewApi.displayNames(p, view.open.map(UserId(_)).toSet)
            table = ComplianceCsv.persons(lang, view.open.map(id => (id, names.get(UserId(id)))))
          yield (attachment(s"open-acknowledgements-$versionId"), bytes(table))
      )

  val exportList: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(
        base / "exports" / path[String]("list").description(
          s"$OverdueReviews, $AcknowledgementRates, $WithoutOwner, $Deviations or $FourEyesFindings"
        )
      )
      .in(unitQuery)
      .in(query[Option[String]]("owner").description("Owner's user id; empty for documents without owner"))
      .in(query[Option[UUID]]("document").description("Document id"))
      .in(language)
      .out(header(Header.contentType(MediaType.TextCsv.charset(StandardCharsets.UTF_8))))
      .out(header[String]("Content-Disposition"))
      .out(byteArrayBody)
      .description(
        "A list of the cockpit as CSV for spreadsheets: UTF-8 with byte order mark, semicolon-separated, with the " +
          "same rights, scope and filters as the list on the screen; text that a spreadsheet would run as a formula " +
          "starts with an apostrophe. Every export is recorded in the audit trail, which is why this is a POST. " +
          s"$FourEyesFindings has no filters, like its list."
      )
      .tag("Compliance")
      .serverLogic(p => (list, unit, owner, document, lang) => exportOf(p, list, unit, owner, document, lang))

  val sendNotice: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(
        base / "overdue-reviews" / path[UUID]("documentId").description("Document id") /
          path[UUID]("versionId").description("Version id") / "notices"
      )
      .in(jsonBody[ComplianceNoticeRequest])
      .out(statusCode(StatusCode.Created).and(jsonBody[ComplianceNoticeView]))
      .description(
        "Reminds the owner of an overdue review (and the owner's deputies) or escalates it (also to the escalation " +
          "targets of the deadline policy; without any, the compliance officers in scope, else the administrators). " +
          "For compliance officers in scope. At most once per version, kind and 24 hours, for all officers together " +
          "(429 notice_too_soon). 422 not_overdue if the review is not overdue, no_recipient if nobody can be " +
          "reached, invalid without kind or reason. The reason is recorded in the audit trail, not sent."
      )
      .tag("Compliance")
      .serverLogic(p => (documentId, versionId, body) => notice(p, documentId, versionId, body))

  def endpoints: List[ZServerEndpoint[Env, Any]] =
    List(
      overview,
      overdueReviews,
      acknowledgements,
      documentsWithoutOwner,
      deviations,
      openAcknowledgementsExport,
      openAcknowledgements,
      exportList,
      sendNotice
    )

  private final case class PurposeRequired(message: String)
  private final case class Refused(status: StatusCode, code: String, message: String)

  private type Failure = DocumentError | AccessDenied | DbError | AcknowledgementError | WorkflowError |
    PurposeRequired | Refused

  /** What a list needs besides the valid versions the caller may monitor; each costs queries. */
  private final case class Needs(
      rates: Boolean = false,
      deviations: Boolean = false,
      notices: Boolean = false
  )

  private def overdueViews(
      now: Instant,
      today: LocalDate,
      entries: List[Entry],
      unit: Option[String],
      owner: Option[String]
  ): List[OverdueReviewView] =
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
            ComplianceCockpit.daysOverdue(e.version, today),
            ComplianceNotices.next(e.lastNotices.get(ComplianceNotices.Reminder), now),
            ComplianceNotices.next(e.lastNotices.get(ComplianceNotices.Escalation), now)
          )
        )
      )

  private def rateViews(
      now: Instant,
      entries: List[Entry],
      unit: Option[String],
      document: Option[UUID]
  ): List[VersionRateView] =
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
          r.share,
          e.lastReminder.map(_.plus(AcknowledgementReminders.ManualInterval)).filter(now.isBefore)
        )
      )

  private def unownedViews(entries: List[Entry], unit: Option[String]): List[UnownedDocumentView] =
    ComplianceCockpit
      .withoutOwner(entries, unit)
      .map(e =>
        UnownedDocumentView(
          e.version.document.id.value,
          e.version.versionId,
          e.version.document.title,
          e.version.label,
          e.version.classPath,
          e.unit,
          e.version.publishedAt
        )
      )

  private def deviationViews(
      entries: List[Entry],
      unit: Option[String],
      owner: Option[String]
  ): List[DeviationFigureView] =
    ComplianceCockpit
      .deviationList(entries, unit, owner)
      .map(e =>
        DeviationFigureView(
          e.version.document.id.value,
          e.version.versionId,
          e.version.document.title,
          e.version.label,
          e.version.classPath,
          e.unit,
          e.owner,
          e.deviations
        )
      )

  private def rateView(versions: Int, r: Rate) =
    RateView(versions, r.audience, r.acknowledged, r.open, r.share)

  private[app] def resource(tenant: dms.kernel.TenantId, v: CurrentVersion): Resource =
    DocumentResource.of(
      tenant,
      v.document.ownerId,
      v.classPath,
      v.status,
      v.createdBy,
      v.releasedBy,
      v.metadata
    )

  /** The compliance officer role is what opens the cockpit. */
  private def requireOfficer(subject: Subject, now: Instant): IO[AccessDenied, Unit] =
    ZIO
      .unless(subject.assignments.exists(a => a.role == Role.ComplianceOfficer && a.isActiveAt(now)))(
        ZIO.fail(
          AccessDenied(
            Action.MonitorCompliance,
            "the compliance cockpit requires the compliance officer role"
          )
        )
      )
      .unit

  /** Runs `f` over the valid versions the caller may monitor, with what `needs` names; denied unless the
    * caller holds an active compliance officer assignment.
    */
  private def cockpit[A](p: Principal, needs: Needs)(
      f: (Instant, LocalDate, List[Entry]) => A
  ): ZIO[Env, (StatusCode, ApiError), A] =
    inTransaction(p)(subject => entries(p, subject, needs).map(f.tupled))

  private def entries(
      p: Principal,
      subject: Subject,
      needs: Needs
  ): ZIO[Env, Failure, (Instant, LocalDate, List[Entry])] =
    for
      now       <- Clock.instant
      _         <- requireOfficer(subject, now)
      documents <- ZIO.service[DocumentService]
      zone      <- documents.tenantZone
      valid     <- documents.currentlyValid
      visible = valid
        .map(v => v -> resource(p.tenantId, v))
        .filter((_, r) => Policy.decide(subject, Action.MonitorCompliance, r, now).isPermitted)
      ids = visible.map(_._1.versionId).toSet
      deviations <- if needs.deviations then documents.deviationCounts(ids) else ZIO.succeed(Map.empty)
      notices    <- if needs.notices then ComplianceNotices.latest(ids) else ZIO.succeed(Map.empty)
      rates      <-
        if !needs.rates then ZIO.succeed((_: CurrentVersion, _: Resource) => Option.empty[Rate])
        else
          for
            readers      <- Recipients.population(now, p.tenantId)
            acknowledged <- ZIO.serviceWithZIO[AcknowledgementService](_.acknowledgersOfValid)
            audiences    <- ZIO.serviceWithZIO[AcknowledgementService](_.audiencesOfValid)
          yield (v: CurrentVersion, r: Resource) =>
            Option.when(v.requiresAcknowledgement) {
              val audience = readers.readers(r, Recipients.filterOf(audiences.get(v.versionId)))
              Rate(audience.size, acknowledged.getOrElse(v.versionId, Set.empty).count(audience.contains))
            }
      reminders <-
        if needs.rates then ZIO.serviceWithZIO[AcknowledgementService](_.lastManualReminders(ids))
        else ZIO.succeed(Map.empty[UUID, Instant])
    yield (
      now,
      LocalDate.ofInstant(now, zone),
      visible.map((v, r) =>
        Entry(
          v,
          rates(v, r),
          deviations.getOrElse(v.versionId, 0),
          reminders.get(v.versionId),
          ComplianceNotices.Kinds.flatMap(k => notices.get((v.versionId, k)).map(k -> _)).toMap
        )
      )
    )

  /** A list as a file: read and recorded in one transaction, so that nothing leaves without a record; names
    * come afterwards, as the directory is another system (ADR 0037).
    */
  private def exportOf(
      p: Principal,
      list: String,
      unit: Option[String],
      owner: Option[String],
      document: Option[UUID],
      lang: Option[String]
  ): ZIO[Env, (StatusCode, ApiError), (String, Array[Byte])] =
    def rows[A](needs: Needs)(
        select: (Instant, LocalDate, List[Entry]) => List[A]
    ): ZIO[Env, (StatusCode, ApiError), List[A]] =
      inTransaction(p) { subject =>
        for
          found <- entries(p, subject, needs)
          rows = select.tupled(found)
          _ <- recordExport(p, list, unit, owner, document, rows.size)
        yield rows
      }
    def named(owners: List[Option[String]]) =
      VersionViewApi
        .displayNames(p, owners.flatten.map(UserId(_)).toSet)
        .map(names => (id: String) => names.get(UserId(id)))
    val table = list match
      case OverdueReviews =>
        for
          found <- rows(Needs())(overdueViews(_, _, _, unit, owner))
          names <- named(found.map(_.owner))
        yield ComplianceCsv.overdueReviews(lang, found, names)
      case AcknowledgementRates =>
        for
          found <- rows(Needs(rates = true))((now, _, all) => rateViews(now, all, unit, document))
          names <- named(found.map(_.owner))
        yield ComplianceCsv.acknowledgements(lang, found, names)
      case WithoutOwner =>
        rows(Needs())((_, _, all) => unownedViews(all, unit)).map(ComplianceCsv.withoutOwner(lang, _))
      case Deviations =>
        for
          found <- rows(Needs(deviations = true))((_, _, all) => deviationViews(all, unit, owner))
          names <- named(found.map(_.owner))
        yield ComplianceCsv.deviations(lang, found, names)
      case FourEyesFindings =>
        for
          found <- inTransaction(p)(subject =>
            FourEyesReportApi
              .visibleFindings(p, subject)
              .tap(all => recordExport(p, list, None, None, None, all.size))
          )
          names <- named(found.flatMap(f => List(f.owner, Some(f.decidedBy), f.actingFor, Some(f.editor))))
        yield ComplianceCsv.fourEyesFindings(lang, found, names)
      case other =>
        ZIO.fail((StatusCode.NotFound, ApiError("not_found", s"the cockpit has no list '$other'")))
    table.map(csv => (attachment(s"compliance-$list"), bytes(csv)))

  private def recordExport(
      p: Principal,
      list: String,
      unit: Option[String],
      owner: Option[String],
      document: Option[UUID],
      rows: Int
  ): ZIO[Env, DbError, Unit] =
    val filters = unit.map("unit" -> Json.Str(_)).toList ++ owner.map("owner" -> Json.Str(_)) ++
      document.map(d => "document" -> Json.Str(d.toString))
    ZIO
      .serviceWithZIO[AuditTrail](
        _.record(
          AuditEvent(
            Actor(p.userId.value, ActorKind.Person),
            ListExported,
            AuditObject("compliance-list"),
            Channel.Api,
            Jsonb(
              Json
                .Obj(
                  (List("list" -> Json.Str(list), "format" -> Json.Str("csv"), "rows" -> Json.Num(rows)) ++
                    filters)*
                )
                .toJson
            )
          )
        )
      )
      .unit

  private def attachment(name: String): String = s"""attachment; filename="$name.csv""""

  private def bytes(csv: String): Array[Byte] = csv.getBytes(StandardCharsets.UTF_8)

  /** Reminds or escalates an overdue review; see [[ComplianceNotices]]. */
  private def notice(
      p: Principal,
      documentId: UUID,
      versionId: UUID,
      body: ComplianceNoticeRequest
  ): ZIO[Env, (StatusCode, ApiError), ComplianceNoticeView] =
    def invalid(message: String) = Refused(StatusCode.UnprocessableEntity, "invalid", message)
    inTransaction(p) { subject =>
      for
        now  <- Clock.instant
        _    <- requireOfficer(subject, now)
        kind <- ZIO
          .fromOption(body.kind.map(_.trim.toLowerCase).filter(ComplianceNotices.Kinds))
          .orElseFail(invalid("State the kind of notice (reminder or escalation)."))
        why <- ZIO
          .fromOption(body.reason.map(_.trim).filter(r => r.nonEmpty && r.length <= MaxReasonLength))
          .orElseFail(
            invalid(
              s"State the reason (1 to $MaxReasonLength characters); it is recorded in the audit trail."
            )
          )
        documents <- ZIO.service[DocumentService]
        valid     <- documents.validVersion(versionId)
        version   <- ZIO
          .fromOption(valid.find(_.document.id.value == documentId))
          .orElseFail(DocumentError.NotFound(s"document $documentId has no valid version $versionId"))
        _ <- ZIO.serviceWithZIO[Authorizer](
          _.check(subject, Action.MonitorCompliance, resource(p.tenantId, version))
        )
        zone <- documents.tenantZone
        _    <- ZIO.unless(ComplianceCockpit.overdue(version, LocalDate.ofInstant(now, zone)))(
          ZIO.fail(
            Refused(
              StatusCode.UnprocessableEntity,
              "not_overdue",
              s"the review of ${version.label} is not overdue"
            )
          )
        )
        escalate = kind == ComplianceNotices.Escalation
        audience <- ZIO.serviceWithZIO[ComplianceNoticeAudience](
          _.of(version.document.id, versionId, escalate, p.userId)
        )
        reached = ComplianceNotices.people(audience, p.userId)
        _ <- ZIO.when(reached.isEmpty || (escalate && audience.escalatedTo.isEmpty))(
          ZIO.fail(
            Refused(
              StatusCode.UnprocessableEntity,
              "no_recipient",
              if escalate then "nobody can be reached beyond the owner"
              else "the document has no owner to remind"
            )
          )
        )
        target = ComplianceNotices.Target(version.document.id, versionId, version.label, version.reviewDueOn)
        sent <- ComplianceNotices.send(target, kind, audience, p.userId, why)
        at   <- ZIO
          .fromEither(sent)
          .mapError(next =>
            Refused(
              StatusCode.TooManyRequests,
              "notice_too_soon",
              s"a notice of this kind about ${version.label} went out less than 24 hours ago; next at $next"
            )
          )
      yield ComplianceNoticeView(versionId, kind, at, reached.size, at.plus(ComplianceNotices.Interval))
    }

  private def persons(
      p: Principal,
      documentId: UUID,
      versionId: UUID,
      purpose: Option[String],
      reason: Option[String],
      exported: Boolean = false
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
        now      <- Clock.instant
        recorded <- ZIO.serviceWithZIO[AcknowledgementService](_.audiences(Set(versionId)))
        readers  <- Recipients
          .population(now, p.tenantId)
          .map(_.readers(r, Recipients.filterOf(recorded.get(versionId))))
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
                    (List(
                      "versionId" -> Json.Str(versionId.toString),
                      "purpose"   -> Json.Str(stated.toString),
                      "open"      -> Json.Num(open.size)
                    ) ++ Option.when(exported)("format" -> Json.Str("csv")))*
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
      case Refused(status, c, m)   => (status, ApiError(c, m))
      case e: WorkflowError        => WorkflowApi.toResponse(e)
      case e: AccessDenied         => DocumentApi.toResponse(e)
      case e: DbError              => DocumentApi.toResponse(e)
      case e: DocumentError        => DocumentApi.toResponse(e)
      case e: AcknowledgementError => AcknowledgementApi.toResponse(e)
    }

/** The lists of the cockpit as CSV (ADR 0077), through the same cells as the "valid on" list: everything that
  * comes from people goes through [[ValidAtApi.Csv.text]], so a title or a name cannot become a formula. User
  * ids stay in the files, as the stable identifier (ADR 0037), with the display name in a column of its own.
  */
private[app] object ComplianceCsv:
  import ValidAtApi.Csv

  private def german(lang: Option[String]): Boolean = lang.exists(_.trim.toLowerCase.startsWith("de"))

  private def pick(lang: Option[String])(de: String*)(en: String*): List[String] =
    (if german(lang) then de else en).toList

  private def text(value: Option[String]): String = value.fold("")(Csv.text)

  private def path(classPath: List[String]): String = Csv.text(classPath.mkString("/"))

  private def owner(id: Option[String], names: String => Option[String]): List[String] =
    List(text(id), text(id.flatMap(names)))

  def overdueReviews(
      lang: Option[String],
      rows: List[OverdueReviewView],
      names: String => Option[String]
  ): String =
    Csv.table(
      pick(lang)(
        "Dokument-ID",
        "Titel",
        "Klasse",
        "Version",
        "Status",
        "Einheit",
        "Eigentümer (Benutzer-ID)",
        "Eigentümer (Name)",
        "Überprüfung fällig am",
        "Tage überfällig"
      )(
        "Document id",
        "Title",
        "Class",
        "Version",
        "Status",
        "Unit",
        "Owner (user id)",
        "Owner (name)",
        "Review date",
        "Days overdue"
      ),
      rows.map(r =>
        List(
          r.documentId.toString,
          Csv.text(r.title),
          path(r.classPath),
          Csv.text(r.versionLabel),
          r.status
        ) ++
          List(text(r.unit)) ++ owner(r.owner, names) ++ List(r.dueOn.toString, r.daysOverdue.toString)
      )
    )

  def acknowledgements(
      lang: Option[String],
      rows: List[VersionRateView],
      names: String => Option[String]
  ): String =
    Csv.table(
      pick(lang)(
        "Dokument-ID",
        "Titel",
        "Klasse",
        "Version",
        "Einheit",
        "Eigentümer (Benutzer-ID)",
        "Eigentümer (Name)",
        "Veröffentlicht",
        "Lesende",
        "Bestätigt",
        "Offen",
        "Quote"
      )(
        "Document id",
        "Title",
        "Class",
        "Version",
        "Unit",
        "Owner (user id)",
        "Owner (name)",
        "Published",
        "Audience",
        "Acknowledged",
        "Open",
        "Rate"
      ),
      rows.map(r =>
        List(
          r.documentId.toString,
          Csv.text(r.title),
          path(r.classPath),
          Csv.text(r.versionLabel),
          text(r.unit)
        ) ++
          owner(r.owner, names) ++ List(
            r.publishedAt.fold("")(_.toString),
            r.audience.toString,
            r.acknowledged.toString,
            r.open.toString,
            r.rate.fold("")(_.toString)
          )
      )
    )

  def withoutOwner(lang: Option[String], rows: List[UnownedDocumentView]): String =
    Csv.table(
      pick(lang)("Dokument-ID", "Titel", "Klasse", "Version", "Einheit", "Veröffentlicht")(
        "Document id",
        "Title",
        "Class",
        "Version",
        "Unit",
        "Published"
      ),
      rows.map(r =>
        List(
          r.documentId.toString,
          Csv.text(r.title),
          path(r.classPath),
          Csv.text(r.versionLabel),
          text(r.unit),
          r.publishedAt.fold("")(_.toString)
        )
      )
    )

  /** The retroactive check of the four-eyes rule (ADR 0078), one finding per row, as on the screen. */
  def fourEyesFindings(
      lang: Option[String],
      rows: List[FourEyesFindingView],
      names: String => Option[String]
  ): String =
    Csv.table(
      pick(lang)(
        "Dokument-ID",
        "Titel",
        "Klasse",
        "Version",
        "Status",
        "Einheit",
        "Eigentümer (Benutzer-ID)",
        "Eigentümer (Name)",
        "Art",
        "Entschieden von (Benutzer-ID)",
        "Entschieden von (Name)",
        "In Vertretung für (Benutzer-ID)",
        "In Vertretung für (Name)",
        "Geändert von (Benutzer-ID)",
        "Geändert von (Name)",
        "Schritt",
        "Runde",
        "Entschieden am",
        "Zuerst geändert am",
        "Prüfung läuft noch"
      )(
        "Document id",
        "Title",
        "Class",
        "Version",
        "Status",
        "Unit",
        "Owner (user id)",
        "Owner (name)",
        "Kind",
        "Decided by (user id)",
        "Decided by (name)",
        "On behalf of (user id)",
        "On behalf of (name)",
        "Changed by (user id)",
        "Changed by (name)",
        "Step",
        "Round",
        "Decided at",
        "First changed at",
        "Review still running"
      ),
      rows.map(r =>
        List(
          r.documentId.toString,
          Csv.text(r.title),
          path(r.classPath),
          Csv.text(r.versionLabel),
          r.versionStatus,
          text(r.unit)
        ) ++ owner(r.owner, names) ++ List(r.kind) ++ owner(Some(r.decidedBy), names) ++
          owner(r.actingFor, names) ++ owner(Some(r.editor), names) ++ List(
            text(r.step),
            r.round.fold("")(_.toString),
            r.decidedAt.toString,
            r.firstChangedAt.toString,
            r.caseOpen.toString
          )
      )
    )

  def deviations(
      lang: Option[String],
      rows: List[DeviationFigureView],
      names: String => Option[String]
  ): String =
    Csv.table(
      pick(lang)(
        "Dokument-ID",
        "Titel",
        "Klasse",
        "Version",
        "Einheit",
        "Eigentümer (Benutzer-ID)",
        "Eigentümer (Name)",
        "Abweichungen"
      )(
        "Document id",
        "Title",
        "Class",
        "Version",
        "Unit",
        "Owner (user id)",
        "Owner (name)",
        "Deviations"
      ),
      rows.map(r =>
        List(
          r.documentId.toString,
          Csv.text(r.title),
          path(r.classPath),
          Csv.text(r.versionLabel),
          text(r.unit)
        ) ++
          owner(r.owner, names) ++ List(r.deviations.toString)
      )
    )

  def persons(lang: Option[String], rows: List[(String, Option[String])]): String =
    Csv.table(
      pick(lang)("Benutzer-ID", "Name")("User id", "Name"),
      rows.map((id, name) => List(Csv.text(id), text(name)))
    )
