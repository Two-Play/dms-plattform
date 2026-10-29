package dms.app

import dms.archive.LegalHold
import dms.audit.{ChainIntegrity, RecordedEntry}
import dms.documents.core.DocumentDetails
import dms.documents.{Acknowledgement, DocumentVersion, RecordedDeviationRequest, ReviewTask, Validity}
import dms.workflow.{Approval, CaseDetails, Vote}
import zio.json.*
import zio.json.ast.Json

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.{Instant, LocalDate}
import java.util.{HexFormat, Locale, UUID}

// The payload of an evidence report (US-12.1, DMS-57, ADR 0034), format 1. Persons are identified by their user
// ids: the platform has no source of display names yet. Field names are part of the format; change them only
// together with a new format version.

final case class EvidenceDocument(
    id: UUID,
    title: String,
    classPath: List[String],
    authorId: String,
    ownerId: Option[String],
    createdAt: Instant,
    templateId: UUID,
    templateVersion: Int
) derives JsonCodec

final case class EvidenceValidity(from: Instant, until: Option[Instant]) derives JsonCodec

final case class EvidenceRendition(objectKey: String, objectVersion: Option[String], sha256: String)
    derives JsonCodec

/** An attachment of a version (DMS-114) with the SHA-256 of its stored bytes. */
final case class EvidenceAttachment(
    name: String,
    contentType: String,
    size: Long,
    sha256: String,
    objectKey: String,
    objectVersion: String,
    scanStatus: String,
    addedBy: String,
    addedAt: Instant
) derives JsonCodec

final case class EvidenceDeviationRequest(
    sectionId: String,
    reason: String,
    requestedBy: String,
    requestedAt: Instant,
    withdrawnBy: Option[String],
    withdrawnAt: Option[Instant]
) derives JsonCodec

/** A vote: who decided what in which step, why, when, after which sign-in, and for whom as deputy. */
final case class EvidenceVote(
    round: Int,
    step: String,
    lane: String,
    voter: String,
    actingFor: Option[String],
    decision: String,
    reason: Option[String],
    votedAt: Instant,
    authenticatedAt: Option[Instant],
    contentSha256: String,
    invalidatedAt: Option[Instant]
) derives JsonCodec

final case class EvidenceApproval(
    approvedBy: String,
    actingFor: Option[String],
    approvedAt: Instant,
    authenticatedAt: Instant,
    contentSha256: String
) derives JsonCodec

final case class EvidenceCase(
    caseId: UUID,
    status: String,
    rounds: Int,
    startedBy: String,
    startedAt: Instant,
    votes: List[EvidenceVote],
    approval: Option[EvidenceApproval]
) derives JsonCodec

final case class EvidenceAcknowledgementEntry(userId: String, openedAt: Instant, acknowledgedAt: Instant)
    derives JsonCodec

/** The acknowledgement rate of a version: `acknowledged` of `audience` readers (ADR 0027: everyone who may
  * read the version under the policy, evaluated at `evaluatedAt`).
  *
  * @param acknowledged
  *   acknowledgements by members of the audience
  * @param acknowledgedTotal
  *   all acknowledgements, including readers who are no longer in the audience
  * @param ratePercent
  *   `acknowledged / audience` in percent with one decimal; absent for an empty audience
  */
final case class EvidenceAcknowledgements(
    evaluatedAt: Instant,
    audience: Int,
    acknowledged: Int,
    acknowledgedTotal: Int,
    ratePercent: Option[String],
    entries: List[EvidenceAcknowledgementEntry]
) derives JsonCodec

final case class EvidenceReview(
    taskId: UUID,
    dueOn: LocalDate,
    createdAt: Instant,
    status: String,
    decidedAt: Option[Instant],
    decidedBy: Option[String],
    nextDueOn: Option[LocalDate],
    newVersionId: Option[UUID],
    comment: Option[String]
) derives JsonCodec

