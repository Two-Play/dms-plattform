package dms.app

import dms.authz.{AccessDenied, Action, Authorizer, ConfigurationArea, Groups, Resource, Role}
import dms.db.{Db, DbError}
import dms.documents.{AcknowledgementRecipients, AcknowledgementSettings}
import dms.documents.core.{
  AcknowledgementError,
  AcknowledgementService,
  DocumentClassService,
  MetadataError,
  StoredAcknowledgementSettings
}
import dms.identity.{Authenticator, Principal}
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.time.Instant
import java.util.UUID

/** Acknowledgement settings of a class or the tenant default (DMS-100, ADR 0048). */
final case class AcknowledgementSettingsView(
    /** Business roles whose holders must acknowledge. */
    roles: List[String],
    /** Keys of access groups whose members must acknowledge. */
    groups: List[String],
    /** Organizational units: readers with a role assignment restricted to one of them must acknowledge. */
    units: List[String],
    /** Days between automatic reminders; 0: none; empty: inherited. */
    reminderDays: Option[Int],
    updatedAt: Instant,
    updatedBy: String
) derives JsonCodec,
      Schema

/** Requested acknowledgement settings; they replace the previous ones as a whole. Without roles, groups and
  * units every reader must acknowledge.
  */
final case class AcknowledgementSettingsChange(
    roles: List[String],
    groups: List[String],
    units: List[String],
    /** Days between automatic reminders (0 to 365, 0: none); omitted: inherited from the tenant default or
      * the installation.
      */
    reminderDays: Option[Int]
) derives JsonCodec,
      Schema

/** A document class with its own acknowledgement settings, if any (otherwise it inherits). */
final case class ClassAcknowledgementSettingsView(
    classId: UUID,
    classKey: String,
    label: String,
    parentId: Option[UUID],
    settings: Option[AcknowledgementSettingsView]
) derives JsonCodec,
      Schema

final case class AcknowledgementSettingsOverviewView(
    /** The installation's reminder interval where neither class nor tenant sets one; 0: none. */
    defaultReminderDays: Int,
    /** Business roles that can be chosen as recipients. */
    roles: List[String],
    tenant: Option[AcknowledgementSettingsView],
    classes: List[ClassAcknowledgementSettingsView]
) derives JsonCodec,
      Schema

/** Administration of who must acknowledge ("Empfängerkreis") and the reminder interval per document class
  * (DMS-100, ADR 0048). Requires `Configure` on document classes (tenant administrator). A class without
  * settings inherits those of its nearest ancestor with settings, then the tenant default. Settings apply to
  * versions published afterwards.
  */
