package dms.documents

import dms.kernel.{DocumentId, TemplateId, UserId}

import java.time.Instant
import java.util.UUID

/** Document versions in one status.
  *
  * @param versions
  *   number of document versions
  * @param documents
  *   number of distinct documents these versions belong to
  */
final case class StatusUsage(status: DocumentStatus, versions: Int, documents: Int)

/** The document versions bound to one template version.
  *
  * @param documents
  *   number of distinct documents with a version on this template version
  * @param byStatus
  *   per status, in the order of [[DocumentStatus]]
  */
final case class VersionUsage(templateVersion: Int, documents: Int, byStatus: List[StatusUsage])

/** An open document version (Draft, InReview, InApproval) bound to an earlier template version. Only what a
  * template designer needs to judge a release (US-01.2): title, author, label, status, template version and
  * time of the last change, never content or field values.
  *
  * @param authorId
  *   the author of the open version
  */
final case class OpenTemplateUse(
    documentId: DocumentId,
    versionId: UUID,
    title: String,
    authorId: UserId,
    label: String,
    status: DocumentStatus,
    templateVersion: Int,
    updatedAt: Instant
)

/** How a template is used by documents on its versions before `beforeVersion` (US-01.2): the basis of the
  * impact analysis before a new template version is released.
  *
  * Documents are bound to the template version they were created from, and new versions of a document keep
  * the template version of the version they are based on. Releasing a template version therefore changes no
  * existing document; it only offers the new version for documents created from now on.
  *
  * @param documents
  *   number of distinct documents with at least one version on an earlier template version
  * @param byStatus
  *   document versions per status over all earlier template versions, in the order of [[DocumentStatus]]
  * @param byVersion
  *   document versions per earlier template version, oldest first; template versions without documents are
  *   left out
  * @param openVersions
  *   the open versions on earlier template versions, the most recently changed first
  */
final case class TemplateUsage(
    templateId: TemplateId,
    beforeVersion: Int,
    documents: Int,
    byStatus: List[StatusUsage],
    byVersion: List[VersionUsage],
    openVersions: List[OpenTemplateUse]
)
