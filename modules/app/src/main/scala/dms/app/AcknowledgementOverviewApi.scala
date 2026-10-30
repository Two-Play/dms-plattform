package dms.app

import dms.audit.*
import dms.authz.{AccessDenied, Action, Authorizer, Groups, RoleAssignments}
import dms.db.{Db, DbError, Jsonb}
import dms.documents.*
import dms.documents.core.*
import dms.identity.{Principal, TenantOrganizations, UserDirectory}
import dms.kernel.{DocumentId, UserId}
import dms.workflow.core.{ApprovalWorkflow, WorkflowError}
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.Instant
import java.util.UUID

/** Who must acknowledge a version (DMS-100, ADR 0048), as recorded when it was published. */
final case class RecipientsView(
    /** True if every reader must acknowledge; otherwise readers with one of the roles, groups or units. */
    everyReader: Boolean,
    /** Business roles, e.g. Reader or Author. */
    roles: List[String],
    /** Keys of access groups. */
    groups: List[String],
    /** Organizational units. */
    units: List[String],
    /** Where the settings came from: class (the document class or an ancestor), tenant (the tenant default)
      * or default (nothing configured).
      */
    source: String,
    sourceClassId: Option[UUID]
) derives JsonCodec,
      Schema

/** Acknowledgement figures of one valid version that requires acknowledgement, without names. */
final case class VersionAcknowledgementOverviewView(
    versionId: UUID,
    versionLabel: String,
    /** Published or ReviewDue. */
    status: String,
    publishedAt: Option[Instant],
    recipients: RecipientsView,
    /** Days between automatic reminders of open acknowledgements; 0: none. */
    reminderDays: Int,
    /** Readers who must acknowledge the version now. */
    audience: Int,
    acknowledged: Int,
    open: Int,
    /** Share of the audience that acknowledged, 0 to 1; empty without audience. */
    rate: Option[Double],
    /** When the last manual reminder was sent. */
    lastReminderAt: Option[Instant],
    /** When the next manual reminder is possible; empty: now. */
    nextReminderAt: Option[Instant]
) derives JsonCodec,
      Schema

/** The acknowledgement overview of a document for its owner and compliance officers. */
final case class AcknowledgementOverviewView(
    documentId: UUID,
    title: String,
    versions: List[VersionAcknowledgementOverviewView]
) derives JsonCodec,
      Schema

/** A reader who must acknowledge a version. */
final case class RecipientView(
    userId: String,
    /** From the user directory; empty if it cannot be asked or does not know the user. */
    displayName: Option[String],
    /** open or done. */
    status: String,
    acknowledgedAt: Option[Instant]
) derives JsonCodec,
      Schema

/** Who must acknowledge a version and whether they did (purpose-limited, ADR 0036, ADR 0048). */
final case class RecipientListView(
    documentId: UUID,
    versionId: UUID,
    title: String,
    versionLabel: String,
    /** The purpose the caller stated; recorded in the audit trail with the reason. */
    purpose: String,
    audience: Int,
    acknowledged: Int,
    open: Int,
    /** Sorted by display name (or user id); filtered by the requested status. */
    recipients: List[RecipientView]
) derives JsonCodec,
      Schema

/** A manual reminder that was sent. */
final case class ReminderSentView(
    versionId: UUID,
    sentAt: Instant,
    /** How many readers who have not acknowledged were reminded. */
    reminded: Int,
    /** When the next manual reminder of the version is possible. */
    nextReminderAt: Instant
) derives JsonCodec,
      Schema

/** Open acknowledgements of one valid version of a document the caller owns (owner dashboard, DMS-130). */
final case class OwnedAcknowledgementView(
    documentId: UUID,
    title: String,
    versionId: UUID,
    versionLabel: String,
    audience: Int,
    acknowledged: Int,
    open: Int
) derives JsonCodec,
      Schema

/** Acknowledgement overview for document owners (DMS-100, ADR 0048; follows ADR 0027 and 0036).
  *
  * Requires `ViewAcknowledgementPersons` on the version: the document's owner (with the owner role) and
  * compliance officers in scope. The overview shows figures only. Names of the recipients are purpose-limited
  * like the cockpit's person list (ADR 0036): only with a stated purpose and reason, recorded in the audit
  * trail in the same transaction. A manual reminder goes to every recipient who has not acknowledged, at most
  * once per 24 hours and version; it is audited (number of readers, no names) and handed to the notifications
  * module through the outbox.
  */
