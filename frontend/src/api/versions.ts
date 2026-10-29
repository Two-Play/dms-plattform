import { queryOptions } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { requestError } from './errors'
import type { DocumentVersionView } from './partners'
import type { components } from './schema'

export type VersionComparison = components['schemas']['VersionComparisonView']
export type SectionDiff = components['schemas']['SectionDiffView']
export type BlockDiff = components['schemas']['BlockDiffView']
export type StructureChange = components['schemas']['StructureChangeView']
export type ValueChange = components['schemas']['ValueChangeView']
export type ChangeSummary = components['schemas']['ChangeSummaryView']

/** Major (change of content, full workflow) or Minor (editorial correction, shortened workflow). */
export type ChangeKind = 'Major' | 'Minor'

/** Longest change summary the backend accepts. */
export const MAX_CHANGE_SUMMARY = 4000

/**
 * Starts the next version from the valid version as a draft (POST /api/v1/documents/{id}/versions, DMS-103).
 * `reason` is the change summary, required for Minor.
 */
export async function startNewVersion(
  client: ApiClient,
  id: string,
  body: { changeKind: ChangeKind; reason?: string },
): Promise<DocumentVersionView> {
  const { data, error, response } = await client.POST('/api/v1/documents/{id}/versions', {
    params: { path: { id } },
    body,
  })
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Starting the new version failed with HTTP ${response.status}`,
    )
  }
  return data
}

/**
 * Changes the change summary of the open draft (PUT /api/v1/documents/{id}/draft/change-summary, DMS-103);
 * an empty summary removes it (not allowed for Minor).
 */
export async function updateChangeSummary(
  client: ApiClient,
  id: string,
  reason: string,
): Promise<DocumentVersionView> {
  const { data, error, response } = await client.PUT(
    '/api/v1/documents/{id}/draft/change-summary',
    { params: { path: { id } }, body: reason === '' ? {} : { reason } },
  )
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Saving the change summary failed with HTTP ${response.status}`,
    )
  }
  return data
}

/** The versions of a document the caller may read, oldest first (GET /api/v1/documents/{id}/versions). */
export function documentVersionsQuery(client: ApiClient, id: string) {
  return queryOptions({
    queryKey: ['documents', id, 'versions'],
    queryFn: async () => {
      const { data, error, response } = await client.GET('/api/v1/documents/{id}/versions', {
        params: { path: { id } },
      })
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the versions failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    retry: false,
  })
}

/** Text, field and metadata changes between two versions (GET /api/v1/documents/{id}/compare). */
export function versionComparisonQuery(client: ApiClient, id: string, from: string, to: string) {
  return queryOptions({
    queryKey: ['documents', id, 'compare', from, to],
    queryFn: async () => {
      const { data, error, response } = await client.GET('/api/v1/documents/{id}/compare', {
        params: { path: { id }, query: { from, to } },
      })
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Comparing the versions failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    retry: false,
  })
}
