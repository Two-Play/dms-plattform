package dms.templates.core

import com.augustnagro.magnum.DbTx
import dms.audit.*
import dms.db.{Db, DbError, Jsonb}
import dms.documents.metadata.MetadataProfile
import dms.kernel.{TemplateId, UserId}
import dms.templates.*
import zio.*
import zio.json.ast.Json

import java.util.UUID

enum TemplateError:
  case NotFound(message: String)

  /** The content does not fit the document class profile; each issue carries its location. */
  case InvalidContent(issues: List[ContentIssue])

  /** The request is invalid for another reason, e.g. test values of the wrong type. */
  case Invalid(problems: List[String])

  case Lifecycle(error: TemplateLifecycleError)

  /** A concurrent change got there first, or another version is already open. */
  case Conflict(message: String)

  case Database(error: DbError)

  def describe: String = this match
    case NotFound(m)            => m
    case InvalidContent(issues) => issues.map(_.show).mkString("; ")
    case Invalid(problems)      => problems.mkString("; ")
    case Lifecycle(e)           => e.message
    case Conflict(m)            => m
    case Database(e)            => s"database error: $e"

/** Looks up the effective metadata profile of a document class version (module `documents`). */
trait ProfileSource:
  def profile(classId: UUID, version: Int): IO[TemplateError, MetadataProfile]

/** Requested content of a template version. */
final case class TemplateDraft(
    name: String,
    description: Option[String],
    documentClassId: UUID,
    documentClassVersion: Int,
    content: TemplateContent
)

/** Result of the test mode: each case with its resolution (or why it failed) and the branch coverage.
  *
  * @param template
  *   the tested version compiled against its class profile, for rendering the cases (field labels, system
  *   fields)
  */
final case class TestRun(
    schema: TemplateSchema,
    cases: List[(TestCase, Either[ResolveError, Resolution])],
    coverage: List[BranchCoverage],
    template: PreviewTemplate
)

/** Manages templates and their versions in the current tenant (business concept 4.1).
  *
  * Every method must run inside `Db.transact(tenant)`. Content is checked against the document class profile
  * on every save; only drafts can be changed; every change is recorded in the audit trail within the same
  * transaction. Authorization is the caller's job; the lifecycle rules including the four-eyes principle for
  * releases are enforced here and again by the database.
  */
