import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AuthContextProps } from 'react-oidc-context'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import {
  canManageLegalHolds,
  documentIdsIn,
  type HoldSearchPreview,
  type LegalHold,
} from '@/api/legal-holds'
import type { Me } from '@/api/me'
import { setLocale } from '@/i18n'
import { createAppRouter } from '@/router'
import { parseLegalHoldsPageSearch } from './legal-holds-params'

const signedIn = {
  isAuthenticated: true,
  isLoading: false,
  user: { profile: { sub: 'records-1', name: 'Rita Records' } },
  error: undefined,
  activeNavigator: undefined,
  signinRedirect: vi.fn(),
  signoutRedirect: vi.fn(),
} as unknown as AuthContextProps

vi.mock('react-oidc-context', () => ({ useAuth: () => signedIn }))

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>
const ok = (data: unknown, status = 200): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status }) })
const failed = (status: number, body: unknown): Reply =>
  Promise.resolve({ data: undefined, error: body, response: new Response(null, { status }) })

const docA = '11111111-1111-4111-8111-111111111111'
const docB = '22222222-2222-4222-8222-222222222222'
const docC = '33333333-3333-4333-8333-333333333333'

const assignment = (organizationalUnit?: string) => ({
  role: 'RecordsManager',
  organizationalUnit,
  validFrom: '2026-01-01T00:00:00Z',
})

const recordsManager: Me = {
  userId: 'records-1',
  tenantId: 'tenant-1',
  roles: ['RecordsManager'],
  assignments: [assignment()],
}

const activeHold: LegalHold = {
  id: 'hold-1',
  name: 'Litigation Müller GmbH',
  matterReference: 'LIT-2026-7',
  reason: 'Lawsuit filed',
  createdBy: 'records-1',
  createdAt: '2026-10-20T09:00:00Z',
  status: 'Active',
  documentCount: 1,
}

const releasedHold: LegalHold = {
  ...activeHold,
  id: 'hold-2',
  name: 'Audit 2025',
  matterReference: undefined,
  status: 'Released',
  releasedAt: '2026-10-25T09:00:00Z',
  releasedBy: 'records-1',
  releaseReason: 'Audit closed',
  documentCount: 0,
}

const detail: LegalHold = {
  ...activeHold,
  documents: [
    {
      documentId: docA,
      title: 'Supply contract North',
      addedVia: 'Document',
      reason: 'Lawsuit filed',
      addedBy: 'records-1',
      addedAt: '2026-10-20T09:00:00Z',
    },
    {
      documentId: docB,
      title: 'Supply contract South',
      addedVia: 'Search',
      reason: 'Same supplier',
      addedBy: 'records-1',
      addedAt: '2026-10-21T09:00:00Z',
      removedAt: '2026-10-22T09:00:00Z',
      removedBy: 'records-1',
      removalReason: 'Added by mistake',
    },
    {
      documentId: docC,
      addedVia: 'Document',
      reason: 'Board minutes',
      addedBy: 'records-2',
      addedAt: '2026-10-21T10:00:00Z',
    },
  ],
}

const preview: HoldSearchPreview = {
  total: 3,
  fingerprint: 'abc123',
  documents: [
    {
      documentId: docA,
      title: 'Supply contract North',
      manageable: true,
      underLegalHold: true,
    },
    {
      documentId: docB,
      title: 'Supply contract South',
      documentNumber: 'VT-2026-0002',
      manageable: true,
      underLegalHold: false,
    },
  ],
}

let me: Me
let post: ReturnType<typeof vi.fn>

beforeEach(() => {
  me = recordsManager
})

