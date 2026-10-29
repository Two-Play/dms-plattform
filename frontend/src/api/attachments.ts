import { queryOptions } from '@tanstack/react-query'
import { config } from '@/config'
import type { ApiClient } from './client'
import { ApiRequestError, requestError } from './errors'
import type { components } from './schema'

/**
 * Attachments of document versions (DMS-114, ADR 0041): files added to a draft, reviewed, approved
 * and archived together with it, and taken over by the next version.
 */

export type Attachment = components['schemas']['AttachmentView']
export type VersionAttachments = components['schemas']['AttachmentsView']

/** The attachments of a version (GET /api/v1/documents/{id}/versions/{versionId}/attachments). */
export function attachmentsQuery(client: ApiClient, documentId: string, versionId: string) {
  return queryOptions({
    queryKey: ['documents', documentId, 'versions', versionId, 'attachments'],
    queryFn: async () => {
      const { data, error, response } = await client.GET(
        '/api/v1/documents/{id}/versions/{versionId}/attachments',
        { params: { path: { id: documentId, versionId } } },
      )
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the attachments failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    retry: false,
  })
}

/** Renames an attachment of the draft (PATCH /api/v1/documents/{id}/attachments/{attachmentId}). */
export async function renameAttachment(
  client: ApiClient,
  documentId: string,
  attachmentId: string,
  name: string,
): Promise<Attachment> {
  const { data, error, response } = await client.PATCH(
    '/api/v1/documents/{id}/attachments/{attachmentId}',
    { params: { path: { id: documentId, attachmentId } }, body: { name } },
  )
  if (!response.ok || !data) {
    throw requestError(response, error, `Renaming failed with HTTP ${response.status}`)
  }
  return data
}

/** Removes an attachment from the draft (DELETE /api/v1/documents/{id}/attachments/{attachmentId}). */
export async function removeAttachment(
  client: ApiClient,
  documentId: string,
  attachmentId: string,
): Promise<void> {
  const { error, response } = await client.DELETE(
    '/api/v1/documents/{id}/attachments/{attachmentId}',
    { params: { path: { id: documentId, attachmentId } } },
  )
  if (!response.ok) {
    throw requestError(response, error, `Removing failed with HTTP ${response.status}`)
  }
}

/**
 * Downloads an attachment. The request needs the access token, so the file is fetched and handed
 * to the browser as a blob under its name.
 */
export async function downloadAttachment(
  client: ApiClient,
  documentId: string,
  attachment: Attachment,
): Promise<void> {
  const { data, error, response } = await client.GET(
    '/api/v1/documents/{id}/attachments/{attachmentId}',
    { params: { path: { id: documentId, attachmentId: attachment.id } }, parseAs: 'blob' },
  )
  if (!response.ok || !data) {
    throw requestError(response, error, `The download failed with HTTP ${response.status}`)
  }
  const url = URL.createObjectURL(data)
  const link = document.createElement('a')
  link.href = url
  link.download = attachment.name
  document.body.append(link)
  link.click()
  link.remove()
  // Revoked after the click has been handled; revoking at once can cancel the download.
  setTimeout(() => {
    URL.revokeObjectURL(url)
  }, 0)
}

/** Uploads a file to the draft of a document and reports the bytes sent so far. */
export type UploadAttachment = (
  documentId: string,
  file: File,
  onProgress: (sent: number, total: number) => void,
) => Promise<Attachment>

/**
 * POST /api/v1/documents/{id}/attachments as multipart/form-data. Uses XMLHttpRequest instead of
 * fetch for the upload progress. The file is sent as `application/octet-stream`; the backend takes
 * the media type from the file name.
 */
export const uploadAttachment: UploadAttachment = async (documentId, file, onProgress) => {
  // Loaded on use: the OIDC module needs the client configuration, which tests do not provide.
  const { getAccessToken } = await import('@/auth/oidc')
  const token = await getAccessToken()
  const form = new FormData()
  form.append('file', new Blob([file], { type: 'application/octet-stream' }), file.name)
  return new Promise<Attachment>((resolve, reject) => {
    const request = new XMLHttpRequest()
    request.open('POST', `${config.apiBaseUrl}/api/v1/documents/${documentId}/attachments`)
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
      if (request.status === 201 && body) {
        resolve(body as Attachment)
      } else {
        const error = errorOf(body)
        reject(
          new ApiRequestError(
            request.status,
            error?.message ?? `The upload failed with HTTP ${String(request.status)}`,
            error?.code,
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

function errorOf(body: unknown): { code: string; message: string } | undefined {
  if (typeof body !== 'object' || body === null) return undefined
  const { code, message } = body as Record<string, unknown>
  return typeof code === 'string' && typeof message === 'string' ? { code, message } : undefined
}

/** File name extensions the backend refuses by default (executables and scripts, ADR 0041). */
export const BLOCKED_EXTENSIONS = new Set([
  'exe',
  'com',
  'bat',
  'cmd',
  'scr',
  'pif',
  'msi',
  'msp',
  'msc',
  'dll',
  'cpl',
  'sys',
  'drv',
  'ocx',
  'vbs',
  'vbe',
  'js',
  'jse',
  'wsf',
  'wsh',
  'ws',
  'ps1',
  'psm1',
  'psd1',
  'hta',
  'lnk',
  'reg',
  'inf',
  'scf',
  'jar',
  'class',
  'sh',
  'bash',
  'zsh',
  'csh',
  'ksh',
  'command',
  'app',
  'dmg',
  'pkg',
  'deb',
  'rpm',
  'apk',
  'appimage',
  'run',
  'bin',
  'elf',
  'so',
  'dylib',
  'vb',
  'gadget',
  'application',
  'appref-ms',
  'xbap',
])

/** The lowercase extension of a file name without the dot, if any. */
export function extensionOf(name: string): string | undefined {
  const dot = name.lastIndexOf('.')
  return dot > 0 && dot < name.length - 1 ? name.slice(dot + 1).toLowerCase() : undefined
}

/** Kinds of files, for the icon of an attachment. */
export type FileKind = 'pdf' | 'image' | 'spreadsheet' | 'text' | 'archive' | 'other'

export function fileKind(contentType: string, name: string): FileKind {
  const extension = extensionOf(name) ?? ''
  if (contentType === 'application/pdf') return 'pdf'
  if (contentType.startsWith('image/')) return 'image'
  if (
    contentType.includes('spreadsheet') ||
    contentType.includes('excel') ||
    contentType === 'text/csv' ||
    ['xlsx', 'xls', 'ods', 'csv'].includes(extension)
  )
    return 'spreadsheet'
  if (
    contentType.startsWith('text/') ||
    contentType.includes('word') ||
    contentType.includes('opendocument.text') ||
    ['docx', 'doc', 'odt', 'rtf', 'txt', 'md'].includes(extension)
  )
    return 'text'
  if (contentType.includes('zip') || ['zip', '7z', 'tar', 'gz'].includes(extension))
    return 'archive'
  return 'other'
}
