package dms.app

import dms.audit.core.{AuditTrailLive, TsaTrust}
import dms.db.Db
import dms.db.queue.JobQueue
import dms.documents.core.{DocumentClassService, DocumentService}
import dms.identity.{TenantOrganizations, UserDirectories}
import dms.kernel.TenantId
import dms.rendering.core.DocumentConverter
import dms.search.core.{OpenSearch, SearchIndex}
import dms.storage.ObjectStore
import dms.templates.core.TemplateService
import zio.*
import zio.config.typesafe.TypesafeConfigProvider
import zio.logging.backend.SLF4J

import java.util.UUID

/** Rebuilds the search index from the database (DMS-51, ADR 0029): for the tenants given as arguments (tenant
  * ids), or for every tenant. Each tenant gets a new index; its alias is moved to it in one step.
  *
  * `sbt "app/runMain dms.app.SearchRebuild [tenant id ...]"` or `bin/search-rebuild` of the staged app.
  */
object SearchRebuild extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j >+>
      Runtime.setConfigProvider(TypesafeConfigProvider.fromResourcePath())

  override def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    for
      args   <- ZIOAppArgs.getArgs
      config <- ZIO.config(SearchRebuildConfig.config)
      ids    <- ZIO.foreach(args.toList)(a => ZIO.attempt(TenantId(UUID.fromString(a))))
      _      <- rebuild(ids).provide(
        Db.dataSource(config.database),
        Db.layer,
        ZLayer.succeed(config.search.openSearch),
        ZLayer.succeed(config.search.settings),
        OpenSearch.layer,
        ZLayer.succeed(TsaTrust(Nil)),
        AuditTrailLive.layer,
        DocumentClassService.layer,
        DocumentClassProfiles.layer,
        TemplateService.layer,
        DocumentTemplates.layer,
        DocumentService.layer,
        // The text of Word originals (DMS-121): read from storage and filled; no conversion needed.
        ZLayer.succeed(config.storage),
        ObjectStore.client,
        ObjectStore.live,
        RenditionWiring.archive,
        ZLayer.succeed(DocumentConverter.unavailable),
        FileVersions.layer,
        TenantOrganizations.layer,
        UserDirectories.layer(config.users, config.auth.issuer),
        SearchDocuments.withOriginals,
        SearchIndex.layer
      )
    yield ()

  private def rebuild(only: List[TenantId]): ZIO[Db & SearchIndex, Throwable, Unit] =
    for
      db      <- ZIO.service[Db]
      index   <- ZIO.service[SearchIndex]
      tenants <-
        if only.nonEmpty then ZIO.succeed(only)
        else JobQueue.tenantIds(db).mapError(e => RuntimeException(e.toString))
      _ <- ZIO.foreachDiscard(tenants)(tenant =>
        index
          .rebuild(tenant)
          .mapError(e => RuntimeException(s"tenant ${tenant.value}: ${e.describe}"))
          .flatMap(r =>
            Console.printLine(
              s"Tenant ${tenant.value}: ${r.documents} documents, ${r.versions} versions in ${r.index} (${r.tookMillis} ms)"
            )
          )
      )
    yield ()
