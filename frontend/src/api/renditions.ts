import { queryOptions, type QueryClient } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { requestError } from './errors'
import type { components } from './schema'

/**
 * Archive renditions (DMS-93, ADR 0055): the state of a version's rendition while it waits for
 * approval (pending, retried automatically with backoff, failed), the retry action for the
 * document's owner, records managers and tenant administrators, and the list of failed renditions
 * of the tenant for the records view.
 */

export type RenditionStatus = components['schemas']['RenditionStatusView']
export type RenditionFailure = components['schemas']['RenditionFailureView']
export type RenditionError = components['schemas']['RenditionErrorView']

/** The states the backend reports (`RenditionStatusView.state`). */
export type RenditionState = 'Done' | 'Pending' | 'Retrying' | 'Failed' | 'None'

/** The failure codes the backend reports (`RenditionErrorView.code`). */
export type RenditionErrorCode =
  'unavailable' | 'rejected' | 'not_archivable' | 'invalid' | 'integrity' | 'database'

const ERROR_CODES: ReadonlySet<string> = new Set([
  'unavailable',
  'rejected',
  'not_archivable',
  'invalid',
  'integrity',
  'database',
])

/** The failure code if the backend sent a known one. */
export function errorCode(error: RenditionError | undefined): RenditionErrorCode | undefined {
  return error?.code && ERROR_CODES.has(error.code) ? (error.code as RenditionErrorCode) : undefined
}

export function renditionStatusQuery(client: ApiClient, documentId: string, versionId: string) {
  return queryOptions({
    queryKey: ['renditions', 'status', documentId, versionId],
    queryFn: async (): Promise<RenditionStatus> => {
      const { data, error, response } = await client.GET(
        '/api/v1/documents/{id}/versions/{versionId}/rendition/status',
        { params: { path: { id: documentId, versionId } } },
      )
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the rendition status failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    // While the rendition is produced or retried, look again now and then.
    refetchInterval: (query) => {
      const state = query.state.data?.state
      return state === 'Pending' || state === 'Retrying' ? 15_000 : false
    },
  })
}

export function renditionFailuresQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['renditions', 'failures'],
    queryFn: async (): Promise<RenditionFailure[]> => {
      const { data, error, response } = await client.GET('/api/v1/renditions/failures')
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the failed renditions failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

/** Requeues the rendering job of a version whose rendition failed or waits for its next attempt. */
export async function retryRendition(
  client: ApiClient,
  documentId: string,
  versionId: string,
): Promise<RenditionStatus> {
  const { data, error, response } = await client.POST(
    '/api/v1/documents/{id}/versions/{versionId}/rendition/retry',
    { params: { path: { id: documentId, versionId } } },
  )
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Retrying the rendition failed with HTTP ${response.status}`,
    )
  }
  return data
}

/** After a retry: the version's status, the failure list and the document's workflow. */
export async function invalidateRendition(queryClient: QueryClient, documentId: string) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['renditions'] }),
    queryClient.invalidateQueries({ queryKey: ['documents', documentId] }),
  ])
}
