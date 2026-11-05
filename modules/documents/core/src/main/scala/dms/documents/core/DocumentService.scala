package dms.documents.core

import com.augustnagro.magnum.DbTx
import dms.audit.*
import dms.db.queue.Outbox
import dms.db.{Db, DbError, Jsonb}
import dms.documents.*
import dms.documents.core.DocumentJson.given
import dms.documents.core.MetadataJson.given
import dms.documents.metadata.*
import dms.kernel.{DocumentId, TemplateId, UserId, VersionNo}
import dms.templates.{
  WordField,
  WordFieldSource,
  WordFieldType,
  WordFields,
  DeviationReport,
  DeviationRequest,
  EditRules,
  SectionId,
  TemplateContent,
  TemplateStatus,
  TemplateVersion
}
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate, ZoneId}
import java.util.UUID

/** Why a version was not withdrawn, for clients to tell the outcomes of a race apart (DMS-143, ADR 0059).
  *
  * @param code
  *   the code of the API error
  */
enum WithdrawalRefusal(val code: String):
  /** The publication got there first: the version is valid (or was valid) now. */
  case AlreadyPublished extends WithdrawalRefusal("version_published")

  /** The valid-from date has come and the publication job has not failed for good: it publishes the version.
    */
  case Due extends WithdrawalRefusal("version_due")

  /** Another withdrawal got there first. */
  case AlreadyWithdrawn extends WithdrawalRefusal("version_withdrawn")

  /** The version was never approved. */
  case NotApproved extends WithdrawalRefusal("version_not_approved")

enum DocumentError:
  case NotFound(message: String)

  /** The request is invalid, e.g. a content change that breaks the edit rules of the template. */
  case Invalid(problems: List[String])

  /** Field values do not fit the class profile. */
  case InvalidValues(issues: List[FieldIssue])

  /** A status change is blocked because required fields of `target` are missing or values are invalid. */
  case Incomplete(target: DocumentStatus, issues: List[FieldIssue])

  case Lifecycle(error: LifecycleError)

  /** A concurrent change got there first, or the version is not in a status that allows the change. */
  case Conflict(message: String)

  /** An approved version cannot be withdrawn (any more), see [[DocumentService.withdrawApproved]]. */
  case WithdrawalRefused(refusal: WithdrawalRefusal, message: String)

  case Database(error: DbError)

  def describe: String = this match
    case NotFound(m)                => m
    case Invalid(p)                 => p.mkString("; ")
    case InvalidValues(i)           => i.map(i => s"${i.field}: ${i.kind.message}").mkString("; ")
    case Incomplete(target, issues) =>
      s"the document cannot move to $target: " + issues
        .map(i => s"${i.field}: ${i.kind.message}")
        .mkString("; ")
    case Lifecycle(LifecycleError.TransitionNotAllowed(from, event)) =>
      s"$event is not possible in status $from"
    case Lifecycle(LifecycleError.LegalHoldActive)                => "a legal hold is active"
    case Lifecycle(LifecycleError.SegregationOfDutiesViolated(_)) =>
      "segregation of duties: the author of a version cannot approve it"
    case Conflict(m)             => m
    case WithdrawalRefused(_, m) => m
    case Database(e)             => s"database error: $e"

/** Access to templates (module `templates`) for documents: the catalog of released versions, the content
  * check, computed fields and the stored content format. Implemented by the application, which wires the
  * modules together.
  */
trait TemplateSource extends ContentCodec:
  /** A template version; fails with `NotFound` if it does not exist. */
  def version(id: TemplateId, version: Int): IO[DocumentError, TemplateVersion]

  /** Problems of `content` against the class profile the template was designed against, for display. */
  def check(template: TemplateVersion, content: TemplateContent): IO[DocumentError, List[String]]

  /** The values of the computed fields of `content` for the given field values. */
  def computed(
      template: TemplateVersion,
      content: TemplateContent,
      values: Map[String, MetadataValue]
  ): IO[DocumentError, Map[String, MetadataValue]]

  /** The computed fields of `content` that have no value for the given field values because they read fields
    * without value, each with those fields (DMS-83). A document cannot be approved while there are any.
    */
  def missingComputed(
      template: TemplateVersion,
      content: TemplateContent,
      values: Map[String, MetadataValue]
  ): IO[DocumentError, Map[String, List[String]]]

/** Request to create a document. `values` are the entered field values with their origin (normally `Manual`;
  * a business system filling master data uses `System(source)`). A file document (DMS-115) gets its content
  * by an upload into its draft ([[DocumentService.setFile]]); the template supplies class and fields.
  */
final case class NewDocument(
    templateId: TemplateId,
    templateVersion: Int,
    title: String,
    values: Map[String, FieldEntry],
    kind: DocumentKind = DocumentKind.Structured
)

/** Change of a draft: the entered values replace the previous ones; content, if given, replaces the content.
  * The title defaults to the current title.
  */
final case class DraftChange(
    title: Option[String],
    values: Map[String, FieldEntry],
    content: Option[TemplateContent]
)

/** A document with all its versions and the effective profile of its class version.
  *
  * @param zone
  *   the time zone of the tenant; scheduled publications switch at 00:00 there (DMS-45)
  */
final case class DocumentDetails(
    document: Document,
    versions: List[DocumentVersion],
    profile: MetadataProfile,
    zone: ZoneId = Publication.DefaultZone
):
  def open: Option[DocumentVersion] = versions.find(v => Versioning.isOpen(v.status))

  /** True if every version was discarded (DMS-129): a first version discarded before it was ever approved.
    * The document is kept as a record but is not shown or opened any more.
    */
  def discarded: Boolean = versions.nonEmpty && versions.forall(_.status == DocumentStatus.Discarded)

  /** True if the document's content is gone (DMS-82, ADR 0054): at least one version was destroyed and all
    * others were destroyed or discarded. The document is kept as a tombstone (audit trail, evidence report,
    * number) but disappears from lists and is not opened any more.
    */
  def destroyed: Boolean =
    versions.exists(_.status == DocumentStatus.Destroyed) &&
      versions.forall(v => v.status == DocumentStatus.Destroyed || v.status == DocumentStatus.Discarded)

  /** Discarded or destroyed: nothing of the document is shown or opened any more. */
  def gone: Boolean = discarded || destroyed

  /** Whether a number can be assigned manually now (see [[DocumentService.assignNumber]]): no version is in
    * review or approval, and an existing number is not frozen in a submitted version yet.
    */
  def numberAssignable: Boolean =
    open.forall(_.status == DocumentStatus.Draft) &&
      (document.number.isEmpty || versions.forall(_.status == DocumentStatus.Draft))

  /** The version readers of `v` are pointed to (see [[Publication.successor]]). */
  def successor(v: DocumentVersion): Option[DocumentVersion] = Publication.successor(v, versions)

  /** When an approved version is published automatically: 00:00 of its valid-from date in the tenant's time
    * zone (at once if it has none).
    */
  def publicationDue(v: DocumentVersion): Option[Instant] =
    Option.when(v.status == DocumentStatus.Approved)(
      v.validFrom.fold(Instant.EPOCH)(Publication.dueAt(_, zone))
    )

  /** The automatic comparison of a minor version with its basis (see [[EditorialCheck]]). */
  def editorialCheck(v: DocumentVersion, threshold: Double): Option[EditorialCheck] =
    EditorialCheck.of(v, versions, threshold)

/** The outcome of discarding a draft (DMS-129, ADR 0050).
  *
  * @param version
  *   the draft, now `Discarded`
  * @param documentDiscarded
  *   true if it was the document's only version that was not discarded (a first version never approved): the
  *   document disappears; false for a new version of an approved document, whose valid version stays
  * @param validVersion
  *   the valid version of the document after the discard (Published or ReviewDue), if any
  * @param releasedFiles
  *   stored files no version references any more, deleted; their objects are removed by a job
  * @param keptFiles
  *   unreferenced files kept because the document is under legal hold
  */
final case class DiscardedDraft(
    version: DocumentVersion,
    documentDiscarded: Boolean,
    validVersion: Option[DocumentVersion],
    removedAttachments: Int,
    releasedFiles: List[AttachmentFile],
    keptFiles: List[AttachmentFile]
)

/** Settings of the documents module (DMS-46).
  *
  * @param editorialChangeThreshold
  *   share of changed text (0 to 1) above which the automatic comparison warns about an editorial (minor)
  *   version
  * @param minorVersionsRequireAcknowledgement
  *   whether publishing a minor version asks readers for a new acknowledgement, unless the version decides
  *   otherwise when it is created; major versions do by default
  */
final case class DocumentSettings(
    editorialChangeThreshold: Double = 0.1,
    minorVersionsRequireAcknowledgement: Boolean = false
)

object DocumentSettings:
  val default: DocumentSettings = DocumentSettings()

/** A version in force at a moment (US-09.1, DMS-53), with its document and what authorization needs: the
  * field values (organizational unit, confidentiality) and the author.
  *
  * @param classPath
  *   class keys of the document's class from the root
  * @param status
  *   the status of the version now, e.g. Superseded for a version that was replaced since
  * @param period
  *   when the version was in force (see [[Validity]])
  */
final case class ValidVersion(
    document: Document,
    classPath: List[String],
    versionId: UUID,
    number: VersionNo,
    status: DocumentStatus,
    scope: String,
    values: Map[String, FieldEntry],
    createdBy: UserId,
    releasedBy: Option[UserId],
    releasedAt: Option[Instant],
    validFrom: Option[java.time.LocalDate],
    period: ValidityPeriod
):
  def label: String = Versioning.label(number, status, 1)

  def metadata: Map[String, MetadataValue] = values.map((k, e) => k -> e.value)

/** A version valid now (Published or ReviewDue), with what the compliance cockpit (US-15.1, DMS-59) reports
  * on and what authorization needs.
  *
  * @param reviewDueOn
  *   the review date (ADR 0030); `None` if the version has no periodic review
  * @param requiresAcknowledgement
  *   whether its readers are asked to acknowledge it (ADR 0027)
  * @param scope
  *   validity scope of the version; empty: unrestricted
  * @param firstPublishedAt
  *   the first publication of any version of the document (library, DMS-119: "new" documents)
  */
final case class CurrentVersion(
    document: Document,
    classPath: List[String],
    versionId: UUID,
    number: VersionNo,
    status: DocumentStatus,
    values: Map[String, FieldEntry],
    createdBy: UserId,
    releasedBy: Option[UserId],
    validFrom: Option[java.time.LocalDate],
    publishedAt: Option[Instant],
    reviewDueOn: Option[java.time.LocalDate],
    requiresAcknowledgement: Boolean,
    scope: String = "",
    firstPublishedAt: Option[Instant] = None
):
  def label: String = Versioning.label(number, status, 1)

  lazy val metadata: Map[String, MetadataValue] = values.map((k, e) => k -> e.value)

/** An approved version waiting for its publication at `dueAt` (00:00 of its valid-from date in the tenant's
  * time zone).
  */
final case class ScheduledPublication(documentId: DocumentId, versionId: UUID, dueAt: Instant)

/** Creates documents from released templates and manages their versions (business concept 4.3, 5.1, 5.2).
  *
  * Every method must run inside `Db.transact(tenant)`. Field values are validated against the class profile
  * version, computed fields are evaluated with the template, and the origin of every value is stored. Only
  * drafts change; status changes follow [[Lifecycle]] and are blocked while required fields of the target
  * status are missing. Every change is recorded in the audit trail, and creation and status changes are
  * published to the outbox, both in the same transaction. Authorization is the caller's job.
  */
