package dms.search.core

import dms.search.SearchError
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration as JDuration

/** Connection and index settings of OpenSearch (ADR 0029).
  *
  * @param url
  *   base URL, e.g. `http://localhost:9200`
  * @param indexPrefix
  *   prefix of index and alias names; the alias of a tenant is `<prefix>-<tenant id>`
  * @param replicas
  *   replicas per index; 0 for a single node
  * @param username
  *   basic authentication, if the cluster has the security plugin enabled
  */
final case class OpenSearchConfig(
    url: String,
    indexPrefix: String = "dms-documents",
    replicas: Int = 0,
    timeoutSeconds: Int = 10,
    username: Option[String] = None,
    password: Option[String] = None
)

/** A minimal OpenSearch REST client on the JDK HTTP client: the handful of index, alias, bulk and search
  * calls the index needs. Calls never run inside a database transaction.
  */
final class OpenSearch(config: OpenSearchConfig, http: HttpClient):

  private val base                          = config.url.stripSuffix("/")
  private val timeout                       = JDuration.ofSeconds(config.timeoutSeconds.toLong)
  private val authorization: Option[String] =
    for
      user     <- config.username
      password <- config.password
    yield "Basic " + java.util.Base64.getEncoder.encodeToString(
      s"$user:$password".getBytes(StandardCharsets.UTF_8)
    )

  /** Sends a request; returns status and body. Only transport failures fail. */
  def send(
      method: String,
      path: String,
      body: Option[String] = None,
      contentType: String = "application/json"
  ): IO[SearchError, (Int, String)] =
    ZIO
      .attemptBlocking {
        val builder = HttpRequest
          .newBuilder(URI.create(s"$base$path"))
          .timeout(timeout)
          .header("Accept", "application/json")
          .method(
            method,
            body.fold(HttpRequest.BodyPublishers.noBody())(b =>
              HttpRequest.BodyPublishers.ofString(b, StandardCharsets.UTF_8)
            )
          )
        body.foreach(_ => builder.header("Content-Type", contentType))
        authorization.foreach(builder.header("Authorization", _))
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        (response.statusCode, response.body)
      }
      .mapError(e => SearchError.Unavailable(s"$method $path: ${Option(e.getMessage).getOrElse(e.toString)}"))

  /** Sends a request and parses the JSON answer; a status of 300 or more fails unless it is `accepted`. */
  def json(
      method: String,
      path: String,
      body: Option[Json] = None,
      accepted: Set[Int] = Set.empty
  ): IO[SearchError, (Int, Json)] =
    send(method, path, body.map(_.toJson)).flatMap { (status, text) =>
      if status >= 300 && !accepted.contains(status) then
        ZIO.fail(SearchError.Unavailable(s"$method $path answered $status: ${text.take(500)}"))
      else
        ZIO
          .fromEither(if text.isBlank then Right(Json.Obj()) else text.fromJson[Json])
          .mapBoth(e => SearchError.Unavailable(s"$method $path: invalid JSON ($e)"), status -> _)
    }

  /** The indices the alias points to; empty if the alias does not exist. */
  def aliasIndices(alias: String): IO[SearchError, List[String]] =
    json("GET", s"/_alias/$alias", accepted = Set(404)).map {
      case (200, Json.Obj(fields)) => fields.map(_._1).toList.sorted
      case _                       => Nil
    }

  /** Creates an index; returns false if it exists already. */
  def createIndex(name: String, body: Json): IO[SearchError, Boolean] =
    json("PUT", s"/$name", Some(body), accepted = Set(400)).flatMap {
      case (400, answer) if answer.toJson.contains("resource_already_exists_exception") => ZIO.succeed(false)
      case (400, answer)                                                                =>
        ZIO.fail(SearchError.Unavailable(s"index $name could not be created: ${answer.toJson.take(500)}"))
      case _ => ZIO.succeed(true)
    }

  def deleteIndex(name: String): IO[SearchError, Unit] =
    json("DELETE", s"/$name", accepted = Set(404)).unit

  /** Points `alias` to `index` only, in one atomic step. */
  def moveAlias(alias: String, index: String, from: List[String]): IO[SearchError, Unit] =
    val actions =
      from
        .filterNot(_ == index)
        .map(old => Json.Obj("remove" -> Json.Obj("index" -> Json.Str(old), "alias" -> Json.Str(alias)))) :+
        Json.Obj("add" -> Json.Obj("index" -> Json.Str(index), "alias" -> Json.Str(alias)))
    json("POST", "/_aliases", Some(Json.Obj("actions" -> Json.Arr(actions*)))).unit

  def refresh(index: String): IO[SearchError, Unit] = json("POST", s"/$index/_refresh").unit

  /** Runs a bulk request (`lines` are the NDJSON lines). Items failing with `ignoredStatus` (e.g. 409 for an
    * outdated external version) are ignored; any other item failure fails.
    */
  def bulk(
      index: String,
      lines: List[Json],
      refresh: Boolean,
      ignoredStatus: Set[Int] = Set(409)
  ): IO[SearchError, Unit] =
    if lines.isEmpty then ZIO.unit
    else
      val body = lines.map(_.toJson).mkString("", "\n", "\n")
      send(
        "POST",
        s"/$index/_bulk${if refresh then "?refresh=wait_for" else ""}",
        Some(body),
        "application/x-ndjson"
      )
        .flatMap { (status, text) =>
          if status >= 300 then ZIO.fail(SearchError.Unavailable(s"bulk answered $status: ${text.take(500)}"))
          else
            ZIO
              .fromEither(text.fromJson[Json])
              .mapError(e => SearchError.Unavailable(s"bulk: invalid JSON ($e)"))
              .flatMap(answer =>
                ZIO.foreachDiscard(OpenSearch.bulkFailure(answer, ignoredStatus))(e =>
                  ZIO.fail(SearchError.Unavailable(e))
                )
              )
        }

  def deleteByQuery(index: String, query: Json): IO[SearchError, Unit] =
    json(
      "POST",
      s"/$index/_delete_by_query?refresh=true&conflicts=proceed",
      Some(Json.Obj("query" -> query)),
      accepted = Set(404)
    ).unit

  def search(index: String, body: Json): IO[SearchError, Json] =
    json("POST", s"/$index/_search", Some(body), accepted = Set(404)).map {
      case (404, _)    => Json.Obj()
      case (_, answer) => answer
    }

object OpenSearch:
  def apply(config: OpenSearchConfig): OpenSearch =
    new OpenSearch(
      config,
      HttpClient
        .newBuilder()
        .connectTimeout(JDuration.ofSeconds(config.timeoutSeconds.toLong))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    )

  val layer: URLayer[OpenSearchConfig, OpenSearch] = ZLayer.fromFunction(OpenSearch(_))

  /** The first failed item of a bulk answer that is not ignored, as text. */
  private[core] def bulkFailure(answer: Json, ignoredStatus: Set[Int]): Option[String] =
    val items = answer match
      case Json.Obj(fields) =>
        fields.collectFirst { case ("items", Json.Arr(items)) => items.toList }.getOrElse(Nil)
      case _ => Nil
    items.view.flatMap {
      case Json.Obj(actions) =>
        actions.collect { case (action, Json.Obj(result)) =>
          val status = result.collectFirst { case ("status", Json.Num(n)) => n.intValue }.getOrElse(0)
          val error  = result.collectFirst { case ("error", e) => e.toJson }
          Option.when(status >= 300 && !ignoredStatus.contains(status))(
            s"bulk $action failed with $status: ${error.getOrElse("").take(300)}"
          )
        }.flatten
      case _ => Nil
    }.headOption
