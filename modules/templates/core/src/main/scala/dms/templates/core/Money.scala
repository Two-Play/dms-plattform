package dms.templates.core

import scala.math.BigDecimal.RoundingMode
import scala.util.Try

/** Exact amounts for computed fields (DMS-77, ADR 0020).
  *
  * CEL has no decimal type, so expressions calculate with doubles. Input amounts stay exact `BigDecimal`s; a
  * computed amount is converted back via the shortest decimal representation of the double
  * (`Double.toString`, so `0.1 + 0.2` becomes `0.30000000000000004` and `1.005` stays `1.005`) and then
  * rounded half up (commercial rounding) to the minor unit of its currency.
  */
object Money:

  /** Minor unit digits of an ISO 4217 currency, as printed by `Formatting`; 2 for unknown codes. */
  def fractionDigits(currency: String): Int =
    Try(java.util.Currency.getInstance(currency).getDefaultFractionDigits).toOption
      .map(_.max(0))
      .getOrElse(2)

  def round(amount: BigDecimal, currency: String): BigDecimal =
    amount.setScale(fractionDigits(currency), RoundingMode.HALF_UP)