final class DocumentService(
    audit: AuditTrail,
    classes: DocumentClassService,
    templates: TemplateSource,
    val settings: DocumentSettings = DocumentSettings.default
):
  import DocumentService.*

  /** The released template version and the profile of its class version, e.g. to authorize a creation. */
  def templateContext(id: TemplateId, version: Int): IO[DocumentError, (TemplateVersion, MetadataProfile)] =
    for
      template <- released(id, version)
      profile  <- profile(DocumentClassId(template.documentClassId), template.documentClassVersion)
    yield (template, profile)

  /** Creates a document with version 1.0 as a draft (working state 1) from a released template version. The
    * content is the template content; the document is bound to the template version and, through the content,
    * to the clause versions.
    */
  def create(request: NewDocument, actor: UserId): IO[DocumentError, (Document, DocumentVersion)] =
    for
      (template, profile) <- templateContext(request.templateId, request.templateVersion)
      id     = DocumentId.random()
      number = Versioning.first
      now       <- Clock.instant
      numbered  <- documentNumber(profile, request.values, now)
      retention <- retentionEntry(profile.classId, None, None)
      values    <- prepare(
        profile,
        template,
        template.content,
        request.values,
        request.title,
        id,
        number,
        numbered.map(_._2),
        retention
      )
      document = Document(
        id,
        profile.classId,
        profile.version,
        request.title.trim,
        ownerOf(values),
        actor,
        template.templateId,
        template.version,
        now,
        numbered.map(_._2),
        request.kind
      )
      version = DocumentVersion(
        UUID.randomUUID(),
        id,
        number,
        1,
        DocumentStatus.Draft,
        ChangeKind.Major,
        None,
        Versioning.scopeKey(metadataOf(values)),
        profile.version,
        template.templateId,
        template.version,
        template.content,
        values,
        None,
        None,
        actor,
        now,
        now,
        None,
        None
      )
      _ <- sql {
        DocumentStore.insertDocument(document)
        DocumentStore.insertVersion(version, templates)
        DocumentStore.insertWorkingState(version, actor, templates)
      }
      _ <- record(
        actor,
        "document.created",
        version,
        Json.Obj(
          "title"           -> Json.Str(document.title),
          "templateId"      -> Json.Str(template.templateId.value.toString),
          "templateVersion" -> Json.Num(template.version),
          "kind"            -> Json.Str(request.kind.toString),
          "classId"         -> Json.Str(profile.classId.value.toString),
          "classVersion"    -> Json.Num(profile.version),
          "origins"         -> origins(values),
          "documentNumber"  -> numbered.fold[Json](Json.Null)(n => Json.Str(n._2)),
          "numberRange"     -> numbered.fold[Json](Json.Null)(n => Json.Str(n._1.key))
        )
      )
      _ <- publish("document.created", document, version)
    yield (document, version)

  /** The number of a new document from the number range of its class (DMS-113), with that range; `None` if
    * the class has none. Takes the counter inside the creating transaction (see [[DocumentNumbering.next]]).
    */
  private def documentNumber(
      profile: MetadataProfile,
      entered: Map[String, FieldEntry],
      now: Instant
  ): IO[DocumentError, Option[(NumberRange, String)]] =
    sql(DocumentNumbering.rangeOf(profile)).flatMap {
      case None        => ZIO.none
      case Some(range) =>
        val unit = entered.get(UnitField).collect { case FieldEntry(MetadataValue.Text(t), _) => t }
        for
          zone <- sql(DocumentStore.tenantZone)
          _    <- ZIO.when(range.pattern.usesUnit && unit.forall(NumberPattern.unitSegment(_).isEmpty))(
            ZIO.fail(
              DocumentError.InvalidValues(
                List(
                  FieldIssue(
                    UnitField,
                    profile.field(UnitField).fold(UnitField)(_.definition.label),
                    IssueKind.MissingRequired(DocumentStatus.Draft)
                  )
                )
              )
            )
          )
          number <- sql(DocumentNumbering.next(range, zone, now, unit))
            .flatMap(r => ZIO.fromEither(r).mapError(p => DocumentError.Invalid(List(p))))
        yield Some(range -> number)
    }

  /** Assigns a document number manually (DMS-113): for a document without number at any time (except while a
    * version is in review or approval, whose values are frozen), and replacing a number only while every
    * version is a draft, because a submitted or approved version carries it. The number must be free in the
    * tenant (409) and a reason is required (422). The open draft gets the number as a new working state;
    * later versions take it over. Recorded in the audit trail with reason, previous and new number.
    */
  def assignNumber(
      id: DocumentId,
      number: String,
      reason: String,
      actor: UserId
  ): IO[DocumentError, Document] =
    val why = reason.trim
    for
      valid <- ZIO
        .fromEither(DocumentNumber.validate(number.trim))
        .mapError(m => DocumentError.Invalid(List(m)))
      _ <- ZIO.when(why.isEmpty)(ZIO.fail(DocumentError.Invalid(List("a reason is required"))))
      _ <- ZIO.when(why.length > MaxReasonLength)(
        ZIO.fail(DocumentError.Invalid(List(s"the reason must have at most $MaxReasonLength characters")))
      )
      document <- sql(DocumentStore.lockDocument(id)).someOrFail(documentNotFound(id))
      versions <- sql(DocumentStore.versions(id, templates))
      _        <- ZIO.when(document.number.contains(valid))(
        ZIO.fail(DocumentError.Invalid(List(s"the document already has the number $valid")))
      )
      _ <- ZIO.when(document.number.isDefined && versions.exists(_.status != DocumentStatus.Draft))(
        ZIO.fail(
          DocumentError.Conflict(
            s"the number ${document.number.getOrElse("")} is frozen: a version of the document was submitted"
          )
        )
      )
      open = versions.find(v => Versioning.isOpen(v.status))
      _ <- ZIO.foreachDiscard(open.filter(_.status != DocumentStatus.Draft))(v =>
        ZIO.fail(
          DocumentError.Conflict(
            s"version ${v.label} is ${v.status}; its values cannot change until it is decided"
          )
        )
      )
      taken <- sql(NumberRangeStore.documentWithNumber(valid))
      _     <- ZIO.when(taken.exists(_ != id))(
        ZIO.fail(DocumentError.Conflict(s"the number $valid is already assigned to another document"))
      )
      _ <- sql(NumberRangeStore.setDocumentNumber(id, valid))
        .mapError(conflict(s"the number $valid is already assigned to another document"))
      draft <- ZIO.foreach(open)(numberDraft(_, valid, actor))
      _     <- ZIO.foreachDiscard(draft.orElse(versions.lastOption))(v =>
        record(
          actor,
          "document.number-assigned",
          v,
          Json.Obj(
            "before" -> document.number.fold[Json](Json.Null)(Json.Str(_)),
            "after"  -> Json.Str(valid),
            "reason" -> Json.Str(why),
            "manual" -> Json.Bool(true)
          ),
          Some(why)
        )
      )
    yield document.copy(number = Some(valid))

  /** The open draft with the document number as a new working state (the platform's value of the core field).
    */
  private def numberDraft(open: DocumentVersion, number: String, actor: UserId) =
    for
      current <- sql(DocumentStore.lockVersion(open.id, templates)).someOrFail(versionNotFound(open.id))
      entry = FieldEntry(MetadataValue.Text(number), ValueOrigin.Platform)
      next  = current.copy(
        workingState = current.workingState + 1,
        values = current.values + (CoreFields.DocumentNumber -> entry),
        rendition = None
      )
      _ <- sql(DocumentStore.updateDraft(next, templates))
        .filterOrFail(identity)(changedConcurrently(next))
      _ <- sql(DocumentStore.insertWorkingState(next, actor, templates))
    yield next

  /** Numbers an existing document that has no number from the number range of its class (backfill, DMS-116,
    * ADR 0040): the counter of the period of the document's creation year is taken as on creation (locked
    * until the transaction ends, numbers taken manually skipped), so documents numbered one after another in
    * creation order get consecutive numbers. The open draft gets the number as a new working state; approved
    * versions keep their frozen values and later versions take the number over. Recorded in the audit trail
    * as `document.number-assigned` with `backfill: true`.
    *
    * Idempotent: a document that has a number already is skipped. Skipped as well: no number range for its
    * class, a version in review or approval (its values are frozen until it is decided), and a pattern with
    * `{OU}` while the document has no organizational unit. Run each document in its own transaction.
    */
  def numberExisting(id: DocumentId, actor: UserId): IO[DocumentError, BackfillOutcome] =
    sql(DocumentStore.lockDocument(id)).someOrFail(documentNotFound(id)).flatMap { document =>
      if document.number.isDefined then ZIO.succeed(BackfillOutcome.Skipped(BackfillSkip.HasNumber))
      else
        for
          profile  <- profile(document.classId, document.classVersion)
          range    <- sql(DocumentNumbering.rangeOf(profile))
          versions <- sql(DocumentStore.versions(id, templates))
          open    = versions.find(v => Versioning.isOpen(v.status))
          current = open.orElse(versions.lastOption)
          unit    = current
            .flatMap(_.values.get(UnitField))
            .collect { case FieldEntry(MetadataValue.Text(t), _) => t }
            .filter(NumberPattern.unitSegment(_).nonEmpty)
          outcome <- range match
            case None => ZIO.succeed(BackfillOutcome.Skipped(BackfillSkip.NoRange))
            case Some(_) if open.exists(_.status != DocumentStatus.Draft) =>
              ZIO.succeed(BackfillOutcome.Skipped(BackfillSkip.InReview))
            case Some(r) if r.pattern.usesUnit && unit.isEmpty =>
              ZIO.succeed(BackfillOutcome.Skipped(BackfillSkip.UnitMissing))
            case Some(r) =>
              for
                zone   <- sql(DocumentStore.tenantZone)
                number <- sql(DocumentNumbering.next(r, zone, document.createdAt, unit))
                  .flatMap(n => ZIO.fromEither(n).mapError(p => DocumentError.Invalid(List(p))))
                _ <- sql(NumberRangeStore.setDocumentNumber(id, number))
                  .mapError(conflict(s"the number $number is already assigned to another document"))
                draft <- ZIO.foreach(open)(numberDraft(_, number, actor))
                _     <- ZIO.foreachDiscard(draft.orElse(versions.lastOption))(v =>
                  record(
                    actor,
                    "document.number-assigned",
                    v,
                    Json.Obj(
                      "before"      -> Json.Null,
                      "after"       -> Json.Str(number),
                      "numberRange" -> Json.Str(r.key),
                      "manual"      -> Json.Bool(false),
                      "backfill"    -> Json.Bool(true)
                    )
                  )
                )
              yield BackfillOutcome.Numbered(number)
        yield outcome
    }

  /** The document with `number`, if any (stable address by number, DMS-113). */
  def findByNumber(number: String): IO[DocumentError, Option[DocumentId]] =
    sql(NumberRangeStore.documentWithNumber(number.trim))

  /** A document with all versions, oldest first. */
  def get(id: DocumentId): IO[DocumentError, DocumentDetails] =
    for
      document <- sql(DocumentStore.document(id)).someOrFail(documentNotFound(id))
      versions <- sql(DocumentStore.versions(id, templates))
      profile  <- profile(document.classId, document.classVersion)
      zone     <- sql(DocumentStore.tenantZone)
    yield DocumentDetails(document, versions, profile, zone)

  /** The most recently changed documents with all their versions; the caller filters what may be read. */
  def recent(limit: Int): IO[DocumentError, List[DocumentDetails]] =
    sql(DocumentStore.recentDocuments(limit)).flatMap(detailsOf)

  /** Several documents with all versions, by id; unknown ones are left out. */
  def getAll(ids: List[DocumentId]): IO[DocumentError, Map[DocumentId, DocumentDetails]] =
    sql(DocumentStore.documents(ids)).flatMap(detailsOf).map(_.map(d => d.document.id -> d).toMap)

  /** The details of documents with a fixed number of queries, however many there are (DMS-60). */
  private def detailsOf(documents: List[Document]): IO[DocumentError, List[DocumentDetails]] =
    for
      versions <- sql(DocumentStore.versionsOf(documents.map(_.id), templates))
      profiles <- ZIO
        .foreach(documents.map(d => (d.classId, d.classVersion)).distinct)((id, version) =>
          profile(id, version).map((id, version) -> _)
        )
        .map(_.toMap)
      zone <- sql(DocumentStore.tenantZone)
    yield documents.map(d =>
      DocumentDetails(d, versions.getOrElse(d.id, Nil), profiles((d.classId, d.classVersion)), zone)
    )

  /** The time zone of the tenant; validity switches at 00:00 there (DMS-45). */
  def tenantZone: IO[DocumentError, ZoneId] = sql(DocumentStore.tenantZone)

  /** The version of every document and validity scope that was in force at `at` (US-09.1, DMS-53), by title.
    * Only versions that were published count: from 00:00 of their valid-from date in the tenant's time zone
    * (or their publication, if later) until their successor became valid. `classKey` limits the result to a
    * class and its subclasses. The caller decides which of them may be read.
    */
  def validAt(at: Instant, classKey: Option[String] = None): IO[DocumentError, List[ValidVersion]] =
    for
      zone       <- sql(DocumentStore.tenantZone)
      candidates <- sql(DocumentStore.validityCandidates(at))
      profiles   <- ZIO.foreach(candidates.map(r => (r.classId, r.classVersion)).distinct)((id, version) =>
        profile(DocumentClassId(id), version).map(p => (id, version) -> p.classPath.map(_.value))
      )
      paths = profiles.toMap
    yield candidates.flatMap { r =>
      val classPath = paths((r.classId, r.classVersion))
      val status    = DocumentStatus.valueOf(r.status)
      val from      = Validity.effectiveStart(r.validFrom, r.publishedAt.toInstant, zone)
      val until     =
        r.successorPublishedAt
          .map(p => Validity.effectiveStart(r.successorValidFrom, p.toInstant, zone))
          .orElse(r.withdrawnAt.map(_.toInstant))
      // A replaced version without a recorded successor has no known end; it is not reported as in force.
      val period = ValidityPeriod(from, until.orElse(Option.unless(Versioning.isValid(status))(from)))
      Option.when(
        Validity.EverValid.contains(status) && period.contains(at) && classKey.forall(classPath.contains)
      )(
        ValidVersion(
          Document(
            DocumentId(r.documentId),
            DocumentClassId(r.classId),
            r.classVersion,
            r.title,
            r.ownerId.map(UserId(_)),
            UserId(r.authorId),
            TemplateId(r.templateId),
            r.templateVersion,
            r.documentCreatedAt.toInstant
          ),
          classPath,
          r.versionId,
          VersionNo(r.major, r.minor),
          status,
          r.scope,
          r.fieldValues.value
            .fromJson[Map[String, FieldEntry]]
            .fold(
              e => throw IllegalStateException(s"cannot read the field values of version ${r.versionId}: $e"),
              identity
            ),
          UserId(r.createdBy),
          r.releasedBy.map(UserId(_)),
          r.releasedAt.map(_.toInstant),
          r.validFrom,
          period
        )
      )
    }

  /** Every version valid now (Published or ReviewDue), by title, with its class path, review date and whether
    * it asks for acknowledgement (compliance cockpit, DMS-59; library, DMS-119). The caller decides which of
    * them may be seen.
    */
  def validityStamp: IO[DocumentError, Option[Instant]] =
    sql(DocumentStore.validityStamp).map(_.map(_.toInstant))

  def currentlyValid: IO[DocumentError, List[CurrentVersion]] =
    sql(DocumentStore.currentlyValid()).flatMap(currentVersions)

  /** [[currentlyValid]] of one version: empty if it is not valid (any more). */
  def validVersion(id: UUID): IO[DocumentError, List[CurrentVersion]] =
    sql(DocumentStore.currentlyValid(Some(id))).flatMap(currentVersions)

  private def currentVersions(rows: List[DocumentStore.CurrentRow]): IO[DocumentError, List[CurrentVersion]] =
    for
      paths <- ZIO.foreach(rows.map(r => (r.classId, r.classVersion)).distinct)((id, version) =>
        profile(DocumentClassId(id), version).map(p => (id, version) -> p.classPath.map(_.value))
      )
      byClass = paths.toMap
    yield rows.map(r =>
      CurrentVersion(
        Document(
          DocumentId(r.documentId),
          DocumentClassId(r.classId),
          r.classVersion,
          r.title,
          r.ownerId.map(UserId(_)),
          UserId(r.authorId),
          TemplateId(r.templateId),
          r.templateVersion,
          r.documentCreatedAt.toInstant,
          r.documentNumber
        ),
        byClass((r.classId, r.classVersion)),
        r.versionId,
        VersionNo(r.major, r.minor),
        DocumentStatus.valueOf(r.status),
        r.fieldValues.value
          .fromJson[Map[String, FieldEntry]]
          .fold(
            e => throw IllegalStateException(s"cannot read the field values of version ${r.versionId}: $e"),
            identity
          ),
        UserId(r.createdBy),
        r.releasedBy.map(UserId(_)),
        r.validFrom,
        r.publishedAt.map(_.toInstant),
        r.reviewDueOn,
        r.requiresAcknowledgement,
        r.scope,
        r.firstPublishedAt.map(_.toInstant)
      )
    )

  /** Ids of the documents changed at or after `since` (the document or one of its versions), without `since`
    * of every document with a released version: what the search index (DMS-51) has to (re)index.
    */
  def changedDocuments(since: Option[Instant]): IO[DocumentError, List[DocumentId]] =
    sql(DocumentStore.changedDocumentIds(since))

  /** How documents use the versions of `template` before `version` (US-01.2): document versions per template
    * version and status, and the open versions with title and author only. The caller decides who may see it;
    * the rows are limited to the current tenant by row-level security.
    */
  def templateUsage(template: TemplateId, version: Int): IO[DocumentError, TemplateUsage] =
    sql {
      val counts = DocumentStore
        .templateUsageCounts(template, version)
        .map((v, status, versions, documents) =>
          v -> StatusUsage(DocumentStatus.valueOf(status), versions.toInt, documents.toInt)
        )
      val documents                = DocumentStore.templateUsageDocuments(template, version).toMap
      def byStatus(v: Option[Int]) = counts.collect { case (`v`, c) => c }.sortBy(_.status.ordinal)
      TemplateUsage(
        template,
        version,
        documents.get(None).fold(0)(_.toInt),
        byStatus(None),
        documents
          .collect { case (Some(v), n) => VersionUsage(v, n.toInt, byStatus(Some(v))) }
          .toList
          .sortBy(_.templateVersion),
        DocumentStore.openTemplateUses(template, version)
      )
    }

  /** Saves a changed draft as its next working state. Locked sections can only keep the template's text
    * unless a deviation request for the section is recorded (edit rules of the template); the computed fields
    * are those of the template. The deviation report is recomputed and stored with the draft.
    */
  def updateDraft(id: DocumentId, change: DraftChange, actor: UserId): IO[DocumentError, DocumentVersion] =
    for
      document <- sql(DocumentStore.document(id)).someOrFail(documentNotFound(id))
      current  <- sql(DocumentStore.lockOpenVersion(id, templates)).someOrFail(noOpenVersion(id))
      _        <- ZIO.unless(Versioning.isEditable(current.status))(
        ZIO.fail(
          DocumentError.Conflict(s"version ${current.label} is ${current.status}; only drafts can be changed")
        )
      )
      template <- templates.version(current.templateId, current.templateVersion)
      profile  <- profile(document.classId, current.classVersion)
      requests <- activeRequests(current.id)
      content = change.content.getOrElse(current.content)
      _ <- ZIO.when(document.kind == DocumentKind.File && content != current.content)(
        ZIO.fail(
          DocumentError.Invalid(
            List("the content of a file document is its uploaded file; upload a new file")
          )
        )
      )
      _ <- ZIO.when(change.content.exists(_ != current.content))(checkContent(template, content, requests))
      title = change.title.getOrElse(titleOf(current).getOrElse(document.title))
      entered   <- protectSystemValues(profile, current, change.values)
      retention <- retentionEntry(document.classId, Some(id), current.values.get(ClassRetention.Field))
      values    <- prepare(
        profile,
        template,
        content,
        entered,
        title,
        id,
        current.number,
        document.number,
        retention
      )
      next = current.copy(
        workingState = current.workingState + 1,
        content = content,
        values = values,
        scope = Versioning.scopeKey(metadataOf(values)),
        rendition = None,
        deviations = DeviationReport.compare(template.content, content, requests)
      )
      _ <- sql(DocumentStore.updateDraft(next, templates)).filterOrFail(identity)(changedConcurrently(next))
      _ <- sql {
        DocumentStore.insertWorkingState(next, actor, templates)
        DocumentStore.updateDocument(id, title.trim, ownerOf(values))
      }
      _ <- record(
        actor,
        "document.draft-changed",
        next,
        Json.Obj(
          "fields"         -> fieldChanges(current.values, values),
          "contentChanged" -> Json.Bool(content != current.content)
        )
      )
    yield next

  /** Submits the open draft for review. Blocked while fields required in review or later are missing: the
    * values freeze here, so this is the last moment the author can add them (ADR 0062).
    */
  def submit(id: DocumentId, actor: UserId): IO[DocumentError, DocumentVersion] =
    for
      document <- sql(DocumentStore.document(id)).someOrFail(documentNotFound(id))
      current  <- sql(DocumentStore.lockOpenVersion(id, templates)).someOrFail(noOpenVersion(id))
      // Drafts started before DMS-126 may lack the summary; they cannot be submitted without one.
      _ <- checkChangeReason(current.changeKind, current.number, current.changeReason)
      // Drafts saved before DMS-133 (or before their class got a rule) get the class's retention rule now.
      refreshed <- refreshRetention(document, current, actor)
      _         <- ZIO.when(document.kind == DocumentKind.File)(
        current.file match
          case None =>
            ZIO.fail(DocumentError.Invalid(List("upload the file of the document before submitting it")))
          case Some(file) =>
            val problems = WordFields.check(file.fields, file.values, complete = true, DocumentJson.readRows)
            ZIO.unless(problems.isEmpty)(ZIO.fail(DocumentError.Invalid(problems)))
      )
      updated <- transition(document, refreshed, LifecycleEvent.Submit, actor).mapError {
        case DocumentError.Incomplete(target, issues) => DocumentError.Incomplete(target, issues.map(noRule))
        case other                                    => other
      }
      _ <- ZIO.when(updated.changeKind == ChangeKind.Minor)(recordEditorialCheck(updated, actor))
    yield updated

  /** The retention rule of a version (DMS-133): the rule records management set for the document; otherwise
    * the rule of the class (own or inherited, origin platform); without one a value entered by hand before
    * DMS-133 stays.
    */
  private def retentionEntry(
      classId: DocumentClassId,
      document: Option[DocumentId],
      current: Option[FieldEntry]
  ): IO[DocumentError, Option[FieldEntry]] =
    for
      own <- ZIO.foreach(document)(id => sql(MetadataStore.documentRetentionRule(id))).map(_.flatten)
      cls <-
        if own.isDefined then ZIO.none
        else classes.effectiveRetentionRule(classId).mapError(metadataError)
    yield own
      .map(rule => FieldEntry(MetadataValue.Text(rule), ValueOrigin.RecordsManagement))
      .orElse(cls.map(rule => FieldEntry(MetadataValue.Text(rule), ValueOrigin.Platform)))
      .orElse(current.filter(_.origin == ValueOrigin.Manual))

  /** Stores the retention rule of a draft whose value is missing or outdated (the class or the document got
    * another rule) as its next working state, recorded like a draft change. Other versions stay as they are.
    */
  private def refreshRetention(
      document: Document,
      current: DocumentVersion,
      actor: UserId
  ): IO[DocumentError, DocumentVersion] =
    val before = current.values.get(ClassRetention.Field)
    retentionEntry(document.classId, Some(document.id), before).flatMap { retention =>
      if retention == before || !Versioning.isEditable(current.status) then ZIO.succeed(current)
      else
        val values = (current.values - ClassRetention.Field) ++ retention.map(ClassRetention.Field -> _)
        val next   = current.copy(workingState = current.workingState + 1, values = values, rendition = None)
        for
          _ <- sql(DocumentStore.updateDraft(next, templates))
            .filterOrFail(identity)(changedConcurrently(next))
          _ <- sql(DocumentStore.insertWorkingState(next, actor, templates))
          _ <- record(
            actor,
            "document.draft-changed",
            next,
            Json.Obj("fields" -> fieldChanges(current.values, values), "contentChanged" -> Json.Bool(false))
          )
        yield next
    }

  /** The retention rule records management set for a document instead of its class's rule, if any (DMS-133).
    */
  def documentRetentionRule(id: DocumentId): IO[DocumentError, Option[String]] =
    sql(MetadataStore.documentRetentionRule(id))

  /** Sets the retention rule of a document as records manager (DMS-133): `Some(rule)` replaces the class's
    * rule for the open draft and every later version (origin records management) until it is removed with
    * `None`, after which the document follows its class again. The rule must exist (checked by the caller).
    * An open draft gets the value at once (as its next working state); versions in review or approved keep
    * theirs (the snapshot of an approved version is frozen). Audited as `document.retention-rule-changed`
    * with before and after; an unchanged rule records nothing. Authorization (records managers) is the
    * caller's job.
    */
  def setRetentionRule(
      id: DocumentId,
      rule: Option[String],
      actor: UserId
  ): IO[DocumentError, Option[String]] =
    for
      document <- sql(DocumentStore.document(id)).someOrFail(documentNotFound(id))
      latest   <- sql(DocumentStore.versions(id, templates))
        .map(_.maxByOption(v => (v.createdAt, v.number)))
        .someOrFail(documentNotFound(id))
      key = rule.map(_.trim).filter(_.nonEmpty)
      before <- sql(
        key.fold(MetadataStore.removeDocumentRetentionRule(id))(
          MetadataStore.setDocumentRetentionRule(id, _, actor.value)
        )
      )
      _ <- ZIO.when(before != key)(
        record(
          actor,
          "document.retention-rule-changed",
          latest,
          Json.Obj(
            "before" -> before.fold(Json.Null)(Json.Str(_)),
            "after"  -> key.fold(Json.Null)(Json.Str(_))
          )
        )
      )
      open <- sql(DocumentStore.lockOpenVersion(id, templates))
      _    <- ZIO.foreachDiscard(open.filter(v => Versioning.isEditable(v.status)))(
        refreshRetention(document, _, actor)
      )
    yield key

  /** The automatic comparison of a submitted minor version with its basis (DMS-46), recorded in the audit
    * trail so that the confirmation of the editorial classification can be traced to the warning.
    */
  private def recordEditorialCheck(v: DocumentVersion, actor: UserId): IO[DocumentError, Unit] =
    for
      versions <- sql(DocumentStore.versions(v.documentId, templates))
      _ <- ZIO.foreachDiscard(EditorialCheck.of(v, versions, settings.editorialChangeThreshold))(check =>
        record(
          actor,
          "document.editorial-check",
          v,
          Json.Obj(
            "basedOn"          -> Json.Str(check.basedOn),
            "changedRatio"     -> Json.Num(check.changedRatio),
            "threshold"        -> Json.Num(check.threshold),
            "exceedsThreshold" -> Json.Bool(check.exceedsThreshold),
            "reason"           -> v.changeReason.fold(Json.Null)(Json.Str(_))
          )
        )
      )
    yield ()

  /** Approved versions waiting for publication, with the moment each becomes valid (DMS-45). */
  def scheduledPublications: IO[DocumentError, List[ScheduledPublication]] =
    sql {
      val zone = DocumentStore.tenantZone
      DocumentStore.scheduled.map((id, document, validFrom) =>
        ScheduledPublication(
          DocumentId(document),
          id,
          validFrom.fold(Instant.EPOCH)(Publication.dueAt(_, zone))
        )
      )
    }

  /** Publishes an approved version whose valid-from date has come (DMS-45): the valid version of the same
    * scope is superseded in the same transaction, so readers see either the old or the new valid version.
    * Does nothing (`None`) if the version is not approved any more (already published) or not due yet (e.g.
    * the tenant's time zone changed), which makes the publication job idempotent. Recorded as done by the
    * platform ([[DocumentService.Scheduler]]).
    */
  def publishScheduled(versionId: UUID): IO[DocumentError, Option[DocumentVersion]] =
    for
      current   <- sql(DocumentStore.lockVersion(versionId, templates)).someOrFail(versionNotFound(versionId))
      zone      <- sql(DocumentStore.tenantZone)
      now       <- Clock.instant
      published <-
        if current.status != DocumentStatus.Approved || !Publication.isDue(current.validFrom, zone, now) then
          ZIO.none
        else
          sql(DocumentStore.document(current.documentId))
            .someOrFail(documentNotFound(current.documentId))
            .flatMap(document => transition(document, current, LifecycleEvent.Publish, Scheduler))
            .asSome
    yield published

  /** Whether the publication of a version failed for good, for showing the way out. The withdrawal and the
    * retry decide again under the lock of the version.
    */
  def publicationFailed(versionId: UUID): IO[DocumentError, Boolean] =
    PublicationJobs.isDead(versionId).mapError(DocumentError.Database(_))

  /** Locks a version for a decision on its publication job and returns it as it is now: a retry takes this
    * lock first, as [[withdrawApproved]] does, so that exactly one of the two wins (DMS-145, ADR 0061).
    */
  def lockForPublicationRetry(versionId: UUID): IO[DocumentError, DocumentVersion] =
    sql(DocumentStore.lockVersion(versionId, templates)).someOrFail(versionNotFound(versionId))

  /** Stops an approved version before its valid-from date (DMS-143, ADR 0059), or later if its publication
    * failed for good, so that a version that can never become valid does not block the document (DMS-145, ADR
    * 0061): it goes to `Withdrawn` and never takes effect, the valid version of its scope stays as it is, and
    * its queued publication jobs are cancelled. The reason is required; the values of the version play no
    * part, they may be what the publication failed on. Frozen data stays: content, snapshot, approval, the
    * rendition and its retention (the database queues the Object Lock job of a version going out of force).
    * No acknowledgement exists yet, because the audience is fixed at publication. Recorded in the audit trail
    * (`document.withdrawn-before-valid`) and published to the outbox (`document.status-changed`, and
    * `document.approved-version-withdrawn` for the notification) in the same transaction.
    *
    * A race with [[publishScheduled]] is decided by the row lock of the version: the clock is read after it,
    * so exactly one of the two changes the version and the other finds its result. A withdrawal that loses
    * fails with [[DocumentError.WithdrawalRefused]]; a publication job that loses does nothing. A race with a
    * retry of the publication is decided the same way: the state of the job is read after the lock, too.
    *
    * A review task decided as revised with this version opens again ([[ReviewService.reopenFor]]).
    *
    * @param approvers
    *   who approved the version, known to the workflow; they are notified with the document's owner, without
    *   the actor
    */
  def withdrawApproved(
      versionId: UUID,
      reason: String,
      approvers: Set[UserId],
      actor: UserId
  ): IO[DocumentError, DocumentVersion] =
    val why = reason.trim
    for
      _ <- ZIO.when(why.isEmpty)(ZIO.fail(DocumentError.Invalid(List("a reason is required"))))
      _ <- ZIO.when(why.length > MaxReasonLength)(
        ZIO.fail(DocumentError.Invalid(List(s"the reason must have at most $MaxReasonLength characters")))
      )
      current <- sql(DocumentStore.lockVersion(versionId, templates)).someOrFail(versionNotFound(versionId))
      zone    <- sql(DocumentStore.tenantZone)
      now     <- Clock.instant
      failed  <- ZIO
        .when(current.status == DocumentStatus.Approved)(PublicationJobs.isDead(versionId))
        .mapBoth(DocumentError.Database(_), _.contains(true))
      _        <- ZIO.foreachDiscard(withdrawalRefusal(current, failed, zone, now))(ZIO.fail(_))
      document <- sql(DocumentStore.document(current.documentId))
        .someOrFail(documentNotFound(current.documentId))
      updated <- transition(
        document,
        current,
        LifecycleEvent.Withdraw,
        actor,
        withdrawalReason = Some(why),
        publicationFailed = failed
      )
      _ <- PublicationJobs
        .cancel(versionId, s"version ${current.label} was withdrawn by ${actor.value}")
        .mapError(DocumentError.Database(_))
      recipients = (approvers ++ document.ownerId - actor).toList.sortBy(_.value)
      _ <- Outbox
        .publish(
          "document",
          document.id.value,
          ApprovedVersionWithdrawn,
          Jsonb(
            Json
              .Obj(
                (List(
                  "documentId"  -> Json.Str(document.id.value.toString),
                  "versionId"   -> Json.Str(versionId.toString),
                  "version"     -> Json.Str(current.label),
                  "withdrawnBy" -> Json.Str(actor.value),
                  "reason"      -> Json.Str(why),
                  "recipients"  -> Json.Arr(recipients.map(u => Json.Str(u.value))*)
                ) ++ current.validFrom.map(d => "validFrom" -> Json.Str(d.toString)) ++
                  Option.when(failed)("publicationFailed" -> Json.Bool(true)))*
              )
              .toJson
          )
        )
        .mapError(DocumentError.Database(_))
      _ <- ReviewService.reopenFor(audit, versionId)
    yield updated

  /** Applies a lifecycle event to a version (review, approval, publication, ...). Approval needs a person
    * other than the author and freezes the version with a metadata snapshot; publication supersedes the valid
    * version of the same scope. An approved version is withdrawn with [[withdrawApproved]] only: it needs a
    * reason.
    */
  def changeStatus(
      versionId: UUID,
      event: LifecycleEvent,
      actor: UserId
  ): IO[DocumentError, DocumentVersion] =
    for
      current <- sql(DocumentStore.lockVersion(versionId, templates)).someOrFail(versionNotFound(versionId))
      _       <- ZIO.when(event == LifecycleEvent.Withdraw && current.status == DocumentStatus.Approved)(
        ZIO.fail(DocumentError.Invalid(List("withdrawing an approved version needs a reason")))
      )
      document <- sql(DocumentStore.document(current.documentId))
        .someOrFail(documentNotFound(current.documentId))
      updated <- transition(document, current, event, actor)
    yield updated

  /** Records the rendition of an open version (rendering, ADR 0008). It is frozen with the version on
    * approval.
    */
  def attachRendition(
      versionId: UUID,
      rendition: Rendition,
      actor: UserId
  ): IO[DocumentError, DocumentVersion] =
    for
      _ <- ZIO.unless(Sha256.matches(rendition.sha256) && rendition.objectKey.trim.nonEmpty)(
        ZIO.fail(DocumentError.Invalid(List("a rendition needs an object key and a lowercase hex SHA-256")))
      )
      current <- sql(DocumentStore.lockVersion(versionId, templates)).someOrFail(versionNotFound(versionId))
      _       <- sql(DocumentStore.attachRendition(versionId, rendition)).filterOrFail(identity)(
        DocumentError.Conflict(s"version ${current.label} is ${current.status}; its rendition is frozen")
      )
      updated = current.copy(rendition = Some(rendition))
      _ <- record(
        actor,
        "document.rendition-attached",
        updated,
        Json.Obj("objectKey" -> Json.Str(rendition.objectKey), "sha256" -> Json.Str(rendition.sha256))
      )
    yield updated

  /** Starts the next version of a document from its valid version (see [[Versioning.startingPoint]]): a major
    * version for a change of content, a minor version for an editorial change. Every new version needs a
    * change summary (DMS-126, business concept 5.1). A document has at most one open version.
    */
  def newVersion(
      id: DocumentId,
      kind: ChangeKind,
      reason: Option[String],
      actor: UserId,
      requiresAcknowledgement: Option[Boolean] = None
  ): IO[DocumentError, DocumentVersion] =
    for
      document <- sql(DocumentStore.document(id)).someOrFail(documentNotFound(id))
      versions <- sql(DocumentStore.versions(id, templates))
      _        <- ZIO.foreachDiscard(versions.find(v => Versioning.isOpen(v.status)).toList)(open =>
        ZIO.fail(DocumentError.Conflict(s"version ${open.label} is still ${open.status}; finish it first"))
      )
      // A new version starts from the valid one; an approved version that is not valid yet would be lost.
      _ <- ZIO.foreachDiscard(versions.find(_.status == DocumentStatus.Approved).toList)(pending =>
        ZIO.fail(
          DocumentError.Conflict(
            s"version ${pending.label} is approved and becomes valid on " +
              s"${pending.validFrom.fold("its valid-from date")(_.toString)}; start the next version after that"
          )
        )
      )
      base <- ZIO
        .fromOption(Versioning.startingPoint(versions))
        .orElseFail(DocumentError.Conflict("only a document with a valid version can get a new version"))
      // A minor version needs only the owner's confirmation: nothing may first take effect that way (ADR 0059).
      _ <- ZIO.when(kind == ChangeKind.Minor && !Versioning.isValid(base.status))(
        ZIO.fail(DocumentError.Conflict("without a valid version, only a major version can be started"))
      )
      number = Versioning.next(versions.filter(_.releasedAt.isDefined).map(_.number).maxOption, kind)
      changeReason <- checkChangeReason(kind, number, reason)
      workingState <- sql(DocumentStore.workingStates(id, number)).map(Versioning.nextWorkingState)
      template     <- templates.version(base.templateId, base.templateVersion)
      profile      <- profile(document.classId, base.classVersion)
      retention    <- retentionEntry(document.classId, Some(id), base.values.get(ClassRetention.Field))
      baseRequests <- sql(DocumentStore.deviationRequests(base.id)).map(_.filter(_.active))
      now          <- Clock.instant
      versionId = UUID.randomUUID()
      carried   = baseRequests.map(r =>
        r.copy(id = UUID.randomUUID(), versionId = versionId, basedOn = Some(r.id))
      )
      // Attachments are taken over as links to the same files (DMS-114): no bytes are copied.
      attachments = base.attachments.map(a =>
        a.copy(id = UUID.randomUUID(), versionId = versionId, basedOn = Some(a.id))
      )
      // The original of a file document is taken over as well (DMS-115); a new upload replaces it in the draft.
      file    = base.file.map(_.copy(versionId = versionId, basedOn = Some(base.id)))
      version = base.copy(
        id = versionId,
        number = number,
        workingState = workingState,
        status = DocumentStatus.Draft,
        changeKind = kind,
        changeReason = changeReason,
        values = (base.values - ClassRetention.Field) ++
          systemValues(id, profile, number, template, document.number) ++
          retention.map(ClassRetention.Field -> _),
        metadataSnapshot = None,
        rendition = None,
        createdBy = actor,
        createdAt = now,
        updatedAt = now,
        releasedBy = None,
        releasedAt = None,
        deviations = DeviationReport.compare(template.content, base.content, carried.map(_.request)),
        validFrom = None,
        supersededBy = None,
        withdrawal = None,
        requiresAcknowledgement = requiresAcknowledgement.getOrElse(
          kind == ChangeKind.Major || settings.minorVersionsRequireAcknowledgement
        ),
        attachments = attachments,
        file = file
      )
      _ <- sql {
        DocumentStore.insertVersion(version, templates)
        DocumentStore.insertWorkingState(version, actor, templates)
        carried.foreach(DocumentStore.insertDeviationRequest)
        attachments.foreach(AttachmentStore.carryOver)
        file.foreach(VersionFileStore.carryOver)
      }.mapError(conflict(s"document ${id.value} was changed concurrently"))
      _ <- record(
        actor,
        "document.version-created",
        version,
        Json.Obj(
          "basedOn"                 -> Json.Str(base.label),
          "changeKind"              -> Json.Str(kind.toString),
          "reason"                  -> changeReason.fold(Json.Null)(Json.Str(_)),
          "requiresAcknowledgement" -> Json.Bool(version.requiresAcknowledgement),
          "deviationRequests"       -> Json.Arr(carried.map(r => Json.Str(r.id.toString))*),
          "attachments"             -> Json.Arr(attachments.map(a => Json.Str(a.id.toString))*),
          "fileSha256"              -> file.fold(Json.Null)(f => Json.Str(f.sha256))
        )
      )
      _ <- publish("document.version-created", document, version)
    yield version

  /** Changes the change summary of the open draft (DMS-103): what changed and why, shown in the version
    * history and above version comparisons. It can be changed until the draft is submitted (409 afterwards);
    * a version with a predecessor keeps a non-empty summary (422, DMS-126). Not a working state: content and
    * values are unchanged. Recorded in the audit trail with the summary before and after; an unchanged
    * summary records nothing.
    */
  def updateChangeReason(
      id: DocumentId,
      reason: Option[String],
      actor: UserId
  ): IO[DocumentError, DocumentVersion] =
    for
      current <- sql(DocumentStore.lockOpenVersion(id, templates)).someOrFail(noOpenVersion(id))
      _       <- ZIO.unless(Versioning.isEditable(current.status))(
        ZIO.fail(
          DocumentError.Conflict(
            s"version ${current.label} is ${current.status}; the change summary only changes while it is a draft"
          )
        )
      )
      changeReason <- checkChangeReason(current.changeKind, current.number, reason)
      updated      <-
        if changeReason == current.changeReason then ZIO.succeed(current)
        else
          for
            now <- Clock.instant
            _   <- sql(DocumentStore.updateChangeReason(current.id, changeReason))
              .filterOrFail(identity)(changedConcurrently(current))
            next = current.copy(changeReason = changeReason, updatedAt = now)
            _ <- record(
              actor,
              "document.change-reason-changed",
              next,
              Json.Obj(
                "changeKind" -> Json.Str(current.changeKind.toString),
                "before"     -> current.changeReason.fold[Json](Json.Null)(Json.Str(_)),
                "after"      -> changeReason.fold[Json](Json.Null)(Json.Str(_))
              )
            )
          yield next
    yield updated

  /** Discards the open draft of a document (DMS-129, ADR 0050), e.g. one created by accident. Only a draft
    * that is not submitted can be discarded (409 otherwise: submitted, approved and published versions are
    * withdrawn or destroyed instead). The version is kept as a record in status `Discarded` and is never read
    * again; if it was the document's only version, the document disappears from lists (its number stays
    * reserved).
    *
    * Its attachment links and the original of a file document are removed while it is still a draft; the
    * stored files that no version references any more are deleted and their objects removed by a job after
    * the commit ([[FileCleanupJobs]]), unless the document is under legal hold, which keeps them. Files of
    * other versions are never touched. Recorded in the audit trail (`document.discarded`, with the reason)
    * and published to the outbox in the same transaction. Ending the draft's workflow case is the caller's
    * job (module `workflow`).
    */
  def discardDraft(id: DocumentId, reason: Option[String], actor: UserId): IO[DocumentError, DiscardedDraft] =
    for
      document <- sql(DocumentStore.lockDocument(id)).someOrFail(documentNotFound(id))
      current  <- sql(DocumentStore.lockOpenVersion(id, templates)).someOrFail(
        DocumentError.Conflict(s"document ${id.value} has no draft to discard")
      )
      _ <- ZIO.unless(current.status == DocumentStatus.Draft)(
        ZIO.fail(
          DocumentError.Conflict(
            s"version ${current.label} is ${current.status}: only drafts that are not submitted can be discarded"
          )
        )
      )
      target <- ZIO
        .fromEither(Lifecycle.transition(current.status, LifecycleEvent.Discard))
        .mapError(DocumentError.Lifecycle(_))
      note = reason.map(_.trim).filter(_.nonEmpty)
      _ <- ZIO.when(note.exists(_.length > MaxReasonLength))(
        ZIO.fail(DocumentError.Invalid(List(s"the reason must have at most $MaxReasonLength characters")))
      )
      versions <- sql(DocumentStore.versions(id, templates))
      now      <- Clock.instant
      files    <- sql {
        val attached = AttachmentStore.removeAllOf(current.id)
        val original = VersionFileStore.remove(current.id)
        val held     = AttachmentStore.underLegalHold(id)
        val orphans  = (attached ++ original).distinct.flatMap(AttachmentStore.unreferenced)
        if !held then orphans.foreach(f => AttachmentStore.deleteFile(f.id))
        (attached.size, held, orphans)
      }
      (removedLinks, held, orphans) = files
      released                      = if held then Nil else orphans
      _ <- ZIO.foreachDiscard(released)(f => FileCleanupJobs.enqueue(f).mapError(DocumentError.Database(_)))
      _ <- sql(DocumentStore.changeStatus(current.id, current.status, target, actor, now, None))
        .filterOrFail(identity)(changedConcurrently(current))
      discarded = current.copy(status = target, updatedAt = now, attachments = Nil, file = None)
      others    = versions.filterNot(_.id == current.id)
      whole     = others.forall(_.status == DocumentStatus.Discarded)
      valid     = others.filter(v => Versioning.isValid(v.status)).maxByOption(_.number)
      _ <- record(
        actor,
        "document.discarded",
        discarded,
        Json.Obj(
          "from"               -> Json.Str(current.status.toString),
          "to"                 -> Json.Str(target.toString),
          "version"            -> Json.Str(current.label),
          "documentDiscarded"  -> Json.Bool(whole),
          "documentNumber"     -> document.number.fold[Json](Json.Null)(Json.Str(_)),
          "reason"             -> note.fold[Json](Json.Null)(Json.Str(_)),
          "removedAttachments" -> Json.Num(removedLinks),
          "removedOriginal"    -> Json.Bool(current.file.isDefined),
          "legalHold"          -> Json.Bool(held),
          "releasedFiles"      -> Json.Arr(released.map(f => Json.Str(f.sha256))*),
          "keptFiles"          -> Json.Arr((if held then orphans else Nil).map(f => Json.Str(f.sha256))*)
        ),
        note
      )
      _ <- publish("document.discarded", document, discarded, Some(current.status))
      _ <- ReviewService.reopenFor(audit, current.id)
    yield DiscardedDraft(
      discarded,
      whole,
      valid,
      removedLinks,
      released,
      if held then orphans else Nil
    )

  /** The trimmed change summary, at most `MaxReasonLength` characters. Required for every version that has a
    * predecessor, major or minor (DMS-126, business concept 5.1: each new version comes with a reason and an
    * overview of the changes); only the first version of a document is exempt. A version has a predecessor
    * exactly when its number is above [[Versioning.first]]: [[Versioning.next]] returns the first number only
    * while no version was ever approved.
    */
  private def checkChangeReason(
      kind: ChangeKind,
      number: VersionNo,
      reason: Option[String]
  ): IO[DocumentError, Option[String]] =
    val changeReason = reason.map(_.trim).filter(_.nonEmpty)
    if changeReason.isEmpty && Ordering[VersionNo].gt(number, Versioning.first) then
      val what = kind match
        case ChangeKind.Major => "a new (major) version"
        case ChangeKind.Minor => "an editorial (minor) version"
      ZIO.fail(DocumentError.Invalid(List(s"$what needs a change summary: what changed and why")))
    else if changeReason.exists(_.length > MaxReasonLength) then
      ZIO.fail(
        DocumentError.Invalid(List(s"the change summary must have at most $MaxReasonLength characters"))
      )
    else ZIO.succeed(changeReason)

  /** The stored file of the document with this content, if any: an upload of the same bytes reuses it. */
  def attachmentFile(document: DocumentId, sha256: String): IO[DocumentError, Option[AttachmentFile]] =
    sql(AttachmentStore.file(document, sha256))

  /** Sets the uploaded original of the draft of a file document (DMS-115, ADR 0042) as its next working
    * state, replacing the previous file. The stored file is inserted unless the document already has a file
    * with the same content, which is used instead (content addressing per document, shared with attachments).
    *
    * @param carried
    *   values of manual fields the file carries (DMS-127: a re-uploaded filled Word document); invalid ones
    *   are left out
    * @param carriedWins
    *   whether the carried values replace the previous values (the platform filled the file); otherwise they
    *   only fill fields without value
    * @return
    *   the draft and whether `file` was inserted (if not, its object is not referenced and can be removed)
    */
  def setFile(
      id: DocumentId,
      file: AttachmentFile,
      name: String,
      fields: List[WordField],
      actor: UserId,
      carried: Map[String, String] = Map.empty,
      carriedWins: Boolean = false
  ): IO[DocumentError, (DocumentVersion, Boolean)] =
    for
      document <- sql(DocumentStore.document(id)).someOrFail(documentNotFound(id))
      _        <- ZIO.unless(document.kind == DocumentKind.File)(
        ZIO.fail(DocumentError.Conflict(s"document ${id.value} is not a file document"))
      )
      current <- sql(DocumentStore.lockOpenVersion(id, templates)).someOrFail(noOpenVersion(id))
      _       <- ZIO.unless(Versioning.isEditable(current.status))(
        ZIO.fail(
          DocumentError.Conflict(
            s"version ${current.label} is ${current.status}; the file only changes while the version is a draft"
          )
        )
      )
      inserted <- sql(AttachmentStore.insertFile(file))
      stored   <-
        if inserted then ZIO.succeed(file)
        else
          sql(AttachmentStore.file(id, file.sha256))
            .someOrFail(DocumentError.Conflict("the stored file vanished"))
      next = current.copy(workingState = current.workingState + 1, rendition = None)
      _ <- sql(DocumentStore.updateDraft(next, templates)).filterOrFail(identity)(changedConcurrently(next))
      // Values entered for fields that stay manual fields are kept while they fit the field's (new) type.
      manual = fields.filter(_.source == WordFieldSource.Manual).map(_.key).toSet
      kept   = current.file.fold(Map.empty[String, String])(
        _.values.filter((k, v) =>
          manual.contains(k) && WordFields
            .check(fields, Map(k -> v), complete = false, DocumentJson.readRows)
            .isEmpty
        )
      )
      found = cleanWordValues(fields, carried).filter((k, v) =>
        manual.contains(k) && WordFields
          .check(fields, Map(k -> v), complete = false, DocumentJson.readRows)
          .isEmpty
      )
      values = if carriedWins then kept ++ found else found ++ kept
      _ <- sql {
        VersionFileStore.set(current.id, stored.id, name, fields, values, actor, None)
        DocumentStore.insertWorkingState(next, actor, templates)
      }
      updated <- sql(DocumentStore.lockVersion(current.id, templates)).someOrFail(versionNotFound(current.id))
      _       <- record(
        actor,
        "document.file-uploaded",
        updated,
        Json.Obj(
          "name"           -> Json.Str(name),
          "sha256"         -> Json.Str(stored.sha256),
          "size"           -> Json.Num(stored.size),
          "contentType"    -> Json.Str(stored.contentType),
          "scanStatus"     -> Json.Str(stored.scanStatus.toString),
          "scanner"        -> Json.Str(stored.scanner),
          "reused"         -> Json.Bool(!inserted),
          "previousSha256" -> current.file.fold(Json.Null)(f => Json.Str(f.sha256)),
          "fields"         -> Json.Arr(fields.map(f => Json.Str(f.key))*),
          "carriedValues"  -> Json.Arr(found.keys.toList.sorted.map(Json.Str(_))*)
        )
      )
    yield (updated, inserted)

  /** Manual Word values as stored: trimmed, empty ones left out, rows (DMS-122) in their canonical JSON form
    * (unreadable rows are kept as sent, so the check reports them).
    */
  private def cleanWordValues(fields: List[WordField], values: Map[String, String]): Map[String, String] =
    values.flatMap { (k, v) =>
      fields.find(f => f.key == k && f.fieldType == WordFieldType.Rows) match
        case Some(f) =>
          DocumentJson.readRows(v).fold(_ => Some(v.trim), DocumentJson.writeRows(f, _)).map(k -> _)
        case None => Option.when(v.trim.nonEmpty)(k -> v.trim)
    }

  /** Stores the values of the manual fields of the draft's Word file (DMS-115) as its next working state.
    * Values are checked against the field definitions; required fields are checked on submission.
    */
  def setWordValues(
      id: DocumentId,
      values: Map[String, String],
      actor: UserId
  ): IO[DocumentError, DocumentVersion] =
    for
      current <- sql(DocumentStore.lockOpenVersion(id, templates)).someOrFail(noOpenVersion(id))
      _       <- ZIO.unless(Versioning.isEditable(current.status))(
        ZIO.fail(
          DocumentError.Conflict(
            s"version ${current.label} is ${current.status}; field values only change while it is a draft"
          )
        )
      )
      file <- ZIO
        .fromOption(current.file)
        .orElseFail(DocumentError.Conflict(s"version ${current.label} has no file with fields"))
      cleaned  = cleanWordValues(file.fields, values)
      problems = WordFields.check(file.fields, cleaned, complete = false, DocumentJson.readRows)
      _ <- ZIO.unless(problems.isEmpty)(ZIO.fail(DocumentError.Invalid(problems)))
      next = current.copy(workingState = current.workingState + 1, rendition = None)
      _ <- sql(DocumentStore.updateDraft(next, templates)).filterOrFail(identity)(changedConcurrently(next))
      _ <- sql {
        val _ = VersionFileStore.setValues(current.id, cleaned)
        DocumentStore.insertWorkingState(next, actor, templates)
      }
      updated <- sql(DocumentStore.lockVersion(current.id, templates)).someOrFail(versionNotFound(current.id))
      changed = (file.values.keySet ++ cleaned.keySet).toList.sorted
        .filter(k => file.values.get(k) != cleaned.get(k))
      _ <- record(
        actor,
        "document.word-values-changed",
        updated,
        Json.Obj(
          "fields" -> Json.Obj(
            changed.map(k =>
              k -> Json.Obj(
                "before" -> file.values.get(k).fold(Json.Null)(Json.Str(_)),
                "after"  -> cleaned.get(k).fold(Json.Null)(Json.Str(_))
              )
            )*
          )
        )
      )
    yield updated

  /** All deviation requests of a version, active and withdrawn, oldest first. */
  def deviationRequests(versionId: UUID): IO[DocumentError, List[RecordedDeviationRequest]] =
    sql(DocumentStore.deviationRequests(versionId))

  /** Records a deviation request for a locked section of the template on the open draft (US-01.1): it permits
    * changing the section's standard text; the additional review step approves the change. One active request
    * per section; the reason is required. The deviation report is recomputed and stored with the draft.
    */
  def requestDeviation(
      id: DocumentId,
      section: SectionId,
      reason: String,
      actor: UserId
  ): IO[DocumentError, (RecordedDeviationRequest, DocumentVersion)] =
    for
      current  <- lockDraft(id)
      template <- templates.version(current.templateId, current.templateVersion)
      text = reason.trim
      _ <- ZIO.when(text.isEmpty)(ZIO.fail(DocumentError.Invalid(List("a deviation request needs a reason"))))
      _ <- ZIO.when(text.length > MaxReasonLength)(
        ZIO.fail(DocumentError.Invalid(List(s"the reason must not exceed $MaxReasonLength characters")))
      )
      _ <- ZIO.unless(template.content.sections.exists(s => s.id == section && s.locked))(
        ZIO.fail(
          DocumentError.Invalid(
            List(
              s"'${section.value}' is not a locked section of the template; only locked sections need a request"
            )
          )
        )
      )
      existing <- sql(DocumentStore.deviationRequests(current.id))
      _        <- ZIO.when(existing.exists(r => r.active && r.sectionId == section))(
        ZIO.fail(alreadyRequested(section))
      )
      now <- databaseNow
      request = RecordedDeviationRequest(
        UUID.randomUUID(),
        current.id,
        section,
        text,
        actor,
        now,
        None,
        None,
        None
      )
      _ <- sql(DocumentStore.insertDeviationRequest(request)).mapError {
        case DocumentError.Database(DbError.UniqueViolation(_, _)) => alreadyRequested(section)
        case other                                                 => other
      }
      updated <- storeDeviations(current, template, existing :+ request)
      _       <- record(
        actor,
        "document.deviation-requested",
        updated,
        Json.Obj(
          "requestId" -> Json.Str(request.id.toString),
          "section"   -> Json.Str(section.value),
          "reason"    -> Json.Str(text)
        )
      )
    yield (request, updated)

  /** Withdraws an active deviation request of the open draft. A section that still deviates is then an
    * unauthorized deviation: further content changes must restore its standard text, and submission is
    * blocked until they do or the deviation is requested again.
    */
  def withdrawDeviation(id: DocumentId, requestId: UUID, actor: UserId): IO[DocumentError, DocumentVersion] =
    for
      current  <- lockDraft(id)
      existing <- sql(DocumentStore.deviationRequests(current.id))
      request  <- ZIO
        .fromOption(existing.find(_.id == requestId))
        .orElseFail(
          DocumentError.NotFound(s"deviation request $requestId does not exist for version ${current.label}")
        )
      _ <- ZIO.unless(request.active)(
        ZIO.fail(DocumentError.Conflict(s"deviation request $requestId is already withdrawn"))
      )
      now <- databaseNow
      _   <- sql(DocumentStore.withdrawDeviationRequest(requestId, actor, now))
        .filterOrFail(identity)(DocumentError.Conflict(s"deviation request $requestId is already withdrawn"))
      template <- templates.version(current.templateId, current.templateVersion)
      withdrawn = request.copy(withdrawnBy = Some(actor), withdrawnAt = Some(now))
      updated <- storeDeviations(
        current,
        template,
        existing.map(r => if r.id == requestId then withdrawn else r)
      )
      _ <- record(
        actor,
        "document.deviation-withdrawn",
        updated,
        Json.Obj("requestId" -> Json.Str(requestId.toString), "section" -> Json.Str(request.sectionId.value))
      )
    yield updated

  /** The open version of a document, locked, if it is a draft. */
  private def lockDraft(id: DocumentId): IO[DocumentError, DocumentVersion] =
    for
      current <- sql(DocumentStore.lockOpenVersion(id, templates)).someOrFail(noOpenVersion(id))
      _       <- ZIO.unless(Versioning.isEditable(current.status))(
        ZIO.fail(
          DocumentError.Conflict(
            s"version ${current.label} is ${current.status}; deviation requests only change while it is a draft"
          )
        )
      )
    yield current

  private def activeRequests(versionId: UUID): IO[DocumentError, List[DeviationRequest]] =
    sql(DocumentStore.deviationRequests(versionId)).map(RecordedDeviationRequest.active)

  /** Recomputes and stores the deviation report of a draft for the given requests. */
  private def storeDeviations(
      current: DocumentVersion,
      template: TemplateVersion,
      requests: List[RecordedDeviationRequest]
  ): IO[DocumentError, DocumentVersion] =
    val report =
      DeviationReport.compare(template.content, current.content, RecordedDeviationRequest.active(requests))
    sql(DocumentStore.updateDeviationReport(current.id, report, templates))
      .filterOrFail(identity)(changedConcurrently(current))
      .as(current.copy(deviations = report))

  /** On submission: the content must follow the edit rules with the active requests (no unauthorized
    * deviation reaches review), and the deviation report is stored as reviewers will see it.
    */
  private def checkDeviations(current: DocumentVersion): IO[DocumentError, DocumentVersion] =
    for
      template <- templates.version(current.templateId, current.templateVersion)
      requests <- activeRequests(current.id)
      violations = EditRules.check(template.content, current.content, requests).map(_.reason)
      _ <- ZIO.unless(violations.isEmpty)(ZIO.fail(DocumentError.Invalid(violations)))
      report = DeviationReport.compare(template.content, current.content, requests)
      updated <-
        if report == current.deviations then ZIO.succeed(current)
        else
          sql(DocumentStore.updateDeviationReport(current.id, report, templates))
            .filterOrFail(identity)(changedConcurrently(current))
            .as(current.copy(deviations = report))
    yield updated

  private def released(id: TemplateId, version: Int): IO[DocumentError, TemplateVersion] =
    templates
      .version(id, version)
      .filterOrFail(_.status == TemplateStatus.Released)(
        DocumentError.Conflict(
          s"template ${id.value} version $version is not released; documents are created from the template catalog"
        )
      )

  private def profile(classId: DocumentClassId, version: Int): IO[DocumentError, MetadataProfile] =
    classes.profile(classId, Some(version)).mapError(metadataError)

  /** Validates the entered values for a draft and adds the system and computed values. */
  private def prepare(
      profile: MetadataProfile,
      template: TemplateVersion,
      content: TemplateContent,
      entered: Map[String, FieldEntry],
      title: String,
      id: DocumentId,
      number: VersionNo,
      documentNumber: Option[String],
      retention: Option[FieldEntry]
  ): IO[DocumentError, Map[String, FieldEntry]] =
    // The retention rule comes from the class or records management (DMS-133): an entered value equal to it is
    // accepted, another one rejected; a value carried over with its origin (e.g. one records management set
    // before) is replaced.
    val retentionIssue = entered
      .get(ClassRetention.Field)
      .filter(_.origin == ValueOrigin.Manual)
      .filterNot(e => retention.exists(_.value == e.value))
      .map(_ =>
        FieldIssue(
          ClassRetention.Field,
          profile.field(ClassRetention.Field).fold(ClassRetention.Field)(_.definition.label),
          IssueKind.NotEditable
        )
      )
    val input = entered - ClassRetention.Field +
      (TitleField -> FieldEntry(MetadataValue.Text(title.trim), ValueOrigin.Manual))
    val system = systemValues(id, profile, number, template, documentNumber) ++
      retention.map(ClassRetention.Field -> _)
    // A field filled by a reference field only takes values from its source (ADR 0023).
    def fromReference(key: String, origin: ValueOrigin) =
      TemplateContent.referenceOf(content, key).exists((r, _) => origin != ValueOrigin.System(r.source.key))
    val notEnterable = input.toList.sortBy(_._1).collect {
      case (key, entry)
          if entry.origin == ValueOrigin.Computed || PlatformFields.contains(key) ||
            fromReference(key, entry.origin) =>
        FieldIssue(key, profile.field(key).fold(key)(_.definition.label), IssueKind.NotEditable)
    }
    val base   = input ++ system
    val issues = notEnterable ++ retentionIssue.toList ++ Validation.validate(
      profile,
      metadataOf(base) + (StatusField -> MetadataValue.Selection(DocumentStatus.Draft.toString)),
      DocumentStatus.Draft
    )
    for
      _        <- ZIO.unless(issues.isEmpty)(ZIO.fail(DocumentError.InvalidValues(issues)))
      computed <- templates.computed(template, content, metadataOf(base))
    yield base ++ computed.map((k, v) => k -> FieldEntry(v, ValueOrigin.Computed))

  /** Values a business system set (origin `System`, other than the platform) cannot be replaced by manual
    * values (US-03.1): a manual value equal to the system value keeps the system value and its origin, a
    * different one is rejected as not editable. Removing a value or replacing it with a value of a system is
    * the job of that system's integration.
    */
  private def protectSystemValues(
      profile: MetadataProfile,
      current: DocumentVersion,
      entered: Map[String, FieldEntry]
  ): IO[DocumentError, Map[String, FieldEntry]] =
    val fromSystem  = current.values.filter((_, e) => isExternal(e.origin))
    val overwritten = entered.toList.sortBy(_._1).collect {
      case (key, FieldEntry(value, ValueOrigin.Manual)) if fromSystem.get(key).exists(_.value != value) =>
        FieldIssue(key, profile.field(key).fold(key)(_.definition.label), IssueKind.NotEditable)
    }
    if overwritten.nonEmpty then ZIO.fail(DocumentError.InvalidValues(overwritten))
    else
      ZIO.succeed(entered.map {
        case (key, FieldEntry(_, ValueOrigin.Manual)) if fromSystem.contains(key) => key -> fromSystem(key)
        case other                                                                => other
      })

  /** Edit rules for a changed content: locked sections keep the template's text unless a deviation request
    * for the section is recorded, computed and reference fields stay those of the template, and the content
    * must fit the profile.
    */
  private def checkContent(
      template: TemplateVersion,
      content: TemplateContent,
      requests: List[DeviationRequest]
  ): IO[DocumentError, Unit] =
    val computed = Option
      .when(content.computed != template.content.computed)(
        "computed fields are defined by the template and cannot be changed in a document"
      )
      .toList ++ Option
      .when(content.references != template.content.references)(
        "reference fields are defined by the template and cannot be changed in a document"
      )
      .toList
    val violations = EditRules.check(template.content, content, requests).map(_.reason)
    for
      _ <- ZIO.unless(computed.isEmpty && violations.isEmpty)(
        ZIO.fail(DocumentError.Invalid(computed ++ violations))
      )
      issues <- templates.check(template, content)
      _      <- ZIO.unless(issues.isEmpty)(ZIO.fail(DocumentError.Invalid(issues)))
    yield ()

  private def transition(
      document: Document,
      before: DocumentVersion,
      event: LifecycleEvent,
      actor: UserId,
      successor: Option[UUID] = None,
      withdrawalReason: Option[String] = None,
      publicationFailed: Boolean = false
  ): IO[DocumentError, DocumentVersion] =
    for
      current <-
        if event == LifecycleEvent.Submit && before.status == DocumentStatus.Draft then
          checkDeviations(before)
        else ZIO.succeed(before)
      profile <- profile(document.classId, current.classVersion)
      values = statusMetadata(current)
      computedInputs <- ZIO
        .when(event == LifecycleEvent.Approve || event == LifecycleEvent.Submit)(
          missingComputedInputs(profile, current)
        )
        .someOrElse(Nil)
      target <- ZIO
        .fromEither(event match
          case LifecycleEvent.Approve =>
            Lifecycle
              .approve(current.status, current.createdBy, actor)
              .left
              .map(TransitionBlocked.NotAllowed(_))
              .flatMap { target =>
                val issues = Validation.validate(profile, values, target)
                val all    = issues ++ computedInputs.filterNot(i => issues.exists(_.field == i.field))
                Either.cond(all.isEmpty, target, TransitionBlocked.Incomplete(target, all))
              }
          case LifecycleEvent.Submit =>
            Validation.checkSubmission(profile, values, current.status, computedInputs)
          // Stopping a submitted version never depends on its values: they are frozen, and a class can name
          // `Withdrawn` among the statuses a field is required in. It would be the way out that fails.
          case LifecycleEvent.Withdraw if current.status != DocumentStatus.Draft =>
            Lifecycle.transition(current.status, event).left.map(TransitionBlocked.NotAllowed(_))
          case other => Validation.checkTransition(profile, values, current.status, other))
        .mapError {
          case TransitionBlocked.NotAllowed(e)             => DocumentError.Lifecycle(e)
          case TransitionBlocked.Incomplete(target, issue) => DocumentError.Incomplete(target, issue)
        }
      now <- Clock.instant
      _   <- ZIO.when(event == LifecycleEvent.Publish)(checkDue(current, now))
      snapshot = Option.when(target == DocumentStatus.Approved)(
        MetadataSnapshot(
          metadataOf(current.values) ++ values + (StatusField -> MetadataValue.Selection(target.toString)),
          now
        )
      )
      // On approval the valid-from date is frozen with the snapshot; on supersession the successor is named.
      validFrom    = snapshot.flatMap(_ => Publication.validFrom(current.metadata))
      supersededBy = successor.filter(_ => target == DocumentStatus.Superseded)
      _ <- ZIO.when(target == DocumentStatus.Published)(supersedeValid(document, current, actor))
      // A version that becomes valid gets its review date (US-07.1), kept outside the frozen package (V18).
      reviewDueOn <- ZIO
        .when(current.status == DocumentStatus.Approved && target == DocumentStatus.Published)(
          reviewDate(document, current, now)
        )
        .map(_.flatten)
      _ <- sql(
        withdrawalReason.fold(
          DocumentStore.changeStatus(
            current.id,
            current.status,
            target,
            actor,
            now,
            snapshot,
            validFrom,
            supersededBy
          )
        )(DocumentStore.withdrawApproved(current.id, actor, now, _))
      )
        .filterOrFail(identity)(changedConcurrently(current))
        .mapError(conflict(s"document ${document.id.value} already has a valid version in this scope"))
      _ <- ZIO.foreachDiscard(reviewDueOn)(d => sql(ReviewStore.setDueOn(current.id, d)))
      // A revision for another scope replaced nothing: the version under review still needs a decision (ADR 0063).
      _ <- ZIO.when(current.status == DocumentStatus.Approved && target == DocumentStatus.Published)(
        ReviewService.reopenFor(audit, current.id)
      )
      // Who must acknowledge is fixed by the class's settings at publication (DMS-100, ADR 0048).
      _ <- ZIO.when(
        current.status == DocumentStatus.Approved && target == DocumentStatus.Published &&
          current.requiresAcknowledgement
      )(sql(AcknowledgementService.recordAudience(current.id, document.classId.value)))
      updated = current.copy(
        status = target,
        updatedAt = now,
        metadataSnapshot = snapshot.orElse(current.metadataSnapshot),
        releasedBy = if snapshot.isDefined then Some(actor) else current.releasedBy,
        releasedAt = if snapshot.isDefined then Some(now) else current.releasedAt,
        validFrom = validFrom.orElse(current.validFrom),
        supersededBy = supersededBy.orElse(current.supersededBy),
        // The database records the same moment on Approved -> Published (migration V15).
        publishedAt =
          if current.status == DocumentStatus.Approved && target == DocumentStatus.Published then Some(now)
          else current.publishedAt,
        withdrawal = withdrawalReason.map(VersionWithdrawal(now, actor, _)).orElse(current.withdrawal)
      )
      details = Json.Obj(
        (List(
          "event" -> Json.Str(event.toString),
          "from"  -> Json.Str(current.status.toString),
          "to"    -> Json.Str(target.toString)
        ) ++ validFrom.map(d => "validFrom" -> Json.Str(d.toString)) ++
          supersededBy.map(s => "supersededBy" -> Json.Str(s.toString)) ++
          reviewDueOn.map(d => "reviewDueOn" -> Json.Str(d.toString)) ++
          withdrawalReason.map(r => "reason" -> Json.Str(r)) ++
          Option.when(publicationFailed)("publicationFailed" -> Json.Bool(true)))*
      )
      _ <- record(
        actor,
        withdrawalReason.fold(statusAction(event))(_ => WithdrawnBeforeValid),
        updated,
        details,
        withdrawalReason
      )
      _ <- publish(
        if event == LifecycleEvent.Submit then "document.submitted" else "document.status-changed",
        document,
        updated,
        Some(current.status)
      )
    yield updated

  /** The review date of a version that becomes valid now: from its valid-from date (or today in the tenant's
    * time zone), the class's interval and the `nextReview` value it was approved with (see [[Review.dueOn]]).
    */
  private def reviewDate(
      document: Document,
      v: DocumentVersion,
      now: Instant
  ): IO[DocumentError, Option[LocalDate]] =
    for
      policy <- classes.reviewPolicy(document.classId).mapError(metadataError)
      zone   <- sql(DocumentStore.tenantZone)
      values     = v.metadataSnapshot.fold(v.metadata)(_.values)
      nextReview = values.get(NextReviewField).collect { case MetadataValue.Date(d) => d }
    yield Review.dueOn(v.validFrom.getOrElse(LocalDate.ofInstant(now, zone)), nextReview, policy)

  /** A version is never published before 00:00 of its valid-from date in the tenant's time zone (DMS-45); the
    * publication job publishes it then.
    */
  private def checkDue(v: DocumentVersion, now: Instant): IO[DocumentError, Unit] =
    sql(DocumentStore.tenantZone).flatMap { zone =>
      val validFrom = v.validFrom.orElse(Publication.validFrom(v.metadata))
      ZIO
        .unless(Publication.isDue(validFrom, zone, now))(
          ZIO.fail(
            DocumentError.Conflict(
              s"version ${v.label} becomes valid on ${validFrom.fold("")(_.toString)} ($zone); it is " +
                "published automatically then"
            )
          )
        )
        .unit
    }

  /** Fields without value that computed fields of the version read (DMS-83): an approved document must not
    * contain computed fields without value, so these fields are required for the approval.
    */
  private def missingComputedInputs(
      profile: MetadataProfile,
      current: DocumentVersion
  ): IO[DocumentError, List[FieldIssue]] =
    for
      template <- templates.version(current.templateId, current.templateVersion)
      missing  <- templates.missingComputed(template, current.content, metadataOf(current.values))
    yield missing.values.flatten.toList.distinct.sorted.map { key =>
      val label = profile.field(key).fold(key)(_.definition.label)
      FieldIssue(key, label, IssueKind.MissingRequired(DocumentStatus.Approved))
    }

  /** Publication replaces the valid version of the same scope (business concept 5.2) in the same transaction;
    * the replaced version names its successor, which the database checks on commit (migration V12).
    */
  private def supersedeValid(
      document: Document,
      publishing: DocumentVersion,
      actor: UserId
  ): IO[DocumentError, Unit] =
    for
      versions <- sql(DocumentStore.versions(document.id, templates))
      _        <- ZIO.foreachDiscard(
        versions.filter(v =>
          v.id != publishing.id && Versioning.isValid(v.status) && v.scope == publishing.scope
        )
      )(valid =>
        sql(DocumentStore.lockVersion(valid.id, templates)) *> transition(
          document,
          valid,
          LifecycleEvent.Supersede,
          actor,
          Some(publishing.id)
        )
      )
    yield ()

  private def record(
      actor: UserId,
      action: String,
      v: DocumentVersion,
      details: Json.Obj,
      reason: Option[String] = None
  ) =
    val automated = isPlatform(actor)
    audit
      .record(
        AuditEvent(
          Actor(actor.value, if automated then ActorKind.Technical else ActorKind.Person),
          action,
          AuditObject("document", Some(v.documentId.value), Some(v.label)),
          if automated then Channel.Automation else Channel.Api,
          Jsonb(details.merge(Json.Obj("versionId" -> Json.Str(v.id.toString))).toJson),
          reason
        )
      )
      .mapError(DocumentError.Database(_))
      .unit

  /** @param previous
    *   for status changes the status before, so that subscribers tell a publication (from Approved) from a
    *   recertification (from ReviewDue, DMS-58)
    */
  private def publish(
      eventType: String,
      document: Document,
      v: DocumentVersion,
      previous: Option[DocumentStatus] = None
  ) =
    val payload = Json.Obj(
      (List(
        "documentId"      -> Json.Str(document.id.value.toString),
        "versionId"       -> Json.Str(v.id.toString),
        "version"         -> Json.Str(v.label),
        "status"          -> Json.Str(v.status.toString),
        "classId"         -> Json.Str(document.classId.value.toString),
        "classVersion"    -> Json.Num(v.classVersion),
        "templateId"      -> Json.Str(v.templateId.value.toString),
        "templateVersion" -> Json.Num(v.templateVersion),
        // True if the workflow adds the additional review step for deviations (DMS-79).
        "requiresExtraReview" -> Json.Bool(v.requiresExtraReview),
        // Minor versions take the editorial workflow path (DMS-46); acknowledgement per version (6.3).
        "changeKind"              -> Json.Str(v.changeKind.toString),
        "requiresAcknowledgement" -> Json.Bool(v.requiresAcknowledgement)
      ) ++ v.validFrom.map(d => "validFrom" -> Json.Str(d.toString)) ++
        document.number.map(n => "documentNumber" -> Json.Str(n)) ++
        previous.map(p => "previousStatus" -> Json.Str(p.toString)))*
    )
    Outbox
      .publish("document", document.id.value, eventType, Jsonb(payload.toJson))
      .mapError(DocumentError.Database(_))
      .unit

