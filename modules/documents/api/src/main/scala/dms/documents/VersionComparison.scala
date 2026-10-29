package dms.documents

import dms.documents.metadata.{CoreFields, MetadataProfile, MetadataValue}
import dms.kernel.UserId
import dms.templates.{ContentDiff, TemplateContent, WordFieldType}

import java.time.Instant
import java.util.UUID
import scala.math.Ordering.Implicits.*

/** A field value or metadata entry that differs between two versions: a value, its origin or both. A value
  * that exists on one side only has `None` on the other.
  */
final case class ValueChange(
    key: String,
    label: String,
    before: Option[MetadataValue],
    after: Option[MetadataValue],
    originBefore: Option[ValueOrigin] = None,
    originAfter: Option[ValueOrigin] = None
)

/** What the author of a version wrote about its changes: the reason given when the version was started
  * (required for editorial versions, optional for major ones).
  */
final case class ChangeSummary(
    versionId: UUID,
    label: String,
    changeKind: ChangeKind,
    summary: Option[String],
    createdBy: UserId,
    createdAt: Instant
)

/** The comparison of two versions of a document for readers (US-05.1, DMS-54), in three parts: the text of
  * the content, the field values of the document class (with their origin) and the metadata (core and system
  * fields and the properties of the version such as status, approval and rendition).
  *
  * @param summaries
  *   the change summaries of the versions after the earlier compared version up to and including the later
  *   one, oldest first: what their authors said they changed
  */
final case class VersionComparison(
    from: DocumentVersion,
    to: DocumentVersion,
    summaries: List[ChangeSummary],
    text: ContentDiff,
    fields: List[ValueChange],
    metadata: List[ValueChange]
)

