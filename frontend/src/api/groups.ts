import { queryOptions, useQuery } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { useApiClient } from './context'
import { requestError } from './errors'
import type { components } from './schema'

/** An access group of the tenant (DMS-101): its members see the confidential documents that name it. */
export type Group = components['schemas']['GroupView']

/** The access groups of the tenant, sorted by key (GET /api/v1/groups). */
export function groupsQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['groups'],
    queryFn: async (): Promise<Group[]> => {
      const { data, error, response } = await client.GET('/api/v1/groups')
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the groups failed with HTTP ${response.status}`,
        )
      }
      return Array.isArray(data) ? data : []
    },
    staleTime: 60_000,
  })
}

export function useGroups(enabled = true) {
  return useQuery({ ...groupsQuery(useApiClient()), enabled })
}
