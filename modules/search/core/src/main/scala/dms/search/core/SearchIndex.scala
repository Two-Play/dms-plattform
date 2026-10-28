package dms.search.core

import com.augustnagro.magnum.*
import dms.authz.Subject
import dms.db.{Db, DbError}
import dms.kernel.{DocumentId, TenantId}
import dms.search.*
import zio.*
import zio.json.ast.Json

import java.time.{Instant, OffsetDateTime, ZoneOffset}

/** @param syncInterval
  *   how often every tenant's index is synchronized with the documents changed since the last run
  * @param syncOverlap
  *   how far a synchronization looks back before the previous run, for changes committed late
  * @param batchSize
  *   documents loaded and written per batch
  */
final case class SearchSettings(
    syncInterval: Duration = 30.seconds,
    syncOverlap: Duration = 2.minutes,
    batchSize: Int = 100
)

/** The search index of all tenants in OpenSearch (DMS-51, DMS-52, ADR 0029), derived from the database.
  *
  * Writing: [[indexDocuments]] loads the indexable versions of documents from the [[SearchSource]] (in a
  * transaction of the tenant), writes them with an external version (the time of their last change, so an
  * older state never replaces a newer one) and removes the document's other entries. [[sync]] does so for
  * every document changed since the previous run, [[rebuild]] fills a new index from the database and moves
  * the tenant's alias to it.
  *
  * Reading: [[search]] filters by [[AccessFilter]] inside the query. OpenSearch is never called inside a
  * database transaction.
  */
