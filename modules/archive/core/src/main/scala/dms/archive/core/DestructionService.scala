package dms.archive.core

import com.augustnagro.magnum.*
import dms.archive.*
import dms.audit.*
import dms.db.queue.Outbox
import dms.db.{Db, Jsonb}
import dms.kernel.{DocumentId, UserId}
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.{LocalDate, OffsetDateTime, ZoneId}
import java.util.UUID
import scala.util.Try

/** Retention rules and the destruction of versions after their retention (business concept 7.4, DMS-82, ADR
  * 0054): the destruction list, proposals with a reason, the decision by a second records manager (four eyes)
  * and the destruction itself.
  *
  * Destruction keeps a tombstone: the version row stays with status `Destroyed`, its number, approval,
  * rendition reference and checksum and a reduced metadata snapshot (system fields only); content, field
  * values, deviation report, working states, attachment links and the original are removed. Stored files no
  * other version references are deleted, and the objects (files and the rendition) are removed from object
  * storage by [[ObjectCleanup]] jobs after the commit, which respect Object Lock. The proposal item records
  * what was destroyed (checksums of the rendition, the approved content and the files), the audit trail gets
  * `document.destroyed` on the document, and the outbox `document.status-changed`, so that the search index
  * drops the version.
  *
  * A legal hold blocks: held versions cannot be proposed, and a hold set after the proposal blocks the
  * version on approval (checked here and by the database guard of V19). The retention is checked again on
  * approval with the rule as it is then. The database enforces the four eyes and that a version becomes
  * `Destroyed` only through an approved proposal and together with the removal of its content (migration
  * V33).
  *
  * Every method must run inside `Db.transact(tenant)`. Authorization (records managers, per document) is the
  * caller's part.
  */
