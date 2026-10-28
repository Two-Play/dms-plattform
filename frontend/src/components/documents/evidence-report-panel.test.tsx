import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { EvidenceReport } from '@/api/evidence-reports'
import { EvidenceReportAction } from './evidence-report-panel'

const documentId = '7c1e2f0a-0000-4000-8000-0000000000d1'
const reportId = '7c1e2f0a-0000-4000-8000-0000000000e1'
const sha256 = 'ab'.repeat(32)

const report: EvidenceReport = {
  id: reportId,
  documentId,
  createdAt: '2026-10-28T09:00:00Z',
  createdBy: 'auditor-1',
  sha256,
  format: 'dms.evidence-report/1',
  integrityStatus: 'Violated',
  findings: ['Version 1.0: rendition checksum mismatch'],
  jsonUrl: `/api/v1/documents/${documentId}/evidence-reports/${reportId}`,
  pdfUrl: `/api/v1/documents/${documentId}/evidence-reports/${reportId}.pdf`,
}

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>

const ok = (data: unknown, status = 200, headers: Record<string, string> = {}): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status, headers }) })
const failed = (status: number, body: unknown): Reply =>
  Promise.resolve({ data: undefined, error: body, response: new Response(null, { status }) })

function renderWith(roles: string[], create: () => Reply = () => ok(report, 201)) {
  const get = vi.fn((p: string) => {
    if (p === '/api/v1/me') return ok({ userId: 'auditor-1', tenantId: 'tenant-1', roles })
    const name = p.endsWith('{reportFile}')
      ? `evidence-report-${reportId}.pdf`
      : `evidence-report-${reportId}.json`
    return ok(new Blob(['x']), 200, { 'Content-Disposition': `attachment; filename="${name}"` })
  })
  const post = vi.fn(create)
  const client = { GET: get, POST: post } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <EvidenceReportAction documentId={documentId} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { get, post, user: userEvent.setup() }
}

function captureDownloads(): string[] {
  Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:report'), revokeObjectURL: vi.fn() })
  const downloads: string[] = []
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
    this: HTMLAnchorElement,
  ) {
    downloads.push(this.download)
  })
  return downloads
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('Evidence report action (DMS-57)', () => {
  it('creates the report, downloads the PDF and offers the JSON form', async () => {
    const downloads = captureDownloads()
    const { get, post, user } = renderWith(['Auditor'])

    await user.click(await screen.findByRole('button', { name: 'Evidence report' }))

    expect(post).toHaveBeenCalledWith('/api/v1/documents/{id}/evidence-report', {
      params: { path: { id: documentId } },
    })
    await waitFor(() => {
      expect(downloads).toEqual([`evidence-report-${reportId}.pdf`])
    })
    expect(get).toHaveBeenCalledWith('/api/v1/documents/{id}/evidence-reports/{reportFile}', {
      params: { path: { id: documentId, reportFile: `${reportId}.pdf` } },
      parseAs: 'blob',
    })
    expect(screen.getByText('Integrity violated')).toBeInTheDocument()
    expect(screen.getByText(`SHA-256 ${sha256}`)).toBeInTheDocument()
    expect(screen.getByText('Version 1.0: rendition checksum mismatch')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Download JSON' }))
    await waitFor(() => {
      expect(downloads).toEqual([
        `evidence-report-${reportId}.pdf`,
        `evidence-report-${reportId}.json`,
      ])
    })
  })

  it('is offered to records managers too', async () => {
    renderWith(['RecordsManager'])
    expect(await screen.findByRole('button', { name: 'Evidence report' })).toBeInTheDocument()
  })

  it('is hidden for other roles', async () => {
    const { get } = renderWith(['Author', 'Reader'])
    await waitFor(() => {
      expect(get).toHaveBeenCalledWith('/api/v1/me')
    })
    expect(screen.queryByRole('button', { name: 'Evidence report' })).not.toBeInTheDocument()
  })

  it('shows why a report could not be created', async () => {
    captureDownloads()
    const { user } = renderWith(['Auditor'], () =>
      failed(403, { code: 'forbidden', message: 'You are not allowed to do this.' }),
    )
    await user.click(await screen.findByRole('button', { name: 'Evidence report' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('You are not allowed to do this.')
  })
})
