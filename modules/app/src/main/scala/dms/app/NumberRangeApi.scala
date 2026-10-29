package dms.app

import dms.authz.{Action, Authorizer, ConfigurationArea, Resource}
import dms.db.queue.JobQueue
import dms.db.{Db, Jsonb}
import dms.documents.core.{
  BackfillOutcome,
  BackfillSkip,
  ClassNumberRange,
  DocumentService,
  MetadataError,
  NumberRangeDraft,
  NumberRangeInfo,
  NumberRangeService
}
import dms.identity.{Authenticator, Principal}
import dms.kernel.DocumentId
import dms.search.core.SearchJobs
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.Instant
import java.util.UUID

final case class NumberRangeView(
    id: UUID,
    key: String,
    name: String,
    /** E.g. `POL-{YYYY}-{NNNN}`: `{YYYY}`/`{YY}` year, `{OU}` organizational unit, `{N…}` counter digits. */
    pattern: String,
    startValue: Long,
    yearlyReset: Boolean,
    /** The number the next document would get now; `{OU}` stands for its organizational unit. */
    nextNumber: String,
    /** The counter value the next document would get now. */
    nextCounter: Long,
    /** Classes the range is assigned to directly (subclasses inherit it unless they override it). */
    classIds: List[UUID],
    createdBy: String,
    createdAt: Instant,
    updatedAt: Instant
) derives JsonCodec,
      Schema

final case class CreateNumberRange(
    /** Stable key: lowercase letters, digits and hyphens. */
    key: String,
    name: String,
    pattern: String,
    /** First counter value of a period; default 1. */
    startValue: Option[Long],
    /** Start again at the start value every year; the pattern then needs `{YYYY}` or `{YY}`. Default false.
      */
    yearlyReset: Option[Boolean]
) derives JsonCodec,
      Schema

/** New settings of a range; they only affect numbers assigned afterwards. */
final case class ChangeNumberRange(
    name: String,
    pattern: String,
    /** A higher start value raises the next number of the current period; a lower one never lowers it. */
    startValue: Long,
    yearlyReset: Boolean
) derives JsonCodec,
      Schema

final case class NumberRangePreviewRequest(
    pattern: String,
    startValue: Option[Long],
    yearlyReset: Option[Boolean]
) derives JsonCodec,
      Schema

final case class NumberRangePreview(
    valid: Boolean,
    problems: List[String],
    /** The first number of a period this year; `{OU}` stands for the organizational unit. */
    example: Option[String]
) derives JsonCodec,
      Schema

/** The number range of a document class: its own assignment and the one that applies. */
final case class ClassNumberRangeView(
    classId: UUID,
    classKey: String,
    label: String,
    parentId: Option[UUID],
    /** `inherit` (no own assignment), `none` (explicitly no number range) or `range`. */
    mode: String,
    /** The own range for mode `range`. */
    numberRangeId: Option[UUID],
    /** The range documents of the class get their numbers from, if any. */
    effectiveRangeId: Option[UUID],
    /** The ancestor class whose assignment applies, if inherited. */
    inheritedFrom: Option[UUID]
) derives JsonCodec,
      Schema

final case class AssignNumberRange(
    /** The range; empty: explicitly no number range (the class does not inherit one). */
    numberRangeId: Option[UUID]
) derives JsonCodec,
      Schema

/** What numbering the existing documents of a class would do (DMS-116), shown before confirming it. */
final case class NumberBackfillPreview(
    classId: UUID,
    numberRangeId: UUID,
    numberRangeKey: String,
    /** The class and its subclasses that take their numbers from the same range. */
    classIds: List[UUID],
    /** Documents of these classes without number, numbered oldest first. */
    documents: Int,
    /** Documents one run numbers at most; press again for the rest. */
    maxPerRun: Int,
    /** The number a document created now would get; existing documents take the counter of the year they were
      * created in.
      */
    nextNumber: String
) derives JsonCodec,
      Schema

/** One document of a backfill run. */
final case class NumberBackfillEntry(
    documentId: UUID,
    /** `Numbered`, `Skipped` or `Failed`. */
    outcome: String,
    number: Option[String],
    /** For `Skipped`: `has-number`, `no-range`, `in-review` (a version is in review or approval) or
      * `unit-missing` (the pattern needs the organizational unit); for `Failed`: the error.
      */
    reason: Option[String]
) derives JsonCodec,
      Schema

/** Result of numbering the existing documents of a class (DMS-116). */
final case class NumberBackfillResult(
    classId: UUID,
    numberRangeId: UUID,
    numberRangeKey: String,
    numbered: Int,
    skipped: Int,
    failed: Int,
    /** Documents without number not looked at in this run (more than `maxPerRun`); run again for them. */
    remaining: Int,
    /** The documents of this run in the order they were numbered (oldest first). */
    entries: List[NumberBackfillEntry]
) derives JsonCodec,
      Schema

/** Number ranges and their assignment to document classes (DMS-113, ADR 0040), for tenant administrators
  * (`Configure` on `NumberRanges`). Changes are recorded in the audit trail and only affect numbers assigned
  * afterwards.
  */
