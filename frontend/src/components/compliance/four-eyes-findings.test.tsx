import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { FourEyesFinding } from '@/api/four-eyes-report'
import { setLocale } from '@/i18n'
import { FourEyesFindings } from './four-eyes-findings'

/** The retroactive check of the four-eyes rule in the compliance cockpit (DMS-163, ADR 0078). */

vi.mock('@tanstack/react-router', () => ({
  Link: ({ children, params }: { children: ReactNode; params: { documentId: string } }) => (
    <a href={`/documents/${params.documentId}`}>{children}</a>
  ),
}))

const finding: FourEyesFinding = {
  documentId: 'd1',
  versionId: 'v1',
  title: 'Travel policy',
  versionLabel: '2.0',
  versionStatus: 'Published',
  classPath: ['policy'],
  kind: 'Approval',
  decidedBy: 'approver-1',
  editor: 'approver-1',
  decidedAt: '2026-10-29T10:00:00Z',
  firstChangedAt: '2026-10-20T10:00:00Z',
  caseOpen: false,
}

function renderWith(findings: FourEyesFinding[]) {
  const get = vi.fn((path: string) =>
    Promise.resolve({
      data: path === '/api/v1/users' ? [] : findings,
      error: undefined,
      response: new Response(null, { status: 200 }),
    }),
  )
  const post = vi.fn(() =>
    Promise.resolve({
      data: undefined,
      error: { message: 'refused' },
      response: new Response(null, { status: 403 }),
    }),
  )
  const client = { GET: get, POST: post } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <FourEyesFindings />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { get, post, user: userEvent.setup() }
}

afterEach(() => {
  setLocale('en')
})

describe('Four-eyes findings in the cockpit (DMS-163)', () => {
  it('asks only when opened: reading the report is audited', async () => {
    const { get, user } = renderWith([finding])

    expect(get).not.toHaveBeenCalledWith('/api/v1/compliance/four-eyes-findings')
    await user.click(screen.getByRole('button', { name: 'Show the findings' }))

    expect(await screen.findByRole('link', { name: 'Travel policy' })).toBeInTheDocument()
    expect(get).toHaveBeenCalledWith('/api/v1/compliance/four-eyes-findings')
  })

  it('tells approvals from votes of a review that still runs, and names a deputy', async () => {
    const vote: FourEyesFinding = {
      ...finding,
      versionId: 'v2',
      kind: 'ReviewVote',
      decidedBy: 'deputy-1',
      actingFor: 'reviewer-1',
      editor: 'reviewer-1',
      step: 'review',
      round: 1,
      caseOpen: true,
    }
    const { user } = renderWith([finding, vote])

    await user.click(screen.getByRole('button', { name: 'Show the findings' }))

    const rows = await screen.findAllByRole('row')
    expect(rows).toHaveLength(3)
    const [, approval, running] = rows as [HTMLElement, HTMLElement, HTMLElement]
    expect(within(approval).getByText('Approval')).toBeInTheDocument()
    expect(within(running).getByText('Review vote (review still running)')).toBeInTheDocument()
    expect(running).toHaveTextContent('through deputy')
  })

  it('exports the findings through the backend, which records it', async () => {
    const { post, user } = renderWith([finding])
    await user.click(screen.getByRole('button', { name: 'Show the findings' }))

    await user.click(
      await screen.findByRole('button', {
        name: 'Export Four-eyes rule: retroactive check as CSV',
      }),
    )

    expect(post).toHaveBeenCalledWith('/api/v1/compliance/exports/{list}', {
      params: {
        path: { list: 'four-eyes-findings' },
        query: { unit: undefined, owner: undefined, document: undefined, lang: 'en' },
      },
      parseAs: 'blob',
    })
  })

  it('says so when nothing was found, in German too', async () => {
    setLocale('de')
    const { user } = renderWith([])

    await user.click(screen.getByRole('button', { name: 'Befunde anzeigen' }))

    expect(await screen.findByText('Keine Befunde.')).toBeInTheDocument()
  })
})
