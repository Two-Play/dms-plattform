/** The two compared versions, kept in the URL so that a comparison can be bookmarked and shared. */
export interface CompareSearch {
  from?: string
  to?: string
}

function id(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : undefined
}

/** Reads the search parameters of `/documents/$documentId/compare`, dropping what is missing. */
export function parseCompareSearch(raw: Record<string, unknown>): CompareSearch {
  const search: CompareSearch = { from: id(raw.from), to: id(raw.to) }
  return Object.fromEntries(Object.entries(search).filter(([, value]) => value !== undefined))
}
