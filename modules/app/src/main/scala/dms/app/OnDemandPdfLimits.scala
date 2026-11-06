package dms.app

import com.augustnagro.magnum.*
import dms.db.{Db, DbError}
import dms.kernel.{TenantId, UserId}
import zio.*
import zio.config.magnolia.DeriveConfig

/** Limits of PDFs printed on demand (`draft.pdf`, `copy.pdf`, DMS-118, ADR 0073). 0 turns a limit off.
  *
  * @param perUserPerMinute
  *   requests a user may start per minute, over all application instances; also the size of a burst
  * @param perTenantPerMinute
  *   the same for a whole tenant, so that many users of one tenant cannot fill the worker's time
  * @param inFlightPerTenant
  *   PDFs of a tenant being printed at the same time, **per application instance**
  * @param inFlightPerInstance
  *   PDFs of all tenants being printed at the same time, **per application instance**
  * @param busyRetrySeconds
  *   `Retry-After` of a request refused by one of the in-flight limits
  */
final case class OnDemandPdfConfig(
    perUserPerMinute: Int = 10,
    perTenantPerMinute: Int = 30,
    inFlightPerTenant: Int = 2,
    inFlightPerInstance: Int = 4,
    busyRetrySeconds: Int = 5
)

// Derived here rather than inside AppConfig's derivation, which would exceed the compiler's inline limit.
object OnDemandPdfConfig:
  given DeriveConfig[OnDemandPdfConfig] = DeriveConfig.derived

/** A request refused by a limit.
  *
  * @param retryAfterSeconds
  *   when asking again makes sense, at least 1
  */
final case class OnDemandPdfRefused(limit: OnDemandPdfRefused.Limit, retryAfterSeconds: Int)

object OnDemandPdfRefused:
  enum Limit(val describe: String):
    case User             extends Limit("You have requested too many PDFs.")
    case Tenant           extends Limit("Your organization has requested too many PDFs.")
    case TenantInFlight   extends Limit("Your organization already has as many PDFs in progress as it may.")
    case InstanceInFlight extends Limit("Too many PDFs are in progress right now.")

/** Decides whether a PDF may be printed on demand. */
trait OnDemandPdfLimiter:
  /** Admits one print for the caller until the scope closes, or refuses it. A refused request costs nothing;
    * an admitted one counts whatever becomes of it, since the worker may have done the work.
    */
  def admit(tenant: TenantId, user: UserId): ZIO[Scope, OnDemandPdfRefused | DbError, Unit]

object OnDemandPdfLimiter:
  /** Admits everything. */
  val unlimited: OnDemandPdfLimiter = (_, _) => ZIO.unit

/** Rates are token buckets in `dms.on_demand_pdf_allowance`, so they hold across application instances, with
  * the database clock as the only clock. In-flight limits are counters in memory: they end with the request,
  * also when an instance crashes, and they hold per instance (ADR 0073).
  */
final class OnDemandPdfLimits(db: Db, config: OnDemandPdfConfig, inFlight: Ref[Map[TenantId, Int]])
    extends OnDemandPdfLimiter:
  import OnDemandPdfRefused.Limit

  def admit(tenant: TenantId, user: UserId): ZIO[Scope, OnDemandPdfRefused | DbError, Unit] =
    // The free check first: a request refused for being one too many in flight keeps its token.
    (slot(tenant) *> allowance(tenant, user)).tapError {
      case OnDemandPdfRefused(limit, seconds) =>
        ZIO.logInfo(s"On-demand PDF refused for tenant ${tenant.value}: $limit, retry after $seconds s")
      case _ => ZIO.unit
    }

  private def slot(tenant: TenantId): ZIO[Scope, OnDemandPdfRefused, Unit] =
    ZIO.acquireRelease(
      inFlight
        .modify { held =>
          val own = held.getOrElse(tenant, 0)
          if config.inFlightPerTenant > 0 && own >= config.inFlightPerTenant then
            (Some(Limit.TenantInFlight), held)
          else if config.inFlightPerInstance > 0 && held.values.sum >= config.inFlightPerInstance then
            (Some(Limit.InstanceInFlight), held)
          else (None, held.updated(tenant, own + 1))
        }
        .flatMap {
          case Some(limit) => ZIO.fail(OnDemandPdfRefused(limit, config.busyRetrySeconds.max(1)))
          case None        => ZIO.unit
        }
    )(_ => inFlight.update(_.updatedWith(tenant)(_.map(_ - 1).filter(_ > 0))))

  /** Both tokens or none: a refusal rolls the transaction back, also the user's token. The user's row comes
    * first in every transaction, so two requests cannot wait for each other.
    */
  private def allowance(tenant: TenantId, user: UserId): IO[OnDemandPdfRefused | DbError, Unit] =
    ZIO
      .when(config.perUserPerMinute > 0 || config.perTenantPerMinute > 0)(
        db.transact(tenant)(
          take(tenant, user.value, config.perUserPerMinute, Limit.User) *>
            take(tenant, OnDemandPdfLimits.WholeTenant, config.perTenantPerMinute, Limit.Tenant)
        )
      )
      .unit

  private def take(
      tenant: TenantId,
      subject: String,
      perMinute: Int,
      limit: Limit
  ): IO[OnDemandPdfRefused | DbError, Unit] =
    val capacity = perMinute.toDouble
    ZIO
      .when(perMinute > 0)(
        Db.sql(
          sql"""insert into dms.on_demand_pdf_allowance as a (tenant_id, user_id, tokens, updated_at)
                values (${tenant.value}, $subject, ${capacity - 1}, now())
                on conflict (tenant_id, user_id) do update
                  set tokens = dms.on_demand_pdf_tokens(a.tokens, a.updated_at, $capacity) - 1, updated_at = now()
                  where dms.on_demand_pdf_tokens(a.tokens, a.updated_at, $capacity) >= 1
                returning a.tokens""".query[Double].run().nonEmpty
        ).flatMap { taken =>
          ZIO.unless(taken)(
            Db.sql(
              sql"""select 1 - dms.on_demand_pdf_tokens(a.tokens, a.updated_at, $capacity)
                    from dms.on_demand_pdf_allowance a
                    where a.tenant_id = ${tenant.value} and a.user_id = $subject"""
                .query[Double]
                .run()
                .head
            ).flatMap(missing =>
              ZIO.fail(OnDemandPdfRefused(limit, (missing * 60 / capacity).ceil.toInt.max(1)))
            )
          )
        }
      )
      .unit

object OnDemandPdfLimits:
  /** `user_id` of the bucket of the whole tenant; user ids are never empty. */
  private val WholeTenant: String = ""

  val layer: URLayer[Db & OnDemandPdfConfig, OnDemandPdfLimiter] = ZLayer(
    for
      db       <- ZIO.service[Db]
      config   <- ZIO.service[OnDemandPdfConfig]
      inFlight <- Ref.make(Map.empty[TenantId, Int])
    yield OnDemandPdfLimits(db, config, inFlight)
  )
