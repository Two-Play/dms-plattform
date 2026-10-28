package dms.integration.webhooks

import dms.db.queue.OutboxEvent
import zio.*
import zio.json.ast.Json

import java.time.Instant
import java.util.UUID

/** Settings of webhook delivery (`dms.webhooks`, ADR 0035).
  *
  * @param allowedHosts
  *   hosts exempt from the SSRF rules: they may use `http` and resolve to private, loopback or link-local
  *   addresses. For development (e.g. `localhost`); empty in production.
  * @param allowPrivateNetworks
  *   lets every subscription target private networks (still `https` only), for receivers inside the company
  *   network; off by default
  * @param timeout
  *   per delivery attempt, including connecting
  * @param maxAttempts
  *   attempts per delivery before it is given up (dead); retries back off exponentially (job runner)
  */
final case class WebhookSettings(
    allowedHosts: Set[String] = Set.empty,
    allowPrivateNetworks: Boolean = false,
    timeout: Duration = 10.seconds,
    maxAttempts: Int = 12
):
  def isAllowedHost(host: String): Boolean = allowedHosts.exists(_.equalsIgnoreCase(host))

/** The event types a subscription can choose (US-14.1). */
object WebhookEventType:
  /** A version became valid (scheduled or immediate publication). */
  val Published = "document.published"

  /** A valid version was replaced by its successor. */
  val Superseded = "document.superseded"

  /** A valid version was withdrawn (US-07.1). */
  val Withdrawn = "document.withdrawn"

  val all: Set[String] = Set(Published, Superseded, Withdrawn)

/** A subscription of a connected system (e.g. the intranet) to document events.
  *
  * @param documentClasses
  *   class keys; a document matches if its class or an ancestor class is listed. Empty: every class.
  */
final case class WebhookSubscription(
    id: UUID,
    url: String,
    eventTypes: Set[String],
    documentClasses: Set[String],
    description: Option[String],
    active: Boolean,
    createdBy: String,
    createdAt: Instant,
    updatedAt: Instant
):
  def matches(notification: WebhookNotification): Boolean =
    active && eventTypes.contains(notification.eventType) &&
      (documentClasses.isEmpty || documentClasses.exists(notification.classPath.contains))

/** One delivery of an event to a subscription: the delivery log entry.
  *
  * @param status
  *   `pending` (not tried yet), `retrying` (failed, tried again later), `delivered`, `dead` (given up after the
  *   last attempt or blocked), `skipped` (subscription deactivated or deleted before it was sent)
  */
final case class WebhookDelivery(
    id: UUID,
    subscriptionId: UUID,
    eventId: Long,
    eventType: String,
    documentId: Option[UUID],
    status: String,
    attempts: Int,
    lastStatusCode: Option[Int],
    lastError: Option[String],
    createdAt: Instant,
    lastAttemptAt: Option[Instant],
    deliveredAt: Option[Instant]
)

object WebhookDelivery:
  val Pending   = "pending"
  val Retrying  = "retrying"
  val Delivered = "delivered"
  val Dead      = "dead"
  val Skipped   = "skipped"

  val statuses: Set[String] = Set(Pending, Retrying, Delivered, Dead, Skipped)

/** What a subscriber is told about an outbox event.
  *
  * @param classPath
  *   class keys of the document's class from the root, for the class filter of subscriptions
  * @param occurredAt
  *   when the change happened
  * @param data
  *   the event data sent to receivers
  */
final case class WebhookNotification(
    eventType: String,
    documentId: UUID,
    classPath: List[String],
    occurredAt: Instant,
    data: Json.Obj
)

/** Port to the business modules: which outbox events are webhook events, with their data. Implemented by the
  * application.
  */
trait WebhookEvents:
  /** Cheap pre-check without database access: whether `event` can be a webhook event at all. */
  def concerns(event: OutboxEvent): Boolean

  /** The notification of `event`, or `None` if it is none (e.g. a recertification, or a confidential
    * document). Runs inside `Db.transact(event.tenant)`.
    */
  def describe(event: OutboxEvent): IO[String, Option[WebhookNotification]]
