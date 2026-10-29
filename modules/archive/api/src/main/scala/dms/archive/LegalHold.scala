package dms.archive

import dms.db.DbError
import dms.kernel.{DocumentId, UserId}
import zio.IO

import java.time.Instant
import java.util.UUID

opaque type LegalHoldId = UUID
object LegalHoldId:
  def apply(value: UUID): LegalHoldId         = value
  def random(): LegalHoldId                   = UUID.randomUUID()
  extension (id: LegalHoldId) def value: UUID = id

/** How a document came under a hold: named by the records manager, or as a hit of a search they confirmed. */
enum HoldSource:
  case Document, Search

/** The release of a hold: who released it when and why. */
final case class HoldRelease(by: UserId, at: Instant, reason: String)

/** A legal hold (business concept 7.4, US-08.1, ADR 0031): suspends the destruction of the documents it
  * covers until it is released explicitly. It names the matter (Vorgang) it was set for and covers any number
  * of documents; a document is under legal hold while at least one active hold covers it.
  *
  * @param matterReference
  *   reference of the matter in the legal department's systems, e.g. a case number
  * @param documentCount
  *   number of documents the hold covers now (removed documents not counted)
  */
final case class LegalHold(
    id: LegalHoldId,
    name: String,
    matterReference: Option[String],
    reason: String,
    createdBy: UserId,
    createdAt: Instant,
    release: Option[HoldRelease],
    documentCount: Int
):
  def isActive: Boolean = release.isEmpty

/** The removal of a single document from an active hold (DMS-106): who removed it when and why. */
final case class HoldRemoval(by: UserId, at: Instant, reason: String)

/** A document a hold covers or covered, with who added it how and why. A removed document (`removal`) no
  * longer counts as covered by the hold; its entry stays as history.
  */
final case class HeldDocument(
    holdId: LegalHoldId,
    documentId: DocumentId,
    source: HoldSource,
    reason: String,
    addedBy: UserId,
    addedAt: Instant,
    removal: Option[HoldRemoval] = None
):
  def isCurrent: Boolean = removal.isEmpty

/** What a new hold is set for. */
final case class NewLegalHold(name: String, matterReference: Option[String], reason: String)

enum LegalHoldError:
  case NotFound(message: String)

  /** The request is incomplete, e.g. a missing reason. */
  case Invalid(messages: List[String])

  /** The hold was released; it cannot change any more. */
  case Released(message: String)

  case Database(error: DbError)

  def describe: String = this match
    case NotFound(m) => m
    case Invalid(ms) => ms.mkString("; ")
    case Released(m) => m
    case Database(e) => s"database error: $e"

/** Read access to legal holds for other modules, in particular the destruction proposals and the destruction
  * path (DMS-82): a document for which [[isUnderLegalHold]] is true must never be proposed or destroyed. The
  * database enforces the latter as well (`dms.document_version_legal_hold_guard`, migration V19); SQL queries
  * can use `dms.document_under_legal_hold(document_id)`.
  *
  * Every method runs inside `Db.transact(tenant)`.
  */
trait LegalHoldLookup:

  /** Whether an active hold covers the document. */
  def isUnderLegalHold(document: DocumentId): IO[DbError, Boolean]

  /** The documents among `documents` that an active hold covers; filter them out of destruction proposals. */
  def heldAmong(documents: Set[DocumentId]): IO[DbError, Set[DocumentId]]

  /** The active holds covering the document, oldest first. */
  def activeHolds(document: DocumentId): IO[DbError, List[LegalHold]]

/** Outbox events and jobs of legal holds (ADR 0010). */
object LegalHoldEvents:

  /** A hold was set. Payload: `holdId`, `name`, `matterReference`, `reason`, `documentIds`. */
  val Created: String = "legal-hold.created"

  /** Documents were added to a hold. Payload: `holdId`, `reason`, `documentIds` (the newly added ones). */
  val DocumentsAdded: String = "legal-hold.documents-added"

  /** A document was removed from an active hold (DMS-106). Payload: `holdId`, `documentId`, `reason` and
    * `underLegalHold` (whether another active hold still covers the document).
    */
  val DocumentRemoved: String = "legal-hold.document-removed"

  /** A hold was released. Payload: `holdId`, `reason`, `documentIds` (all documents of the hold) and
    * `releasedDocumentIds` (those no other active hold covers any more).
    */
  val Released: String = "legal-hold.released"

  /** Job that brings the object legal hold of a document's renditions in line with its holds. Payload:
    * `documentId`. Enqueued when documents are added to, removed from or released with a hold and when a
    * rendition is attached to a version of a document under legal hold (migration V19).
    */
  val SyncJobKind: String = "archive.legal-hold-sync"
