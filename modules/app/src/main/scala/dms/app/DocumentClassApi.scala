package dms.app

import dms.authz.{AccessDenied, Action, Authorizer, ConfigurationArea, Resource}
import dms.db.{Db, DbError}
import dms.documents.DocumentStatus
import dms.documents.core.{ClassDraft, DocumentClassService, FieldDraft, MetadataError}
import dms.documents.core.MetadataJson.given
import dms.documents.metadata.*
import dms.identity.{Authenticator, Principal}
import sttp.model.StatusCode
import sttp.tapir.Schema
import sttp.tapir.generic.Configuration
import sttp.tapir.json.zio.jsonBody
import sttp.tapir.ztapir.*
import zio.*
import zio.json.JsonCodec

import java.util.UUID

/** Tapir schemas for the metadata model; sum types carry a `type` discriminator like their JSON codecs. */
object MetadataSchemas:
  given Configuration          = Configuration.default.withDiscriminator("type")
  given Schema[DocumentStatus] = Schema.derivedEnumeration[DocumentStatus].defaultStringBased
  given Schema[SelectOption]   = Schema.derived
  given Schema[FieldType]      = Schema.derived
  given Schema[MetadataValue]  = Schema.derived

import MetadataSchemas.given

final case class FieldDefinitionView(
    key: String,
    version: Int,
    label: String,
    description: Option[String],
    fieldType: FieldType,
    multiple: Boolean,
    /** Core, System or Tenant. */
    origin: String,
    editable: Boolean
) derives JsonCodec,
      Schema

final case class CreateFieldDefinition(
    key: String,
    label: String,
    description: Option[String],
    fieldType: FieldType,
    multiple: Option[Boolean]
) derives JsonCodec,
      Schema

final case class ChangeFieldDefinition(
    label: String,
    description: Option[String],
    fieldType: FieldType,
    multiple: Option[Boolean]
) derives JsonCodec,
      Schema

/** A class's use of a field definition version and the statuses in which the field is required. */
final case class FieldBindingView(key: String, version: Int, requiredIn: List[DocumentStatus])
    derives JsonCodec,
      Schema

final case class DocumentClassView(
    id: UUID,
    key: String,
    version: Int,
    parentId: Option[UUID],
    parentVersion: Option[Int],
    label: String,
    description: Option[String],
    fields: List[FieldBindingView]
) derives JsonCodec,
      Schema

final case class CreateDocumentClass(
    key: String,
    parentId: Option[UUID],
    /** Parent profile version to inherit from; default: the parent's latest version. */
    parentVersion: Option[Int],
    label: String,
    description: Option[String],
    fields: List[FieldBindingView]
) derives JsonCodec,
      Schema

final case class ChangeDocumentClass(
    label: String,
    description: Option[String],
    fields: List[FieldBindingView],
    parentVersion: Option[Int]
) derives JsonCodec,
      Schema

final case class ProfileFieldView(
    key: String,
    version: Int,
    label: String,
    description: Option[String],
    fieldType: FieldType,
    multiple: Boolean,
    origin: String,
    editable: Boolean,
    requiredIn: List[DocumentStatus],
    /** Class that introduced the field; empty for core and system fields. */
    definedBy: Option[String]
) derives JsonCodec,
      Schema

final case class ProfileView(
    classId: UUID,
    classKey: String,
    version: Int,
    classPath: List[String],
    fields: List[ProfileFieldView]
) derives JsonCodec,
      Schema

/** Administration of document classes and field definitions (business concept 3.1, 3.2). Every endpoint
  * requires `Configure` on [[ConfigurationArea.DocumentClasses]] (tenant administrator).
  */
