package dms.app

import dms.authz.{AccessDenied, Action, Authorizer, Resource}
import dms.db.{Db, DbError}
import dms.documents.core.{DocumentClassService, MetadataError}
import dms.documents.core.MetadataJson.given
import dms.documents.metadata.{DocumentClassId, MetadataProfile, MetadataValue}
import dms.identity.{Authenticator, Principal}
import dms.kernel.TemplateId
import dms.templates.*
import dms.templates.core.*
import dms.templates.core.ContentJson.given
import sttp.model.StatusCode
import sttp.tapir.{Schema, SchemaType}
import sttp.tapir.generic.Configuration
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.time.Instant
import java.util.UUID

/** Tapir schemas for the template content model; sum types carry a `type` discriminator like their JSON
  * codecs. Blocks contain blocks: the nested lists refer to the derived schema by name (see
  * [[RecursiveSchemas]]).
  */
object TemplateSchemas:
  given Configuration           = Configuration.default.withDiscriminator("type")
  given Schema[SectionId]       = Schema.string
  given Schema[Mark]            = Schema.derivedEnumeration[Mark].defaultStringBased
  given Schema[Inline]          = Schema.derived
  given Schema[ResolvedInline]  = Schema.derived
  given Schema[Block]           = RecursiveTemplateSchemas.block
  given Schema[ResolvedBlock]   = RecursiveTemplateSchemas.resolvedBlock
  given Schema[Section]         = Schema.derived
  given Schema[ComputedField]   = Schema.derived
  given Schema[ReferenceSource] =
    Schema.string[ReferenceSource].description("Master data source, e.g. `partner-master`")
  given Schema[ReferenceMapping] = Schema.derived
  given Schema[ReferenceField]   = Schema.derived
  given Schema[TemplateContent]  = Schema.derived
  given Schema[ResolvedSection]  = Schema.derived
  given Schema[ResolvedContent]  = Schema.derived

private object RecursiveTemplateSchemas:
  private given Configuration  = Configuration.default.withDiscriminator("type")
  private given Schema[Mark]   = Schema.derivedEnumeration[Mark].defaultStringBased
  private given Schema[Inline] = Schema.derived

  private given Schema[ResolvedInline] = Schema.derived

  private given Schema[Block] = Schema(SchemaType.SRef(Schema.SName("dms.templates.Block")))
  val block: Schema[Block]    = Schema.derived[Block]

  private given Schema[ResolvedBlock] =
    Schema(SchemaType.SRef(Schema.SName("dms.templates.ResolvedBlock")))
  val resolvedBlock: Schema[ResolvedBlock] = Schema.derived[ResolvedBlock]

import MetadataSchemas.given
import TemplateSchemas.given

final case class TemplateRequest(
    name: String,
    description: Option[String],
    documentClassId: UUID,
    /** The class profile version the placeholders refer to. */
    documentClassVersion: Int,
    content: TemplateContent
) derives JsonCodec,
      Schema

final case class TemplateVersionView(
    templateId: UUID,
    version: Int,
    name: String,
    description: Option[String],
    documentClassId: UUID,
    documentClassVersion: Int,
    /** Draft, Testing, Released, Deprecated or Archived. */
    status: String,
    content: TemplateContent,
    createdBy: String,
    createdAt: Instant,
    editedBy: List[String],
    releasedBy: Option[String],
    releasedAt: Option[Instant]
) derives JsonCodec,
      Schema

/** A released template version in the catalog. */
final case class CatalogEntry(
    templateId: UUID,
    version: Int,
    name: String,
    description: Option[String],
    documentClassId: UUID,
    documentClassVersion: Int,
    releasedAt: Option[Instant]
) derives JsonCodec,
      Schema

/** Where a problem is: section (none for computed fields), block path, part, line and column (1-based). */
final case class LocationView(
    sectionId: Option[String],
    blockPath: String,
    part: String,
    line: Int,
    column: Int
) derives JsonCodec,
      Schema

