import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AuthContextProps } from 'react-oidc-context'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { ClassNumberRange, NumberRange } from '@/api/number-ranges'
import { setLocale } from '@/i18n'
import { createAppRouter } from '@/router'

const signedIn = {
  isAuthenticated: true,
  isLoading: false,
  user: { profile: { sub: 'admin-1', name: 'Ada Admin' } },
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

interface Init {
  params?: { path?: Record<string, string> }
  body?: unknown
}

let ranges: NumberRange[]
let classes: ClassNumberRange[]

const contracts: NumberRange = {
  id: 'r1',
  key: 'contracts',
  name: 'Contracts',
  pattern: 'VTR-{YYYY}-{NNNN}',
  startValue: 1,
  yearlyReset: true,
  nextNumber: 'VTR-2026-0007',
  nextCounter: 7,
  classIds: ['c1'],
  createdBy: 'admin-1',
  createdAt: '2026-10-01T08:00:00Z',
  updatedAt: '2026-10-01T08:00:00Z',
}

beforeEach(() => {
  ranges = [contracts]
  classes = [
    {
      classId: 'c1',
      classKey: 'contract',
      label: 'Contract',
      mode: 'range',
      numberRangeId: 'r1',
      effectiveRangeId: 'r1',
    },
    {
      classId: 'c2',
      classKey: 'service-contract',
      label: 'Service contract',
      parentId: 'c1',
      mode: 'inherit',
      effectiveRangeId: 'r1',
      inheritedFrom: 'c1',
    },
    { classId: 'c3', classKey: 'policy', label: 'Policy', mode: 'inherit' },
  ]
})

/** The backend's check of a pattern, simplified: one counter, year needed for a yearly reset. */
function preview(body: { pattern: string; startValue?: number; yearlyReset?: boolean }) {
  const problems = [
    ...(/\{N+\}/.test(body.pattern) ? [] : ['the pattern needs exactly one counter, e.g. {NNNN}']),
    ...(body.yearlyReset && !body.pattern.includes('{YY')
      ? ['a yearly reset needs the year in the pattern']
      : []),
  ]
  if (problems.length > 0) return { valid: false, problems }
  const digits = /\{(N+)\}/.exec(body.pattern)?.[1]?.length ?? 1
  const counter = String(body.startValue ?? 1).padStart(digits, '0')
  return {
    valid: true,
    problems: [],
    example: body.pattern
      .replace('{YYYY}', '2026')
      .replace('{YY}', '26')
      .replace(/\{N+\}/, counter),
  }
}

function renderAdmin() {
  const get = vi.fn((p: string) => {
    switch (p) {
      case '/api/v1/me':
        return ok({ userId: 'admin-1', tenantId: 'tenant-1', roles: ['TenantAdmin'] })
      case '/api/v1/groups':
        return ok([])
      case '/api/v1/workflow/tasks':
        return ok([])
      case '/api/v1/admin/number-ranges':
        return ok(ranges)
      case '/api/v1/admin/number-range-assignments':
        return ok(classes)
      default:
        return failed(404, { code: 'not_found', message: `unexpected ${p}` })
    }
  })
  const post = vi.fn((p: string, init?: Init) => {
    if (p === '/api/v1/admin/number-ranges/preview') {
      return ok(preview(init?.body as { pattern: string; startValue?: number }))
    }
    if (p === '/api/v1/admin/number-ranges') {
      const body = init?.body as NumberRange
      const created: NumberRange = {
        ...contracts,
        ...body,
        id: 'r2',
        nextNumber: preview(body).example ?? '',
        classIds: [],
      }
      ranges = [...ranges, created]
      return ok(created, 201)
    }
    return failed(404, { code: 'not_found', message: `unexpected ${p}` })
  })
  const put = vi.fn((p: string, init?: Init) => {
    if (p === '/api/v1/admin/number-ranges/{id}') {
      const body = init?.body as NumberRange
      const changed = { ...contracts, ...body, nextNumber: preview(body).example ?? '' }
      ranges = ranges.map((r) => (r.id === changed.id ? changed : r))
      return ok(changed)
    }
    if (p === '/api/v1/admin/number-range-assignments/{classId}') {
      const classId = init?.params?.path?.classId ?? ''
      const rangeId = (init?.body as { numberRangeId?: string }).numberRangeId
      classes = classes.map((c) =>
        c.classId === classId
          ? {
              ...c,
              mode: rangeId ? 'range' : 'none',
              numberRangeId: rangeId,
              effectiveRangeId: rangeId,
              inheritedFrom: undefined,
            }
          : c,
      )
      return ok(classes.find((c) => c.classId === classId))
    }
    return failed(404, { code: 'not_found', message: `unexpected ${p}` })
  })
  const del = vi.fn(() => failed(404, { code: 'not_found', message: 'unexpected' }))
  const client = { GET: get, POST: post, PUT: put, DELETE: del } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: ['/admin'] }))
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <RouterProvider router={router} context={{ auth: signedIn }} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { get, post, put, del, user: userEvent.setup() }
}

async function openTab(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.click(await screen.findByRole('tab', { name }, { timeout: 3000 }))
}

