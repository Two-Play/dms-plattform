package dms.documents.metadata

import dms.documents.{DocumentStatus, Lifecycle, LifecycleError, LifecycleEvent}

/** What is wrong with a field. `code` is stable for clients; `message` is for display. */
enum IssueKind:
  /** The field has no value but is required in the target status. */
  case MissingRequired(status: DocumentStatus)
  case UnknownField
  case WrongType(expected: String, actual: String)
  case OutOfRange(detail: String)
  case PatternMismatch(pattern: String)
  case UnknownOption(option: String)

  /** The option exists but was removed from the current field version. */
  case RetiredOption(option: String)
  case UnknownCurrency(currency: String)

  /** The field is set by the platform or taken over from a business system and cannot be entered. */
  case NotEditable

  def code: String = this match
    case MissingRequired(_) => "missing_required"
    case UnknownField       => "unknown_field"
    case WrongType(_, _)    => "wrong_type"
    case OutOfRange(_)      => "out_of_range"
    case PatternMismatch(_) => "pattern_mismatch"
    case UnknownOption(_)   => "unknown_option"
    case RetiredOption(_)   => "retired_option"
    case UnknownCurrency(_) => "unknown_currency"
    case NotEditable        => "not_editable"

  def message: String = this match
    case MissingRequired(status)     => s"The field is required in status $status."
    case UnknownField                => "The field is not part of the profile."
    case WrongType(expected, actual) => s"Expected a value of type $expected, got $actual."
    case OutOfRange(detail)          => detail
    case PatternMismatch(pattern)    => s"The value does not match the format $pattern."
    case UnknownOption(option)       => s"'$option' is not an option of this field."
    case RetiredOption(option)       => s"The option '$option' is no longer available."
    case UnknownCurrency(currency)   => s"'$currency' is not an allowed currency."
    case NotEditable                 =>
      "The field is set by the platform or taken over from a business system and cannot be entered."

/** A problem with one field, addressable by its stable key. */
final case class FieldIssue(field: String, label: String, kind: IssueKind)

/** Why a document cannot move to the next status. */
enum TransitionBlocked:
  case NotAllowed(error: LifecycleError)

  /** Required fields of `target` are missing or values are invalid. */
  case Incomplete(target: DocumentStatus, issues: List[FieldIssue])