final case class ContentIssueView(location: LocationView, message: String) derives JsonCodec, Schema

final case class CheckTemplate(documentClassId: UUID, documentClassVersion: Int, content: TemplateContent)
    derives JsonCodec,
      Schema

final case class CheckResult(valid: Boolean, issues: List[ContentIssueView]) derives JsonCodec, Schema

final case class TestTemplate(
    /** Values keyed by field key; without values, sample data covering every condition branch is generated.
      */
    values: Option[Map[String, MetadataValue]],
    /** Document language for formatting: `en` (default) or `de`. */
    language: Option[String]
) derives JsonCodec,
      Schema

final case class BranchView(location: LocationView, condition: String, outcomes: List[Boolean])
    derives JsonCodec,
      Schema

final case class TestCaseView(
    name: String,
    values: Map[String, MetadataValue],
    /** Resolved content; empty when the case failed. */
    content: Option[ResolvedContent],
    /** The resolved text without markup. */
    text: Option[String],
    /** The resolved content as HTML fragment (`article.dms-document`, marked "Draft – not valid"), like the
      * live preview; style it with the document stylesheet. Empty when the case failed.
      */
    html: Option[String],
    missingRequired: List[String],
    branches: List[BranchView],
    error: Option[String]
) derives JsonCodec,
      Schema

final case class TestRunView(
    cases: List[TestCaseView],
    coverage: List[BranchView],
    allBranchesCovered: Boolean
) derives JsonCodec,
      Schema

final case class ChangeTemplateStatus(
    /** Testing, Draft (from Testing), Released, Deprecated or Archived. */
    status: String
) derives JsonCodec,
      Schema

/** Looks up class profiles for templates through the document class service (module `documents`). */
final class DocumentClassProfiles(classes: DocumentClassService) extends ProfileSource:
  def profile(classId: UUID, version: Int): IO[TemplateError, MetadataProfile] =
    classes.profile(DocumentClassId(classId), Some(version)).mapError {
      case MetadataError.NotFound(m) => TemplateError.NotFound(m)
      case MetadataError.Invalid(p)  => TemplateError.Invalid(p)
      case MetadataError.Conflict(m) => TemplateError.Conflict(m)
      case MetadataError.Database(e) => TemplateError.Database(e)
    }

object DocumentClassProfiles:
  val layer: URLayer[DocumentClassService, ProfileSource] = ZLayer.fromFunction(DocumentClassProfiles(_))

/** Template administration for template designers (business concept 4.1) and the template catalog for
  * authors. Administration requires `EditTemplates`; releasing a version requires `ReleaseTemplate` (a second
  * template designer); the catalog requires `ReadTemplateCatalog` (any business role).
  */
