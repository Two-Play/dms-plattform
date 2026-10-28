package dms.templates

import dms.kernel.{TemplateId, UserId}

import java.time.Instant
import java.util.UUID

/** Lifecycle of a template version (business concept 4.1): Draft → Testing → Released → Deprecated →
  * Archived. Only released versions are offered in the template catalog.
  */
enum TemplateStatus:
  case Draft, Testing, Released, Deprecated, Archived

/** One version of a template. A version is immutable once released; changes create a new version.
  *
  * @param documentClassId
  *   the document class whose metadata profile provides the fields the placeholders refer to
  * @param documentClassVersion
  *   the class version the template was designed against
  */
final case class TemplateVersion(
    templateId: TemplateId,
    version: Int,
    name: String,
    description: Option[String],
    documentClassId: UUID,
    documentClassVersion: Int,
    status: TemplateStatus,
    content: TemplateContent,
    createdBy: UserId,
    createdAt: Instant,
    releasedBy: Option[UserId] = None,
    releasedAt: Option[Instant] = None
)
