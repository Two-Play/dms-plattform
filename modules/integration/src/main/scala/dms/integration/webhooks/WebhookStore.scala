package dms.integration.webhooks

import com.augustnagro.magnum.*
import dms.db.{Db, DbError, Jsonb}
import zio.*
import zio.json.*

import java.time.OffsetDateTime
import java.util.UUID

/** What a delivery attempt needs: the delivery and its subscription. */
final case class DeliveryTarget(
    deliveryId: UUID,
    subscriptionId: UUID,
    status: String,
    payload: String,
    url: String,
    secret: StoredSecret,
    subscriptionActive: Boolean
)

/** Webhook subscriptions and deliveries (migrations V22, V29). Every method runs inside
  * `Db.transact(tenant)`; RLS limits the rows to the tenant. Lists of event types and classes are passed as
  * JSON to stay independent of the driver's array mapping.
  */
object WebhookStore:

  private type SubscriptionRow =
    (UUID, String, String, String, Option[String], Boolean, String, OffsetDateTime, OffsetDateTime)

  private def subscription(row: SubscriptionRow): WebhookSubscription =
    val (id, url, types, classes, description, active, createdBy, createdAt, updatedAt) = row
    WebhookSubscription(
      id,
      url,
      strings(types),
      strings(classes),
      description,
      active,
      createdBy,
      createdAt.toInstant,
      updatedAt.toInstant
    )

  private def strings(json: String): Set[String] = json.fromJson[List[String]].fold(_ => Set.empty, _.toSet)

  private def json(values: Set[String]): Jsonb = Jsonb(values.toList.sorted.toJson)

  /** Stores a secret: encrypted, or (only for tests of the migration) in plain text. */
  private def columns(secret: StoredSecret): (Option[String], Option[String], Option[String]) = secret match
    case StoredSecret.Sealed(keyId, ciphertext) => (None, Some(keyId), Some(ciphertext))
    case StoredSecret.Plain(plain)              => (Some(plain), None, None)

  private def stored(plain: Option[String], keyId: Option[String], ciphertext: Option[String]): StoredSecret =
    (keyId, ciphertext) match
      case (Some(k), Some(c)) => StoredSecret.Sealed(k, c)
      case _                  => StoredSecret.Plain(plain.getOrElse(""))

  /** Creates a subscription with id `id`; the secret is encrypted by the caller ([[WebhookSecretKeys.seal]]
    * binds it to the id).
    */
  def create(
      id: UUID,
      url: String,
      secret: StoredSecret,
      eventTypes: Set[String],
      documentClasses: Set[String],
      description: Option[String],
      createdBy: String
  ): IO[DbError, WebhookSubscription] =
    val (plain, keyId, ciphertext) = columns(secret)
    Db.sql(
      sql"""insert into dms.webhook_subscription
              (id, tenant_id, url, secret, secret_key_id, secret_ciphertext, event_types, document_classes,
               description, created_by)
            values ($id, dms.current_tenant(), $url, $plain, $keyId, $ciphertext, array(select jsonb_array_elements_text(${json(
          eventTypes
        )})),
                    array(select jsonb_array_elements_text(${json(
          documentClasses
        )})), $description, $createdBy)
            returning id, url, array_to_json(event_types)::text, array_to_json(document_classes)::text, description, active,
                   created_by, created_at, updated_at""".query[SubscriptionRow].run().head
    ).map(subscription)

  /** Subscriptions that are not deleted, oldest first. */
  def list: IO[DbError, List[WebhookSubscription]] =
    Db.sql(
      sql"""select id, url, array_to_json(event_types)::text, array_to_json(document_classes)::text, description, active,
                   created_by, created_at, updated_at
            from dms.webhook_subscription where deleted_at is null order by created_at, id"""
        .query[SubscriptionRow]
        .run()
        .toList
    ).map(_.map(subscription))

  def get(id: UUID): IO[DbError, Option[WebhookSubscription]] =
    Db.sql(
      sql"""select id, url, array_to_json(event_types)::text, array_to_json(document_classes)::text, description, active,
                   created_by, created_at, updated_at
            from dms.webhook_subscription where id = $id and deleted_at is null"""
        .query[SubscriptionRow]
        .run()
        .headOption
    ).map(_.map(subscription))

  def update(
      id: UUID,
      url: String,
      eventTypes: Set[String],
      documentClasses: Set[String],
      description: Option[String],
      active: Boolean
  ): IO[DbError, Boolean] =
    Db.sql(
      sql"""update dms.webhook_subscription
            set url = $url, event_types = array(select jsonb_array_elements_text(${json(eventTypes)})),
                document_classes = array(select jsonb_array_elements_text(${json(documentClasses)})),
                description = $description, active = $active, updated_at = now()
            where id = $id and deleted_at is null""".update.run() > 0
    )

  def rotateSecret(id: UUID, secret: StoredSecret.Sealed): IO[DbError, Boolean] =
    Db.sql(
      sql"""update dms.webhook_subscription
            set secret = null, secret_key_id = ${secret.keyId}, secret_ciphertext = ${secret.ciphertext},
                updated_at = now()
            where id = $id and deleted_at is null""".update.run() > 0
    )

  /** The stored secret of a subscription that is not deleted. */
  def secret(id: UUID): IO[DbError, Option[StoredSecret]] =
    Db.sql(
      sql"""select secret, secret_key_id, secret_ciphertext from dms.webhook_subscription
            where id = $id and deleted_at is null"""
        .query[(Option[String], Option[String], Option[String])]
        .run()
        .headOption
    ).map(_.map(stored.tupled))

  /** Every stored secret of the tenant, including those of deleted subscriptions. */
  def secrets: IO[DbError, List[(UUID, StoredSecret)]] =
    Db.sql(
      sql"""select id, secret, secret_key_id, secret_ciphertext from dms.webhook_subscription order by id"""
        .query[(UUID, Option[String], Option[String], Option[String])]
        .run()
        .toList
    ).map(_.map((id, p, k, c) => id -> stored(p, k, c)))

  /** Replaces the stored secret `before` with `after` (re-encryption); `false` if it changed meanwhile, e.g.
    * because an administrator rotated it.
    */
  def reseal(id: UUID, before: StoredSecret, after: StoredSecret.Sealed): IO[DbError, Boolean] =
    val (plain, keyId, ciphertext) = columns(before)
    Db.sql(
      sql"""update dms.webhook_subscription
            set secret = null, secret_key_id = ${after.keyId}, secret_ciphertext = ${after.ciphertext}
            where id = $id and secret is not distinct from cast($plain as text)
              and secret_key_id is not distinct from cast($keyId as text)
              and secret_ciphertext is not distinct from cast($ciphertext as text)""".update.run() > 0
    )

  /** Deletes a subscription: it stops receiving events; it and its delivery log are kept. */
  def delete(id: UUID): IO[DbError, Boolean] =
    Db.sql(
      sql"""update dms.webhook_subscription set active = false, deleted_at = now(), updated_at = now()
            where id = $id and deleted_at is null""".update.run() > 0
    )

  /** Records a delivery of an outbox event to a subscription; `false` if it exists already. */
  def insertDelivery(
      id: UUID,
      subscription: UUID,
      eventId: Long,
      eventType: String,
      documentId: Option[UUID],
      payload: String
  ): IO[DbError, Boolean] =
    Db.sql(
      sql"""insert into dms.webhook_delivery
              (id, tenant_id, subscription_id, event_id, event_type, document_id, payload)
            values ($id, dms.current_tenant(), $subscription, $eventId, $eventType, $documentId, ${Jsonb(
          payload
        )})
            on conflict (subscription_id, event_id) do nothing""".update.run() > 0
    )

  def target(deliveryId: UUID): IO[DbError, Option[DeliveryTarget]] =
    Db.sql(
      sql"""select d.id, d.subscription_id, d.status, d.payload::text, s.url, s.secret, s.secret_key_id,
                   s.secret_ciphertext, s.active and s.deleted_at is null
            from dms.webhook_delivery d join dms.webhook_subscription s on s.id = d.subscription_id
            where d.id = $deliveryId"""
        .query[(UUID, UUID, String, String, String, Option[String], Option[String], Option[String], Boolean)]
        .run()
        .headOption
    ).map(_.map { (id, subscription, status, payload, url, plain, keyId, ciphertext, active) =>
      DeliveryTarget(id, subscription, status, payload, url, stored(plain, keyId, ciphertext), active)
    })

  /** Records an attempt: its outcome, the HTTP status if there was an answer, the error otherwise. */
  def recordAttempt(
      deliveryId: UUID,
      status: String,
      statusCode: Option[Int],
      error: Option[String]
  ): IO[DbError, Unit] =
    Db.sql(
      sql"""update dms.webhook_delivery
            set status = $status, attempts = attempts + 1, last_status_code = $statusCode,
                last_error = ${error.map(_.take(1000))}, last_attempt_at = now(),
                delivered_at = case when cast($status as text) = 'delivered' then now() end
            where id = $deliveryId""".update.run()
    ).unit

  /** Marks a delivery that is not sent at all, e.g. because its subscription was deactivated. */
  def skip(deliveryId: UUID, reason: String): IO[DbError, Unit] =
    Db.sql(
      sql"""update dms.webhook_delivery set status = ${WebhookDelivery.Skipped}, last_error = $reason
            where id = $deliveryId""".update.run()
    ).unit

  /** Sets a delivery back to pending before it is sent again. */
  def reopen(deliveryId: UUID): IO[DbError, Unit] =
    Db.sql(
      sql"""update dms.webhook_delivery set status = ${WebhookDelivery.Pending}, delivered_at = null
            where id = $deliveryId""".update.run()
    ).unit

  private type DeliveryRow = (
      UUID,
      UUID,
      Long,
      String,
      Option[UUID],
      Option[String],
      String,
      Int,
      Option[Int],
      Option[String],
      OffsetDateTime,
      Option[OffsetDateTime],
      Option[OffsetDateTime]
  )

  private def delivery(row: DeliveryRow): WebhookDelivery =
    val (
      id,
      sub,
      event,
      eventType,
      doc,
      number,
      status,
      attempts,
      code,
      error,
      created,
      attempted,
      delivered
    ) =
      row
    WebhookDelivery(
      id,
      sub,
      event,
      eventType,
      doc,
      number,
      status,
      attempts,
      code,
      error,
      created.toInstant,
      attempted.map(_.toInstant),
      delivered.map(_.toInstant)
    )

  /** The deliveries of a subscription, newest first, optionally of one status. */
  def deliveries(subscription: UUID, status: Option[String], limit: Int): IO[DbError, List[WebhookDelivery]] =
    Db.sql(
      sql"""select id, subscription_id, event_id, event_type, document_id, payload -> 'data' ->> 'documentNumber',
                   status, attempts, last_status_code,
                   last_error, created_at, last_attempt_at, delivered_at
            from dms.webhook_delivery
            where subscription_id = $subscription and (cast($status as text) is null or status = $status)
            order by created_at desc, event_id desc
            limit $limit""".query[DeliveryRow].run().toList
    ).map(_.map(delivery))

  def delivery(subscription: UUID, id: UUID): IO[DbError, Option[WebhookDelivery]] =
    Db.sql(
      sql"""select id, subscription_id, event_id, event_type, document_id, payload -> 'data' ->> 'documentNumber',
                   status, attempts, last_status_code,
                   last_error, created_at, last_attempt_at, delivered_at
            from dms.webhook_delivery where subscription_id = $subscription and id = $id"""
        .query[DeliveryRow]
        .run()
        .headOption
    ).map(_.map(delivery))

  /** The body of a delivery as sent. */
  def payload(id: UUID): IO[DbError, Option[String]] =
    Db.sql(
      sql"select payload::text from dms.webhook_delivery where id = $id".query[String].run().headOption
    )
