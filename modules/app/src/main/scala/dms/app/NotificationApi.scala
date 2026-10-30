package dms.app

import dms.authz.{AccessDenied, Action, Authorizer, ConfigurationArea, Resource}
import dms.db.{Db, DbError}
import dms.identity.{Authenticator, Principal}
import dms.notifications.*
import dms.notifications.core.{
  Delivery,
  DeliverySettings,
  NotificationInbox,
  NotificationJobs,
  NotificationStore
}
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.time.Instant
import java.util.UUID

final case class NotificationView(
    id: UUID,
    /** e.g. workflow.task-assigned, acknowledgement.reminder */
    kind: String,
    /** workflow, acknowledgement, deputyship, publication, legal-hold */
    category: String,
    subject: String,
    body: String,
    /** Path inside the web application, e.g. /documents/{id} */
    link: Option[String],
    documentId: Option[UUID],
    createdAt: Instant,
    readAt: Option[Instant]
) derives JsonCodec,
      Schema

final case class NotificationPageView(
    items: List[NotificationView],
    /** Notifications matching the filter. */
    total: Int,
    /** Unread notifications of the caller. */
    unread: Int
) derives JsonCodec,
      Schema

final case class UnreadCountView(unread: Int) derives JsonCodec, Schema

final case class MarkedReadView(marked: Int) derives JsonCodec, Schema

final case class CategoryPreferenceView(
    category: String,
    /** Whether notifications of this category are also sent by email. */
    email: Boolean,
    emailByDefault: Boolean
) derives JsonCodec,
      Schema

final case class NotificationPreferencesView(
    /** de or en; absent: the tenant's language. */
    language: Option[String],
    tenantLanguage: String,
    categories: List[CategoryPreferenceView],
    /** Whether the platform can send emails at all (an SMTP server is configured). */
    emailAvailable: Boolean
) derives JsonCodec,
      Schema

final case class ChangeNotificationPreferences(
    /** de or en; absent or empty: the tenant's language. */
    language: Option[String],
    /** Category -> email on/off; categories left out keep their setting. */
    email: Map[String, Boolean]
) derives JsonCodec,
      Schema

final case class DeliveryView(
    id: UUID,
    eventId: Long,
    kind: String,
    category: String,
    recipient: String,
    channel: String,
    documentId: Option[UUID],
    /** pending, retrying, delivered, skipped, dead */
    status: String,
    attempts: Int,
    lastError: Option[String],
    createdAt: Instant
) derives JsonCodec,
      Schema

final case class TenantLanguageView(language: String) derives JsonCodec, Schema

/** In-app notifications, preferences and the dead letters of notification delivery (DMS-130, ADR 0052).
  *
  * Everyone signed in reads and marks their own notifications and sets their preferences; tenant
  * administrators (`Configure` on `Notifications`) see failed deliveries, send them again and set the
  * tenant's default language.
  */
