package dms.documents.core

import com.augustnagro.magnum.DbTx
import dms.audit.*
import dms.db.{Db, DbError, Jsonb}
import dms.documents.{NumberPattern, NumberRange, NumberToken}
import dms.documents.metadata.{DocumentClassId, MetadataProfile}
import dms.kernel.UserId
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.time.{Instant, ZoneId}
import java.util.UUID

/** Requested settings of a number range; the key is fixed once the range exists. */
final case class NumberRangeDraft(
    key: String,
    name: String,
    pattern: String,
    startValue: Long,
    yearlyReset: Boolean
)

/** A number range with what the administration shows: the number the next document would get now (with `{OU}`
  * standing for the organizational unit) and the classes it is assigned to directly.
  */
final case class NumberRangeInfo(
    range: NumberRange,
    nextNumber: String,
    nextCounter: Long,
    classIds: List[UUID]
)

/** The number range of a class (DMS-113): its own assignment and the one that applies.
  *
  * @param own
  *   `None`: no own assignment (inherits); `Some(None)`: explicitly no number range; `Some(Some(id))`: a
  *   range
  * @param effective
  *   the range documents of the class get their number from, if any
  * @param inheritedFrom
  *   the ancestor class whose assignment applies, if not the class's own
  */
final case class ClassNumberRange(
    classId: UUID,
    classKey: String,
    label: String,
    parentId: Option[UUID],
    own: Option[Option[UUID]],
    effective: Option[UUID],
    inheritedFrom: Option[UUID]
)

/** Why a backfill left a document without number (DMS-116). */
enum BackfillSkip:
  /** The document has a number already (numbered on creation, manually or by an earlier backfill). */
  case HasNumber

  /** Its class has no number range (any more). */
  case NoRange

  /** A version is in review or approval; its values are frozen until it is decided. */
  case InReview

  /** The pattern contains `{OU}` and the document has no organizational unit. */
  case UnitMissing

/** What a backfill did with one document (DMS-116). */
enum BackfillOutcome:
  case Numbered(number: String)
  case Skipped(reason: BackfillSkip)

/** What a backfill of a class would number (DMS-116): the documents without number of the class and of its
  * subclasses that take their number from the same range, oldest first.
  *
  * @param classIds
  *   the class and the subclasses that share its effective number range
  */
final case class BackfillScope(
    classId: UUID,
    range: NumberRange,
    classIds: Set[UUID],
    documents: List[dms.kernel.DocumentId]
)

/** Administration of number ranges and their assignment to document classes (DMS-113, ADR 0040).
  *
  * Every method must run inside `Db.transact(tenant)`; changes are recorded in the audit trail in the same
  * transaction. Authorization is the caller's job. Changes of a range only affect numbers assigned
  * afterwards.
  */
