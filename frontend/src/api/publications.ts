import { queryOptions, type QueryClient } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { requestError } from './errors'
import type { components } from './schema'

/**
 * Scheduled publications (DMS-142, ADR 0058): the state of the publication job of an approved
 * version, the retry for the document's owner, records managers and tenant administrators, and the
 * list of failed publications of the tenant for the records view.
 */

export type PublicationStatus = components['schemas']['PublicationStatusView']
export type PublicationFailure = components['schemas']['PublicationFailureView']
export type PublicationError = components['schemas']['RenditionErrorView']

/** The states the backend reports (`PublicationStatusView.state`). */
export type PublicationState = 'Pending' | 'Retrying' | 'Failed' | 'None'

/** The failure codes the backend reports for publications. */
export type PublicationErrorCode = 'conflict' | 'legal_hold' | 'invalid' | 'not_found' | 'database'

const ERROR_CODES: ReadonlySet<string> = new Set([
  'conflict',
  'legal_hold',
  'invalid',
  'not_found',
  'database',
])

/** The failure code if the backend sent a known one. */
export function publicationErrorCode(
  error: PublicationError | undefined,
): PublicationErrorCode | undefined {
  return error?.code && ERROR_CODES.has(error.code)
    ? (error.code as PublicationErrorCode)
    : undefined
}

export function publicationStatusQuery(client: ApiClient, documentId: string, versionId: string) {
  return queryOptions({
    queryKey: ['publications', 'status', documentId, versionId],
    queryFn: async (): Promise<PublicationStatus> => {
      const { data, error, response } = await client.GET(
        '/api/v1/documents/{id}/versions/{versionId}/publication/status',
        { params: { path: { id: documentId, versionId } } },
      )
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the publication status failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

export function publicationFailuresQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['publications', 'failures'],
    queryFn: async (): Promise<PublicationFailure[]> => {
      const { data, error, response } = await client.GET('/api/v1/publications/failures')
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the failed publications failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

/** Requeues the publication job of a version whose publication failed. */
export async function retryPublication(
  client: ApiClient,
  documentId: string,
  versionId: string,
): Promise<PublicationStatus> {
  const { data, error, response } = await client.POST(
    '/api/v1/documents/{id}/versions/{versionId}/publication/retry',
    { params: { path: { id: documentId, versionId } } },
  )
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Retrying the publication failed with HTTP ${response.status}`,
    )
  }
  return data
}

/** After a retry: the version's status, the failure list and the document. */
export async function invalidatePublication(queryClient: QueryClient, documentId: string) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['publications'] }),
    queryClient.invalidateQueries({ queryKey: ['documents', documentId] }),
  ])
}
