package dms.documents.core

import com.augustnagro.magnum.DbTx
import dms.audit.*
import dms.db.queue.Outbox
import dms.db.{Db, DbError, Jsonb}
import dms.documents.*
import dms.documents.core.MetadataJson.given
import dms.documents.metadata.*
import dms.kernel.{DocumentId, TemplateId, UserId, VersionNo}
import dms.templates.{
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

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

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
    case Conflict(m) => m
    case Database(e) => s"database error: $e"

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
  * a business system filling master data uses `System(source)`).
  */
final case class NewDocument(
    templateId: TemplateId,
    templateVersion: Int,
    title: String,
    values: Map[String, FieldEntry]
)

/** Change of a draft: the entered values replace the previous ones; content, if given, replaces the content.
  * The title defaults to the current title.
  */
final case class DraftChange(
    title: Option[String],
    values: Map[String, FieldEntry],
    content: Option[TemplateContent]
)

/** A document with all its versions and the effective profile of its class version. */
final case class DocumentDetails(
    document: Document,
    versions: List[DocumentVersion],
    profile: MetadataProfile
):
  def open: Option[DocumentVersion] = versions.find(v => Versioning.isOpen(v.status))

/** Creates documents from released templates and manages their versions (business concept 4.3, 5.1, 5.2).
  *
  * Every method must run inside `Db.transact(tenant)`. Field values are validated against the class profile
  * version, computed fields are evaluated with the template, and the origin of every value is stored. Only
  * drafts change; status changes follow [[Lifecycle]] and are blocked while required fields of the target
  * status are missing. Every change is recorded in the audit trail, and creation and status changes are
  * published to the outbox, both in the same transaction. Authorization is the caller's job.
  */
