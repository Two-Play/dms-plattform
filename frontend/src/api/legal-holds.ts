import { queryOptions, type QueryClient } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { ApiRequestError, requestError } from './errors'
import type { Me } from './me'
import type { components } from './schema'

/**
 * Legal holds (US-08.1, DMS-56, DMS-106, ADR 0031 and 0047): records managers set a hold for a
 * matter, put documents under it (named, or the documents of a search they reviewed and confirm),
 * take single documents out of it and release it, each with a reason. All hold endpoints need a
 * records manager assignment for the whole tenant; documents additionally need the records
 * manager's scope. Everybody else only learns that a document is under legal hold, never which hold.
 */

export type LegalHold = components['schemas']['LegalHoldView']
export type HeldDocument = components['schemas']['HeldDocumentView']
export type LegalHoldRef = components['schemas']['LegalHoldRefView']
export type HoldSearchFilter = components['schemas']['HoldSearchFilter']
export type HoldSearchPreview = components['schemas']['HoldSearchPreviewView']
export type HoldCandidate = components['schemas']['HoldCandidateView']

export type LegalHoldStatus = 'active' | 'released' | 'all'

/** Most documents one request puts under a hold (`LegalHoldApi.MaxDocuments`). */
export const MAX_HOLD_DOCUMENTS = 1000

/**
 * Whether the user may manage legal holds: a records manager assignment for the whole tenant (no
 * organizational unit, no document class), as the backend requires for every hold endpoint.
 */
export function canManageLegalHolds(me: Me | undefined): boolean {
  return (me?.assignments ?? []).some(
    (a) => a.role === 'RecordsManager' && !a.organizationalUnit && !a.documentClass,
  )
}

/** The search of the search page as a hold filter, without confirmation. */
export interface HoldSearch {
  q?: string
  class?: string
  status?: string
  lang?: string
}

function filterOf(search: HoldSearch): HoldSearchFilter {
  const list = (value: string | undefined) => (value ? [value] : undefined)
  return {
    q: search.q?.trim() || undefined,
    class: list(search.class),
    status: list(search.status),
    lang: list(search.lang),
  }
}

/** The filter that confirms exactly the documents of a preview the records manager reviewed. */
export function confirmedFilter(search: HoldSearch, preview: HoldSearchPreview): HoldSearchFilter {
  return {
    ...filterOf(search),
    expectedCount: preview.total,
    expectedFingerprint: preview.fingerprint,
  }
}

export function legalHoldsQuery(client: ApiClient, status: LegalHoldStatus) {
  return queryOptions({
    queryKey: ['legal-holds', 'list', status],
    queryFn: async (): Promise<LegalHold[]> => {
      const { data, error, response } = await client.GET('/api/v1/legal-holds', {
        params: { query: { status } },
      })
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the legal holds failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

export function legalHoldQuery(client: ApiClient, id: string) {
  return queryOptions({
    queryKey: ['legal-holds', id],
    queryFn: async (): Promise<LegalHold> => {
      const { data, error, response } = await client.GET('/api/v1/legal-holds/{id}', {
        params: { path: { id } },
      })
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the legal hold failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    retry: false,
  })
}

/** The documents a search would put under a hold, with the fingerprint that confirms them. */
export async function previewHoldSearch(
  client: ApiClient,
  search: HoldSearch,
): Promise<HoldSearchPreview> {
  const { data, error, response } = await client.POST('/api/v1/legal-holds/search-preview', {
    body: filterOf(search),
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Checking the search failed with HTTP ${response.status}`)
  }
  return data
}

export interface NewLegalHold {
  name: string
  reason: string
  matterReference?: string
  documentIds?: string[]
  searchFilter?: HoldSearchFilter
}

export async function createLegalHold(client: ApiClient, hold: NewLegalHold): Promise<LegalHold> {
  const { data, error, response } = await client.POST('/api/v1/legal-holds', {
    body: {
      name: hold.name.trim(),
      reason: hold.reason.trim(),
      matterReference: hold.matterReference?.trim() || undefined,
      documentIds: hold.documentIds?.length ? hold.documentIds : undefined,
      searchFilter: hold.searchFilter,
    },
  })
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Setting the legal hold failed with HTTP ${response.status}`,
    )
  }
  return data
}

export async function addLegalHoldDocuments(
  client: ApiClient,
  id: string,
  request: { reason: string; documentIds?: string[]; searchFilter?: HoldSearchFilter },
): Promise<LegalHold> {
  const { data, error, response } = await client.POST('/api/v1/legal-holds/{id}/documents', {
    params: { path: { id } },
    body: {
      reason: request.reason.trim(),
      documentIds: request.documentIds?.length ? request.documentIds : undefined,
      searchFilter: request.searchFilter,
    },
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Adding the documents failed with HTTP ${response.status}`)
  }
  return data
}

export async function removeLegalHoldDocument(
  client: ApiClient,
  id: string,
  documentId: string,
  reason: string,
): Promise<LegalHold> {
  const { data, error, response } = await client.POST(
    '/api/v1/legal-holds/{id}/documents/{documentId}/remove',
    { params: { path: { id, documentId } }, body: { reason: reason.trim() } },
  )
  if (!response.ok || !data) {
    throw requestError(response, error, `Removing the document failed with HTTP ${response.status}`)
  }
  return data
}

export async function releaseLegalHold(
  client: ApiClient,
  id: string,
  reason: string,
): Promise<LegalHold> {
  const { data, error, response } = await client.POST('/api/v1/legal-holds/{id}/release', {
    params: { path: { id } },
    body: { reason: reason.trim() },
  })
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Releasing the legal hold failed with HTTP ${response.status}`,
    )
  }
  return data
}

/**
 * After a change of a hold: its lists and views, and every document view, list, search and
 * library page that shows the legal hold marker.
 */
export async function invalidateAfterHoldChange(queryClient: QueryClient): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['legal-holds'] }),
    queryClient.invalidateQueries({ queryKey: ['documents'] }),
    queryClient.invalidateQueries({ queryKey: ['search'] }),
    queryClient.invalidateQueries({ queryKey: ['library'] }),
  ])
}

const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi

/**
 * Document ids from pasted text: ids or links to documents (`/documents/<id>`), separated by
 * anything. Distinct, in the order given, lower case.
 */
export function documentIdsIn(text: string): string[] {
  return [...new Set((text.match(UUID) ?? []).map((id) => id.toLowerCase()))]
}

/** Whether the preview can be confirmed: it has documents, all of which the caller may manage. */
export function confirmable(preview: HoldSearchPreview | undefined): boolean {
  const documents = preview?.documents ?? []
  return documents.length > 0 && documents.every((d) => d.manageable)
}

/** The search result changed since it was reviewed (409 result_changed). */
export function isResultChanged(error: unknown): boolean {
  return error instanceof ApiRequestError && error.code === 'result_changed'
}