final class DestructionService(audit: AuditTrail):
  import DestructionService.{*, given}

  // Retention rules

  def rules: IO[DestructionError, List[RetentionRule]] =
    sql(
      sql"""select key, name, period_months, trigger_event, from_year_end, updated_by, updated_at
            from dms.retention_rule order by key""".query[RuleRow].run().toList.map(toRule)
    )

  /** Creates or changes a rule; recorded in the audit trail with before and after. */
  def saveRule(key: String, input: RetentionRuleInput, actor: UserId): IO[DestructionError, RetentionRule] =
    val name     = input.name.trim
    val problems =
      Option
        .unless(key.matches(RetentionPeriod.KeyPattern))(s"key must match ${RetentionPeriod.KeyPattern}")
        .toList ++
        Option.when(name.isEmpty)("name is required") ++
        Option.when(name.length > 200)("name is longer than 200 characters") ++
        Option.unless(input.periodMonths >= 1 && input.periodMonths <= RetentionPeriod.MaxPeriodMonths)(
          s"periodMonths must be between 1 and ${RetentionPeriod.MaxPeriodMonths}"
        )
    for
      _      <- ZIO.unless(problems.isEmpty)(ZIO.fail(DestructionError.Invalid(problems)))
      before <- rules.map(_.find(_.key == key))
      _      <- sql(
        sql"""insert into dms.retention_rule (tenant_id, key, name, period_months, trigger_event, from_year_end,
                                              created_by, updated_by)
              values (dms.current_tenant(), $key, $name, ${input.periodMonths}, ${input.trigger.toString},
                      ${input.fromYearEnd}, ${actor.value}, ${actor.value})
              on conflict (tenant_id, key) do update
                set name = excluded.name, period_months = excluded.period_months,
                    trigger_event = excluded.trigger_event, from_year_end = excluded.from_year_end,
                    updated_at = now(), updated_by = excluded.updated_by""".update.run()
      )
      saved <- rules.map(_.find(_.key == key)).someOrFail(DestructionError.NotFound(s"rule $key"))
      _     <- record(
        actor,
        "retention-rule.saved",
        AuditObject("retention-rule", None, Some(key)),
        None,
        Json.Obj(
          "key"    -> Json.Str(key),
          "before" -> before.fold[Json](Json.Null)(ruleJson),
          "after"  -> ruleJson(saved)
        )
      )
    yield saved

  // Destruction list

  /** The versions out of force whose retention ended on `today` (tenant time zone), with legal hold and open
    * proposals marked, plus how many versions out of force have no rule or a running retention.
    */
  def destructionList: IO[DestructionError, DestructionList] =
    for
      known <- rules.map(_.map(r => r.key -> r).toMap)
      zone  <- tenantZone
      rows  <- sql(candidateRows(None))
      now   <- Clock.instant
      today  = now.atZone(zone).toLocalDate
      judged = rows.map(r => r -> judge(r, known, zone))
    yield DestructionList(
      judged.collect {
        case (r, Judgement.Ended(rule, ends)) if RetentionPeriod.ended(ends, today) =>
          candidate(r, rule, ends)
      },
      judged.count(_._2 == Judgement.NoRule),
      judged.count {
        case (_, Judgement.Running(_))     => true
        case (_, Judgement.Ended(_, ends)) => !RetentionPeriod.ended(ends, today)
        case _                             => false
      }
    )

  // Proposals

  /** Proposes the destruction of versions whose retention ended. Every version must be on the destruction
    * list, not under legal hold and not part of another open proposal; the reason is required.
    */
  def propose(
      versionIds: List[UUID],
      reason: String,
      actor: UserId
  ): IO[DestructionError, DestructionProposal] =
    val distinct = versionIds.distinct
    for
      why <- requireReason(reason, "reason")
      _   <- ZIO.when(distinct.isEmpty)(ZIO.fail(DestructionError.Invalid(List("versionIds is required"))))
      _   <- ZIO.when(distinct.size > MaxItems)(
        ZIO.fail(DestructionError.Invalid(List(s"at most $MaxItems versions can be proposed at once")))
      )
      list <- destructionList
      byId = list.candidates.map(c => c.versionId -> c).toMap
      chosen <- ZIO.foreach(distinct)(id =>
        byId.get(id) match
          case None => ZIO.fail(DestructionError.Invalid(List(s"version $id is not on the destruction list")))
          case Some(c) if c.underLegalHold =>
            ZIO.fail(DestructionError.Conflict(s"version ${c.version} of ${c.title} is under legal hold"))
          case Some(c) if c.pendingProposal.isDefined =>
            ZIO.fail(
              DestructionError.Conflict(
                s"version ${c.version} of ${c.title} is part of an open proposal already"
              )
            )
          case Some(c) => ZIO.succeed(c)
      )
      id = UUID.randomUUID()
      _ <- sql {
        val _ = sql"""insert into dms.destruction_proposal (id, tenant_id, reason, proposed_by)
                      values ($id, dms.current_tenant(), $why, ${actor.value})""".update.run()
        chosen.foreach(c =>
          sql"""insert into dms.destruction_item (tenant_id, proposal_id, version_id, document_id, retention_rule,
                                                  retention_ended_on)
                values (dms.current_tenant(), $id, ${c.versionId}, ${c.documentId.value}, ${c.rule.key},
                        ${java.sql.Date.valueOf(c.retentionEndsOn)})""".update.run()
        )
      }
      _ <- record(
        actor,
        "destruction.proposed",
        AuditObject("destruction-proposal", Some(id)),
        Some(why),
        Json.Obj(
          "versionIds" -> Json.Arr(Chunk.fromIterable(chosen.map(c => Json.Str(c.versionId.toString))))
        )
      )
      _ <- ZIO.foreachDiscard(chosen)(c =>
        record(
          actor,
          "document.destruction-proposed",
          AuditObject("document", Some(c.documentId.value), Some(c.version)),
          Some(why),
          Json.Obj(
            "proposalId"      -> Json.Str(id.toString),
            "versionId"       -> Json.Str(c.versionId.toString),
            "retentionRule"   -> Json.Str(c.rule.key),
            "retentionEndsOn" -> Json.Str(c.retentionEndsOn.toString)
          )
        )
      )
      created <- get(id)
    yield created

  /** Rejects an open proposal; nothing is destroyed. Four eyes: not by the proposer. */
  def reject(id: UUID, reason: String, actor: UserId): IO[DestructionError, DestructionProposal] =
    for
      why      <- requireReason(reason, "reason")
      proposal <- lockOpen(id, actor)
      _        <- sql {
        val _ = sql"""update dms.destruction_proposal
                      set status = 'Rejected', decided_by = ${actor.value}, decided_at = now(),
                          decision_reason = $why
                      where id = $id""".update.run()
        sql"""update dms.destruction_item set outcome = 'Rejected', outcome_at = now()
              where proposal_id = $id and outcome = 'Pending'""".update.run()
      }
      _ <- record(
        actor,
        "destruction.rejected",
        AuditObject("destruction-proposal", Some(id)),
        Some(why),
        Json.Obj("proposedBy" -> Json.Str(proposal.proposedBy.value))
      )
      _ <- ZIO.foreachDiscard(proposal.items)(i =>
        record(
          actor,
          "document.destruction-rejected",
          AuditObject("document", Some(i.documentId.value), Some(i.version)),
          Some(why),
          Json.Obj("proposalId" -> Json.Str(id.toString), "versionId" -> Json.Str(i.versionId.toString))
        )
      )
      rejected <- get(id)
    yield rejected

  /** Approves an open proposal and destroys its versions in the same transaction. Four eyes: not by the
    * proposer. A version that is under legal hold now, whose retention has not ended with the current rule or
    * whose status changed is not destroyed (`Blocked`, with the reason). Returns the decided proposal and the
    * objects to remove after the commit (enqueued as [[ObjectCleanup]] jobs already).
    */
  def approve(id: UUID, reason: String, actor: UserId): IO[DestructionError, DestructionProposal] =
    for
      why      <- requireReason(reason, "reason")
      proposal <- lockOpen(id, actor)
      known    <- rules.map(_.map(r => r.key -> r).toMap)
      zone     <- tenantZone
      _        <- sql(
        sql"""update dms.destruction_proposal
              set status = 'Approved', decided_by = ${actor.value}, decided_at = now(), decision_reason = $why
              where id = $id""".update.run()
      )
      outcomes <- ZIO.foreach(proposal.items)(i => execute(proposal, i, why, actor, known, zone))
      _        <- record(
        actor,
        "destruction.approved",
        AuditObject("destruction-proposal", Some(id)),
        Some(why),
        Json.Obj(
          "proposedBy" -> Json.Str(proposal.proposedBy.value),
          "destroyed"  -> Json.Num(outcomes.count(_ == ItemOutcome.Destroyed)),
          "blocked"    -> Json.Num(outcomes.count(_ == ItemOutcome.Blocked))
        )
      )
      approved <- get(id)
    yield approved

  def get(id: UUID): IO[DestructionError, DestructionProposal] =
    sql(proposals(Some(id), None).headOption)
      .someOrFail(DestructionError.NotFound(s"destruction proposal $id does not exist"))

  /** Proposals, the latest first, optionally of one status. */
  def list(status: Option[ProposalStatus]): IO[DestructionError, List[DestructionProposal]] =
    sql(proposals(None, status))

  /** The destruction records of a document's destroyed versions (evidence report). */
  def recordsOf(document: DocumentId): IO[DestructionError, List[DestructionRecord]] =
    for
      ids <- sql(
        sql"""select distinct proposal_id from dms.destruction_item
              where document_id = ${document.value} and outcome = 'Destroyed'""".query[UUID].run().toList
      )
      found <- ZIO.foreach(ids)(get)
    yield found.flatMap(p =>
      p.items
        .filter(i => i.documentId == document && i.outcome == ItemOutcome.Destroyed)
        .map(DestructionRecord(p, _))
    )

  // Destruction of one version

  private def execute(
      proposal: DestructionProposal,
      item: DestructionItem,
      reason: String,
      actor: UserId,
      known: Map[String, RetentionRule],
      zone: ZoneId
  ): IO[DestructionError, ItemOutcome] =
    for
      now     <- Clock.instant
      current <- sql {
        // The document row first, like the legal hold guard: a hold being added concurrently waits or is seen.
        val _ =
          sql"select id from dms.document where id = ${item.documentId.value} for update".query[UUID].run()
        candidateRows(Some(item.versionId)).headOption
      }
      blocked = current match
        case None                        => Some("the version is no longer out of force")
        case Some(r) if r.underLegalHold => Some("LegalHold")
        case Some(r)                     =>
          judge(r, known, zone) match
            case Judgement.Ended(_, ends) if RetentionPeriod.ended(ends, now.atZone(zone).toLocalDate) => None
            case Judgement.NoRule => Some("NoRule")
            case _                => Some("RetentionRunning")
      outcome <- (blocked, current) match
        case (Some(why), _)    => block(proposal, item, why, actor).as(ItemOutcome.Blocked)
        case (None, Some(row)) => destroy(proposal, item, row, reason, actor).as(ItemOutcome.Destroyed)
        case (None, None)      => ZIO.succeed(ItemOutcome.Blocked)
    yield outcome

  private def block(proposal: DestructionProposal, item: DestructionItem, why: String, actor: UserId) =
    sql(
      sql"""update dms.destruction_item set outcome = 'Blocked', outcome_detail = $why, outcome_at = now()
            where proposal_id = ${proposal.id} and version_id = ${item.versionId}""".update.run()
    ) *> record(
      actor,
      "document.destruction-blocked",
      AuditObject("document", Some(item.documentId.value), Some(item.version)),
      None,
      Json.Obj(
        "proposalId" -> Json.Str(proposal.id.toString),
        "versionId"  -> Json.Str(item.versionId.toString),
        "blockedBy"  -> Json.Str(why)
      )
    )

  private def destroy(
      proposal: DestructionProposal,
      item: DestructionItem,
      row: CandidateRow,
      reason: String,
      actor: UserId
  ): IO[DestructionError, Unit] =
    for
      facts <- sql {
        val version = item.versionId
        val v       =
          sql"""select rendition_key, rendition_version, rendition_sha256 from dms.document_version
                where id = $version for update"""
            .query[(Option[String], Option[String], Option[String])]
            .run()
            .head
        val content =
          sql"select content_sha256 from dms.workflow_case where version_id = $version"
            .query[String]
            .run()
            .headOption
        val states =
          sql"select count(*) from dms.document_working_state where version_id = $version"
            .query[Long]
            .run()
            .head
        val linked =
          sql"""select a.name, f.id, f.sha256, 'Attachment' from dms.document_version_attachment a
                join dms.document_attachment_file f on f.id = a.file_id where a.version_id = $version
                union all
                select o.name, f.id, f.sha256, 'Original' from dms.document_version_file o
                join dms.document_attachment_file f on f.id = o.file_id where o.version_id = $version"""
            .query[(String, UUID, String, String)]
            .run()
            .toList
        if row.status != "Archived" then
          val _ = sql"""update dms.document_version
                        set status = 'Archived', status_changed_at = now(), status_changed_by = ${actor.value},
                            updated_at = now()
                        where id = $version""".update.run()
        val _ = sql"""update dms.document_version
              set status = 'Destroyed', content = ${Jsonb(TombstoneContent)}, field_values = ${Jsonb("{}")},
                  deviation_report = ${Jsonb(TombstoneDeviations)},
                  metadata_snapshot = case when metadata_snapshot is null then null
                    else jsonb_build_object(
                      'values', coalesce((select jsonb_object_agg(e.key, e.value)
                                          from jsonb_each(metadata_snapshot -> 'values') e
                                          where e.key in ('documentId', 'documentNumber', 'documentClass',
                                                          'version', 'template', 'templateVersion',
                                                          'renditionChecksum', 'retentionRule')), '{}'::jsonb),
                      'takenAt', metadata_snapshot -> 'takenAt') end,
                  content_removed_at = now(), content_removed_by = ${actor.value}, status_changed_at = now(),
                  status_changed_by = ${actor.value}, updated_at = now()
              where id = $version""".update.run()
        val _ = sql"delete from dms.document_working_state where version_id = $version".update.run()
        val _ = sql"delete from dms.document_version_attachment where version_id = $version".update.run()
        val _ = sql"delete from dms.document_version_file where version_id = $version".update.run()
        // Files no version references any more are deleted; their objects follow after the commit.
        val released = linked.map(_._2).distinct.flatMap { fileId =>
          val referenced =
            sql"""select exists (select 1 from dms.document_version_attachment where file_id = $fileId)
                      or exists (select 1 from dms.document_version_file where file_id = $fileId)"""
              .query[Boolean]
              .run()
              .head
          if referenced then None
          else
            sql"""delete from dms.document_attachment_file where id = $fileId
                  returning object_key, object_version"""
              .query[(String, String)]
              .run()
              .headOption
              .map(fileId -> _)
        }
        (v, content, states, linked, released)
      }
      (rendition, contentSha, states, linked, released) = facts
      releasedIds                                       = released.map(_._1).toSet
      files                                             = linked
        .distinctBy(l => (l._2, l._4))
        .map((name, fileId, sha, role) => DestroyedFile(name, sha, role, releasedIds.contains(fileId)))
      objects = released.map((_, o) => o) ++ rendition._1.zip(rendition._2).toList
      _ <- ZIO.foreachDiscard(objects)((key, version) =>
        ObjectCleanup
          .enqueue(ObjectCleanup.Request(Some(item.documentId.value), key, version, "destruction"))
          .mapError(DestructionError.Database(_))
      )
      _ <- sql(
        sql"""update dms.destruction_item
              set outcome = 'Destroyed', outcome_at = now(), rendition_sha256 = ${rendition._3},
                  content_sha256 = $contentSha, files = ${Jsonb(files.toJson)}
              where proposal_id = ${proposal.id} and version_id = ${item.versionId}""".update.run()
      )
      _ <- record(
        actor,
        RetentionEvents.Destroyed,
        AuditObject("document", Some(item.documentId.value), Some(item.version)),
        Some(reason),
        Json.Obj(
          "versionId"         -> Json.Str(item.versionId.toString),
          "version"           -> Json.Str(item.version),
          "from"              -> Json.Str(row.status),
          "title"             -> Json.Str(row.title),
          "documentNumber"    -> row.documentNumber.fold[Json](Json.Null)(Json.Str(_)),
          "proposalId"        -> Json.Str(proposal.id.toString),
          "proposedBy"        -> Json.Str(proposal.proposedBy.value),
          "proposalReason"    -> Json.Str(proposal.reason),
          "retentionRule"     -> Json.Str(item.retentionRule),
          "retentionEndedOn"  -> Json.Str(item.retentionEndedOn.toString),
          "renditionSha256"   -> rendition._3.fold[Json](Json.Null)(Json.Str(_)),
          "contentSha256"     -> contentSha.fold[Json](Json.Null)(Json.Str(_)),
          "workingStates"     -> Json.Num(states),
          "files"             -> files.toJsonAST.getOrElse(Json.Arr()),
          "removedObjectsDue" -> Json.Num(objects.size)
        )
      )
      _ <- Outbox
        .publish(
          "document",
          item.documentId.value,
          "document.status-changed",
          Jsonb(
            Json
              .Obj(
                "documentId"     -> Json.Str(item.documentId.value.toString),
                "versionId"      -> Json.Str(item.versionId.toString),
                "version"        -> Json.Str(item.version),
                "status"         -> Json.Str("Destroyed"),
                "previousStatus" -> Json.Str(row.status)
              )
              .toJson
          )
        )
        .mapError(DestructionError.Database(_))
    yield ()

  // Helpers

  private def lockOpen(id: UUID, actor: UserId): IO[DestructionError, DestructionProposal] =
    for
      status <- sql(
        sql"select status from dms.destruction_proposal where id = $id for update"
          .query[String]
          .run()
          .headOption
      ).someOrFail(DestructionError.NotFound(s"destruction proposal $id does not exist"))
      _ <- ZIO.unless(status == ProposalStatus.Proposed.toString)(
        ZIO.fail(DestructionError.Conflict(s"destruction proposal $id was ${status.toLowerCase} already"))
      )
      proposal <- get(id)
      _        <- ZIO.when(proposal.proposedBy == actor)(
        ZIO.fail(
          DestructionError.SamePerson(
            "four eyes: a destruction is approved or rejected by another records manager than the one who proposed it"
          )
        )
      )
    yield proposal

  private def record(
      actor: UserId,
      action: String,
      target: AuditObject,
      reason: Option[String],
      details: Json.Obj
  ): IO[DestructionError, Unit] =
    audit
      .record(
        AuditEvent(
          Actor(
            actor.value,
            if actor.value.startsWith("system:") then ActorKind.Technical else ActorKind.Person
          ),
          action,
          target,
          if actor.value.startsWith("system:") then Channel.Automation else Channel.Api,
          Jsonb(details.toJson),
          reason = reason
        )
      )
      .mapError(DestructionError.Database(_))
      .unit

