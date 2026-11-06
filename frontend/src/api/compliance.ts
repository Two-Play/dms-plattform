import { queryOptions } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { getLocale } from '@/i18n'
import { formatPercent } from '@/i18n/format'
import { ApiRequestError, requestError } from './errors'
import type { components } from './schema'
import { attachmentName } from './search'

/**
 * Compliance cockpit (DMS-59, ADR 0036): overdue reviews per organizational unit and owner and
 * acknowledgement rates per version and unit, for compliance officers. Names of readers who have
 * not acknowledged a version are purpose-limited: they need a stated purpose and reason, which the
 * backend records in the audit trail. Officers remind or escalate overdue reviews from here and
 * export every list; the backend builds the file, so that it can record the export (ADR 0077).
 */

export type ComplianceOverview = components['schemas']['ComplianceOverviewView']
export type OverdueReview = components['schemas']['OverdueReviewView']
export type VersionRate = components['schemas']['VersionRateView']
export type OpenAcknowledgements = components['schemas']['OpenAcknowledgementsView']
export type RateFigures = components['schemas']['RateView']
export type UnownedDocument = components['schemas']['UnownedDocumentView']
export type DeviationFigure = components['schemas']['DeviationFigureView']
export type ComplianceNotice = components['schemas']['ComplianceNoticeView']

/** Whom a notice about an overdue review reaches: the owner, or also beyond the owner. */
export type NoticeKind = 'reminder' | 'escalation'

/** The lists of the cockpit, by their name in the export path. */
export type ComplianceList =
  | 'overdue-reviews'
  | 'acknowledgements'
  | 'documents-without-owner'
  | 'deviations'
  | 'four-eyes-findings'

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

export function unownedDocumentsQuery(client: ApiClient, unit: GroupFilter) {
  return queryOptions({
    queryKey: ['compliance', 'documents-without-owner', unit ?? null],
    queryFn: async (): Promise<UnownedDocument[]> => {
      const { data, error, response } = await client.GET(
        '/api/v1/compliance/documents-without-owner',
        { params: { query: { unit } } },
      )
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the documents without owner failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

export function deviationFiguresQuery(client: ApiClient, unit: GroupFilter, owner: GroupFilter) {
  return queryOptions({
    queryKey: ['compliance', 'deviations', unit ?? null, owner ?? null],
    queryFn: async (): Promise<DeviationFigure[]> => {
      const { data, error, response } = await client.GET('/api/v1/compliance/deviations', {
        params: { query: { unit, owner } },
      })
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the deviations failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

/** Reminds the owner of an overdue review or escalates it. The reason goes to the audit trail only. */
export async function sendComplianceNotice(
  client: ApiClient,
  documentId: string,
  versionId: string,
  kind: NoticeKind,
  reason: string,
): Promise<ComplianceNotice> {
  const { data, error, response } = await client.POST(
    '/api/v1/compliance/overdue-reviews/{documentId}/{versionId}/notices',
    { params: { path: { documentId, versionId } }, body: { kind, reason } },
  )
  if (!response.ok || !data) {
    throw requestError(response, error, `Sending the notice failed with HTTP ${response.status}`)
  }
  return data
}

/** The translated reason a notice was refused for, if the backend named one we know. */
export function noticeRefusal(e: unknown): 'tooSoon' | 'notOverdue' | 'noRecipient' | undefined {
  if (!(e instanceof ApiRequestError)) return undefined
  if (e.code === 'notice_too_soon') return 'tooSoon'
  if (e.code === 'not_overdue') return 'notOverdue'
  return e.code === 'no_recipient' ? 'noRecipient' : undefined
}

function saveFile(data: Blob, response: Response, fallback: string) {
  const url = URL.createObjectURL(data)
  const link = document.createElement('a')
  link.href = url
  link.download = attachmentName(response.headers.get('Content-Disposition'), fallback)
  document.body.append(link)
  link.click()
  link.remove()
  // Revoked after the click has been handled; revoking at once can cancel the download.
  setTimeout(() => {
    URL.revokeObjectURL(url)
  }, 0)
}

/** Downloads a list as CSV, with the filters of the screen. The backend records the export. */
export async function downloadComplianceList(
  client: ApiClient,
  list: ComplianceList,
  filters: { unit: GroupFilter; owner: GroupFilter; document: string | undefined },
): Promise<void> {
  const { data, error, response } = await client.POST('/api/v1/compliance/exports/{list}', {
    params: { path: { list }, query: { ...filters, lang: getLocale() } },
    parseAs: 'blob',
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `The export failed with HTTP ${response.status}`)
  }
  saveFile(data, response, `compliance-${list}.csv`)
}

/** Downloads who has not acknowledged the version; audited with purpose and reason like the list. */
export async function downloadOpenAcknowledgements(
  client: ApiClient,
  documentId: string,
  versionId: string,
  purpose: AcknowledgementPurpose,
  reason: string,
): Promise<void> {
  const { data, error, response } = await client.POST(
    '/api/v1/compliance/acknowledgements/{documentId}/{versionId}/open/export',
    {
      params: { path: { documentId, versionId }, query: { lang: getLocale() } },
      body: { purpose, reason },
      parseAs: 'blob',
    },
  )
  if (!response.ok || !data) {
    throw requestError(response, error, `The export failed with HTTP ${response.status}`)
  }
  saveFile(data, response, `open-acknowledgements-${versionId}.csv`)
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
  // POST with a body: purpose and reason must not travel in the URL (access logs, ADR 0060).
  const { data, error, response } = await client.POST(
    '/api/v1/compliance/acknowledgements/{documentId}/{versionId}/open',
    { params: { path: { documentId, versionId } }, body: { purpose, reason } },
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
