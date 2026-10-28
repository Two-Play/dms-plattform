package dms.documents

import dms.documents.metadata.{DocumentClassId, MetadataValue}
import dms.kernel.{DocumentId, TemplateId, UserId, VersionNo}
import dms.templates.{Block, TemplateContent}

import java.time.Instant
import java.util.UUID

/** Where a field value comes from (business concept 4.3, hybrid creation). */
enum ValueOrigin:
  /** Entered by a person. */
  case Manual

  /** Set by a system: the platform itself (`dms`) or a connected business system, e.g. `crm`. */
  case System(source: String)

  /** Computed from other fields by a computed field of the template (business concept 4.2). */
  case Computed

object ValueOrigin:
  /** The platform as source of system values (document id, class, version, template). */
  val Platform: ValueOrigin = System("dms")

/** A field value of a document version with its origin. */
final case class FieldEntry(value: MetadataValue, origin: ValueOrigin)

/** The sealed rendition of a version in object storage (ADR 0016), produced by rendering (ADR 0008).
  *
  * @param sha256
  *   lowercase hex SHA-256 of the rendition, the `renditionChecksum` of the metadata
  */
final case class Rendition(objectKey: String, objectVersion: Option[String], sha256: String)

/** The complete metadata of a version as approved, including system fields such as status, version and
  * rendition checksum. Taken once at approval and never changed.
  */
final case class MetadataSnapshot(values: Map[String, MetadataValue], takenAt: Instant)

/** A document: a stable identity whose content lives in versions.
  *
  * @param classVersion
  *   the profile version of the document class the document was created with
  * @param templateVersion
  *   the released template version the document was created from
  */
final case class Document(
    id: DocumentId,
    classId: DocumentClassId,
    classVersion: Int,
    title: String,
    ownerId: Option[UserId],
    authorId: UserId,
    templateId: TemplateId,
    templateVersion: Int,
    createdAt: Instant
)

/** One version of a document: a complete, self-contained version package (ADR 0021). Open versions (draft,
  * review, approval) are worked on; from approval on, the package is frozen.
  *
  * @param number
  *   the version number this version gets on approval, e.g. 1.0 or 1.1
  * @param workingState
  *   saved working states of this version, counted continuously (`1.1-draft.3`)
  * @param scope
  *   the validity scope (see [[Versioning.scopeKey]]); at most one version per scope is valid
  * @param classVersion
  *   the profile version the field values are validated against
  * @param content
  *   the structured content, a copy of the template content (sections incl. locked flags) as edited by the
  *   authors; it pins the clause versions it references
  * @param values
  *   field values with their origin; computed values are not profile fields
  * @param createdBy
  *   the author of this version; they cannot approve it
  */
final case class DocumentVersion(
    id: UUID,
    documentId: DocumentId,
    number: VersionNo,
    workingState: Int,
    status: DocumentStatus,
    changeKind: ChangeKind,
    changeReason: Option[String],
    scope: String,
    classVersion: Int,
    templateId: TemplateId,
    templateVersion: Int,
    content: TemplateContent,
    values: Map[String, FieldEntry],
    metadataSnapshot: Option[MetadataSnapshot],
    rendition: Option[Rendition],
    createdBy: UserId,
    createdAt: Instant,
    updatedAt: Instant,
    releasedBy: Option[UserId],
    releasedAt: Option[Instant]
):
  def label: String = Versioning.label(number, status, workingState)

  /** The field values without their origin. */
  def metadata: Map[String, MetadataValue] = values.map((k, e) => k -> e.value)

  /** The clause versions the content references, in document order without duplicates. */
  def clauses: List[(String, Int)] =
    TemplateContent.blocks(content).collect { case Block.ClauseRef(key, version) => (key, version) }.distinct