final class TemplateService(audit: AuditTrail, profiles: ProfileSource, limits: Limits = Limits.default):
  import TemplateService.*

  /** Checks content against a class profile version without saving it. */
  def check(
      classId: UUID,
      classVersion: Int,
      content: TemplateContent
  ): IO[TemplateError, List[ContentIssue]] =
    profiles
      .profile(classId, classVersion)
      .map(p => TemplateCompiler.compile(content, ProfileSchema.from(p), limits).left.getOrElse(Nil))

  /** Creates a template with version 1 as a draft. */
  def create(draft: TemplateDraft, actor: UserId): IO[TemplateError, TemplateVersion] =
    for
      _   <- validate(draft)
      now <- Clock.instant
      version = TemplateVersion(
        TemplateId.random(),
        1,
        draft.name,
        draft.description,
        draft.documentClassId,
        draft.documentClassVersion,
        TemplateStatus.Draft,
        draft.content,
        actor,
        now
      )
      _ <- sql {
        TemplateStore.insertTemplate(version.templateId, actor)
        TemplateStore.insertVersion(version)
      }
      _ <- record(actor, "template.created", version, Json.Obj())
    yield version

  /** Adds the next version as a draft. A template has at most one version in Draft or Testing at a time. */
  def newVersion(id: TemplateId, draft: TemplateDraft, actor: UserId): IO[TemplateError, TemplateVersion] =
    for
      existing <- versions(id)
      _        <- ZIO
        .fromOption(existing.find(v => TemplateLifecycle.isOpen(v.status)))
        .flip
        .mapError(open =>
          TemplateError.Conflict(s"version ${open.version} is still ${open.status}; finish it first")
        )
      _   <- validate(draft)
      now <- Clock.instant
      version = TemplateVersion(
        id,
        existing.map(_.version).max + 1,
        draft.name,
        draft.description,
        draft.documentClassId,
        draft.documentClassVersion,
        TemplateStatus.Draft,
        draft.content,
        actor,
        now
      )
      _ <- sql(TemplateStore.insertVersion(version))
        .mapError(conflict(s"template ${id.value} was changed concurrently"))
      _ <- record(actor, "template.version-created", version, Json.Obj())
    yield version

  /** Changes a draft. */
  def updateDraft(
      id: TemplateId,
      number: Int,
      draft: TemplateDraft,
      actor: UserId
  ): IO[TemplateError, TemplateVersion] =
    for
      current <- locked(id, number)
      _       <- ZIO.unless(TemplateLifecycle.isEditable(current.status))(
        ZIO.fail(TemplateError.Lifecycle(TemplateLifecycleError.NotEditable(current.status)))
      )
      _ <- validate(draft)
      changed = current.copy(
        name = draft.name,
        description = draft.description,
        documentClassId = draft.documentClassId,
        documentClassVersion = draft.documentClassVersion,
        content = draft.content
      )
      _       <- sql(TemplateStore.updateDraft(changed, actor)).filterOrFail(identity)(notEditable(current))
      updated <- version(id, number)
      _       <- record(actor, "template.draft-changed", updated, Json.Obj())
    yield updated

  /** All versions of a template, oldest first. */
  def versions(id: TemplateId): IO[TemplateError, List[TemplateVersion]] =
    sql(TemplateStore.versions(id)).filterOrFail(_.nonEmpty)(templateNotFound(id))

  def version(id: TemplateId, number: Int): IO[TemplateError, TemplateVersion] =
    sql(TemplateStore.version(id, number)).someOrFail(versionNotFound(id, number))

  /** The latest version of every template, for template designers. */
  def list: IO[TemplateError, List[TemplateVersion]] = sql(TemplateStore.latestVersions())

  /** Released versions only: the template catalog for authors. */
  def catalog: IO[TemplateError, List[TemplateVersion]] = sql(TemplateStore.released())

  /** A released version compiled against its class profile, for creating documents and live previews
    * (US-03.2). Versions in any other status are not found: authors only see the catalog.
    *
    * Compiled versions are cached by template id and version number. Released versions and class profile
    * versions are immutable, and the version is always read first through row-level security, so the cache
    * never hands out a template the caller's tenant cannot read.
    */
  def released(id: TemplateId, number: Int): IO[TemplateError, PreviewTemplate] =
    for
      v <- version(id, number)
        .filterOrFail(_.status == TemplateStatus.Released)(
          TemplateError.NotFound(s"template ${id.value} version $number is not released")
        )
      cached   <- ZIO.succeed(compiledCache.get(id -> number))
      template <- cached match
        case Some(t) => ZIO.succeed(t)
        case None    =>
          for
            profile  <- profiles.profile(v.documentClassId, v.documentClassVersion)
            compiled <- ZIO
              .fromEither(TemplateCompiler.compile(v.content, ProfileSchema.from(profile), limits))
              .mapError(TemplateError.InvalidContent(_))
            t = PreviewTemplate(v, profile, compiled)
            _ <- ZIO.succeed(compiledCache.put(id -> number, t))
          yield t
    yield template

  private val compiledCache = LruCache[(TemplateId, Int), PreviewTemplate](256)

  /** Moves a version to `to`. Releasing requires an actor who neither created nor changed the version. */
  def changeStatus(
      id: TemplateId,
      number: Int,
      to: TemplateStatus,
      actor: UserId
  ): IO[TemplateError, TemplateVersion] =
    for
      current <- locked(id, number)
      _       <- ZIO
        .fromEither(TemplateLifecycle.transition(current, to, actor))
        .mapError(TemplateError.Lifecycle(_))
      _   <- ZIO.when(to == TemplateStatus.Testing)(validate(draftOf(current)))
      now <- Clock.instant
      _   <- sql(TemplateStore.changeStatus(id, number, current.status, to, actor, now))
        .filterOrFail(identity)(
          TemplateError.Conflict(s"template ${id.value} version $number was changed concurrently")
        )
      updated <- version(id, number)
      _       <- record(
        actor,
        if to == TemplateStatus.Released then "template.released" else "template.status-changed",
        updated,
        Json.Obj("from" -> Json.Str(current.status.toString), "to" -> Json.Str(to.toString))
      )
    yield updated

  /** Test mode: resolves a version with the given values, or with generated sample data covering every
    * branch.
    */
  def test(
      id: TemplateId,
      number: Int,
      values: Option[Map[String, FieldValue]],
      language: Language
  ): IO[TemplateError, TestRun] =
    for
      v       <- version(id, number)
      profile <- profiles.profile(v.documentClassId, v.documentClassVersion)
      schema = ProfileSchema.from(profile)
      compiled <- ZIO
        .fromEither(TemplateCompiler.compile(v.content, schema, limits))
        .mapError(TemplateError.InvalidContent(_))
      problems = values.toList.flatMap(Resolver.checkValues(schema, _))
      _   <- ZIO.unless(problems.isEmpty)(ZIO.fail(TemplateError.Invalid(problems)))
      run <- ZIO.attempt(runTests(PreviewTemplate(v, profile, compiled), values, language)).orDie
    yield run

  /** A version in any status compiled against its class profile, for template designers: the fields of a
    * draft or a version in testing (DMS-98). Not cached, unlike [[released]]: drafts change.
    */
  def compiled(id: TemplateId, number: Int): IO[TemplateError, PreviewTemplate] =
    for
      v        <- version(id, number)
      profile  <- profiles.profile(v.documentClassId, v.documentClassVersion)
      compiled <- ZIO
        .fromEither(TemplateCompiler.compile(v.content, ProfileSchema.from(profile), limits))
        .mapError(TemplateError.InvalidContent(_))
    yield PreviewTemplate(v, profile, compiled)

  private def runTests(
      template: PreviewTemplate,
      values: Option[Map[String, FieldValue]],
      language: Language
  ) =
    val compiled = template.compiled
    values match
      case Some(vs) =>
        val result   = Resolver.resolve(compiled, vs, language)
        val coverage = compiled.conditionals.map { c =>
          val outcomes = result.toOption.toList.flatMap(_.branches).find(_.location == c.expression.location)
          BranchCoverage(
            c.expression.location,
            c.expression.source,
            outcomes.fold(Set.empty[Boolean])(_.outcomes)
          )
        }
        TestRun(compiled.schema, List(TestCase("given values", vs) -> result), coverage, template)
      case None =>
        val suite = TestCases.generate(compiled, language)
        TestRun(
          compiled.schema,
          suite.cases.map(c => c -> Resolver.resolve(compiled, c.values, language)),
          suite.coverage,
          template
        )

  private def validate(draft: TemplateDraft): IO[TemplateError, Unit] =
    for
      _ <- ZIO.when(draft.name.trim.isEmpty)(
        ZIO.fail(TemplateError.Invalid(List("the name must not be empty")))
      )
      issues <- check(draft.documentClassId, draft.documentClassVersion, draft.content)
      _      <- ZIO.unless(issues.isEmpty)(ZIO.fail(TemplateError.InvalidContent(issues)))
    yield ()

  private def locked(id: TemplateId, number: Int): IO[TemplateError, TemplateVersion] =
    sql(TemplateStore.version(id, number, forUpdate = true)).someOrFail(versionNotFound(id, number))

  private def record(actor: UserId, action: String, v: TemplateVersion, extra: Json.Obj) =
    val details = Json
      .Obj(
        "name"                 -> Json.Str(v.name),
        "status"               -> Json.Str(v.status.toString),
        "documentClassId"      -> Json.Str(v.documentClassId.toString),
        "documentClassVersion" -> Json.Num(v.documentClassVersion)
      )
      .merge(extra)
    audit
      .record(
        AuditEvent(
          Actor(actor.value, ActorKind.Person),
          action,
          AuditObject("template", Some(v.templateId.value), Some(v.version.toString)),
          Channel.Api,
          Jsonb(details.toString)
        )
      )
      .mapError(TemplateError.Database(_))
      .unit

