package dms.documents

import dms.kernel.{DocumentId, UserId}

import java.time.Instant
import java.util.UUID

/** A reader's confirmation that they read a valid version which requires acknowledgement (business concept
  * 6.3, US-06.2, ADR 0027). Recorded once per reader and version and never changed.
  *
  * @param versionLabel
  *   the version number the reader acknowledged, e.g. `2.0`
  * @param openedAt
  *   when the reader opened the version first; acknowledging is only possible afterwards
  */
final case class Acknowledgement(
    id: UUID,
    documentId: DocumentId,
    versionId: UUID,
    versionLabel: String,
    userId: UserId,
    openedAt: Instant,
    acknowledgedAt: Instant
)

object Acknowledgements:

  /** Whether `version` asks its readers for an acknowledgement now: it is valid (Published or ReviewDue) and
    * requires acknowledgement. Superseded versions no longer do; their successor asks on its own if it
    * requires acknowledgement.
    */
  def requested(version: DocumentVersion): Boolean =
    Versioning.isValid(version.status) && version.requiresAcknowledgement
