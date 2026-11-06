import { queryOptions, type QueryClient } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { requestError } from './errors'
import type { components } from './schema'

/**
 * What records management keeps straight about documents (DMS-162, ADR 0084): owner and title that
 * differ from the version last in force, and open versions below their basis. The backend records
 * every reading in the audit trail, and leaves out what the caller is not cleared for, which it
 * counts in `notCleared`.
 */

export type RecordDifference = components['schemas']['RecordDifferenceView']
export type RecordDifferences = components['schemas']['RecordDifferencesView']
export type DraftBelowBasis = components['schemas']['DraftBelowBasisView']
export type DraftsBelowBasis = components['schemas']['DraftsBelowBasisView']

export function recordDifferencesQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['records', 'document-differences'],
    queryFn: async (): Promise<RecordDifferences> => {
      const { data, error, response } = await client.GET('/api/v1/records/document-differences')
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the document differences failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

export function draftsBelowBasisQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['records', 'drafts-below-basis'],
    queryFn: async (): Promise<DraftsBelowBasis> => {
      const { data, error, response } = await client.GET('/api/v1/records/drafts-below-basis')
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the drafts below their basis failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

/** Takes the chosen values of the version last in force; `reason` goes to the audit trail. */
export async function correctRecord(
  client: ApiClient,
  difference: RecordDifference,
  choice: { owner: boolean; title: boolean },
  reason: string,
): Promise<void> {
  const { error, response } = await client.POST('/api/v1/documents/{id}/record-correction', {
    params: { path: { id: difference.documentId } },
    body: { versionId: difference.versionId, ...choice, reason: reason.trim() },
  })
  if (!response.ok) {
    throw requestError(response, error, `Correcting failed with HTTP ${response.status}`)
  }
}

/** The owner changes rights, tasks and search results; the title shows in every list. */
export async function invalidateAfterCorrection(queryClient: QueryClient): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['records'] }),
    queryClient.invalidateQueries({ queryKey: ['documents'] }),
    queryClient.invalidateQueries({ queryKey: ['search'] }),
    queryClient.invalidateQueries({ queryKey: ['library'] }),
  ])
}