final case class EvidenceVersion(
    id: UUID,
    version: String,
    status: String,
    changeKind: String,
    changeReason: Option[String],
    createdBy: String,
    createdAt: Instant,
    releasedBy: Option[String],
    releasedAt: Option[Instant],
    validFrom: Option[LocalDate],
    publishedAt: Option[Instant],
    validity: Option[EvidenceValidity],
    supersededBy: Option[UUID],
    contentSha256: String,
    rendition: Option[EvidenceRendition],
    deviationRequests: List[EvidenceDeviationRequest],
    workflow: List[EvidenceCase],
    requiresAcknowledgement: Boolean,
    acknowledgements: Option[EvidenceAcknowledgements],
    reviews: List[EvidenceReview],
    /** Present only for versions with attachments (DMS-114), so reports of documents without attachments keep
      * their layout.
      */
    attachments: Option[List[EvidenceAttachment]] = None
) derives JsonCodec

final case class EvidenceLegalHold(
    id: UUID,
    name: String,
    matterReference: Option[String],
    reason: String,
    createdBy: String,
    createdAt: Instant,
    releasedAt: Option[Instant],
    releasedBy: Option[String],
    releaseReason: Option[String]
) derives JsonCodec

/** An audit chain entry about the document, with its stored hash. */
final case class EvidenceAuditEntry(
    chainSeq: Long,
    occurredAt: Instant,
    actorId: String,
    actorKind: String,
    onBehalfOf: Option[String],
    action: String,
    objectVersion: Option[String],
    reason: Option[String],
    channel: String,
    details: Json,
    hash: String
) derives JsonCodec

final case class EvidenceViolation(chainSeq: Long, kind: String, detail: String) derives JsonCodec

/** The audit chain of the tenant as verified for the report: the whole chain (links, hashes, timestamp
  * anchors, newest write-once checkpoint), and whether the document's entries lie in its verified, anchored
  * and checkpointed part.
  */
final case class EvidenceChain(
    intact: Boolean,
    entriesChecked: Long,
    headSeq: Option[Long],
    headHash: Option[String],
    anchorsChecked: Int,
    violation: Option[EvidenceViolation],
    documentEntries: Int,
    documentEntriesVerified: Boolean,
    anchoredUntilSeq: Option[Long],
    anchoredAt: Option[Instant],
    documentEntriesAnchored: Boolean,
    checkpointedUntilSeq: Option[Long],
    documentEntriesCheckpointed: Boolean,
    checkpointProblem: Option[String]
) derives JsonCodec

/** A check of one version: `Match`, `Mismatch` or `Unverified` (e.g. object storage unavailable). */
final case class EvidenceCheck(
    versionId: UUID,
    version: String,
    check: String,
    status: String,
    expected: Option[String],
    detail: Option[String]
) derives JsonCodec

/** @param status
  *   `Intact` (everything checked and consistent), `Violated` (a check found a manipulation or inconsistency)
  *   or `Incomplete` (no violation, but something could not be checked)
  */
final case class EvidenceIntegrity(
    status: String,
    checkedAt: Instant,
    auditChain: EvidenceChain,
    checks: List[EvidenceCheck],
    findings: List[String]
) derives JsonCodec

final case class EvidenceReportPayload(
    format: String,
    reportId: UUID,
    createdAt: Instant,
    createdBy: String,
    tenantId: UUID,
    document: EvidenceDocument,
    versions: List[EvidenceVersion],
    legalHolds: List[EvidenceLegalHold],
    auditTrail: List[EvidenceAuditEntry],
    integrity: EvidenceIntegrity,
    notes: List[String]
) derives JsonCodec

/** Everything a report is built from, gathered in one transaction (except the integrity results). */
final case class EvidenceFacts(
    details: DocumentDetails,
    cases: List[CaseDetails],
    deviationRequests: Map[UUID, List[RecordedDeviationRequest]],
    acknowledgements: Map[UUID, List[Acknowledgement]],
    audiences: Map[UUID, Set[String]],
    reviews: List[ReviewTask],
    holds: List[LegalHold],
    entries: List[RecordedEntry],
    contentSha256: Map[UUID, String]
)

/** Result of re-reading a stored rendition. */
enum RenditionCheck:
  case Matches
  case Mismatch(detail: String)
  case Unverified(detail: String)