object DocumentClassApi:
  type Env = Authenticator & Db & Authorizer & DocumentClassService

  private val admin      = "api" / "v1" / "admin"
  private val fieldsPath = admin / "field-definitions"
  private val classPath  = admin / "document-classes"
  private val classId    = path[UUID]("id").description("Document class id")
  private val fieldKey   = path[String]("key").description("Stable field key, e.g. contractPartner")

  val listFields: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(fieldsPath)
      .out(jsonBody[List[FieldDefinitionView]])
      .description("Latest version of every field definition, including the built-in core fields")
      .tag("Configuration")
      .serverLogic(p => _ => configure(p)(_.listFields).map(_.map(fieldView)))

  val getField: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(fieldsPath / fieldKey)
      .out(jsonBody[List[FieldDefinitionView]])
      .description("All versions of a field definition, oldest first")
      .tag("Configuration")
      .serverLogic(p => key => configure(p)(_.fieldVersions(key)).map(_.map(fieldView)))

  val createField: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(fieldsPath)
      .in(jsonBody[CreateFieldDefinition])
      .out(statusCode(StatusCode.Created).and(jsonBody[FieldDefinitionView]))
      .description("Creates version 1 of a field definition")
      .tag("Configuration")
      .serverLogic(p =>
        req =>
          configure(p)(
            _.createField(
              req.key,
              FieldDraft(req.label, req.description, req.fieldType, req.multiple.getOrElse(false)),
              p.userId
            )
          ).map(fieldView)
      )

  val newFieldVersion: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(fieldsPath / fieldKey / "versions")
      .in(jsonBody[ChangeFieldDefinition])
      .out(statusCode(StatusCode.Created).and(jsonBody[FieldDefinitionView]))
      .description(
        "Creates the next version of a field definition. The type kind stays the same; removed selection " +
          "options remain as inactive options."
      )
      .tag("Configuration")
      .serverLogic(p =>
        (key, req) =>
          configure(p)(
            _.newFieldVersion(
              key,
              FieldDraft(req.label, req.description, req.fieldType, req.multiple.getOrElse(false)),
              p.userId
            )
          ).map(fieldView)
      )

  val listClasses: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(classPath)
      .out(jsonBody[List[DocumentClassView]])
      .description("Latest version of every document class")
      .tag("Configuration")
      .serverLogic(p => _ => configure(p)(_.listClasses).map(_.map(classView)))

  val getClass: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(classPath / classId)
      .out(jsonBody[List[DocumentClassView]])
      .description("All profile versions of a document class, oldest first")
      .tag("Configuration")
      .serverLogic(p => id => configure(p)(_.classVersions(DocumentClassId(id))).map(_.map(classView)))

  val createClass: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(classPath)
      .in(jsonBody[CreateDocumentClass])
      .out(statusCode(StatusCode.Created).and(jsonBody[DocumentClassView]))
      .description("Creates a document class with profile version 1, optionally below a parent class")
      .tag("Configuration")
      .serverLogic(p =>
        req =>
          bindings(req.fields)
            .flatMap(fields =>
              configure(p)(
                _.createClass(
                  req.key,
                  req.parentId.map(DocumentClassId(_)),
                  ClassDraft(req.label, req.description, fields, req.parentVersion),
                  p.userId
                )
              )
            )
            .map(classView)
      )

  val newClassVersion: ZServerEndpoint[Env, Any] =
    SecuredApi.base.post
      .in(classPath / classId / "versions")
      .in(jsonBody[ChangeDocumentClass])
      .out(statusCode(StatusCode.Created).and(jsonBody[DocumentClassView]))
      .description(
        "Creates the next profile version of a document class. Existing documents keep their profile version."
      )
      .tag("Configuration")
      .serverLogic(p =>
        (id, req) =>
          bindings(req.fields)
            .flatMap(fields =>
              configure(p)(
                _.newClassVersion(
                  DocumentClassId(id),
                  ClassDraft(req.label, req.description, fields, req.parentVersion),
                  p.userId
                )
              )
            )
            .map(classView)
      )

  val getProfile: ZServerEndpoint[Env, Any] =
    SecuredApi.base.get
      .in(classPath / classId / "profile")
      .in(query[Option[Int]]("version").description("Profile version; default: latest"))
      .out(jsonBody[ProfileView])
      .description("Effective profile of a class version: core, inherited and own fields with requiredness")
      .tag("Configuration")
      .serverLogic(p =>
        (id, version) => configure(p)(_.profile(DocumentClassId(id), version)).map(profileView)
      )

  val endpoints: List[ZServerEndpoint[Env, Any]] = List(
    listFields,
    getField,
    createField,
    newFieldVersion,
    listClasses,
    getClass,
    createClass,
    newClassVersion,
    getProfile
  )

  /** Runs `f` in a transaction of the caller's tenant after checking `Configure` on document classes. */
  private def configure[A](principal: Principal)(
      f: DocumentClassService => IO[MetadataError, A]
  ): ZIO[Db & Authorizer & DocumentClassService, (StatusCode, ApiError), A] =
    val resource = Resource(principal.tenantId, configurationArea = Some(ConfigurationArea.DocumentClasses))
    (for
      db      <- ZIO.service[Db]
      authz   <- ZIO.service[Authorizer]
      service <- ZIO.service[DocumentClassService]
      result  <- db.transact(principal.tenantId)(
        authz.subject(principal).flatMap(authz.check(_, Action.Configure, resource)) *> f(service)
      )
    yield result).mapError(toResponse)

  def toResponse(error: AccessDenied | DbError | MetadataError): (StatusCode, ApiError) = error match
    case AccessDenied(_, reason) =>
      (StatusCode.Forbidden, ApiError("forbidden", s"You are not allowed to do this: $reason."))
    case MetadataError.NotFound(message) => (StatusCode.NotFound, ApiError("not_found", message))
    case MetadataError.Invalid(problems) =>
      (StatusCode.UnprocessableEntity, ApiError("invalid", problems.mkString("; ")))
    case MetadataError.Conflict(message) => (StatusCode.Conflict, ApiError("conflict", message))
    case MetadataError.Database(e)       => databaseError(e)
    case e: DbError                      => databaseError(e)

  private def databaseError(error: DbError): (StatusCode, ApiError) =
    (
      StatusCode.InternalServerError,
      ApiError("database_error", s"The request could not be completed ($error).")
    )

  private def bindings(views: List[FieldBindingView]): IO[(StatusCode, ApiError), List[FieldBinding]] =
    ZIO
      .foreach(views)(v =>
        ZIO.fromEither(FieldKey.parse(v.key)).map(FieldBinding(_, v.version, v.requiredIn.toSet))
      )
      .mapError(e => (StatusCode.UnprocessableEntity, ApiError("invalid", e)))

  private def statuses(s: Set[DocumentStatus]): List[DocumentStatus] = s.toList.sortBy(_.ordinal)

  private def fieldView(d: FieldDefinition): FieldDefinitionView =
    FieldDefinitionView(
      d.key.value,
      d.version,
      d.label,
      d.description,
      d.fieldType,
      d.multiple,
      d.origin.toString,
      d.editable
    )

  private def classView(c: DocumentClassVersion): DocumentClassView =
    DocumentClassView(
      c.classId.value,
      c.key.value,
      c.version,
      c.parent.map(_.id.value),
      c.parent.map(_.version),
      c.label,
      c.description,
      c.fields.map(b => FieldBindingView(b.key.value, b.version, statuses(b.requiredIn)))
    )

  private def profileView(p: MetadataProfile): ProfileView =
    ProfileView(
      p.classId.value,
      p.classKey.value,
      p.version,
      p.classPath.map(_.value),
      p.fields.map { f =>
        val d = f.definition
        ProfileFieldView(
          d.key.value,
          d.version,
          d.label,
          d.description,
          d.fieldType,
          d.multiple,
          d.origin.toString,
          d.editable,
          statuses(f.requiredIn),
          f.definedBy.map(_.value)
        )
      }
    )
