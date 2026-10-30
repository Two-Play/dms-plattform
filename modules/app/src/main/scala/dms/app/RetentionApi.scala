package dms.app

import dms.archive.*
import dms.archive.core.{CleanupReport, DestructionService, ObjectCleanup}
import dms.authz.{AccessDenied, Action, Authorizer, ConfigurationArea, Policy, Resource, Subject}
import dms.db.{Db, DbError}
import dms.documents.DocumentStatus
import dms.documents.core.{DocumentDetails, DocumentError, DocumentService}
import dms.identity.{Authenticator, Principal}
import dms.kernel.DocumentId
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.util.Try

final case class RetentionRuleView(
    /** Referenced by the core field `retentionRule` of a version's metadata. */
    key: String,
    name: String,
    periodMonths: Int,
    /** EndOfValidity, Approval or Creation. */
    trigger: String,
    /** The period starts at the end of the year of the trigger event. */
    fromYearEnd: Boolean,
    updatedBy: String,
    updatedAt: Instant
) derives JsonCodec,
      Schema

final case class SaveRetentionRule(
    name: Option[String],
    /** 1 to 1200. */
    periodMonths: Option[Int],
    /** EndOfValidity (default), Approval or Creation. */
    trigger: Option[String],
    /** Default true. */
    fromYearEnd: Option[Boolean]
) derives JsonCodec,
      Schema

/** A version whose retention ended. */
final case class DestructionCandidateView(
    documentId: UUID,
    versionId: UUID,
    /** Absent if the caller may not manage the document (scope, confidentiality). */
    title: Option[String],
    documentNumber: Option[String],
    classKey: String,
    version: String,
    /** Superseded, Withdrawn or Archived. */
    status: String,
    retentionRule: String,
    retentionRuleName: String,
    outOfForceAt: Option[Instant],
    retentionEndsOn: LocalDate,
    /** A legal hold blocks the destruction; such versions cannot be proposed. */
    underLegalHold: Boolean,
    /** The open proposal the version is part of, if any. */
    pendingProposalId: Option[UUID],
    /** Whether the caller may propose this version. */
    manageable: Boolean
) derives JsonCodec,
      Schema

final case class DestructionListView(
    candidates: List[DestructionCandidateView],
    /** Versions out of force without a retention rule (or with an unknown key); never proposed. */
    withoutRule: Int,
    /** Versions out of force whose retention is still running. */
    retentionRunning: Int
) derives JsonCodec,
      Schema

final case class ProposeDestruction(
    /** Versions from the destruction list. */
    versionIds: Option[List[UUID]],
    /** Why they are to be destroyed (required). */
    reason: Option[String]
) derives JsonCodec,
      Schema

final case class DecideDestruction(
    /** Why the proposal is approved or rejected (required). */
    reason: Option[String]
) derives JsonCodec,
      Schema

final case class DestroyedFileView(name: String, role: String, sha256: String, released: Boolean)
    derives JsonCodec,
      Schema

final case class DestructionItemView(
    versionId: UUID,
    documentId: UUID,
    /** Absent if the caller may not manage the document. */
    title: Option[String],
    documentNumber: Option[String],
    version: String,
    retentionRule: String,
    retentionEndedOn: LocalDate,
    /** Pending, Destroyed, Blocked or Rejected. */
    outcome: String,
    /** Why a version was blocked: LegalHold, RetentionRunning, NoRule or a status change. */
    outcomeDetail: Option[String],
    outcomeAt: Option[Instant],
    renditionSha256: Option[String],
    contentSha256: Option[String],
    files: List[DestroyedFileView]
) derives JsonCodec,
      Schema

final case class DestructionProposalView(
    id: UUID,
    /** Proposed, Approved or Rejected. */
    status: String,
    reason: String,
    proposedBy: String,
    proposedAt: Instant,
    decidedBy: Option[String],
    decidedAt: Option[Instant],
    decisionReason: Option[String],
    items: List[DestructionItemView],
    /** True if the caller may approve or reject it now: open and proposed by someone else (four eyes). */
    canDecide: Boolean
) derives JsonCodec,
      Schema

final case class StorageCleanupRequest(
    /** Only report what would be removed (default true). */
    dryRun: Option[Boolean]
) derives JsonCodec,
      Schema

