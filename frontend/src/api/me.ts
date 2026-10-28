import { queryOptions, useQuery } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { useApiClient } from './context'
import { requestError } from './errors'
import type { components } from './schema'

export type Me = components['schemas']['Me']
export type RoleAssignment = components['schemas']['RoleAssignmentView']

/** Business roles of the platform (`dms.authz.Role`). */
export type BusinessRole =
  | 'Reader'
  | 'Author'
  | 'Owner'
  | 'Reviewer'
  | 'Approver'
  | 'TemplateDesigner'
  | 'RecordsManager'
  | 'Auditor'
  | 'TenantAdmin'
  | 'ComplianceOfficer'

/** The signed-in user with the business roles of their active role assignments (GET /api/v1/me). */
export function meQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['me'],
    queryFn: async () => {
      const { data, error, response } = await client.GET('/api/v1/me')
      if (!response.ok || !data) {
        throw requestError(response, error, `Loading the user failed with HTTP ${response.status}`)
      }
      return data
    },
    staleTime: 60_000,
  })
}

export function useMe() {
  return useQuery(meQuery(useApiClient()))
}

/** Whether the user holds `role` in an active assignment (any scope). */
export function hasRole(me: Me | undefined, role: BusinessRole): boolean {
  return Array.isArray(me?.roles) && me.roles.includes(role)
}
