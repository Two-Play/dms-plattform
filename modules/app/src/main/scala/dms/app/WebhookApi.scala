package dms.app

import dms.audit.*
import dms.authz.{AccessDenied, Action, Authorizer, ConfigurationArea, Resource, Subject}
import dms.db.{Db, DbError, Jsonb}
import dms.identity.{Authenticator, Principal}
import dms.integration.webhooks.*
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.Instant
import java.util.UUID

final case class WebhookRequest(
    /** Receiver URL: `https`; addresses in private, loopback or link-local networks are rejected. */
    url: String,
    /** Any of `document.published`, `document.superseded`, `document.withdrawn`; at least one. */
    eventTypes: List[String],
    /** Class keys; a document matches if its class or an ancestor class is listed. Empty: every class. */
    documentClasses: List[String] = Nil,
    description: Option[String] = None,
    /** Default: active. */
    active: Option[Boolean] = None
) derives JsonCodec,
      Schema

final case class WebhookView(
    id: UUID,
    url: String,
    eventTypes: List[String],
    documentClasses: List[String],
    description: Option[String],
    active: Boolean,
    createdBy: String,
    createdAt: Instant,
    updatedAt: Instant
) derives JsonCodec,
      Schema

/** A subscription with its signing secret, returned only on creation and rotation. */
final case class WebhookWithSecret(
    webhook: WebhookView,
    /** Signing secret (`whsec_...`, Standard Webhooks). Shown once; store it in the receiver. */
    secret: String
) derives JsonCodec,
      Schema

final case class WebhookDeliveryView(
    id: UUID,
    /** The outbox event delivered. */
    eventId: Long,
    eventType: String,
    documentId: Option[UUID],
    /** The document number in the body sent (DMS-107), if the document had one. */
    documentNumber: Option[String],
    /** pending, retrying, delivered, dead (given up or blocked) or skipped (subscription deactivated). */
    status: String,
    attempts: Int,
    lastStatusCode: Option[Int],
    lastError: Option[String],
    createdAt: Instant,
    lastAttemptAt: Option[Instant],
    deliveredAt: Option[Instant]
) derives JsonCodec,
      Schema

/** Outcome of a test event (`webhook.test`, DMS-107). */
final case class WebhookTestResult(
    /** The `webhook-id` sent, `test_<uuid>`. */
    webhookId: String,
    /** Whether the receiver answered 2xx. */
    delivered: Boolean,
    /** The receiver's HTTP status, if it answered. */
    statusCode: Option[Int],
    /** Why it was not delivered: blocked target, unreachable receiver, non-2xx answer, unreadable secret. */
    error: Option[String],
    durationMillis: Long
) derives JsonCodec,
      Schema

/** Webhook subscriptions of connected systems (US-14.1, DMS-58, ADR 0035), managed by tenant administrators
  * (`Configure` on `Webhooks`). Receivers get `document.published`, `document.superseded` and
  * `document.withdrawn` as signed POST requests (Standard Webhooks); the delivery log shows every delivery
  * with its attempts, and dead deliveries can be sent again; a signed test event checks a receiver (DMS-107).
  * Changes are recorded in the audit trail; the secret never is. Secrets are stored encrypted with the
  * platform key and shown only in the answer that creates them (creation, rotation).
  */
