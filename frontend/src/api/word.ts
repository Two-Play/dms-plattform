import { queryOptions } from '@tanstack/react-query'
import { config } from '@/config'
import type { ApiClient } from './client'
import { ApiRequestError, requestError } from './errors'
import type { components } from './schema'
import { attachmentName } from './search'

/**
 * File documents and Word templates (DMS-115, ADR 0042): the uploaded original of a version, the fields of
 * Word documents (content controls, `{{key}}` placeholders) with their values, the filled Word document and
 * a PDF preview; Word templates with their field definitions.
 */

export type WordTemplate = components['schemas']['WordTemplateView']
export type WordFieldDefinition = components['schemas']['WordFieldDefinition']
export type WordFields = components['schemas']['WordFieldsView']
export type WordField = components['schemas']['WordFieldView']
export type WordColumn = components['schemas']['WordColumnView']
export type WordColumnDefinition = components['schemas']['WordColumnDefinition']
export type VersionFile = components['schemas']['VersionFileView']
export type CreateWordDocument = components['schemas']['CreateWordDocument']

/**
 * Field types of manual fields (DMS-115, DMS-122). `Rows` belongs to repeating sections of the Word file only;
 * designers cannot choose it for other fields.
 */
export const WORD_FIELD_TYPES = ['Text', 'Date', 'Number', 'Checkbox', 'Choice', 'Rows'] as const
export type WordFieldType = (typeof WORD_FIELD_TYPES)[number]
/** Types a designer can choose for a field that is not a repeating section, and for columns of rows. */
export const WORD_SIMPLE_TYPES = ['Text', 'Date', 'Number', 'Checkbox', 'Choice'] as const
/** Where a field's value comes from. */
export const WORD_FIELD_SOURCES = ['Automatic', 'Manual'] as const

/** File name extensions the conversion worker turns into PDF/A (the backend refuses others). */
export const CONVERTIBLE_EXTENSIONS = [
  'doc',
  'docx',
  'docm',
  'dot',
  'dotx',
  'dotm',
  'odt',
  'ott',
  'rtf',
  'txt',
  'html',
  'htm',
  'xls',
  'xlsx',
  'xlsm',
  'xlt',
  'xltx',
  'ods',
  'ots',
  'csv',
  'ppt',
  'pptx',
  'pps',
  'ppsx',
  'pot',
  'potx',
  'odp',
  'otp',
  'odg',
  'otg',
  'vsd',
  'vsdx',
  'pdf',
] as const

/** Whether a file is a Word document (DOCX). */
export function isWordFile(name: string): boolean {
  return name.toLowerCase().endsWith('.docx')
}

/** Whether the fields of a file are filled: Word (DOCX), Excel (XLSX) and PowerPoint (PPTX), DMS-122. */
export function isFillableFile(name: string): boolean {
  return /\.(docx|xlsx|pptx)$/i.test(name)
}

/** Values of a check box field. */
export const CHECKED = 'true'
export const UNCHECKED = 'false'

/** A row of a `Rows` field: the value of each column by key. */
export type WordRow = Record<string, string>

/**
 * The rows of a `Rows` field from its value, a JSON array of objects by column key (DMS-122); an empty or
 * unreadable value has no rows.
 */
export function parseRows(value: string | undefined): WordRow[] {
  if (!value) return []
  try {
    const parsed = JSON.parse(value) as unknown
    if (!Array.isArray(parsed)) return []
    return parsed.map((row) => {
      const entries =
        typeof row === 'object' && row !== null
          ? Object.entries(row as Record<string, unknown>)
          : []
      return Object.fromEntries(
        entries.filter((e): e is [string, string] => typeof e[1] === 'string'),
      )
    })
  } catch {
    return []
  }
}

/** The value of a `Rows` field; empty without rows. Empty rows are kept while editing (the backend drops them). */
export function serializeRows(rows: WordRow[]): string {
  if (rows.length === 0) return ''
  return JSON.stringify(
    rows.map((row) => Object.fromEntries(Object.entries(row).filter(([, v]) => v !== ''))),
  )
}

/** Word templates of every status, for designers (GET /api/v1/admin/word-templates). */
export function wordTemplatesAdminQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['word-templates', 'admin'],
    queryFn: async () => {
      const { data, error, response } = await client.GET('/api/v1/admin/word-templates')
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading Word templates failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    retry: false,
  })
}

