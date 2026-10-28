import { queryOptions } from '@tanstack/react-query'
import type { Client } from 'openapi-fetch'
import type { ApiClient } from './client'
import { ApiRequestError, requestError, type ApiErrorBody } from './errors'

/**
 * Read confirmations of published versions (DMS-47). The endpoints are not yet part of the generated
 * schema, so the types are written by hand after the agreed contract; once `schema.d.ts` contains
 * them, `AckPaths` and the cast in `ackClient` can be replaced by the generated `paths`.
 */
export interface Acknowledgement {
  documentId: string
  versionId: string
  title: string
  versionLabel: string
  classPath?: string[]
  /** Date (yyyy-mm-dd) from which the version is valid. */
  validFrom?: string
  publishedAt: string
  /** Whether the caller has opened the version. */
  opened: boolean
  acknowledgedAt?: string
}

export type AcknowledgementStatus = 'open' | 'done'

interface ErrorResponses {
  default: { headers: Record<string, unknown>; content: { 'application/json': ApiErrorBody } }
}

interface VersionPath {
  path: { id: string; versionId: string }
  query?: never
  header?: never
  cookie?: never
}

interface AckPaths {
  '/api/v1/acknowledgements': {
    parameters: { query?: never; header?: never; path?: never; cookie?: never }
    get: {
      parameters: {
        query?: { status?: AcknowledgementStatus }
        header?: never
        path?: never
        cookie?: never
      }
      requestBody?: never
      responses: {
        200: {
          headers: Record<string, unknown>
          content: { 'application/json': Acknowledgement[] }
        }
      } & ErrorResponses
    }
  }
  '/api/v1/documents/{id}/versions/{versionId}/opened': {
    parameters: { query?: never; header?: never; path?: never; cookie?: never }
    post: {
      parameters: VersionPath
      requestBody?: never
      responses: { 204: { headers: Record<string, unknown>; content?: never } } & ErrorResponses
    }
  }
  '/api/v1/documents/{id}/versions/{versionId}/acknowledgement': {
    parameters: { query?: never; header?: never; path?: never; cookie?: never }
    post: {
      parameters: VersionPath
      requestBody?: never
      responses: { 201: { headers: Record<string, unknown>; content?: never } } & ErrorResponses
    }
  }
}

/** The same client (with its auth middleware), typed for the hand-written paths. */
function ackClient(client: ApiClient): Client<AckPaths> {
  return client as unknown as Client<AckPaths>
}

/** Read confirmations of the caller (GET /api/v1/acknowledgements?status=…). */
export async function listAcknowledgements(
  client: ApiClient,
  status: AcknowledgementStatus = 'open',
): Promise<Acknowledgement[]> {
  const { data, error, response } = await ackClient(client).GET('/api/v1/acknowledgements', {
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

/** Records that the caller opened the version (POST …/versions/{versionId}/opened). */
export async function markOpened(
  client: ApiClient,
  documentId: string,
  versionId: string,
): Promise<void> {
  const { error, response } = await ackClient(client).POST(
    '/api/v1/documents/{id}/versions/{versionId}/opened',
    { params: { path: { id: documentId, versionId } } },
  )
  if (!response.ok) {
    throw requestError(response, error, `Opening the version failed with HTTP ${response.status}`)
  }
}

/** Confirms that the caller has read the version (POST …/versions/{versionId}/acknowledgement). */
export async function acknowledge(
  client: ApiClient,
  documentId: string,
  versionId: string,
): Promise<void> {
  const { error, response } = await ackClient(client).POST(
    '/api/v1/documents/{id}/versions/{versionId}/acknowledgement',
    { params: { path: { id: documentId, versionId } } },
  )
  if (!response.ok) {
    throw requestError(response, error, `Confirming failed with HTTP ${response.status}`)
  }
}

/**
 * Open read confirmations for the task inbox, or `null` when the backend does not offer them yet
 * (404: DMS-47 not deployed), so the inbox can leave them out.
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
