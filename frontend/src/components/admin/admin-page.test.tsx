import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AuthContextProps } from 'react-oidc-context'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { Deputyship, NewDeputyship } from '@/api/deputyships'
import type { Group, GroupMember } from '@/api/groups'
import type { Me } from '@/api/me'
import {
  checkDeputyship,
  emptyDeputyshipInput,
  toRequest,
} from '@/components/deputies/deputyship-input'
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
  params?: { path?: Record<string, string>; query?: Record<string, unknown> }
  body?: unknown
}

interface State {
  me: Me
  groups: Group[]
  members: Record<string, GroupMember[]>
  deputyships: Deputyship[]
}

let state: State

/** The tenant's people as the user directory finds them (DMS-111). */
const people = [
  { id: 'reviewer-1', displayName: 'Rita Reviewer', email: 'rita@example.com' },
  { id: 'approver-2', displayName: 'Arne Approver', email: 'arne@example.com' },
  { id: 'deputy-2', displayName: 'Dora Deputy', email: 'dora@example.com' },
  { id: 'nobody', displayName: 'Nora Nobody' },
]

/** Like the backend: every word starts a part of the name or the email. */
function searchPeople(q: string) {
  const words = q.toLowerCase().split(/\s+/).filter(Boolean)
  return people.filter((p) => {
    const parts = [...p.displayName.toLowerCase().split(' '), p.email ?? '']
    return words.every((w) => parts.some((part) => part.startsWith(w)))
  })
}

function deputyship(over: Partial<Deputyship>): Deputyship {
  return {
    id: 'd1',
    principal: 'approver-1',
    deputy: 'deputy-1',
    validFrom: '2026-11-01T08:00:00Z',
    validUntil: '2026-11-15T18:00:00Z',
    roles: ['Approver'],
    createdBy: 'approver-1',
    createdAt: '2026-10-28T08:00:00Z',
    status: 'Scheduled',
    ...over,
  }
}

beforeEach(() => {
  state = {
    me: { userId: 'admin-1', tenantId: 'tenant-1', roles: ['TenantAdmin'] },
    groups: [{ key: 'board', name: 'Board' }],
    members: {
      board: [
        { userId: 'approver-1', addedBy: 'admin-1', addedAt: '2026-10-01T10:00:00Z' },
        {
          userId: 'reviewer-9',
          addedBy: 'admin-1',
          addedAt: '2026-09-01T10:00:00Z',
          removedBy: 'admin-1',
          removedAt: '2026-09-20T10:00:00Z',
        },
      ],
    },
    deputyships: [
      deputyship({}),
      deputyship({
        id: 'd2',
        principal: 'admin-1',
        deputy: 'admin-2',
        status: 'Ended',
        endedAt: '2026-10-02T10:00:00Z',
        endedBy: 'admin-1',
      }),
    ],
  }
})

