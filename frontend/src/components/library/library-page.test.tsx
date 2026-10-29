import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AuthContextProps } from 'react-oidc-context'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { LibraryResult } from '@/api/library'
import { setLocale } from '@/i18n'
import { createAppRouter } from '@/router'
import { parseLibraryPageSearch, toggleValue } from './library-params'

const signedIn = {
  isAuthenticated: true,
  isLoading: false,
  user: { profile: { sub: 'reader-1', name: 'Rudi Reader' } },
  error: undefined,
  activeNavigator: undefined,
  signinRedirect: vi.fn(),
  signoutRedirect: vi.fn(),
} as unknown as AuthContextProps

vi.mock('react-oidc-context', () => ({ useAuth: () => signedIn }))

const travelId = '7c1e2f0a-0000-4000-8000-0000000000a1'
const securityId = '7c1e2f0a-0000-4000-8000-0000000000b2'

const result = (overrides: Partial<LibraryResult> = {}): LibraryResult => ({
  total: 2,
  page: 1,
  size: 24,
  sort: 'title',
  order: 'asc',
  since: '2026-09-29',
  timeZone: 'Europe/Berlin',
  items: [
    {
      documentId: securityId,
      versionId: 'v-security-2',
      title: 'IT security policy',
      version: '2.0',
      status: 'Published',
      classPath: ['policy'],
      classLabel: 'Policy',
      organizationalUnit: 'Finance',
      confidentiality: 'Internal',
      tags: ['security'],
      scope: '',
      validFrom: '2026-10-29',
      validSince: '2026-10-28T23:00:00Z',
      publishedAt: '2026-10-29T08:00:00Z',
      isNew: false,
      changed: true,
      requiresAcknowledgement: true,
      acknowledgementPending: false,
    },
    {
      documentId: travelId,
      versionId: 'v-travel-1',
      title: 'Travel expenses',
      documentNumber: 'ZA-001',
      version: '1.0',
      status: 'Published',
      classPath: ['instruction'],
      classLabel: 'Central instruction',
      organizationalUnit: 'HR',
      confidentiality: 'Internal',
      tags: ['travel', 'costs'],
      scope: 'DE',
      summary: 'How to claim travel expenses.',
      validFrom: '2026-10-29',
      validSince: '2026-10-28T23:00:00Z',
      publishedAt: '2026-10-29T07:00:00Z',
      isNew: true,
      changed: false,
      requiresAcknowledgement: true,
      acknowledgementPending: true,
    },
  ],
  facets: {
    class: [
      { value: 'instruction', label: 'Central instruction', count: 1 },
      { value: 'policy', label: 'Policy', count: 1 },
    ],
    unit: [
      { value: 'Finance', count: 1 },
      { value: 'HR', count: 1 },
    ],
    tag: [
      { value: 'costs', count: 1 },
      { value: 'security', count: 1 },
      { value: 'travel', count: 1 },
    ],
    confidentiality: [{ value: 'Internal', count: 2 }],
    changed: 2,
    pending: 1,
  },
  ...overrides,
})

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>

const ok = (data: unknown): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status: 200 }) })
const failed = (status: number, body: unknown): Reply =>
  Promise.resolve({ data: undefined, error: body, response: new Response(null, { status }) })

let library: () => Reply

beforeEach(() => {
  library = () => ok(result())
})

