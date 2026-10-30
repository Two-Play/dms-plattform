package dms.notifications.core

import dms.db.queue.{ClaimedJob, JobQueue, OutboxEvent}
import dms.db.{Db, DbError, Jsonb}
import dms.kernel.{TenantId, UserId}
import dms.notifications.*
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.ZoneId
import java.util.UUID
import scala.util.Try

/** @param appBaseUrl
  *   address of the web application, for the links in emails (e.g. `https://dms.example.org`)
  * @param emailEnabled
  *   whether an SMTP server is configured; without one only in-app notifications are delivered
  * @param maxAttempts
  *   attempts of an email delivery before it is dead; the job runner backs off exponentially
  * @param retention
  *   read in-app notifications and finished deliveries older than this are deleted
  * @param purgeInterval
  *   how often the clean-up runs per tenant
  */
final case class DeliverySettings(
    appBaseUrl: String,
    emailEnabled: Boolean,
    maxAttempts: Int = 8,
    retention: Duration = 180.days,
    purgeInterval: Duration = 1.day
)

/** Delivery of notifications through the outbox and the job queue (DMS-130, ADR 0052), in two steps like the
  * webhooks (ADR 0035):
  *
  *   1. [[deliver]] (outbox subscriber): the port [[NotificationEvents]] says what an event means for whom.
  *      In one transaction of the event's tenant, one delivery per notice, recipient and channel is recorded
  *      (in-app always, email if configured and chosen for the category) and a job `notifications.deliver`
  *      enqueued for each. The unique key (event, kind, recipient, channel) makes a retried event add
  *      nothing.
  *   2. The job checks again whether the recipient may read the document *now* ([[NotificationDocuments]]):
  *      if not, the text names no title. It renders the text in the recipient's language (their choice, else
  *      the tenant's), stores the in-app notification or sends the email (address from the user directory). A
  *      failed email is retried with backoff until `maxAttempts`, then the delivery is `dead`; an
  *      administrator can send it again.
  *
  * In-app delivery is exactly once (the notification's id is the delivery id); email is at least once with
  * the same `Message-ID` on every attempt.
  */
