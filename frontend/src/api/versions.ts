import { queryOptions } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { requestError } from './errors'
import type { components } from './schema'

export type VersionComparison = components['schemas']['VersionComparisonView']
export type SectionDiff = components['schemas']['SectionDiffView']
export type BlockDiff = components['schemas']['BlockDiffView']
export type StructureChange = components['schemas']['StructureChangeView']
export type ValueChange = components['schemas']['ValueChangeView']
export type ChangeSummary = components['schemas']['ChangeSummaryView']

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
