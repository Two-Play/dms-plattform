import { queryOptions } from '@tanstack/react-query'
import type { ApiClient } from './client'
import type { AcknowledgementPurpose } from './compliance'
import { ApiRequestError, requestError } from './errors'
import type { components } from './schema'

/**
 * Acknowledgement overview for document owners and compliance officers, reminders and who must
 * acknowledge per document class (DMS-100, ADR 0048). Figures come without names; names of the
 * recipients need a stated purpose and reason, which the backend records in the audit trail.
 */

export type AcknowledgementOverview = components['schemas']['AcknowledgementOverviewView']
export type VersionAcknowledgementOverview =
  components['schemas']['VersionAcknowledgementOverviewView']
export type Recipients = components['schemas']['RecipientsView']
export type RecipientList = components['schemas']['RecipientListView']
export type Recipient = components['schemas']['RecipientView']
export type ReminderSent = components['schemas']['ReminderSentView']
export type AcknowledgementSettingsOverview =
  components['schemas']['AcknowledgementSettingsOverviewView']
export type AcknowledgementSettings = components['schemas']['AcknowledgementSettingsView']
export type ClassAcknowledgementSettings = components['schemas']['ClassAcknowledgementSettingsView']
export type AcknowledgementSettingsChange = components['schemas']['AcknowledgementSettingsChange']

export type RecipientStatus = 'open' | 'done'

/**
 * The acknowledgement overview of a document, or `null` when the caller may not see it (403: not
 * the owner or a compliance officer), so the document page leaves the card out.
 */
export function acknowledgementOverviewQuery(client: ApiClient, documentId: string) {
  return queryOptions({
    queryKey: ['acknowledgements', 'overview', documentId],
    queryFn: async (): Promise<AcknowledgementOverview | null> => {
      const { data, error, response } = await client.GET(
        '/api/v1/documents/{id}/acknowledgement-overview',
        { params: { path: { id: documentId } } },
      )
      if (response.status === 403) return null
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the acknowledgement overview failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    retry: false,
  })
}

/** Who must acknowledge the version, with names; the access is audited with purpose and reason. */
export async function fetchRecipients(
  client: ApiClient,
  documentId: string,
  versionId: string,
  purpose: AcknowledgementPurpose,
  reason: string,
): Promise<RecipientList> {
  const { data, error, response } = await client.GET(
    '/api/v1/documents/{id}/versions/{versionId}/acknowledgement-recipients',
    { params: { path: { id: documentId, versionId }, query: { purpose, reason } } },
  )
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Loading the recipients failed with HTTP ${response.status}`,
    )
  }
  return data
}

/** Reminds everyone who has not acknowledged the version; at most once per day (429 otherwise). */
export async function sendReminder(
  client: ApiClient,
  documentId: string,
  versionId: string,
): Promise<ReminderSent> {
  const { data, error, response } = await client.POST(
    '/api/v1/documents/{id}/versions/{versionId}/acknowledgement-reminders',
    { params: { path: { id: documentId, versionId } } },
  )
  if (!response.ok || !data) {
    throw requestError(response, error, `Sending the reminder failed with HTTP ${response.status}`)
  }
  return data
}

/** Whether a failed reminder was refused because one was sent less than a day ago. */
export function isTooSoon(e: unknown): boolean {
  return e instanceof ApiRequestError && (e.status === 429 || e.code === 'reminder_too_soon')
}

/** The recipients as CSV (semicolon-separated, for spreadsheets in German and English locales). */
export function recipientsCsv(
  list: RecipientList,
  headers: { name: string; userId: string; status: string; acknowledgedAt: string },
  statusLabel: (status: string) => string,
): string {
  const cell = (value: string) =>
    /[";\n\r]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value
  const rows = (list.recipients ?? []).map((r) =>
    [r.displayName ?? '', r.userId, statusLabel(r.status), r.acknowledgedAt ?? '']
      .map(cell)
      .join(';'),
  )
  return [
    [headers.name, headers.userId, headers.status, headers.acknowledgedAt].map(cell).join(';'),
    ...rows,
  ].join('\r\n')
}

/** Offers `content` as a file download. */
export function downloadText(content: string, fileName: string, type = 'text/csv;charset=utf-8') {
  // A byte order mark lets spreadsheet programs read umlauts correctly.
  const url = URL.createObjectURL(new Blob(['﻿', content], { type }))
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.append(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

/** The tenant default and every class with its acknowledgement settings (administrators). */
export function acknowledgementSettingsQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['acknowledgement-settings'],
    queryFn: async (): Promise<AcknowledgementSettingsOverview> => {
      const { data, error, response } = await client.GET('/api/v1/admin/acknowledgement-settings')
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the acknowledgement settings failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    retry: false,
  })
}

/** Saves the settings of a class, or the tenant default when `classId` is undefined. */
export async function saveAcknowledgementSettings(
  client: ApiClient,
  classId: string | undefined,
  change: AcknowledgementSettingsChange,
): Promise<AcknowledgementSettings> {
  const { data, error, response } = classId
    ? await client.PUT('/api/v1/admin/acknowledgement-settings/classes/{classId}', {
        params: { path: { classId } },
        body: change,
      })
    : await client.PUT('/api/v1/admin/acknowledgement-settings/tenant', { body: change })
  if (!response.ok || !data) {
    throw requestError(response, error, `Saving the settings failed with HTTP ${response.status}`)
  }
  return data
}

/** Removes the settings of a class (it inherits again) or the tenant default. */
export async function removeAcknowledgementSettings(
  client: ApiClient,
  classId: string | undefined,
): Promise<void> {
  const { error, response } = classId
    ? await client.DELETE('/api/v1/admin/acknowledgement-settings/classes/{classId}', {
        params: { path: { classId } },
      })
    : await client.DELETE('/api/v1/admin/acknowledgement-settings/tenant')
  if (!response.ok) {
    throw requestError(response, error, `Removing the settings failed with HTTP ${response.status}`)
  }
}