/** A small thread-safe least-recently-used cache. */
private[core] final class LruCache[K, V](maxEntries: Int):
  private val entries = new java.util.LinkedHashMap[K, V](16, 0.75f, true):
    override def removeEldestEntry(eldest: java.util.Map.Entry[K, V]): Boolean = this.size() > maxEntries

  def get(key: K): Option[V]      = entries.synchronized(Option(entries.get(key)))
  def put(key: K, value: V): Unit = entries.synchronized { entries.put(key, value); () }
  def size: Int                   = entries.synchronized(entries.size)

object TemplateService:
  val layer: URLayer[AuditTrail & ProfileSource, TemplateService] =
    ZLayer.fromFunction((audit: AuditTrail, profiles: ProfileSource) => TemplateService(audit, profiles))

  private def sql[A](f: DbTx ?=> A): IO[TemplateError, A] = Db.sql(f).mapError(TemplateError.Database(_))

  private def draftOf(v: TemplateVersion): TemplateDraft =
    TemplateDraft(v.name, v.description, v.documentClassId, v.documentClassVersion, v.content)

  private def templateNotFound(id: TemplateId) =
    TemplateError.NotFound(s"template ${id.value} does not exist")

  private def versionNotFound(id: TemplateId, number: Int) =
    TemplateError.NotFound(s"template ${id.value} version $number does not exist")

  private def notEditable(v: TemplateVersion) =
    TemplateError.Lifecycle(TemplateLifecycleError.NotEditable(v.status))

  private def conflict(message: String)(error: TemplateError): TemplateError = error match
    case TemplateError.Database(DbError.UniqueViolation(_, _)) => TemplateError.Conflict(message)
    case other                                                 => other