final case class CleanupSummaryView(
    scanned: Int,
    orphaned: Int,
    /** Removed, or in a dry run: would be removed. */
    deleted: Int,
    keptLocked: Int,
    keptHeld: Int,
    keptRecent: Int,
    failed: Int,
    filesDeleted: Int
) derives JsonCodec,
      Schema

final case class CleanupEntryView(
    key: String,
    version: String,
    /** Deleted, WouldDelete, KeptLocked, KeptHeld, KeptRecent, Failed, FileDeleted or FileWouldDelete. */
    outcome: String,
    detail: Option[String],
    lastModified: Option[Instant]
) derives JsonCodec,
      Schema

final case class CleanupReportView(
    id: UUID,
    dryRun: Boolean,
    startedAt: Instant,
    finishedAt: Instant,
    triggeredBy: String,
    graceHours: Long,
    summary: CleanupSummaryView,
    entries: List[CleanupEntryView]
) derives JsonCodec,
      Schema

/** Retention and destruction (business concept 7.4, DMS-82, ADR 0054).
  *
  * Records managers for the whole tenant (`ManageRetention`, as for legal holds) maintain retention rules,
  * see the destruction list, propose versions with a reason and approve or reject proposals of others (four
  * eyes; approving also needs `Destroy` on every document). The clean-up of orphaned objects (dry run or
  * real) is a configuration task of the retention area (records managers and tenant administrators).
  */
