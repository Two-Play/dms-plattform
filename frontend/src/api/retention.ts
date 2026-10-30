import { queryOptions, type QueryClient } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { ApiRequestError, requestError } from './errors'
import { canManageLegalHolds } from './legal-holds'
import type { Me } from './me'
import type { components } from './schema'

/**
 * Retention and destruction (business concept 7.4, DMS-82, ADR 0054): records managers maintain
 * retention rules, see the versions whose retention ended, propose them for destruction with a
 * reason and approve or reject the proposals of others (four eyes). The clean-up of orphaned
 * objects in object storage can be checked (dry run) and run.
 */

export type RetentionRule = components['schemas']['RetentionRuleView']
export type DestructionCandidate = components['schemas']['DestructionCandidateView']
export type DestructionList = components['schemas']['DestructionListView']
export type DestructionProposal = components['schemas']['DestructionProposalView']
export type DestructionItem = components['schemas']['DestructionItemView']
export type CleanupReport = components['schemas']['CleanupReportView']
export type CleanupEntry = components['schemas']['CleanupEntryView']

export type ProposalStatus = 'proposed' | 'approved' | 'rejected' | 'all'
export type RetentionTrigger = 'EndOfValidity' | 'Approval' | 'Creation'

export const RETENTION_TRIGGERS: readonly RetentionTrigger[] = [
  'EndOfValidity',
  'Approval',
  'Creation',
]

/** Keys of retention rules (`RetentionPeriod.KeyPattern`). */
export const RULE_KEY = /^[a-z0-9][a-z0-9._-]{0,62}$/

/** Longest period of a rule in months (`RetentionPeriod.MaxPeriodMonths`). */
export const MAX_PERIOD_MONTHS = 1200

/** Most versions one proposal takes (`DestructionService.MaxItems`). */
export const MAX_PROPOSAL_ITEMS = 500

/** Records managers for the whole tenant, as for legal holds (ADR 0047). */
export function canManageRetention(me: Me | undefined): boolean {
  return canManageLegalHolds(me)
}

export interface DestructionListFilter {
  class?: string
  rule?: string
  q?: string
}

export function destructionListQuery(client: ApiClient, filter: DestructionListFilter) {
  return queryOptions({
    queryKey: ['retention', 'destruction-list', filter],
    queryFn: async (): Promise<DestructionList> => {
      const { data, error, response } = await client.GET('/api/v1/retention/destruction-list', {
        params: {
          query: {
            class: filter.class || undefined,
            rule: filter.rule || undefined,
            q: filter.q?.trim() || undefined,
          },
        },
      })
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the destruction list failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

export function proposalsQuery(client: ApiClient, status: ProposalStatus) {
  return queryOptions({
    queryKey: ['retention', 'proposals', status],
    queryFn: async (): Promise<DestructionProposal[]> => {
      const { data, error, response } = await client.GET(
        '/api/v1/retention/destruction-proposals',
        { params: { query: { status } } },
      )
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the destruction proposals failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

export function retentionRulesQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['retention', 'rules'],
    queryFn: async (): Promise<RetentionRule[]> => {
      const { data, error, response } = await client.GET('/api/v1/retention/rules')
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the retention rules failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

export function cleanupRunsQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['retention', 'cleanup'],
    queryFn: async (): Promise<CleanupReport[]> => {
      const { data, error, response } = await client.GET('/api/v1/retention/storage-cleanup')
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the clean-up runs failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

export async function proposeDestruction(
  client: ApiClient,
  versionIds: string[],
  reason: string,
): Promise<DestructionProposal> {
  const { data, error, response } = await client.POST('/api/v1/retention/destruction-proposals', {
    body: { versionIds, reason: reason.trim() },
  })
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Proposing the destruction failed with HTTP ${response.status}`,
    )
  }
  return data
}

export async function decideDestruction(
  client: ApiClient,
  id: string,
  decision: 'approve' | 'reject',
  reason: string,
): Promise<DestructionProposal> {
  const options = { params: { path: { id } }, body: { reason: reason.trim() } }
  const { data, error, response } =
    decision === 'approve'
      ? await client.POST('/api/v1/retention/destruction-proposals/{id}/approve', options)
      : await client.POST('/api/v1/retention/destruction-proposals/{id}/reject', options)
  if (!response.ok || !data) {
    throw requestError(response, error, `The decision failed with HTTP ${response.status}`)
  }
  return data
}

export interface RuleInput {
  name: string
  periodMonths: number
  trigger: RetentionTrigger
  fromYearEnd: boolean
}

export async function saveRetentionRule(
  client: ApiClient,
  key: string,
  rule: RuleInput,
): Promise<RetentionRule> {
  const { data, error, response } = await client.PUT('/api/v1/retention/rules/{key}', {
    params: { path: { key } },
    body: { ...rule, name: rule.name.trim() },
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Saving the rule failed with HTTP ${response.status}`)
  }
  return data
}

export async function runStorageCleanup(
  client: ApiClient,
  dryRun: boolean,
): Promise<CleanupReport> {
  const { data, error, response } = await client.POST('/api/v1/retention/storage-cleanup', {
    body: { dryRun },
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `The clean-up failed with HTTP ${response.status}`)
  }
  return data
}

/** Whether the backend refused a decision because the caller proposed it (403 four_eyes). */
export function isFourEyes(error: unknown): boolean {
  return error instanceof ApiRequestError && error.code === 'four_eyes'
}

/**
 * After a proposal or decision: the retention views and everything that lists documents, since
 * destroyed versions leave lists, search and library.
 */
export async function invalidateAfterDestruction(queryClient: QueryClient): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['retention'] }),
    queryClient.invalidateQueries({ queryKey: ['documents'] }),
    queryClient.invalidateQueries({ queryKey: ['search'] }),
    queryClient.invalidateQueries({ queryKey: ['library'] }),
  ])
}

/** The distinct values of a candidate field, sorted, for the filters of the destruction list. */
export function distinct(values: readonly string[]): string[] {
  return [...new Set(values)].sort((a, b) => a.localeCompare(b))
}

/** The candidates matching the filters of the page: class, rule and part of title or number. */
export function filterCandidates(
  candidates: readonly DestructionCandidate[],
  filter: DestructionListFilter,
): DestructionCandidate[] {
  const text = filter.q?.trim().toLowerCase() ?? ''
  return candidates.filter(
    (c) =>
      (!filter.class || c.classKey === filter.class) &&
      (!filter.rule || c.retentionRule === filter.rule) &&
      (text === '' ||
        (c.title ?? '').toLowerCase().includes(text) ||
        (c.documentNumber ?? '').toLowerCase().includes(text)),
  )
}
