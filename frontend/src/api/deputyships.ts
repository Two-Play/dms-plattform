import { queryOptions } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { requestError } from './errors'
import type { components } from './schema'

/**
 * Deputies of workflow participants (DMS-49, DMS-99, ADR 0028): during a time-limited period the
 * deputy may complete the principal's open review and approval tasks "on behalf of" them.
 */
export type Deputyship = components['schemas']['DeputyshipView']
export type NewDeputyship = components['schemas']['CreateDeputyship']

/** Lane roles a deputyship can cover. */
export const deputyRoles = ['Author', 'Owner', 'Reviewer', 'Approver'] as const

/**
 * Deputyships: `mine` those of the caller as principal or deputy, `all` every deputyship of the
 * tenant (tenant administrators). Latest period first (GET /api/v1/deputyships).
 */
export function deputyshipsQuery(client: ApiClient, scope: 'mine' | 'all') {
  return queryOptions({
    queryKey: ['deputyships', scope],
    queryFn: async (): Promise<Deputyship[]> => {
      const { data, error, response } = await client.GET('/api/v1/deputyships', {
        params: { query: scope === 'all' ? { all: true } : {} },
      })
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the deputyships failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

/** Sets a deputy (POST /api/v1/deputyships); 422 for an invalid period or missing authority. */
export async function createDeputyship(
  client: ApiClient,
  request: NewDeputyship,
): Promise<Deputyship> {
  const { data, error, response } = await client.POST('/api/v1/deputyships', { body: request })
  if (!response.ok || !data) {
    throw requestError(response, error, `Setting the deputy failed with HTTP ${response.status}`)
  }
  return data
}

/** Ends a deputyship now (DELETE /api/v1/deputyships/{id}); 409 if it has ended already. */
export async function endDeputyship(client: ApiClient, id: string): Promise<Deputyship> {
  const { data, error, response } = await client.DELETE('/api/v1/deputyships/{id}', {
    params: { path: { id } },
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Ending the deputyship failed with HTTP ${response.status}`)
  }
  return data
}
