/**
 * The state of the legal holds page, kept in the URL so that a hold can be linked (e.g. from the
 * legal hold marker of a document) and bookmarked.
 */
export interface LegalHoldsPageSearch {
  /** Which holds the list shows; default the active ones. */
  status?: 'released' | 'all'
  /** Id of the hold shown in detail. */
  hold?: string
}

/** Reads the search parameters of `/legal-holds`, dropping what is missing or malformed. */
export function parseLegalHoldsPageSearch(raw: Record<string, unknown>): LegalHoldsPageSearch {
  const status = raw.status === 'released' || raw.status === 'all' ? raw.status : undefined
  const hold = typeof raw.hold === 'string' && raw.hold.trim() !== '' ? raw.hold.trim() : undefined
  const search: LegalHoldsPageSearch = { status, hold }
  return Object.fromEntries(Object.entries(search).filter(([, v]) => v !== undefined))
}
