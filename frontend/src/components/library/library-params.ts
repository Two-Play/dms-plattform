import { LIBRARY_SORTS, type LibraryParams, type LibrarySort } from '@/api/library'

/**
 * The state of the library page, kept in the URL so that a filtered view can be bookmarked and
 * shared (DMS-119). Multiple values of a filter are comma-separated, e.g. `?class=policy,directive`.
 */
export interface LibraryPageSearch {
  q?: string
  /** Class keys, comma-separated. */
  class?: string
  /** Issuing units, comma-separated. */
  unit?: string
  tag?: string
  confidentiality?: string
  /** YYYY-MM-DD: "new" and "changed since" this date. */
  since?: string
  changed?: boolean
  pending?: boolean
  sort?: LibrarySort
  order?: 'asc' | 'desc'
  /** 1-based page. */
  page?: number
  /** Cards (default) or a compact list. */
  view?: 'cards' | 'list'
}

export type LibraryFilterKey = 'class' | 'unit' | 'tag' | 'confidentiality'

const DATE = /^\d{4}-\d{2}-\d{2}$/

function text(value: unknown): string | undefined {
  if (typeof value === 'number') return String(value)
  if (typeof value !== 'string') return undefined
  const trimmed = value.trim()
  return trimmed === '' ? undefined : trimmed
}

/** A comma-separated list without blanks and duplicates, or nothing. */
function csv(value: unknown): string | undefined {
  const values = splitValues(text(value))
  return values.length > 0 ? values.join(',') : undefined
}

function flag(value: unknown): boolean | undefined {
  return value === true || value === 'true' ? true : undefined
}

/** The values of a comma-separated filter. */
export function splitValues(value: string | undefined): string[] {
  if (!value) return []
  return [
    ...new Set(
      value
        .split(',')
        .map((v) => v.trim())
        .filter(Boolean),
    ),
  ]
}

/** `values` with `value` added or, if present, removed; `undefined` when empty. */
export function toggleValue(current: string | undefined, value: string): string | undefined {
  const values = splitValues(current)
  const next = values.includes(value) ? values.filter((v) => v !== value) : [...values, value]
  return next.length > 0 ? next.join(',') : undefined
}

/** Reads the search parameters of `/library`, dropping what is missing or malformed. */
export function parseLibraryPageSearch(raw: Record<string, unknown>): LibraryPageSearch {
  const page = Number(raw.page)
  const since = text(raw.since)
  const sort = text(raw.sort)
  const search: LibraryPageSearch = {
    q: text(raw.q),
    class: csv(raw.class),
    unit: csv(raw.unit),
    tag: csv(raw.tag),
    confidentiality: csv(raw.confidentiality),
    since: since && DATE.test(since) ? since : undefined,
    changed: flag(raw.changed),
    pending: flag(raw.pending),
    sort:
      sort && (LIBRARY_SORTS as readonly string[]).includes(sort)
        ? (sort as LibrarySort)
        : undefined,
    order: raw.order === 'asc' || raw.order === 'desc' ? raw.order : undefined,
    page: Number.isInteger(page) && page > 1 ? page : undefined,
    view: raw.view === 'list' ? 'list' : undefined,
  }
  return Object.fromEntries(Object.entries(search).filter(([, value]) => value !== undefined))
}

/** The API parameters of a page state. */
export function libraryParams(search: LibraryPageSearch, size: number): LibraryParams {
  return {
    q: search.q,
    class: splitValues(search.class),
    unit: splitValues(search.unit),
    tag: splitValues(search.tag),
    confidentiality: splitValues(search.confidentiality),
    since: search.since,
    changed: search.changed,
    pending: search.pending,
    sort: search.sort,
    order: search.order,
    page: search.page,
    size,
  }
}

/** Whether any filter narrows the list (sorting, page and view do not). */
export function hasFilters(search: LibraryPageSearch): boolean {
  return Boolean(
    search.q ||
    search.class ||
    search.unit ||
    search.tag ||
    search.confidentiality ||
    search.changed ||
    search.pending,
  )
}