object AcknowledgementOverviewApi:
  type Env = DocumentApi.Env & AcknowledgementService & RoleAssignments & Groups & AuditTrail &
    TenantOrganizations & UserDirectory & AcknowledgementReminderConfig

  private val document = "api" / "v1" / "documents" / path[UUID]("id").description("Document id")
  private val version  = document / "versions" / path[UUID]("versionId").description("Version id")

  val overview: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(document / "acknowledgement-overview")
      .out(jsonBody[AcknowledgementOverviewView])
      .description(
        "Acknowledgement figures of every valid version of the document that requires acknowledgement: " +
          "recipients as set by the document class at publication, audience, acknowledged, open, reminder " +
          "interval and manual reminders. For the document's owner and compliance officers in scope (403 " +
          "otherwise); no names."
      )
      .tag("Acknowledgements")
      .serverLogic(p => id => inTransaction(p)(ctx => overviewOf(p, ctx, DocumentId(id))))

  val recipients: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(version / "acknowledgement-recipients")
      .in(query[Option[String]]("status").description("open, done or absent for all"))
      .in(query[Option[String]]("purpose").description("Required: escalation or reminder"))
      .in(
        query[Option[String]]("reason").description(
          s"Required: why the names are needed, up to ${ComplianceApi.MaxReasonLength} characters; recorded in " +
            "the audit trail"
        )
      )
      .out(jsonBody[RecipientListView])
      .description(
        "Who must acknowledge the version, with display names, and whether they did (acknowledgement time). " +
          "For the document's owner and compliance officers in scope, with a stated purpose and reason (403 " +
          "purpose_required otherwise); every access is recorded in the audit trail. 422 if the version does " +
          "not ask for acknowledgement."
      )
      .tag("Acknowledgements")
      .serverLogic(p =>
        (id, versionId, status, purpose, reason) => recipientList(p, id, versionId, status, purpose, reason)
      )

  val remind: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(version / "acknowledgement-reminders")
      .out(statusCode(StatusCode.Created).and(jsonBody[ReminderSentView]))
      .description(
        "Reminds every recipient who has not acknowledged the version yet (delivered by the notifications " +
          "module). For the document's owner and compliance officers in scope; at most once per 24 hours and " +
          "version (429 reminder_too_soon); 422 nothing_open if everybody acknowledged. Audited."
      )
      .tag("Acknowledgements")
      .serverLogic(p => (id, versionId) => inTransaction(p)(ctx => remindOpen(p, ctx, id, versionId)))

  val mine: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "acknowledgements" / "owned")
      .out(jsonBody[List[OwnedAcknowledgementView]])
      .description(
        "Valid versions of documents the caller owns that still have open acknowledgements, with the figures " +
          "(no names), most open first; for the owner dashboard (DMS-130). Only versions the caller may see the " +
          "figures of (owner role)."
      )
      .tag("Acknowledgements")
      .serverLogic(p => _ => inTransaction(p)(ctx => owned(p, ctx)))

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(mine, overview, recipients, remind)

  private def owned(p: Principal, ctx: DocumentApi.Context) =
    for
      valid <- ctx.service.currentlyValid.map(
        _.filter(v => v.requiresAcknowledgement && v.document.ownerId.contains(p.userId))
      )
      permitted <- ZIO.filter(valid)(v =>
        ctx.allowed(Action.ViewAcknowledgementPersons, ComplianceApi.resource(p.tenantId, v))
      )
      rows <-
        if permitted.isEmpty then ZIO.succeed(Nil)
        else
          for
            now       <- Clock.instant
            readers   <- Recipients.population(now, p.tenantId)
            acks      <- ZIO.service[AcknowledgementService]
            audiences <- acks.audiencesOfValid
            done      <- acks.acknowledgersOfValid
          yield permitted.flatMap { v =>
            val members = readers
              .readers(ComplianceApi.resource(p.tenantId, v), Recipients.filterOf(audiences.get(v.versionId)))
              .members
            val acknowledged = members.count(done.getOrElse(v.versionId, Set.empty).contains)
            val open         = members.size - acknowledged
            Option.when(open > 0)(
              OwnedAcknowledgementView(
                v.document.id.value,
                v.document.title,
                v.versionId,
                v.label,
                members.size,
                acknowledged,
                open
              )
            )
          }
    yield rows.sortBy(r => (-r.open, r.title.toLowerCase))

  private final case class PurposeRequired(message: String)
  private final case class NothingOpen(message: String)

  private type Failure = DocumentError | AccessDenied | WorkflowError | AcknowledgementError | DbError |
    PurposeRequired | NothingOpen

  /** The recipients of a version now: who must acknowledge and who of them did. */
  private final case class Figures(members: List[UserId], acknowledged: Map[UserId, Instant]):
    def open: List[UserId] = members.filterNot(acknowledged.contains)
    def done: Int          = members.count(acknowledged.contains)

  private def figures(
      p: Principal,
      details: DocumentDetails,
      v: DocumentVersion,
      audience: Option[VersionAudience]
  ): ZIO[Env, Failure, Figures] =
    for
      now     <- Clock.instant
      readers <- Recipients
        .population(now, p.tenantId)
        .map(_.readers(DocumentApi.resource(p.tenantId, details, v), Recipients.filterOf(audience)))
      acks <- ZIO.serviceWithZIO[AcknowledgementService](_.acknowledgementsOfVersion(v.id))
    yield Figures(readers.members, acks.map(a => a.userId -> a.acknowledgedAt).toMap)

  private def overviewOf(p: Principal, ctx: DocumentApi.Context, id: DocumentId) =
    for
      details <- ctx.service.get(id)
      _       <- ctx.readable(details)
      requested = details.versions.filter(Acknowledgements.requested)
      permitted <- ZIO.filter(requested)(v =>
        ctx.allowed(Action.ViewAcknowledgementPersons, DocumentApi.resource(p.tenantId, details, v))
      )
      // Deny (and log) if the document asks for acknowledgement but the caller may see none of it.
      _ <- ZIO.when(permitted.isEmpty)(
        ZIO.foreachDiscard(requested.headOption)(v =>
          ctx.check(Action.ViewAcknowledgementPersons, DocumentApi.resource(p.tenantId, details, v))
        )
      )
      acks      <- ZIO.service[AcknowledgementService]
      config    <- ZIO.service[AcknowledgementReminderConfig]
      audiences <- acks.audiences(permitted.map(_.id).toSet)
      reminders <- acks.lastManualReminders(permitted.map(_.id).toSet)
      now       <- Clock.instant
      versions  <- ZIO.foreach(permitted.reverse) { v =>
        val audience = audiences.get(v.id)
        figures(p, details, v, audience).map { f =>
          val last = reminders.get(v.id)
          VersionAcknowledgementOverviewView(
            v.id,
            v.label,
            v.status.toString,
            v.publishedAt,
            recipientsView(audience),
            audience.flatMap(_.reminderDays).getOrElse(config.defaultDays),
            f.members.size,
            f.done,
            f.open.size,
            Option.when(f.members.nonEmpty)(f.done.toDouble / f.members.size),
            last,
            last.map(_.plus(AcknowledgementReminders.ManualInterval)).filter(now.isBefore)
          )
        }
      }
    yield AcknowledgementOverviewView(id.value, details.document.title, versions)

  private def recipientsView(audience: Option[VersionAudience]) =
    val r = audience.fold(AcknowledgementRecipients.everyReader)(_.recipients)
    RecipientsView(
      r.everyReader,
      r.roles.toList.sorted,
      r.groups.toList.sorted,
      r.units.toList.sorted,
      audience.fold(AudienceSource.Default)(_.source).toString.toLowerCase,
      audience.flatMap(_.sourceClass)
    )

  /** The version if it asks for acknowledgement and the caller may see who must acknowledge it. */
  private def permittedVersion(p: Principal, ctx: DocumentApi.Context, id: UUID, versionId: UUID) =
    for
      details <- ctx.service.get(DocumentId(id))
      v       <- ZIO
        .fromOption(details.versions.find(_.id == versionId))
        .orElseFail(DocumentError.NotFound(s"document $id has no version $versionId"))
      _ <- ctx.check(Action.ViewAcknowledgementPersons, DocumentApi.resource(p.tenantId, details, v))
      _ <- ZIO.unless(Acknowledgements.requested(v))(
        ZIO.fail(AcknowledgementError.NotRequested(s"version ${v.label} does not ask for acknowledgement"))
      )
    yield (details, v)

  private def recipientList(
      p: Principal,
      id: UUID,
      versionId: UUID,
      status: Option[String],
      purpose: Option[String],
      reason: Option[String]
  ): ZIO[Env, (StatusCode, ApiError), RecipientListView] =
    for
      wanted <- status.map(_.trim.toLowerCase) match
        case None | Some("")             => ZIO.succeed(None)
        case Some(s @ ("open" | "done")) => ZIO.succeed(Some(s))
        case Some(other)                 =>
          ZIO.fail((StatusCode.BadRequest, ApiError("invalid", s"unknown status '$other'; open or done")))
      listed <- inTransaction(p) { ctx =>
        for
          stated <- ZIO
            .fromOption(purpose.flatMap(ComplianceApi.Purpose.parse))
            .orElseFail(
              PurposeRequired(
                "State the purpose (escalation or reminder) to see who must acknowledge a version."
              )
            )
          why <- ZIO
            .fromOption(
              reason.map(_.trim).filter(r => r.nonEmpty && r.length <= ComplianceApi.MaxReasonLength)
            )
            .orElseFail(
              PurposeRequired(
                s"State the reason (1 to ${ComplianceApi.MaxReasonLength} characters); it is recorded in the " +
                  "audit trail."
              )
            )
          found <- permittedVersion(p, ctx, id, versionId)
          (details, v) = found
          audience <- ZIO.serviceWithZIO[AcknowledgementService](_.audiences(Set(v.id)))
          f        <- figures(p, details, v, audience.get(v.id))
          shown = f.members.filter(u =>
            wanted match
              case Some("open") => !f.acknowledged.contains(u)
              case Some(_)      => f.acknowledged.contains(u)
              case None         => true
          )
          _ <- ZIO.serviceWithZIO[AuditTrail](
            _.record(
              AuditEvent(
                Actor(p.userId.value, ActorKind.Person),
                ComplianceApi.PersonsViewed,
                AuditObject("document", Some(id), Some(v.label)),
                Channel.Api,
                Jsonb(
                  Json
                    .Obj(
                      "versionId" -> Json.Str(versionId.toString),
                      "purpose"   -> Json.Str(stated.toString),
                      "status"    -> Json.Str(wanted.getOrElse("all")),
                      "names"     -> Json.Num(shown.size)
                    )
                    .toJson
                ),
                Some(why)
              )
            )
          )
        yield (details.document.title, v.label, stated, f, shown)
      }
      (title, label, stated, f, shown) = listed
      // Names are resolved after the transaction: the directory is another system (ADR 0037).
      names <- VersionViewApi.displayNames(p, shown.toSet)
    yield RecipientListView(
      id,
      versionId,
      title,
      label,
      stated.toString,
      f.members.size,
      f.done,
      f.open.size,
      shown
        .map(u =>
          RecipientView(
            u.value,
            names.get(u),
            if f.acknowledged.contains(u) then "done" else "open",
            f.acknowledged.get(u)
          )
        )
        .sortBy(r => (r.displayName.getOrElse(r.userId).toLowerCase, r.userId))
    )

  private def remindOpen(p: Principal, ctx: DocumentApi.Context, id: UUID, versionId: UUID) =
    for
      found <- permittedVersion(p, ctx, id, versionId)
      (details, v) = found
      acks     <- ZIO.service[AcknowledgementService]
      audience <- acks.audiences(Set(v.id))
      f        <- figures(p, details, v, audience.get(v.id))
      _        <- ZIO.when(f.open.isEmpty)(
        ZIO.fail(NothingOpen(s"every recipient acknowledged version ${v.label}"))
      )
      sentAt <- acks.remindManually(
        ReminderTarget(details.document.id, v.id, v.label, details.document.title),
        f.open,
        p.userId
      )
    yield ReminderSentView(v.id, sentAt, f.open.size, sentAt.plus(AcknowledgementReminders.ManualInterval))

  private def inTransaction[A](principal: Principal)(
      f: DocumentApi.Context => ZIO[Env, Failure, A]
  ): ZIO[Env, (StatusCode, ApiError), A] =
    (for
      db       <- ZIO.service[Db]
      authz    <- ZIO.service[Authorizer]
      service  <- ZIO.service[DocumentService]
      workflow <- ZIO.service[ApprovalWorkflow]
      result   <- db.transact(principal.tenantId)(
        authz.subject(principal).flatMap(subject => f(DocumentApi.Context(service, workflow, authz, subject)))
      )
    yield result).mapError {
      case PurposeRequired(m)      => (StatusCode.Forbidden, ApiError("purpose_required", m))
      case NothingOpen(m)          => (StatusCode.UnprocessableEntity, ApiError("nothing_open", m))
      case e: WorkflowError        => WorkflowApi.toResponse(e)
      case e: AccessDenied         => DocumentApi.toResponse(e)
      case e: DbError              => DocumentApi.toResponse(e)
      case e: DocumentError        => DocumentApi.toResponse(e)
      case e: AcknowledgementError => AcknowledgementApi.toResponse(e)
    }
