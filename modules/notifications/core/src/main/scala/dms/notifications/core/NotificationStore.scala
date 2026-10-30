package dms.notifications.core

import com.augustnagro.magnum.*
import dms.db.{Db, DbError, Jsonb}
import dms.kernel.{DocumentId, UserId}
import dms.notifications.*
import zio.*
import zio.json.*

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.UUID

/** A delivery of one notification to one recipient over one channel (`dms.notification_delivery`). */
final case class Delivery(
    id: UUID,
    eventId: Long,
    kind: String,
    category: NotificationCategory,
    recipient: UserId,
    channel: Channel,
    document: Option[DocumentRef],
    params: Map[String, String],
    status: String,
    attempts: Int,
    lastError: Option[String],
    createdAt: Instant
)

object Delivery:
  val Pending   = "pending"
  val Retrying  = "retrying"
  val Delivered = "delivered"
  val Skipped   = "skipped"
  val Dead      = "dead"

  /** Statuses that need no further attempt. */
  val Finished: Set[String] = Set(Delivered, Skipped, Dead)

/** Deliveries, in-app notifications and preferences (migration V34). Every method runs inside
  * `Db.transact(tenant)`; row-level security limits the rows to the tenant.
  */
object NotificationStore:

  // ---- deliveries ----------------------------------------------------------------------------------------

  /** Records a delivery unless the event already has one of this kind for the recipient and channel. Returns
    * whether it was inserted.
    */
  def insertDelivery(
      id: UUID,
      eventId: Long,
      notice: Notice,
      recipient: UserId,
      channel: Channel
  ): IO[DbError, Boolean] =
    Db.sql(
      sql"""insert into dms.notification_delivery
              (id, tenant_id, event_id, kind, category, recipient, channel, document_id, version_id, params)
            values ($id, dms.current_tenant(), $eventId, ${notice.kind}, ${notice.category.key}, ${recipient.value},
                    ${channel.key}, ${notice.document.map(_.documentId.value)}, ${notice.document.flatMap(
          _.versionId
        )}, ${Jsonb(notice.params.toJson)})
            on conflict (event_id, kind, recipient, channel) do nothing
            returning id""".query[UUID].run().nonEmpty
    )

  private type DeliveryRow = (
      UUID,
      Long,
      String,
      String,
      String,
      String,
      Option[UUID],
      Option[UUID],
      Jsonb,
      String,
      Int,
      Option[String],
      OffsetDateTime
  )

  private def delivery(row: DeliveryRow): Option[Delivery] =
    val (id, event, kind, category, recipient, channel, doc, version, params, status, attempts, error, at) =
      row
    for
      c  <- NotificationCategory.fromKey(category)
      ch <- Channel.fromKey(channel)
    yield Delivery(
      id,
      event,
      kind,
      c,
      UserId(recipient),
      ch,
      doc.map(d => DocumentRef(DocumentId(d), version)),
      params.value.fromJson[Map[String, String]].getOrElse(Map.empty),
      status,
      attempts,
      error,
      at.toInstant
    )

  /** A delivery, locked for the rest of the transaction. */
  def lockDelivery(id: UUID): IO[DbError, Option[Delivery]] =
    Db.sql(
      sql"""select id, event_id, kind, category, recipient, channel, document_id, version_id, params, status, attempts,
                   last_error, created_at from dms.notification_delivery where id = $id for update"""
        .query[DeliveryRow]
        .run()
        .headOption
    ).map(_.flatMap(delivery))

  /** Deliveries of an outbox event, oldest first (for tests and support). */
  def deliveriesOfEvent(eventId: Long): IO[DbError, List[Delivery]] =
    Db.sql(
      sql"""select id, event_id, kind, category, recipient, channel, document_id, version_id, params, status, attempts,
                   last_error, created_at from dms.notification_delivery where event_id = $eventId
            order by created_at, recipient, channel"""
        .query[DeliveryRow]
        .run()
        .toList
    ).map(_.flatMap(delivery))

  /** Deliveries that failed for good, newest first. */
  def dead(limit: Int): IO[DbError, List[Delivery]] =
    Db.sql(
      sql"""select id, event_id, kind, category, recipient, channel, document_id, version_id, params, status, attempts,
                   last_error, created_at from dms.notification_delivery where status = 'dead'
            order by created_at desc limit $limit"""
        .query[DeliveryRow]
        .run()
        .toList
    ).map(_.flatMap(delivery))

  /** Records the outcome of an attempt: `status` and the error, if any. */
  def recordAttempt(id: UUID, status: String, error: Option[String]): IO[DbError, Unit] =
    Db.sql(
      sql"""update dms.notification_delivery
            set status = $status, attempts = attempts + 1, last_error = $error,
                finished_at = case when $status in ('delivered', 'skipped', 'dead') then now() end
            where id = $id""".update.run()
    ).unit

  /** Finishes a delivery without an attempt, e.g. because email is switched off. */
  def skip(id: UUID, why: String): IO[DbError, Unit] =
    Db.sql(
      sql"""update dms.notification_delivery set status = 'skipped', last_error = $why, finished_at = now()
            where id = $id""".update.run()
    ).unit

  /** Sets a dead delivery back to pending so that it is attempted again. Returns whether it was dead. */
  def reopen(id: UUID): IO[DbError, Boolean] =
    Db.sql(
      sql"""update dms.notification_delivery set status = 'pending', finished_at = null
            where id = $id and status = 'dead'""".update.run() > 0
    )

  // ---- in-app notifications -------------------------------------------------------------------------------

  /** Stores the in-app notification of a delivery (its id); a repeated attempt stores nothing. */
  def insertNotification(
      id: UUID,
      user: UserId,
      kind: String,
      category: NotificationCategory,
      subject: String,
      body: String,
      link: Option[String],
      document: Option[DocumentId],
      at: Instant
  ): IO[DbError, Unit] =
    Db.sql(
      sql"""insert into dms.notification
              (id, tenant_id, user_id, kind, category, subject, body, link, document_id, created_at)
            values ($id, dms.current_tenant(), ${user.value}, $kind, ${category.key}, $subject, $body, $link,
                    ${document.map(_.value)}, ${utc(at)})
            on conflict (id) do nothing""".update.run()
    ).unit

  private type NotificationRow =
    (
        UUID,
        String,
        String,
        String,
        String,
        Option[String],
        Option[UUID],
        OffsetDateTime,
        Option[OffsetDateTime]
    )

  private def notification(row: NotificationRow): Option[Notification] =
    val (id, kind, category, subject, body, link, document, at, read) = row
    NotificationCategory
      .fromKey(category)
      .map(c =>
        Notification(
          id,
          kind,
          c,
          subject,
          body,
          link,
          document.map(DocumentId(_)),
          at.toInstant,
          read.map(_.toInstant)
        )
      )

  /** Notifications of `user`, newest first. */
  def page(user: UserId, unreadOnly: Boolean, offset: Int, limit: Int): IO[DbError, List[Notification]] =
    Db.sql(
      sql"""select id, kind, category, subject, body, link, document_id, created_at, read_at
            from dms.notification
            where user_id = ${user.value} and ($unreadOnly = false or read_at is null)
            order by created_at desc, id desc
            offset $offset limit $limit"""
        .query[NotificationRow]
        .run()
        .toList
    ).map(_.flatMap(notification))

  /** How many notifications `user` has, and how many of them are unread. */
  def counts(user: UserId): IO[DbError, (Int, Int)] =
    Db.sql(
      sql"""select count(*)::int, (count(*) filter (where read_at is null))::int
            from dms.notification where user_id = ${user.value}""".query[(Int, Int)].run().head
    )

  /** Marks one notification of `user` as read. `false` if `user` has no such notification. */
  def markRead(user: UserId, id: UUID, at: Instant): IO[DbError, Boolean] =
    Db.sql(
      sql"""update dms.notification set read_at = coalesce(read_at, ${utc(at)})
            where id = $id and user_id = ${user.value}""".update.run() > 0
    )

  /** Marks every unread notification of `user` as read; returns how many. */
  def markAllRead(user: UserId, at: Instant): IO[DbError, Int] =
    Db.sql(
      sql"""update dms.notification set read_at = ${utc(at)}
            where user_id = ${user.value} and read_at is null""".update.run()
    )

  /** Deletes read notifications and finished deliveries created before `before` (retention). */
  def purge(before: Instant): IO[DbError, (Int, Int)] =
    Db.sql {
      val notifications =
        sql"""delete from dms.notification where read_at is not null and created_at < ${utc(before)}""".update
          .run()
      val deliveries =
        sql"""delete from dms.notification_delivery
              where status in ('delivered', 'skipped') and created_at < ${utc(before)}""".update.run()
      (notifications, deliveries)
    }

  // ---- preferences ----------------------------------------------------------------------------------------

  /** The tenant's default language. */
  def tenantLanguage: IO[DbError, Language] =
    Db.sql(
      sql"select language from dms.tenant where id = dms.current_tenant()".query[String].run().headOption
    ).map(_.flatMap(Language.fromCode).getOrElse(Language.De))

  /** The tenant's time zone (dates in texts). */
  def tenantZone: IO[DbError, java.time.ZoneId] =
    Db.sql(
      sql"select time_zone from dms.tenant where id = dms.current_tenant()".query[String].run().headOption
    ).map(
      _.flatMap(z => scala.util.Try(java.time.ZoneId.of(z)).toOption)
        .getOrElse(java.time.ZoneId.of("Europe/Berlin"))
    )

  def setTenantLanguage(language: Language): IO[DbError, Unit] =
    Db.sql(
      sql"update dms.tenant set language = ${language.code} where id = dms.current_tenant()".update.run()
    ).unit

  /** The preferences of `user`, defaults filled in. */
  def preferences(user: UserId): IO[DbError, NotificationPreferences] =
    for
      tenant <- tenantLanguage
      row    <- Db.sql(
        sql"""select language, email from dms.notification_settings where user_id = ${user.value}"""
          .query[(Option[String], Jsonb)]
          .run()
          .headOption
      )
    yield
      val chosen = row
        .flatMap(_._2.value.fromJson[Map[String, Boolean]].toOption)
        .getOrElse(Map.empty)
        .flatMap((k, v) => NotificationCategory.fromKey(k).map(_ -> v))
      NotificationPreferences(
        row.flatMap(_._1).flatMap(Language.fromCode),
        NotificationCategory.values.map(c => c -> chosen.getOrElse(c, c.emailByDefault)).toMap,
        tenant
      )

  /** Stores the preferences of `user`: language and the email choice per category. */
  def savePreferences(
      user: UserId,
      language: Option[Language],
      email: Map[NotificationCategory, Boolean]
  ): IO[DbError, Unit] =
    val chosen = Jsonb(email.map((c, on) => c.key -> on).toJson)
    Db.sql(
      sql"""insert into dms.notification_settings (tenant_id, user_id, language, email, updated_at)
            values (dms.current_tenant(), ${user.value}, ${language.map(_.code)}, $chosen, now())
            on conflict (tenant_id, user_id)
            do update set language = excluded.language, email = excluded.email, updated_at = now()""".update
        .run()
    ).unit

  private def utc(i: Instant): OffsetDateTime = OffsetDateTime.ofInstant(i, ZoneOffset.UTC)
