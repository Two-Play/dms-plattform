import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
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

const earlierId = '7c1e2f0a-0000-4000-8000-0000000000e2'
const removedId = '7c1e2f0a-0000-4000-8000-0000000000e3'

const earlier = {
  id: earlierId,
  documentId,
  createdAt: '2026-09-01T09:00:00Z',
  createdBy: 'auditor-1',
  sha256,
  format: 'dms.evidence-report/2',
  integrityStatus: 'Intact',
  jsonUrl: `/api/v1/documents/${documentId}/evidence-reports/${earlierId}`,
  pdfUrl: `/api/v1/documents/${documentId}/evidence-reports/${earlierId}.pdf`,
}

const removed = {
  id: removedId,
  documentId,
  createdAt: '2010-09-01T09:00:00Z',
  createdBy: 'auditor-1',
  sha256,
  format: 'dms.evidence-report/1',
  removedAt: '2026-09-01T02:00:00Z',
}

const noReports = () => ok({ items: [], total: 0 })

function renderWith(
  roles: string[],
  create: () => Reply = () => ok(report, 201),
  list: (offset: number) => Reply = noReports,
) {
  const get = vi.fn((p: string, init?: { params?: { query?: { offset?: number } } }) => {
    if (p === '/api/v1/me') return ok({ userId: 'auditor-1', tenantId: 'tenant-1', roles })
    if (p === '/api/v1/documents/{id}/evidence-reports') {
      return list(init?.params?.query?.offset ?? 0)
    }
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

  it('lists earlier reports with their files, and what is left of a removed one', async () => {
    const downloads = captureDownloads()
    const { user } = renderWith(['Auditor'], undefined, () =>
      ok({ items: [earlier, removed], total: 2 }),
    )

    const list = await screen.findByRole('region', { name: 'Earlier reports' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(2)
    expect(within(list).getAllByText(`SHA-256 ${sha256}`)).toHaveLength(2)
    expect(within(list).getByText('Integrity intact')).toBeInTheDocument()
    expect(within(list).getByText(/Content removed on/)).toBeInTheDocument()
    // Only the report that still has its content can be downloaded.
    expect(within(list).getAllByRole('button')).toHaveLength(2)

    await user.click(within(list).getByRole('button', { name: /as PDF$/ }))
    await waitFor(() => {
      expect(downloads).toEqual([`evidence-report-${reportId}.pdf`])
    })
  })

  it('says so when there is no report yet', async () => {
    renderWith(['RecordsManager'])
    expect(
      await screen.findByText('No report has been created for this document yet.'),
    ).toBeInTheDocument()
  })

  it('loads more reports page by page', async () => {
    const { get, user } = renderWith(['Auditor'], undefined, (offset) =>
      offset === 0
        ? ok({ items: [earlier], total: 2, nextOffset: 1 })
        : ok({ items: [removed], total: 2 }),
    )

    await user.click(await screen.findByRole('button', { name: 'Show more' }))

    expect(await screen.findByText(/Content removed on/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Show more' })).not.toBeInTheDocument()
    expect(get).toHaveBeenCalledWith('/api/v1/documents/{id}/evidence-reports', {
      params: { path: { id: documentId }, query: { limit: 20, offset: 1 } },
    })
  })

  it('offers a retry when the list cannot be loaded', async () => {
    let attempts = 0
    const { user } = renderWith(['Auditor'], undefined, () => {
      attempts += 1
      return attempts === 1
        ? failed(500, { code: 'internal', message: 'Boom' })
        : ok({ items: [earlier], total: 1 })
    })

    await user.click(await screen.findByRole('button', { name: 'Try again' }))

    expect(await screen.findByText('Integrity intact')).toBeInTheDocument()
  })

  it('leaves the list out when the backend refuses it', async () => {
    renderWith(['Auditor'], undefined, () => failed(403, { code: 'forbidden', message: 'No.' }))

    expect(await screen.findByRole('button', { name: 'Evidence report' })).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.queryByRole('status')).not.toBeInTheDocument()
    })
    expect(screen.queryByRole('region', { name: 'Earlier reports' })).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows a new report in the list', async () => {
    captureDownloads()
    let created = false
    const { user } = renderWith(
      ['Auditor'],
      () => {
        created = true
        return ok(report, 201)
      },
      () => ok(created ? { items: [earlier], total: 1 } : { items: [], total: 0 }),
    )

    await user.click(await screen.findByRole('button', { name: 'Evidence report' }))

    const list = await screen.findByRole('region', { name: 'Earlier reports' })
    expect(await within(list).findByText('Integrity intact')).toBeInTheDocument()
  })
})
