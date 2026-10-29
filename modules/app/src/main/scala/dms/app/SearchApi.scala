package dms.app

import dms.archive.core.LegalHoldStore
import dms.authz.{AccessDenied, Action, Authorizer, ConfigurationArea, Resource}
import dms.db.{Db, DbError}
import dms.documents.DocumentStatus
import dms.identity.{Authenticator, Principal}
import dms.kernel.DocumentId
import dms.search.*
import dms.search.core.SearchIndex
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.time.LocalDate
import java.util.UUID
import scala.util.Try

/** A hit of the search: one version of a document. */
final case class SearchHitView(
    documentId: UUID,
    versionId: UUID,
    title: String,
    /** Version number, e.g. `2.0`. */
    versionLabel: String,
    classPath: List[String],
    /** DocumentStatus: Published, ReviewDue or Superseded. */
    status: String,
    validFrom: Option[LocalDate],
    /** Plain text around the matches; matches are enclosed in `<mark>` and `</mark>`. Not HTML: nothing is
      * escaped; split at the markers and render the parts as text.
      */
    snippet: String,
    validUntil: Option[LocalDate],
    language: Option[String],
    organizationalUnit: Option[String],
    /** Relevance; absent for a listing without query text. */
    score: Option[Double],
    /** The document number (DMS-113), if the version has one. */
    documentNumber: Option[String] = None,
    /** True while an active legal hold covers the document (DMS-106); which holds is not shown here. */
    legalHold: Boolean = false
) derives JsonCodec,
      Schema

final case class FacetCountView(value: String, count: Long) derives JsonCodec, Schema

/** Counts over the versions the caller may read. `class` counts a version for its class and every ancestor;
  * `status` ignores the status filter, so the other statuses can be offered.
  */
final case class SearchFacetsView(
    `class`: List[FacetCountView],
    status: List[FacetCountView],
    language: List[FacetCountView]
) derives JsonCodec,
      Schema

final case class SearchResultView(
    /** Number of hits (all pages). */
    total: Long,
    page: Int,
    size: Int,
    hits: List[SearchHitView],
    facets: SearchFacetsView
) derives JsonCodec,
      Schema

final case class SearchRebuildView(index: String, documents: Int, versions: Int, tookMillis: Long)
    derives JsonCodec,
      Schema

/** Full-text search over published documents (DMS-51, DMS-52, ADR 0029).
  *
  * Every signed-in user may search; the results only contain versions the caller may read
  * (`ReadPublished`/`ReadDocument`), enforced by a filter inside the OpenSearch query that is built from the
  * caller's role assignments at the time of the request. Rebuilding the index requires `Configure` on the
  * configuration area `Search` (tenant administrators).
  */
