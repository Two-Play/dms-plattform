import { queryOptions, type QueryClient } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { ApiRequestError, requestError } from './errors'
import type { components } from './schema'

/**
 * Periodic review of valid versions (DMS-55, ADR 0030). The document owner gets a review task at
 * the class's lead time before the review date and decides once: recertify (new review date, same
 * version), revise (new draft) or withdraw (the version goes out of force).
 */

/** A review task of a valid version. */
export type ReviewTask = components['schemas']['ReviewTaskView']

export type ReviewTaskStatus = 'open' | 'done'

/** Review tasks of the documents the caller owns (GET /api/v1/reviews?status=…). */
export async function listReviewTasks(
  client: ApiClient,
  status: ReviewTaskStatus = 'open',
): Promise<ReviewTask[]> {
  const { data, error, response } = await client.GET('/api/v1/reviews', {
    params: { query: { status } },
  })
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Loading the review tasks failed with HTTP ${response.status}`,
    )
  }
  return data
}

/**
 * Open review tasks for the task inbox, or `null` when the backend does not offer them (404), so
 * the inbox can leave them out.
 */
export function openReviewTasksQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['reviews', 'open'],
    queryFn: async (): Promise<ReviewTask[] | null> => {
      try {
        return await listReviewTasks(client, 'open')
      } catch (e) {
        if (e instanceof ApiRequestError && e.status === 404) return null
        throw e
      }
    },
    retry: false,
  })
}

/**
 * Recertifies the version without a change (POST /api/v1/reviews/{taskId}/recertify). Without
 * `nextReviewOn` the next review date is today plus the class's interval.
 */
export async function recertify(
  client: ApiClient,
  taskId: string,
  body: { nextReviewOn?: string; comment?: string },
): Promise<ReviewTask> {
  const { data, error, response } = await client.POST('/api/v1/reviews/{taskId}/recertify', {
    params: { path: { taskId } },
    body,
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Recertifying failed with HTTP ${response.status}`)
  }
  return data
}

/**
 * Starts the revision (POST /api/v1/reviews/{taskId}/revise): a new major version as a draft, or
 * the version already open. `newVersionId` of the result names the draft.
 */
export async function revise(
  client: ApiClient,
  taskId: string,
  body: { changeKind?: 'Major' | 'Minor'; reason?: string } = {},
): Promise<ReviewTask> {
  const { data, error, response } = await client.POST('/api/v1/reviews/{taskId}/revise', {
    params: { path: { taskId } },
    body,
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Starting the revision failed with HTTP ${response.status}`)
  }
  return data
}

/** Withdraws the version: it goes out of force (POST /api/v1/reviews/{taskId}/withdraw). */
export async function withdraw(
  client: ApiClient,
  taskId: string,
  reason: string,
): Promise<ReviewTask> {
  const { data, error, response } = await client.POST('/api/v1/reviews/{taskId}/withdraw', {
    params: { path: { taskId } },
    body: { reason },
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Withdrawing failed with HTTP ${response.status}`)
  }
  return data
}

/** After a decision: the review tasks and the document's views are stale. */
export async function invalidateAfterReview(
  queryClient: QueryClient,
  documentId: string,
): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['reviews'] }),
    queryClient.invalidateQueries({ queryKey: ['documents', documentId] }),
  ])
}
