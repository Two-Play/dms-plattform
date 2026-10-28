/**
 * The state of the compliance cockpit, kept in the URL so that a drill-down can be bookmarked and
 * shared. `unit` and `owner` may be the empty string: versions without unit or owner.
 */
export interface CompliancePageSearch {
  /** Which list is shown below the key figures. */
  view?: 'reviews' | 'acknowledgements'
  unit?: string
  owner?: string
  /** Document id: the acknowledgement rates of one document. */
  document?: string
}

function value(raw: unknown): string | undefined {
  if (typeof raw === 'number') return String(raw)
  return typeof raw === 'string' ? raw.trim() : undefined
}

/** Reads the search parameters of `/compliance`, dropping what is missing or malformed. */
export function parseCompliancePageSearch(raw: Record<string, unknown>): CompliancePageSearch {
  const view = raw.view === 'reviews' || raw.view === 'acknowledgements' ? raw.view : undefined
  const document = value(raw.document)
  const search: CompliancePageSearch = {
    view,
    unit: view ? value(raw.unit) : undefined,
    owner: view === 'reviews' ? value(raw.owner) : undefined,
    document: view === 'acknowledgements' && document ? document : undefined,
  }
  return Object.fromEntries(Object.entries(search).filter(([, v]) => v !== undefined))
}

/** How a unit or owner group is shown; versions without one form a group of their own. */
export function groupLabel(value: string | undefined, none: string): string {
  return value === undefined || value === '' ? none : value
}
