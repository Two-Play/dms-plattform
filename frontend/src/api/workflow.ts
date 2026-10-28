import type { ApiClient } from './client'
import { requestError } from './errors'
import type { components } from './schema'

export type CastVote = components['schemas']['CastVote']
export type WorkflowCaseView = components['schemas']['WorkflowCaseView']

/**
 * Casts a vote on a workflow task (POST /api/v1/workflow/tasks/{taskId}/votes). The API client adds
 * the ID token for the step-up check. Approval votes fail with an `ApiRequestError` whose code is
 * `reauthentication_required` when the last sign-in is older than the configured window; the caller
 * then signs in again with `reauthenticate` (auth/step-up.ts) and the user repeats the vote.
 */
export async function castVote(
  client: ApiClient,
  taskId: string,
  vote: CastVote,
): Promise<WorkflowCaseView> {
  const { data, error, response } = await client.POST('/api/v1/workflow/tasks/{taskId}/votes', {
    params: { path: { taskId } },
    body: vote,
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Voting failed with HTTP ${response.status}`)
  }
  return data
}