object EvidenceReport:

  val Format: String = "dms.evidence-report/1"
  val FormatNo: Int  = 1
  val Intact: String = "Intact"
  val Violated       = "Violated"
  val Incomplete     = "Incomplete"
  val Match: String  = "Match"
  val Mismatch       = "Mismatch"
  val Unverified     = "Unverified"
  val PersonNote     =
    "Persons are identified by their user ids: the platform has no source of display names yet."
  val AudienceNote =
    "Acknowledgement audience: everyone who may read the version under the current role assignments and " +
      "access groups, evaluated when the report was created (ADR 0027)."

  /** The canonical serialization: object keys sorted by UTF-16 code units, no whitespace, absent optional
    * fields left out. The SHA-256 of a report is taken over the UTF-8 bytes of exactly this text.
    */
  def canonical(payload: EvidenceReportPayload): String =
    canonical(payload.toJsonAST.fold(e => throw IllegalStateException(e), identity))

  def canonical(json: Json): String = json match
    case Json.Obj(fields) =>
      fields.toList
        .filterNot(_._2 == Json.Null)
        .sortBy(_._1)
        .map((k, v) => s"${Json.Str(k).toJson}:${canonical(v)}")
        .mkString("{", ",", "}")
    case Json.Arr(items) => items.map(canonical).mkString("[", ",", "]")
    case other           => other.toJson

  def sha256(text: String): String =
    HexFormat
      .of()
      .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))

  def parse(payload: String): Either[String, EvidenceReportPayload] = payload.fromJson[EvidenceReportPayload]

  /** Builds the report from the gathered facts and the integrity results. */
  def build(
      reportId: UUID,
      createdAt: Instant,
      createdBy: String,
      tenantId: UUID,
      facts: EvidenceFacts,
      chain: ChainIntegrity,
      renditions: Map[UUID, RenditionCheck],
      attachments: Map[UUID, RenditionCheck] = Map.empty
  ): EvidenceReportPayload =
    val d         = facts.details.document
    val versions  = facts.details.versions
    val chainView = chainOf(chain, facts.entries)
    val checks    = versions.flatMap(v =>
      versionChecks(v, facts) ++ renditionCheck(v, renditions) ++ attachmentChecks(v, attachments)
    )
    val findings =
      chainFindings(chainView) ++ checks
        .filter(_.status != Match)
        .map(c =>
          s"Version ${c.version}: ${c.check} ${c.status.toLowerCase(Locale.ROOT)}" + c.detail.fold("")(d =>
            s" ($d)"
          )
        )
    val status =
      if !chainView.intact || !chainView.documentEntriesVerified || checks.exists(_.status == Mismatch) then
        Violated
      else if checks.exists(_.status == Unverified) || chainView.checkpointProblem.isDefined then Incomplete
      else Intact
    EvidenceReportPayload(
      format = Format,
      reportId = reportId,
      createdAt = createdAt,
      createdBy = createdBy,
      tenantId = tenantId,
      document = EvidenceDocument(
        d.id.value,
        d.title,
        facts.details.profile.classPath.map(_.value),
        d.authorId.value,
        d.ownerId.map(_.value),
        d.createdAt,
        d.templateId.value,
        d.templateVersion
      ),
      versions = versions.map(version(_, facts, createdAt)),
      legalHolds = facts.holds.map(h =>
        EvidenceLegalHold(
          h.id.value,
          h.name,
          h.matterReference,
          h.reason,
          h.createdBy.value,
          h.createdAt,
          h.release.map(_.at),
          h.release.map(_.by.value),
          h.release.map(_.reason)
        )
      ),
      auditTrail = facts.entries.map(e =>
        EvidenceAuditEntry(
          e.chainSeq,
          e.occurredAt,
          e.actorId,
          e.actorKind,
          e.onBehalfOf,
          e.action,
          e.objectVersion,
          e.reason,
          e.channel,
          e.details.fromJson[Json].getOrElse(Json.Str(e.details)),
          e.hash
        )
      ),
      integrity = EvidenceIntegrity(status, createdAt, chainView, checks, findings),
      notes = List(PersonNote, AudienceNote)
    )

  private def version(v: DocumentVersion, facts: EvidenceFacts, at: Instant): EvidenceVersion =
    val cases = facts.cases.filter(_.workflowCase.versionId == v.id)
    EvidenceVersion(
      id = v.id,
      version = v.label,
      status = v.status.toString,
      changeKind = v.changeKind.toString,
      changeReason = v.changeReason,
      createdBy = v.createdBy.value,
      createdAt = v.createdAt,
      releasedBy = v.releasedBy.map(_.value),
      releasedAt = v.releasedAt,
      validFrom = v.validFrom,
      publishedAt = v.publishedAt,
      validity = validity(v, facts),
      supersededBy = v.supersededBy,
      contentSha256 = facts.contentSha256.getOrElse(v.id, ""),
      rendition = v.rendition.map(r => EvidenceRendition(r.objectKey, r.objectVersion, r.sha256)),
      deviationRequests = facts.deviationRequests
        .getOrElse(v.id, Nil)
        .map(r =>
          EvidenceDeviationRequest(
            r.sectionId.value,
            r.reason,
            r.requestedBy.value,
            r.requestedAt,
            r.withdrawnBy.map(_.value),
            r.withdrawnAt
          )
        ),
      workflow = cases.map(caseOf),
      requiresAcknowledgement = v.requiresAcknowledgement,
      acknowledgements = Option.when(v.requiresAcknowledgement && v.publishedAt.isDefined)(
        acknowledgements(
          facts.acknowledgements.getOrElse(v.id, Nil),
          facts.audiences.getOrElse(v.id, Set.empty),
          at
        )
      ),
      reviews = facts.reviews
        .filter(_.versionId == v.id)
        .map(t =>
          EvidenceReview(
            t.id,
            t.dueOn,
            t.createdAt,
            t.status.toString,
            t.decidedAt,
            t.decidedBy.map(_.value),
            t.nextDueOn,
            t.newVersionId,
            t.comment
          )
        ),
      attachments = Option.when(v.attachments.nonEmpty)(
        v.attachments.map(a =>
          EvidenceAttachment(
            a.name,
            a.contentType,
            a.size,
            a.sha256,
            a.objectKey,
            a.objectVersion,
            a.scanStatus.toString,
            a.addedBy.value,
            a.addedAt
          )
        )
      )
    )

  /** In force from its effective start until its successor's effective start or its withdrawal (DMS-53). */
  private def validity(v: DocumentVersion, facts: EvidenceFacts): Option[EvidenceValidity] =
    val zone = facts.details.zone
    v.publishedAt.map { published =>
      val from      = Validity.effectiveStart(v.validFrom, published, zone)
      val successor = v.supersededBy
        .flatMap(id => facts.details.versions.find(_.id == id))
        .flatMap(s => s.publishedAt.map(p => Validity.effectiveStart(s.validFrom, p, zone)))
      val withdrawn = facts.reviews
        .find(t => t.versionId == v.id && t.status == dms.documents.ReviewStatus.Withdrawn)
        .flatMap(_.decidedAt)
      EvidenceValidity(from, successor.orElse(withdrawn))
    }

  private def caseOf(c: CaseDetails): EvidenceCase =
    val w = c.workflowCase
    EvidenceCase(
      w.id,
      w.status.toString,
      w.round,
      w.startedBy.value,
      w.startedAt,
      c.votes.sortBy(v => (v.votedAt, v.id.toString)).map(vote),
      c.approval.map(approval)
    )

  private def vote(v: Vote): EvidenceVote =
    EvidenceVote(
      v.round,
      v.step,
      v.lane,
      v.voter.value,
      v.actingFor.map(_.value),
      v.decision.toString,
      v.reason,
      v.votedAt,
      v.authenticatedAt,
      v.contentSha256,
      v.invalidatedAt
    )

  private def approval(a: Approval): EvidenceApproval =
    EvidenceApproval(
      a.approvedBy.value,
      a.actingFor.map(_.value),
      a.approvedAt,
      a.authenticatedAt,
      a.contentSha256
    )

  def acknowledgements(
      acks: List[Acknowledgement],
      audience: Set[String],
      at: Instant
  ): EvidenceAcknowledgements =
    val inAudience = acks.count(a => audience.contains(a.userId.value))
    EvidenceAcknowledgements(
      evaluatedAt = at,
      audience = audience.size,
      acknowledged = inAudience,
      acknowledgedTotal = acks.size,
      ratePercent = Option.when(audience.nonEmpty)(
        String.format(Locale.ROOT, "%.1f", Double.box(inAudience * 100.0 / audience.size))
      ),
      entries = acks.map(a => EvidenceAcknowledgementEntry(a.userId.value, a.openedAt, a.acknowledgedAt))
    )

  private def chainOf(c: ChainIntegrity, entries: List[RecordedEntry]): EvidenceChain =
    val r                           = c.verification
    val lastSeq                     = entries.map(_.chainSeq).maxOption
    def covers(until: Option[Long]) = lastSeq.forall(s => until.exists(_ >= s))
    EvidenceChain(
      intact = r.ok,
      entriesChecked = r.entriesChecked,
      headSeq = r.headSeq,
      headHash = r.headHash,
      anchorsChecked = r.anchorsChecked,
      violation = r.violation.map(v => EvidenceViolation(v.chainSeq, v.kind.toString, v.detail)),
      documentEntries = entries.size,
      documentEntriesVerified =
        covers(r.headSeq) && r.violation.forall(v => entries.forall(_.chainSeq < v.chainSeq)),
      anchoredUntilSeq = c.anchor.map(_.anchoredUntilSeq),
      anchoredAt = c.anchor.map(_.tsaTime),
      documentEntriesAnchored = covers(c.anchor.map(_.anchoredUntilSeq)),
      checkpointedUntilSeq = c.checkpointedUntilSeq,
      documentEntriesCheckpointed = covers(c.checkpointedUntilSeq),
      checkpointProblem = c.checkpointProblem
    )

  private def chainFindings(c: EvidenceChain): List[String] =
    c.violation.map(v => s"Audit chain violated at position ${v.chainSeq}: ${v.kind} (${v.detail})").toList ++
      Option
        .unless(c.documentEntriesVerified)(
          "The document's audit entries are not in the verified part of the chain"
        )
        .toList ++
      c.checkpointProblem.map(p => s"Write-once checkpoints not compared: $p").toList ++
      Option
        .unless(c.documentEntriesAnchored)(
          "Not all of the document's audit entries are covered by a timestamp anchor yet"
        )
        .toList

  /** The approval binds the content checksum (DMS-41): the stored version must still have it, and the
    * metadata snapshot must name the attached rendition.
    */
  private def versionChecks(v: DocumentVersion, facts: EvidenceFacts): List[EvidenceCheck] =
    val approval = facts.cases.filter(_.workflowCase.versionId == v.id).flatMap(_.approval).lastOption
    val content  = approval.map { a =>
      val actual = facts.contentSha256.getOrElse(v.id, "")
      EvidenceCheck(
        v.id,
        v.label,
        "approved content",
        if actual == a.contentSha256 then Match else Mismatch,
        Some(a.contentSha256),
        Option.unless(actual == a.contentSha256)(s"the version's content has SHA-256 $actual")
      )
    }
    val snapshot = for
      r     <- v.rendition
      snap  <- v.metadataSnapshot
      value <- snap.values.get("renditionChecksum").collect {
        case dms.documents.metadata.MetadataValue.Text(t) => t
      }
    yield EvidenceCheck(
      v.id,
      v.label,
      "metadata snapshot",
      if value == r.sha256 then Match else Mismatch,
      Some(r.sha256),
      Option.unless(value == r.sha256)(s"the snapshot records rendition checksum $value")
    )
    content.toList ++ snapshot.toList

  private def renditionCheck(v: DocumentVersion, results: Map[UUID, RenditionCheck]): Option[EvidenceCheck] =
    v.rendition.map { r =>
      val (status, detail) = results.get(v.id) match
        case Some(RenditionCheck.Matches)       => (Match, None)
        case Some(RenditionCheck.Mismatch(d))   => (Mismatch, Some(d))
        case Some(RenditionCheck.Unverified(d)) => (Unverified, Some(d))
        case None                               => (Unverified, Some("not checked"))
      EvidenceCheck(v.id, v.label, "rendition checksum", status, Some(r.sha256), detail)
    }

  /** Every attachment file read back from object storage against its SHA-256 (DMS-114), keyed by file id. */
  private def attachmentChecks(v: DocumentVersion, results: Map[UUID, RenditionCheck]): List[EvidenceCheck] =
    v.attachments.map { a =>
      val (status, detail) = results.get(a.fileId) match
        case Some(RenditionCheck.Matches)       => (Match, None)
        case Some(RenditionCheck.Mismatch(d))   => (Mismatch, Some(d))
        case Some(RenditionCheck.Unverified(d)) => (Unverified, Some(d))
        case None                               => (Unverified, Some("not checked"))
      EvidenceCheck(v.id, v.label, s"attachment checksum (${a.name})", status, Some(a.sha256), detail)
    }
