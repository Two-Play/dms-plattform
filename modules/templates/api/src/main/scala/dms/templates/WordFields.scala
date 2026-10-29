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

/** Type of a manually filled field; decides the input and how the value is checked and printed (DMS-115,
  * DMS-122).
  */
enum WordFieldType:
  case Text, Date, Number

  /** Yes or no: a check box content control (`w14:checkbox`); the value is `true` or `false`. */
  case Checkbox

  /** One of the field's options: a drop-down list or combo box content control, or options a designer
    * defined; the value is the option's text.
    */
  case Choice

  /** A list of rows: a repeating section content control (`w15:repeatingSection`) whose item is repeated per
    * row; the value is a JSON array of objects with the values of the columns by key (see
    * [[WordFields.Rows]]).
    */
  case Rows

/** A field of a Word file (DMS-115, ADR 0042): a content control (Word "Inhaltssteuerelement") whose tag is
  * the field key, or a `{{key}}` placeholder. Keys with the prefix `dms:` are automatic by default, all
  * others manual.
  *
  * @param key
  *   the key without the `dms:` prefix, e.g. `documentNumber` or `customer`
  * @param label
  *   shown in the form: the content control's title (alias), else the key
  * @param options
  *   the allowed values of a [[WordFieldType.Choice]] field (DMS-122)
  * @param columns
  *   the columns of a [[WordFieldType.Rows]] field: the manual fields inside the repeating section (DMS-122)
  * @param warning
  *   what the platform cannot fill as the Word file shows it (e.g. a nested repeating section), found when
  *   the file was read (DMS-122)
  */
