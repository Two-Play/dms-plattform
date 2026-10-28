package dms.documents.metadata

import dms.documents.DocumentStatus

/** Core and system metadata of business concept 3.2: the root profile every document class inherits.
  *
  * Defined by the platform, identical for all tenants and not stored per tenant. Tenants cannot define fields
  * with these keys; classes may only tighten their requiredness.
  */
object CoreFields:
  import DocumentStatus.*
  import FieldOrigin.{Core, System}

  /** Version of the built-in definitions; a platform release that changes them increments it. */
  val Version = 1

  private def field(
      key: String,
      label: String,
      fieldType: FieldType,
      origin: FieldOrigin,
      requiredIn: Set[DocumentStatus],
      multiple: Boolean = false,
      description: Option[String] = None
  ): ProfileField =
    ProfileField(
      FieldDefinition(FieldKey.unsafe(key), Version, label, description, fieldType, multiple, origin),
      requiredIn,
      definedBy = None
    )

  private def selection(codes: String*): FieldType.Selection =
    FieldType.Selection(codes.map(c => SelectOption(c, c)).toList)

  /** Key of the confidentiality level field. */
  val Confidentiality = "confidentiality"

  /** Key of the field naming the access groups of a confidential version (DMS-101, ADR 0032). */
  val AllowedGroups = "allowedGroups"

  /** Format of access group keys (lowercase letters, digits and hyphens). */
  val GroupKeyPattern = "[a-z0-9][a-z0-9-]{0,62}"

  /** Confidentiality levels from which access is restricted to named groups (business concept 2.3). */
  val RestrictedLevels: Set[String] = Set("Confidential", "StrictlyConfidential")

  /** Whether `values` restrict access to named groups, i.e. the confidentiality is at least `Confidential`.
    */
  def isRestricted(values: Map[String, MetadataValue]): Boolean =
    values.get(Confidentiality).exists {
      case MetadataValue.Selection(code) => RestrictedLevels.contains(code)
      case MetadataValue.Text(text)      => RestrictedLevels.contains(text)
      case _                             => false
    }

  /** Fields that are required because of other values: [[AllowedGroups]] for a restricted version from the
    * status on in which the confidentiality itself is required (review), so that a confidential version
    * cannot enter the workflow without anyone besides author and owner being allowed to see it.
    */
  def requiredByValues(values: Map[String, MetadataValue], status: DocumentStatus): Set[String] =
    if isRestricted(values) && fromReview.contains(status) then Set(AllowedGroups) else Set.empty

  private val fromDraft  = Requiredness.always
  private val fromReview = Requiredness.from(InReview)

  /** The root profile, in display order. */
  val fields: List[ProfileField] = List(
    field("documentId", "Document ID", FieldType.Text(maxLength = Some(64)), System, fromDraft),
    field("title", "Title", FieldType.Text(maxLength = Some(500)), Core, fromDraft),
    field("documentClass", "Document class", FieldType.Text(maxLength = Some(63)), System, fromDraft),
    field("version", "Version", FieldType.Text(pattern = Some("""\d+\.\d+""")), System, fromDraft),
    field("status", "Status", selection(DocumentStatus.values.map(_.toString).toSeq*), System, fromDraft),
    field(
      "owner",
      "Owner",
      FieldType.PersonRef,
      Core,
      fromReview,
      description = Some("Preferably a function rather than a person")
    ),
    field(
      "organizationalUnit",
      "Organizational unit",
      FieldType.Text(maxLength = Some(200)),
      Core,
      fromReview
    ),
    field(
      "scope",
      "Scope",
      FieldType.Text(maxLength = Some(200)),
      Core,
      Set.empty,
      multiple = true,
      description = Some("Companies, locations, functions")
    ),
    field("validFrom", "Valid from", FieldType.Date(), Core, fromReview),
    field("validUntil", "Valid until", FieldType.Date(), Core, Set.empty),
    field("nextReview", "Next review", FieldType.Date(), Core, Set.empty),
    field(
      "confidentiality",
      "Confidentiality",
      selection("Public", "Internal", "Confidential", "StrictlyConfidential"),
      Core,
      fromReview
    ),
    field(
      AllowedGroups,
      "Allowed groups",
      FieldType.Text(maxLength = Some(63), pattern = Some(GroupKeyPattern)),
      Core,
      Set.empty,
      multiple = true,
      description = Some(
        "Access groups whose members may see a confidential version besides author and owner; required from " +
          "review on when the confidentiality is Confidential or StrictlyConfidential"
      )
    ),
    field(
      "language",
      "Language",
      FieldType.Text(pattern = Some("[a-z]{2}(-[A-Z]{2})?")),
      Core,
      fromReview,
      description = Some("Language of the master version, e.g. de or en-GB")
    ),
    field(
      "retentionRule",
      "Retention rule",
      FieldType.Text(maxLength = Some(200)),
      System,
      fromReview,
      description = Some("Set from the class; only records managers change it")
    ),
    field("containsPersonalData", "Contains personal data", FieldType.YesNo, Core, fromReview),
    field("tags", "Tags", FieldType.Text(maxLength = Some(100)), Core, Set.empty, multiple = true),
    field("template", "Template", FieldType.Text(maxLength = Some(200)), System, fromDraft),
    field("templateVersion", "Template version", FieldType.Text(maxLength = Some(32)), System, fromDraft),
    field(
      "renditionChecksum",
      "Rendition checksum",
      FieldType.Text(pattern = Some("[0-9a-f]{64}")),
      System,
      Requiredness.from(Approved),
      description = Some("SHA-256 of the sealed rendition")
    )
  )

  val keys: Set[FieldKey] = fields.map(_.key).toSet

  def isCore(key: FieldKey): Boolean = keys.contains(key)

  def definition(key: FieldKey): Option[FieldDefinition] = fields.find(_.key == key).map(_.definition)
