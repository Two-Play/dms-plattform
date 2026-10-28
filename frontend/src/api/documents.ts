import type { ApiClient } from './client'
import { requestError, type DocumentVersionView, type DocumentView } from './partners'
import type { MetadataValue } from './templates'

/** Creates a document as draft 1.0 from a released template version (POST /api/v1/documents). */
export async function createDocument(
  client: ApiClient,
  body: {
    templateId: string
    templateVersion: number
    title: string
    values: Record<string, MetadataValue>
  },
): Promise<DocumentView> {
  const { data, error, response } = await client.POST('/api/v1/documents', { body })
  if (!response.ok || !data) {
    throw requestError(response, error, `Saving the draft failed with HTTP ${response.status}`)
  }
  return data
}

/** Saves the entered values as the next working state (PUT /api/v1/documents/{id}/draft). */
export async function updateDraft(
  client: ApiClient,
  id: string,
  body: { title: string; values: Record<string, MetadataValue> },
): Promise<DocumentVersionView> {
  const { data, error, response } = await client.PUT('/api/v1/documents/{id}/draft', {
    params: { path: { id } },
    body,
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Saving the draft failed with HTTP ${response.status}`)
  }
  return data
}

/** Takes the picked entry over into the draft as system values. */
export async function applyReference(
  client: ApiClient,
  id: string,
  key: string,
  entryId: string,
): Promise<DocumentVersionView> {
  const { data, error, response } = await client.PUT('/api/v1/documents/{id}/references/{key}', {
    params: { path: { id, key } },
    body: { entryId },
  })
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Taking the partner over failed with HTTP ${response.status}`,
    )
  }
  return data
}

/** Removes the values taken over for a reference field from the draft. */
export async function removeReference(
  client: ApiClient,
  id: string,
  key: string,
): Promise<DocumentVersionView> {
  const { data, error, response } = await client.DELETE('/api/v1/documents/{id}/references/{key}', {
    params: { path: { id, key } },
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Removing the partner failed with HTTP ${response.status}`)
  }
  return data
}
