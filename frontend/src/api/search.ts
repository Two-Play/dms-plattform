import { queryOptions } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { ApiRequestError, requestError } from './errors'
import type { components } from './schema'

/**
 * Full-text search (DMS-51, DMS-52) and the "valid on" history (DMS-53).
 *
 * `GET /api/v1/search?q=&class=&status=&lang=&page=1&size=20`, types from the generated schema.
 */

/** One hit. `snippet` is plain text whose matches are wrapped in `<mark>…</mark>`, nothing else. */
export type SearchHit = Omit<components['schemas']['SearchHitView'], 'classPath'> & {
  classPath: string[]
}

export type FacetValue = components['schemas']['FacetCountView']

export interface SearchFacets {
  class: FacetValue[]
  status: FacetValue[]
  language: FacetValue[]
}

export interface SearchResult {
  total: number
  page: number
  size: number
  hits: SearchHit[]
  facets: SearchFacets
}

export interface SearchParams {
  q: string
  class?: string
  status?: string
  lang?: string
  /** 1-based. */
  page?: number
  size?: number
}

export const SEARCH_PAGE_SIZE = 20

/** One value as a list parameter, or none. */
function listParam(value: string | undefined): string[] | undefined {
  return value ? [value] : undefined
}

/** Query parameters without empty values. */
function definedQuery(params: Record<string, string | number | undefined>) {
  return Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== undefined && value !== ''),
  )
}

/**
 * Full-text search (GET /api/v1/search). Throws {@link ApiRequestError}; status 404 means the
 * backend does not offer search yet.
 */
export async function searchDocuments(
  client: ApiClient,
  params: SearchParams,
): Promise<SearchResult> {
  const { data, error, response } = await client.GET('/api/v1/search', {
    params: {
      query: {
        q: params.q || undefined,
        class: listParam(params.class),
        status: listParam(params.status),
        lang: listParam(params.lang),
        page: params.page ?? 1,
        size: params.size ?? SEARCH_PAGE_SIZE,
      },
    },
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Searching failed with HTTP ${response.status}`)
  }
  return {
    total: data.total,
    page: data.page,
    size: data.size,
    hits: (data.hits ?? []).map((hit) => ({ ...hit, classPath: hit.classPath ?? [] })),
    facets: {
      class: data.facets.class ?? [],
      status: data.facets.status ?? [],
      language: data.facets.language ?? [],
    },
  }
}

/** Search results, or `null` when the backend does not offer search yet (404). */
export function searchQuery(client: ApiClient, params: SearchParams) {
  return queryOptions({
    queryKey: ['search', params],
    queryFn: async (): Promise<SearchResult | null> => {
      try {
        return await searchDocuments(client, params)
      } catch (e) {
        if (e instanceof ApiRequestError && e.status === 404) return null
        throw e
      }
    },
    retry: false,
  })
}

/** The versions in force at a moment, per document and validity scope. */
export type ValidAtResult = components['schemas']['ValidAtView']
export type ValidVersion = components['schemas']['ValidVersionView']

export interface ValidAtParams {
  /** YYYY-MM-DD, read in the tenant's time zone. */
  date: string
  class?: string
}

/** Versions in force on a date (GET /api/v1/documents/valid-at). */
export function validAtQuery(client: ApiClient, params: ValidAtParams) {
  return queryOptions({
    queryKey: ['documents', 'valid-at', params],
    queryFn: async (): Promise<ValidAtResult> => {
      const { data, error, response } = await client.GET('/api/v1/documents/valid-at', {
        params: { query: definedQuery({ date: params.date, class: params.class }) },
      })
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the versions valid on ${params.date} failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    retry: false,
  })
}

/** The file name from `Content-Disposition: attachment; filename="…"`. */
export function attachmentName(header: string | null, fallback: string): string {
  const match = header ? /filename="?([^";]+)"?/i.exec(header) : null
  return match?.[1] ?? fallback
}

/**
 * Downloads the "valid on" list as CSV (GET /api/v1/documents/valid-at/export.csv). The request
 * needs the access token, so the file is fetched and handed to the browser as a blob.
 */
export async function downloadValidAtCsv(client: ApiClient, params: ValidAtParams): Promise<void> {
  const { data, error, response } = await client.GET('/api/v1/documents/valid-at/export.csv', {
    params: { query: definedQuery({ date: params.date, class: params.class }) },
    parseAs: 'blob',
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `The export failed with HTTP ${response.status}`)
  }
  const url = URL.createObjectURL(data)
  const link = document.createElement('a')
  link.href = url
  link.download = attachmentName(
    response.headers.get('Content-Disposition'),
    `valid-at-${params.date}.csv`,
  )
  document.body.append(link)
  link.click()
  link.remove()
  // Revoked after the click has been handled; revoking at once can cancel the download.
  setTimeout(() => {
    URL.revokeObjectURL(url)
  }, 0)
}
