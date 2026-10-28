import type { ApiClient } from './client'
import { requestError } from './errors'
import type { components } from './schema'
import { attachmentName } from './search'

/**
 * Evidence reports of a document (DMS-57, ADR 0034) for internal audit and records managers: the
 * complete history and integrity status, registered with the SHA-256 of its JSON form.
 */

/** A created report: registry entry, integrity status and where to download it. */
export type EvidenceReport = components['schemas']['EvidenceReportView']

export type EvidenceReportFormat = 'pdf' | 'json'

/** Creates the evidence report of a document (POST /api/v1/documents/{id}/evidence-report). */
export async function createEvidenceReport(
  client: ApiClient,
  documentId: string,
): Promise<EvidenceReport> {
  const { data, error, response } = await client.POST('/api/v1/documents/{id}/evidence-report', {
    params: { path: { id: documentId } },
  })
  if (!response.ok || !data) {
    throw requestError(
      response,
      error,
      `Creating the evidence report failed with HTTP ${response.status}`,
    )
  }
  return data
}

/**
 * Downloads a report as PDF/A or as its JSON form (the bytes the checksum is taken over). The
 * request needs the access token, so the file is fetched and handed to the browser as a blob.
 */
export async function downloadEvidenceReport(
  client: ApiClient,
  report: EvidenceReport,
  format: EvidenceReportFormat,
): Promise<void> {
  const { data, error, response } =
    format === 'pdf'
      ? await client.GET('/api/v1/documents/{id}/evidence-reports/{reportFile}', {
          params: { path: { id: report.documentId, reportFile: `${report.id}.pdf` } },
          parseAs: 'blob',
        })
      : await client.GET('/api/v1/documents/{id}/evidence-reports/{reportId}', {
          params: { path: { id: report.documentId, reportId: report.id } },
          parseAs: 'blob',
        })
  if (!response.ok || !data) {
    throw requestError(response, error, `The download failed with HTTP ${response.status}`)
  }
  const url = URL.createObjectURL(data)
  const link = document.createElement('a')
  link.href = url
  link.download = attachmentName(
    response.headers.get('Content-Disposition'),
    `evidence-report-${report.id}.${format}`,
  )
  document.body.append(link)
  link.click()
  link.remove()
  // Revoked after the click has been handled; revoking at once can cancel the download.
  setTimeout(() => {
    URL.revokeObjectURL(url)
  }, 0)
}