object WebhookApi:
  type Env = Authenticator & Db & Authorizer & AuditTrail & WebhookSettings & WebhookSender

  private val base      = "api" / "v1" / "admin" / "webhooks"
  private val webhookId = path[UUID]("id").description("Webhook subscription id")

  val list: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base)
      .out(jsonBody[List[WebhookView]])
      .description("The webhook subscriptions of the tenant, oldest first (tenant administrators)")
      .tag("Webhooks")
      .serverLogic(p => _ => inTransaction(p)(ctx => ctx.administer *> WebhookStore.list.map(_.map(view))))

  val create: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(base)
      .in(jsonBody[WebhookRequest])
      .out(statusCode(StatusCode.Created).and(jsonBody[WebhookWithSecret]))
      .description(
        "Subscribes a receiver (tenant administrators). The URL must be https and resolve to public addresses " +
          "(422 otherwise). Returns the signing secret once."
      )
      .tag("Webhooks")
      .serverLogic(p =>
        req =>
          (administrator(p) *> checked(req)).zip(ZIO.service[WebhookSettings]).flatMap { (valid, settings) =>
            inTransaction(p) { ctx =>
              val id     = UUID.randomUUID()
              val secret = WebhookSignature.newSecret()
              for
                _       <- ctx.administer
                created <- WebhookStore
                  .create(
                    id,
                    valid.url,
                    settings.secretKeys.seal(secret, p.tenantId, id),
                    valid.eventTypes,
                    valid.classes,
                    valid.description,
                    p.userId.value
                  )
                _ <- ZIO.unless(valid.active)(
                  WebhookStore
                    .update(created.id, valid.url, valid.eventTypes, valid.classes, valid.description, false)
                )
                _ <- ctx.record(p, "webhook.created", created.id, details(valid))
              yield WebhookWithSecret(view(created.copy(active = valid.active)), secret)
            }
          }
      )

  val get: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base / webhookId)
      .out(jsonBody[WebhookView])
      .description("A webhook subscription (tenant administrators)")
      .tag("Webhooks")
      .serverLogic(p => id => inTransaction(p)(ctx => ctx.administer *> existing(id).map(view)))

  val update: ZServerEndpoint[Env, Any] =
    SecuredApi.base.put
      .in(base / webhookId)
      .in(jsonBody[WebhookRequest])
      .out(jsonBody[WebhookView])
      .description("Changes a webhook subscription; `active: false` pauses it (tenant administrators)")
      .tag("Webhooks")
      .serverLogic(p =>
        (id, req) =>
          (administrator(p) *> checked(req)).flatMap { valid =>
            inTransaction(p) { ctx =>
              for
                _ <- ctx.administer
                _ <- existing(id)
                _ <- WebhookStore
                  .update(id, valid.url, valid.eventTypes, valid.classes, valid.description, valid.active)
                _       <- ctx.record(p, "webhook.changed", id, details(valid))
                updated <- existing(id)
              yield view(updated)
            }
          }
      )

  val delete: ZServerEndpoint[Env, Any] =
    SecuredApi.base.delete
      .in(base / webhookId)
      .out(statusCode(StatusCode.NoContent))
      .description(
        "Deletes a webhook subscription: it receives nothing from now on; its delivery log is kept (tenant " +
          "administrators)"
      )
      .tag("Webhooks")
      .serverLogic(p =>
        id =>
          inTransaction(p) { ctx =>
            for
              _ <- ctx.administer
              _ <- existing(id)
              _ <- WebhookStore.delete(id)
              _ <- ctx.record(p, "webhook.deleted", id, Json.Obj())
            yield ()
          }
      )

  val rotateSecret: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(base / webhookId / "secret")
      .out(jsonBody[WebhookWithSecret])
      .description(
        "Replaces the signing secret; deliveries from now on are signed with the new one (tenant administrators)"
      )
      .tag("Webhooks")
      .serverLogic(p =>
        id =>
          ZIO.serviceWithZIO[WebhookSettings] { settings =>
            inTransaction(p) { ctx =>
              val secret = WebhookSignature.newSecret()
              for
                _       <- ctx.administer
                _       <- existing(id)
                _       <- WebhookStore.rotateSecret(id, settings.secretKeys.seal(secret, p.tenantId, id))
                _       <- ctx.record(p, "webhook.secret-rotated", id, Json.Obj())
                rotated <- existing(id)
              yield WebhookWithSecret(view(rotated), secret)
            }
          }
      )

  val test: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(base / webhookId / "test")
      .out(jsonBody[WebhookTestResult])
      .description(
        "Sends a signed test event (`webhook.test`) to the receiver right away and returns the outcome; also for " +
          "paused subscriptions. Not part of the delivery log and not retried (tenant administrators)."
      )
      .tag("Webhooks")
      .serverLogic(p =>
        id =>
          for
            target <- inTransaction(p)(ctx =>
              ctx.administer *> existing(id).zip(
                WebhookStore.secret(id).someOrFail(Failure.NotFound(s"the webhook $id does not exist"))
              )
            )
            (subscription, secret) = target
            settings <- ZIO.service[WebhookSettings]
            sender   <- ZIO.service[WebhookSender]
            // Outside the transaction: the receiver may take up to the timeout.
            result <- WebhookPing(sender, settings).send(p.tenantId, id, subscription.url, secret)
            _      <- inTransaction(p)(
              _.record(
                p,
                "webhook.tested",
                id,
                Json.Obj(
                  (List("delivered" -> Json.Bool(result.delivered)) ++
                    result.statusCode.map(c => "statusCode" -> Json.Num(c)))*
                )
              )
            )
          yield WebhookTestResult(
            result.webhookId,
            result.delivered,
            result.statusCode,
            result.error,
            result.durationMillis
          )
      )

  val deliveries: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base / webhookId / "deliveries")
      .in(query[Option[String]]("status").description("Only deliveries in this status, e.g. dead"))
      .in(query[Option[Int]]("limit").description("At most this many, 1–500 (default 100)"))
      .out(jsonBody[List[WebhookDeliveryView]])
      .description("The delivery log of a subscription, newest first (tenant administrators)")
      .tag("Webhooks")
      .serverLogic(p =>
        (id, status, limit) =>
          inTransaction(p) { ctx =>
            for
              _ <- ctx.administer
              _ <- ZIO.foreachDiscard(status.filterNot(WebhookDelivery.statuses.contains))(s =>
                ZIO.fail(Failure.Invalid(s"unknown status '$s'"))
              )
              _     <- existing(id)
              found <- WebhookStore.deliveries(id, status, limit.getOrElse(100).max(1).min(500))
            yield found.map(deliveryView)
          }
      )

  val redeliver: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(base / webhookId / "deliveries" / path[UUID]("deliveryId").description("Delivery id") / "redeliver")
      .out(statusCode(StatusCode.Accepted).and(jsonBody[WebhookDeliveryView]))
      .description(
        "Sends a delivery again with the same id and body, e.g. a dead one after the receiver was fixed (tenant " +
          "administrators). 409 while it is still pending or retrying."
      )
      .tag("Webhooks")
      .serverLogic(p =>
        (id, deliveryId) =>
          ZIO.serviceWithZIO[WebhookSettings] { settings =>
            inTransaction(p) { ctx =>
              for
                _     <- ctx.administer
                _     <- existing(id)
                found <- WebhookStore
                  .delivery(id, deliveryId)
                  .someOrFail(Failure.NotFound(s"no delivery $deliveryId"))
                _ <- ZIO.when(
                  found.status == WebhookDelivery.Pending || found.status == WebhookDelivery.Retrying
                )(ZIO.fail(Failure.Conflict(s"delivery $deliveryId is still ${found.status}")))
                _ <- WebhookJobs.redeliver(deliveryId, settings)
                _ <- ctx.record(
                  p,
                  "webhook.redelivered",
                  id,
                  Json.Obj("deliveryId" -> Json.Str(deliveryId.toString))
                )
                after <- WebhookStore
                  .delivery(id, deliveryId)
                  .someOrFail(Failure.NotFound(s"no delivery $deliveryId"))
              yield deliveryView(after)
            }
          }
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] =
    List(list, create, get, update, delete, rotateSecret, test, deliveries, redeliver)

  /** A request after validation. */
  private final case class Valid(
      url: String,
      eventTypes: Set[String],
      classes: Set[String],
      description: Option[String],
      active: Boolean
  )

  /** Fails unless the caller administers webhooks; before a request is checked, so that only administrators
    * learn about invalid targets or make the platform resolve host names.
    */
  private def administrator(p: Principal): ZIO[Env, (StatusCode, ApiError), Unit] =
    inTransaction(p)(_.administer)

  /** Checks a request, including the SSRF check of the URL (resolves its host; outside the transaction). */
  private def checked(req: WebhookRequest): ZIO[WebhookSettings, (StatusCode, ApiError), Valid] =
    ZIO.serviceWithZIO[WebhookSettings] { settings =>
      val types       = req.eventTypes.map(_.trim).toSet
      val classes     = req.documentClasses.map(_.trim).filter(_.nonEmpty).toSet
      val description = req.description.map(_.trim).filter(_.nonEmpty)
      val problems    = List(
        Option.when(types.isEmpty)("at least one event type is required"),
        Option.when(!types.subsetOf(WebhookEventType.all))(
          s"unknown event types ${(types -- WebhookEventType.all).toList.sorted.mkString(", ")}; known: " +
            WebhookEventType.all.toList.sorted.mkString(", ")
        ),
        Option.when(description.exists(_.length > 500))("the description must have at most 500 characters"),
        Option.when(req.url.length > 2000)("the URL must have at most 2000 characters")
      ).flatten
      (if problems.nonEmpty then ZIO.fail(problems.mkString("; "))
       else UrlGuard.validate(req.url, settings).map(_.toString))
        .mapBoth(
          m => (StatusCode.UnprocessableEntity, ApiError("invalid", m)),
          url => Valid(url, types, classes, description, req.active.getOrElse(true))
        )
    }

  private def details(v: Valid): Json.Obj =
    Json.Obj(
      "url"             -> Json.Str(v.url),
      "eventTypes"      -> Json.Arr(v.eventTypes.toList.sorted.map(Json.Str(_))*),
      "documentClasses" -> Json.Arr(v.classes.toList.sorted.map(Json.Str(_))*),
      "active"          -> Json.Bool(v.active)
    )

  private def existing(id: UUID): IO[Failure | DbError, WebhookSubscription] =
    WebhookStore.get(id).someOrFail(Failure.NotFound(s"the webhook $id does not exist"))

  private enum Failure:
    case Invalid(message: String)
    case NotFound(message: String)
    case Conflict(message: String)

  private final class Context(
      authz: Authorizer,
      audit: AuditTrail,
      subject: Subject,
      tenant: dms.kernel.TenantId
  ):
    def administer: IO[AccessDenied, Unit] =
      authz
        .check(
          subject,
          Action.Configure,
          Resource(tenant, configurationArea = Some(ConfigurationArea.Webhooks))
        )
        .unit

    def record(p: Principal, action: String, id: UUID, details: Json.Obj): IO[DbError, Unit] =
      audit
        .record(
          AuditEvent(
            Actor(p.userId.value, ActorKind.Person),
            action,
            AuditObject("webhook", Some(id)),
            Channel.Api,
            Jsonb(details.toJson)
          )
        )
        .unit

  private def inTransaction[A](principal: Principal)(
      f: Context => ZIO[Any, Failure | AccessDenied | DbError, A]
  ): ZIO[Env, (StatusCode, ApiError), A] =
    (for
      db     <- ZIO.service[Db]
      authz  <- ZIO.service[Authorizer]
      audit  <- ZIO.service[AuditTrail]
      result <- db.transact(principal.tenantId)(
        authz.subject(principal).flatMap(subject => f(Context(authz, audit, subject, principal.tenantId)))
      )
    yield result).mapError {
      case Failure.Invalid(m)  => (StatusCode.UnprocessableEntity, ApiError("invalid", m))
      case Failure.NotFound(m) => (StatusCode.NotFound, ApiError("not_found", m))
      case Failure.Conflict(m) => (StatusCode.Conflict, ApiError("conflict", m))
      case e: AccessDenied     => DocumentApi.toResponse(e)
      case e: DbError          => DocumentApi.toResponse(e)
    }

  private def view(s: WebhookSubscription): WebhookView =
    WebhookView(
      s.id,
      s.url,
      s.eventTypes.toList.sorted,
      s.documentClasses.toList.sorted,
      s.description,
      s.active,
      s.createdBy,
      s.createdAt,
      s.updatedAt
    )

  private def deliveryView(d: WebhookDelivery): WebhookDeliveryView =
    WebhookDeliveryView(
      d.id,
      d.eventId,
      d.eventType,
      d.documentId,
      d.documentNumber,
      d.status,
      d.attempts,
      d.lastStatusCode,
      d.lastError,
      d.createdAt,
      d.lastAttemptAt,
      d.deliveredAt
    )
