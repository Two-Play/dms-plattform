import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { PublicationFailure, PublicationStatus } from '@/api/publications'
import { setLocale } from '@/i18n'
import { PublicationFailuresPanel, PublicationStatusNotice } from './publication-failures'

/** State, cause and retry of failed publications (DMS-142). */

vi.mock('@tanstack/react-router', () => ({
  Link: ({
    children,
    params,
    search,
  }: {
    children: ReactNode
    params: { documentId: string }
    search: { version: string }
  }) => <a href={`/documents/${params.documentId}?version=${search.version}`}>{children}</a>,
}))

const documentId = '7c1e2f0a-0000-4000-8000-0000000000d1'
const versionId = '7c1e2f0a-0000-4000-8000-0000000000e1'

const failed: PublicationStatus = {
  documentId,
  versionId,
  version: '2.0',
  state: 'Failed',
  attempts: 10,
  maxAttempts: 10,
  failedAt: '2026-10-29T11:00:00Z',
  lastError: { code: 'legal_hold', message: 'a legal hold is active' },
  canRetry: true,
}

const failure: PublicationFailure = { ...failed, title: 'Reiserichtlinie' }

function ok(data: unknown) {
  return { data, error: undefined, response: new Response(null, { status: 200 }) }
}

function renderWith(ui: ReactNode, load: () => unknown) {
  const get = vi.fn(() => Promise.resolve(load()))
  const post = vi.fn(() => Promise.resolve(ok({ ...failed, state: 'Pending', attempts: 0 })))
  const client = { GET: get, POST: post } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const view = render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>{ui}</ApiClientProvider>
    </QueryClientProvider>,
  )
  return { ...view, get, post, user: userEvent.setup() }
}

afterEach(() => {
  setLocale('en')
})

describe('Failed publication on the document page', () => {
  it('shows the cause and retries for those who may', async () => {
    const { post, user } = renderWith(
      <PublicationStatusNotice documentId={documentId} versionId={versionId} />,
      () => ok(failed),
    )
    const notice = await screen.findByRole('alert')
    expect(notice).toHaveTextContent('all 10 attempts failed')
    expect(notice).toHaveTextContent('A legal hold is active on the document.')
    await user.click(within(notice).getByRole('button', { name: 'Retry now' }))
    expect(post).toHaveBeenCalledWith(
      '/api/v1/documents/{id}/versions/{versionId}/publication/retry',
      { params: { path: { id: documentId, versionId } } },
    )
  })

  it('offers no retry to those who may not', async () => {
    renderWith(<PublicationStatusNotice documentId={documentId} versionId={versionId} />, () =>
      ok({ ...failed, canRetry: false }),
    )
    const notice = await screen.findByRole('alert')
    expect(within(notice).queryByRole('button')).not.toBeInTheDocument()
  })

  it('stays silent while the version simply waits for its date', async () => {
    const { get, container } = renderWith(
      <PublicationStatusNotice documentId={documentId} versionId={versionId} />,
      () => ok({ ...failed, state: 'Pending', lastError: undefined }),
    )
    await waitFor(() => {
      expect(get).toHaveBeenCalled()
    })
    expect(container).toBeEmptyDOMElement()
  })
})

describe('Failed publications for records management', () => {
  it('lists failed versions with cause and retry, hiding titles the caller is not cleared for', async () => {
    const { get, user, post } = renderWith(<PublicationFailuresPanel />, () =>
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
    )
    const table = await screen.findByRole('table', { name: 'Versions with failed publications' })
    expect(get).toHaveBeenCalledWith('/api/v1/publications/failures')
    expect(within(table).getByRole('link', { name: 'Reiserichtlinie' })).toHaveAttribute(
      'href',
      `/documents/${documentId}?version=${versionId}`,
    )
    expect(within(table).getByText('Confidential document')).toBeInTheDocument()
    expect(within(table).getByText('Being retried')).toBeInTheDocument()
    await user.click(within(table).getByRole('button', { name: 'Retry now' }))
    expect(post).toHaveBeenCalledTimes(1)
  })

  it('shows an empty state in German', async () => {
    setLocale('de')
    renderWith(<PublicationFailuresPanel />, () => ok([]))
    expect(
      await screen.findByText('Keine Veröffentlichung ist fehlgeschlagen.'),
    ).toBeInTheDocument()
  })
})