final class DocumentService(audit: AuditTrail, classes: DocumentClassService, templates: TemplateSource):
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
      values <- prepare(profile, template, template.content, request.values, request.title, id, number)
      now    <- Clock.instant
      document = Document(
        id,
        profile.classId,
        profile.version,
        request.title.trim,
        ownerOf(values),
        actor,
        template.templateId,
        template.version,
        now
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
          "classId"         -> Json.Str(profile.classId.value.toString),
          "classVersion"    -> Json.Num(profile.version),
          "origins"         -> origins(values)
        )
      )
      _ <- publish("document.created", document, version)
    yield (document, version)

  /** A document with all versions, oldest first. */
  def get(id: DocumentId): IO[DocumentError, DocumentDetails] =
    for
      document <- sql(DocumentStore.document(id)).someOrFail(documentNotFound(id))
      versions <- sql(DocumentStore.versions(id, templates))
      profile  <- profile(document.classId, document.classVersion)
    yield DocumentDetails(document, versions, profile)

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
      _ <- ZIO.when(change.content.exists(_ != current.content))(checkContent(template, content, requests))
      title = change.title.getOrElse(titleOf(current).getOrElse(document.title))
      entered <- protectSystemValues(profile, current, change.values)
      values  <- prepare(profile, template, content, entered, title, id, current.number)
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

  /** Submits the open draft for review. Blocked while fields required in review are missing. */
  def submit(id: DocumentId, actor: UserId): IO[DocumentError, DocumentVersion] =
    for
      document <- sql(DocumentStore.document(id)).someOrFail(documentNotFound(id))
      current  <- sql(DocumentStore.lockOpenVersion(id, templates)).someOrFail(noOpenVersion(id))
      updated  <- transition(document, current, LifecycleEvent.Submit, actor)
    yield updated

  /** Applies a lifecycle event to a version (review, approval, publication, ...). Approval needs a person
    * other than the author and freezes the version with a metadata snapshot; publication supersedes the valid
    * version of the same scope.
    */
  def changeStatus(
      versionId: UUID,
      event: LifecycleEvent,
      actor: UserId
  ): IO[DocumentError, DocumentVersion] =
    for
      current  <- sql(DocumentStore.lockVersion(versionId, templates)).someOrFail(versionNotFound(versionId))
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

  /** Starts the next version of a document from its valid version: a major version for a change of content, a
    * minor version (with a reason) for an editorial change. A document has at most one open version.
    */
  def newVersion(
      id: DocumentId,
      kind: ChangeKind,
      reason: Option[String],
      actor: UserId
  ): IO[DocumentError, DocumentVersion] =
    for
      document <- sql(DocumentStore.document(id)).someOrFail(documentNotFound(id))
      versions <- sql(DocumentStore.versions(id, templates))
      _        <- ZIO.foreachDiscard(versions.find(v => Versioning.isOpen(v.status)).toList)(open =>
        ZIO.fail(DocumentError.Conflict(s"version ${open.label} is still ${open.status}; finish it first"))
      )
      base <- ZIO
        .fromOption(versions.filter(v => Versioning.isValid(v.status)).maxByOption(_.number))
        .orElseFail(DocumentError.Conflict("only a document with a valid version can get a new version"))
      changeReason = reason.map(_.trim).filter(_.nonEmpty)
      _ <- ZIO.when(kind == ChangeKind.Minor && changeReason.isEmpty)(
        ZIO.fail(DocumentError.Invalid(List("an editorial (minor) version needs a reason")))
      )
      number = Versioning.next(versions.filter(_.releasedAt.isDefined).map(_.number).maxOption, kind)
      workingState <- sql(DocumentStore.workingStates(id, number)).map(Versioning.nextWorkingState)
      template     <- templates.version(base.templateId, base.templateVersion)
      profile      <- profile(document.classId, base.classVersion)
      baseRequests <- sql(DocumentStore.deviationRequests(base.id)).map(_.filter(_.active))
      now          <- Clock.instant
      versionId = UUID.randomUUID()
      carried   = baseRequests.map(r =>
        r.copy(id = UUID.randomUUID(), versionId = versionId, basedOn = Some(r.id))
      )
      version = base.copy(
        id = versionId,
        number = number,
        workingState = workingState,
        status = DocumentStatus.Draft,
        changeKind = kind,
        changeReason = changeReason,
        values = base.values ++ systemValues(id, profile, number, template),
        metadataSnapshot = None,
        rendition = None,
        createdBy = actor,
        createdAt = now,
        updatedAt = now,
        releasedBy = None,
        releasedAt = None,
        deviations = DeviationReport.compare(template.content, base.content, carried.map(_.request))
      )
      _ <- sql {
        DocumentStore.insertVersion(version, templates)
        DocumentStore.insertWorkingState(version, actor, templates)
        carried.foreach(DocumentStore.insertDeviationRequest)
      }.mapError(conflict(s"document ${id.value} was changed concurrently"))
      _ <- record(
        actor,
        "document.version-created",
        version,
        Json.Obj(
          "basedOn"           -> Json.Str(base.label),
          "changeKind"        -> Json.Str(kind.toString),
          "reason"            -> changeReason.fold(Json.Null)(Json.Str(_)),
          "deviationRequests" -> Json.Arr(carried.map(r => Json.Str(r.id.toString))*)
        )
      )
      _ <- publish("document.version-created", document, version)
    yield version

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
    classes.profile(classId, Some(version)).mapError {
      case MetadataError.NotFound(m) => DocumentError.NotFound(m)
      case MetadataError.Invalid(p)  => DocumentError.Invalid(p)
      case MetadataError.Conflict(m) => DocumentError.Conflict(m)
      case MetadataError.Database(e) => DocumentError.Database(e)
    }

  /** Validates the entered values for a draft and adds the system and computed values. */
  private def prepare(
      profile: MetadataProfile,
      template: TemplateVersion,
      content: TemplateContent,
      entered: Map[String, FieldEntry],
      title: String,
      id: DocumentId,
      number: VersionNo
  ): IO[DocumentError, Map[String, FieldEntry]] =
    val input  = entered + (TitleField -> FieldEntry(MetadataValue.Text(title.trim), ValueOrigin.Manual))
    val system = systemValues(id, profile, number, template)
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
    val issues = notEnterable ++ Validation.validate(
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
    * must fit the
    * profile.
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
      actor: UserId
  ): IO[DocumentError, DocumentVersion] =
    for
      current <-
        if event == LifecycleEvent.Submit && before.status == DocumentStatus.Draft then
          checkDeviations(before)
        else ZIO.succeed(before)
      profile <- profile(document.classId, current.classVersion)
      values = statusMetadata(current)
      computedInputs <- ZIO
        .when(event == LifecycleEvent.Approve)(missingComputedInputs(profile, current))
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
          case other => Validation.checkTransition(profile, values, current.status, other))
        .mapError {
          case TransitionBlocked.NotAllowed(e)             => DocumentError.Lifecycle(e)
          case TransitionBlocked.Incomplete(target, issue) => DocumentError.Incomplete(target, issue)
        }
      now <- Clock.instant
      snapshot = Option.when(target == DocumentStatus.Approved)(
        MetadataSnapshot(
          metadataOf(current.values) ++ values + (StatusField -> MetadataValue.Selection(target.toString)),
          now
        )
      )
      _ <- ZIO.when(target == DocumentStatus.Published)(supersedeValid(document, current, actor))
      _ <- sql(DocumentStore.changeStatus(current.id, current.status, target, actor, now, snapshot))
        .filterOrFail(identity)(changedConcurrently(current))
        .mapError(conflict(s"document ${document.id.value} already has a valid version in this scope"))
      updated = current.copy(
        status = target,
        updatedAt = now,
        metadataSnapshot = snapshot.orElse(current.metadataSnapshot),
        releasedBy = if snapshot.isDefined then Some(actor) else current.releasedBy,
        releasedAt = if snapshot.isDefined then Some(now) else current.releasedAt
      )
      details = Json.Obj(
        "event" -> Json.Str(event.toString),
        "from"  -> Json.Str(current.status.toString),
        "to"    -> Json.Str(target.toString)
      )
      _ <- record(actor, statusAction(event), updated, details)
      _ <- publish(
        if event == LifecycleEvent.Submit then "document.submitted" else "document.status-changed",
        document,
        updated
      )
    yield updated

  /** Publication replaces the valid version of the same scope (business concept 5.2). */
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
          actor
        )
      )
    yield ()

  private def record(actor: UserId, action: String, v: DocumentVersion, details: Json.Obj) =
    audit
      .record(
        AuditEvent(
          Actor(actor.value, ActorKind.Person),
          action,
          AuditObject("document", Some(v.documentId.value), Some(v.label)),
          Channel.Api,
          Jsonb(details.merge(Json.Obj("versionId" -> Json.Str(v.id.toString))).toJson)
        )
      )
      .mapError(DocumentError.Database(_))
      .unit

  private def publish(eventType: String, document: Document, v: DocumentVersion) =
    val payload = Json.Obj(
      "documentId"      -> Json.Str(document.id.value.toString),
      "versionId"       -> Json.Str(v.id.toString),
      "version"         -> Json.Str(v.label),
      "status"          -> Json.Str(v.status.toString),
      "classId"         -> Json.Str(document.classId.value.toString),
      "classVersion"    -> Json.Num(v.classVersion),
      "templateId"      -> Json.Str(v.templateId.value.toString),
      "templateVersion" -> Json.Num(v.templateVersion),
      // True if the workflow adds the additional review step for deviations (DMS-79).
      "requiresExtraReview" -> Json.Bool(v.requiresExtraReview)
    )
    Outbox
      .publish("document", document.id.value, eventType, Jsonb(payload.toJson))
      .mapError(DocumentError.Database(_))
      .unit

