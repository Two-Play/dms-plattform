import { getLocale, type Locale } from '@/i18n'

/**
 * Numbers people type and read in form fields (DMS-165). The decimal separator of the language is the only
 * decimal separator and the other sign only separates complete groups of three, so `1.250` is 1250 in German
 * and 1.25 in English. What fits neither is refused: reading it either way could store a wrong number.
 */

const separators: Record<Locale, { decimal: string; group: string }> = {
  de: { decimal: ',', group: '.' },
  en: { decimal: '.', group: ',' },
}

export type ParsedNumber =
  | {
      kind: 'number'
      value: number
      /** The digits as stored and as CEL writes them: no groups, a point before the decimals. */
      canonical: string
      decimals: number
    }
  /** Digits and separators that are no number in this language, e.g. German `1.25`. */
  | { kind: 'ambiguous' }
  | { kind: 'invalid' }

const escaped = (sign: string) => (sign === '.' ? String.raw`\.` : sign)

function patterns(locale: Locale): { plain: RegExp; grouped: RegExp } {
  const decimal = escaped(separators[locale].decimal)
  const group = escaped(separators[locale].group)
  const fraction = String.raw`(?:${decimal}(\d+))?`
  return {
    plain: new RegExp(String.raw`^(-?\d+)${fraction}$`),
    // A number does not start with a zero group: `0.250` is a decimal typed the other way round.
    grouped: new RegExp(String.raw`^(-?[1-9]\d{0,2}(?:${group}\d{3})+)${fraction}$`),
  }
}

const compiled: Record<Locale, ReturnType<typeof patterns>> = {
  de: patterns('de'),
  en: patterns('en'),
}

const NUMBER_LIKE = /^-?[\d.,]+$/

/** Reads a number as written in `locale`. */
export function parseNumberText(text: string, locale: Locale = getLocale()): ParsedNumber {
  const trimmed = text.trim()
  const { plain, grouped } = compiled[locale]
  const match = plain.exec(trimmed) ?? grouped.exec(trimmed)
  if (!match) return { kind: NUMBER_LIKE.test(trimmed) ? 'ambiguous' : 'invalid' }
  const whole = (match[1] ?? '').replaceAll(separators[locale].group, '')
  const fraction = match[2]
  const canonical = fraction === undefined ? whole : `${whole}.${fraction}`
  return {
    kind: 'number',
    value: Number(canonical),
    canonical,
    decimals: fraction?.length ?? 0,
  }
}

/** The digits of a stored number as written in `locale`; its digits and decimals stay as they are. */
export function canonicalToText(canonical: string, locale: Locale = getLocale()): string {
  const [whole = '', fraction] = canonical.split('.')
  const { decimal, group } = separators[locale]
  const grouped = whole.replace(/\B(?=(\d{3})+$)/g, group)
  return fraction === undefined ? grouped : `${grouped}${decimal}${fraction}`
}

/** Whether `text` is a stored number: digits with an optional decimal point. */
export function isCanonicalNumber(text: string): boolean {
  return /^-?\d+(\.\d+)?$/.test(text)
}

/** A number without exponent, as `String` writes the shortest digits that read back as the same number. */
function plainDigits(value: number): string {
  const text = String(value)
  return text.includes('e')
    ? value.toLocaleString('en-US', { useGrouping: false, maximumFractionDigits: 20 })
    : text
}

/** A stored number as text for a form field in `locale`; it reads back as the same number. */
export function numberToText(value: number, locale: Locale = getLocale()): string {
  return canonicalToText(plainDigits(value), locale)
}