final class NumberRangeService(audit: AuditTrail):
  import NumberRangeService.*

  def list: IO[MetadataError, List[NumberRangeInfo]] =
    for
      zone   <- sql(DocumentStore.tenantZone)
      now    <- Clock.instant
      ranges <- sql(NumberRangeStore.ranges())
      infos  <- ZIO.foreach(ranges)(r => info(r, zone, now))
    yield infos

  def get(id: UUID): IO[MetadataError, NumberRangeInfo] =
    for
      zone  <- sql(DocumentStore.tenantZone)
      now   <- Clock.instant
      range <- existing(id)
      info  <- info(range, zone, now)
    yield info

  def create(draft: NumberRangeDraft, actor: UserId): IO[MetadataError, NumberRangeInfo] =
    for
      pattern <- checked(draft)
      now     <- Clock.instant
      range = NumberRange(
        UUID.randomUUID(),
        draft.key.trim,
        draft.name.trim,
        pattern,
        draft.startValue,
        draft.yearlyReset,
        actor.value,
        now,
        now
      )
      _ <- sql(NumberRangeStore.insertRange(range)).mapError {
        case MetadataError.Database(DbError.UniqueViolation(_, _)) =>
          MetadataError.Conflict(s"a number range with key '${range.key}' exists already")
        case other => other
      }
      _    <- record(actor, "number-range.created", range.id, details(range))
      info <- get(range.id)
    yield info

  /** Changes name, pattern, start value and yearly reset. Numbers already assigned stay; the counter of the
    * current period continues (raised to the start value if that is higher).
    */
  def update(id: UUID, draft: NumberRangeDraft, actor: UserId): IO[MetadataError, NumberRangeInfo] =
    for
      before  <- existing(id)
      pattern <- checked(draft.copy(key = before.key))
      after = before.copy(
        name = draft.name.trim,
        pattern = pattern,
        startValue = draft.startValue,
        yearlyReset = draft.yearlyReset
      )
      _ <- sql(NumberRangeStore.updateRange(after, actor.value))
      _ <- record(
        actor,
        "number-range.changed",
        id,
        Json.Obj("before" -> details(before), "after" -> details(after))
      )
      info <- get(id)
    yield info

  /** Problems of the settings, or the first number of a period this year (`{OU}` kept as placeholder), e.g.
    * for a form preview. Key and name are not checked.
    */
  def preview(
      pattern: String,
      startValue: Long,
      yearlyReset: Boolean
  ): IO[MetadataError, Either[List[String], String]] =
    for
      zone <- sql(DocumentStore.tenantZone)
      now  <- Clock.instant
      parsed   = NumberPattern.parse(pattern)
      problems = NumberRange.check("preview", "preview", parsed, startValue, yearlyReset)
    yield parsed match
      case Right(p) if problems.isEmpty => Right(example(p, now.atZone(zone).getYear, startValue))
      case _                            => Left(problems)

  /** Every class with its own and its effective number range, by key. */
  def classes: IO[MetadataError, List[ClassNumberRange]] =
    for
      tree        <- sql(NumberRangeStore.classTree())
      assignments <- sql(NumberRangeStore.assignments())
      latest      <- sql(MetadataStore.latestClasses())
      labels  = latest.map(c => c.classId.value -> c.label).toMap
      parents = tree.map((id, _, parent) => id -> parent).toMap
    yield tree.sortBy(_._2).map { (id, key, parent) =>
      val source = resolve(id, parents, assignments)
      ClassNumberRange(
        id,
        key,
        labels.getOrElse(id, key),
        parent,
        assignments.get(id),
        source.flatMap(assignments.get).flatten,
        source.filter(_ != id)
      )
    }

  /** Assigns a range to a class (`None`: explicitly none, so that the class does not inherit one). */
  def assign(classId: UUID, range: Option[UUID], actor: UserId): IO[MetadataError, ClassNumberRange] =
    for
      _ <- existingClass(classId)
      _ <- ZIO.foreachDiscard(range)(existing)
      _ <- sql(NumberRangeStore.assign(classId, range, actor.value))
      _ <- record(
        actor,
        "number-range.assigned",
        classId,
        Json.Obj("rangeId" -> range.fold[Json](Json.Null)(r => Json.Str(r.toString))),
        "document-class"
      )
      cls <- classOf(classId)
    yield cls

  /** Removes the class's own assignment: it inherits the range of its parent again. */
  def unassign(classId: UUID, actor: UserId): IO[MetadataError, ClassNumberRange] =
    for
      _       <- existingClass(classId)
      removed <- sql(NumberRangeStore.unassign(classId))
      _ <- ZIO.when(removed)(record(actor, "number-range.unassigned", classId, Json.Obj(), "document-class"))
      cls <- classOf(classId)
    yield cls

  /** What a backfill of `classId` would number (DMS-116): the documents without number of the class and of
    * its subclasses with the same effective number range, oldest first. 404 for an unknown class, 409 if the
    * class has no number range.
    */
  def backfillScope(classId: UUID): IO[MetadataError, BackfillScope] =
    for
      all     <- classes
      cls     <- ZIO.fromOption(all.find(_.classId == classId)).orElseFail(classNotFound(classId))
      rangeId <- ZIO
        .fromOption(cls.effective)
        .orElseFail(MetadataError.Conflict(s"the class ${cls.classKey} has no number range"))
      range <- existing(rangeId)
      parents = all.map(c => c.classId -> c.parentId).toMap
      members = all
        .filter(c => c.effective.contains(rangeId) && descendsFrom(c.classId, classId, parents))
        .map(_.classId)
        .toSet
      unnumbered <- sql(NumberRangeStore.unnumberedDocuments())
    yield BackfillScope(classId, range, members, unnumbered.collect { case (id, c) if members(c) => id })

  /** Records the summary of a backfill run of a class (`number-range.backfilled`); every numbered document
    * has its own `document.number-assigned` entry.
    */
  def recordBackfill(
      scope: BackfillScope,
      numbered: Int,
      skipped: Int,
      failed: Int,
      remaining: Int,
      actor: UserId
  ): IO[MetadataError, Unit] =
    record(
      actor,
      "number-range.backfilled",
      scope.classId,
      Json.Obj(
        "rangeId"   -> Json.Str(scope.range.id.toString),
        "rangeKey"  -> Json.Str(scope.range.key),
        "numbered"  -> Json.Num(numbered),
        "skipped"   -> Json.Num(skipped),
        "failed"    -> Json.Num(failed),
        "remaining" -> Json.Num(remaining)
      ),
      "document-class"
    )

  private def classOf(classId: UUID): IO[MetadataError, ClassNumberRange] =
    classes.flatMap(all => ZIO.fromOption(all.find(_.classId == classId)).orElseFail(classNotFound(classId)))

  private def existingClass(classId: UUID): IO[MetadataError, Unit] =
    sql(NumberRangeStore.classTree()).flatMap(tree =>
      ZIO.unless(tree.exists(_._1 == classId))(ZIO.fail(classNotFound(classId))).unit
    )

  private def existing(id: UUID): IO[MetadataError, NumberRange] =
    sql(NumberRangeStore.range(id)).someOrFail(MetadataError.NotFound(s"the number range $id does not exist"))

  private def info(range: NumberRange, zone: ZoneId, now: Instant): IO[MetadataError, NumberRangeInfo] =
    val year = now.atZone(zone).getYear
    for
      stored      <- sql(NumberRangeStore.counter(range.id, range.period(year)))
      assignments <- sql(NumberRangeStore.assignments())
      next = range.nextCounter(stored)
    yield NumberRangeInfo(
      range,
      example(range.pattern, year, next),
      next,
      assignments.collect { case (cls, Some(r)) if r == range.id => cls }.toList.sortBy(_.toString)
    )

  private def checked(draft: NumberRangeDraft): IO[MetadataError, NumberPattern] =
    val pattern  = NumberPattern.parse(draft.pattern)
    val problems =
      NumberRange.check(draft.key.trim, draft.name, pattern, draft.startValue, draft.yearlyReset)
    if problems.nonEmpty then ZIO.fail(MetadataError.Invalid(problems))
    else ZIO.fromEither(pattern).mapError(MetadataError.Invalid(_))

  private def record(
      actor: UserId,
      action: String,
      id: UUID,
      details: Json,
      kind: String = "number-range"
  ): IO[MetadataError, Unit] =
    audit
      .record(
        AuditEvent(
          Actor(actor.value, ActorKind.Person),
          action,
          AuditObject(kind, Some(id)),
          Channel.Api,
          Jsonb(details.toJson)
        )
      )
      .mapError(MetadataError.Database(_))
      .unit

