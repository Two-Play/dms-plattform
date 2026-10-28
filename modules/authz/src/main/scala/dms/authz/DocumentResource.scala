package dms.authz

import dms.documents.metadata.{CoreFields, MetadataValue}
import dms.documents.{Document, DocumentStatus, DocumentVersion}
import dms.kernel.{TenantId, UserId}

import scala.util.Try

/** The [[Resource]] of a document version: the attributes [[Policy]] decides on, taken from the document and
  * the version's field values. Shared by the document API and the search index (ADR 0029), so that a search
  * hit and opening the version are decided on the same attributes.
  */
object DocumentResource:

  /** Field holding the organizational unit (core field). */
  val OrganizationalUnitField = "organizationalUnit"

  /** Field holding the confidentiality level (core field); missing or unknown values count as `Internal`. */
  val ConfidentialityField = CoreFields.Confidentiality

  /** Core field listing the keys of the access groups (ADR 0032) allowed to see a confidential or strictly
    * confidential version besides its author and owner (DMS-51, DMS-101).
    */
  val AllowedGroupsField = CoreFields.AllowedGroups

  /** @param classPath
    *   class keys from the root to the document's class (the profile of its class version)
    * @param participants
    *   users the workflow assigned to the version's current step
    */
  def apply(
      tenant: TenantId,
      document: Document,
      classPath: List[String],
      version: DocumentVersion,
      participants: Set[UserId] = Set.empty
  ): Resource =
    of(
      tenant,
      document.ownerId,
      classPath,
      version.status,
      version.createdBy,
      version.releasedBy,
      version.metadata
    )
      .copy(assignedParticipants = participants)

  /** The resource of a version given by its attributes, e.g. from a bulk query (compliance cockpit, DMS-59).
    *
    * @param createdBy
    *   the version's author
    * @param releasedBy
    *   who approved the version
    * @param values
    *   the version's field values
    */
  def of(
      tenant: TenantId,
      ownerId: Option[UserId],
      classPath: List[String],
      status: DocumentStatus,
      createdBy: UserId,
      releasedBy: Option[UserId],
      values: Map[String, MetadataValue]
  ): Resource =
    Resource(
      tenant,
      organizationalUnit = organizationalUnit(values),
      documentClassPath = classPath,
      confidentiality = confidentiality(values),
      status = Some(status),
      authorId = Some(createdBy),
      ownerId = ownerId,
      approvedBy = releasedBy.toSet,
      allowedGroups = allowedGroups(values)
    )

  def organizationalUnit(values: Map[String, MetadataValue]): Option[String] =
    text(values, OrganizationalUnitField)

  def confidentiality(values: Map[String, MetadataValue]): Confidentiality =
    text(values, ConfidentialityField)
      .flatMap(c => Try(Confidentiality.valueOf(c)).toOption)
      .getOrElse(Confidentiality.Internal)

  def allowedGroups(values: Map[String, MetadataValue]): Set[String] =
    def texts(value: MetadataValue): List[String] = value match
      case MetadataValue.Text(t)      => List(t)
      case MetadataValue.Selection(c) => List(c)
      case MetadataValue.Many(vs)     => vs.flatMap(texts)
      case _                          => Nil
    values.get(AllowedGroupsField).toList.flatMap(texts).map(_.trim).filter(_.nonEmpty).toSet

  private def text(values: Map[String, MetadataValue], key: String): Option[String] =
    values.get(key).collect {
      case MetadataValue.Text(t)      => t
      case MetadataValue.Selection(c) => c
    }