object NumberRangeApi:
  type Env = Authenticator & Db & Authorizer & NumberRangeService & DocumentService

  private val base        = "api" / "v1" / "admin" / "number-ranges"
  private val assignments = "api" / "v1" / "admin" / "number-range-assignments"
  private val rangeId     = path[UUID]("id").description("Number range id")
  private val classId     = path[UUID]("classId").description("Document class id")

  val list: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base)
      .out(jsonBody[List[NumberRangeView]])
      .description("The number ranges of the tenant by key, each with the number the next document would get")
      .tag("Number ranges")
      .serverLogic(p => _ => configure(p)(_.list.map(_.map(view))))

  val get: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(base / rangeId)
      .out(jsonBody[NumberRangeView])
      .description("A number range")
      .tag("Number ranges")
      .serverLogic(p => id => configure(p)(_.get(id).map(view)))

  val create: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(base)
      .in(jsonBody[CreateNumberRange])
      .out(statusCode(StatusCode.Created).and(jsonBody[NumberRangeView]))
      .description(
        "Creates a number range. 422 for an invalid key, name, pattern or start value, or a yearly reset " +
          "without year in the pattern; 409 for a taken key."
      )
      .tag("Number ranges")
      .serverLogic(p =>
        req =>
          configure(p)(
            _.create(
              NumberRangeDraft(
                req.key,
                req.name,
                req.pattern,
                req.startValue.getOrElse(1L),
                req.yearlyReset.getOrElse(false)
              ),
              p.userId
            ).map(view)
          )
      )

  val update: ZServerEndpoint[Env, Any] =
    SecuredApi.base.put
      .in(base / rangeId)
      .in(jsonBody[ChangeNumberRange])
      .out(jsonBody[NumberRangeView])
      .description(
        "Changes name, pattern, start value and yearly reset. Numbers already assigned stay; the change applies " +
          "to the next number."
      )
      .tag("Number ranges")
      .serverLogic(p =>
        (id, req) =>
          configure(p)(
            _.update(
              id,
              NumberRangeDraft("", req.name, req.pattern, req.startValue, req.yearlyReset),
              p.userId
            ).map(view)
          )
      )

  val preview: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(base / "preview")
      .in(jsonBody[NumberRangePreviewRequest])
      .out(jsonBody[NumberRangePreview])
      .description("Checks a pattern and shows the first number of a period this year, e.g. while typing")
      .tag("Number ranges")
      .serverLogic(p =>
        req =>
          configure(p)(
            _.preview(req.pattern, req.startValue.getOrElse(1L), req.yearlyReset.getOrElse(false)).map {
              case Left(problems) => NumberRangePreview(valid = false, problems, None)
              case Right(example) => NumberRangePreview(valid = true, Nil, Some(example))
            }
          )
      )

  val classes: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(assignments)
      .out(jsonBody[List[ClassNumberRangeView]])
      .description("Every document class with its own and its effective number range, by key")
      .tag("Number ranges")
      .serverLogic(p => _ => configure(p)(_.classes.map(_.map(classView))))

  val assign: ZServerEndpoint[Env, Any] =
    SecuredApi.base.put
      .in(assignments / classId)
      .in(jsonBody[AssignNumberRange])
      .out(jsonBody[ClassNumberRangeView])
      .description(
        "Assigns a number range to a class; subclasses inherit it unless they have their own. Without " +
          "`numberRangeId` the class explicitly gets no number range."
      )
      .tag("Number ranges")
      .serverLogic(p => (id, req) => configure(p)(_.assign(id, req.numberRangeId, p.userId).map(classView)))

  val unassign: ZServerEndpoint[Env, Any] =
    SecuredApi.base.delete
      .in(assignments / classId)
      .out(jsonBody[ClassNumberRangeView])
      .description("Removes the class's own assignment: it inherits the number range of its parent again")
      .tag("Number ranges")
      .serverLogic(p => id => configure(p)(_.unassign(id, p.userId).map(classView)))

  /** Documents one backfill run numbers at most, so that a request stays short; the result says how many are
    * left.
    */
  val MaxPerRun: Int = 500

  val backfillPreview: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(assignments / classId / "backfill")
      .out(jsonBody[NumberBackfillPreview])
      .description(
        "How many existing documents of the class (and of its subclasses with the same number range) have no " +
          "number yet and would be numbered by `POST …/backfill`. 409 if the class has no number range."
      )
      .tag("Number ranges")
      .serverLogic(p =>
        id =>
          configure(p)(service =>
            for
              scope <- service.backfillScope(id)
              info  <- service.get(scope.range.id)
            yield NumberBackfillPreview(
              id,
              scope.range.id,
              scope.range.key,
              scope.classIds.toList.sortBy(_.toString),
              scope.documents.size,
              MaxPerRun,
              info.nextNumber
            )
          )
      )

  val backfill: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(assignments / classId / "backfill")
      .out(jsonBody[NumberBackfillResult])
      .description(
        "Numbers the existing documents without number of the class and of its subclasses with the same number " +
          "range (DMS-116), oldest first, each in its own transaction like on creation (counter of the year " +
          s"the document was created in). At most $MaxPerRun per run; `remaining` tells how many are left. " +
          "Documents with a version in review or approval, or without the organizational unit a `{OU}` pattern " +
          "needs, are skipped. Idempotent: numbered documents are never renumbered. Each number is audited " +
          "(`document.number-assigned` with `backfill`) and the documents are reindexed for the search."
      )
      .tag("Number ranges")
      .serverLogic(p => id => runBackfill(p, id))

  def endpoints: List[ZServerEndpoint[Env, Any]] =
    List(preview, list, create, get, update, classes, backfillPreview, backfill, assign, unassign)

  private def runBackfill(
      p: Principal,
      classId: UUID
  ): ZIO[Env, (StatusCode, ApiError), NumberBackfillResult] =
    for
      scope <- configure(p)(_.backfillScope(classId))
      batch = scope.documents.take(MaxPerRun)
      // One transaction per document: a failure rolls back that document's counter only (no gap), and the
      // counter row is locked only briefly.
      entries <- ZIO.foreach(batch)(id =>
        numberOne(p, id).fold(
          e => NumberBackfillEntry(id.value, "Failed", None, Some(e._2.message)),
          {
            case BackfillOutcome.Numbered(n) => NumberBackfillEntry(id.value, "Numbered", Some(n), None)
            case BackfillOutcome.Skipped(r)  =>
              NumberBackfillEntry(id.value, "Skipped", None, Some(skipCode(r)))
          }
        )
      )
      numbered  = entries.count(_.outcome == "Numbered")
      skipped   = entries.count(_.outcome == "Skipped")
      failed    = entries.count(_.outcome == "Failed")
      remaining = scope.documents.size - batch.size
      _ <- configure(p)(_.recordBackfill(scope, numbered, skipped, failed, remaining, p.userId))
    yield NumberBackfillResult(
      classId,
      scope.range.id,
      scope.range.key,
      numbered,
      skipped,
      failed,
      remaining,
      entries
    )

  /** Numbers one document in its own transaction (authorized again) and enqueues its reindexing in the same
    * transaction, so the search shows the number seconds after the commit.
    */
  private def numberOne(p: Principal, id: DocumentId): ZIO[Env, (StatusCode, ApiError), BackfillOutcome] =
    val resource = Resource(p.tenantId, configurationArea = Some(ConfigurationArea.NumberRanges))
    (for
      db        <- ZIO.service[Db]
      authz     <- ZIO.service[Authorizer]
      documents <- ZIO.service[DocumentService]
      outcome   <- db.transact(p.tenantId)(
        for
          _       <- authz.subject(p).flatMap(authz.check(_, Action.Configure, resource))
          outcome <- documents.numberExisting(id, p.userId)
          _       <- ZIO.when(outcome.isInstanceOf[BackfillOutcome.Numbered])(
            JobQueue.enqueue(
              SearchJobs.IndexKind,
              Jsonb(Json.Obj("documentId" -> Json.Str(id.value.toString)).toJson),
              idempotencyKey = Some(s"${SearchJobs.IndexKind}:number-backfill:${id.value}")
            )
          )
        yield outcome
      )
    yield outcome).mapError(DocumentApi.toResponse)

  private def skipCode(reason: BackfillSkip): String = reason match
    case BackfillSkip.HasNumber   => "has-number"
    case BackfillSkip.NoRange     => "no-range"
    case BackfillSkip.InReview    => "in-review"
    case BackfillSkip.UnitMissing => "unit-missing"

  private def configure[A](principal: Principal)(
      f: NumberRangeService => IO[MetadataError, A]
  ): ZIO[Env, (StatusCode, ApiError), A] =
    val resource = Resource(principal.tenantId, configurationArea = Some(ConfigurationArea.NumberRanges))
    (for
      db      <- ZIO.service[Db]
      authz   <- ZIO.service[Authorizer]
      service <- ZIO.service[NumberRangeService]
      result  <- db.transact(principal.tenantId)(
        authz.subject(principal).flatMap(authz.check(_, Action.Configure, resource)) *> f(service)
      )
    yield result).mapError(DocumentClassApi.toResponse)

  private def view(i: NumberRangeInfo): NumberRangeView =
    NumberRangeView(
      i.range.id,
      i.range.key,
      i.range.name,
      i.range.pattern.show,
      i.range.startValue,
      i.range.yearlyReset,
      i.nextNumber,
      i.nextCounter,
      i.classIds,
      i.range.createdBy,
      i.range.createdAt,
      i.range.updatedAt
    )

  private def classView(c: ClassNumberRange): ClassNumberRangeView =
    ClassNumberRangeView(
      c.classId,
      c.classKey,
      c.label,
      c.parentId,
      c.own.fold("inherit")(_.fold("none")(_ => "range")),
      c.own.flatten,
      c.effective,
      c.inheritedFrom
    )
