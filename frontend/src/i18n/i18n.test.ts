import { describe, expect, it } from 'vitest'
import { format, lookup, plural, type PluralMessage } from '@/i18n/core'
import { formatDate, formatDateTime, formatNumber, formatPlainDate } from '@/i18n/format'
import { applyUserLocale, browserLocale, getLocale, setLocale, t, tOptional } from '@/i18n'
import { de } from '@/i18n/locales/de'
import { en } from '@/i18n/locales/en'

/** Every message of a catalog by dotted key. */
function leaves(node: unknown, prefix = ''): Map<string, string | PluralMessage> {
  const out = new Map<string, string | PluralMessage>()
  for (const [key, value] of Object.entries(node as Record<string, unknown>)) {
    const path = prefix ? `${prefix}.${key}` : key
    const leaf = lookup(node, key)
    if (leaf !== undefined) out.set(path, leaf)
    else for (const [k, v] of leaves(value, path)) out.set(k, v)
  }
  return out
}

function placeholders(message: string | PluralMessage): string[] {
  const texts = typeof message === 'string' ? [message] : Object.values(message)
  const names = texts.flatMap((text) => [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1] ?? ''))
  if (typeof message !== 'string') names.push('count')
  return [...new Set(names)].sort()
}

describe('message formatting', () => {
  it('fills placeholders and formats numbers for the language', () => {
    expect(format('Hello {name}', 'en-GB', { name: 'Ada' })).toBe('Hello Ada')
    expect(format('{n} items', 'de-DE', { n: 1234.5 })).toBe('1.234,5 items')
    expect(format('{n} items', 'en-GB', { n: 1234.5 })).toBe('1,234.5 items')
    expect(format('Keep {unknown}', 'en-GB', {})).toBe('Keep {unknown}')
  })

  it('picks the plural form by count, with an optional zero form', () => {
    const tasks = plural({ zero: 'No tasks', one: '{count} task', other: '{count} tasks' })
    expect(format(tasks, 'en-GB', { count: 0 })).toBe('No tasks')
    expect(format(tasks, 'en-GB', { count: 1 })).toBe('1 task')
    expect(format(tasks, 'en-GB', { count: 2 })).toBe('2 tasks')
  })

  it('translates keys in the active language and falls back to English for unknown runtime keys', () => {
    setLocale('de')
    expect(t('shell.signOut')).toBe('Abmelden')
    expect(t('shell.openTasks', { count: 1 })).toBe('1 offene Aufgabe')
    expect(t('shell.openTasks', { count: 3 })).toBe('3 offene Aufgaben')
    expect(tOptional('status.InReview')).toBe('In Prüfung')
    expect(tOptional('status.Unknown')).toBeUndefined()
    setLocale('en')
    expect(t('shell.signOut')).toBe('Sign out')
    expect(tOptional('status.InReview')).toBe('In review')
  })
})

describe('the German catalog', () => {
  const english = leaves(en)
  const german = leaves(de)

  it('has every English key, and no others', () => {
    expect([...german.keys()].sort()).toEqual([...english.keys()].sort())
  })

  it('uses the same placeholders as English and has no empty texts', () => {
    for (const [key, message] of english) {
      const translated = german.get(key)
      expect(translated, key).toBeDefined()
      if (translated === undefined) continue
      expect(typeof translated, key).toBe(typeof message)
      expect(placeholders(translated), key).toEqual(placeholders(message))
      const texts = typeof translated === 'string' ? [translated] : Object.values(translated)
      for (const text of texts) expect(text.trim(), key).not.toBe('')
    }
  })
})

describe('language choice', () => {
  it('defaults to the browser language if it is German or English, otherwise German', () => {
    expect(browserLocale(['de-AT', 'en'])).toBe('de')
    expect(browserLocale(['en-US'])).toBe('en')
    expect(browserLocale(['fr-FR', 'en-GB'])).toBe('en')
    expect(browserLocale(['fr-FR'])).toBe('de')
    expect(browserLocale([])).toBe('de')
  })

  it('sets the lang attribute and remembers the choice, also per signed-in user', () => {
    setLocale('de')
    expect(document.documentElement.lang).toBe('de')
    expect(localStorage.getItem('dms-locale')).toBe('de')

    applyUserLocale('user-1')
    setLocale('en')
    expect(localStorage.getItem('dms-locale:user-1')).toBe('en')

    // Someone else signs in on this browser and picks German; user 1 gets English back.
    applyUserLocale('user-2')
    setLocale('de')
    applyUserLocale('user-1')
    expect(getLocale()).toBe('en')
    expect(document.documentElement.lang).toBe('en')
    applyUserLocale(undefined)
  })
})

describe('date and number formats', () => {
  it('follow the active language', () => {
    setLocale('de')
    expect(formatPlainDate('2026-03-12')).toBe('12.03.2026')
    expect(formatNumber(1250.5)).toBe('1.250,5')
    expect(formatDateTime('2026-03-12T14:05:00')).toBe('12.03.2026, 14:05')
    setLocale('en')
    expect(formatPlainDate('2026-03-12')).toBe('12 Mar 2026')
    expect(formatNumber(1250.5)).toBe('1,250.5')
    expect(formatDate('2026-03-12T14:05:00')).toBe('12 Mar 2026')
  })
})
