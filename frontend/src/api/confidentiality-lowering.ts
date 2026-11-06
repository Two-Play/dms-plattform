import { queryOptions, type QueryClient } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { requestError } from './errors'
import type { components } from './schema'

export type Lowerings = components['schemas']['LoweringsView']
export type Lowering = components['schemas']['LoweringView']

/** Longest reason the backend accepts. */
export const MAX_LOWERING_REASON = 4000

/** The step of the workflow in which a lower confidentiality is decided (ADR 0083). */
export const LOWERING_STEP = 'confidentiality-lowering'

/** Levels from the most open to the most restricted (business concept 2.3). */
export const LEVELS = ['Public', 'Internal', 'Confidential', 'StrictlyConfidential']

/** The levels below `level`, the closest first: what a draft can ask for. */
export function levelsBelow(level: string): string[] {
  return LEVELS.slice(0, Math.max(LEVELS.indexOf(level), 0)).reverse()
}

const loweringKey = (documentId: string, versionId: string) => [
  'documents',
  documentId,
  'confidentiality-lowering',
  versionId,
]

/**
 * The level of a version and the requests to lower it
 * (GET /api/v1/documents/{id}/versions/{versionId}/confidentiality-lowering, ADR 0083).
 */
export function loweringQuery(client: ApiClient, documentId: string, versionId: string) {
  return queryOptions({
    queryKey: loweringKey(documentId, versionId),
    queryFn: async (): Promise<Lowerings> => {
      const { data, error, response } = await client.GET(
        '/api/v1/documents/{id}/versions/{versionId}/confidentiality-lowering',
        { params: { path: { id: documentId, versionId } } },
      )
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the confidentiality requests failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    retry: false,
  })
}

/** Asks for a lower level (POST …/confidentiality-lowering). */
export async function requestLowering(
  client: ApiClient,
  documentId: string,
  body: { level: string; reason: string },
): Promise<Lowerings> {
  const { data, error, response } = await client.POST(
    '/api/v1/documents/{id}/confidentiality-lowering',
    { params: { path: { id: documentId } }, body },
  )
  if (!response.ok || !data) {
    throw requestError(response, error, `Asking failed with HTTP ${response.status}`)
  }
  return data
}

/** Withdraws the open request of the draft (POST …/confidentiality-lowering/withdraw). */
export async function withdrawLowering(client: ApiClient, documentId: string): Promise<Lowerings> {
  const { data, error, response } = await client.POST(
    '/api/v1/documents/{id}/confidentiality-lowering/withdraw',
    { params: { path: { id: documentId } } },
  )
  if (!response.ok || !data) {
    throw requestError(response, error, `Withdrawing failed with HTTP ${response.status}`)
  }
  return data
}

/** Shows what the server answered with, then updates what depends on it (editors of the version). */
export async function applyLowerings(
  queryClient: QueryClient,
  documentId: string,
  lowerings: Lowerings,
): Promise<void> {
  queryClient.setQueryData(loweringKey(documentId, lowerings.versionId), lowerings)
  await queryClient.invalidateQueries({ queryKey: ['documents', documentId] })
}
