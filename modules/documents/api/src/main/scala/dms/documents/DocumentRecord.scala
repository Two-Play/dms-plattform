package dms.documents

import dms.documents.metadata.CoreFields
import dms.kernel.UserId

/** Where the document differs from the version that last gave it owner and title (DMS-162, ADR 0084).
  *
  * @param version
  *   [[Versioning.lastInForce]]
  * @param owner
  *   the owner `version` names, if that is not the document's
  * @param title
  *   the title `version` names, if that is not the document's
  */
final case class RecordDifference(version: DocumentVersion, owner: Option[UserId], title: Option[String])

/** An open version below the level of the version it started from (DMS-162, ADR 0084). */
final case class DraftBelowBasis(version: DocumentVersion, basis: DocumentVersion)

/** Pure rules of the reports for records management (DMS-162, ADR 0084). */
object DocumentRecord:

  /** What a draft saved before ADR 0074 left behind on the document. A value the version does not have says
    * nothing, as at publication. `None` if nothing differs or no version ever took effect.
    */
  def difference(document: Document, versions: List[DocumentVersion]): Option[RecordDifference] =
    Versioning
      .lastInForce(versions)
      .map(v =>
        RecordDifference(
          v,
          v.namedOwner.filterNot(document.ownerId.contains),
          v.namedTitle.filterNot(_ == document.title)
        )
      )
      .filter(d => d.owner.isDefined || d.title.isDefined)

  /** The version on its way through the workflow, if any: it read the owner when it started, and takes owner
    * and title to the document itself once it is published, so a correction waits for it.
    */
  def inWorkflow(versions: List[DocumentVersion]): Option[DocumentVersion] =
    versions.find(v =>
      v.status == DocumentStatus.Approved || (Versioning.isOpen(v.status) && v.status != DocumentStatus.Draft)
    )

  /** The open version, if it stands below its basis. Both sides count by what access goes by
    * ([[DocumentVersion.accessMetadata]]): an approved lowering is below nothing until it takes effect.
    */
  def belowBasis(versions: List[DocumentVersion]): Option[DraftBelowBasis] =
    for
      open <- versions.find(v => Versioning.isOpen(v.status))
      b    <- Versioning.basis(open, versions)
      if CoreFields.lowersConfidentiality(b.accessMetadata, open.accessMetadata)
    yield DraftBelowBasis(open, b)
