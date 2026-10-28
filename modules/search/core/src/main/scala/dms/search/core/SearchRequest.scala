package dms.search.core

import dms.documents.DocumentStatus
import dms.kernel.DocumentId
import dms.search.*
import zio.json.ast.Json

import java.time.LocalDate
import java.util.UUID
import scala.util.Try

/** The OpenSearch request of a search and the reading of its answer (ADR 0029).
  *
  * The permission filter and the class and language filters are part of the query, so the hit count and all
  * aggregations only see readable versions. The status filter is a `post_filter` (it narrows the hits) and is
  * repeated in the class and language aggregations, so the status facet counts the other statuses as well:
  * still only over readable versions.
  */
object SearchRequest:

  /** Largest page size. */
  val MaxSize = 100

  /** OpenSearch's default `index.max_result_window`. */
  val MaxWindow = 10000

  /** Length of a snippet without highlights. */
  val SnippetLength = 200

  private val TextFields =
    List("title^3", "title.de^3", "title.en^3", "text", "text.de", "text.en")

  private val SnippetFields = List("text.de", "text.en", "text")

  def validate(query: SearchQuery): Either[String, SearchQuery] =
    if query.page < 1 then Left("page must be at least 1")
    else if query.size < 1 || query.size > MaxSize then Left(s"size must be between 1 and $MaxSize")
    else if query.page.toLong * query.size > MaxWindow then
      Left(s"only the first $MaxWindow hits can be paged through; narrow the search")
    else
      query.statuses.find(s => !SearchDocument.isIndexed(s)) match
        case Some(s) =>
          Left(
            s"status $s is not searchable; one of ${SearchDocument.IndexedStatuses.toList.map(_.toString).sorted.mkString(", ")}"
          )
        case None => Right(query)

  def body(query: SearchQuery, access: Filter): Json =
    val statuses =
      Filter.anyOf(
        "status",
        (if query.statuses.isEmpty then SearchDocument.DefaultStatuses else query.statuses.toSet)
          .map(_.toString)
      )
    val filters = Filter.and(
      List(
        Filter.anyOf("status", SearchDocument.IndexedStatuses.map(_.toString)),
        access
      ) ++ Option.when(query.classes.nonEmpty)(Filter.anyOf("classPath", query.classes)).toList ++
        Option.when(query.languages.nonEmpty)(Filter.anyOf("language", query.languages)).toList
    )
    val text = query.text.map(_.trim).filter(_.nonEmpty)
    val must = text.fold(Json.Obj("match_all" -> Json.Obj()))(q =>
      Json.Obj(
        "simple_query_string" -> Json.Obj(
          "query"            -> Json.Str(q),
          "fields"           -> Json.Arr(TextFields.map(Json.Str(_))*),
          "default_operator" -> Json.Str("and"),
          "lenient"          -> Json.Bool(true)
        )
      )
    )
    def terms(field: String, size: Int) =
      Json.Obj("terms" -> Json.Obj("field" -> Json.Str(field), "size" -> Json.Num(size)))
    def filtered(field: String, size: Int) =
      Json.Obj("filter" -> statuses.toJson, "aggs" -> Json.Obj("values" -> terms(field, size)))
    val sort =
      if text.isDefined then Json.Arr(Json.Str("_score"), Json.Obj("title.keyword" -> Json.Str("asc")))
      else Json.Arr(Json.Obj("title.keyword" -> Json.Str("asc")), Json.Obj("versionId" -> Json.Str("asc")))
    Json.Obj(
      "from"             -> Json.Num((query.page - 1) * query.size),
      "size"             -> Json.Num(query.size),
      "track_total_hits" -> Json.Bool(true),
      "_source"          -> Json.Obj("excludes" -> Json.Arr(Json.Str("text"), Json.Str("metadata"))),
      "query"            -> Json.Obj("bool" -> Json.Obj("must" -> must, "filter" -> filters.toJson)),
      "post_filter"      -> statuses.toJson,
      "sort"             -> sort,
      "aggs"             -> Json.Obj(
        "class"    -> filtered("classPath", 200),
        "language" -> filtered("language", 50),
        "status"   -> terms("status", 20)
      ),
      "highlight" -> Json.Obj(
        "pre_tags"            -> Json.Arr(Json.Str(IndexLayout.HighlightStart)),
        "post_tags"           -> Json.Arr(Json.Str(IndexLayout.HighlightEnd)),
        "fragment_size"       -> Json.Num(SnippetLength),
        "number_of_fragments" -> Json.Num(1),
        "fields"              -> Json.Obj(
          SnippetFields.map(f =>
            f -> Json.Obj("no_match_size" -> Json.Num(if f == "text" then SnippetLength else 0))
          )*
        )
      )
    )

  /** Reads the answer of [[body]]. */
  def result(query: SearchQuery, answer: Json): SearchResult =
    val hitsObj = field(answer, "hits")
    val total   = hitsObj.flatMap(field(_, "total")).flatMap(field(_, "value")).flatMap(long).getOrElse(0L)
    val hits    = hitsObj.flatMap(field(_, "hits")).toList.flatMap(array).flatMap(hit)
    val aggs    = field(answer, "aggregations")
    def buckets(name: String, nested: Boolean): List[FacetCount] =
      aggs
        .flatMap(field(_, name))
        .flatMap(a => if nested then field(a, "values") else Some(a))
        .flatMap(field(_, "buckets"))
        .toList
        .flatMap(array)
        .flatMap(b =>
          for value <- field(b, "key").flatMap(string); count <- field(b, "doc_count").flatMap(long)
          yield FacetCount(value, count)
        )
    SearchResult(
      total,
      query.page,
      query.size,
      hits,
      Facets(
        buckets("class", nested = true),
        buckets("status", nested = false),
        buckets("language", nested = true)
      )
    )

  private def hit(json: Json): Option[SearchHit] =
    for
      source <- field(json, "_source")
      doc    <- field(source, "documentId").flatMap(string).flatMap(s => Try(UUID.fromString(s)).toOption)
      ver    <- field(source, "versionId").flatMap(string).flatMap(s => Try(UUID.fromString(s)).toOption)
      status <- field(source, "status").flatMap(string).flatMap(s => Try(DocumentStatus.valueOf(s)).toOption)
    yield
      val highlights = field(json, "highlight")
      val fragments  =
        SnippetFields.flatMap(f => highlights.flatMap(field(_, f)).toList.flatMap(array).flatMap(string))
      // The fragment with the most highlights; the start of the text if nothing matched.
      val snippet = fragments
        .sortBy(f => -f.count(_ == IndexLayout.HighlightStart.head))
        .headOption
        .fold("")(IndexLayout.snippet)
      SearchHit(
        DocumentId(doc),
        ver,
        str(source, "title"),
        str(source, "versionLabel"),
        field(source, "classPath").toList.flatMap(array).flatMap(string),
        status,
        field(source, "validFrom").flatMap(string).flatMap(s => Try(LocalDate.parse(s)).toOption),
        field(source, "validUntil").flatMap(string).flatMap(s => Try(LocalDate.parse(s)).toOption),
        field(source, "language").flatMap(string),
        field(source, "organizationalUnit").flatMap(string),
        snippet,
        field(json, "_score").flatMap(double)
      )

  private def field(json: Json, name: String): Option[Json] = json match
    case Json.Obj(fields) => fields.collectFirst { case (`name`, v) if v != Json.Null => v }
    case _                => None
  private def array(json: Json): List[Json] = json match
    case Json.Arr(values) => values.toList
    case _                => Nil
  private def string(json: Json): Option[String] = json match
    case Json.Str(s) => Some(s)
    case _           => None
  private def long(json: Json): Option[Long] = json match
    case Json.Num(n) => Some(n.longValue)
    case _           => None
  private def double(json: Json): Option[Double] = json match
    case Json.Num(n) => Some(n.doubleValue)
    case _           => None
  private def str(json: Json, name: String): String = field(json, name).flatMap(string).getOrElse("")