describe('admin: number ranges (DMS-113)', () => {
  it('lists the ranges with their next number and the classes with their effective range', async () => {
    const { user } = renderAdmin()
    await openTab(user, 'Number ranges')

    const table = await screen.findByRole('table', { name: 'Number ranges of the tenant' })
    const row = within(table).getAllByRole('row')[1] as HTMLElement
    expect(within(row).getByText('VTR-{YYYY}-{NNNN}')).toBeInTheDocument()
    expect(within(row).getByText('VTR-2026-0007')).toBeInTheDocument()
    expect(within(row).getByText('Contract')).toBeInTheDocument()

    await screen.findByRole('table', { name: 'Document classes and their number range' })
    expect(screen.getByTestId('effective-contract')).toHaveTextContent('Contracts')
    expect(screen.getByTestId('effective-service-contract')).toHaveTextContent(
      'Contracts (from Contract)',
    )
    expect(screen.getByTestId('effective-policy')).toHaveTextContent('No numbers')
    expect(screen.getByLabelText('Number range of Service contract')).toHaveValue('inherit')
  })

  it('previews the next number while typing and creates a range', async () => {
    const { post, user } = renderAdmin()
    await openTab(user, 'Number ranges')
    await screen.findByRole('table', { name: 'Number ranges of the tenant' })

    await user.type(screen.getByLabelText('Key *'), 'policies')
    await user.type(screen.getByLabelText('Name *'), 'Policies')
    // userEvent treats { as a key descriptor; {{ types a literal brace.
    await user.type(screen.getByLabelText('Pattern *'), 'RL-{{NNN}')
    expect(await screen.findByText('Next number: RL-001')).toBeInTheDocument()

    await user.click(screen.getByLabelText('Start again every year'))
    const problem = await screen.findByText(/yearly reset needs the year/)
    expect(problem.closest('[role="alert"]')).not.toBeNull()
    await user.click(screen.getByLabelText('Start again every year'))

    await user.click(screen.getByRole('button', { name: 'Create number range' }))
    await waitFor(() => {
      expect(post).toHaveBeenCalledWith('/api/v1/admin/number-ranges', {
        body: {
          key: 'policies',
          name: 'Policies',
          pattern: 'RL-{NNN}',
          startValue: 1,
          yearlyReset: false,
        },
      })
    })
    expect(await screen.findByTestId('next-policies')).toHaveTextContent('RL-001')
  })

  it('checks the key and start value before creating', async () => {
    const { post, user } = renderAdmin()
    await openTab(user, 'Number ranges')
    await screen.findByRole('table', { name: 'Number ranges of the tenant' })

    await user.type(screen.getByLabelText('Key *'), 'Policies!')
    await user.type(screen.getByLabelText('Name *'), 'Policies')
    await user.clear(screen.getByLabelText('Start value'))
    await user.type(screen.getByLabelText('Start value'), '-3')
    await user.click(screen.getByRole('button', { name: 'Create number range' }))

    expect(await screen.findByText(/Lowercase letters, digits and hyphens/)).toHaveAttribute(
      'role',
      'alert',
    )
    expect(screen.getByText('Enter a whole number from 0.')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalledWith('/api/v1/admin/number-ranges', expect.anything())
  })

  it('edits a range (future numbers only) and assigns ranges to classes', async () => {
    const { put, user } = renderAdmin()
    await openTab(user, 'Number ranges')
    await screen.findByRole('table', { name: 'Number ranges of the tenant' })

    await user.click(screen.getByRole('button', { name: 'Edit number range Contracts' }))
    const editor = screen.getByRole('region', { name: 'Edit Contracts' })
    expect(within(editor).getByText(/only affect numbers assigned from now on/)).toBeInTheDocument()
    const start = within(editor).getByLabelText('Start value')
    await user.clear(start)
    await user.type(start, '100')
    await user.click(within(editor).getByRole('button', { name: 'Save changes' }))
    await waitFor(() => {
      expect(put).toHaveBeenCalledWith('/api/v1/admin/number-ranges/{id}', {
        params: { path: { id: 'r1' } },
        body: {
          name: 'Contracts',
          pattern: 'VTR-{YYYY}-{NNNN}',
          startValue: 100,
          yearlyReset: true,
        },
      })
    })

    await user.selectOptions(screen.getByLabelText('Number range of Policy'), 'r1')
    await waitFor(() => {
      expect(put).toHaveBeenCalledWith('/api/v1/admin/number-range-assignments/{classId}', {
        params: { path: { classId: 'c3' } },
        body: { numberRangeId: 'r1' },
      })
    })
    await waitFor(() => {
      expect(screen.getByTestId('effective-policy')).toHaveTextContent('Contracts')
    })
  })

  it('shows the tab in German', async () => {
    setLocale('de')
    const { user } = renderAdmin()
    await openTab(user, 'Nummernkreise')

    expect(
      await screen.findByRole('table', { name: 'Nummernkreise des Mandanten' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Nummernkreis anlegen' })).toBeInTheDocument()
    expect(screen.getByText('Zuordnung zu Dokumentenklassen')).toBeInTheDocument()
    expect(await screen.findByTestId('effective-service-contract')).toHaveTextContent(
      'Contracts (von Contract)',
    )
  })
})