object VersionComparison:

  /** Compares `from` with `to`; either may be the newer one.
    *
    * @param profile
    *   labels the fields
    * @param versions
    *   the versions whose change summaries may be shown, e.g. those the caller may read; withdrawn versions
    *   between the two are left out
    * @param contents
    *   the texts to compare instead of the versions' structured content, e.g. the paragraphs of the filled
    *   Word documents of file versions (DMS-121), whose structured content is not used
    */
  def compare(
      profile: MetadataProfile,
      from: DocumentVersion,
      to: DocumentVersion,
      versions: List[DocumentVersion],
      contents: Option[(TemplateContent, TemplateContent)] = None
  ): VersionComparison =
    val (before, after) = contents.getOrElse((from.content, to.content))
    VersionComparison(
      from,
      to,
      summaries(from, to, versions),
      ContentDiff.compare(before, after),
      changes(profile, fieldEntries(from), fieldEntries(to)) ++ wordValueChanges(from, to),
      changes(profile, metadataEntries(from), metadataEntries(to))
    )

  /** Prefix of the keys of manual Word field values in the field changes (DMS-121). */
  val WordFieldPrefix = "word:"

  /** Labels of the version properties that are not metadata fields. */
  private val propertyLabels: List[(String, String)] = List(
    "changeKind"              -> "Change kind",
    "classVersion"            -> "Class version",
    "approvedBy"              -> "Approved by",
    "approvedAt"              -> "Approved at",
    "publishedAt"             -> "Published at",
    "requiresAcknowledgement" -> "Requires acknowledgement",
    "originalName"            -> "Original file",
    "originalChecksum"        -> "Original checksum"
  )

  private def isCore(key: String): Boolean = CoreFields.fields.exists(_.key.value == key)

  /** Field values of the document class: everything that is not a core or system field. */
  private def fieldEntries(v: DocumentVersion): Map[String, FieldEntry] =
    v.values.filter((k, _) => !isCore(k))

  /** Core and system fields with the status and checksum of the version, and its properties. */
  private def metadataEntries(v: DocumentVersion): Map[String, FieldEntry] =
    def platform(value: MetadataValue) = FieldEntry(value, ValueOrigin.Platform)
    val properties                     = Map(
      "status"                  -> Some(MetadataValue.Selection(v.status.toString)),
      "renditionChecksum"       -> v.rendition.map(r => MetadataValue.Text(r.sha256)),
      "changeKind"              -> Some(MetadataValue.Text(v.changeKind.toString)),
      "classVersion"            -> Some(MetadataValue.Number(BigDecimal(v.classVersion))),
      "approvedBy"              -> v.releasedBy.map(u => MetadataValue.PersonRef(u.value)),
      "approvedAt"              -> v.releasedAt.map(t => MetadataValue.Text(t.toString)),
      "publishedAt"             -> v.publishedAt.map(t => MetadataValue.Text(t.toString)),
      "requiresAcknowledgement" -> Some(MetadataValue.YesNo(v.requiresAcknowledgement)),
      // The original of a file version (DMS-115): its name and the SHA-256 the approval binds.
      "originalName"     -> v.file.map(f => MetadataValue.Text(f.name)),
      "originalChecksum" -> v.file.map(f => MetadataValue.Text(f.sha256))
    ).collect { case (k, Some(value)) => k -> platform(value) }
    v.values.filter((k, _) => isCore(k)) ++ properties

  /** The entries that differ, in the order of the profile, then the version properties, then by key. */
  private def changes(
      profile: MetadataProfile,
      before: Map[String, FieldEntry],
      after: Map[String, FieldEntry]
  ): List[ValueChange] =
    val profileOrder               = (CoreFields.fields ++ profile.fields).map(_.key.value).distinct
    val propertyOrder              = propertyLabels.map(_._1)
    val known                      = profileOrder ++ propertyOrder
    val keys                       = before.keySet ++ after.keySet
    val ordered                    = known.filter(keys.contains) ++ (keys -- known).toList.sorted
    def label(key: String): String =
      profile
        .field(key)
        .map(_.definition.label)
        .orElse(CoreFields.fields.find(_.key.value == key).map(_.definition.label))
        .orElse(propertyLabels.toMap.get(key))
        .getOrElse(key)
    ordered.flatMap { key =>
      val (b, a) = (before.get(key), after.get(key))
      Option.when(b != a)(
        ValueChange(key, label(key), b.map(_.value), a.map(_.value), b.map(_.origin), a.map(_.origin))
      )
    }

  /** The values of the manual fields of the versions' Word files that differ (DMS-121), in the field order of
    * the later file, keyed `word:<key>`, labelled by their definitions; dates typed as dates.
    */
  private def wordValueChanges(from: DocumentVersion, to: DocumentVersion): List[ValueChange] =
    val definitions =
      (to.file.toList.flatMap(_.fields) ++ from.file.toList.flatMap(_.fields)).distinctBy(_.key)
    val before  = from.file.fold(Map.empty[String, String])(_.values)
    val after   = to.file.fold(Map.empty[String, String])(_.values)
    val keys    = before.keySet ++ after.keySet
    val ordered =
      definitions.map(_.key).filter(keys.contains) ++ (keys -- definitions.map(_.key)).toList.sorted
    def value(key: String, raw: String): MetadataValue =
      definitions
        .find(_.key == key)
        .filter(_.fieldType == WordFieldType.Date)
        .flatMap(_ => scala.util.Try(java.time.LocalDate.parse(raw)).toOption)
        .fold(MetadataValue.Text(raw))(MetadataValue.Date(_))
    ordered.flatMap { key =>
      val (b, a) = (before.get(key).filter(_.nonEmpty), after.get(key).filter(_.nonEmpty))
      Option.when(b != a)(
        ValueChange(
          WordFieldPrefix + key,
          definitions.find(_.key == key).fold(key)(_.label),
          b.map(value(key, _)),
          a.map(value(key, _)),
          b.map(_ => ValueOrigin.Manual),
          a.map(_ => ValueOrigin.Manual)
        )
      )
    }

  private def summaries(
      from: DocumentVersion,
      to: DocumentVersion,
      versions: List[DocumentVersion]
  ): List[ChangeSummary] =
    val (earlier, later) = if order(from) <= order(to) then (from, to) else (to, from)
    (versions :+ later)
      .distinctBy(_.id)
      .filter(v => order(v) > order(earlier) && order(v) <= order(later))
      .filter(v => v.status != DocumentStatus.Withdrawn || v.id == later.id)
      .sortBy(order)
      .map(v => ChangeSummary(v.id, v.label, v.changeKind, v.changeReason, v.createdBy, v.createdAt))

  /** Versions in the order they were started. */
  private def order(v: DocumentVersion): (Instant, Int, Int, Int) =
    (v.createdAt, v.number.major, v.number.minor, v.workingState)
end VersionComparison
