package dms.templates

import java.time.LocalDate
import java.util.Locale
import scala.util.Try

/** Where the value of a field of a Word document comes from (DMS-115, ADR 0042). */
enum WordFieldSource:
  /** Filled by the platform: a system value (document number, version, approval, …) or a field value of the
    * document's metadata with the same key.
    */
  case Automatic

  /** Entered by the author in the form generated from the fields of the Word file. */
  case Manual

/** Type of a manually filled field; decides the input and how the value is checked and printed. */
enum WordFieldType:
  case Text, Date, Number

/** A field of a Word file (DMS-115, ADR 0042): a content control (Word "Inhaltssteuerelement") whose tag is
  * the field key, or a `{{key}}` placeholder. Keys with the prefix `dms:` are automatic by default, all
  * others manual.
  *
  * @param key
  *   the key without the `dms:` prefix, e.g. `documentNumber` or `customer`
  * @param label
  *   shown in the form: the content control's title (alias), else the key
  */
final case class WordField(
    key: String,
    label: String,
    fieldType: WordFieldType,
    required: Boolean,
    source: WordFieldSource
)

object WordFields:

  /** Prefix of automatic field keys in Word tags and placeholders. */
  val Prefix: String = "dms:"

  /** Keys the platform fills itself, besides the metadata fields of the document's class. */
  val SystemKeys: List[String] = List(
    "documentId",
    "documentNumber",
    "title",
    "version",
    "status",
    "author",
    "owner",
    "issuingUnit",
    "validFrom",
    "validUntil",
    "approvedAt",
    "approvedBy",
    "class"
  )

  /** Accepted keys: a letter followed by letters, digits, `_`, `-` and `.`, at most 64 characters. */
  private val KeyPattern = "[A-Za-z][A-Za-z0-9_.-]{0,63}".r

  /** The field a tag or placeholder names, if it is a valid key: `dms:key` automatic, `key` manual. */
  def parse(tag: String, label: Option[String]): Option[WordField] =
    val raw       = tag.trim
    val automatic = raw.toLowerCase(Locale.ROOT).startsWith(Prefix)
    val key       = if automatic then raw.substring(Prefix.length) else raw
    Option.when(KeyPattern.matches(key))(
      WordField(
        key,
        label.map(_.trim).filter(_.nonEmpty).getOrElse(key),
        if automatic then WordFieldType.Text else guessType(key),
        required = false,
        if automatic then WordFieldSource.Automatic else WordFieldSource.Manual
      )
    )

  private def guessType(key: String): WordFieldType =
    val k = key.toLowerCase(Locale.ROOT)
    if k.endsWith("date") || k.endsWith("datum") then WordFieldType.Date else WordFieldType.Text

  /** Definitions for the fields found in a file: the previous definition of a key wins (labels, types and
    * sources edited by a designer), new keys get the found one. In the order found.
    */
  def merge(found: List[WordField], previous: List[WordField]): List[WordField] =
    val known = previous.map(f => f.key -> f).toMap
    found.distinctBy(_.key).map(f => known.getOrElse(f.key, f))

  /** Why a value entered for a manual field is not acceptable, if it is not. */
  def problem(field: WordField, value: String): Option[String] =
    val v = value.trim
    if v.length > 4000 then Some(s"${field.label}: at most 4000 characters")
    else
      field.fieldType match
        case WordFieldType.Text => None
        case WordFieldType.Date =>
          Option.when(v.nonEmpty && Try(LocalDate.parse(v)).isFailure)(s"${field.label}: a date (yyyy-MM-dd)")
        case WordFieldType.Number =>
          Option.when(v.nonEmpty && Try(BigDecimal(v)).isFailure)(s"${field.label}: a number")

  /** Problems of manual values: unknown keys, invalid values and – with `complete` – missing required ones.
    */
  def check(fields: List[WordField], values: Map[String, String], complete: Boolean): List[String] =
    val manual  = fields.filter(_.source == WordFieldSource.Manual)
    val byKey   = manual.map(f => f.key -> f).toMap
    val unknown = values.keys.toList.sorted.filterNot(byKey.contains).map(k => s"$k: not a manual field")
    val invalid = manual.flatMap(f => values.get(f.key).flatMap(problem(f, _)))
    val missing =
      if complete then
        manual
          .filter(f => f.required && values.get(f.key).forall(_.trim.isEmpty))
          .map(f => s"${f.label}: required")
      else Nil
    unknown ++ invalid ++ missing
