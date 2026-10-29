import { queryOptions } from '@tanstack/react-query'
import type { ApiClient } from './client'
import { requestError } from './errors'
import type { DocumentVersionView } from './partners'
import type { components } from './schema'
import { attachmentName } from './search'

/**
 * Reading and printing a version (DMS-112, ADR 0039): its rendered content as HTML, and its PDF – the
 * draft PDF of an open version, the archived rendition of an approved one, or a copy of an approved
 * version with the change history.
 */

export type VersionHtml = components['schemas']['VersionHtmlView']

/** Statuses of a version that is still worked on (draft, review, approval). */
const OPEN_STATUSES = new Set(['Draft', 'InReview', 'InApproval'])

export function isOpenVersion(version: Pick<DocumentVersionView, 'status'>): boolean {
  return OPEN_STATUSES.has(version.status)
}

/** Whether the version was approved: its content and values are frozen and it has an archive rendition. */
export function isApprovedVersion(version: Pick<DocumentVersionView, 'releasedAt'>): boolean {
  return version.releasedAt !== undefined
}

/**
 * The content of a version resolved with its stored values (GET …/versions/{versionId}/html); drafts carry
 * the draft marking, approved versions come from their frozen package.
 */
export function versionHtmlQuery(client: ApiClient, documentId: string, versionId: string) {
  return queryOptions({
    queryKey: ['documents', documentId, 'versions', versionId, 'html'],
    queryFn: async () => {
      const { data, error, response } = await client.GET(
        '/api/v1/documents/{id}/versions/{versionId}/html',
        { params: { path: { id: documentId, versionId } } },
      )
      if (!response.ok || !data) {
        throw requestError(
          response,
          error,
          `Loading the document content failed with HTTP ${response.status}`,
        )
      }
      return data
    },
    retry: false,
  })
}

/**
 * Which PDF a download gives: `draft` for an open version (on demand, marked "Draft – not valid"),
 * `rendition` for the archived PDF/A of an approved version, `copy` for an approved version with the
 * change history (on demand; the archived file is not altered). `undefined`: none (e.g. a withdrawn draft).
 */
export type PdfKind = 'draft' | 'rendition' | 'copy'

export function pdfKind(
  version: Pick<DocumentVersionView, 'status' | 'releasedAt' | 'rendition'>,
  changelog: boolean,
): PdfKind | undefined {
  if (isOpenVersion(version)) return 'draft'
  if (!isApprovedVersion(version)) return undefined
  if (changelog) return 'copy'
  return version.rendition ? 'rendition' : 'copy'
}

/** Downloads the PDF of a version; the request needs the access token, so it is handed over as a blob. */
export async function downloadVersionPdf(
  client: ApiClient,
  documentId: string,
  version: Pick<DocumentVersionView, 'id' | 'version' | 'status' | 'releasedAt' | 'rendition'>,
  changelog: boolean,
): Promise<void> {
  const kind = pdfKind(version, changelog)
  if (kind === undefined) throw new Error(`Version ${version.version} has no PDF.`)
  const path = { id: documentId, versionId: version.id }
  const query = { changelog }
  const { data, error, response } =
    kind === 'rendition'
      ? await client.GET('/api/v1/documents/{id}/versions/{versionId}/rendition', {
          params: { path },
          parseAs: 'blob',
        })
      : kind === 'draft'
        ? await client.GET('/api/v1/documents/{id}/versions/{versionId}/draft.pdf', {
            params: { path, query },
            parseAs: 'blob',
          })
        : await client.GET('/api/v1/documents/{id}/versions/{versionId}/copy.pdf', {
            params: { path, query },
            parseAs: 'blob',
          })
  if (!response.ok || !data) {
    throw requestError(response, error, `The download failed with HTTP ${response.status}`)
  }
  saveBlob(
    data,
    attachmentName(
      response.headers.get('Content-Disposition'),
      `${documentId}-${version.version}.pdf`,
    ),
  )
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
