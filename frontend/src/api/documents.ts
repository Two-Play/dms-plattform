import { queryOptions } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { requestError } from './errors'
import type { DocumentVersionView, DocumentView } from './partners'
import type { components } from './schema'
import type { MetadataValue } from './templates'

export type DocumentSummary = components['schemas']['DocumentSummaryView']
export type FieldValue = components['schemas']['FieldValueView']
export type WorkflowCase = components['schemas']['WorkflowCaseView']
export type WorkflowTask = components['schemas']['TaskView']
export type WorkflowVote = components['schemas']['VoteView']

/** The most recently changed documents the caller may read (GET /api/v1/documents). */
export function documentListQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['documents', 'list'],
    queryFn: async () => {
      const { data, error, response } = await client.GET('/api/v1/documents', {
        params: { query: { limit: 100 } },
      })
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the documents failed with HTTP ${response.status}`,
        )
      }
      return data
    },
  })
}

/** A document with its open version, or its latest readable version (GET /api/v1/documents/{id}). */
export function documentQuery(client: ApiClient, id: string) {
  return queryOptions({
    queryKey: ['documents', id],
    queryFn: async () => {
      const { data, error, response } = await client.GET('/api/v1/documents/{id}', {
        params: { path: { id } },
      })
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the document failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    retry: false,
  })
}

/** The review and approval cases of the document's readable versions (GET /api/v1/documents/{id}/workflow). */
export function documentWorkflowQuery(client: ApiClient, id: string) {
  return queryOptions({
    queryKey: ['documents', id, 'workflow'],
    queryFn: async () => {
      const { data, error, response } = await client.GET('/api/v1/documents/{id}/workflow', {
        params: { path: { id } },
      })
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the workflow failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    retry: false,
  })
}

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

/** Submits the draft for review and starts its workflow (POST /api/v1/documents/{id}/submit). */
export async function submitDocument(client: ApiClient, id: string): Promise<DocumentVersionView> {
  const { data, error, response } = await client.POST('/api/v1/documents/{id}/submit', {
    params: { path: { id } },
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Submitting failed with HTTP ${response.status}`)
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