function renderAt(path: string) {
  const get = vi.fn((p: string, init?: Init) => {
    switch (p) {
      case '/api/v1/me':
        return ok(state.me)
      case '/api/v1/groups':
        return ok(state.groups)
      case '/api/v1/groups/{key}/members':
        return ok(state.members[init?.params?.path?.key ?? ''] ?? [])
      case '/api/v1/deputyships':
        return ok(
          init?.params?.query?.all === true
            ? state.deputyships
            : state.deputyships.filter(
                (d) => d.principal === state.me.userId || d.deputy === state.me.userId,
              ),
        )
      case '/api/v1/workflow/tasks':
        return ok([])
      case '/api/v1/users/search':
        return ok(searchPeople((init?.params?.query?.q as string | undefined) ?? ''))
      default:
        return failed(404, { code: 'not_found', message: `unexpected ${p}` })
    }
  })
  const post = vi.fn((p: string, init?: Init) => {
    if (p === '/api/v1/groups') {
      const body = init?.body as Group
      if (state.groups.some((g) => g.key === body.key)) {
        return failed(409, { code: 'conflict', message: `the group ${body.key} exists already` })
      }
      state.groups = [...state.groups, body]
      state.members[body.key] = []
      return ok(body, 201)
    }
    if (p === '/api/v1/deputyships') {
      const body = init?.body as NewDeputyship
      if (body.deputy === 'nobody') {
        return failed(422, {
          code: 'invalid',
          message: 'deputy nobody does not hold role Approver (same authority required)',
        })
      }
      const created = deputyship({
        id: 'd3',
        principal: body.principal ?? state.me.userId,
        deputy: body.deputy,
        validUntil: body.validUntil,
        status: 'Active',
      })
      state.deputyships = [created, ...state.deputyships]
      return ok(created, 201)
    }
    return failed(404, { code: 'not_found', message: `unexpected ${p}` })
  })
  const put = vi.fn((p: string, init?: Init) => {
    const key = init?.params?.path?.key ?? ''
    if (p === '/api/v1/groups/{key}') {
      const name = (init?.body as { name: string }).name
      state.groups = state.groups.map((g) => (g.key === key ? { ...g, name } : g))
      return ok({ key, name })
    }
    if (p === '/api/v1/groups/{key}/members/{userId}') {
      const userId = init?.params?.path?.userId ?? ''
      state.members[key] = [
        { userId, addedBy: 'admin-1', addedAt: '2026-10-28T10:00:00Z' },
        ...(state.members[key] ?? []),
      ]
      return ok(state.members[key])
    }
    return failed(404, { code: 'not_found', message: `unexpected ${p}` })
  })
  const del = vi.fn((p: string, init?: Init) => {
    if (p === '/api/v1/groups/{key}/members/{userId}') {
      const key = init?.params?.path?.key ?? ''
      const userId = init?.params?.path?.userId
      state.members[key] = (state.members[key] ?? []).map((m) =>
        m.userId === userId && !m.removedAt
          ? { ...m, removedBy: 'admin-1', removedAt: '2026-10-28T11:00:00Z' }
          : m,
      )
      return ok(state.members[key])
    }
    if (p === '/api/v1/deputyships/{id}') {
      const id = init?.params?.path?.id
      state.deputyships = state.deputyships.map((d) =>
        d.id === id
          ? { ...d, status: 'Ended', endedAt: '2026-10-28T12:00:00Z', endedBy: 'admin-1' }
          : d,
      )
      return ok(state.deputyships.find((d) => d.id === id))
    }
    return failed(404, { code: 'not_found', message: `unexpected ${p}` })
  })
  const client = { GET: get, POST: post, PUT: put, DELETE: del } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: [path] }))
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <RouterProvider router={router} context={{ auth: signedIn }} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { router, get, post, put, del, user: userEvent.setup() }
}

/** Types into the people picker labelled `label` and picks the person named `name` from the hits. */
async function pickPerson(
  user: ReturnType<typeof userEvent.setup>,
  label: string,
  query: string,
  name: string,
) {
  await user.type(screen.getByRole('combobox', { name: label }), query)
  const hits = await screen.findByRole('listbox', { name: 'People found' })
  await user.click(within(hits).getByRole('option', { name: new RegExp(name) }))
}

// The first render loads the route chunk and several queries; allow for a busy test machine.
const membersTable = () => screen.findByRole('table', { name: /Members of/ }, { timeout: 3000 })