object NotificationApi:
  type Env = Authenticator & Db & Authorizer & NotificationJobs & DeliverySettings

  private val base  = "api" / "v1" / "notifications"
  private val admin = "api" / "v1" / "admin" / "notifications"

  val list: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base)
      .in(query[Option[Boolean]]("unread").description("Only unread notifications"))
      .in(query[Option[Int]]("offset").description("Skip this many (default 0)"))
      .in(
        query[Option[Int]]("limit").description(
          s"Page size, 1 to ${NotificationInbox.MaxPageSize} (default 20)"
        )
      )
      .out(jsonBody[NotificationPageView])
      .description("The caller's in-app notifications, newest first, with the number of unread ones")
      .tag("Notifications")
      .serverLogic(p =>
        (unread, offset, limit) =>
          inTransaction(p)(
            NotificationInbox
              .page(p.userId, unread.contains(true), offset.getOrElse(0), limit.getOrElse(20))
              .map(page => NotificationPageView(page.items.map(view), page.total, page.unread))
          )
      )

  val unreadCount: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base / "unread-count")
      .out(jsonBody[UnreadCountView])
      .description("How many of the caller's notifications are unread (the badge of the bell)")
      .tag("Notifications")
      .serverLogic(p =>
        _ => inTransaction(p)(NotificationInbox.unreadCount(p.userId).map(UnreadCountView(_)))
      )

  val markRead: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(base / path[UUID]("id").description("Notification id") / "read")
      .out(statusCode(StatusCode.NoContent))
      .description("Marks one of the caller's notifications as read; 404 for notifications of others")
      .tag("Notifications")
      .serverLogic(p =>
        id =>
          inTransaction(p)(NotificationInbox.markRead(p.userId, id))
            .flatMap(found =>
              ZIO.unless(found)(
                ZIO.fail((StatusCode.NotFound, ApiError("not_found", s"notification $id does not exist")))
              )
            )
            .unit
      )

  val markAllRead: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(base / "read-all")
      .out(jsonBody[MarkedReadView])
      .description("Marks all of the caller's notifications as read")
      .tag("Notifications")
      .serverLogic(p => _ => inTransaction(p)(NotificationInbox.markAllRead(p.userId).map(MarkedReadView(_))))

  val preferences: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base / "preferences")
      .out(jsonBody[NotificationPreferencesView])
      .description("The caller's notification language and email choices per category; in-app is always on")
      .tag("Notifications")
      .serverLogic(p =>
        _ =>
          for
            settings <- ZIO.service[DeliverySettings]
            found    <- inTransaction(p)(NotificationInbox.preferences(p.userId))
          yield preferencesView(found, settings)
      )

  val changePreferences: ZServerEndpoint[Env, Any] =
    SecuredApi.base.put
      .in(base / "preferences")
      .in(jsonBody[ChangeNotificationPreferences])
      .out(jsonBody[NotificationPreferencesView])
      .description(
        "Sets the caller's notification language (de, en; empty: the tenant's) and email per category; 422 " +
          "for an unknown language or category"
      )
      .tag("Notifications")
      .serverLogic(p =>
        req =>
          for
            settings <- ZIO.service[DeliverySettings]
            language <- req.language.map(_.trim).filter(_.nonEmpty) match
              case None       => ZIO.none
              case Some(code) =>
                ZIO
                  .fromOption(Language.fromCode(code))
                  .mapBoth(_ => invalid(s"unknown language $code; supported: de, en"), Some(_))
            email <- ZIO.foreach(req.email.toList)((key, on) =>
              ZIO
                .fromOption(NotificationCategory.fromKey(key))
                .mapBoth(_ => invalid(s"unknown notification category $key"), _ -> on)
            )
            saved <- inTransaction(p)(NotificationInbox.changePreferences(p.userId, language, email.toMap))
          yield preferencesView(saved, settings)
      )

  val deliveries: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(admin / "deliveries")
      .in(query[Option[Int]]("limit").description("At most this many (1 to 200, default 50)"))
      .out(jsonBody[List[DeliveryView]])
      .description(
        "Notification deliveries that failed for good (dead letters), newest first; tenant administrators"
      )
      .tag("Notifications")
      .serverLogic(p =>
        limit =>
          inTransaction(p)(
            configure(p) *> NotificationStore
              .dead(limit.getOrElse(50).max(1).min(200))
              .map(_.map(deliveryView))
          )
      )

  val redeliver: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(admin / "deliveries" / path[UUID]("id").description("Delivery id") / "redeliver")
      .out(statusCode(StatusCode.Accepted))
      .description("Sends a dead delivery again; 409 if it is not dead; tenant administrators")
      .tag("Notifications")
      .serverLogic(p =>
        id =>
          ZIO
            .serviceWithZIO[NotificationJobs](jobs => inTransaction(p)(configure(p) *> jobs.redeliver(id)))
            .flatMap(reopened =>
              ZIO.unless(reopened)(
                ZIO.fail(
                  (StatusCode.Conflict, ApiError("not_dead", s"delivery $id is not dead or does not exist"))
                )
              )
            )
            .unit
      )

  val tenantLanguage: ZServerEndpoint[Env, Any] =
    SecuredApi.base.put
      .in(admin / "language")
      .in(jsonBody[TenantLanguageView])
      .out(jsonBody[TenantLanguageView])
      .description(
        "Sets the tenant's default language of notifications (de, en) for users without a choice; tenant " +
          "administrators"
      )
      .tag("Notifications")
      .serverLogic(p =>
        req =>
          for
            language <- ZIO
              .fromOption(Language.fromCode(req.language))
              .orElseFail(invalid(s"unknown language ${req.language}; supported: de, en"))
            _ <- inTransaction(p)(configure(p) *> NotificationStore.setTenantLanguage(language))
          yield TenantLanguageView(language.code)
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] =
    List(
      list,
      unreadCount,
      markAllRead,
      preferences,
      changePreferences,
      markRead,
      deliveries,
      redeliver,
      tenantLanguage
    )

  private def invalid(message: String) = (StatusCode.UnprocessableEntity, ApiError("invalid", message))

  private def configure(p: Principal): ZIO[Authorizer, AccessDenied | DbError, Unit] =
    ZIO.serviceWithZIO[Authorizer](authz =>
      authz
        .subject(p)
        .flatMap(s =>
          authz.check(
            s,
            Action.Configure,
            Resource(p.tenantId, configurationArea = Some(ConfigurationArea.Notifications))
          )
        )
        .unit
    )

  private def inTransaction[R, A](principal: Principal)(
      f: ZIO[R, AccessDenied | DbError, A]
  ): ZIO[R & Db, (StatusCode, ApiError), A] =
    ZIO
      .serviceWithZIO[Db](_.transact(principal.tenantId)(f))
      .mapError {
        case e: AccessDenied => DocumentApi.toResponse(e)
        case e: DbError      => DocumentApi.toResponse(e)
      }

  def view(n: Notification): NotificationView =
    NotificationView(
      n.id,
      n.kind,
      n.category.key,
      n.subject,
      n.body,
      n.link,
      n.documentId.map(_.value),
      n.createdAt,
      n.readAt
    )

  private def preferencesView(
      p: NotificationPreferences,
      settings: DeliverySettings
  ): NotificationPreferencesView =
    NotificationPreferencesView(
      p.language.map(_.code),
      p.tenantLanguage.code,
      NotificationCategory.values.toList.map(c =>
        CategoryPreferenceView(c.key, p.emailFor(c), c.emailByDefault)
      ),
      settings.emailEnabled
    )

  private def deliveryView(d: Delivery): DeliveryView =
    DeliveryView(
      d.id,
      d.eventId,
      d.kind,
      d.category.key,
      d.recipient.value,
      d.channel.key,
      d.document.map(_.documentId.value),
      d.status,
      d.attempts,
      d.lastError,
      d.createdAt
    )