object AcknowledgementSettingsApi:
  type Env = Authenticator & Db & Authorizer & AcknowledgementService & DocumentClassService & Groups &
    AcknowledgementReminderConfig

  private val base     = "api" / "v1" / "admin" / "acknowledgement-settings"
  private val classId  = path[UUID]("classId").description("Document class id")
  private val classes  = base / "classes" / classId
  private val tenant   = base / "tenant"
  private val roleList = Role.values.filterNot(_ == Role.TenantAdmin).map(_.toString).toList

  val list: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base)
      .out(jsonBody[AcknowledgementSettingsOverviewView])
      .description("The tenant default and every document class with its acknowledgement settings")
      .tag("Administration")
      .serverLogic(p =>
        _ =>
          configure(p)(
            for
              config   <- ZIO.service[AcknowledgementReminderConfig]
              stored   <- ZIO.serviceWithZIO[AcknowledgementService](_.settings)
              versions <- ZIO.serviceWithZIO[DocumentClassService](_.listClasses).mapError(fromMetadata)
              byClass = stored.flatMap(s => s.classId.map(_ -> s)).toMap
            yield AcknowledgementSettingsOverviewView(
              config.defaultDays,
              roleList,
              stored.find(_.classId.isEmpty).map(view),
              versions
                .sortBy(_.key.value)
                .map(c =>
                  ClassAcknowledgementSettingsView(
                    c.classId.value,
                    c.key.value,
                    c.label,
                    c.parent.map(_.id.value),
                    byClass.get(c.classId.value).map(view)
                  )
                )
            )
          )
      )

  val changeTenant: ZServerEndpoint[Env, Any] =
    SecuredApi.base.put
      .in(tenant)
      .in(jsonBody[AcknowledgementSettingsChange])
      .out(jsonBody[AcknowledgementSettingsView])
      .description("Replaces the tenant default, which applies to classes without settings of their own")
      .tag("Administration")
      .serverLogic(p => change => configure(p)(save(p, None, change)))

  val removeTenant: ZServerEndpoint[Env, Any] =
    SecuredApi.base.delete
      .in(tenant)
      .out(statusCode(StatusCode.NoContent))
      .description(
        "Removes the tenant default: every reader must acknowledge, installation reminder interval"
      )
      .tag("Administration")
      .serverLogic(p => _ => configure(p)(remove(p, None)))

  val changeClass: ZServerEndpoint[Env, Any] =
    SecuredApi.base.put
      .in(classes)
      .in(jsonBody[AcknowledgementSettingsChange])
      .out(jsonBody[AcknowledgementSettingsView])
      .description(
        "Replaces the acknowledgement settings of a class and its subclasses without settings of their own. " +
          "Applies to versions published afterwards. 422 for unknown roles or groups."
      )
      .tag("Administration")
      .serverLogic(p => (id, change) => configure(p)(existing(id) *> save(p, Some(id), change)))

  val removeClass: ZServerEndpoint[Env, Any] =
    SecuredApi.base.delete
      .in(classes)
      .out(statusCode(StatusCode.NoContent))
      .description("Removes the settings of a class, which then inherits again (404 if it has none)")
      .tag("Administration")
      .serverLogic(p => id => configure(p)(existing(id) *> remove(p, Some(id))))

  def endpoints: List[ZServerEndpoint[Env, Any]] =
    List(list, changeTenant, removeTenant, changeClass, removeClass)

  private final case class Invalid(message: String)
  private final case class NotFound(message: String)

  private type Failure = AccessDenied | DbError | AcknowledgementError | Invalid | NotFound

  private def fromMetadata(e: MetadataError): Failure = e match
    case MetadataError.NotFound(m)  => NotFound(m)
    case MetadataError.Invalid(ps)  => Invalid(ps.mkString("; "))
    case MetadataError.Conflict(m)  => Invalid(m)
    case MetadataError.Database(db) => db

  private def view(s: StoredAcknowledgementSettings) =
    AcknowledgementSettingsView(
      s.settings.recipients.roles.toList.sorted,
      s.settings.recipients.groups.toList.sorted,
      s.settings.recipients.units.toList.sorted,
      s.settings.reminderDays,
      s.updatedAt,
      s.updatedBy.value
    )

  private def existing(id: UUID) =
    ZIO
      .serviceWithZIO[DocumentClassService](_.listClasses)
      .mapError(fromMetadata)
      .filterOrFail(_.exists(_.classId.value == id))(NotFound(s"document class $id does not exist"))

  private def save(p: Principal, classId: Option[UUID], change: AcknowledgementSettingsChange) =
    val roles  = change.roles.map(_.trim).filter(_.nonEmpty).toSet
    val groups = change.groups.map(_.trim).filter(_.nonEmpty).toSet
    val units  = change.units.map(_.trim).filter(_.nonEmpty).toSet
    for
      _ <- ZIO.foreachDiscard((roles -- roleList).headOption)(r =>
        ZIO.fail(Invalid(s"'$r' is not a business role; choose from ${roleList.mkString(", ")}"))
      )
      known <- ZIO.serviceWithZIO[Groups](_.existing(groups))
      _     <- ZIO.foreachDiscard((groups -- known).toList.sorted.headOption)(g =>
        ZIO.fail(Invalid(s"access group '$g' does not exist"))
      )
      stored <- ZIO.serviceWithZIO[AcknowledgementService](
        _.changeSettings(
          classId,
          AcknowledgementSettings(AcknowledgementRecipients(roles, groups, units), change.reminderDays),
          p.userId
        )
      )
    yield view(stored)

  private def remove(p: Principal, classId: Option[UUID]) =
    ZIO
      .serviceWithZIO[AcknowledgementService](_.removeSettings(classId, p.userId))
      .filterOrFail(identity)(
        NotFound(classId.fold("the tenant has no default settings")(id => s"class $id has no settings"))
      )
      .unit

  private def configure[A](p: Principal)(f: ZIO[Env, Failure, A]): ZIO[Env, (StatusCode, ApiError), A] =
    val resource = Resource(p.tenantId, configurationArea = Some(ConfigurationArea.DocumentClasses))
    (for
      db     <- ZIO.service[Db]
      authz  <- ZIO.service[Authorizer]
      result <- db.transact(p.tenantId)(
        authz.subject(p).flatMap(authz.check(_, Action.Configure, resource)) *> f
      )
    yield result).mapError {
      case Invalid(m)              => (StatusCode.UnprocessableEntity, ApiError("invalid", m))
      case NotFound(m)             => (StatusCode.NotFound, ApiError("not_found", m))
      case e: AccessDenied         => DocumentApi.toResponse(e)
      case e: DbError              => DocumentApi.toResponse(e)
      case e: AcknowledgementError => AcknowledgementApi.toResponse(e)
    }
