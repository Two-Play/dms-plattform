package dms.search.core

import dms.documents.metadata.MetadataValue
import dms.kernel.TenantId
import dms.search.SearchDocument
import zio.json.*
import zio.json.ast.Json

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{Instant, ZoneOffset}

/** Names, settings, mapping and entries of the search index (ADR 0029).
  *
  * One index per tenant behind the alias `<prefix>-<tenant id>`; every entry also carries its tenant, and the
  * permission filter requires it. An entry is one document version with id = version id.
  */
object IndexLayout:

  def alias(prefix: String, tenant: TenantId): String = s"$prefix-${tenant.value}"

  /** The index created when a tenant's alias does not exist yet; a fixed name, so that instances creating it
    * at the same time agree.
    */
  def initialIndex(alias: String): String = s"$alias-initial"

  private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)

  /** The index a rebuild fills before the alias is moved to it. */
  def rebuildIndex(alias: String, at: Instant): String = s"$alias-${stamp.format(at)}"

  /** Characters that mark highlights in answers of OpenSearch; removed from indexed text, so that only real
    * matches become highlights.
    */
  val HighlightStart = ""
  val HighlightEnd   = ""

  /** Settings and mapping. `title` and `text` are analyzed three times: standard (exact words), German and
    * English (stemming); queries search all of them. Field values are indexed typed under
    * `metadata.<kind>.<field key>`; access fields are keywords.
    */
  def definition(replicas: Int): Json =
    s"""{
       |  "settings": {
       |    "index": { "number_of_shards": 1, "number_of_replicas": $replicas }
       |  },
       |  "mappings": {
       |    "dynamic": "strict",
       |    "properties": {
       |      "tenantId": { "type": "keyword" },
       |      "documentId": { "type": "keyword" },
       |      "versionId": { "type": "keyword" },
       |      "versionLabel": { "type": "keyword" },
       |      "title": {
       |        "type": "text",
       |        "fields": {
       |          "de": { "type": "text", "analyzer": "german" },
       |          "en": { "type": "text", "analyzer": "english" },
       |          "keyword": { "type": "keyword", "ignore_above": 512 }
       |        }
       |      },
       |      "text": {
       |        "type": "text",
       |        "fields": {
       |          "de": { "type": "text", "analyzer": "german" },
       |          "en": { "type": "text", "analyzer": "english" }
       |        }
       |      },
       |      "classKey": { "type": "keyword" },
       |      "classPath": { "type": "keyword" },
       |      "status": { "type": "keyword" },
       |      "validFrom": { "type": "date", "format": "strict_date" },
       |      "validUntil": { "type": "date", "format": "strict_date" },
       |      "publishedAt": { "type": "date" },
       |      "scope": { "type": "keyword" },
       |      "language": { "type": "keyword" },
       |      "organizationalUnit": { "type": "keyword" },
       |      "confidentiality": { "type": "keyword" },
       |      "authorId": { "type": "keyword" },
       |      "ownerId": { "type": "keyword" },
       |      "allowedGroups": { "type": "keyword" },
       |      "metadata": {
       |        "type": "object",
       |        "dynamic": true,
       |        "properties": {
       |          "keyword": { "type": "object", "dynamic": true },
       |          "number": { "type": "object", "dynamic": true },
       |          "amount": { "type": "object", "dynamic": true },
       |          "date": { "type": "object", "dynamic": true },
       |          "flag": { "type": "object", "dynamic": true }
       |        }
       |      }
       |    },
       |    "dynamic_templates": [
       |      { "keywords": { "path_match": "metadata.keyword.*", "mapping": { "type": "keyword", "ignore_above": 1024 } } },
       |      { "numbers": { "path_match": "metadata.number.*", "mapping": { "type": "double" } } },
       |      { "amountValues": { "path_match": "metadata.amount.*.value", "mapping": { "type": "double" } } },
       |      { "amountCurrencies": { "path_match": "metadata.amount.*.currency", "mapping": { "type": "keyword" } } },
       |      { "dates": { "path_match": "metadata.date.*", "mapping": { "type": "date", "format": "strict_date" } } },
       |      { "flags": { "path_match": "metadata.flag.*", "mapping": { "type": "boolean" } } }
       |    ]
       |  }
       |}""".stripMargin
      .fromJson[Json]
      .fold(e => throw IllegalStateException(s"index definition: $e"), identity)

  /** The source of an index entry. */
  def entry(doc: SearchDocument): Json =
    def str(s: String)                     = Json.Str(s)
    def opt[A](a: Option[A])(f: A => Json) = a.fold[Json](Json.Null)(f)
    def arr(values: Iterable[String])      = Json.Arr(values.toList.map(str)*)
    Json.Obj(
      "tenantId"           -> str(doc.tenantId.value.toString),
      "documentId"         -> str(doc.documentId.value.toString),
      "versionId"          -> str(doc.versionId.toString),
      "versionLabel"       -> str(doc.versionLabel),
      "title"              -> str(clean(doc.title)),
      "text"               -> str(clean(doc.text)),
      "classKey"           -> str(doc.classKey),
      "classPath"          -> arr(doc.classPath),
      "status"             -> str(doc.status.toString),
      "validFrom"          -> opt(doc.validFrom)(d => str(d.toString)),
      "validUntil"         -> opt(doc.validUntil)(d => str(d.toString)),
      "publishedAt"        -> opt(doc.publishedAt)(i => str(i.toString)),
      "scope"              -> arr(doc.scope),
      "language"           -> opt(doc.language)(str),
      "organizationalUnit" -> opt(doc.access.organizationalUnit)(str),
      "confidentiality"    -> str(doc.access.confidentiality),
      "authorId"           -> str(doc.access.authorId.value),
      "ownerId"            -> opt(doc.access.ownerId)(u => str(u.value)),
      "allowedGroups"      -> arr(doc.access.allowedGroups.toList.sorted),
      "metadata"           -> metadata(doc.metadata)
    )

  /** The external version of an entry: the latest change in microseconds, so an older state never replaces a
    * newer one.
    */
  def version(doc: SearchDocument): Long =
    ChronoUnit.MICROS.between(Instant.EPOCH, doc.changedAt)

  /** Field values grouped by kind, each field as a list of values. */
  def metadata(values: Map[String, MetadataValue]): Json =
    def flatten(v: MetadataValue): List[MetadataValue] = v match
      case MetadataValue.Many(vs) => vs.flatMap(flatten)
      case other                  => List(other)
    def kind(v: MetadataValue): Option[(String, Json)] = v match
      case MetadataValue.Text(t)             => Some("keyword" -> Json.Str(clean(t)))
      case MetadataValue.Selection(c)        => Some("keyword" -> Json.Str(c))
      case MetadataValue.PersonRef(u)        => Some("keyword" -> Json.Str(u))
      case MetadataValue.DocumentRef(d)      => Some("keyword" -> Json.Str(d.toString))
      case MetadataValue.Number(n)           => Some("number" -> Json.Num(n.bigDecimal))
      case MetadataValue.Amount(n, currency) =>
        Some("amount" -> Json.Obj("value" -> Json.Num(n.bigDecimal), "currency" -> Json.Str(currency)))
      case MetadataValue.Date(d)  => Some("date" -> Json.Str(d.toString))
      case MetadataValue.YesNo(b) => Some("flag" -> Json.Bool(b))
      case MetadataValue.Many(_)  => None
    val byKind = values.toList
      .sortBy(_._1)
      .flatMap((key, value) => flatten(value).flatMap(kind).map { case (k, json) => (k, key, json) })
      .groupBy(_._1)
    Json.Obj(byKind.toList.sortBy(_._1).map { (k, entries) =>
      k -> Json.Obj(
        entries.groupBy(_._2).toList.sortBy(_._1).map((key, vs) => key -> Json.Arr(vs.map(_._3)*))*
      )
    }*)

  /** Indexed text without highlight characters and without literal `<mark>` markers, so that a snippet's
    * markers always enclose real matches.
    */
  private def clean(text: String): String =
    text
      .replace(HighlightStart, "")
      .replace(HighlightEnd, "")
      .replace(MarkStart, "")
      .replace(MarkEnd, "")

  val MarkStart = "<mark>"
  val MarkEnd   = "</mark>"

  /** A highlighted fragment as plain text with the matches enclosed in `<mark>` and `</mark>`. Nothing else
    * is escaped: clients split at the markers and render the parts as text, never as HTML.
    */
  def snippet(fragment: String): String =
    fragment.replace(HighlightStart, MarkStart).replace(HighlightEnd, MarkEnd)