function renderAt(path: string, posts: (p: string, body: unknown) => Reply = () => ok({})) {
  const get = vi.fn((p: string, init?: { params?: { query?: Record<string, unknown> } }) => {
    switch (p) {
      case '/api/v1/me':
        return ok(me)
      case '/api/v1/users':
        return ok([{ id: 'records-1', displayName: 'Rita Records' }])
      case '/api/v1/legal-holds':
        return ok(
          init?.params?.query?.status === 'released'
            ? [releasedHold]
            : init?.params?.query?.status === 'all'
              ? [activeHold, releasedHold]
              : [activeHold],
        )
      case '/api/v1/legal-holds/{id}':
        return ok(detail)
      case '/api/v1/search':
        return ok({
          total: 3,
          page: 1,
          size: 20,
          hits: [
            {
              documentId: docA,
              versionId: 'va',
              title: 'Supply contract North',
              versionLabel: '1.0',
              classPath: ['contract'],
              status: 'Published',
              snippet: 'Supply',
              legalHold: true,
            },
          ],
          facets: { class: [], status: [], language: [] },
        })
      default:
        return failed(404, { code: 'not_found', message: `unexpected ${p}` })
    }
  })
  post = vi.fn((p: string, init?: { body?: unknown }) => posts(p, init?.body))
  const client = { GET: get, POST: post } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: [path] }))
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <RouterProvider router={router} context={{ auth: signedIn }} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { router, get, user: userEvent.setup() }
}

describe('legal holds navigation', () => {
  it('shows the entry to records managers for the whole tenant only', async () => {
    renderAt('/')
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    expect(await within(nav).findByRole('link', { name: 'Legal holds' })).toBeInTheDocument()
  })

  it('hides the entry from records managers of a unit and from readers', async () => {
    me = { ...recordsManager, assignments: [assignment('HR')] }
    renderAt('/')
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    await within(nav).findByRole('link', { name: 'Tasks' })
    expect(within(nav).queryByRole('link', { name: 'Legal holds' })).not.toBeInTheDocument()
    expect(canManageLegalHolds({ ...me, assignments: [] })).toBe(false)
  })

  it('explains the page to users who may not manage holds', async () => {
    me = { ...recordsManager, assignments: [assignment('HR')] }
    renderAt('/legal-holds')
    expect(
      await screen.findByText(
        'Only records managers for the whole organization manage legal holds.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New legal hold' })).not.toBeInTheDocument()
  })
})