/** Pure validation of metadata values against an effective profile. */
object Validation:

  private val CurrencyCode = "[A-Z]{3}".r

  def isCurrencyCode(code: String): Boolean = CurrencyCode.matches(code)

  /** Validates `values` for a document that is to be in `targetStatus`: every field required in that status
    * (including fields required because of other values, [[CoreFields.requiredByValues]]) must have a value,
    * and every value must satisfy its definition. Issues follow the profile's field order, unknown fields
    * come last.
    *
    * @param includeSystemFields
    *   `false` skips fields set by the platform (document id, status, checksum, ...), e.g. for checking an
    *   author's input before the platform adds its values
    */
  def validate(
      profile: MetadataProfile,
      values: Map[String, MetadataValue],
      targetStatus: DocumentStatus,
      includeSystemFields: Boolean = true
  ): List[FieldIssue] =
    val fields      = profile.fields.filter(f => includeSystemFields || f.definition.editable)
    val conditional = CoreFields.requiredByValues(values, targetStatus)
    val known       = fields.flatMap { field =>
      val value    = values.get(field.key.value)
      val required = field.isRequiredIn(targetStatus) || conditional.contains(field.key.value)
      val kinds    =
        if isEmpty(value) then Option.when(required)(IssueKind.MissingRequired(targetStatus)).toList
        else value.toList.flatMap(checkField(field.definition, _))
      kinds.map(FieldIssue(field.key.value, field.definition.label, _))
    }
    known ++ unknownFields(profile, values)

  /** Applies a lifecycle event only if the metadata is complete and valid for the target status, e.g. a
    * submission (`Submit`) is blocked while fields required in `InReview` are missing.
    */
  def checkTransition(
      profile: MetadataProfile,
      values: Map[String, MetadataValue],
      from: DocumentStatus,
      event: LifecycleEvent
  ): Either[TransitionBlocked, DocumentStatus] =
    Lifecycle.transition(from, event).left.map(TransitionBlocked.NotAllowed(_)).flatMap { target =>
      val issues = validate(profile, values, target)
      Either.cond(issues.isEmpty, target, TransitionBlocked.Incomplete(target, issues))
    }

  /** Checks the given values against their field definitions: type, rules, options and unknown fields. Fields
    * without a value are not reported here.
    */
  def checkValues(profile: MetadataProfile, values: Map[String, MetadataValue]): List[FieldIssue] =
    val known = profile.fields.flatMap { field =>
      values.get(field.key.value).toList.flatMap { value =>
        checkField(field.definition, value).map(FieldIssue(field.key.value, field.definition.label, _))
      }
    }
    known ++ unknownFields(profile, values)

  private def unknownFields(profile: MetadataProfile, values: Map[String, MetadataValue]): List[FieldIssue] =
    values.keySet
      .filterNot(key => profile.field(key).isDefined)
      .toList
      .sorted
      .map(key => FieldIssue(key, key, IssueKind.UnknownField))

  /** Whether `value` counts as not filled in (absent, blank text or an empty list). */
  def isEmpty(value: Option[MetadataValue]): Boolean = value match
    case None                                => true
    case Some(MetadataValue.Text(text))      => text.trim.isEmpty
    case Some(MetadataValue.PersonRef(user)) => user.trim.isEmpty
    case Some(MetadataValue.Selection(code)) => code.trim.isEmpty
    case Some(MetadataValue.Many(values))    => values.forall(v => isEmpty(Some(v)))
    case Some(_)                             => false

  def checkField(definition: FieldDefinition, value: MetadataValue): List[IssueKind] =
    (definition.multiple, value) match
      case (true, MetadataValue.Many(values)) => values.flatMap(checkSingle(definition.fieldType, _)).distinct
      case (true, other) => List(IssueKind.WrongType(s"list of ${definition.fieldType.kind}", other.kind))
      case (false, many: MetadataValue.Many) =>
        List(IssueKind.WrongType(definition.fieldType.kind, many.kind))
      case (false, single) => checkSingle(definition.fieldType, single)

  private def checkSingle(fieldType: FieldType, value: MetadataValue): List[IssueKind] =
    (fieldType, value) match
      case (FieldType.Text(maxLength, pattern), MetadataValue.Text(text)) =>
        maxLength
          .filter(text.length > _)
          .map(n => IssueKind.OutOfRange(s"At most $n characters are allowed."))
          .toList ++
          pattern.filterNot(p => text.isEmpty || p.r.matches(text)).map(IssueKind.PatternMismatch(_)).toList

      case (FieldType.Number(min, max, scale), MetadataValue.Number(n)) =>
        bounds(n, min, max) ++
          scale
            .filter(s => n.underlying.stripTrailingZeros.scale > s)
            .map(s => IssueKind.OutOfRange(s"At most $s decimal places are allowed."))
            .toList

      case (FieldType.Amount(min, max, currencies), MetadataValue.Amount(n, currency)) =>
        val currencyIssue =
          Option.when(!isCurrencyCode(currency) || (currencies.nonEmpty && !currencies.contains(currency)))(
            IssueKind.UnknownCurrency(currency)
          )
        currencyIssue.toList ++ bounds(n, min, max)

      case (FieldType.Date(min, max), MetadataValue.Date(date)) =>
        min.filter(date.isBefore).map(m => IssueKind.OutOfRange(s"The date must not be before $m.")).toList ++
          max.filter(date.isAfter).map(m => IssueKind.OutOfRange(s"The date must not be after $m.")).toList

      case (FieldType.Selection(options), MetadataValue.Selection(code)) =>
        options.find(_.code == code) match
          case None                           => List(IssueKind.UnknownOption(code))
          case Some(option) if !option.active => List(IssueKind.RetiredOption(code))
          case Some(_)                        => Nil

      case (FieldType.YesNo, MetadataValue.YesNo(_))             => Nil
      case (FieldType.PersonRef, MetadataValue.PersonRef(_))     => Nil
      case (FieldType.DocumentRef, MetadataValue.DocumentRef(_)) => Nil
      case (expected, actual) => List(IssueKind.WrongType(expected.kind, actual.kind))

  private def bounds(n: BigDecimal, min: Option[BigDecimal], max: Option[BigDecimal]): List[IssueKind] =
    min.filter(n < _).map(m => IssueKind.OutOfRange(s"The value must be at least $m.")).toList ++
      max.filter(n > _).map(m => IssueKind.OutOfRange(s"The value must be at most $m.")).toList