object NumberRangeService:
  val layer: URLayer[AuditTrail, NumberRangeService] = ZLayer.fromFunction(NumberRangeService(_))

  private def sql[A](f: DbTx ?=> A): IO[MetadataError, A] = Db.sql(f).mapError(MetadataError.Database(_))

  private def classNotFound(id: UUID) = MetadataError.NotFound(s"the document class $id does not exist")

  /** The number for `counter` in `year`, with `{OU}` kept as placeholder (it depends on the document). */
  def example(pattern: NumberPattern, year: Int, counter: Long): String =
    pattern.tokens.map {
      case NumberToken.Literal(text)   => text
      case NumberToken.Year            => f"${year % 10000}%04d"
      case NumberToken.ShortYear       => f"${year % 100}%02d"
      case NumberToken.Unit            => "{OU}"
      case NumberToken.Counter(digits) => s"%0${digits}d".format(counter)
    }.mkString

  /** The class whose assignment applies to `id`: the class itself or its nearest ancestor with an assignment.
    */
  def resolve(
      id: UUID,
      parents: Map[UUID, Option[UUID]],
      assignments: Map[UUID, Option[UUID]]
  ): Option[UUID] =
    @annotation.tailrec
    def go(current: Option[UUID], seen: Set[UUID]): Option[UUID] = current match
      case Some(c) if assignments.contains(c) => Some(c)
      case Some(c) if !seen.contains(c)       => go(parents.get(c).flatten, seen + c)
      case _                                  => None
    go(Some(id), Set.empty)

  /** Whether `id` is `ancestor` or one of its descendants. */
  def descendsFrom(id: UUID, ancestor: UUID, parents: Map[UUID, Option[UUID]]): Boolean =
    @annotation.tailrec
    def go(current: Option[UUID], seen: Set[UUID]): Boolean = current match
      case Some(c) if c == ancestor     => true
      case Some(c) if !seen.contains(c) => go(parents.get(c).flatten, seen + c)
      case _                            => false
    go(Some(id), Set.empty)

  private def details(r: NumberRange): Json =
    Json.Obj(
      "key"         -> Json.Str(r.key),
      "name"        -> Json.Str(r.name),
      "pattern"     -> Json.Str(r.pattern.show),
      "startValue"  -> Json.Num(r.startValue),
      "yearlyReset" -> Json.Bool(r.yearlyReset)
    )

