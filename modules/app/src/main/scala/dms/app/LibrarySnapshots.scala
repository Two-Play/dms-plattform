package dms.app

import dms.authz.{DocumentResource, Resource}
import dms.db.{Db, DbError}
import dms.documents.Validity
import dms.documents.core.{CurrentVersion, DocumentError, DocumentService}
import dms.kernel.TenantId
import zio.*
import zio.config.magnolia.DeriveConfig

import java.time.{Instant, ZoneId}

/** The library's copy of the valid versions (DMS-60, ADR 0056), `dms.library`.
  *
  * @param cacheMaxVersions
  *   valid versions kept in memory over all tenants, the tenants loaded longest ago go first; a tenant with
  *   more versions is not kept, 0 turns the copy off
  * @param cacheMaxAgeSeconds
  *   a copy is read anew after this time even if no version changed, so changes that do not touch a version
  *   (e.g. a document's owner) reach the library
  */
final case class LibraryConfig(cacheMaxVersions: Int = 200000, cacheMaxAgeSeconds: Int = 30)

// Derived here rather than inside AppConfig's derivation, which would exceed the compiler's inline limit.
object LibraryConfig:
  given DeriveConfig[LibraryConfig] = DeriveConfig.derived

/** A valid version as the library shows it to everyone, and what decides whether a caller may read it. The
  * fields that depend on the caller or the request are filled in per request (`LibraryApi`).
  */
final case class LibraryEntry(resource: Resource, item: LibraryItem, firstPublished: Instant)

/** The valid versions of a tenant at `stamp`, sorted by title. */
final case class LibrarySnapshot(
    stamp: Option[Instant],
    loadedAt: Instant,
    zone: ZoneId,
    entries: List[LibraryEntry]
)

/** The valid versions of each tenant kept between requests: the library lists, filters and counts all valid
  * versions a caller may read, and reading and decoding them per request does not scale (ADR 0056).
  *
  * Holds only what is the same for every reader of the tenant. Permissions, acknowledgements and legal holds
  * are decided per request on top of it. A copy is used while the tenant's validity stamp is unchanged, which
  * one index lookup tells, so a published or withdrawn version shows up at once, on every instance. Only one
  * request per tenant reads a new copy; the others wait for it without holding a database connection.
  */
final class LibrarySnapshots(
    db: Db,
    documents: DocumentService,
    config: LibraryConfig,
    copies: Ref[Map[TenantId, LibrarySnapshot]],
    gates: Ref.Synchronized[Map[TenantId, Semaphore]]
):
  private val maxAge = java.time.Duration.ofSeconds(config.cacheMaxAgeSeconds.max(0).toLong)

  def of(tenant: TenantId): IO[DocumentError | DbError, LibrarySnapshot] =
    if config.cacheMaxVersions <= 0 then load(tenant)
    else
      current(tenant).flatMap {
        case Some(snapshot) => ZIO.succeed(snapshot)
        case None           =>
          gate(tenant).flatMap(
            _.withPermit(current(tenant).someOrElseZIO(load(tenant).tap(keep(tenant, _))))
          )
      }

  /** The kept copy, if it is still the tenant's current state. */
  private def current(tenant: TenantId): IO[DocumentError | DbError, Option[LibrarySnapshot]] =
    copies.get.map(_.get(tenant)).flatMap {
      case None           => ZIO.none
      case Some(snapshot) =>
        for
          stamp <- db.transact(tenant)(documents.validityStamp)
          now   <- Clock.instant
        yield Option.when(snapshot.stamp == stamp && snapshot.loadedAt.plus(maxAge).isAfter(now))(snapshot)
    }

  /** The stamp comes first: a change between the two reads makes the copy look older than it is, not newer.
    */
  private def load(tenant: TenantId): IO[DocumentError | DbError, LibrarySnapshot] =
    db.transact(tenant)(
      for
        stamp <- documents.validityStamp
        zone  <- documents.tenantZone
        valid <- documents.currentlyValid
        now   <- Clock.instant
      yield LibrarySnapshot(stamp, now, zone, LibrarySnapshots.entries(tenant, valid, zone))
    )

  private def keep(tenant: TenantId, snapshot: LibrarySnapshot): UIO[Unit] =
    copies.update(all =>
      val others = all - tenant
      if snapshot.entries.size > config.cacheMaxVersions then others
      else
        val kept = others.toList
          .sortBy(_._2.loadedAt)
          .reverse
          .foldLeft((Map(tenant -> snapshot), snapshot.entries.size)) { case ((map, size), (id, other)) =>
            if size + other.entries.size > config.cacheMaxVersions then (map, size)
            else (map + (id -> other), size + other.entries.size)
          }
        kept._1
    )

  private def gate(tenant: TenantId): UIO[Semaphore] =
    gates.modifyZIO(all =>
      all.get(tenant) match
        case Some(semaphore) => ZIO.succeed((semaphore, all))
        case None            => Semaphore.make(1).map(s => (s, all + (tenant -> s)))
    )

object LibrarySnapshots:
  val layer: URLayer[Db & DocumentService & LibraryConfig, LibrarySnapshots] = ZLayer.fromZIO(
    for
      db        <- ZIO.service[Db]
      documents <- ZIO.service[DocumentService]
      config    <- ZIO.service[LibraryConfig]
      copies    <- Ref.make(Map.empty[TenantId, LibrarySnapshot])
      gates     <- Ref.Synchronized.make(Map.empty[TenantId, Semaphore])
    yield LibrarySnapshots(db, documents, config, copies, gates)
  )

  /** The entries of the valid versions, sorted by title (the library's default order). */
  private[app] def entries(tenant: TenantId, valid: List[CurrentVersion], zone: ZoneId): List[LibraryEntry] =
    val all       = valid.map(entry(tenant, _, zone))
    val byVersion = all.map(e => e.item.versionId -> e).toMap
    Library.sort(all.map(_.item), LibrarySort.Title, ascending = true, zone).map(i => byVersion(i.versionId))

  private def entry(tenant: TenantId, v: CurrentVersion, zone: ZoneId): LibraryEntry =
    val published = v.publishedAt.getOrElse(v.document.createdAt)
    val values    = v.metadata
    LibraryEntry(
      DocumentApi.versionResource(
        tenant,
        v.classPath,
        v.document.ownerId,
        v.status,
        v.createdBy,
        values,
        v.releasedBy
      ),
      LibraryItem(
        v.document.id.value,
        v.versionId,
        Library.title(values).getOrElse(v.document.title),
        v.document.number,
        v.label,
        v.status.toString,
        v.classPath,
        v.classPath.lastOption.getOrElse(""),
        DocumentResource.organizationalUnit(values),
        DocumentResource.confidentiality(values).toString,
        Library.tags(values),
        v.scope,
        Library.summary(values),
        v.validFrom,
        Validity.effectiveStart(v.validFrom, published, zone),
        published,
        isNew = false,
        changed = false,
        v.requiresAcknowledgement,
        acknowledgementPending = false
      ),
      v.firstPublishedAt.getOrElse(published)
    )
