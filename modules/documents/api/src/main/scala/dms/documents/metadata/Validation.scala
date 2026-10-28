package dms.documents.metadata

/** What is wrong with a field. `code` is stable for clients; `message` is for display. */
enum IssueKind:
  case UnknownField
  case WrongType(expected: String, actual: String)
  case OutOfRange(detail: String)
  case PatternMismatch(pattern: String)
  case UnknownOption(option: String)

  /** The option exists but was removed from the current field version. */
  case RetiredOption(option: String)
  case UnknownCurrency(currency: String)

  def code: String = this match
    case UnknownField       => "unknown_field"
    case WrongType(_, _)    => "wrong_type"
    case OutOfRange(_)      => "out_of_range"
    case PatternMismatch(_) => "pattern_mismatch"
    case UnknownOption(_)   => "unknown_option"
    case RetiredOption(_)   => "retired_option"
    case UnknownCurrency(_) => "unknown_currency"

  def message: String = this match
    case UnknownField                => "The field is not part of the profile."
    case WrongType(expected, actual) => s"Expected a value of type $expected, got $actual."
    case OutOfRange(detail)          => detail
    case PatternMismatch(pattern)    => s"The value does not match the format $pattern."
    case UnknownOption(option)       => s"'$option' is not an option of this field."
    case RetiredOption(option)       => s"The option '$option' is no longer available."
    case UnknownCurrency(currency)   => s"'$currency' is not an allowed currency."

/** A problem with one field, addressable by its stable key. */
final case class FieldIssue(field: String, label: String, kind: IssueKind)

/** Pure validation of metadata values against an effective profile. */
object Validation:

  private val CurrencyCode = "[A-Z]{3}".r

  def isCurrencyCode(code: String): Boolean = CurrencyCode.matches(code)

  /** Checks the given values against their field definitions: type, rules, options and unknown fields. Fields
    * without a value are not reported here.
    */
  def checkValues(profile: MetadataProfile, values: Map[String, MetadataValue]): List[FieldIssue] =
    val known = profile.fields.flatMap { field =>
      values.get(field.key.value).toList.flatMap { value =>
        checkField(field.definition, value).map(FieldIssue(field.key.value, field.definition.label, _))
      }
    }
    val unknown = values.keySet
      .filterNot(key => profile.field(key).isDefined)
      .toList
      .sorted
      .map(key => FieldIssue(key, key, IssueKind.UnknownField))
    known ++ unknown

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
