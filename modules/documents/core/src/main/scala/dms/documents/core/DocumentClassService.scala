package dms.documents.core

import com.augustnagro.magnum.DbTx
import dms.audit.*
import dms.db.{Db, DbError, Jsonb}
import dms.documents.metadata.*
import dms.documents.core.MetadataJson.given
import dms.kernel.UserId
import zio.*
import zio.json.*
import zio.json.ast.Json

enum MetadataError:
  case NotFound(message: String)

  /** The request violates a rule of the metadata model; `problems` are meant for display. */
  case Invalid(problems: List[String])

  /** A concurrent change created the same key or version first. */
  case Conflict(message: String)
  case Database(error: DbError)

/** Requested content of a field definition version. */
final case class FieldDraft(
    label: String,
    description: Option[String],
    fieldType: FieldType,
    multiple: Boolean = false
)

/** Requested content of a class version.
  *
  * @param parentVersion
  *   the parent's profile version to inherit from; default: the parent's latest version
  */
final case class ClassDraft(
    label: String,
    description: Option[String],
    fields: List[FieldBinding],
    parentVersion: Option[Int] = None
)

/** Manages document classes and field definitions of the current tenant (business concept 3.1, 3.2).
  *
  * Every method must run inside `Db.transact(tenant)`. Changes never update rows: they add a new version, so
  * documents keep the profile version they were created with. Each change is recorded in the audit trail
  * within the same transaction.
  */
final class DocumentClassService(audit: AuditTrail):
  import DocumentClassService.*

  def listFields: IO[MetadataError, List[FieldDefinition]] =
    sql(MetadataStore.latestFields()).map(CoreFields.fields.map(_.definition) ++ _)

  /** All versions of a field definition, oldest first. */
  def fieldVersions(key: String): IO[MetadataError, List[FieldDefinition]] =
    for
      k        <- parseKey(key)
      versions <- CoreFields.definition(k) match
        case Some(core) => ZIO.succeed(List(core))
        case None       => sql(MetadataStore.fieldVersions(k))
      _ <- ZIO.when(versions.isEmpty)(
        ZIO.fail(MetadataError.NotFound(s"field definition $key does not exist"))
      )
    yield versions

  def createField(key: String, draft: FieldDraft, actor: UserId): IO[MetadataError, FieldDefinition] =
    for
      k <- parseKey(key)
      definition = FieldDefinition(k, 1, draft.label, draft.description, draft.fieldType, draft.multiple)
      _ <- invalidIfAny(Profiles.checkDefinition(definition))
      _ <- sql(MetadataStore.insertField(definition, actor.value))
        .mapError(conflict(s"field $key already exists"))
      _ <- recordField("field-definition.created", definition, actor)
    yield definition

  def newFieldVersion(key: String, draft: FieldDraft, actor: UserId): IO[MetadataError, FieldDefinition] =
    for
      k <- parseKey(key)
      _ <- ZIO.when(CoreFields.isCore(k))(
        ZIO.fail(MetadataError.Invalid(List(s"$key is a core field and cannot be changed by a tenant")))
      )
      previous <- sql(MetadataStore.fieldVersions(k).lastOption)
        .someOrFail(MetadataError.NotFound(s"field definition $key does not exist"))
      requested = FieldDefinition(k, 0, draft.label, draft.description, draft.fieldType, draft.multiple)
      next <- ZIO.fromEither(Profiles.nextVersion(previous, requested)).mapError(MetadataError.Invalid(_))
      _    <- sql(MetadataStore.insertField(next, actor.value))
        .mapError(conflict(s"field $key was changed concurrently"))
      _ <- recordField("field-definition.version-created", next, actor)
    yield next

  /** The latest version of every class. */
  def listClasses: IO[MetadataError, List[DocumentClassVersion]] =
    sql(MetadataStore.latestClasses())

  /** All versions of a class, oldest first. */
  def classVersions(id: DocumentClassId): IO[MetadataError, List[DocumentClassVersion]] =
    sql(MetadataStore.classVersions(id)).filterOrFail(_.nonEmpty)(classNotFound(id))

  def createClass(
      key: String,
      parent: Option[DocumentClassId],
      draft: ClassDraft,
      actor: UserId
  ): IO[MetadataError, DocumentClassVersion] =
    for
      k       <- ZIO.fromEither(ClassKey.parse(key)).mapError(e => MetadataError.Invalid(List(e)))
      created <- sqlEither {
        for
          parentRef <- parent.fold(noParent(draft))(p => pinParent(p, draft.parentVersion).map(Some(_)))
          cls = DocumentClassVersion(
            DocumentClassId.random(),
            k,
            1,
            parentRef,
            draft.label,
            draft.description,
            draft.fields
          )
          _ <- resolve(cls)
        yield cls
      }
      _ <- sql {
        val _ = MetadataStore.insertClass(created.classId, k, parent, actor.value)
        MetadataStore.insertClassVersion(created, actor.value)
      }.mapError(conflict(s"class $key already exists"))
      _ <- recordClass("document-class.created", created, actor)
    yield created

  def newClassVersion(
      id: DocumentClassId,
      draft: ClassDraft,
      actor: UserId
  ): IO[MetadataError, DocumentClassVersion] =
    for
      next <- sqlEither {
        for
          latest    <- MetadataStore.latestClassVersion(id).toRight(classNotFound(id))
          parentRef <- latest.parent.fold(noParent(draft))(p =>
            pinParent(p.id, draft.parentVersion).map(Some(_))
          )
          cls = latest.copy(
            version = latest.version + 1,
            parent = parentRef,
            label = draft.label,
            description = draft.description,
            fields = draft.fields
          )
          _ <- resolve(cls)
        yield cls
      }
      _ <- sql(MetadataStore.insertClassVersion(next, actor.value))
        .mapError(conflict(s"class ${next.key} was changed concurrently"))
      _ <- recordClass("document-class.version-created", next, actor)
    yield next

  /** The effective profile of a class version (default: latest), including inherited and core fields. */
  def profile(id: DocumentClassId, version: Option[Int] = None): IO[MetadataError, MetadataProfile] =
    sqlEither(
      version
        .fold(MetadataStore.latestClassVersion(id))(MetadataStore.classVersion(id, _))
        .toRight(classNotFound(id))
        .flatMap(resolve)
    )

  private def recordField(action: String, definition: FieldDefinition, actor: UserId) =
    record(
      actor,
      action,
      AuditObject("field-definition", None, Some(definition.version.toString)),
      Json.Obj(
        "key"       -> Json.Str(definition.key.value),
        "label"     -> Json.Str(definition.label),
        "fieldType" -> definition.fieldType.toJsonAST.getOrElse(Json.Null),
        "multiple"  -> Json.Bool(definition.multiple)
      )
    )

  private def recordClass(action: String, cls: DocumentClassVersion, actor: UserId) =
    record(
      actor,
      action,
      AuditObject("document-class", Some(cls.classId.value), Some(cls.version.toString)),
      Json.Obj(
        "key"           -> Json.Str(cls.key.value),
        "label"         -> Json.Str(cls.label),
        "parentVersion" -> cls.parent.fold(Json.Null)(p => Json.Num(p.version)),
        "fields"        -> cls.fields.toJsonAST.getOrElse(Json.Null)
      )
    )

  private def record(actor: UserId, action: String, target: AuditObject, details: Json) =
    audit
      .record(
        AuditEvent(Actor(actor.value, ActorKind.Person), action, target, Channel.Api, Jsonb(details.toJson))
      )
      .mapError(MetadataError.Database(_))

