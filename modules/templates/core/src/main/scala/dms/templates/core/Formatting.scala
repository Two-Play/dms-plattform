package dms.templates.core

import dms.templates.*

import java.text.{DecimalFormat, DecimalFormatSymbols}
import java.time.chrono.IsoChronology
import java.time.format.{DateTimeFormatter, DateTimeFormatterBuilder, FormatStyle}
import java.util.{Currency, Locale}
import scala.util.Try

/** Formats values for placeholders according to the document language (business concept 4.2).
  *
  * Formats: `plain` (default, by value type), `number`, `amount`, `date:long`, `date:short`. A format that
  * does not fit the value falls back to `plain`.
  */
object Formatting:

  def format(
      value: FieldValue,
      format: Option[String],
      language: Language,
      valueType: Option[ValueType]
  ): String =
    (format.getOrElse("plain"), value) match
      case ("amount", FieldValue.Amount(v, c)) => amount(v, c, language)
      case ("number", FieldValue.Number(v))    => number(v, language)
      case ("date:long", FieldValue.Date(d))   =>
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(language.locale).format(d)
      case ("date:short", FieldValue.Date(d)) => shortDate(language).format(d)
      case _                                  => plain(value, language, valueType)

  def plain(value: FieldValue, language: Language, valueType: Option[ValueType]): String = value match
    case FieldValue.Text(v) =>
      valueType match
        case Some(ValueType.Selection(choices)) => choices.find(_.code == v).fold(v)(_.label)
        case _                                  => v
    case FieldValue.Number(v)    => number(v, language)
    case FieldValue.Amount(v, c) => amount(v, c, language)
    case FieldValue.Date(d)      => shortDate(language).format(d)
    case FieldValue.YesNo(v)     =>
      language match
        case Language.De => if v then "ja" else "nein"
        case Language.En => if v then "yes" else "no"
    case FieldValue.ListOf(values) =>
      val element = valueType.collect { case ValueType.ListOf(e) => e }
      values.map(plain(_, language, element)).mkString(", ")
    case FieldValue.Record(fields) =>
      fields.toList.sortBy(_._1).map((k, v) => s"$k: ${plain(v, language, None)}").mkString(", ")

  private def number(v: BigDecimal, language: Language): String =
    val f = DecimalFormat("#,##0.##########", DecimalFormatSymbols.getInstance(language.locale))
    f.format(v.bigDecimal)

  /** German: `1.234,50 €`; English: `€1,234.50`. Codes without a symbol are written out: `CHF 1,234.50`. */
  private def amount(v: BigDecimal, currency: String, language: Language): String =
    Try(Currency.getInstance(currency)).toOption match
      case Some(c) =>
        val digits  = c.getDefaultFractionDigits.max(0)
        val pattern = if digits == 0 then "#,##0" else "#,##0." + "0" * digits
        val n = DecimalFormat(pattern, DecimalFormatSymbols.getInstance(language.locale)).format(v.bigDecimal)
        val symbol = c.getSymbol(language match
          case Language.De => Locale.GERMANY
          case Language.En => Locale.US)
        language match
          case Language.De                              => s"$n $symbol"
          case Language.En if symbol.exists(_.isLetter) => s"$symbol $n"
          case Language.En                              => s"$symbol$n"
      case None => s"${number(v, language)} $currency".trim

  /** The language's short date with a four-digit year, e.g. `28.10.2026` or `10/28/2026`. */
  private def shortDate(language: Language): DateTimeFormatter =
    val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
      FormatStyle.SHORT,
      null,
      IsoChronology.INSTANCE,
      language.locale
    )
    val fourDigits = if pattern.contains("yyyy") then pattern else pattern.replace("yy", "yyyy")
    DateTimeFormatter.ofPattern(fourDigits, language.locale)
