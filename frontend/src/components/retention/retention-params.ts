/**
 * The state of the retention page, kept in the URL so that a view (e.g. the open proposals) can be
 * linked and bookmarked.
 */
export interface RetentionPageSearch {
  /** The section shown; default the destruction list. */
  tab?: 'proposals' | 'rules' | 'cleanup'
  /** Proposals of this status; default the open ones. */
  status?: 'approved' | 'rejected' | 'all'
  /** Filters of the destruction list. */
  class?: string
  rule?: string
  q?: string
}

const TABS = ['proposals', 'rules', 'cleanup'] as const
const STATUSES = ['approved', 'rejected', 'all'] as const

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : undefined
}

function oneOf<T extends string>(values: readonly T[], value: unknown): T | undefined {
  return values.find((v) => v === value)
}

/** Reads the search parameters of `/retention`, dropping what is missing or malformed. */
export function parseRetentionPageSearch(raw: Record<string, unknown>): RetentionPageSearch {
  const search: RetentionPageSearch = {
    tab: oneOf(TABS, raw.tab),
    status: oneOf(STATUSES, raw.status),
    class: text(raw.class),
    rule: text(raw.rule),
    q: text(raw.q),
  }
  return Object.fromEntries(Object.entries(search).filter(([, v]) => v !== undefined))
}