/** Assigns document numbers on creation (DMS-113, ADR 0040), inside the creating transaction. */
private[core] object DocumentNumbering:

  /** Attempts to find a free number before giving up (numbers can be taken by manual assignments). */
  private val MaxAttempts = 10000

  /** The range that applies to the class of `profile`, if any. */
  def rangeOf(profile: MetadataProfile)(using DbTx): Option[NumberRange] =
    val tree        = NumberRangeStore.classTree()
    val assignments = NumberRangeStore.assignments()
    val parents     = tree.map((id, _, parent) => id -> parent).toMap
    NumberRangeService
      .resolve(profile.classId.value, parents, assignments)
      .flatMap(assignments.get)
      .flatten
      .flatMap(NumberRangeStore.range)

  /** Takes the next free number of `range` for a document created at `now`: locks the counter of the period
    * (until the transaction ends), skips numbers already taken (by manual assignment) and stores the next
    * counter value. Rolling the transaction back rolls the counter back as well.
    */
  def next(range: NumberRange, zone: ZoneId, now: Instant, unit: Option[String])(using
      DbTx
  ): Either[String, String] =
    val year   = now.atZone(zone).getYear
    val period = range.period(year)
    val stored = NumberRangeStore.lockCounter(range.id, period, range.startValue)
    @annotation.tailrec
    def free(counter: Long, attempts: Int): Either[String, (String, Long)] =
      range.pattern.format(year, unit, counter) match
        case Left(problem)                                                        => Left(problem)
        case Right(number) if NumberRangeStore.documentWithNumber(number).isEmpty => Right((number, counter))
        case Right(_) if attempts >= MaxAttempts                                  =>
          Left(s"no free number in range ${range.key} after $MaxAttempts attempts")
        case Right(_) => free(counter + 1, attempts + 1)
    free(range.nextCounter(Some(stored)), 0).map { (number, counter) =>
      NumberRangeStore.setCounter(range.id, period, counter + 1)
      number
    }
