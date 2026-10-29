import { useSyncExternalStore } from 'react'
import {
  format,
  lookup,
  type MessageAt,
  type MessageKeys,
  type ParamArgs,
  type ParamValue,
} from '@/i18n/core'
import { de } from '@/i18n/locales/de'
import { en, type Messages } from '@/i18n/locales/en'

export { plural } from '@/i18n/core'

/** Languages of the UI; German is the default (DMS-110). */
export const LOCALES = ['de', 'en'] as const
export type Locale = (typeof LOCALES)[number]

export type MessageKey = MessageKeys<Messages>

const catalogs: Record<Locale, unknown> = { de, en }

/** Tags for `Intl`: German as used in Germany, British English (day before month, 24 h). */
const intlTags: Record<Locale, string> = { de: 'de-DE', en: 'en-GB' }

const STORAGE_KEY = 'dms-locale'

function isLocale(value: unknown): value is Locale {
  return typeof value === 'string' && (LOCALES as readonly string[]).includes(value)
}

function readStored(key: string): Locale | undefined {
  try {
    const value = localStorage.getItem(key)
    return isLocale(value) ? value : undefined
  } catch {
    return undefined
  }
}

function writeStored(key: string, locale: Locale): void {
  try {
    localStorage.setItem(key, locale)
  } catch {
    // Storage can be unavailable (private mode); the choice then lasts for this page only.
  }
}

/** The browser's preferred language if it is one of ours, otherwise German. */
export function browserLocale(languages: readonly string[] = navigator.languages): Locale {
  for (const language of languages) {
    const primary = language.toLowerCase().split('-')[0]
    if (isLocale(primary)) return primary
  }
  return 'de'
}

let current: Locale = readStored(STORAGE_KEY) ?? browserLocale()
let userKey: string | undefined
const listeners = new Set<() => void>()

function apply(locale: Locale): void {
  document.documentElement.lang = locale
  if (locale === current) return
  current = locale
  for (const listener of listeners) listener()
}

apply(current)

export function getLocale(): Locale {
  return current
}

/** The `Intl` tag of the active language, for `Intl.*Format` and `localeCompare`. */
export function intlLocale(locale: Locale = current): string {
  return intlTags[locale]
}

/** Switches the language and remembers it in this browser, also for the signed-in user. */
export function setLocale(locale: Locale): void {
  writeStored(STORAGE_KEY, locale)
  if (userKey) writeStored(userKey, locale)
  apply(locale)
}

/**
 * Called when a user signs in: applies the language this user chose before in this browser, if any.
 * Several people sharing a browser each keep their own language.
 */
export function applyUserLocale(userId: string | undefined): void {
  userKey = userId ? `${STORAGE_KEY}:${userId}` : undefined
  const stored = userKey ? readStored(userKey) : undefined
  if (stored && stored !== current) {
    writeStored(STORAGE_KEY, stored)
    apply(stored)
  }
}

export function subscribeLocale(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/**
 * The message for `key` in the active language, with its parameters filled in. Usable in components
 * and outside them (toasts, error texts): `App` renders the routes again from scratch when the
 * language changes, so no component keeps texts of the previous language.
 */
export function t<K extends MessageKey>(
  key: K,
  ...args: ParamArgs<MessageAt<Messages, K>>
): string {
  const params = args[0] as Readonly<Record<string, ParamValue>> | undefined
  const message = lookup(catalogs[current], key) ?? lookup(en, key)
  if (message === undefined) return key
  return format(message, intlTags[current], params)
}

/** The message for a key built at runtime, e.g. from a status the API sends; undefined if unknown. */
export function tOptional(key: string): string | undefined {
  const message = lookup(catalogs[current], key) ?? lookup(en, key)
  return message === undefined ? undefined : format(message, intlTags[current], undefined)
}

/** The active language; re-renders the component when it changes. */
export function useLocale(): Locale {
  return useSyncExternalStore(subscribeLocale, getLocale, getLocale)
}