object DocumentClassService:
  val layer: URLayer[AuditTrail, DocumentClassService] = ZLayer.fromFunction(DocumentClassService(_))

  private def sql[A](f: DbTx ?=> A): IO[MetadataError, A] = Db.sql(f).mapError(MetadataError.Database(_))

  private def sqlEither[A](f: DbTx ?=> Either[MetadataError, A]): IO[MetadataError, A] = sql(f).absolve

  private def parseKey(key: String): IO[MetadataError, FieldKey] =
    ZIO.fromEither(FieldKey.parse(key)).mapError(e => MetadataError.Invalid(List(e)))

  private def invalidIfAny(problems: List[String]): IO[MetadataError, Unit] =
    ZIO.unless(problems.isEmpty)(ZIO.fail(MetadataError.Invalid(problems))).unit

  private def classNotFound(id: DocumentClassId) =
    MetadataError.NotFound(s"document class ${id.value} does not exist")

  private def conflict(message: String)(error: MetadataError): MetadataError = error match
    case MetadataError.Database(DbError.UniqueViolation(_, _)) => MetadataError.Conflict(message)
    case other                                                 => other

  private def noParent(draft: ClassDraft): Either[MetadataError, Option[ClassRef]] =
    if draft.parentVersion.isDefined then
      Left(MetadataError.Invalid(List("a root class has no parent version")))
    else Right(None)

  /** The parent version to inherit from: the requested one, or the parent's latest. */
  private def pinParent(parent: DocumentClassId, version: Option[Int])(using
      DbTx
  ): Either[MetadataError, ClassRef] =
    version
      .fold(MetadataStore.latestClassVersion(parent))(MetadataStore.classVersion(parent, _))
      .map(_.ref)
      .toRight(
        MetadataError.NotFound(
          s"parent class ${parent.value}${version.fold("")(v => s" version $v")} does not exist"
        )
      )

  /** Loads the ancestors of `cls` and resolves its effective profile. */
  private def resolve(cls: DocumentClassVersion)(using DbTx): Either[MetadataError, MetadataProfile] =
    for
      ancestors <- ancestorsOf(cls, Nil)
      chain       = ancestors :+ cls
      definitions = chain
        .flatMap(_.fields)
        .filterNot(b => CoreFields.isCore(b.key))
        .map(b => (b.key, b.version))
        .distinct
        .flatMap((k, v) => MetadataStore.field(k, v).map((k, v) -> _))
        .toMap
      profile <- Profiles
        .resolve(chain, (k, v) => definitions.get((k, v)))
        .left
        .map(errors => MetadataError.Invalid(errors.map(_.message)))
    yield profile

  /** Ancestors from the root down to the direct parent. */
  private def ancestorsOf(cls: DocumentClassVersion, seen: List[DocumentClassVersion])(using
      DbTx
  ): Either[MetadataError, List[DocumentClassVersion]] =
    cls.parent match
      case None                                          => Right(seen)
      case Some(ref) if seen.exists(_.classId == ref.id) =>
        Left(MetadataError.Invalid(List(s"class ${cls.key} has a cyclic parent chain")))
      case Some(ref) =>
        MetadataStore
          .classVersion(ref.id, ref.version)
          .toRight(
            MetadataError.NotFound(s"parent class ${ref.id.value} version ${ref.version} does not exist")
          )
          .flatMap(parent => ancestorsOf(parent, parent :: seen))