final class SearchIndex(
    db: Db,
    client: OpenSearch,
    source: SearchSource,
    config: OpenSearchConfig,
    settings: SearchSettings
):

  def alias(tenant: TenantId): String = IndexLayout.alias(config.indexPrefix, tenant)

  /** The caller's search. `subject` must have been built in a transaction of its tenant just before. */
  def search(subject: Subject, query: SearchQuery): IO[SearchError, SearchResult] =
    for
      valid  <- ZIO.fromEither(SearchRequest.validate(query)).mapError(SearchError.Invalid(_))
      now    <- Clock.instant
      answer <- client.search(
        alias(subject.tenantId),
        SearchRequest.body(valid, AccessFilter.forSubject(subject, now))
      )
    yield SearchRequest.result(valid, answer)

  /** Creates the tenant's index and alias if the alias does not exist yet; returns the alias. */
  def ensureIndex(tenant: TenantId): IO[SearchError, String] =
    val name = alias(tenant)
    client.aliasIndices(name).flatMap {
      case Nil =>
        val index = IndexLayout.initialIndex(name)
        val body  = IndexLayout.definition(config.replicas) match
          case Json.Obj(fields) => Json.Obj(fields :+ ("aliases" -> Json.Obj(name -> Json.Obj())))
          case other            => other
        client.createIndex(index, body).as(name)
      case _ => ZIO.succeed(name)
    }

  /** Reindexes documents: writes their indexable versions and removes their other entries. Returns the number
    * of written versions.
    */
  def indexDocuments(tenant: TenantId, ids: List[DocumentId]): IO[SearchError, Int] =
    ZIO.when(ids.nonEmpty)(ensureIndex(tenant)).someOrElse(alias(tenant)).flatMap { name =>
      ZIO
        .foreach(ids.grouped(settings.batchSize.max(1)).toList) { batch =>
          for
            docs <- load(tenant, batch)
            _    <- client.bulk(name, lines(docs), refresh = true)
            _    <- client.deleteByQuery(name, stale(batch, docs))
          yield docs.size
        }
        .map(_.sum)
    }

  def indexDocument(tenant: TenantId, id: DocumentId): IO[SearchError, Int] = indexDocuments(tenant, List(id))

  /** Reindexes every document changed since the previous synchronization of the tenant (with an overlap),
    * every document if there was none. Returns the number of documents.
    */
  def sync(tenant: TenantId): IO[SearchError, Int] =
    for
      state <- transact(tenant)(Db.sql((SearchIndex.syncedUntil, SearchIndex.databaseNow)))
      (since, now) = state
      ids <- transact(tenant)(source.changedDocuments(since.map(_.minus(settings.syncOverlap))))
      _   <- indexDocuments(tenant, ids)
      _   <- transact(tenant)(Db.sql(SearchIndex.markSynced(now)))
    yield ids.size

  /** Builds a new index of the tenant from the database and moves the alias to it in one step; the previous
    * index is deleted afterwards. Searches use the previous index until the move. Changes made during the
    * rebuild are written to the previous index and caught up by the next [[sync]], which starts from the
    * beginning of the rebuild.
    */
  def rebuild(tenant: TenantId): IO[SearchError, RebuildResult] =
    for
      started <- transact(tenant)(Db.sql(SearchIndex.databaseNow))
      clock   <- Clock.instant
      name  = alias(tenant)
      index = IndexLayout.rebuildIndex(name, clock)
      _      <- client.createIndex(index, IndexLayout.definition(config.replicas))
      result <- fill(tenant, index).onError(_ => client.deleteIndex(index).ignore)
      (documents, versions) = result
      _        <- client.refresh(index)
      previous <- client.aliasIndices(name)
      _        <- client.moveAlias(name, index, previous)
      _        <- ZIO.foreachDiscard(previous.filterNot(_ == index))(client.deleteIndex)
      _        <- transact(tenant)(Db.sql(SearchIndex.markSynced(started)))
      done     <- Clock.instant
      _        <- ZIO.logInfo(
        s"Search index of tenant ${tenant.value} rebuilt: $documents documents, $versions versions in $index"
      )
    yield RebuildResult(index, documents, versions, done.toEpochMilli - clock.toEpochMilli)

  private def fill(tenant: TenantId, index: String): IO[SearchError, (Int, Int)] =
    for
      ids    <- transact(tenant)(source.changedDocuments(None))
      counts <- ZIO.foreach(ids.grouped(settings.batchSize.max(1)).toList)(batch =>
        load(tenant, batch).flatMap(docs =>
          client
            .bulk(index, lines(docs), refresh = false)
            .as((docs.map(_.documentId).distinct.size, docs.size))
        )
      )
    yield (counts.map(_._1).sum, counts.map(_._2).sum)

  private def load(tenant: TenantId, ids: List[DocumentId]): IO[SearchError, List[SearchDocument]] =
    transact(tenant)(ZIO.foreach(ids)(source.load(tenant, _)))
      .map(_.flatten.filter(d => SearchDocument.isIndexed(d.status)))

  private def lines(docs: List[SearchDocument]): List[Json] =
    docs.flatMap(doc =>
      List(
        Json.Obj(
          "index" -> Json.Obj(
            "_id"          -> Json.Str(doc.versionId.toString),
            "version"      -> Json.Num(IndexLayout.version(doc)),
            "version_type" -> Json.Str("external_gte")
          )
        ),
        IndexLayout.entry(doc)
      )
    )

  /** Entries of the documents that are not among the written versions. */
  private def stale(ids: List[DocumentId], written: List[SearchDocument]): Json =
    val ofDocuments = Filter.anyOf("documentId", ids.map(_.value.toString)).toJson
    if written.isEmpty then ofDocuments
    else
      Json.Obj(
        "bool" -> Json.Obj(
          "filter"   -> ofDocuments,
          "must_not" -> Filter.anyOf("versionId", written.map(_.versionId.toString)).toJson
        )
      )

  private def transact[A](tenant: TenantId)(body: IO[SearchError | DbError, A]): IO[SearchError, A] =
    db.transact(tenant)(body).mapError {
      case e: SearchError => e
      case e: DbError     => SearchError.Source(e.toString)
    }

object SearchIndex:

  val layer: URLayer[Db & OpenSearch & SearchSource & OpenSearchConfig & SearchSettings, SearchIndex] =
    ZLayer.fromFunction(SearchIndex(_, _, _, _, _))

  private def syncedUntil(using DbTx): Option[Instant] =
    sql"select synced_until from dms.search_sync_state"
      .query[OffsetDateTime]
      .run()
      .headOption
      .map(_.toInstant)

  private def databaseNow(using DbTx): Instant =
    sql"select now()".query[OffsetDateTime].run().head.toInstant

  private def markSynced(at: Instant)(using DbTx): Unit =
    val time = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
    val _    = sql"""insert into dms.search_sync_state (tenant_id, synced_until)
                     values (dms.current_tenant(), $time)
                     on conflict (tenant_id) do update set synced_until = excluded.synced_until, updated_at = now()""".update
      .run()
