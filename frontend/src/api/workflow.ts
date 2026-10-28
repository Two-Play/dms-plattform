import { queryOptions, type QueryClient } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { requestError } from './errors'
import type { components } from './schema'

export type CastVote = components['schemas']['CastVote']
export type WorkflowCaseView = components['schemas']['WorkflowCaseView']

/**
 * A review or approval task. `dueAt` is not sent yet (deadlines follow with DMS-87); the inbox shows
 * it and marks overdue tasks once the API delivers it.
 */
export type WorkflowTask = components['schemas']['TaskView'] & { dueAt?: string }

/** Vote decisions (`dms.workflow.VoteDecision`). */
export type VoteDecision = 'Approve' | 'ApproveWithConditions' | 'RequestChanges' | 'Reject'

/** A reason is required for every decision except a plain approval. */
export function needsReason(decision: VoteDecision): boolean {
  return decision !== 'Approve'
}

/** Approval steps need a recent sign-in for approving votes (step-up, ADR 0024). */
export function isApprovalStep(task: Pick<WorkflowTask, 'kind'>): boolean {
  return task.kind === 'Approval'
}

/**
 * The decisions offered for a task: review steps return the version with "request changes",
 * approval steps with "reject".
 */
export function decisionsFor(task: Pick<WorkflowTask, 'kind'>): VoteDecision[] {
  return isApprovalStep(task)
    ? ['Approve', 'ApproveWithConditions', 'Reject']
    : ['Approve', 'ApproveWithConditions', 'RequestChanges']
}

export const decisionLabels: Record<VoteDecision, string> = {
  Approve: 'Approve',
  ApproveWithConditions: 'Approve with conditions',
  RequestChanges: 'Request changes',
  Reject: 'Reject',
}

/** The open review and approval tasks of the caller, oldest first (GET /api/v1/workflow/tasks). */
export function myTasksQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['workflow', 'tasks'],
    queryFn: async (): Promise<WorkflowTask[]> => {
      const { data, error, response } = await client.GET('/api/v1/workflow/tasks')
      if (!response.ok || !data) {
        throw requestError(response, error, `Loading the tasks failed with HTTP ${response.status}`)
      }
      return data
    },
  })
}

/** Refreshes everything a vote changes: the task list, the document and its workflow. */
export function invalidateAfterVote(queryClient: QueryClient, documentId: string): Promise<void> {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: ['workflow', 'tasks'] }),
    queryClient.invalidateQueries({ queryKey: ['documents', documentId] }),
    queryClient.invalidateQueries({ queryKey: ['documents', 'list'] }),
  ]).then(() => undefined)
}

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