describe('admin: access groups (DMS-105)', () => {
  it('lists the members of a group with their history', async () => {
    renderAt('/admin')

    const table = await membersTable()
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(2)
    expect(within(rows[0] as HTMLElement).getByText('approver-1')).toBeInTheDocument()
    expect(within(rows[1] as HTMLElement).getByText('Former')).toBeInTheDocument()
    expect(within(rows[1] as HTMLElement).queryByRole('button')).not.toBeInTheDocument()
  })

  it('adds and removes members', async () => {
    const { put, del, user } = renderAt('/admin')
    await membersTable()

    // The member is picked by name (DMS-111); the user id is stored.
    await pickPerson(user, 'Person', 'rit', 'Rita Reviewer')
    expect(screen.getByRole('combobox', { name: 'Person' })).toHaveValue('Rita Reviewer')
    expect(screen.getByText('rita@example.com')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Add member' }))
    await waitFor(() => {
      expect(put).toHaveBeenCalledWith('/api/v1/groups/{key}/members/{userId}', {
        params: { path: { key: 'board', userId: 'reviewer-1' } },
      })
    })
    expect(await screen.findByText('Rita Reviewer added to Board.')).toBeInTheDocument()
    expect(
      await screen.findByRole('button', { name: 'Remove Rita Reviewer from Board' }),
    ).toBeEnabled()
    expect(screen.getByRole('combobox', { name: 'Person' })).toHaveValue('')

    await user.click(screen.getByRole('button', { name: 'Remove approver-1 from Board' }))
    await waitFor(() => {
      expect(del).toHaveBeenCalledWith('/api/v1/groups/{key}/members/{userId}', {
        params: { path: { key: 'board', userId: 'approver-1' } },
      })
    })
    await waitFor(() => {
      expect(
        screen.queryByRole('button', { name: 'Remove approver-1 from Board' }),
      ).not.toBeInTheDocument()
    })
  })

  it('creates a group after checking the key and renames it', async () => {
    const { post, put, user } = renderAt('/admin')
    await membersTable()

    await user.type(screen.getByLabelText('Key *'), 'Legal Board')
    await user.type(screen.getByLabelText('Name *'), 'Legal board')
    await user.click(screen.getByRole('button', { name: 'Create group' }))
    expect(await screen.findByText(/Lowercase letters, digits and hyphens/)).toHaveAttribute(
      'role',
      'alert',
    )
    expect(post).not.toHaveBeenCalled()

    await user.clear(screen.getByLabelText('Key *'))
    await user.type(screen.getByLabelText('Key *'), 'legal-board')
    await user.click(screen.getByRole('button', { name: 'Create group' }))
    await waitFor(() => {
      expect(post).toHaveBeenCalledWith('/api/v1/groups', {
        body: { key: 'legal-board', name: 'Legal board' },
      })
    })
    // the new group is selected
    expect(await screen.findByRole('heading', { name: 'Legal board' })).toBeInTheDocument()

    const name = screen.getByLabelText('Name')
    await user.clear(name)
    await user.type(name, 'Legal and board')
    await user.click(screen.getByRole('button', { name: 'Rename' }))
    await waitFor(() => {
      expect(put).toHaveBeenCalledWith('/api/v1/groups/{key}', {
        params: { path: { key: 'legal-board' } },
        body: { name: 'Legal and board' },
      })
    })
    expect(
      await within(screen.getByRole('navigation', { name: 'Access groups' })).findByText(
        'Legal and board',
      ),
    ).toBeInTheDocument()
  })

  it('shows a hint instead of the administration to other users', async () => {
    state.me = { ...state.me, roles: ['Approver'] }
    renderAt('/admin')
    expect(
      await screen.findByText('Only tenant administrators can use the administration.'),
    ).toBeInTheDocument()
  })
})

describe('deputyships (DMS-99)', () => {
  it('admins see all deputyships and set one for someone else', async () => {
    const { post, user } = renderAt('/admin')
    await membersTable()
    await user.click(screen.getByRole('tab', { name: 'Deputyships' }))

    const table = await screen.findByRole('table', { name: 'All deputyships of the tenant' })
    expect(within(table).getAllByRole('row')).toHaveLength(3)

    await pickPerson(user, 'Principal (optional)', 'arne', 'Arne Approver')
    await pickPerson(user, 'Deputy *', 'dora@', 'Dora Deputy')
    await user.type(screen.getByLabelText('Until *'), '2099-01-31T18:00')
    await user.click(screen.getByRole('button', { name: 'Set deputy' }))
    await waitFor(() => {
      expect(post).toHaveBeenCalledWith('/api/v1/deputyships', {
        body: expect.objectContaining({
          principal: 'approver-2',
          deputy: 'deputy-2',
          validUntil: new Date('2099-01-31T18:00').toISOString(),
        }) as unknown,
      })
    })
    expect(await screen.findByText(/Deputy set: Dora Deputy for Arne Approver/)).toBeInTheDocument()
  })

  it('everyone manages their own deputies from the user menu page', async () => {
    state.me = { userId: 'approver-1', tenantId: 'tenant-1', roles: ['Approver'] }
    state.deputyships = [
      ...state.deputyships,
      deputyship({ id: 'd4', principal: 'approver-3', deputy: 'approver-1', status: 'Active' }),
    ]
    const { del, user } = renderAt('/deputies')

    const mine = await screen.findByRole('table', { name: 'My deputies' })
    expect(within(mine).getByText('deputy-1')).toBeInTheDocument()
    const standing = screen.getByRole('table', { name: 'Deputyships in which I am the deputy' })
    expect(within(standing).getByText('approver-3')).toBeInTheDocument()

    await user.click(
      within(mine).getByRole('button', { name: 'End deputyship of deputy-1 for approver-1' }),
    )
    await waitFor(() => {
      expect(del).toHaveBeenCalledWith('/api/v1/deputyships/{id}', {
        params: { path: { id: 'd1' } },
      })
    })
    expect(await within(mine).findByText('Ended')).toBeInTheDocument()
  })

  it('checks the input and shows the backend reason', async () => {
    state.me = { userId: 'approver-1', tenantId: 'tenant-1', roles: ['Approver'] }
    const { post, user } = renderAt('/deputies')
    await screen.findByRole('table', { name: 'My deputies' })

    await user.click(screen.getByRole('button', { name: 'Set deputy' }))
    expect(await screen.findByText('Select the deputy.')).toBeInTheDocument()
    expect(screen.getByText('Choose the end of the period.')).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Deputy *' })).toHaveAttribute(
      'aria-invalid',
      'true',
    )
    expect(post).not.toHaveBeenCalled()

    // Keyboard only: arrow down to the hit, Enter picks it and does not submit the form.
    await user.type(screen.getByRole('combobox', { name: 'Deputy *' }), 'nora')
    await screen.findByRole('option', { name: /Nora Nobody/ })
    await user.keyboard('{ArrowDown}{Enter}')
    expect(screen.getByRole('combobox', { name: 'Deputy *' })).toHaveValue('Nora Nobody')
    expect(post).not.toHaveBeenCalled()
    await user.type(screen.getByLabelText('Until *'), '2099-01-31T18:00')
    await user.click(screen.getByRole('checkbox', { name: 'Approver' }))
    await user.click(screen.getByRole('button', { name: 'Set deputy' }))
    expect(
      await screen.findByText(
        'deputy nobody does not hold role Approver (same authority required)',
      ),
    ).toBeInTheDocument()
    expect(post).toHaveBeenCalledWith('/api/v1/deputyships', {
      body: expect.objectContaining({ deputy: 'nobody', roles: ['Approver'] }) as unknown,
    })
  })

  it('validates and converts the period', () => {
    const now = new Date('2026-10-28T12:00:00Z')
    expect(checkDeputyship({ ...emptyDeputyshipInput, deputy: 'd' }, now)).toEqual({
      validUntil: 'Choose the end of the period.',
    })
    expect(
      checkDeputyship(
        {
          ...emptyDeputyshipInput,
          deputy: 'd',
          validFrom: '2026-11-02T08:00',
          validUntil: '2026-11-01T08:00',
        },
        now,
      ).validUntil,
    ).toBe('The period must end in the future and after it starts.')
    expect(
      checkDeputyship({ ...emptyDeputyshipInput, principal: 'a', deputy: 'a' }, now).deputy,
    ).toBe('A person cannot be their own deputy.')
    expect(
      toRequest({
        ...emptyDeputyshipInput,
        deputy: ' d ',
        validUntil: '2026-11-01T08:00',
        reason: ' ',
      }),
    ).toEqual({
      principal: undefined,
      deputy: 'd',
      validFrom: undefined,
      validUntil: new Date('2026-11-01T08:00').toISOString(),
      roles: undefined,
      documentClass: undefined,
      reason: undefined,
    })
  })
})

describe('user menu', () => {
  it('links to the deputies page', async () => {
    const { router, user } = renderAt('/admin')
    await membersTable()
    await user.click(screen.getByRole('button', { name: 'User menu' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Deputies' }))
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/deputies')
    })
  })
})