object SearchApi:
  type Env = Authenticator & Db & Authorizer & SearchIndex

  private def list(name: String, description: String) =
    query[List[String]](name).description(description)

  val search: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in("api" / "v1" / "search")
      .in(
        query[Option[String]]("q").description(
          "Query text: words, \"phrases\", prefix*, -excluded. Empty: list by filter."
        )
      )
      .in(list("class", "Class keys; a class includes its subclasses. Repeat or separate with commas."))
      .in(
        list(
          "status",
          "Published, ReviewDue, Superseded. Default: the valid versions (Published, ReviewDue)."
        )
      )
      .in(list("lang", "Languages of the documents, e.g. de, en"))
      .in(query[Option[Int]]("page").description("1-based page, default 1"))
      .in(
        query[Option[Int]]("size").description(
          s"Hits per page, 1 to ${dms.search.core.SearchRequest.MaxSize}, default 20"
        )
      )
      .out(jsonBody[SearchResultView])
      .description(
        "Searches the published documents the caller may read: full text (German and English stemming) over title, " +
          "content and field values, filtered by class, status and language, with facet counts and snippets. " +
          "Hits, total and facet counts only cover versions the caller may read."
      )
      .tag("Search")
      .serverLogic(p =>
        (q, classes, statuses, languages, page, size) =>
          for
            parsed <- ZIO
              .foreach(values(statuses))(s =>
                ZIO
                  .fromOption(Try(DocumentStatus.valueOf(s)).toOption)
                  .orElseFail((StatusCode.BadRequest, ApiError("invalid", s"unknown status '$s'")))
              )
            subject <- subjectOf(p)
            index   <- ZIO.service[SearchIndex]
            result  <- index
              .search(
                subject,
                SearchQuery(
                  q,
                  values(classes),
                  parsed,
                  values(languages),
                  page.getOrElse(1),
                  size.getOrElse(20)
                )
              )
              .tapError(logFailure)
              .mapError(toResponse)
            held <- ZIO
              .serviceWithZIO[Db](
                _.transact(p.tenantId)(LegalHoldStore.heldAmong(result.hits.map(_.documentId).toSet))
              )
              .mapError(toResponse)
          yield view(result, held)
      )

  val rebuild: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in("api" / "v1" / "admin" / "search" / "rebuild")
      .out(jsonBody[SearchRebuildView])
      .description(
        "Rebuilds the caller's tenant's search index from the database: fills a new index and switches to it in " +
          "one step; searches keep working meanwhile. Requires the tenant administrator role."
      )
      .tag("Search")
      .serverLogic(p =>
        _ =>
          for
            db    <- ZIO.service[Db]
            authz <- ZIO.service[Authorizer]
            index <- ZIO.service[SearchIndex]
            _     <- db
              .transact(p.tenantId)(
                authz
                  .subject(p)
                  .flatMap(
                    authz.check(
                      _,
                      Action.Configure,
                      Resource(p.tenantId, configurationArea = Some(ConfigurationArea.Search))
                    )
                  )
              )
              .mapError(toResponse)
            result <- index.rebuild(p.tenantId).tapError(logFailure).mapError(toResponse)
          yield SearchRebuildView(result.index, result.documents, result.versions, result.tookMillis)
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] = List(search, rebuild)

  /** Repeated parameters and comma-separated values. */
  private def values(params: List[String]): List[String] =
    params.flatMap(_.split(',')).map(_.trim).filter(_.nonEmpty).distinct

  private def subjectOf(p: Principal) =
    for
      db      <- ZIO.service[Db]
      authz   <- ZIO.service[Authorizer]
      subject <- db.transact(p.tenantId)(authz.subject(p)).mapError(toResponse)
    yield subject

  private def view(r: SearchResult, held: Set[DocumentId]): SearchResultView =
    def counts(fs: List[FacetCount]) = fs.map(f => FacetCountView(f.value, f.count))
    SearchResultView(
      r.total,
      r.page,
      r.size,
      r.hits.map(h =>
        SearchHitView(
          h.documentId.value,
          h.versionId,
          h.title,
          h.versionLabel,
          h.classPath,
          h.status.toString,
          h.validFrom,
          h.snippet,
          h.validUntil,
          h.language,
          h.organizationalUnit,
          h.score.filterNot(_.isNaN),
          h.documentNumber,
          held.contains(h.documentId)
        )
      ),
      SearchFacetsView(counts(r.facets.classes), counts(r.facets.statuses), counts(r.facets.languages))
    )

  private def logFailure(error: SearchError): UIO[Unit] = error match
    case SearchError.Invalid(_) => ZIO.unit
    case e                      => ZIO.logWarning(s"Search failed: ${e.describe}")

  /** Details of index and database failures are logged, not returned. */
  def toResponse(error: SearchError | AccessDenied | DbError): (StatusCode, ApiError) = error match
    case SearchError.Invalid(message) => (StatusCode.BadRequest, ApiError("invalid", message))
    case SearchError.Unavailable(_)   =>
      (
        StatusCode.ServiceUnavailable,
        ApiError("search_unavailable", "The search is not available right now.")
      )
    case SearchError.Source(_) =>
      (StatusCode.InternalServerError, ApiError("search_error", "The search index could not be built."))
    case e: AccessDenied => DocumentApi.toResponse(e)
    case e: DbError      => DocumentApi.toResponse(e)