function renderAt(path: string) {
  const get = vi.fn<
    (path: string, init?: { params?: { query?: Record<string, unknown> } }) => Reply
  >((p) => {
    switch (p) {
      case '/api/v1/library':
        return library()
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

/** The query parameters of the library requests. */
function queries(get: ReturnType<typeof renderAt>['get']) {
  return get.mock.calls
    .filter(([p]) => p === '/api/v1/library')
    .map(([, init]) => init?.params?.query)
}

describe('Library page', () => {
  it('is in the navigation for every signed-in user', async () => {
    const { router, user } = renderAt('/')
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    await user.click(within(nav).getByRole('link', { name: 'Library' }))
    expect(router.state.location.pathname).toBe('/library')
    expect(await screen.findByRole('heading', { level: 1, name: 'Library' })).toBeInTheDocument()
  })

  it('shows cards with number, index, issuing body, description and markers', async () => {
    renderAt('/library')
    const list = await screen.findByRole('list', { name: 'Documents of the library' })
    expect(screen.getByText('2 documents')).toBeInTheDocument()

    const travel = within(list).getByRole('link', { name: 'Travel expenses' })
    expect(travel).toHaveAttribute('href', `/documents/${travelId}`)
    const card = travel.closest('li') as HTMLElement
    expect(within(card).getByText('ZA-001')).toBeInTheDocument()
    expect(within(card).getByText('1.0')).toBeInTheDocument()
    expect(within(card).getByText('HR')).toBeInTheDocument()
    expect(within(card).getByText('Central instruction')).toBeInTheDocument()
    expect(within(card).getByText('How to claim travel expenses.')).toBeInTheDocument()
    expect(within(card).getByText('New')).toBeInTheDocument()
    expect(within(card).getByText('Acknowledgement open')).toBeInTheDocument()
    expect(within(card).getByText(/Scope: DE/)).toBeInTheDocument()

    const security = within(list)
      .getByRole('link', { name: 'IT security policy' })
      .closest('li') as HTMLElement
    expect(within(security).getByText('Changed since 29 Sept 2026')).toBeInTheDocument()
    expect(within(security).queryByText('Acknowledgement open')).not.toBeInTheDocument()
  })

  it('keeps filters in the URL and sends them to the API; several values per facet', async () => {
    const { get, router, user } = renderAt('/library?class=policy')
    const filters = await screen.findByRole('complementary', { name: 'Filters' })
    expect(queries(get)).toContainEqual({ class: ['policy'], page: 1, size: 24 })
    expect(within(filters).getByRole('button', { name: /Policy\s*1/ })).toHaveAttribute(
      'aria-pressed',
      'true',
    )

    await user.click(within(filters).getByRole('button', { name: /Central instruction\s*1/ }))
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ class: 'policy,instruction' })
    })
    await user.click(within(filters).getByRole('button', { name: /HR\s*1/ }))
    await user.click(within(filters).getByRole('button', { name: /Awaiting my acknowledgement/ }))
    await waitFor(() => {
      expect(queries(get)).toContainEqual({
        class: ['policy', 'instruction'],
        unit: ['HR'],
        pending: true,
        page: 1,
        size: 24,
      })
    })
    expect(router.state.location.search).toEqual({
      class: 'policy,instruction',
      unit: 'HR',
      pending: true,
    })

    await user.click(screen.getAllByRole('button', { name: 'Clear filters' })[0] as HTMLElement)
    await waitFor(() => {
      expect(router.state.location.search).toEqual({})
    })
  })

  it('filters by free text after typing stops and sorts', async () => {
    const { get, router, user } = renderAt('/library')
    await screen.findByRole('list', { name: 'Documents of the library' })

    await user.type(screen.getByRole('searchbox', { name: 'Filter' }), 'travel')
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ q: 'travel' })
    })
    await user.selectOptions(screen.getByRole('combobox', { name: 'Sort by' }), 'number')
    await waitFor(() => {
      expect(queries(get)).toContainEqual({ q: 'travel', sort: 'number', page: 1, size: 24 })
    })
    const order = screen.getByRole('combobox', { name: 'Order' })
    expect(order).toHaveValue('asc')
    await user.selectOptions(order, 'desc')
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ q: 'travel', sort: 'number', order: 'desc' })
    })
    // Dates sort newest first by default; the default stays out of the URL.
    await user.selectOptions(screen.getByRole('combobox', { name: 'Sort by' }), 'lastChanged')
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ q: 'travel', sort: 'lastChanged' })
    })
    expect(screen.getByRole('combobox', { name: 'Order' })).toHaveValue('desc')
  })

  it('switches to the list view and back', async () => {
    const { router, user } = renderAt('/library')
    await screen.findByRole('list', { name: 'Documents of the library' })
    await user.click(screen.getByRole('button', { name: 'List' }))
    const table = await screen.findByRole('table', { name: 'Valid documents of the library' })
    expect(within(table).getByRole('link', { name: 'Travel expenses' })).toBeInTheDocument()
    expect(within(table).getByText('ZA-001')).toBeInTheDocument()
    expect(router.state.location.search).toEqual({ view: 'list' })
  })

  it('pages through large libraries', async () => {
    library = () => ok(result({ total: 50 }))
    const { get, router, user } = renderAt('/library')
    expect(await screen.findByText('Page 1 of 3')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => {
      expect(queries(get)).toContainEqual({ page: 2, size: 24 })
    })
    expect(router.state.location.search).toEqual({ page: 2 })
  })

  it('explains an empty library and an empty filter result', async () => {
    const empty = {
      total: 0,
      items: [],
      facets: { class: [], unit: [], tag: [], confidentiality: [], changed: 0, pending: 0 },
    }
    library = () => ok(result(empty))
    renderAt('/library')
    expect(await screen.findByText('No valid documents yet')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Clear filters' })).not.toBeInTheDocument()
  })

  it('offers to clear the filters when nothing matches', async () => {
    library = () =>
      ok(
        result({
          total: 0,
          items: [],
        }),
      )
    renderAt('/library?tag=unknown')
    expect(await screen.findByText('No documents match the filters')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Clear filters' })).toHaveLength(2)
  })

  it('shows errors with a retry', async () => {
    library = () => failed(500, { code: 'database_error', message: 'The database is down.' })
    renderAt('/library')
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('The database is down.')
    expect(within(alert).getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('renders in German', async () => {
    setLocale('de')
    renderAt('/library')
    expect(await screen.findByRole('heading', { level: 1, name: 'Bibliothek' })).toBeInTheDocument()
    const list = await screen.findByRole('list', { name: 'Dokumente der Bibliothek' })
    expect(screen.getByText('2 Dokumente')).toBeInTheDocument()
    expect(within(list).getByText('Neu')).toBeInTheDocument()
    expect(within(list).getByText('Kenntnisnahme offen')).toBeInTheDocument()
    expect(within(list).getByText('Geändert seit 29.09.2026')).toBeInTheDocument()
    const filters = screen.getByRole('complementary', { name: 'Filter' })
    expect(
      within(filters).getByRole('region', { name: 'Herausgebende Stelle' }),
    ).toBeInTheDocument()
    expect(within(filters).getByRole('button', { name: /Intern\s*2/ })).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Hauptnavigation' })
    expect(within(nav).getByRole('link', { name: 'Bibliothek' })).toBeInTheDocument()
  })
})

describe('Library URL state', () => {
  it('drops malformed values and normalizes lists', () => {
    expect(
      parseLibraryPageSearch({
        q: '  ',
        class: 'policy, ,policy,instruction',
        since: '29.10.2026',
        sort: 'author',
        order: 'up',
        page: '0',
        changed: 'true',
        pending: 'yes',
        view: 'grid',
      }),
    ).toEqual({ class: 'policy,instruction', changed: true })
    expect(
      parseLibraryPageSearch({ since: '2026-10-01', sort: 'lastChanged', order: 'asc', page: 3 }),
    ).toEqual({ since: '2026-10-01', sort: 'lastChanged', order: 'asc', page: 3 })
  })

  it('toggles values of a filter', () => {
    expect(toggleValue(undefined, 'a')).toBe('a')
    expect(toggleValue('a', 'b')).toBe('a,b')
    expect(toggleValue('a,b', 'a')).toBe('b')
    expect(toggleValue('a', 'a')).toBeUndefined()
  })
})