object TemplateApi:
  type Env = Authenticator & Db & Authorizer & TemplateService

  private val admin      = "api" / "v1" / "admin" / "templates"
  private val templateId = path[UUID]("id").description("Template id")
  private val versionNo  = path[Int]("version").description("Template version number")

  val list: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(admin)
      .out(jsonBody[List[TemplateVersionView]])
      .description("Latest version of every template, in any status")
      .tag("Templates")
      .serverLogic(p => _ => designer(p)(_.list).map(_.map(view)))

  val create: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(admin)
      .in(jsonBody[TemplateRequest])
      .out(statusCode(StatusCode.Created).and(jsonBody[TemplateVersionView]))
      .description(
        "Creates a template with version 1 as a draft. The content is checked against the document class " +
          "profile; invalid expressions and placeholders are reported with their position (422)."
      )
      .tag("Templates")
      .serverLogic(p => req => designer(p)(_.create(draft(req), p.userId)).map(view))

  val check: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(admin / "check")
      .in(jsonBody[CheckTemplate])
      .out(jsonBody[CheckResult])
      .description("Checks content against a document class profile version without saving it")
      .tag("Templates")
      .serverLogic(p =>
        req =>
          designer(p)(_.check(req.documentClassId, req.documentClassVersion, req.content))
            .map(issues => CheckResult(issues.isEmpty, issues.map(issueView)))
      )

  val get: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(admin / templateId)
      .out(jsonBody[List[TemplateVersionView]])
      .description("All versions of a template, oldest first")
      .tag("Templates")
      .serverLogic(p => id => designer(p)(_.versions(TemplateId(id))).map(_.map(view)))

  val getVersion: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(admin / templateId / "versions" / versionNo)
      .out(jsonBody[TemplateVersionView])
      .description("One version of a template")
      .tag("Templates")
      .serverLogic(p => (id, v) => designer(p)(_.version(TemplateId(id), v)).map(view))

  val newVersion: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(admin / templateId / "versions")
      .in(jsonBody[TemplateRequest])
      .out(statusCode(StatusCode.Created).and(jsonBody[TemplateVersionView]))
      .description(
        "Creates the next version as a draft. Fails with 409 while another version is in Draft or Testing. " +
          "Released versions stay unchanged."
      )
      .tag("Templates")
      .serverLogic(p =>
        (id, req) => designer(p)(_.newVersion(TemplateId(id), draft(req), p.userId)).map(view)
      )

  val updateDraft: ZServerEndpoint[Env, Any] =
    SecuredApi.base.put
      .in(admin / templateId / "versions" / versionNo)
      .in(jsonBody[TemplateRequest])
      .out(jsonBody[TemplateVersionView])
      .description("Changes a draft; versions in any other status cannot be changed (409)")
      .tag("Templates")
      .serverLogic(p =>
        (id, v, req) => designer(p)(_.updateDraft(TemplateId(id), v, draft(req), p.userId)).map(view)
      )

  val test: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(admin / templateId / "versions" / versionNo / "test")
      .in(jsonBody[TestTemplate])
      .out(jsonBody[TestRunView])
      .description(
        "Test mode: resolves the version with the given values, or with generated sample data that shows every " +
          "condition branch, empty optional fields and long values. Reports which branches were reached."
      )
      .tag("Templates")
      .serverLogic(p =>
        (id, v, req) =>
          for
            language <- ZIO
              .fromOption(req.language.fold(Some(Language.default))(Language.parse))
              .orElseFail(invalid(s"unknown language '${req.language.getOrElse("")}'; use en or de"))
            values = req.values.map(_.map((k, value) => k -> ProfileSchema.fieldValue(value)))
            run <- designer(p)(_.test(TemplateId(id), v, values, language))
          yield testRunView(run, language)
      )

  val fields: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(admin / templateId / "versions" / versionNo / "fields")
      .out(jsonBody[TemplateFieldsView])
      .description(
        "Fields of a version in any status, for template designers: the editable fields of the class profile " +
          "with type, rules and requiredness, those the template uses first. 422 (invalid_content) if the " +
          "content does not compile against the profile."
      )
      .tag("Templates")
      .serverLogic(p =>
        (id, v) => designer(p)(_.compiled(TemplateId(id), v)).map(TemplatePreviewApi.fieldsView)
      )

  def endpoints: List[ZServerEndpoint[Env, Any]] =
    List(list, create, check, get, getVersion, newVersion, updateDraft, test, fields)

  private def draft(req: TemplateRequest): TemplateDraft =
    TemplateDraft(req.name, req.description, req.documentClassId, req.documentClassVersion, req.content)

  /** Runs `f` in a transaction of the caller's tenant after checking `EditTemplates`. */
  private[app] def designer[A](principal: Principal)(
      f: TemplateService => IO[TemplateError, A]
  ): ZIO[Db & Authorizer & TemplateService, (StatusCode, ApiError), A] =
    authorized(principal, Action.EditTemplates, _ => ZIO.succeed(Resource(principal.tenantId)))(f)

  /** Runs `f` in a transaction of the caller's tenant after checking `action` on the resource built by
    * `resource`, which may load data with the service first.
    */
  private[app] def authorized[A](
      principal: Principal,
      action: Action,
      resource: TemplateService => IO[TemplateError, Resource]
  )(
      f: TemplateService => IO[TemplateError, A]
  ): ZIO[Db & Authorizer & TemplateService, (StatusCode, ApiError), A] =
    (for
      db      <- ZIO.service[Db]
      authz   <- ZIO.service[Authorizer]
      service <- ZIO.service[TemplateService]
      result  <- db.transact(principal.tenantId)(
        for
          r       <- resource(service)
          subject <- authz.subject(principal)
          _       <- authz.check(subject, action, r)
          a       <- f(service)
        yield a
      )
    yield result).mapError(toResponse)

  private def invalid(message: String) = (StatusCode.UnprocessableEntity, ApiError("invalid", message))

  def toResponse(error: AccessDenied | DbError | TemplateError): (StatusCode, ApiError) = error match
    case AccessDenied(_, reason) =>
      (StatusCode.Forbidden, ApiError("forbidden", s"You are not allowed to do this: $reason."))
    case TemplateError.NotFound(message)     => (StatusCode.NotFound, ApiError("not_found", message))
    case e @ TemplateError.InvalidContent(_) =>
      (StatusCode.UnprocessableEntity, ApiError("invalid_content", e.describe))
    case e @ TemplateError.Invalid(_) => (StatusCode.UnprocessableEntity, ApiError("invalid", e.describe))
    case TemplateError.Lifecycle(e @ TemplateLifecycleError.SegregationOfDutiesViolated(_)) =>
      (StatusCode.Forbidden, ApiError("segregation_of_duties", e.message))
    case TemplateError.Lifecycle(e)      => (StatusCode.Conflict, ApiError("lifecycle", e.message))
    case TemplateError.Conflict(message) => (StatusCode.Conflict, ApiError("conflict", message))
    case TemplateError.Database(e)       => databaseError(e)
    case e: DbError                      => databaseError(e)

  private def databaseError(error: DbError): (StatusCode, ApiError) =
    (
      StatusCode.InternalServerError,
      ApiError("database_error", s"The request could not be completed ($error).")
    )

  private[app] def view(v: TemplateVersion): TemplateVersionView =
    TemplateVersionView(
      v.templateId.value,
      v.version,
      v.name,
      v.description,
      v.documentClassId,
      v.documentClassVersion,
      v.status.toString,
      v.content,
      v.createdBy.value,
      v.createdAt,
      v.editedBy.map(_.value),
      v.releasedBy.map(_.value),
      v.releasedAt
    )

  private def locationView(l: Location): LocationView =
    LocationView(l.sectionId.map(_.value), l.blockPath, l.part, l.line, l.column)

  private def issueView(i: ContentIssue): ContentIssueView =
    ContentIssueView(locationView(i.location), i.message)

  private def testRunView(run: TestRun, language: Language): TestRunView =
    val options = HtmlOptions.draft(language, run.template.labels, run.template.systemFields)
    def metadata(values: Map[String, FieldValue]): Map[String, MetadataValue] =
      values.flatMap((k, v) =>
        run.schema.variable(k).flatMap(x => ProfileSchema.metadataValue(v, x.valueType)).map(k -> _)
      )
    val cases = run.cases.map { (c, result) =>
      result match
        case Right(r) =>
          TestCaseView(
            c.name,
            metadata(c.values),
            Some(r.content),
            Some(r.content.plainText),
            Some(HtmlRenderer.render(r.content, options)),
            r.missingRequired,
            r.branches.map(b => BranchView(locationView(b.location), b.condition, b.outcomes.toList.sorted)),
            None
          )
        case Left(e) =>
          TestCaseView(c.name, metadata(c.values), None, None, None, Nil, Nil, Some(e.message))
    }
    val coverage =
      run.coverage.map(b => BranchView(locationView(b.location), b.condition, b.covered.toList.sorted))
    TestRunView(cases, coverage, run.coverage.forall(_.complete))
