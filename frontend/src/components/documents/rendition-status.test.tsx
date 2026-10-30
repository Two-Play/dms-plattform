import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { RenditionFailure, RenditionStatus } from '@/api/renditions'
import { setLocale } from '@/i18n'
import { getToasts } from '@/lib/toast'
import { RenditionFailuresPanel } from './rendition-failures-panel'
import { RenditionStatusNotice } from './rendition-status'

/** State, cause and retry of failed archive renditions (DMS-93). */

vi.mock('@tanstack/react-router', () => ({
  Link: ({ children, params }: { children: ReactNode; params: { documentId: string } }) => (
    <a href={`/documents/${params.documentId}`}>{children}</a>
  ),
}))

const documentId = '7c1e2f0a-0000-4000-8000-0000000000d1'
const versionId = '7c1e2f0a-0000-4000-8000-0000000000e1'

const failed: RenditionStatus = {
  documentId,
  versionId,
  version: '1.0',
  state: 'Failed',
  attempts: 10,
  maxAttempts: 10,
  requestedAt: '2026-10-29T09:00:00Z',
  failedAt: '2026-10-29T11:00:00Z',
  lastError: { code: 'unavailable', message: 'rendering unavailable: connection refused' },
  canRetry: true,
}

const retrying: RenditionStatus = {
  ...failed,
  state: 'Retrying',
  attempts: 2,
  failedAt: undefined,
  nextAttemptAt: '2026-10-29T09:01:00Z',
  lastError: { code: 'not_archivable', message: 'the PDF is not valid PDF/A-2b: font' },
  canRetry: false,
}

const failure: RenditionFailure = {
  documentId,
  versionId,
  version: '1.0',
  title: 'Reiserichtlinie',
  state: 'Failed',
  attempts: 10,
  maxAttempts: 10,
  requestedAt: '2026-10-29T09:00:00Z',
  failedAt: '2026-10-29T11:00:00Z',
  lastError: { code: 'unavailable', message: 'rendering unavailable: connection refused' },
  canRetry: true,
}

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>
const ok = (data: unknown, status = 200): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status }) })
const refused = (status: number, body: unknown): Reply =>
  Promise.resolve({ data: undefined, error: body, response: new Response(null, { status }) })

function renderWith(
  ui: ReactNode,
  {
    load = () => ok(failed),
    retry = () => ok({ ...failed, state: 'Pending', attempts: 0, canRetry: false }),
  }: { load?: () => Reply; retry?: () => Reply } = {},
) {
  const get = vi.fn(load)
  const post = vi.fn(retry)
  const client = { GET: get, POST: post } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>{ui}</ApiClientProvider>
    </QueryClientProvider>,
  )
  return { get, post, user: userEvent.setup() }
}

afterEach(() => {
  setLocale('en')
  vi.restoreAllMocks()
})

describe('Rendition status on the document page (DMS-93)', () => {
  it('explains a failed rendition and retries it', async () => {
    const { get, post, user } = renderWith(
      <RenditionStatusNotice documentId={documentId} versionId={versionId} />,
    )
    const notice = await screen.findByRole('alert')
    expect(notice).toHaveTextContent('all 10 attempts failed')
    expect(notice).toHaveTextContent(
      'The rendering or conversion service or the object storage could not be reached.',
    )
    expect(
      within(notice).getByText('rendering unavailable: connection refused'),
    ).toBeInTheDocument()
    expect(get).toHaveBeenCalledWith(
      '/api/v1/documents/{id}/versions/{versionId}/rendition/status',
      { params: { path: { id: documentId, versionId } } },
    )
    await user.click(within(notice).getByRole('button', { name: 'Retry now' }))
    expect(post).toHaveBeenCalledWith(
      '/api/v1/documents/{id}/versions/{versionId}/rendition/retry',
      { params: { path: { id: documentId, versionId } } },
    )
    await waitFor(() => {
      expect(getToasts().map((t) => t.message)).toContain(
        'The rendering of version 1.0 was requeued.',
      )
    })
  })

  it('shows an automatic retry without a button for callers who may not retry', async () => {
    renderWith(<RenditionStatusNotice documentId={documentId} versionId={versionId} />, {
      load: () => ok(retrying),
    })
    const notice = await screen.findByTestId('rendition-status')
    expect(notice).toHaveAttribute('role', 'status')
    expect(notice).toHaveTextContent('attempt 2 of 10')
    expect(notice).toHaveTextContent('archive standard (PDF/A-2b)')
    expect(within(notice).queryByRole('button')).not.toBeInTheDocument()
  })

  it('shows a pending rendition as a plain note', async () => {
    renderWith(<RenditionStatusNotice documentId={documentId} versionId={versionId} />, {
      load: () => ok({ ...failed, state: 'Pending', attempts: 0, lastError: undefined }),
    })
    expect(
      await screen.findByText(
        'The archive PDF is being produced; the version is approved afterwards.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('explains a refused retry', async () => {
    const { user } = renderWith(
      <RenditionStatusNotice documentId={documentId} versionId={versionId} />,
      { retry: () => refused(409, { code: 'rendition_not_failed', message: 'already done' }) },
    )
    await user.click(await screen.findByRole('button', { name: 'Retry now' }))
    await waitFor(() => {
      expect(getToasts().map((t) => t.message)).toContain(
        'The rendering cannot be retried: already done',
      )
    })
  })

  it('speaks German', async () => {
    setLocale('de')
    renderWith(<RenditionStatusNotice documentId={documentId} versionId={versionId} />)
    const notice = await screen.findByRole('alert')
    expect(notice).toHaveTextContent('alle 10 Versuche sind fehlgeschlagen')
    expect(notice).toHaveTextContent('Ursache:')
    expect(within(notice).getByRole('button', { name: 'Jetzt erneut versuchen' })).toBeEnabled()
  })
})

describe('Failed renditions for records management (DMS-93)', () => {
  it('lists failed versions with cause and retry, hiding titles the caller is not cleared for', async () => {
    const { get, post, user } = renderWith(<RenditionFailuresPanel />, {
      load: () =>
        ok([
          failure,
          {
            ...failure,
            versionId: '7c1e2f0a-0000-4000-8000-0000000000e2',
            title: undefined,
            state: 'Retrying',
            failedAt: undefined,
            nextAttemptAt: '2026-10-29T12:00:00Z',
            canRetry: false,
          },
        ]),
    })
    const table = await screen.findByRole('table', { name: 'Versions with failed archive PDFs' })
    expect(get).toHaveBeenCalledWith('/api/v1/renditions/failures')
    expect(within(table).getByRole('link', { name: 'Reiserichtlinie' })).toHaveAttribute(
      'href',
      `/documents/${documentId}`,
    )
    expect(within(table).getByText('Confidential document')).toBeInTheDocument()
    expect(within(table).getByText('Being retried')).toBeInTheDocument()
    expect(within(table).getAllByRole('button', { name: 'Retry now' })).toHaveLength(1)
    await user.click(within(table).getByRole('button', { name: 'Retry now' }))
    expect(post).toHaveBeenCalledTimes(1)
  })

  it('shows an empty state in German', async () => {
    setLocale('de')
    renderWith(<RenditionFailuresPanel />, { load: () => ok([]) })
    expect(await screen.findByText('Kein Archiv-PDF ist fehlgeschlagen.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Fehlgeschlagene Archiv-PDFs' })).toBeInTheDocument()
  })
})
