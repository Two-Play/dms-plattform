import { queryOptions } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { requestError } from './errors'
import type { components } from './schema'

/**
 * Uploading a finished file and deciding at the end what it becomes (DMS-127, ADR 0049): the file is
 * analysed and previewed without being stored; as document it is created from the upload template of the
 * chosen class, as template it becomes a Word template with the four-eyes release.
 */

export type UploadAnalysis = components['schemas']['UploadAnalysisView']
export type UploadTemplate = components['schemas']['UploadTemplateView']
export type UploadClass = components['schemas']['TemplateClassView']
export type TemplateFromVersion = components['schemas']['TemplateFromVersion']

/** A multipart form with the text parts first and the part `file` last (the backend reads them in order). */
function formOf(file: File, fields: Record<string, string> = {}): FormData {
  const form = new FormData()
  for (const [name, value] of Object.entries(fields)) form.append(name, value)
  form.append('file', new Blob([file], { type: 'application/octet-stream' }), file.name)
  return form
}

/** Sends `FormData` as it is: the browser sets the multipart boundary. */
const asForm = (body: unknown) => body as FormData

/** The classes the caller may create documents in (GET /api/v1/uploads/classes). */
export function uploadClassesQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['uploads', 'classes'],
    queryFn: async () => {
      const { data, error, response } = await client.GET('/api/v1/uploads/classes')
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the document classes failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    retry: false,
  })
}

/** The fields of a file with the values it carries; nothing is stored (POST /api/v1/uploads/fields). */
export async function analyseUpload(
  client: ApiClient,
  file: File,
  language: string,
): Promise<UploadAnalysis> {
  const { data, error, response } = await client.POST('/api/v1/uploads/fields', {
    params: { query: { language } },
    body: formOf(file) as never,
    bodySerializer: asForm,
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Reading the file failed with HTTP ${response.status}`)
  }
  return data
}

/** A PDF of the file with the entered values filled in, marked as preview (POST /api/v1/uploads/preview.pdf). */
export async function previewUpload(
  client: ApiClient,
  file: File,
  values: Record<string, string>,
  language: string,
): Promise<Blob> {
  const { data, error, response } = await client.POST('/api/v1/uploads/preview.pdf', {
    params: { query: { language } },
    body: formOf(file, { values: JSON.stringify(values) }) as never,
    bodySerializer: asForm,
    parseAs: 'blob',
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `The preview failed with HTTP ${response.status}`)
  }
  return data
}

/** The upload template of a class, created on demand (POST /api/v1/uploads/classes/{classId}/template). */
export async function uploadTemplateOf(
  client: ApiClient,
  classId: string,
): Promise<UploadTemplate> {
  const { data, error, response } = await client.POST(
    '/api/v1/uploads/classes/{classId}/template',
    { params: { path: { classId } } },
  )
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Preparing the document class failed with HTTP ${response.status}`,
    )
  }
  return data
}

/** "Use as template": a new Word template draft from the original of a version, without its values. */
export async function templateFromVersion(
  client: ApiClient,
  documentId: string,
  versionId: string,
  body: TemplateFromVersion,
) {
  const { data, error, response } = await client.POST(
    '/api/v1/documents/{id}/versions/{versionId}/word-template',
    { params: { path: { id: documentId, versionId } }, body },
  )
  if (!response.ok || !data) {
    throw requestError(response, error, `Creating the template failed with HTTP ${response.status}`)
  }
  return data
}
