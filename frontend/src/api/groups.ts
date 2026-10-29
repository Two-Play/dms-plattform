import { queryOptions, useQuery } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { useApiClient } from './context'
import { requestError } from './errors'
import type { components } from './schema'

/** An access group of the tenant (DMS-101): its members see the confidential documents that name it. */
export type Group = components['schemas']['GroupView']

/** A current or ended membership of a user in an access group (kept for the history). */
export type GroupMember = components['schemas']['GroupMemberView']

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

/** Members of a group, current ones first, then ended ones (GET …/groups/{key}/members, admins). */
export function groupMembersQuery(client: ApiClient, key: string) {
  return queryOptions({
    queryKey: ['groups', key, 'members'],
    queryFn: async (): Promise<GroupMember[]> => {
      const { data, error, response } = await client.GET('/api/v1/groups/{key}/members', {
        params: { path: { key } },
      })
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the members failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

/** Creates a group (POST /api/v1/groups, admins); 422 for an invalid key, 409 for a taken one. */
export async function createGroup(client: ApiClient, key: string, name: string): Promise<Group> {
  const { data, error, response } = await client.POST('/api/v1/groups', {
    body: { key, name },
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Creating the group failed with HTTP ${response.status}`)
  }
  return data
}

/** Changes the display name of a group; the key stays (PUT /api/v1/groups/{key}, DMS-105). */
export async function renameGroup(client: ApiClient, key: string, name: string): Promise<Group> {
  const { data, error, response } = await client.PUT('/api/v1/groups/{key}', {
    params: { path: { key } },
    body: { name },
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Renaming the group failed with HTTP ${response.status}`)
  }
  return data
}

/**
 * Adds a user to a group (PUT …/members/{userId}); idempotent. The backend resolves the
 * participants of open tasks of confidential versions naming the group again (DMS-105).
 */
export async function addGroupMember(
  client: ApiClient,
  key: string,
  userId: string,
): Promise<GroupMember[]> {
  const { data, error, response } = await client.PUT('/api/v1/groups/{key}/members/{userId}', {
    params: { path: { key, userId } },
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Adding the member failed with HTTP ${response.status}`)
  }
  return data
}

/** Ends a membership (DELETE …/members/{userId}); the row stays in the history. */
export async function removeGroupMember(
  client: ApiClient,
  key: string,
  userId: string,
): Promise<GroupMember[]> {
  const { data, error, response } = await client.DELETE('/api/v1/groups/{key}/members/{userId}', {
    params: { path: { key, userId } },
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Removing the member failed with HTTP ${response.status}`)
  }
  return data
}