final class NotificationJobs(
    db: Db,
    events: NotificationEvents,
    documents: NotificationDocuments,
    directory: RecipientDirectory,
    mail: MailSender,
    settings: DeliverySettings
):
  import NotificationJobs.*

  /** Handlers to register with the job runner. */
  val handlers: Map[String, ClaimedJob => IO[String, Unit]] = Map(DeliverKind -> send, PurgeKind -> purge)

  /** Outbox subscriber: records the deliveries of an event's notices and enqueues their jobs. */
  def deliver(event: OutboxEvent): IO[String, Unit] =
    ZIO
      .when(events.concerns(event))(
        db.transact(event.tenant)(
          events.describe(event).flatMap(ZIO.foreachDiscard(_)(fanOut(event, _)))
        ).mapError(describe)
      )
      .unit

  private def fanOut(event: OutboxEvent, notice: Notice): IO[DbError, Unit] =
    ZIO.foreachDiscard(notice.recipients.distinct) { recipient =>
      for
        preferences <- NotificationStore.preferences(recipient)
        channels = Channel.InApp ::
          (if settings.emailEnabled && preferences.emailFor(notice.category) then List(Channel.Email)
           else Nil)
        _ <- ZIO.foreachDiscard(channels) { channel =>
          val id = UUID.randomUUID()
          NotificationStore
            .insertDelivery(id, event.id, notice, recipient, channel)
            .flatMap(created => ZIO.when(created)(enqueue(id, None)))
        }
      yield ()
    }

  /** Sends a dead delivery again (same id, so the in-app notification is still stored once). Runs inside
    * `Db.transact(tenant)`. `false` if the delivery is not dead.
    */
  def redeliver(deliveryId: UUID): IO[DbError, Boolean] =
    NotificationStore
      .reopen(deliveryId)
      .tap(reopened =>
        ZIO.when(reopened)(Clock.instant.flatMap(now => enqueue(deliveryId, Some(now.toEpochMilli))))
      )

  private def enqueue(deliveryId: UUID, attempt: Option[Long]): IO[DbError, Unit] =
    JobQueue
      .enqueue(
        DeliverKind,
        Jsonb(Json.Obj("deliveryId" -> Json.Str(deliveryId.toString)).toJson),
        idempotencyKey = Some(s"$DeliverKind:$deliveryId${attempt.fold("")(a => s":$a")}"),
        maxAttempts = settings.maxAttempts
      )
      .unit

  // ---- the delivery job -------------------------------------------------------------------------------------

  private final case class Prepared(
      delivery: Delivery,
      preferences: NotificationPreferences,
      facts: Option[DocumentFacts],
      zone: ZoneId
  )

  private def send(job: ClaimedJob): IO[String, Unit] =
    for
      tenant   <- ZIO.fromOption(job.tenant).orElseFail(s"job ${job.id} ($DeliverKind) has no tenant")
      id       <- ZIO.fromEither(deliveryOf(job.payload))
      prepared <- db.transact(tenant)(prepare(tenant, id)).mapError(describe)
      _ <- ZIO.foreachDiscard(prepared)(p => attempt(tenant, p, last = job.attempts >= job.maxAttempts))
    yield ()

  /** Loads the delivery and everything decided inside the tenant's transaction: preferences, the read check,
    * the time zone. `None` if nothing is left to do.
    */
  private def prepare(tenant: TenantId, id: UUID): IO[String | DbError, Option[Prepared]] =
    NotificationStore.lockDelivery(id).flatMap {
      case None                                            => ZIO.none
      case Some(d) if Delivery.Finished.contains(d.status) => ZIO.none
      case Some(d)                                         =>
        for
          preferences <- NotificationStore.preferences(d.recipient)
          prepared    <-
            if d.channel == Channel.Email && !preferences.emailFor(d.category) then
              NotificationStore.skip(d.id, s"email is switched off for ${d.category.key}").as(None)
            else if d.channel == Channel.Email && !settings.emailEnabled then
              NotificationStore.skip(d.id, "no SMTP server is configured").as(None)
            else
              for
                facts <- ZIO.foreach(d.document)(documents.visibleTo(tenant, d.recipient, _)).map(_.flatten)
                zone  <- NotificationStore.tenantZone
              yield Some(Prepared(d, preferences, facts, zone))
        yield prepared
    }

  private def attempt(tenant: TenantId, p: Prepared, last: Boolean): IO[String, Unit] =
    val d      = p.delivery
    val people = (d.recipient :: d.params.collect {
      case (k, v) if NotificationTemplates.PersonParams.contains(k) => UserId(v)
    }.toList).toSet
    d.channel match
      case Channel.InApp =>
        for
          // Names are a convenience in-app: without the directory the ids are shown.
          known <- directory.people(tenant, people).catchAll(_ => ZIO.succeed(Map.empty[UserId, Person]))
          rendered = render(p, known)
          now <- Clock.instant
          _   <- record(tenant)(
            NotificationStore.insertNotification(
              d.id,
              d.recipient,
              d.kind,
              d.category,
              rendered.subject,
              rendered.body,
              rendered.link,
              d.document.map(_.documentId),
              now
            ) *> NotificationStore.recordAttempt(d.id, Delivery.Delivered, None)
          )
        yield ()
      case Channel.Email =>
        directory.people(tenant, people).either.flatMap {
          case Left(error)  => failed(tenant, d, s"the user directory is unavailable: $error", last)
          case Right(known) =>
            known.get(d.recipient).flatMap(_.email).map(_.trim).filter(_.nonEmpty) match
              case None => record(tenant)(NotificationStore.skip(d.id, "the recipient has no email address"))
              case Some(address) =>
                val rendered        = render(p, known)
                val language        = p.preferences.effectiveLanguage
                val (subject, text) = NotificationTemplates.email(
                  language,
                  known.get(d.recipient).flatMap(_.displayName),
                  rendered,
                  settings.appBaseUrl
                )
                mail
                  .send(Mail(address, subject, text, s"<${d.id}@notifications.dms>", language))
                  .foldZIO(
                    error => failed(tenant, d, error, last),
                    _ => record(tenant)(NotificationStore.recordAttempt(d.id, Delivery.Delivered, None))
                  )
        }

  private def failed(tenant: TenantId, d: Delivery, error: String, last: Boolean): IO[String, Unit] =
    record(tenant)(
      NotificationStore.recordAttempt(d.id, if last then Delivery.Dead else Delivery.Retrying, Some(error))
    ) *> ZIO.fail(error)

  private def render(p: Prepared, known: Map[UserId, Person]): Rendered =
    val d = p.delivery
    NotificationTemplates.render(
      d.kind,
      p.preferences.effectiveLanguage,
      d.params,
      d.document,
      TemplateContext(
        d.recipient,
        p.facts,
        known.collect { case (id, Person(Some(name), _)) => id -> name },
        p.zone
      )
    )

  private def record(tenant: TenantId)(work: IO[DbError, Unit]): IO[String, Unit] =
    db.transact(tenant)(work).mapError(describe)

  // ---- retention ------------------------------------------------------------------------------------------

  /** Enqueues the clean-up of every tenant for the current interval. Returns the number of new jobs. */
  def tick: IO[DbError, Int] =
    for
      now     <- Clock.instant
      tenants <- JobQueue.tenantIds(db)
      slot = now.toEpochMilli / settings.purgeInterval.toMillis.max(1)
      added <- ZIO.foreach(tenants)(tenant =>
        db.transact(tenant)(
          JobQueue
            .enqueue(PurgeKind, idempotencyKey = Some(s"$PurgeKind:${tenant.value}:$slot"), maxAttempts = 3)
        )
      )
    yield added.flatten.size

  /** Calls [[tick]] every purge interval; failures are logged and retried on the next tick. */
  def schedule: UIO[Nothing] =
    (tick.catchAll(e => ZIO.logWarning(s"Scheduling the notification clean-up failed: $e").as(0)) *>
      ZIO.sleep(settings.purgeInterval)).forever

  private def purge(job: ClaimedJob): IO[String, Unit] =
    for
      tenant <- ZIO.fromOption(job.tenant).orElseFail(s"job ${job.id} ($PurgeKind) has no tenant")
      now    <- Clock.instant
      _      <- db.transact(tenant)(NotificationStore.purge(now.minus(settings.retention))).mapError(describe)
    yield ()

object NotificationJobs:
  val DeliverKind = "notifications.deliver"
  val PurgeKind   = "notifications.purge"

  private def describe(error: String | DbError): String = error match
    case e: DbError => e.toString
    case s: String  => s

  private def deliveryOf(payload: Jsonb): Either[String, UUID] =
    payload.value
      .fromJson[Json.Obj]
      .flatMap(_.get("deliveryId").collect { case Json.Str(id) => id }.toRight("deliveryId missing"))
      .flatMap(id => Try(UUID.fromString(id)).toEither.left.map(_.getMessage))