final case class WordField(
    key: String,
    label: String,
    fieldType: WordFieldType,
    required: Boolean,
    source: WordFieldSource,
    options: List[String] = Nil,
    columns: List[WordField] = Nil,
    warning: Option[String] = None
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

  /** Types a column of a [[WordFieldType.Rows]] field can have. */
  val ColumnTypes: Set[WordFieldType] =
    Set(
      WordFieldType.Text,
      WordFieldType.Date,
      WordFieldType.Number,
      WordFieldType.Checkbox,
      WordFieldType.Choice
    )

  /** Values of a [[WordFieldType.Checkbox]] field. */
  val Checked: String   = "true"
  val Unchecked: String = "false"

  /** Limits of a [[WordFieldType.Rows]] value. */
  val MaxRows: Int = 200

  /** Rows as stored: the value of each column by key; columns without value are left out. */
  type Rows = List[Map[String, String]]

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

  /** Types the Word file itself decides: a check box, drop-down list or repeating section control. */
  private val Structural: Set[WordFieldType] =
    Set(WordFieldType.Checkbox, WordFieldType.Choice, WordFieldType.Rows)

  /** Definitions for the fields found in a file: the previous definition of a key wins (labels, types and
    * sources edited by a designer), new keys get the found one. In the order found. What the file decides
    * structurally wins over the previous definition (DMS-122): the type of check box, drop-down list and
    * repeating section controls, the options of drop-down lists, the column keys of repeating sections and
    * the warnings of the scan.
    */
  def merge(found: List[WordField], previous: List[WordField]): List[WordField] =
    val known = previous.map(f => f.key -> f).toMap
    found.distinctBy(_.key).map(f => known.get(f.key).fold(f)(adopt(f, _)))

  private def adopt(found: WordField, previous: WordField): WordField =
    val fieldType =
      if Structural(found.fieldType) || previous.fieldType == WordFieldType.Rows then found.fieldType
      else previous.fieldType
    previous.copy(
      fieldType = fieldType,
      options =
        if fieldType != WordFieldType.Choice then Nil
        else if found.options.nonEmpty then found.options
        else previous.options,
      columns = if fieldType == WordFieldType.Rows then merge(found.columns, previous.columns) else Nil,
      warning = found.warning
    )

  /** A definition edited by a designer applied to the field `current` read from the file (DMS-122): label,
    * type, required, source and options come from `edited`, the key, the columns' keys and the warning from
    * the file. A repeating section stays a list of rows, other fields cannot become one; a choice needs
    * options.
    */
  def edit(current: WordField, edited: WordField): Either[String, WordField] =
    val rows = current.fieldType == WordFieldType.Rows
    if edited.label.trim.isEmpty then Left(s"${current.key}: the label must not be empty")
    else if rows && edited.fieldType != WordFieldType.Rows then
      Left(s"${current.key}: a repeating section is a list of rows")
    else if !rows && edited.fieldType == WordFieldType.Rows then
      Left(s"${current.key}: only a repeating section of the Word file is a list of rows")
    else
      for
        options <- optionsOf(current.key, edited)
        columns <-
          if !rows then Right(Nil)
          else if edited.columns.map(_.key).sorted != current.columns.map(_.key).sorted then
            Left(
              s"${current.key}: the columns are those of the Word file: ${current.columns.map(_.key).mkString(", ")}"
            )
          else
            current.columns.foldRight[Either[String, List[WordField]]](Right(Nil)) { (column, acc) =>
              for
                rest   <- acc
                change <- edited.columns
                  .find(_.key == column.key)
                  .toRight(s"${current.key}.${column.key}: missing")
                _ <- Either.cond(
                  ColumnTypes(change.fieldType),
                  (),
                  s"${current.key}.${column.key}: a column is text, a date, a number, a check box or a choice"
                )
                _ <- Either.cond(
                  change.label.trim.nonEmpty,
                  (),
                  s"${current.key}.${column.key}: the label must not be empty"
                )
                columnOptions <- optionsOf(s"${current.key}.${column.key}", change)
              yield column.copy(
                label = change.label.trim,
                fieldType = change.fieldType,
                required = change.required,
                options = columnOptions
              ) :: rest
            }
      yield current.copy(
        label = edited.label.trim,
        fieldType = edited.fieldType,
        required = edited.required,
        source = if rows then WordFieldSource.Manual else edited.source,
        options = options,
        columns = columns
      )

  private def optionsOf(key: String, edited: WordField): Either[String, List[String]] =
    val cleaned = edited.options.map(_.trim).filter(_.nonEmpty).distinct
    if edited.fieldType != WordFieldType.Choice then Right(Nil)
    else if cleaned.isEmpty then Left(s"$key: a choice needs at least one option")
    else if cleaned.exists(_.length > 500) then Left(s"$key: options have at most 500 characters")
    else Right(cleaned)

  /** Why a value entered for a manual field is not acceptable, if it is not. Rows are checked by
    * [[rowProblems]].
    */
  def problem(field: WordField, value: String): Option[String] =
    val v = value.trim
    if field.fieldType == WordFieldType.Rows then None
    else if v.length > 4000 then Some(s"${field.label}: at most 4000 characters")
    else
      field.fieldType match
        case WordFieldType.Text => None
        case WordFieldType.Date =>
          Option.when(v.nonEmpty && Try(LocalDate.parse(v)).isFailure)(s"${field.label}: a date (yyyy-MM-dd)")
        case WordFieldType.Number =>
          Option.when(v.nonEmpty && Try(BigDecimal(v)).isFailure)(s"${field.label}: a number")
        case WordFieldType.Checkbox =>
          Option.when(v.nonEmpty && v != Checked && v != Unchecked)(s"${field.label}: true or false")
        case WordFieldType.Choice =>
          Option.when(v.nonEmpty && field.options.nonEmpty && !field.options.contains(v))(
            s"${field.label}: one of ${field.options.mkString(", ")}"
          )
        case WordFieldType.Rows => None

  /** Problems of the rows of a [[WordFieldType.Rows]] field: too many rows, unknown columns, invalid values
    * and – with `complete` – missing values of required columns (rows counted from 1).
    */
  def rowProblems(field: WordField, rows: Rows, complete: Boolean): List[String] =
    val byKey = field.columns.map(c => c.key -> c).toMap
    val many  = Option.when(rows.size > MaxRows)(s"${field.label}: at most $MaxRows rows").toList
    many ++ rows.zipWithIndex.flatMap { (row, i) =>
      val at      = s"${field.label}, row ${i + 1}"
      val unknown = row.keys.toList.sorted.filterNot(byKey.contains).map(k => s"$at: $k is not a column")
      val invalid = field.columns.flatMap(c => row.get(c.key).flatMap(problem(c, _)).map(p => s"$at: $p"))
      val missing =
        if complete then
          field.columns
            .filter(c => c.required && row.get(c.key).forall(_.trim.isEmpty))
            .map(c => s"$at: ${c.label}: required")
        else Nil
      unknown ++ invalid ++ missing
    }

  /** Problems of manual values: unknown keys, invalid values and – with `complete` – missing required ones.
    * `rows` reads the value of a [[WordFieldType.Rows]] field (a JSON array, read where JSON is available).
    */
  def check(
      fields: List[WordField],
      values: Map[String, String],
      complete: Boolean,
      rows: String => Either[String, Rows]
  ): List[String] =
    val manual  = fields.filter(_.source == WordFieldSource.Manual)
    val byKey   = manual.map(f => f.key -> f).toMap
    val unknown = values.keys.toList.sorted.filterNot(byKey.contains).map(k => s"$k: not a manual field")
    val invalid = manual.flatMap { f =>
      values.get(f.key).toList.flatMap { v =>
        if f.fieldType == WordFieldType.Rows then
          rows(v).fold(m => List(s"${f.label}: $m"), rowProblems(f, _, complete))
        else problem(f, v).toList
      }
    }
    val missing =
      if complete then
        manual
          .filter(f => f.required && values.get(f.key).forall(_.trim.isEmpty))
          .map(f => s"${f.label}: required")
      else Nil
    unknown ++ invalid ++ missing
