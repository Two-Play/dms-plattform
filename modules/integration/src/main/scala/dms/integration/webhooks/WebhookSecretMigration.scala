package dms.integration.webhooks

import dms.db.queue.JobQueue
import dms.db.{Db, DbError}
import dms.kernel.TenantId
import zio.*

/** Brings every stored webhook secret to the active platform key (DMS-107, ADR 0035): plain-text secrets from
  * before migration V29 are encrypted, secrets of older keys are re-encrypted. Runs at startup of every
  * instance, one transaction per tenant; idempotent, and safe beside an administrator rotating a secret (a
  * row that changed meanwhile is left alone).
  *
  * A secret that cannot be decrypted (key no longer configured) is reported and kept; deliveries of its
  * subscription fail until the key is configured again or the secret is rotated.
  */
object WebhookSecretMigration:

  /** Outcome over all tenants. */
  final case class Outcome(resealed: Int, unreadable: Int)

  def run(db: Db, keys: WebhookSecretKeys): IO[DbError, Outcome] =
    JobQueue
      .tenantIds(db)
      .flatMap(ZIO.foreach(_)(tenant => db.transact(tenant)(inTenant(tenant, keys))))
      .map(results => Outcome(results.map(_.resealed).sum, results.map(_.unreadable).sum))

  /** The work for one tenant; runs inside `Db.transact(tenant)`. */
  def inTenant(tenant: TenantId, keys: WebhookSecretKeys): IO[DbError, Outcome] =
    WebhookStore.secrets.flatMap { stored =>
      ZIO
        .foreach(stored.filter((_, s) => keys.needsResealing(s))) { (id, secret) =>
          keys.open(secret, tenant, id) match
            case Right(plain) =>
              WebhookStore
                .reseal(id, secret, keys.seal(plain, tenant, id))
                .map(done => (if done then 1 else 0, 0))
            case Left(problem) =>
              ZIO
                .logWarning(s"Webhook secret of subscription $id (tenant ${tenant.value}) kept: $problem")
                .as((0, 1))
        }
        .map(counts => Outcome(counts.map(_._1).sum, counts.map(_._2).sum))
    }
