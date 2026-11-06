import { queryOptions, type QueryClient } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { requestError } from './errors'
import type { components } from './schema'

export type Deviations = components['schemas']['DeviationsView']
export type DeviationRequest = components['schemas']['DeviationRequestView']
export type Deviation = components['schemas']['DeviationView']
export type DeviationChange = components['schemas']['DeviationChange']
export type PathStep = components['schemas']['PathStep']

/** Longest reason the backend accepts. */
export const MAX_DEVIATION_REASON = 4000

/** Response code of a submission refused because of the edit rules of the template (ADR 0069). */
export const UNAUTHORIZED_DEVIATIONS = 'unauthorized_deviations'

const deviationsKey = (documentId: string, versionId: string) => [
  'documents',
  documentId,
  'deviations',
  versionId,
]

/**
 * The deviation report of a version and its requests, active and withdrawn
 * (GET /api/v1/documents/{id}/versions/{versionId}/deviations, ADR 0022).
 */
export function deviationsQuery(client: ApiClient, documentId: string, versionId: string) {
  return queryOptions({
    queryKey: deviationsKey(documentId, versionId),
    queryFn: async (): Promise<Deviations> => {
      const { data, error, response } = await client.GET(
        '/api/v1/documents/{id}/versions/{versionId}/deviations',
        { params: { path: { id: documentId, versionId } } },
      )
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the deviation report failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    retry: false,
  })
}

/**
 * The locked sections of the template of a version with their text (GET …/versions/{versionId}/standard-text).
 * Template versions do not change, so what was loaded stays valid.
 */
export function standardTextQuery(client: ApiClient, documentId: string, versionId: string) {
  return queryOptions({
    queryKey: ['documents', documentId, 'standard-text', versionId],
    queryFn: async () => {
      const { data, error, response } = await client.GET(
        '/api/v1/documents/{id}/versions/{versionId}/standard-text',
        { params: { path: { id: documentId, versionId } } },
      )
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the standard text failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    staleTime: Infinity,
    retry: false,
  })
}

/** Records a deviation request for a locked section of the draft (POST …/deviation-requests). */
export async function requestDeviation(
  client: ApiClient,
  documentId: string,
  body: { sectionId: string; reason: string },
): Promise<Deviations> {
  const { data, error, response } = await client.POST('/api/v1/documents/{id}/deviation-requests', {
    params: { path: { id: documentId } },
    body,
  })
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Requesting the deviation failed with HTTP ${response.status}`,
    )
  }
  return data
}

/** Withdraws a deviation request of the draft (POST …/deviation-requests/{requestId}/withdraw). */
export async function withdrawDeviation(
  client: ApiClient,
  documentId: string,
  requestId: string,
): Promise<Deviations> {
  const { data, error, response } = await client.POST(
    '/api/v1/documents/{id}/deviation-requests/{requestId}/withdraw',
    { params: { path: { id: documentId, requestId } } },
  )
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Withdrawing the request failed with HTTP ${response.status}`,
    )
  }
  return data
}

/** Shows the report the server answered with, then updates what depends on it (the extra review flag). */
export async function applyDeviations(
  queryClient: QueryClient,
  documentId: string,
  deviations: Deviations,
): Promise<void> {
  queryClient.setQueryData(deviationsKey(documentId, deviations.versionId), deviations)
  await queryClient.invalidateQueries({ queryKey: ['documents', documentId] })
}