object DocumentService:
  val layer: URLayer[AuditTrail & DocumentClassService & TemplateSource, DocumentService] =
    ZLayer.fromFunction(DocumentService(_, _, _))

  private val TitleField  = "title"
  private val StatusField = "status"
  private val OwnerField  = "owner"
  private val Sha256      = "[0-9a-f]{64}".r

  /** Longest accepted reason of a deviation request. */
  val MaxReasonLength: Int = 4000

  /** Fields only the platform sets. Other system fields of the core profile (the retention rule) can be
    * entered until they are derived from the class configuration.
    */
  private val PlatformFields: Set[String] =
    Set(
      "documentId",
      "documentClass",
      "version",
      "template",
      "templateVersion",
      StatusField,
      "renditionChecksum"
    )

  private def sql[A](f: DbTx ?=> A): IO[DocumentError, A] = Db.sql(f).mapError(DocumentError.Database(_))

  /** The current time at the precision PostgreSQL stores (microseconds), so that a value and its stored form
    * are equal, e.g. the requests inside a deviation report.
    */
  private val databaseNow: UIO[Instant] = Clock.instant.map(_.truncatedTo(ChronoUnit.MICROS))

  /** Values the platform sets: identifiers, class, version number and template. */
  private def systemValues(
      id: DocumentId,
      profile: MetadataProfile,
      number: VersionNo,
      template: TemplateVersion
  ): Map[String, FieldEntry] =
    Map(
      "documentId"      -> id.value.toString,
      "documentClass"   -> profile.classKey.value,
      "version"         -> number.show,
      "template"        -> template.templateId.value.toString,
      "templateVersion" -> template.version.toString
    ).map((k, v) => k -> FieldEntry(MetadataValue.Text(v), ValueOrigin.Platform))

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