object RetentionApi:
  type Env = Authenticator & Db & Authorizer & DocumentService & DestructionService & ObjectCleanup

  private val base      = "api" / "v1" / "retention"
  private val proposals = base / "destruction-proposals"
  private val proposal  = path[UUID]("id").description("Destruction proposal id")

  val listRules: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base / "rules")
      .out(jsonBody[List[RetentionRuleView]])
      .description("The retention rules of the tenant by key. Requires the records manager role.")
      .tag("Retention")
      .serverLogic(p => _ => checkedSubject(p) *> inTransaction(p)((_, _, s) => s.rules.map(_.map(ruleView))))

  val saveRule: ZServerEndpoint[Env, Any] =
    SecuredApi.base.put
      .in(base / "rules" / path[String]("key").description("Rule key, e.g. contract-10y"))
      .in(jsonBody[SaveRetentionRule])
      .out(jsonBody[RetentionRuleView])
      .description(
        "Creates or changes a retention rule: period in months after the trigger (EndOfValidity, Approval or " +
          "Creation), optionally from the end of that year. The key is what the field retentionRule of a version " +
          "names. 422 for an invalid key, name or period. Audited. Requires the records manager role."
      )
      .tag("Retention")
      .serverLogic(p =>
        (key, req) =>
          for
            _       <- checkedSubject(p)
            trigger <- ZIO
              .fromOption(Try(RetentionTrigger.valueOf(req.trigger.getOrElse("EndOfValidity"))).toOption)
              .orElseFail(invalid("trigger must be EndOfValidity, Approval or Creation"))
            input = RetentionRuleInput(
              req.name.getOrElse(""),
              req.periodMonths.getOrElse(0),
              trigger,
              req.fromYearEnd.getOrElse(true)
            )
            saved <- inTransaction(p)((_, _, s) => s.saveRule(key, input, p.userId))
          yield ruleView(saved)
      )

  val destructionList: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base / "destruction-list")
      .in(query[Option[String]]("class").description("Class key"))
      .in(query[Option[String]]("rule").description("Retention rule key"))
      .in(query[Option[String]]("q").description("Part of the title or the document number"))
      .out(jsonBody[DestructionListView])
      .description(
        "The versions out of force whose retention ended (destruction list), with legal holds and open proposals " +
          "marked, and how many versions have no rule or a running retention. Titles only of documents the caller " +
          "may manage. Requires the records manager role."
      )
      .tag("Retention")
      .serverLogic(p =>
        (classKey, rule, q) =>
          for
            subject <- checkedSubject(p)
            result  <- inTransaction(p) { (_, documents, service) =>
              for
                list <- service.destructionList
                filtered = list.candidates.filter(c =>
                  classKey.forall(_ == c.classKey) && rule.forall(_ == c.rule.key) &&
                    q.map(_.trim.toLowerCase)
                      .filter(_.nonEmpty)
                      .forall(text =>
                        c.title.toLowerCase.contains(text) || c.documentNumber
                          .exists(_.toLowerCase.contains(text))
                      )
                )
                allowed <- permitted(
                  subject,
                  documents,
                  filtered.map(_.documentId).distinct,
                  Action.ManageRetention
                )
              yield DestructionListView(
                filtered.map(c => candidateView(c, allowed.contains(c.documentId))),
                list.withoutRule,
                list.retentionRunning
              )
            }
          yield result
      )

  val propose: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(proposals)
      .in(jsonBody[ProposeDestruction])
      .out(statusCode(StatusCode.Created).and(jsonBody[DestructionProposalView]))
      .description(
        "Proposes versions of the destruction list for destruction; a reason is required (422). 409 if a version is " +
          "under legal hold or part of another open proposal, 422 if it is not on the destruction list. Another " +
          "records manager approves or rejects it. Requires the records manager role for the tenant and every document."
      )
      .tag("Retention")
      .serverLogic(p =>
        req =>
          for
            reason  <- required(req.reason)
            subject <- checkedSubject(p)
            result  <- inTransaction(p) { (authz, documents, service) =>
              for
                list <- service.destructionList
                ids     = req.versionIds.getOrElse(Nil)
                touched = list.candidates.filter(c => ids.contains(c.versionId)).map(_.documentId).distinct
                _ <- checkAll(authz, subject, documents, touched, Action.ManageRetention, archived = false)
                created <- service.propose(ids, reason, p.userId)
                view    <- proposalView(subject, documents, created)
              yield view
            }
          yield result
      )

  val listProposals: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(proposals)
      .in(query[Option[String]]("status").description("proposed (default), approved, rejected or all"))
      .out(jsonBody[List[DestructionProposalView]])
      .description("Destruction proposals, the latest first. Requires the records manager role.")
      .tag("Retention")
      .serverLogic(p =>
        status =>
          for
            state <- status.getOrElse("proposed") match
              case "all" => ZIO.none
              case other =>
                ZIO
                  .fromOption(ProposalStatus.values.find(_.toString.equalsIgnoreCase(other)))
                  .mapBoth(
                    _ =>
                      (
                        StatusCode.BadRequest,
                        ApiError("invalid", s"unknown status '$other'; proposed, approved, rejected or all")
                      ),
                    Some(_)
                  )
            subject <- checkedSubject(p)
            result  <- inTransaction(p)((_, documents, service) =>
              service.list(state).flatMap(ZIO.foreach(_)(proposalView(subject, documents, _)))
            )
          yield result
      )

  val getProposal: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(proposals / proposal)
      .out(jsonBody[DestructionProposalView])
      .description(
        "A destruction proposal with its versions and their outcome. Requires the records manager role."
      )
      .tag("Retention")
      .serverLogic(p =>
        id =>
          for
            subject <- checkedSubject(p)
            result  <- inTransaction(p)((_, documents, service) =>
              service.get(id).flatMap(proposalView(subject, documents, _))
            )
          yield result
      )

  val approve: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(proposals / proposal / "approve")
      .in(jsonBody[DecideDestruction])
      .out(jsonBody[DestructionProposalView])
      .description(
        "Approves a proposal and destroys its versions: content, values, working states, files no other version " +
          "uses and the rendition are removed (objects after the commit, respecting Object Lock); a tombstone with " +
          "checksums, the audit trail and the evidence report stay. A version under legal hold now, or whose " +
          "retention no longer ended, is blocked instead. A reason is required (422); 403 four_eyes for the " +
          "proposer; 409 if decided already. Requires the records manager role for the tenant and every document."
      )
      .tag("Retention")
      .serverLogic(p =>
        (id, req) =>
          for
            reason  <- required(req.reason)
            subject <- checkedSubject(p)
            result  <- inTransaction(p) { (authz, documents, service) =>
              for
                open <- service.get(id)
                _    <- checkAll(
                  authz,
                  subject,
                  documents,
                  open.items.map(_.documentId).distinct,
                  Action.Destroy,
                  true
                )
                decided <- service.approve(id, reason, p.userId)
                view    <- proposalView(subject, documents, decided)
              yield view
            }
          yield result
      )

  val reject: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(proposals / proposal / "reject")
      .in(jsonBody[DecideDestruction])
      .out(jsonBody[DestructionProposalView])
      .description(
        "Rejects a proposal; nothing is destroyed. A reason is required (422); 403 four_eyes for the proposer; 409 " +
          "if decided already. Requires the records manager role."
      )
      .tag("Retention")
      .serverLogic(p =>
        (id, req) =>
          for
            reason  <- required(req.reason)
            subject <- checkedSubject(p)
            result  <- inTransaction(p)((_, documents, service) =>
              service.reject(id, reason, p.userId).flatMap(proposalView(subject, documents, _))
            )
          yield result
      )

  val runCleanup: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(base / "storage-cleanup")
      .in(jsonBody[StorageCleanupRequest])
      .out(jsonBody[CleanupReportView])
      .description(
        "Finds objects in object storage that no version references (older renditions of drafts, removed " +
          "attachments, replaced originals, files of discarded drafts) and removes them, or with dryRun (default) " +
          "only reports them. Objects of documents under legal hold, objects protected by Object Lock and objects " +
          "younger than the grace period are kept. The run is stored and audited. Requires the records manager or " +
          "tenant administrator role."
      )
      .tag("Retention")
      .serverLogic(p =>
        req =>
          for
            _       <- checkedConfiguration(p)
            cleanup <- ZIO.service[ObjectCleanup]
            report  <- cleanup
              .sweep(p.tenantId, req.dryRun.getOrElse(true), p.userId)
              .mapError(m => (StatusCode.ServiceUnavailable, ApiError("cleanup_failed", m)))
          yield reportView(report)
      )

  val listCleanups: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base / "storage-cleanup")
      .out(jsonBody[List[CleanupReportView]])
      .description(
        "The latest clean-up runs with their reports. Requires the records manager or tenant administrator role."
      )
      .tag("Retention")
      .serverLogic(p =>
        _ =>
          for
            _       <- checkedConfiguration(p)
            db      <- ZIO.service[Db]
            cleanup <- ZIO.service[ObjectCleanup]
            runs    <- db.transact(p.tenantId)(cleanup.runs(20)).mapError(DocumentApi.toResponse)
          yield runs.map(reportView)
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] =
    List(
      listRules,
      saveRule,
      destructionList,
      propose,
      listProposals,
      getProposal,
      approve,
      reject,
      runCleanup,
      listCleanups
    )

  private type Failure = DestructionError | DocumentError | AccessDenied

  private def invalid(message: String): (StatusCode, ApiError) =
    (StatusCode.UnprocessableEntity, ApiError("invalid", message))

  private def required(value: Option[String]): IO[(StatusCode, ApiError), String] =
    ZIO.fromOption(value.map(_.trim).filter(_.nonEmpty)).orElseFail(invalid("reason is required"))

  /** The caller's subject, if they are a records manager for the whole tenant (403 otherwise). */
  private def checkedSubject(p: Principal): ZIO[Env, (StatusCode, ApiError), Subject] =
    subjectWith(p, Action.ManageRetention, Resource(p.tenantId))

  private def checkedConfiguration(p: Principal): ZIO[Env, (StatusCode, ApiError), Subject] =
    subjectWith(
      p,
      Action.Configure,
      Resource(p.tenantId, configurationArea = Some(ConfigurationArea.Retention))
    )

  private def subjectWith(p: Principal, action: Action, resource: Resource) =
    for
      db      <- ZIO.service[Db]
      authz   <- ZIO.service[Authorizer]
      subject <- db
        .transact(p.tenantId)(authz.subject(p).tap(s => authz.check(s, action, resource)))
        .mapError {
          case e: AccessDenied => DocumentApi.toResponse(e)
          case e: DbError      => DocumentApi.toResponse(e)
        }
    yield subject

  /** The resource of a document for retention decisions: its latest version; for `Destroy` as archived (the
    * destruction archives a superseded or withdrawn version first).
    */
  private def resourceOf(subject: Subject, details: DocumentDetails, archived: Boolean): Option[Resource] =
    details.versions.lastOption.map { latest =>
      val r = DocumentApi.resource(subject.tenantId, details, latest)
      if archived then r.copy(status = Some(DocumentStatus.Archived)) else r
    }

  private def checkAll(
      authz: Authorizer,
      subject: Subject,
      documents: DocumentService,
      ids: List[DocumentId],
      action: Action,
      archived: Boolean
  ): IO[Failure, Unit] =
    ZIO.foreachDiscard(ids)(id =>
      documents
        .get(id)
        .flatMap(details =>
          ZIO.foreachDiscard(resourceOf(subject, details, archived))(authz.check(subject, action, _))
        )
    )

  /** The documents among `ids` the caller may act on with `action` (without failing). */
  private def permitted(
      subject: Subject,
      documents: DocumentService,
      ids: List[DocumentId],
      action: Action
  ): IO[DocumentError, Set[DocumentId]] =
    for
      now     <- Clock.instant
      allowed <- ZIO.foreach(ids)(id =>
        documents
          .get(id)
          .map(details =>
            resourceOf(subject, details, archived = false)
              .exists(r => Policy.decide(subject, action, r, now).isPermitted)
          )
          .map(id -> _)
      )
    yield allowed.collect { case (id, true) => id }.toSet

  private def proposalView(
      subject: Subject,
      documents: DocumentService,
      proposal: DestructionProposal
  ): IO[DocumentError, DestructionProposalView] =
    permitted(subject, documents, proposal.items.map(_.documentId).distinct, Action.ManageRetention).map(
      allowed =>
        DestructionProposalView(
          proposal.id,
          proposal.status.toString,
          proposal.reason,
          proposal.proposedBy.value,
          proposal.proposedAt,
          proposal.decision.map(_.by.value),
          proposal.decision.map(_.at),
          proposal.decision.map(_.reason),
          proposal.items.map(i =>
            DestructionItemView(
              i.versionId,
              i.documentId.value,
              Option.when(allowed.contains(i.documentId))(i.title),
              i.documentNumber,
              i.version,
              i.retentionRule,
              i.retentionEndedOn,
              i.outcome.toString,
              i.outcomeDetail,
              i.outcomeAt,
              i.renditionSha256,
              i.contentSha256,
              i.files.map(f => DestroyedFileView(f.name, f.role, f.sha256, f.released))
            )
          ),
          proposal.status == ProposalStatus.Proposed && proposal.proposedBy != subject.userId
        )
    )

  private def ruleView(r: RetentionRule): RetentionRuleView =
    RetentionRuleView(
      r.key,
      r.name,
      r.periodMonths,
      r.trigger.toString,
      r.fromYearEnd,
      r.updatedBy.value,
      r.updatedAt
    )

  private def candidateView(c: DestructionCandidate, manageable: Boolean): DestructionCandidateView =
    DestructionCandidateView(
      c.documentId.value,
      c.versionId,
      Option.when(manageable)(c.title),
      c.documentNumber,
      c.classKey,
      c.version,
      c.status,
      c.rule.key,
      c.rule.name,
      c.outOfForceAt,
      c.retentionEndsOn,
      c.underLegalHold,
      c.pendingProposal,
      manageable && !c.underLegalHold && c.pendingProposal.isEmpty
    )

  private def reportView(r: CleanupReport): CleanupReportView =
    CleanupReportView(
      r.id,
      r.dryRun,
      r.startedAt,
      r.finishedAt,
      r.triggeredBy,
      r.graceHours,
      CleanupSummaryView(
        r.summary.scanned,
        r.summary.orphaned,
        r.summary.deleted,
        r.summary.keptLocked,
        r.summary.keptHeld,
        r.summary.keptRecent,
        r.summary.failed,
        r.summary.filesDeleted
      ),
      r.entries.map(e => CleanupEntryView(e.key, e.version, e.outcome, e.detail, e.lastModified))
    )

  private def inTransaction[A](principal: Principal)(
      f: (Authorizer, DocumentService, DestructionService) => ZIO[Any, Failure, A]
  ): ZIO[Env, (StatusCode, ApiError), A] =
    (for
      db        <- ZIO.service[Db]
      authz     <- ZIO.service[Authorizer]
      documents <- ZIO.service[DocumentService]
      service   <- ZIO.service[DestructionService]
      result    <- db.transact(principal.tenantId)(f(authz, documents, service))
    yield result).mapError {
      case e: DestructionError => toResponse(e)
      case e: AccessDenied     => DocumentApi.toResponse(e)
      case e: DbError          => DocumentApi.toResponse(e)
      case e: DocumentError    => DocumentApi.toResponse(e)
    }

  def toResponse(error: DestructionError): (StatusCode, ApiError) = error match
    case e @ DestructionError.Invalid(_) => (StatusCode.UnprocessableEntity, ApiError("invalid", e.describe))
    case DestructionError.NotFound(m)    => (StatusCode.NotFound, ApiError("not_found", m))
    case DestructionError.Conflict(m)    => (StatusCode.Conflict, ApiError("conflict", m))
    case DestructionError.SamePerson(m)  => (StatusCode.Forbidden, ApiError("four_eyes", m))
    case DestructionError.Database(e)    => DocumentApi.toResponse(e)
