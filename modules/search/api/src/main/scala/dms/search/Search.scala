package dms.search

import dms.documents.DocumentStatus
import dms.documents.metadata.MetadataValue
import dms.kernel.{DocumentId, TenantId, UserId}
import zio.IO

import java.time.{Instant, LocalDate}
import java.util.UUID

/** The attributes the permission filter of the search decides on (ADR 0029): the same attributes the
  * authorization policy reads from a document version (ADR 0018).
  *
  * @param confidentiality
  *   the confidentiality level, e.g. `Internal`
  * @param allowedGroups
  *   groups (ZITADEL project roles) allowed to see a confidential or strictly confidential version
  */
final case class AccessAttributes(
    organizationalUnit: Option[String],
    confidentiality: String,
    authorId: UserId,
    ownerId: Option[UserId],
    allowedGroups: Set[String]
)

/** One version of a document as it is indexed: one index entry per version (ADR 0029).
  *
  * @param classPath
  *   class keys from the root to the document's class
  * @param text
  *   the resolved content as plain text
  * @param metadata
  *   the field values, indexed typed by their kind (ADR 0019)
  * @param changedAt
  *   the latest change of the document or the version in the database; orders concurrent writes of the same
  *   version in the index
  */
final case class SearchDocument(
    tenantId: TenantId,
    documentId: DocumentId,
    versionId: UUID,
    versionLabel: String,
    title: String,
    text: String,
    classPath: List[String],
    status: DocumentStatus,
    validFrom: Option[LocalDate],
    validUntil: Option[LocalDate],
    publishedAt: Option[Instant],
    scope: List[String],
    language: Option[String],
    metadata: Map[String, MetadataValue],
    access: AccessAttributes,
    changedAt: Instant
):
  def classKey: String = classPath.lastOption.getOrElse("")

object SearchDocument:

  /** Statuses of indexed versions: the valid versions and the superseded ones (published history). */
  val IndexedStatuses: Set[DocumentStatus] =
    Set(DocumentStatus.Published, DocumentStatus.ReviewDue, DocumentStatus.Superseded)

  /** Statuses a search covers unless the caller asks for others: the valid versions. */
  val DefaultStatuses: Set[DocumentStatus] = Set(DocumentStatus.Published, DocumentStatus.ReviewDue)

  def isIndexed(status: DocumentStatus): Boolean = IndexedStatuses.contains(status)

/** Where the index takes documents from: the database, the source of truth (ADR 0009). Implemented by the
  * application with the documents and templates modules. Every method runs inside `Db.transact(tenant)`.
  */
trait SearchSource:

  /** Documents changed at or after `since` (document or version); without `since` all documents that have a
    * released version.
    */
  def changedDocuments(since: Option[Instant]): IO[SearchError, List[DocumentId]]

  /** The versions of a document of `tenant` (the tenant of the transaction) to index, possibly none (no
    * version in an indexed status, or the document does not exist).
    */
  def load(tenant: TenantId, id: DocumentId): IO[SearchError, List[SearchDocument]]

enum SearchError:
  /** OpenSearch is not reachable or answered with an error. */
  case Unavailable(message: String)

  /** The documents could not be read from the database. */
  case Source(message: String)

  /** The query is not valid, e.g. a page beyond the result window. */
  case Invalid(message: String)

  def describe: String = this match
    case Unavailable(m) => s"search index unavailable: $m"
    case Source(m)      => s"documents could not be read: $m"
    case Invalid(m)     => m

/** A search of the caller (DMS-52).
  *
  * @param text
  *   the query text (simple query syntax: words, "phrases", prefix*, -excluded); empty lists by filter only
  * @param classes
  *   class keys; a class matches its whole subtree
  * @param statuses
  *   empty: the valid versions ([[SearchDocument.DefaultStatuses]])
  * @param page
  *   1-based
  */
final case class SearchQuery(
    text: Option[String],
    classes: List[String] = Nil,
    statuses: List[DocumentStatus] = Nil,
    languages: List[String] = Nil,
    page: Int = 1,
    size: Int = 20
)

/** @param snippet
  *   plain text of the version around the matches; matches are enclosed in `<mark>` and `</mark>`. Not HTML:
  *   nothing is escaped, clients split at the markers and render the parts as text. Literal markers in the
  *   document text are removed when indexing, so markers always enclose matches.
  */
final case class SearchHit(
    documentId: DocumentId,
    versionId: UUID,
    title: String,
    versionLabel: String,
    classPath: List[String],
    status: DocumentStatus,
    validFrom: Option[LocalDate],
    validUntil: Option[LocalDate],
    language: Option[String],
    organizationalUnit: Option[String],
    snippet: String,
    score: Option[Double]
)

final case class FacetCount(value: String, count: Long)

/** Counts per value over the documents the caller may read. `classes` counts a document for its class and
  * every ancestor; `statuses` ignores the status filter, so other statuses can be offered.
  */
final case class Facets(classes: List[FacetCount], statuses: List[FacetCount], languages: List[FacetCount])

final case class SearchResult(total: Long, page: Int, size: Int, hits: List[SearchHit], facets: Facets)

/** Outcome of a rebuild of a tenant's index. */
final case class RebuildResult(index: String, documents: Int, versions: Int, tookMillis: Long)
