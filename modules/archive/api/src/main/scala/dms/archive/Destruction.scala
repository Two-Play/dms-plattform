package dms.archive

import dms.db.DbError
import dms.kernel.{DocumentId, UserId}

import java.time.{Instant, LocalDate, ZoneId}
import java.util.UUID

/** The event a retention period starts from (business concept 7.4: "Auslöser"). */
enum RetentionTrigger:
  /** The version went out of force: withdrawn, or superseded by its successor. */
  case EndOfValidity

  /** The version was approved. */
  case Approval

  /** The document was created. */
  case Creation

/** A retention rule (DMS-82, ADR 0054), referenced by its key in the core field `retentionRule` of a
  * version's metadata snapshot.
  *
  * @param periodMonths
  *   retention period after the trigger event
  * @param fromYearEnd
  *   the period starts at the end of the year of the trigger event (typical for commercial and tax law)
  */
final case class RetentionRule(
    key: String,
    name: String,
    periodMonths: Int,
    trigger: RetentionTrigger,
    fromYearEnd: Boolean,
    updatedBy: UserId,
    updatedAt: Instant
)

/** A rule as entered by a records manager. */
final case class RetentionRuleInput(
    name: String,
    periodMonths: Int,
    trigger: RetentionTrigger,
    fromYearEnd: Boolean
)

/** Pure retention rules (ADR 0054). */
object RetentionPeriod:

  /** Keys of retention rules: lower-case letters, digits, '.', '_' and '-', starting with a letter or digit.
    */
  val KeyPattern: String = "[a-z0-9][a-z0-9._-]{0,62}"

  val MaxPeriodMonths: Int = 1200

  /** The last day of the retention period of a version whose trigger event happened at `triggeredAt`: the
    * period starts on that day in the tenant's time zone, or on 31 December of its year with `fromYearEnd`.
    */
  def endsOn(rule: RetentionRule, triggeredAt: Instant, zone: ZoneId): LocalDate =
    val day   = triggeredAt.atZone(zone).toLocalDate
    val start = if rule.fromYearEnd then LocalDate.of(day.getYear, 12, 31) else day
    start.plusMonths(rule.periodMonths.toLong)

  /** The retention ended on `today` if its last day lies before it. */
  def ended(endsOn: LocalDate, today: LocalDate): Boolean = today.isAfter(endsOn)

  /** The moment the rule's trigger event happened for a version, if it did. */
  def triggeredAt(
      trigger: RetentionTrigger,
      outOfForceAt: Option[Instant],
      approvedAt: Option[Instant],
      documentCreatedAt: Instant
  ): Option[Instant] = trigger match
    case RetentionTrigger.EndOfValidity => outOfForceAt
    case RetentionTrigger.Approval      => approvedAt
    case RetentionTrigger.Creation      => Some(documentCreatedAt)

/** A version whose retention ended, offered for destruction (destruction list).
  *
  * @param version
  *   the version number, e.g. "1.2"
  * @param status
  *   Superseded, Withdrawn or Archived
  * @param outOfForceAt
  *   when the version went out of force (withdrawal or the successor's publication)
  * @param pendingProposal
  *   the open proposal the version is part of already, if any
  */
final case class DestructionCandidate(
    documentId: DocumentId,
    versionId: UUID,
    title: String,
    documentNumber: Option[String],
    classKey: String,
    version: String,
    status: String,
    rule: RetentionRule,
    outOfForceAt: Option[Instant],
    retentionEndsOn: LocalDate,
    underLegalHold: Boolean,
    pendingProposal: Option[UUID]
)

/** The destruction list: candidates and how many versions out of force could not be judged. */
final case class DestructionList(
    candidates: List[DestructionCandidate],
    /** Versions out of force without a retention rule, or with a key no rule exists for. */
    withoutRule: Int,
    /** Versions out of force whose retention has not ended yet. */
    retentionRunning: Int
)

enum ProposalStatus:
  case Proposed, Approved, Rejected

/** What happened to a version of a proposal. */
enum ItemOutcome:
  /** Undecided, or being executed. */
  case Pending

  case Destroyed

  /** Not destroyed on approval: legal hold, retention no longer ended, status changed. */
  case Blocked

  /** The proposal was rejected. */
  case Rejected

/** A file of a destroyed version: its name, SHA-256, role (Attachment or Original) and whether its bytes were
  * released (no other version references them).
  */
final case class DestroyedFile(name: String, sha256: String, role: String, released: Boolean)

/** A version of a destruction proposal. Checksums are recorded when the version is destroyed (tombstone). */
final case class DestructionItem(
    versionId: UUID,
    documentId: DocumentId,
    title: String,
    documentNumber: Option[String],
    version: String,
    retentionRule: String,
    retentionEndedOn: LocalDate,
    outcome: ItemOutcome,
    outcomeDetail: Option[String],
    outcomeAt: Option[Instant],
    renditionSha256: Option[String],
    contentSha256: Option[String],
    files: List[DestroyedFile]
)

/** Who decided a proposal when and why. */
final case class ProposalDecision(by: UserId, at: Instant, reason: String)

/** A destruction proposal (business concept 7.4: "Vernichtung wird vorgeschlagen, doppelt freigegeben und
  * protokolliert"): proposed with a reason, approved or rejected once by another records manager.
  */
final case class DestructionProposal(
    id: UUID,
    status: ProposalStatus,
    reason: String,
    proposedBy: UserId,
    proposedAt: Instant,
    decision: Option[ProposalDecision],
    items: List[DestructionItem]
)

/** The destruction record of a version, for the evidence report: the proposal and the tombstone. */
final case class DestructionRecord(proposal: DestructionProposal, item: DestructionItem)

enum DestructionError:
  case NotFound(message: String)
  case Invalid(messages: List[String])

  /** The proposal was decided already, or a version is part of another open proposal. */
  case Conflict(message: String)

  /** Four eyes: whoever proposed cannot decide. */
  case SamePerson(message: String)

  case Database(error: DbError)

  def describe: String = this match
    case NotFound(m)   => m
    case Invalid(ms)   => ms.mkString("; ")
    case Conflict(m)   => m
    case SamePerson(m) => m
    case Database(e)   => s"database error: $e"

/** Job kinds and audit actions of retention, destruction and clean-up (ADR 0054). */
object RetentionEvents:

  /** Removes one object version from object storage after the commit that released it (destruction, orphan
    * clean-up). Payload: `documentId` (optional), `objectKey`, `objectVersion`, `origin`.
    */
  val ObjectCleanupJobKind: String = "archive.object-cleanup"

  /** Periodic maintenance of a tenant: compaction of working states, removal of discarded drafts' content and
    * the clean-up of orphaned objects.
    */
  val MaintenanceJobKind: String = "archive.maintenance"

  val Destroyed: String = "document.destroyed"
