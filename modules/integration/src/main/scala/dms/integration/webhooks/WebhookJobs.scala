package dms.integration.webhooks

import dms.db.queue.{ClaimedJob, JobQueue, OutboxEvent}
import dms.db.{Db, DbError, Jsonb}
import dms.kernel.TenantId
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.util.UUID
import scala.util.Try

/** Webhook delivery through the outbox and the job queue (ADR 0010, 0035), in two steps so that no event is
  * lost while a receiver is down:
  *
  *   1. [[deliver]] (outbox subscriber): for a webhook event it writes, in one transaction of the event's
  *      tenant, one delivery per matching subscription (with the exact body to send) and a job
  *      `webhook.deliver` for each. It only writes rows, so the outbox relay marks the event delivered only
  *      after every subscription has its delivery; a retried event finds them and adds nothing.
  *   2. The job sends the delivery, signed (see [[WebhookSignature]]), after the SSRF check ([[UrlGuard]]). A
  *      2xx answer delivers it; anything else fails the job, which the job runner retries with exponential
  *      backoff until `maxAttempts`, then the delivery is `dead` (dead letter) and can be sent again by an
  *      administrator. A target the SSRF check blocks is dead at once.
  *
  * Receivers get at-least-once delivery; the `webhook-id` (the delivery id) is the same on every attempt.
  */
final class WebhookJobs(db: Db, events: WebhookEvents, sender: WebhookSender, settings: WebhookSettings):
  import WebhookJobs.*

  /** Handlers to register with the job runner. */
  val handlers: Map[String, ClaimedJob => IO[String, Unit]] = Map(DeliverKind -> send)

  /** Outbox subscriber: records the deliveries of a webhook event and enqueues their jobs. */
  def deliver(event: OutboxEvent): IO[String, Unit] =
    ZIO
      .when(events.concerns(event))(
        db.transact(event.tenant)(
          events.describe(event).flatMap(ZIO.foreachDiscard(_)(fanOut(event, _)))
        ).mapError(describe)
      )
      .unit

  private def fanOut(event: OutboxEvent, notification: WebhookNotification): IO[String | DbError, Unit] =
    for
      subscriptions <- WebhookStore.list
      _             <- ZIO.foreachDiscard(subscriptions.filter(_.matches(notification))) { s =>
        val id = UUID.randomUUID()
        WebhookStore
          .insertDelivery(
            id,
            s.id,
            event.id,
            notification.eventType,
            Some(notification.documentId),
            body(event, notification)
          )
          .flatMap(created => ZIO.when(created)(enqueue(id)))
      }
    yield ()

  /** Sends a delivery again, see [[WebhookJobs.redeliver]]. */
  def redeliver(deliveryId: UUID): IO[DbError, Unit] = WebhookJobs.redeliver(deliveryId, settings)

  private def enqueue(deliveryId: UUID): IO[DbError, Unit] =
    JobQueue
      .enqueue(
        DeliverKind,
        payload(deliveryId),
        idempotencyKey = Some(s"$DeliverKind:$deliveryId"),
        maxAttempts = settings.maxAttempts
      )
      .unit

  private def send(job: ClaimedJob): IO[String, Unit] =
    for
      tenant   <- ZIO.fromOption(job.tenant).orElseFail(s"job ${job.id} ($DeliverKind) has no tenant")
      delivery <- ZIO.fromEither(deliveryOf(job.payload))
      target   <- db.transact(tenant)(WebhookStore.target(delivery)).mapError(describe)
      _        <- target match
        case None                                             => ZIO.unit
        case Some(t) if t.status == WebhookDelivery.Delivered => ZIO.unit
        case Some(t) if t.status == WebhookDelivery.Skipped   => ZIO.unit
        case Some(t) if !t.subscriptionActive                 =>
          record(tenant)(WebhookStore.skip(t.deliveryId, "the subscription was deactivated or deleted"))
        case Some(t) => attempt(tenant, t, last = job.attempts >= job.maxAttempts)
    yield ()

  private def attempt(tenant: TenantId, t: DeliveryTarget, last: Boolean): IO[String, Unit] =
    ZIO
      .fromEither(UrlGuard.parse(t.url, settings))
      .flatMap(uri => UrlGuard.check(uri, settings).as(uri))
      .either
      .flatMap {
        case Left(blocked) =>
          // Blocked targets do not become allowed by retrying: dead at once.
          record(tenant)(
            WebhookStore.recordAttempt(t.deliveryId, WebhookDelivery.Dead, None, Some(blocked))
          ) *>
            ZIO.logWarning(s"Webhook delivery ${t.deliveryId} blocked: $blocked")
        case Right(uri) =>
          for
            now <- Clock.instant
            timestamp = now.getEpochSecond
            id        = t.deliveryId.toString
            headers   = List(
              WebhookSignature.IdHeader        -> id,
              WebhookSignature.TimestampHeader -> timestamp.toString,
              WebhookSignature.SignatureHeader -> WebhookSignature.sign(t.secret, id, timestamp, t.payload),
              "User-Agent"                     -> UserAgent
            )
            answer <- sender.send(uri, headers, t.payload).either
            failed = answer match
              case Right(code) if code >= 200 && code < 300 => None
              case Right(code)                              => Some(s"the receiver answered HTTP $code")
              case Left(error)                              => Some(error)
            status =
              if failed.isEmpty then WebhookDelivery.Delivered
              else if last then WebhookDelivery.Dead
              else WebhookDelivery.Retrying
            _ <- record(tenant)(WebhookStore.recordAttempt(t.deliveryId, status, answer.toOption, failed))
            _ <- ZIO.foreachDiscard(failed)(ZIO.fail(_))
          yield ()
      }

  private def record(tenant: TenantId)(work: IO[DbError, Unit]): IO[String, Unit] =
    db.transact(tenant)(work).mapError(describe)

  private def body(event: OutboxEvent, n: WebhookNotification): String =
    Json
      .Obj(
        "type"      -> Json.Str(n.eventType),
        "timestamp" -> Json.Str(n.occurredAt.toString),
        "data"      -> n.data.merge(Json.Obj("eventId" -> Json.Num(event.id)))
      )
      .toJson

object WebhookJobs:
  val DeliverKind = "webhook.deliver"
  val UserAgent   = "DMS-Webhooks/1"

  /** Sends a delivery again with the same id and body (e.g. a dead one, after the receiver was fixed): sets
    * it back to pending and enqueues a new job. Runs inside `Db.transact(tenant)`.
    */
  def redeliver(deliveryId: UUID, settings: WebhookSettings): IO[DbError, Unit] =
    WebhookStore.reopen(deliveryId) *>
      Clock.instant.flatMap(now =>
        JobQueue
          .enqueue(
            DeliverKind,
            payload(deliveryId),
            idempotencyKey = Some(s"$DeliverKind:$deliveryId:${now.toEpochMilli}"),
            maxAttempts = settings.maxAttempts
          )
          .unit
      )

  private def payload(deliveryId: UUID): Jsonb =
    Jsonb(Json.Obj("deliveryId" -> Json.Str(deliveryId.toString)).toJson)

  private def describe(error: String | DbError): String = error match
    case e: DbError => e.toString
    case s: String  => s

  private def deliveryOf(payload: Jsonb): Either[String, UUID] =
    payload.value
      .fromJson[Json.Obj]
      .flatMap(_.get("deliveryId").collect { case Json.Str(id) => id }.toRight("deliveryId missing"))
      .flatMap(id => Try(UUID.fromString(id)).toEither.left.map(_.getMessage))
