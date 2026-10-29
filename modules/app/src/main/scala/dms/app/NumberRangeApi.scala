package dms.app

import dms.authz.{Action, Authorizer, ConfigurationArea, Resource}
import dms.db.Db
import dms.documents.core.{
  ClassNumberRange,
  MetadataError,
  NumberRangeDraft,
  NumberRangeInfo,
  NumberRangeService
}
import dms.identity.{Authenticator, Principal}
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

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

/** Number ranges and their assignment to document classes (DMS-113, ADR 0040), for tenant administrators
  * (`Configure` on `NumberRanges`). Changes are recorded in the audit trail and only affect numbers assigned
  * afterwards.
  */
object NumberRangeApi:
  type Env = Authenticator & Db & Authorizer & NumberRangeService

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

  def endpoints: List[ZServerEndpoint[Env, Any]] =
    List(preview, list, create, get, update, classes, assign, unassign)

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
