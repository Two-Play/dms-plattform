package dms.documents.metadata

import dms.documents.{DocumentStatus, ReviewPolicy}

import java.time.LocalDate
import java.util.UUID

/** Stable, addressable key of a field definition, e.g. `contractPartner`. Used in APIs, stored values and
  * issue reports; never changes once created.
  */
opaque type FieldKey = String

object FieldKey:
  private val Pattern = "[a-z][a-zA-Z0-9]{0,62}".r

  def parse(value: String): Either[String, FieldKey] =
    Either.cond(
      Pattern.matches(value),
      value,
      s"invalid field key '$value': lower camel case, letters and digits, at most 63 characters"
    )

  /** For keys known to be valid, such as the built-in core fields. */
  def unsafe(value: String): FieldKey =
    parse(value).fold(e => throw IllegalArgumentException(e), identity)

  extension (key: FieldKey) def value: String = key

  given Ordering[FieldKey] = Ordering.String

/** Stable key of a document class, e.g. `service-contract`. It is the value used in role assignment scopes
  * and in authorization class paths.
  */
opaque type ClassKey = String

object ClassKey:
  private val Pattern = "[a-z][a-z0-9-]{0,62}".r

  def parse(value: String): Either[String, ClassKey] =
    Either.cond(
      Pattern.matches(value),
      value,
      s"invalid class key '$value': lower case letters, digits and hyphens, at most 63 characters"
    )

  def unsafe(value: String): ClassKey =
    parse(value).fold(e => throw IllegalArgumentException(e), identity)

  extension (key: ClassKey) def value: String = key

opaque type DocumentClassId = UUID

object DocumentClassId:
  def apply(value: UUID): DocumentClassId         = value
  def random(): DocumentClassId                   = UUID.randomUUID()
  extension (id: DocumentClassId) def value: UUID = id

/** One entry of a selection list. Options are never deleted: a removed option becomes inactive, so values
  * that use it stay readable (business concept 3.2).
  */
final case class SelectOption(code: String, label: String, active: Boolean = true)

/** Data type of a field with its validation rules (business concept 3.2). */
enum FieldType:
  case Text(maxLength: Option[Int] = None, pattern: Option[String] = None)
  case Number(min: Option[BigDecimal] = None, max: Option[BigDecimal] = None, scale: Option[Int] = None)

  /** Amount with an ISO 4217 currency; `currencies` restricts the allowed codes (empty: any code). */
  case Amount(min: Option[BigDecimal] = None, max: Option[BigDecimal] = None, currencies: List[String] = Nil)
  case Date(min: Option[LocalDate] = None, max: Option[LocalDate] = None)
  case Selection(options: List[SelectOption])
  case YesNo
  case PersonRef
  case DocumentRef

  /** Type name without rules, e.g. `Text`; a new field version must keep it. */
  def kind: String = productPrefix

/** Where a field comes from (the four metadata layers of business concept 3.2). */
enum FieldOrigin:
  /** Platform-wide core metadata, entered by authors; not changeable by tenants. */
  case Core

  /** Set by the platform and not editable: identifiers, status, checksums, template version. */
  case System

  /** Defined by the tenant administrator: class-specific fields and tenant extensions. */
  case Tenant

/** A versioned, central field definition. Classes and templates reference it by key and version instead of
  * copying it. Every change creates a new version; rows are never updated.
  *
  * @param multiple
  *   the field holds a list of values of `fieldType` (e.g. tags, scope)
  */
final case class FieldDefinition(
    key: FieldKey,
    version: Int,
    label: String,
    description: Option[String],
    fieldType: FieldType,
    multiple: Boolean = false,
    origin: FieldOrigin = FieldOrigin.Tenant
):
  def editable: Boolean = origin != FieldOrigin.System

/** A class's use of a field definition version, with the statuses in which the field is required. */
final case class FieldBinding(key: FieldKey, version: Int, requiredIn: Set[DocumentStatus] = Set.empty)

/** A pinned version of a document class. */
final case class ClassRef(id: DocumentClassId, version: Int)

/** One immutable version of a document class: its own field bindings and the pinned parent version. The
  * effective profile including inherited fields is computed by [[Profiles.resolve]].
  *
  * @param review
  *   periodic review settings (US-07.1); the latest class version's settings apply to all documents of the
  *   class
  */
final case class DocumentClassVersion(
    classId: DocumentClassId,
    key: ClassKey,
    version: Int,
    parent: Option[ClassRef],
    label: String,
    description: Option[String],
    fields: List[FieldBinding],
    review: ReviewPolicy = ReviewPolicy.default
):
  def ref: ClassRef = ClassRef(classId, version)

/** A field of an effective profile.
  *
  * @param definedBy
  *   the class that introduced the field; `None` for core and system fields
  */
final case class ProfileField(
    definition: FieldDefinition,
    requiredIn: Set[DocumentStatus],
    definedBy: Option[ClassKey]
):
  def key: FieldKey                                 = definition.key
  def isRequiredIn(status: DocumentStatus): Boolean = requiredIn.contains(status)

/** The effective metadata profile of a document class version: core fields, inherited fields and the class's
  * own fields. Documents refer to `(classId, version)` and keep it until migrated explicitly.
  *
  * @param classPath
  *   class keys from the root to this class, as used by authorization scopes
  */
final case class MetadataProfile(
    classId: DocumentClassId,
    classKey: ClassKey,
    version: Int,
    classPath: List[ClassKey],
    fields: List[ProfileField]
):
  def field(key: String): Option[ProfileField] = fields.find(_.key.value == key)

  def requiredIn(status: DocumentStatus): List[ProfileField] = fields.filter(_.isRequiredIn(status))

/** A metadata value as entered or stored for a document. */
enum MetadataValue:
  case Text(value: String)
  case Number(value: BigDecimal)
  case Amount(value: BigDecimal, currency: String)
  case Date(value: LocalDate)

  /** The code of a [[SelectOption]]. */
  case Selection(code: String)
  case YesNo(value: Boolean)

  /** A user id (ZITADEL `sub`) or a function. */
  case PersonRef(userId: String)
  case DocumentRef(documentId: UUID)

  /** Values of a field with `multiple = true`. */
  case Many(values: List[MetadataValue])

  def kind: String = productPrefix

/** Helpers to express in which statuses a field is required. */
object Requiredness:
  import DocumentStatus.*

  /** The regular path of a document version, in order. */
  val forwardPath: List[DocumentStatus] =
    List(Draft, InReview, InApproval, Approved, Published, ReviewDue, Superseded, Archived)

  /** Required in `status` and every later status of the regular path (e.g. from `InReview` on). Statuses
    * outside the path (Withdrawn, Destroyed) only require it when named explicitly.
    */
  def from(status: DocumentStatus): Set[DocumentStatus] =
    if forwardPath.contains(status) then forwardPath.dropWhile(_ != status).toSet else Set(status)

  val always: Set[DocumentStatus] = from(Draft)