object DestructionService:
  val layer: URLayer[AuditTrail, DestructionService] = ZLayer.fromFunction(DestructionService(_))

  /** Most versions in one proposal. */
  val MaxItems: Int = 500

  val MaxReasonLength: Int = 4000

  /** Empty structured content in the stored format (ADR 0007), the content of a destroyed version. */
  val TombstoneContent: String = """{"format":1,"sections":[]}"""

  /** The empty deviation report in the stored format (V10). */
  val TombstoneDeviations: String = """{"format":1,"deviations":[]}"""

  private[core] given JsonCodec[DestroyedFile] = DeriveJsonCodec.gen

  private[core] given DbCodec[LocalDate] =
    DbCodec[java.sql.Date].biMap(d => if d == null then null else d.toLocalDate, java.sql.Date.valueOf)

  private type RuleRow = (String, String, Int, String, Boolean, String, OffsetDateTime)

  private def toRule(r: RuleRow): RetentionRule =
    val (key, name, months, trigger, yearEnd, by, at) = r
    RetentionRule(key, name, months, RetentionTrigger.valueOf(trigger), yearEnd, UserId(by), at.toInstant)

  private def ruleJson(r: RetentionRule): Json =
    Json.Obj(
      "name"         -> Json.Str(r.name),
      "periodMonths" -> Json.Num(r.periodMonths),
      "trigger"      -> Json.Str(r.trigger.toString),
      "fromYearEnd"  -> Json.Bool(r.fromYearEnd)
    )

  /** A version out of force with what its retention depends on. */
  final case class CandidateRow(
      versionId: UUID,
      documentId: UUID,
      title: String,
      documentNumber: Option[String],
      classKey: String,
      major: Int,
      minor: Int,
      status: String,
      ruleKey: Option[String],
      releasedAt: Option[OffsetDateTime],
      documentCreatedAt: OffsetDateTime,
      outOfForceAt: Option[OffsetDateTime],
      underLegalHold: Boolean,
      pendingProposal: Option[UUID]
  ) derives DbCodec:
    def version: String = s"$major.$minor"

  /** Approved versions out of force (Superseded, Withdrawn after being valid, Archived), optionally one. The
    * rule key comes from the metadata snapshot. Out of force since the withdrawal or the successor's
    * publication; for archived versions without either, since the last status change.
    */
  private[core] def candidateRows(version: Option[UUID])(using DbTx): List[CandidateRow] =
    sql"""select v.id, d.id, d.title, d.document_number, c.key, v.major, v.minor, v.status,
                 v.metadata_snapshot #>> '{values,retentionRule,value}', v.released_at, d.created_at,
                 coalesce(v.withdrawn_at, s.published_at, v.status_changed_at),
                 dms.document_under_legal_hold(d.id),
                 (select i.proposal_id from dms.destruction_item i
                  where i.version_id = v.id and i.outcome = 'Pending')
          from dms.document_version v
          join dms.document d on d.id = v.document_id
          join dms.document_class c on c.id = d.class_id
          left join dms.document_version s on s.id = v.superseded_by
          where v.status in ('Superseded', 'Withdrawn', 'Archived') and v.released_at is not null
            and (cast($version as uuid) is null or v.id = cast($version as uuid))
          order by d.title, d.id, v.major, v.minor""".query[CandidateRow].run().toList

  private enum Judgement:
    case NoRule
    case Running(rule: RetentionRule)
    case Ended(rule: RetentionRule, endsOn: LocalDate)

  private def judge(r: CandidateRow, rules: Map[String, RetentionRule], zone: ZoneId): Judgement =
    r.ruleKey.flatMap(rules.get) match
      case None       => Judgement.NoRule
      case Some(rule) =>
        RetentionPeriod.triggeredAt(
          rule.trigger,
          r.outOfForceAt.map(_.toInstant),
          r.releasedAt.map(_.toInstant),
          r.documentCreatedAt.toInstant
        ) match
          case None     => Judgement.Running(rule)
          case Some(at) => Judgement.Ended(rule, RetentionPeriod.endsOn(rule, at, zone))

  private def candidate(r: CandidateRow, rule: RetentionRule, ends: LocalDate): DestructionCandidate =
    DestructionCandidate(
      DocumentId(r.documentId),
      r.versionId,
      r.title,
      r.documentNumber,
      r.classKey,
      r.version,
      r.status,
      rule,
      r.outOfForceAt.map(_.toInstant),
      ends,
      r.underLegalHold,
      r.pendingProposal
    )

  private[core] def tenantZone: IO[DestructionError, ZoneId] =
    sql(
      sql"select time_zone from dms.tenant where id = dms.current_tenant()".query[String].run().headOption
    ).map(_.flatMap(z => Try(ZoneId.of(z)).toOption).getOrElse(ZoneId.of("Europe/Berlin")))

  private def requireReason(reason: String, field: String): IO[DestructionError, String] =
    val trimmed = reason.trim
    if trimmed.isEmpty then ZIO.fail(DestructionError.Invalid(List(s"$field is required")))
    else if trimmed.length > MaxReasonLength then
      ZIO.fail(DestructionError.Invalid(List(s"$field is longer than $MaxReasonLength characters")))
    else ZIO.succeed(trimmed)

  private type ProposalRow =
    (UUID, String, String, String, OffsetDateTime, Option[String], Option[OffsetDateTime], Option[String])

  final case class ItemRow(
      proposalId: UUID,
      versionId: UUID,
      documentId: UUID,
      title: String,
      documentNumber: Option[String],
      major: Int,
      minor: Int,
      retentionRule: String,
      retentionEndedOn: LocalDate,
      outcome: String,
      outcomeDetail: Option[String],
      outcomeAt: Option[OffsetDateTime],
      renditionSha256: Option[String],
      contentSha256: Option[String],
      files: Jsonb
  ) derives DbCodec

  private def proposals(id: Option[UUID], status: Option[ProposalStatus])(using
      DbTx
  ): List[DestructionProposal] =
    val state = status.map(_.toString)
    val heads =
      sql"""select id, status, reason, proposed_by, proposed_at, decided_by, decided_at, decision_reason
            from dms.destruction_proposal
            where (cast($id as uuid) is null or id = cast($id as uuid))
              and (cast($state as text) is null or status = cast($state as text))
            order by proposed_at desc, id
            limit 200""".query[ProposalRow].run().toList
    heads.map { case (pid, st, reason, by, at, decidedBy, decidedAt, decisionReason) =>
      val items =
        sql"""select i.proposal_id, i.version_id, i.document_id, d.title, d.document_number, v.major, v.minor,
                       i.retention_rule, i.retention_ended_on, i.outcome, i.outcome_detail, i.outcome_at,
                       i.rendition_sha256, i.content_sha256, i.files
                from dms.destruction_item i
                join dms.document d on d.id = i.document_id
                join dms.document_version v on v.id = i.version_id
                where i.proposal_id = $pid
                order by d.title, d.id, v.major, v.minor""".query[ItemRow].run().toList
      DestructionProposal(
        pid,
        ProposalStatus.valueOf(st),
        reason,
        UserId(by),
        at.toInstant,
        for
          b <- decidedBy
          a <- decidedAt
          r <- decisionReason
        yield ProposalDecision(UserId(b), a.toInstant, r),
        items.map(toItem)
      )
    }

  private def toItem(r: ItemRow): DestructionItem =
    DestructionItem(
      r.versionId,
      DocumentId(r.documentId),
      r.title,
      r.documentNumber,
      s"${r.major}.${r.minor}",
      r.retentionRule,
      r.retentionEndedOn,
      ItemOutcome.valueOf(r.outcome),
      r.outcomeDetail,
      r.outcomeAt.map(_.toInstant),
      r.renditionSha256,
      r.contentSha256,
      r.files.value.fromJson[List[DestroyedFile]].getOrElse(Nil)
    )

  private def sql[A](f: DbTx ?=> A): IO[DestructionError, A] =
    Db.sql(f).mapError(DestructionError.Database(_))