object DocumentService:
  /** With the default [[DocumentSettings]]. */
  val layer: URLayer[AuditTrail & DocumentClassService & TemplateSource, DocumentService] =
    ZLayer.fromFunction(DocumentService(_, _, _))

  def configured(
      settings: DocumentSettings
  ): URLayer[AuditTrail & DocumentClassService & TemplateSource, DocumentService] =
    ZLayer.fromFunction(DocumentService(_, _, _, settings))

  /** Audit action of [[DocumentService.withdrawApproved]]. */
  val WithdrawnBeforeValid = "document.withdrawn-before-valid"

  /** Outbox event of [[DocumentService.withdrawApproved]] for the notification of approvers and owner; the
    * status change itself is a `document.status-changed` like every other.
    */
  val ApprovedVersionWithdrawn = "document.approved-version-withdrawn"

  /** Why `v` cannot be withdrawn at `now`, if so: it must be approved, and before its valid-from date unless
    * its publication failed for good.
    */
  private def withdrawalRefusal(
      v: DocumentVersion,
      publicationFailed: Boolean,
      zone: ZoneId,
      now: Instant
  ): Option[DocumentError] =
    def refused(refusal: WithdrawalRefusal, message: String) =
      Some(DocumentError.WithdrawalRefused(refusal, s"version ${v.label} $message"))
    v.status match
      case DocumentStatus.Approved if !publicationFailed && Publication.isDue(v.validFrom, zone, now) =>
        refused(
          WithdrawalRefusal.Due,
          s"became due on ${v.validFrom.fold("its approval")(_.toString)} and is being published; it can " +
            "only be withdrawn as a valid version"
        )
      case DocumentStatus.Approved  => None
      case DocumentStatus.Withdrawn =>
        refused(WithdrawalRefusal.AlreadyWithdrawn, "has been withdrawn already")
      case status if v.publishedAt.isDefined =>
        refused(
          WithdrawalRefusal.AlreadyPublished,
          s"was published first and is $status now; it can only be withdrawn as a valid version"
        )
      case status =>
        refused(WithdrawalRefusal.NotApproved, s"is $status; only an approved version can be withdrawn")

  /** The platform as actor of scheduled publications (DMS-45); recorded as technical actor. */
  val Scheduler: UserId = UserId("system:publication")

  /** Actors of the platform itself (`system:...`, e.g. [[Scheduler]]): recorded as technical actors through
    * the automation channel.
    */
  def isPlatform(actor: UserId): Boolean = actor.value.startsWith("system:")

  private val TitleField      = "title"
  private val StatusField     = "status"
  private val OwnerField      = "owner"
  private val NextReviewField = "nextReview"
  private val UnitField       = "organizationalUnit"
  private val Sha256          = "[0-9a-f]{64}".r

  /** Longest accepted reason of a deviation request. */
  val MaxReasonLength: Int = 4000

  /** Fields only the platform sets. The retention rule (DMS-133) comes from the class or records management
    * and is handled separately (see `retentionEntry`).
    */
  private val PlatformFields: Set[String] =
    Set(
      "documentId",
      CoreFields.DocumentNumber,
      "documentClass",
      "version",
      "template",
      "templateVersion",
      StatusField,
      "renditionChecksum"
    )

  private def sql[A](f: DbTx ?=> A): IO[DocumentError, A] = Db.sql(f).mapError(DocumentError.Database(_))

  /** A metadata error as document error, e.g. when a class used by a document cannot be read. */
  def metadataError(error: MetadataError): DocumentError = error match
    case MetadataError.NotFound(m) => DocumentError.NotFound(m)
    case MetadataError.Invalid(p)  => DocumentError.Invalid(p)
    case MetadataError.Conflict(m) => DocumentError.Conflict(m)
    case MetadataError.Database(e) => DocumentError.Database(e)

  /** The current time at the precision PostgreSQL stores (microseconds), so that a value and its stored form
    * are equal, e.g. the requests inside a deviation report.
    */
  private val databaseNow: UIO[Instant] = Clock.instant.map(_.truncatedTo(ChronoUnit.MICROS))

  /** Values the platform sets: identifiers (and the document number, if any), class, version number and
    * template.
    */
  private def systemValues(
      id: DocumentId,
      profile: MetadataProfile,
      number: VersionNo,
      template: TemplateVersion,
      documentNumber: Option[String]
  ): Map[String, FieldEntry] =
    (Map(
      "documentId"      -> id.value.toString,
      "documentClass"   -> profile.classKey.value,
      "version"         -> number.show,
      "template"        -> template.templateId.value.toString,
      "templateVersion" -> template.version.toString
    ) ++ documentNumber.map(CoreFields.DocumentNumber -> _))
      .map((k, v) => k -> FieldEntry(MetadataValue.Text(v), ValueOrigin.Platform))

  /** A missing retention rule on submission names its cause (DMS-133): the class has none. */
  private def noRule(issue: FieldIssue): FieldIssue = issue match
    case FieldIssue(ClassRetention.Field, label, IssueKind.MissingRequired(_)) =>
      FieldIssue(ClassRetention.Field, label, IssueKind.NoRetentionRule)
    case other => other

  /** A value set by a business system rather than by the platform. */
  private def isExternal(origin: ValueOrigin): Boolean = origin match
    case ValueOrigin.System(_) => origin != ValueOrigin.Platform
    case _                     => false

  private def metadataOf(values: Map[String, FieldEntry]): Map[String, MetadataValue] =
    values.map((k, e) => k -> e.value)

  /** The profile values of a version (without computed values) plus status and rendition checksum, as the
    * validation of a status change needs them.
    */
  private def statusMetadata(v: DocumentVersion): Map[String, MetadataValue] =
    v.values.collect { case (k, e) if e.origin != ValueOrigin.Computed => k -> e.value } +
      (StatusField -> MetadataValue.Selection(v.status.toString)) ++
      v.rendition.map(r => "renditionChecksum" -> MetadataValue.Text(r.sha256))

  private def ownerOf(values: Map[String, FieldEntry]): Option[UserId] =
    values.get(OwnerField).collect { case FieldEntry(MetadataValue.PersonRef(user), _) => UserId(user) }

  private def titleOf(v: DocumentVersion): Option[String] =
    v.values.get(TitleField).collect { case FieldEntry(MetadataValue.Text(t), _) => t }

  private def origins(values: Map[String, FieldEntry]): Json =
    Json.Obj(values.toList.sortBy(_._1).map((k, e) => k -> Json.Str(originName(e.origin)))*)

  private def originName(origin: ValueOrigin): String = origin match
    case ValueOrigin.Manual         => "manual"
    case ValueOrigin.System(source) => s"system:$source"
    case ValueOrigin.Computed       => "computed"

  /** Changed fields with before and after values (business concept 7.3). */
  private def fieldChanges(before: Map[String, FieldEntry], after: Map[String, FieldEntry]): Json =
    def json(e: Option[FieldEntry]) = e.flatMap(_.value.toJsonAST.toOption).getOrElse(Json.Null)
    Json.Obj(
      (before.keySet ++ after.keySet).toList.sorted
        .filter(k => before.get(k) != after.get(k))
        .map(k => k -> Json.Obj("before" -> json(before.get(k)), "after" -> json(after.get(k))))*
    )

  private def statusAction(event: LifecycleEvent): String = event match
    case LifecycleEvent.Submit  => "document.submitted"
    case LifecycleEvent.Approve => "document.approved"
    case LifecycleEvent.Publish => "document.published"
    case _                      => "document.status-changed"

  private def documentNotFound(id: DocumentId) =
    DocumentError.NotFound(s"document ${id.value} does not exist")

  private def versionNotFound(id: UUID) = DocumentError.NotFound(s"document version $id does not exist")

  private def alreadyRequested(section: SectionId) =
    DocumentError.Conflict(s"section '${section.value}' already has an active deviation request")

  private def noOpenVersion(id: DocumentId) =
    DocumentError.Conflict(s"document ${id.value} has no open version")

  private def changedConcurrently(v: DocumentVersion) =
    DocumentError.Conflict(s"version ${v.label} of document ${v.documentId.value} was changed concurrently")

  private def conflict(message: String)(error: DocumentError): DocumentError = error match
    case DocumentError.Database(DbError.UniqueViolation(_, _)) => DocumentError.Conflict(message)
    case other                                                 => other
