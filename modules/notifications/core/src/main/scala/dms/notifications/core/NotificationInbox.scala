package dms.notifications.core

import dms.db.DbError
import dms.kernel.UserId
import dms.notifications.*
import zio.*

import java.util.UUID

/** The in-app notifications and preferences of a user (DMS-130, ADR 0052). Every method runs inside
  * `Db.transact(tenant)` and acts on the caller's own rows only.
  */
object NotificationInbox:
  val MaxPageSize = 100

  /** A page of the user's notifications, newest first. */
  def page(user: UserId, unreadOnly: Boolean, offset: Int, limit: Int): IO[DbError, NotificationPage] =
    for
      items  <- NotificationStore.page(user, unreadOnly, offset.max(0), limit.max(1).min(MaxPageSize))
      counts <- NotificationStore.counts(user)
    yield NotificationPage(items, if unreadOnly then counts._2 else counts._1, counts._2)

  def unreadCount(user: UserId): IO[DbError, Int] = NotificationStore.counts(user).map(_._2)

  /** `false` if the user has no notification `id`. */
  def markRead(user: UserId, id: UUID): IO[DbError, Boolean] =
    Clock.instant.flatMap(NotificationStore.markRead(user, id, _))

  def markAllRead(user: UserId): IO[DbError, Int] =
    Clock.instant.flatMap(NotificationStore.markAllRead(user, _))

  def preferences(user: UserId): IO[DbError, NotificationPreferences] = NotificationStore.preferences(user)

  /** Stores the language (`None`: the tenant's) and the email choices; categories left out keep their current
    * choice.
    */
  def changePreferences(
      user: UserId,
      language: Option[Language],
      email: Map[NotificationCategory, Boolean]
  ): IO[DbError, NotificationPreferences] =
    for
      current <- NotificationStore.preferences(user)
      _       <- NotificationStore.savePreferences(user, language, current.email ++ email)
      updated <- NotificationStore.preferences(user)
    yield updated
