import { intlLocale } from '@/i18n'

/** Date and number formats of the active language (DMS-110); formatters are cached per language. */

const cache = new Map<string, Intl.DateTimeFormat>()

function dateTimeFormat(key: string, options: Intl.DateTimeFormatOptions): Intl.DateTimeFormat {
  const locale = intlLocale()
  const id = `${locale}|${key}`
  let f = cache.get(id)
  if (!f) {
    f = new Intl.DateTimeFormat(locale, options)
    cache.set(id, f)
  }
  return f
}

type DateInput = Date | string | number

function toDate(value: DateInput): Date {
  return value instanceof Date ? value : new Date(value)
}

/** Date and time, e.g. "12.03.2026, 14:05" or "12 Mar 2026, 14:05". */
export function formatDateTime(value: DateInput): string {
  return dateTimeFormat('dateTime', { dateStyle: 'medium', timeStyle: 'short' }).format(
    toDate(value),
  )
}

/** Date of an instant in the browser's time zone, e.g. "12.03.2026". */
export function formatDate(value: DateInput): string {
  return dateTimeFormat('date', { dateStyle: 'medium' }).format(toDate(value))
}

/** A calendar date without time zone (`YYYY-MM-DD`), shown as it is. */
export function formatPlainDate(value: string): string {
  return dateTimeFormat('plainDate', { dateStyle: 'medium', timeZone: 'UTC' }).format(
    new Date(`${value.slice(0, 10)}T00:00:00Z`),
  )
}

/** Weekday and date, e.g. "Donnerstag, 29. Oktober". */
export function formatLongDate(value: DateInput): string {
  return dateTimeFormat('long', { weekday: 'long', day: 'numeric', month: 'long' }).format(
    toDate(value),
  )
}

export function formatNumber(value: number, options?: Intl.NumberFormatOptions): string {
  return new Intl.NumberFormat(intlLocale(), options).format(value)
}

/** A share from 0 to 1 as percentage, e.g. "87 %" or "87%". */
export function formatPercent(value: number, maximumFractionDigits = 0): string {
  return formatNumber(value, { style: 'percent', maximumFractionDigits })
}
