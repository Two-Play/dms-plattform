import { keepPreviousData, queryOptions } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { requestError } from './errors'
import type { components } from './schema'

/**
 * The library (DMS-119, ADR 0043): the versions valid now that the caller may read, with filters,
 * facets, "new"/"changed since" markers and open acknowledgements.
 *
 * `GET /api/v1/library?q=&class=&unit=&tag=&confidentiality=&since=&changed=&pending=&sort=&order=&page=&size=`
 */

export type LibraryItem = Omit<components['schemas']['LibraryItemView'], 'classPath' | 'tags'> & {
  classPath: string[]
  tags: string[]
}

export type LibraryFacetValue = components['schemas']['LibraryFacetView']

export interface LibraryFacets {
  class: LibraryFacetValue[]
  unit: LibraryFacetValue[]
  tag: LibraryFacetValue[]
  confidentiality: LibraryFacetValue[]
  /** Documents new or changed since the reference date. */
  changed: number
  /** Documents the caller has yet to acknowledge. */
  pending: number
}

export interface LibraryResult {
  total: number
  page: number
  size: number
  sort: LibrarySort
  order: 'asc' | 'desc'
  /** YYYY-MM-DD: the reference date of `isNew` and `changed`. */
  since: string
  timeZone: string
  items: LibraryItem[]
  facets: LibraryFacets
}

export const LIBRARY_SORTS = ['title', 'number', 'validFrom', 'lastChanged'] as const
export type LibrarySort = (typeof LIBRARY_SORTS)[number]

export const LIBRARY_PAGE_SIZE = 24

export interface LibraryParams {
  q?: string
  class?: string[]
  unit?: string[]
  tag?: string[]
  confidentiality?: string[]
  /** YYYY-MM-DD; default (backend): 30 days ago. */
  since?: string
  changed?: boolean
  pending?: boolean
  sort?: LibrarySort
  order?: 'asc' | 'desc'
  /** 1-based. */
  page?: number
  size?: number
}

function list(values: string[] | undefined): string[] | undefined {
  return values && values.length > 0 ? values : undefined
}

function isSort(value: string): value is LibrarySort {
  return (LIBRARY_SORTS as readonly string[]).includes(value)
}

/** The library page (GET /api/v1/library). Throws {@link ApiRequestError}. */
export async function fetchLibrary(
  client: ApiClient,
  params: LibraryParams,
): Promise<LibraryResult> {
  const { data, error, response } = await client.GET('/api/v1/library', {
    params: {
      query: {
        q: params.q || undefined,
        class: list(params.class),
        unit: list(params.unit),
        tag: list(params.tag),
        confidentiality: list(params.confidentiality),
        since: params.since,
        changed: params.changed || undefined,
        pending: params.pending || undefined,
        sort: params.sort,
        order: params.order,
        page: params.page ?? 1,
        size: params.size ?? LIBRARY_PAGE_SIZE,
      },
    },
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Loading the library failed with HTTP ${response.status}`)
  }
  return {
    total: data.total,
    page: data.page,
    size: data.size,
    sort: isSort(data.sort) ? data.sort : 'title',
    order: data.order === 'desc' ? 'desc' : 'asc',
    since: data.since,
    timeZone: data.timeZone,
    items: (data.items ?? []).map((item) => ({
      ...item,
      classPath: item.classPath ?? [],
      tags: item.tags ?? [],
    })),
    facets: {
      class: data.facets.class ?? [],
      unit: data.facets.unit ?? [],
      tag: data.facets.tag ?? [],
      confidentiality: data.facets.confidentiality ?? [],
      changed: data.facets.changed,
      pending: data.facets.pending,
    },
  }
}

/** The library; keeps the previous page on screen while the next filter combination loads. */
export function libraryQuery(client: ApiClient, params: LibraryParams) {
  return queryOptions({
    queryKey: ['library', params],
    queryFn: () => fetchLibrary(client, params),
    placeholderData: keepPreviousData,
  })
}
