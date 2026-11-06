import { queryOptions } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { requestError } from './errors'
import type { components } from './schema'

/**
 * Retroactive check of the four-eyes rule (DMS-163, ADR 0078): approvals and approving votes that
 * stand although the person changed the version. The backend records every reading in the audit
 * trail, so the cockpit asks only when the officer opens the report.
 */

export type FourEyesFinding = components['schemas']['FourEyesFindingView']

/** The kinds the backend reports (`FourEyesFindingView.kind`). */
export type FindingKind = 'Approval' | 'ApprovalVote' | 'ReviewVote'

export function fourEyesFindingsQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['compliance', 'four-eyes-findings'],
    queryFn: async (): Promise<FourEyesFinding[]> => {
      const { data, error, response } = await client.GET('/api/v1/compliance/four-eyes-findings')
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the four-eyes findings failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}
