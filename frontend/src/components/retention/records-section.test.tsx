import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type {
  DraftBelowBasis,
  DraftsBelowBasis,
  RecordDifference,
  RecordDifferences,
} from '@/api/records'
import { setLocale } from '@/i18n'
import { RecordsSection } from './records-section'

vi.mock('@tanstack/react-router', () => ({
  Link: ({ children, params }: { children: ReactNode; params: { documentId: string } }) => (
    <a href={`/documents/${params.documentId}`}>{children}</a>
  ),
}))

const difference: RecordDifference = {
  documentId: 'd1',
  versionId: 'v1',
  versionLabel: '2.0',
  versionStatus: 'Published',
  classPath: ['policy'],
  documentOwner: 'taker',
  documentTitle: 'Hijacked',
  versionOwner: 'rightful',
  versionTitle: 'Travel policy',
}

const draft: DraftBelowBasis = {
  documentId: 'd2',
  versionId: 'v2',
  versionLabel: '3.0-draft.2',
  versionStatus: 'Draft',
  title: 'Board minutes',
  classPath: ['policy'],
  author: 'author-1',
  owner: 'owner-1',
  level: 'Internal',
  basisLabel: '2.0',
  basisLevel: 'Confidential',
  changedAt: '2026-10-29T10:00:00Z',
}

function ok(data: unknown) {
  return { data, error: undefined, response: new Response(null, { status: 200 }) }
}

function refused(status: number, message: string) {
  return {
    data: undefined,
    error: { code: 'conflict', message },
    response: new Response(null, { status }),
  }
}

function renderWith(
  differences: RecordDifferences | Error,
  drafts: DraftsBelowBasis = { drafts: [], notCleared: 0 },
  post: () => unknown = () => ok({ documentId: 'd1', title: 'Travel policy' }),
) {
  const get = vi.fn((path: string) => {
    if (path === '/api/v1/records/document-differences') {
      return Promise.resolve(
        differences instanceof Error ? refused(500, differences.message) : ok(differences),
      )
    }
    if (path === '/api/v1/records/drafts-below-basis') return Promise.resolve(ok(drafts))
    return Promise.resolve(ok([]))
  })
  const postFn = vi.fn(() => Promise.resolve(post()))
  const client = { GET: get, POST: postFn } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <RecordsSection />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { get, post: postFn, user: userEvent.setup() }
}

beforeEach(() => {
  setLocale('de')
})

afterEach(() => {
  setLocale('en')
})

describe('Document data for records management (DMS-162)', () => {
  it('shows what differs, and says so if nothing does', async () => {
    renderWith({ differences: [], notCleared: 0 })
    expect(await screen.findByText('Keine Abweichungen.')).toBeInTheDocument()
    expect(
      await screen.findByText('Keine offene Version steht unter ihrer Ausgangsversion.'),
    ).toBeInTheDocument()
  })

  it('names both values of a difference', async () => {
    renderWith({ differences: [difference], notCleared: 0 })
    const row = (await screen.findByRole('link', { name: 'Hijacked' })).closest('tr')
    expect(row).not.toBeNull()
    const cells = within(row as HTMLElement)
    expect(cells.getByText(/Travel policy/)).toBeInTheDocument()
    expect(cells.getByText('taker')).toBeInTheDocument()
    expect(cells.getByText('rightful')).toBeInTheDocument()
  })

  it('says that documents were left out for lack of clearance', async () => {
    renderWith({ differences: [difference], notCleared: 2 })
    expect(await screen.findByText(/2 weitere Dokumente/)).toBeInTheDocument()
  })

  it('shows the failure of a report with a way to retry', async () => {
    const { get, user } = renderWith(new Error('down'))
    const retry = await screen.findByRole('button', { name: /erneut/i })
    get.mockClear()
    await user.click(retry)
    expect(get).toHaveBeenCalledWith('/api/v1/records/document-differences')
  })

  it('offers no correction while a version is in the workflow, and says why', async () => {
    renderWith({ differences: [{ ...difference, inWorkflow: '3.0-draft.1' }], notCleared: 0 })
    expect(
      await screen.findByText('Wartet auf Version 3.0-draft.1 im Workflow'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Hijacked berichtigen' })).not.toBeInTheDocument()
  })

  it('needs a reason, and sends the chosen values for the version shown', async () => {
    const { post, user } = renderWith({ differences: [difference], notCleared: 0 })
    await user.click(await screen.findByRole('button', { name: 'Hijacked berichtigen' }))
    const dialog = await screen.findByRole('dialog')
    const box = within(dialog)

    await user.click(box.getByRole('button', { name: 'Berichtigen' }))
    expect(box.getByText('Geben Sie eine Begründung an.')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()

    await user.click(box.getByRole('checkbox', { name: /Titel/ }))
    await user.type(box.getByLabelText('Begründung *'), 'Taken over by a draft')
    await user.click(box.getByRole('button', { name: 'Berichtigen' }))

    await waitFor(() => {
      expect(post).toHaveBeenCalledWith('/api/v1/documents/{id}/record-correction', {
        params: { path: { id: 'd1' } },
        body: { versionId: 'v1', owner: true, title: false, reason: 'Taken over by a draft' },
      })
    })
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })

  it('needs at least one value', async () => {
    const { post, user } = renderWith({
      differences: [{ ...difference, versionOwner: undefined }],
      notCleared: 0,
    })
    await user.click(await screen.findByRole('button', { name: 'Hijacked berichtigen' }))
    const box = within(await screen.findByRole('dialog'))
    expect(box.queryByRole('checkbox', { name: /Eigentümer/ })).not.toBeInTheDocument()

    await user.click(box.getByRole('checkbox', { name: /Titel/ }))
    await user.type(box.getByLabelText('Begründung *'), 'Reason')
    await user.click(box.getByRole('button', { name: 'Berichtigen' }))

    expect(box.getByRole('alert')).toHaveTextContent('Wählen Sie mindestens einen Wert.')
    expect(post).not.toHaveBeenCalled()
  })

  it('keeps the dialog open with the reason of a refusal', async () => {
    const { user } = renderWith({ differences: [difference], notCleared: 0 }, undefined, () =>
      refused(409, 'the document no longer differs from this version'),
    )
    await user.click(await screen.findByRole('button', { name: 'Hijacked berichtigen' }))
    const box = within(await screen.findByRole('dialog'))
    await user.type(box.getByLabelText('Begründung *'), 'Reason')
    await user.click(box.getByRole('button', { name: 'Berichtigen' }))

    expect(await box.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('lists open versions below their basis with both levels and the people to ask', async () => {
    renderWith({ differences: [], notCleared: 0 }, { drafts: [draft], notCleared: 1 })
    const row = (await screen.findByRole('link', { name: 'Board minutes' })).closest('tr')
    const cells = within(row as HTMLElement)
    expect(cells.getByText('Intern')).toBeInTheDocument()
    expect(cells.getByText('Vertraulich')).toBeInTheDocument()
    expect(cells.getByText('author-1')).toBeInTheDocument()
    expect(cells.getByText('owner-1')).toBeInTheDocument()
    expect(screen.getByText(/1 weiteres Dokument/)).toBeInTheDocument()
  })
})
