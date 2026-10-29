import { queryOptions } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { formatPercent } from '@/i18n/format'
import { requestError } from './errors'
import type { components } from './schema'

/**
 * Compliance cockpit (DMS-59, ADR 0036): overdue reviews per organizational unit and owner and
 * acknowledgement rates per version and unit, for compliance officers. Names of readers who have
 * not acknowledged a version are purpose-limited: they need a stated purpose and reason, which the
 * backend records in the audit trail.
 */

export type ComplianceOverview = components['schemas']['ComplianceOverviewView']
export type OverdueReview = components['schemas']['OverdueReviewView']
export type VersionRate = components['schemas']['VersionRateView']
export type OpenAcknowledgements = components['schemas']['OpenAcknowledgementsView']
export type RateFigures = components['schemas']['RateView']

/** Why the names of readers are looked at (purpose limitation). */
export type AcknowledgementPurpose = 'escalation' | 'reminder'

/**
 * A unit or owner filter: `undefined` for all, the empty string for versions without unit or
 * owner.
 */
export type GroupFilter = string | undefined

export function complianceOverviewQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['compliance', 'overview'],
    queryFn: async (): Promise<ComplianceOverview> => {
      const { data, error, response } = await client.GET('/api/v1/compliance/overview')
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the compliance overview failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

export function overdueReviewsQuery(client: ApiClient, unit: GroupFilter, owner: GroupFilter) {
  return queryOptions({
    queryKey: ['compliance', 'overdue-reviews', unit ?? null, owner ?? null],
    queryFn: async (): Promise<OverdueReview[]> => {
      const { data, error, response } = await client.GET('/api/v1/compliance/overdue-reviews', {
        params: { query: { unit, owner } },
      })
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the overdue reviews failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

export function acknowledgementRatesQuery(
  client: ApiClient,
  unit: GroupFilter,
  document: string | undefined,
) {
  return queryOptions({
    queryKey: ['compliance', 'acknowledgements', unit ?? null, document ?? null],
    queryFn: async (): Promise<VersionRate[]> => {
      const { data, error, response } = await client.GET('/api/v1/compliance/acknowledgements', {
        params: { query: { unit, document } },
      })
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the acknowledgement rates failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

/**
 * Who has not acknowledged the version yet. The backend records the access with purpose and
 * reason in the audit trail; it is fetched on explicit request only, never cached.
 */
export async function fetchOpenAcknowledgements(
  client: ApiClient,
  documentId: string,
  versionId: string,
  purpose: AcknowledgementPurpose,
  reason: string,
): Promise<OpenAcknowledgements> {
  const { data, error, response } = await client.GET(
    '/api/v1/compliance/acknowledgements/{documentId}/{versionId}/open',
    { params: { path: { documentId, versionId }, query: { purpose, reason } } },
  )
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Loading who has not acknowledged failed with HTTP ${response.status}`,
    )
  }
  return data
}

/** A rate (0 to 1) as a whole percentage, or a dash without audience. */
export function formatRate(rate: number | undefined): string {
  return rate === undefined ? '–' : formatPercent(rate)
}