describe('legal holds page', () => {
  it('lists the active holds and switches to the released ones', async () => {
    const { get, user, router } = renderAt('/legal-holds')
    const table = await screen.findByRole('table', { name: 'Legal holds' })
    expect(within(table).getByRole('link', { name: 'Litigation Müller GmbH' })).toBeInTheDocument()
    expect(within(table).getByText('LIT-2026-7')).toBeInTheDocument()

    await user.click(screen.getByRole('link', { name: 'Released' }))
    expect(router.state.location.search).toEqual({ status: 'released' })
    expect(await screen.findByRole('link', { name: 'Audit 2025' })).toBeInTheDocument()
    expect(get).toHaveBeenCalledWith('/api/v1/legal-holds', {
      params: { query: { status: 'released' } },
    })
  })

  it('sets a new hold with name and reason and opens it', async () => {
    const { user, router } = renderAt('/legal-holds', (p) =>
      p === '/api/v1/legal-holds' ? ok({ ...activeHold, id: 'hold-9' }, 201) : ok({}),
    )
    await user.click(await screen.findByRole('button', { name: 'New legal hold' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Set legal hold' }))
    expect(within(dialog).getByText('Name the matter.')).toBeInTheDocument()
    expect(within(dialog).getByText('State the reason.')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()

    await user.type(within(dialog).getByLabelText('Name of the matter *'), 'Litigation Müller GmbH')
    await user.type(within(dialog).getByLabelText('Matter reference'), 'LIT-2026-7')
    await user.type(within(dialog).getByLabelText('Reason *'), 'Lawsuit filed')
    await user.click(within(dialog).getByRole('button', { name: 'Set legal hold' }))

    await waitFor(() => {
      expect(router.state.location.search).toEqual({ hold: 'hold-9' })
    })
    expect(post).toHaveBeenCalledWith('/api/v1/legal-holds', {
      body: {
        name: 'Litigation Müller GmbH',
        reason: 'Lawsuit filed',
        matterReference: 'LIT-2026-7',
        documentIds: undefined,
        searchFilter: undefined,
      },
    })
    expect(await screen.findByText('Legal hold "Litigation Müller GmbH" set.')).toBeInTheDocument()
  })

  it('shows a hold with its documents and their history, without titles the caller may not see', async () => {
    renderAt('/legal-holds?hold=hold-1')
    const table = await screen.findByRole('table', {
      name: 'Documents of the legal hold, with who added or removed them and why',
    })
    const [first, second, third] = within(table).getAllByRole('row').slice(1) as [
      HTMLElement,
      HTMLElement,
      HTMLElement,
    ]
    expect(within(first).getByRole('link', { name: 'Supply contract North' })).toBeInTheDocument()
    expect(within(first).getByText('Covered')).toBeInTheDocument()
    expect(within(second).getByText(/^Removed on/)).toBeInTheDocument()
    expect(within(second).getByText('Reason: Added by mistake')).toBeInTheDocument()
    expect(within(second).queryByRole('button')).not.toBeInTheDocument()
    expect(within(third).getByText('Document not visible to you')).toBeInTheDocument()
    expect(within(third).queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByText('1 document under this hold')).toBeInTheDocument()
  })

  it('removes a document with a reason', async () => {
    const { user } = renderAt('/legal-holds?hold=hold-1', () => ok(detail))
    await user.click(
      await screen.findByRole('button', { name: 'Remove Supply contract North from the hold' }),
    )
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Remove from hold' }))
    expect(within(dialog).getByText('State the reason.')).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Reason *'), 'Wrong supplier')
    await user.click(within(dialog).getByRole('button', { name: 'Remove from hold' }))

    expect(await screen.findByText('Document removed from the hold.')).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/api/v1/legal-holds/{id}/documents/{documentId}/remove', {
      params: { path: { id: 'hold-1', documentId: docA } },
      body: { reason: 'Wrong supplier' },
    })
  })

  it('releases a hold only with reason and explicit confirmation', async () => {
    const { user } = renderAt('/legal-holds?hold=hold-1', () => ok(releasedHold))
    await user.click(await screen.findByRole('button', { name: 'Release' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Reason *'), 'Settled')
    await user.click(within(dialog).getByRole('button', { name: 'Release hold' }))
    expect(within(dialog).getByText('Confirm that the hold may be lifted.')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()

    await user.click(
      within(dialog).getByRole('checkbox', {
        name: 'The matter is closed and the legal department has lifted the hold.',
      }),
    )
    await user.click(within(dialog).getByRole('button', { name: 'Release hold' }))
    expect(await screen.findByText('Legal hold "Audit 2025" released.')).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/api/v1/legal-holds/{id}/release', {
      params: { path: { id: 'hold-1' } },
      body: { reason: 'Settled' },
    })
  })

  it('adds the documents of a checked search, confirming exactly the reviewed documents', async () => {
    let attempts = 0
    const { user } = renderAt('/legal-holds?hold=hold-1', (p) => {
      if (p === '/api/v1/legal-holds/search-preview') return ok(preview)
      attempts += 1
      return attempts === 1
        ? failed(409, { code: 'result_changed', message: 'changed' })
        : ok({ ...activeHold, addedDocumentIds: [docB] })
    })
    await user.click(await screen.findByRole('button', { name: 'Add documents' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('button', { name: 'Check search' })).toBeDisabled()
    await user.type(within(dialog).getByLabelText('Search text'), 'supply')
    await user.click(within(dialog).getByRole('button', { name: 'Check search' }))

    const list = await within(dialog).findByRole('list', { name: 'Documents of the search' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(2)
    expect(within(list).getByText('Under legal hold')).toBeInTheDocument()
    expect(within(dialog).getByText('3 hits in 2 documents.')).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Reason *'), 'Same supplier')
    await user.click(
      within(dialog).getByRole('button', { name: 'Put 2 documents under legal hold' }),
    )

    // The result changed since the check: nothing is added, the records manager checks again.
    expect(
      await within(dialog).findByText(
        'The search finds other documents now than the ones you reviewed. Check the search again.',
      ),
    ).toBeInTheDocument()
    expect(
      within(dialog).queryByRole('list', { name: 'Documents of the search' }),
    ).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Check search' }))
    await within(dialog).findByRole('list', { name: 'Documents of the search' })
    await user.click(
      within(dialog).getByRole('button', { name: 'Put 2 documents under legal hold' }),
    )

    expect(await screen.findByText('1 document put under legal hold.')).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/api/v1/legal-holds/search-preview', {
      body: { q: 'supply', class: undefined, status: undefined, lang: undefined },
    })
    expect(post).toHaveBeenLastCalledWith('/api/v1/legal-holds/{id}/documents', {
      params: { path: { id: 'hold-1' } },
      body: {
        reason: 'Same supplier',
        documentIds: undefined,
        searchFilter: {
          q: 'supply',
          class: undefined,
          status: undefined,
          lang: undefined,
          expectedCount: 3,
          expectedFingerprint: 'abc123',
        },
      },
    })
  })

  it('adds named documents from pasted links', async () => {
    const { user } = renderAt('/legal-holds?hold=hold-1', () =>
      ok({ ...activeHold, addedDocumentIds: [] }),
    )
    await user.click(await screen.findByRole('button', { name: 'Add documents' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('radio', { name: 'Named documents' }))
    await user.type(
      within(dialog).getByLabelText('Document links or ids *'),
      `https://dms.example/documents/${docA}\n${docB.toUpperCase()}`,
    )
    expect(within(dialog).getByText('2 documents recognised.')).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Reason *'), 'Named by legal')
    await user.click(within(dialog).getByRole('button', { name: 'Add 2 documents' }))

    expect(
      await screen.findByText('No new documents: the hold covers them already.'),
    ).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/api/v1/legal-holds/{id}/documents', {
      params: { path: { id: 'hold-1' } },
      body: { reason: 'Named by legal', documentIds: [docA, docB], searchFilter: undefined },
    })
  })

  it('is available in German', async () => {
    setLocale('de')
    renderAt('/legal-holds')
    expect(
      await screen.findByRole('heading', { name: 'Legal Holds', level: 1 }),
    ).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Neuer Legal Hold' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Aufgehoben' })).toBeInTheDocument()
    expect(await screen.findByRole('columnheader', { name: 'Aktenzeichen' })).toBeInTheDocument()
  })
})

describe('legal holds on the search page', () => {
  it('puts the checked search result under an existing hold', async () => {
    const { user, router } = renderAt('/search?q=supply', (p) =>
      p === '/api/v1/legal-holds/search-preview'
        ? ok(preview)
        : ok({ ...activeHold, addedDocumentIds: [docB] }),
    )
    const results = await screen.findByRole('list', { name: 'Search results' })
    expect(within(results).getByTestId('legal-hold-badge')).toHaveTextContent('Legal Hold')

    await user.click(screen.getByRole('button', { name: 'Put under legal hold' }))
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByRole('list', { name: 'Documents of the search' })
    expect(within(dialog).getByLabelText('Legal hold')).toHaveValue('hold-1')
    await user.type(within(dialog).getByLabelText('Reason *'), 'Same supplier')
    await user.click(
      within(dialog).getByRole('button', { name: 'Put 2 documents under legal hold' }),
    )

    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/legal-holds')
    })
    expect(post).toHaveBeenLastCalledWith('/api/v1/legal-holds/{id}/documents', {
      params: { path: { id: 'hold-1' } },
      body: {
        reason: 'Same supplier',
        documentIds: undefined,
        searchFilter: {
          q: 'supply',
          class: undefined,
          status: undefined,
          lang: undefined,
          expectedCount: 3,
          expectedFingerprint: 'abc123',
        },
      },
    })
  })

  it('offers the action to records managers for the whole tenant only', async () => {
    me = { userId: 'reader', tenantId: 'tenant-1', roles: ['Reader'], assignments: [] }
    renderAt('/search?q=supply')
    await screen.findByRole('list', { name: 'Search results' })
    expect(screen.queryByRole('button', { name: 'Put under legal hold' })).not.toBeInTheDocument()
  })
})

describe('legal hold helpers', () => {
  it('reads document ids from pasted links and ids', () => {
    expect(documentIdsIn(`see /documents/${docA}?v=1, ${docA} and ${docC.toUpperCase()}`)).toEqual([
      docA,
      docC,
    ])
    expect(documentIdsIn('nothing here')).toEqual([])
  })

  it('reads the page parameters', () => {
    expect(parseLegalHoldsPageSearch({ status: 'released', hold: ' h1 ' })).toEqual({
      status: 'released',
      hold: 'h1',
    })
    expect(parseLegalHoldsPageSearch({ status: 'active', hold: '' })).toEqual({})
  })
})