/** Released Word templates for authors (GET /api/v1/word-templates); empty if the backend has none. */
export function wordTemplateCatalogQuery(client: ApiClient) {
  return queryOptions({
    queryKey: ['word-templates', 'catalog'],
    queryFn: async () => {
      const { data, error, response } = await client.GET('/api/v1/word-templates')
      if (response.status === 404) return []
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading Word templates failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    retry: false,
  })
}

/** The Word file of a template version; `null` if the version is not a Word template. */
export function wordTemplateQuery(client: ApiClient, templateId: string, version: number) {
  return queryOptions({
    queryKey: ['word-templates', templateId, version],
    queryFn: async () => {
      const { data, error, response } = await client.GET(
        '/api/v1/admin/templates/{id}/versions/{version}/word',
        { params: { path: { id: templateId, version } } },
      )
      if (response.status === 404) return null
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the Word file failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    retry: false,
  })
}

/** Saves the field definitions of a draft Word template. */
export async function saveWordFieldDefinitions(
  client: ApiClient,
  templateId: string,
  version: number,
  fields: WordFieldDefinition[],
): Promise<WordTemplate> {
  const { data, error, response } = await client.PUT(
    '/api/v1/admin/templates/{id}/versions/{version}/word/fields',
    { params: { path: { id: templateId, version } }, body: { fields } },
  )
  if (!response.ok || !data) {
    throw requestError(response, error, `Saving the fields failed with HTTP ${response.status}`)
  }
  return data
}

/** Starts the next version of a Word template, taking its Word file over. */
export async function newWordTemplateVersion(
  client: ApiClient,
  templateId: string,
): Promise<WordTemplate> {
  const { data, error, response } = await client.POST(
    '/api/v1/admin/templates/{id}/word-versions',
    {
      params: { path: { id: templateId } },
    },
  )
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Creating a new version failed with HTTP ${response.status}`,
    )
  }
  return data
}

/** Creates a document from a released Word template (POST /api/v1/documents/word). */
export async function createWordDocument(client: ApiClient, body: CreateWordDocument) {
  const { data, error, response } = await client.POST('/api/v1/documents/word', { body })
  if (!response.ok || !data) {
    throw requestError(response, error, `Creating the document failed with HTTP ${response.status}`)
  }
  return data
}

/** The fields of a version's Word file with their values. */
export function wordFieldsQuery(client: ApiClient, documentId: string, versionId: string) {
  return queryOptions({
    queryKey: ['documents', documentId, 'versions', versionId, 'word-fields'],
    queryFn: async () => {
      const { data, error, response } = await client.GET(
        '/api/v1/documents/{id}/versions/{versionId}/word-fields',
        { params: { path: { id: documentId, versionId } } },
      )
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the fields failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    retry: false,
  })
}

/** Enters the values of the manual fields of the draft. */
export async function saveWordValues(
  client: ApiClient,
  documentId: string,
  values: Record<string, string>,
) {
  const { data, error, response } = await client.PUT('/api/v1/documents/{id}/word-fields', {
    params: { path: { id: documentId } },
    body: { values },
  })
  if (!response.ok || !data) {
    throw requestError(response, error, `Saving the fields failed with HTTP ${response.status}`)
  }
  return data
}

/** A PDF of a file version for reading in the browser (rendition or converted original). */
export async function fetchPreview(
  client: ApiClient,
  documentId: string,
  versionId: string,
): Promise<Blob> {
  const { data, error, response } = await client.GET(
    '/api/v1/documents/{id}/versions/{versionId}/preview.pdf',
    { params: { path: { id: documentId, versionId } }, parseAs: 'blob' },
  )
  if (!response.ok || !data) {
    throw requestError(response, error, `The preview failed with HTTP ${response.status}`)
  }
  return data
}

/** Downloads the original of a version unchanged. */
export async function downloadOriginal(
  client: ApiClient,
  documentId: string,
  file: Pick<VersionFile, 'versionId' | 'name'>,
): Promise<void> {
  const { data, error, response } = await client.GET(
    '/api/v1/documents/{id}/versions/{versionId}/file',
    { params: { path: { id: documentId, versionId: file.versionId } }, parseAs: 'blob' },
  )
  if (!response.ok || !data) {
    throw requestError(response, error, `The download failed with HTTP ${response.status}`)
  }
  saveBlob(data, file.name)
}

/** Downloads the Word, Excel or PowerPoint file with its fields filled (DMS-122: `…/filled`). */
export async function downloadFilled(
  client: ApiClient,
  documentId: string,
  file: Pick<VersionFile, 'versionId' | 'name'>,
): Promise<void> {
  const { data, error, response } = await client.GET(
    '/api/v1/documents/{id}/versions/{versionId}/filled',
    { params: { path: { id: documentId, versionId: file.versionId } }, parseAs: 'blob' },
  )
  if (!response.ok || !data) {
    throw requestError(response, error, `The download failed with HTTP ${response.status}`)
  }
  saveBlob(data, attachmentName(response.headers.get('Content-Disposition'), file.name))
}

/** Downloads the Word file of a template version. */
export async function downloadTemplateWord(
  client: ApiClient,
  template: Pick<WordTemplate, 'templateId' | 'version' | 'name'>,
): Promise<void> {
  const { data, error, response } = await client.GET(
    '/api/v1/admin/templates/{id}/versions/{version}/word/file',
    { params: { path: { id: template.templateId, version: template.version } }, parseAs: 'blob' },
  )
  if (!response.ok || !data) {
    throw requestError(response, error, `The download failed with HTTP ${response.status}`)
  }
  saveBlob(data, template.name)
}

function saveBlob(blob: Blob, name: string): void {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = name
  document.body.append(link)
  link.click()
  link.remove()
  // Revoked after the click has been handled; revoking at once can cancel the download.
  setTimeout(() => {
    URL.revokeObjectURL(url)
  }, 0)
}

/** Uploads a file with PUT as multipart/form-data and reports the bytes sent so far. */
export type UploadFile<T> = (
  file: File,
  onProgress: (sent: number, total: number) => void,
) => Promise<T>

/**
 * PUT `path` as multipart/form-data with one part `file`. XMLHttpRequest instead of fetch for the upload
 * progress, like attachment uploads.
 */
export async function putFile<T>(
  path: string,
  file: File,
  onProgress: (sent: number, total: number) => void,
): Promise<T> {
  // Loaded on use: the OIDC module needs the client configuration, which tests do not provide.
  const { getAccessToken } = await import('@/auth/oidc')
  const token = await getAccessToken()
  const form = new FormData()
  form.append('file', new Blob([file], { type: 'application/octet-stream' }), file.name)
  return new Promise<T>((resolve, reject) => {
    const request = new XMLHttpRequest()
    request.open('PUT', `${config.apiBaseUrl}${path}`)
    if (token) request.setRequestHeader('Authorization', `Bearer ${token}`)
    request.upload.onprogress = (event) => {
      onProgress(event.loaded, event.lengthComputable ? event.total : file.size)
    }
    request.onload = () => {
      let body: unknown = undefined
      try {
        body = JSON.parse(request.responseText) as unknown
      } catch {
        // not JSON, e.g. a proxy error page
      }
      if (request.status === 200 && body) {
        resolve(body as T)
      } else {
        const error =
          typeof body === 'object' && body !== null
            ? (body as { code?: unknown; message?: unknown })
            : undefined
        reject(
          new ApiRequestError(
            request.status,
            typeof error?.message === 'string'
              ? error.message
              : `The upload failed with HTTP ${String(request.status)}`,
            typeof error?.code === 'string' ? error.code : undefined,
          ),
        )
      }
    }
    request.onerror = () => {
      reject(new TypeError('Failed to fetch'))
    }
    request.send(form)
  })
}

/** Uploads the original of a file document into its draft. */
export function uploadOriginal(
  documentId: string,
): UploadFile<components['schemas']['DocumentVersionView']> {
  return (file, onProgress) => putFile(`/api/v1/documents/${documentId}/file`, file, onProgress)
}

/** Uploads the Word file of a draft template version. */
export function uploadTemplateWord(templateId: string, version: number): UploadFile<WordTemplate> {
  return (file, onProgress) =>
    putFile(
      `/api/v1/admin/templates/${templateId}/versions/${String(version)}/word`,
      file,
      onProgress,
    )
}
