import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AuthContextProps } from 'react-oidc-context'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import type { ComplianceOverview, OverdueReview, VersionRate } from '@/api/compliance'
import { ApiClientProvider } from '@/api/context'
import type { Me } from '@/api/me'
import { createAppRouter } from '@/router'
import { parseCompliancePageSearch } from './compliance-params'

const signedIn = {
  isAuthenticated: true,
  isLoading: false,
  user: { profile: { sub: 'officer', name: 'Carla Compliance' } },
  error: undefined,
  activeNavigator: undefined,
  signinRedirect: vi.fn(),
  signoutRedirect: vi.fn(),
} as unknown as AuthContextProps

vi.mock('react-oidc-context', () => ({ useAuth: () => signedIn }))

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>
const ok = (data: unknown): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status: 200 }) })
const failed = (status: number, body: unknown): Reply =>
  Promise.resolve({ data: undefined, error: body, response: new Response(null, { status }) })

const overview: ComplianceOverview = {
  today: '2026-10-29',
  overdueReviews: 3,
  overdueByUnit: [{ unit: 'HR', count: 2 }, { count: 1 }],
  overdueByOwner: [
    { owner: 'owner-a', count: 2 },
    { owner: 'owner-b', count: 1 },
  ],
  acknowledgements: { versions: 2, audience: 12, acknowledged: 3, open: 9, rate: 0.25 },
  acknowledgementsByUnit: [
    { unit: 'HR', rate: { versions: 1, audience: 7, acknowledged: 1, open: 6, rate: 1 / 7 } },
    { unit: 'Finance', rate: { versions: 1, audience: 5, acknowledged: 2, open: 3, rate: 0.4 } },
  ],
}

const overdue: OverdueReview[] = [
  {
    documentId: 'd1',
    versionId: 'v1',
    title: 'HR policy',
    versionLabel: '1.0',
    classPath: ['policy'],
    unit: 'HR',
    owner: 'owner-a',
    status: 'ReviewDue',
    dueOn: '2026-10-24',
    daysOverdue: 5,
  },
]

const rates: VersionRate[] = [
  {
    documentId: 'd1',
    versionId: 'v1',
    title: 'HR policy',
    versionLabel: '1.0',
    classPath: ['policy'],
    unit: 'HR',
    owner: 'owner-a',
    audience: 7,
    acknowledged: 1,
    open: 6,
    rate: 1 / 7,
  },
]

let me: Me

beforeEach(() => {
  me = { userId: 'officer', tenantId: 'tenant-1', roles: ['ComplianceOfficer'] }
})

