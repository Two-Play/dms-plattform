import { queryOptions } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { ApiRequestError, requestError } from './errors'
import type { components } from './schema'

/**
 * Acknowledgements (read confirmations) of published versions (DMS-47, ADR 0027). A reader opens
 * the version first ({@link markOpened}, recorded once), then acknowledges it ({@link acknowledge}).
 */

/** A version in the caller's acknowledgement overview, open or done. */
export type Acknowledgement = components['schemas']['AcknowledgementItemView']
/** A recorded acknowledgement with version and timestamp. */
export type AcknowledgementView = components['schemas']['AcknowledgementView']
/** What the caller did with one version: opened, acknowledged, and whether it asks for it. */
export type AcknowledgementState = components['schemas']['AcknowledgementStateView']

export type AcknowledgementStatus = 'open' | 'done'

/** Read confirmations of the caller (GET /api/v1/acknowledgements?status=…). */
export async function listAcknowledgements(
  client: ApiClient,
  status: AcknowledgementStatus = 'open',
): Promise<Acknowledgement[]> {
  const { data, error, response } = await client.GET('/api/v1/acknowledgements', {
    params: { query: { status } },
  })
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Loading the read confirmations failed with HTTP ${response.status}`,
    )
  }
  return data
}

/** Records that the caller opened the version (POST …/versions/{versionId}/opened); idempotent. */
export async function markOpened(
  client: ApiClient,
  documentId: string,
  versionId: string,
): Promise<void> {
  const { error, response } = await client.POST(
    '/api/v1/documents/{id}/versions/{versionId}/opened',
    { params: { path: { id: documentId, versionId } } },
  )
  if (!response.ok) {
    throw requestError(response, error, `Opening the version failed with HTTP ${response.status}`)
  }
}

/**
 * Confirms that the caller has read the version (POST …/versions/{versionId}/acknowledgement).
 * Fails with code `not_opened` before {@link markOpened} and `already_acknowledged` the second time.
 */
export async function acknowledge(
  client: ApiClient,
  documentId: string,
  versionId: string,
): Promise<AcknowledgementView> {
  const { data, error, response } = await client.POST(
    '/api/v1/documents/{id}/versions/{versionId}/acknowledgement',
    { params: { path: { id: documentId, versionId } } },
  )
  if (!response.ok || !data) {
    throw requestError(response, error, `Confirming failed with HTTP ${response.status}`)
  }
  return data
}

/**
 * Open read confirmations for the task inbox, or `null` when the backend does not offer them
 * (404), so the inbox can leave them out.
 */
export function openAcknowledgementsQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['acknowledgements', 'open'],
    queryFn: async (): Promise<Acknowledgement[] | null> => {
      try {
        return await listAcknowledgements(client, 'open')
      } catch (e) {
        if (e instanceof ApiRequestError && e.status === 404) return null
        throw e
      }
    },
    retry: false,
  })
}

/** The caller's state for one version (GET …/versions/{versionId}/acknowledgement). */
export function acknowledgementStateQuery(
  client: ApiClient,
  documentId: string,
  versionId: string,
) {
  return queryOptions({
    queryKey: ['acknowledgements', 'state', versionId],
    queryFn: async (): Promise<AcknowledgementState> => {
      const { data, error, response } = await client.GET(
        '/api/v1/documents/{id}/versions/{versionId}/acknowledgement',
        { params: { path: { id: documentId, versionId } } },
      )
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the read confirmation failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    retry: false,
  })
}
