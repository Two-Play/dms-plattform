import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AuthContextProps } from 'react-oidc-context'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { SearchResult, ValidAtResult } from '@/api/search'
import { createAppRouter } from '@/router'

const signedIn = {
  isAuthenticated: true,
  isLoading: false,
  user: { profile: { sub: 'reader-1', name: 'Rita Reader' } },
  error: undefined,
  activeNavigator: undefined,
  signinRedirect: vi.fn(),
  signoutRedirect: vi.fn(),
} as unknown as AuthContextProps

vi.mock('react-oidc-context', () => ({ useAuth: () => signedIn }))

const docA = '7c1e2f0a-0000-4000-8000-0000000000a1'
const docB = '7c1e2f0a-0000-4000-8000-0000000000b2'

const result = (overrides: Partial<SearchResult> = {}): SearchResult => ({
  total: 2,
  page: 1,
  size: 20,
  hits: [
    {
      documentId: docA,
      versionId: 'va',
      title: 'Lab safety policy',
      versionLabel: '2.0',
      classPath: ['policy', 'safety'],
      status: 'Published',
      validFrom: '2027-01-01',
      documentNumber: 'RL-2027-0003',
      snippet:
        'Always wear <mark>gloves</mark> <img src=x onerror="window.pwned=true"> <script>window.pwned=true</script> in the lab.',
    },
    {
      documentId: docB,
      versionId: 'vb',
      title: 'Glove supplier contract',
      versionLabel: '1.0',
      classPath: ['contract'],
      status: 'InReview',
      snippet: '<mark>Glove</mark> deliveries',
    },
  ],
  facets: {
    class: [
      { value: 'policy', count: 1 },
      { value: 'contract', count: 1 },
    ],
    status: [
      { value: 'Published', count: 1 },
      { value: 'InReview', count: 1 },
    ],
    language: [{ value: 'de', count: 2 }],
  },
  ...overrides,
})

const validAt: ValidAtResult = {
  at: '2027-01-15T22:59:59.999999Z',
  date: '2027-01-15',
  timeZone: 'Europe/Berlin',
  items: [
    {
      documentId: docA,
      versionId: 'va1',
      title: 'Lab safety policy',
      classPath: ['policy', 'safety'],
      versionLabel: '1.0',
      status: 'Superseded',
      scope: '',
      validFrom: '2026-01-01',
      validSince: '2026-01-02T12:00:00Z',
      validUntil: '2027-01-31T23:00:05Z',
      approvedBy: 'deputy-1',
      actingFor: 'approver-1',
      approvedAt: '2026-01-02T11:00:00Z',
    },
  ],
}

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>

const ok = (data: unknown, headers: Record<string, string> = {}): Reply =>
  Promise.resolve({
    data,
    error: undefined,
    response: new Response(null, { status: 200, headers }),
  })
const failed = (status: number, body: unknown): Reply =>
  Promise.resolve({ data: undefined, error: body, response: new Response(null, { status }) })

interface State {
  search: () => Reply
}

let state: State

beforeEach(() => {
  state = { search: () => ok(result()) }
})

afterEach(() => {
  delete (window as { pwned?: boolean }).pwned
  vi.restoreAllMocks()
})

