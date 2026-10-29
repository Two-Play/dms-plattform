package dms.app

import dms.documents.metadata.MetadataValue
import dms.documents.core.DocumentDetails
import dms.documents.{DocumentVersion, VersionFile}
import dms.kernel.UserId
import dms.documents.core.DocumentJson
import dms.templates.{WordField, WordFieldSource, WordFieldType, WordFields}

import java.time.{Instant, LocalDate}
import scala.util.Try

/** The values filled into the fields of a Word document version (DMS-115, ADR 0042): automatic fields from
  * the platform (system values and the version's metadata), manual fields from the values entered for the
  * version. Dates in the document language; persons by display name.
  */
object WordFilling:

  /** The approval of a version as printed: when, by whom, on behalf of whom (deputy). */
  final case class ApprovalFacts(at: Instant, by: UserId, onBehalfOf: Option[UserId])

  /** The value of every automatic key the platform knows for the version (system keys and metadata keys). */
  def automatic(
      details: DocumentDetails,
      v: DocumentVersion,
      names: Map[UserId, String],
      approval: Option[ApprovalFacts]
  ): Map[String, String] =
    val language                    = DocumentRenditions.language(v).code
    val name                        = (u: UserId) => names.getOrElse(u, u.value)
    val zone                        = details.zone
    def instant(i: Instant): String = VersionPrintLayout.date(i.atZone(zone).toLocalDate, language)
    val metadata = v.metadata.flatMap((k, value) => format(value, language, name).map(k -> _))
    val approved =
      approval.orElse(v.releasedAt.zip(v.releasedBy).map((at, by) => ApprovalFacts(at, by, None)))
    val system = List(
      Some("documentId" -> details.document.id.value.toString),
      details.document.number.orElse(metadata.get("documentNumber")).map("documentNumber" -> _),
      Some("title"   -> DocumentRenditions.title(details, v)),
      Some("version" -> (if v.releasedAt.isDefined || approval.isDefined then v.number.show else v.label)),
      Some(
        "status" -> VersionPrintLayout.statusLabel(
          if approval.isDefined then "Approved" else v.status.toString,
          language
        )
      ),
      Some("author" -> name(v.createdBy)),
      details.document.ownerId.map(o => "owner" -> name(o)),
      metadata.get("organizationalUnit").map("issuingUnit" -> _),
      details.profile.classPath.lastOption.map(c => "class" -> c.value),
      approved.map(a => "approvedAt" -> instant(a.at)),
      approved.map(a =>
        "approvedBy" -> (name(a.by) + a.onBehalfOf.fold("")(o =>
          (if language == "de" then " i. V. für " else " on behalf of ") + name(o)
        ))
      )
    ).flatten.toMap
    metadata ++ system

  /** The automatic values as filled: dates (metadata dates, the approval date) as [[FieldValue.Date]], so
    * that date picker controls print them in their own format (DMS-122), everything else as text.
    */
  def automaticValues(
      details: DocumentDetails,
      v: DocumentVersion,
      names: Map[UserId, String],
      approval: Option[ApprovalFacts]
  ): Map[String, FieldValue] =
    val texts    = automatic(details, v, names, approval)
    val approved =
      approval.orElse(v.releasedAt.zip(v.releasedBy).map((at, by) => ApprovalFacts(at, by, None)))
    val dates = v.metadata.collect { case (k, MetadataValue.Date(d)) => k -> d } ++
      approved.map(a => "approvedAt" -> a.at.atZone(details.zone).toLocalDate)
    texts.map((k, text) => k -> dates.get(k).fold(FieldValue.Text(text))(FieldValue.Date(_, text)))

  /** The values to fill into the version's file: automatic fields from [[automaticValues]], manual ones as
    * entered (dates formatted in the document language, check boxes checked or not, rows with their typed
    * columns). Fields without value are left out.
    */
  def values(
      details: DocumentDetails,
      v: DocumentVersion,
      file: VersionFile,
      names: Map[UserId, String],
      approval: Option[ApprovalFacts]
  ): Map[String, FieldValue] =
    val language = DocumentRenditions.language(v).code
    val auto     = automaticValues(details, v, names, approval)
    file.fields.flatMap { f =>
      val value = f.source match
        case WordFieldSource.Automatic => auto.get(f.key).filter(_.printed.nonEmpty)
        case WordFieldSource.Manual    =>
          file.values.get(f.key).filter(_.nonEmpty).flatMap(manual(f, _, language))
      value.map(f.key -> _)
    }.toMap

  /** A manual value as filled. */
  private def manual(field: WordField, raw: String, language: String): Option[FieldValue] =
    field.fieldType match
      case WordFieldType.Date =>
        Some(
          Try(LocalDate.parse(raw)).toOption
            .fold(FieldValue.Text(raw))(d => FieldValue.Date(d, VersionPrintLayout.date(d, language)))
        )
      case WordFieldType.Checkbox => Some(FieldValue.Check(raw == WordFields.Checked))
      case WordFieldType.Rows     =>
        DocumentJson
          .readRows(raw)
          .toOption
          .map(rows =>
            FieldValue.Rows(
              rows.map(row =>
                field.columns
                  .flatMap(c =>
                    row.get(c.key).filter(_.nonEmpty).flatMap(manual(c, _, language)).map(c.key -> _)
                  )
                  .toMap
              )
            )
          )
      case _ => Some(FieldValue.Text(raw))

  /** The persons whose names the automatic values may print. */
  def people(details: DocumentDetails, v: DocumentVersion, approval: Option[ApprovalFacts]): Set[UserId] =
    Set(v.createdBy) ++ details.document.ownerId ++ v.releasedBy ++ approval.toList.flatMap(a =>
      a.by :: a.onBehalfOf.toList
    ) ++ v.metadata.values.collect { case MetadataValue.PersonRef(id) => UserId(id) }

  private def format(value: MetadataValue, language: String, name: UserId => String): Option[String] =
    value match
      case MetadataValue.Text(t)        => Some(t)
      case MetadataValue.Number(n)      => Some(n.bigDecimal.toPlainString)
      case MetadataValue.Amount(n, cur) => Some(s"${n.bigDecimal.toPlainString} $cur")
      case MetadataValue.Date(d)        => Some(VersionPrintLayout.date(d, language))
      case MetadataValue.Selection(c)   => Some(c)
      case MetadataValue.YesNo(b)       =>
        Some(if language == "de" then (if b then "Ja" else "Nein") else (if b then "Yes" else "No"))
      case MetadataValue.PersonRef(id)   => Some(name(UserId(id)))
      case MetadataValue.DocumentRef(id) => Some(id.toString)
      case MetadataValue.Many(values)    =>
        Some(values.flatMap(format(_, language, name)).mkString(", ")).filter(_.nonEmpty)