function renderAt(path: string) {
  const get = vi.fn((p: string, init?: { params?: { query?: Record<string, string> } }) => {
    switch (p) {
      case '/api/v1/me':
        return ok(me)
      case '/api/v1/compliance/overview':
        return ok(overview)
      case '/api/v1/compliance/overdue-reviews':
        return ok(init?.params?.query?.unit === 'Finance' ? [] : overdue)
      case '/api/v1/compliance/acknowledgements':
        return ok(rates)
      case '/api/v1/compliance/acknowledgements/{documentId}/{versionId}/open':
        return init?.params?.query?.reason
          ? ok({
              documentId: 'd1',
              versionId: 'v1',
              title: 'HR policy',
              versionLabel: '1.0',
              purpose: 'Escalation',
              audience: 7,
              acknowledged: 1,
              open: ['reader-hr-2', 'reader-all'],
            })
          : failed(403, { code: 'purpose_required', message: 'State the purpose.' })
      default:
        return failed(404, { code: 'not_found', message: `unexpected ${p}` })
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

describe('compliance cockpit', () => {
  it('shows the navigation entry only to compliance officers', async () => {
    renderAt('/')
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    expect(await within(nav).findByRole('link', { name: 'Compliance' })).toBeInTheDocument()
  })

  it('hides the navigation entry from other roles', async () => {
    me = { userId: 'reader', tenantId: 'tenant-1', roles: ['Reader'] }
    renderAt('/')
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    await within(nav).findByRole('link', { name: 'Tasks' })
    expect(within(nav).queryByRole('link', { name: 'Compliance' })).not.toBeInTheDocument()
  })

  it('shows the key figures and the figures per unit and owner', async () => {
    renderAt('/compliance')

    expect(
      await screen.findByRole('link', { name: 'Overdue reviews: 3. Show the list' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: 'Acknowledgement rate: 25 %. Show the list' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: 'Open acknowledgements: 9. Show the list' }),
    ).toBeInTheDocument()
    const byUnit = screen.getByRole('table', { name: 'Overdue reviews by organizational unit' })
    expect(within(byUnit).getByText('No unit')).toBeInTheDocument()
    const rateTable = screen.getByRole('table', {
      name: 'Acknowledgement rate by organizational unit',
    })
    expect(
      within(rateTable).getByRole('link', { name: 'Acknowledgement rate 14 % in HR' }),
    ).toBeInTheDocument()
  })

  it('leads from a figure to the filtered list and back to all', async () => {
    const { router, get, user } = renderAt('/compliance')

    await user.click(await screen.findByRole('link', { name: '2 overdue in HR' }))

    expect(router.state.location.search).toEqual({ view: 'reviews', unit: 'HR' })
    const list = await screen.findByRole('table', { name: 'Overdue reviews' })
    expect(within(list).getByRole('link', { name: 'HR policy' })).toBeInTheDocument()
    expect(within(list).getByText('5 days')).toBeInTheDocument()
    expect(get).toHaveBeenCalledWith('/api/v1/compliance/overdue-reviews', {
      params: { query: { unit: 'HR', owner: undefined } },
    })
    expect(screen.getByText('Filtered by unit HR')).toBeInTheDocument()

    await user.click(screen.getByRole('link', { name: 'Clear filter' }))
    expect(router.state.location.search).toEqual({ view: 'reviews' })
  })

  it('filters by versions without unit', async () => {
    const { router, get, user } = renderAt('/compliance')

    await user.click(await screen.findByRole('link', { name: '1 overdue in No unit' }))

    expect(router.state.location.search).toEqual({ view: 'reviews', unit: '' })
    await screen.findByRole('table', { name: 'Overdue reviews' })
    expect(get).toHaveBeenCalledWith('/api/v1/compliance/overdue-reviews', {
      params: { query: { unit: '', owner: undefined } },
    })
  })

  it('asks for purpose and reason before it shows who has not acknowledged', async () => {
    const { get, user } = renderAt('/compliance?view=acknowledgements')

    const list = await screen.findByRole('table', { name: 'Acknowledgement rate per version' })
    expect(within(list).getByText('1 of 7')).toBeInTheDocument()
    await user.click(
      within(list).getByRole('button', { name: 'Who has not acknowledged HR policy 1.0' }),
    )

    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Show names' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('State why you need the names.')
    expect(get).not.toHaveBeenCalledWith(
      '/api/v1/compliance/acknowledgements/{documentId}/{versionId}/open',
      expect.anything(),
    )

    await user.click(within(dialog).getByRole('radio', { name: 'Reminder to the readers' }))
    await user.type(within(dialog).getByLabelText('Reason *'), 'Rate below target')
    await user.click(within(dialog).getByRole('button', { name: 'Show names' }))

    const names = await within(dialog).findByRole('list', {
      name: 'Readers without acknowledgement',
    })
    expect(
      within(names)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual(['reader-hr-2', 'reader-all'])
    expect(get).toHaveBeenCalledWith(
      '/api/v1/compliance/acknowledgements/{documentId}/{versionId}/open',
      {
        params: {
          path: { documentId: 'd1', versionId: 'v1' },
          query: { purpose: 'reminder', reason: 'Rate below target' },
        },
      },
    )
  })
})

describe('compliance search parameters', () => {
  it('keeps the empty unit (no unit) and drops what does not belong to the view', () => {
    expect(
      parseCompliancePageSearch({ view: 'reviews', unit: '', owner: 'o', document: 'd' }),
    ).toEqual({
      view: 'reviews',
      unit: '',
      owner: 'o',
    })
    expect(
      parseCompliancePageSearch({ view: 'acknowledgements', owner: 'o', document: 'd' }),
    ).toEqual({
      view: 'acknowledgements',
      document: 'd',
    })
    expect(parseCompliancePageSearch({ view: 'other', unit: 'HR' })).toEqual({})
  })
})
