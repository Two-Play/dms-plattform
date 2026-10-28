/** The state of the search page, kept in the URL so that searches can be bookmarked and shared. */
export interface SearchPageSearch {
  /** Search text. */
  q?: string
  /** Class key filter (facet "class"); also limits the "valid on" list. */
  class?: string
  status?: string
  lang?: string
  /** 1-based result page. */
  page?: number
  /** YYYY-MM-DD: switches to the versions valid on that date. */
  validOn?: string
}

const DATE = /^\d{4}-\d{2}-\d{2}$/

function text(value: unknown): string | undefined {
  if (typeof value === 'number') return String(value)
  if (typeof value !== 'string') return undefined
  const trimmed = value.trim()
  return trimmed === '' ? undefined : trimmed
}

/** Reads the search parameters of `/search`, dropping what is missing or malformed. */
export function parseSearchPageSearch(raw: Record<string, unknown>): SearchPageSearch {
  const page = Number(raw.page)
  const validOn = text(raw.validOn)
  const search: SearchPageSearch = {
    q: text(raw.q),
    class: text(raw.class),
    status: text(raw.status),
    lang: text(raw.lang),
    page: Number.isInteger(page) && page > 1 ? page : undefined,
    validOn: validOn && DATE.test(validOn) ? validOn : undefined,
  }
  return Object.fromEntries(Object.entries(search).filter(([, value]) => value !== undefined))
}