function renderAt(path: string) {
  const get = vi.fn<
    (path: string, init?: { params?: { query?: Record<string, unknown> } }) => Reply
  >((p) => {
    switch (p) {
      case '/api/v1/search':
        return state.search()
      case '/api/v1/documents/valid-at':
        return ok(validAt)
      case '/api/v1/documents/valid-at/export.csv':
        return ok(new Blob(['﻿Document id;Title\r\n'], { type: 'text/csv' }), {
          'Content-Disposition': 'attachment; filename="valid-at-2027-01-15.csv"',
        })
      case '/api/v1/me':
        return ok({ userId: 'reader-1', tenantId: 'tenant-1', roles: ['Reader'] })
      default:
        return ok([])
    }
  })
  const client = { GET: get } as unknown as ApiClient
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

/** The query parameters of the calls to `path`. */
function queries(get: ReturnType<typeof renderAt>['get'], path: string) {
  return get.mock.calls.filter(([p]) => p === path).map(([, init]) => init?.params?.query)
}

describe('Search page', () => {
  it('is in the navigation for every signed-in user', async () => {
    const { router, user } = renderAt('/')
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    await user.click(within(nav).getByRole('link', { name: 'Search' }))
    expect(router.state.location.pathname).toBe('/search')
    expect(await screen.findByRole('heading', { name: 'Search' })).toBeInTheDocument()
  })

  it('lists hits with facets and highlighted snippets', async () => {
    const { get } = renderAt('/search?q=glove')

    const hits = await screen.findByRole('list', { name: 'Search results' })
    expect(screen.getByText('2 results')).toBeInTheDocument()
    expect(within(hits).getByRole('link', { name: 'Lab safety policy' })).toHaveAttribute(
      'href',
      `/documents/${docA}`,
    )
    expect(within(hits).getByText('policy › safety', { exact: false })).toBeInTheDocument()
    // The document number (DMS-113) of hits that have one.
    expect(
      within(hits)
        .getAllByTestId('hit-number')
        .map((n) => n.textContent),
    ).toEqual(['No. RL-2027-0003'])
    expect(within(hits).getByText('In review')).toBeInTheDocument()
    expect(within(hits).getByText('gloves', { selector: 'mark' })).toBeInTheDocument()
    expect(within(hits).getByText('Glove', { selector: 'mark' })).toBeInTheDocument()

    const filters = screen.getByRole('complementary', { name: 'Filters' })
    expect(within(filters).getByRole('button', { name: /policy\s*1/ })).toHaveAttribute(
      'aria-pressed',
      'false',
    )
    expect(within(filters).getByRole('button', { name: /In review\s*1/ })).toBeInTheDocument()
    // Language codes are shown by name in the active language.
    expect(within(filters).getByRole('button', { name: /German\s*2/ })).toBeInTheDocument()
    expect(queries(get, '/api/v1/search')).toContainEqual({ q: 'glove', page: 1, size: 20 })
  })

  it('renders snippets as text: only <mark> highlights, no other markup', async () => {
    renderAt('/search?q=glove')
    const hits = await screen.findByRole('list', { name: 'Search results' })

    expect(hits.querySelector('img')).toBeNull()
    expect(hits.querySelector('script')).toBeNull()
    expect(hits.querySelectorAll('mark')).toHaveLength(2)
    expect(
      within(hits).getByText(/<img src=x onerror="window\.pwned=true">/, { exact: false }),
    ).toBeInTheDocument()
    expect((window as { pwned?: boolean }).pwned).toBeUndefined()
  })

  it('filters by a facet and pages through the results', async () => {
    state.search = () => ok(result({ total: 45 }))
    const { get, router, user } = renderAt('/search?q=glove')
    const filters = await screen.findByRole('complementary', { name: 'Filters' })

    await user.click(within(filters).getByRole('button', { name: /policy\s*1/ }))
    await waitFor(() => {
      expect(queries(get, '/api/v1/search')).toContainEqual({
        q: 'glove',
        class: ['policy'],
        page: 1,
        size: 20,
      })
    })
    expect(router.state.location.search).toEqual({ q: 'glove', class: 'policy' })

    expect(await screen.findByText('Page 1 of 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => {
      expect(queries(get, '/api/v1/search')).toContainEqual({
        q: 'glove',
        class: ['policy'],
        page: 2,
        size: 20,
      })
    })
    expect(router.state.location.search).toEqual({ q: 'glove', class: 'policy', page: 2 })
  })

  it('says that search is not available yet when the endpoint is missing', async () => {
    state.search = () => failed(404, undefined)
    renderAt('/search?q=glove')
    expect(await screen.findByText('Search is not available yet')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows other errors', async () => {
    state.search = () => failed(500, { code: 'database_error', message: 'The index is down.' })
    renderAt('/search?q=glove')
    expect(await screen.findByRole('alert')).toHaveTextContent('The index is down.')
  })

  it('switches to the versions valid on a date', async () => {
    const { get, router, user } = renderAt('/search?q=glove')
    await screen.findByRole('list', { name: 'Search results' })

    fireEvent.change(screen.getByLabelText('Valid on'), { target: { value: '2027-01-15' } })
    await user.click(screen.getByRole('button', { name: 'Search' }))

    const table = await screen.findByRole('table', { name: 'Versions valid on 15 Jan 2027' })
    expect(router.state.location.search).toEqual({ q: 'glove', validOn: '2027-01-15' })
    expect(queries(get, '/api/v1/documents/valid-at')).toContainEqual({ date: '2027-01-15' })
    expect(within(table).getByRole('link', { name: 'Lab safety policy' })).toBeInTheDocument()
    expect(within(table).getByText('1.0')).toBeInTheDocument()
    expect(within(table).getByText('Superseded')).toBeInTheDocument()
    expect(within(table).getByText('deputy-1', { exact: false })).toBeInTheDocument()
    expect(within(table).getByText(/on behalf of/)).toHaveTextContent('on behalf of approver-1')
    expect(screen.getByText(/search text is not applied/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Back to search' }))
    expect(router.state.location.search).toEqual({ q: 'glove' })
  })

  it('limits the valid-on list to a class and exports it as CSV', async () => {
    const createObjectURL = vi.fn(() => 'blob:valid-at')
    const revokeObjectURL = vi.fn()
    Object.assign(URL, { createObjectURL, revokeObjectURL })
    const downloads: string[] = []
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      downloads.push(`${this.download} ${this.href}`)
    })
    const { get, user } = renderAt('/search?validOn=2027-01-15')
    const filters = await screen.findByRole('complementary', { name: 'Filters' })

    await user.click(await within(filters).findByRole('button', { name: /safety\s*1/ }))
    await waitFor(() => {
      expect(queries(get, '/api/v1/documents/valid-at')).toContainEqual({
        date: '2027-01-15',
        class: 'safety',
      })
    })

    await user.click(screen.getByRole('button', { name: 'Export CSV' }))
    await waitFor(() => {
      expect(downloads).toEqual(['valid-at-2027-01-15.csv blob:valid-at'])
    })
    const exportCall = get.mock.calls.find(([p]) => p === '/api/v1/documents/valid-at/export.csv')
    expect(exportCall?.[1]).toMatchObject({
      params: { query: { date: '2027-01-15', class: 'safety' } },
      parseAs: 'blob',
    })
    expect(createObjectURL).toHaveBeenCalledOnce()
    await waitFor(() => {
      expect(revokeObjectURL).toHaveBeenCalledWith('blob:valid-at')
    })
  })
})
