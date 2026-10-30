import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AuthContextProps } from 'react-oidc-context'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { Notification, NotificationPreferences } from '@/api/notifications'
import { setLocale } from '@/i18n'
import { createAppRouter } from '@/router'

const signedIn = {
  isAuthenticated: true,
  isLoading: false,
  user: { profile: { sub: 'owner-1', preferred_username: 'owner' } },
  error: undefined,
  activeNavigator: undefined,
  signinRedirect: vi.fn(),
  signoutRedirect: vi.fn(),
} as unknown as AuthContextProps

vi.mock('react-oidc-context', () => ({ useAuth: () => signedIn }))

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>

const ok = (data: unknown, status = 200): Reply =>
  Promise.resolve({
    data,
    error: undefined,
    response: new Response(null, { status }),
  })
const failed = (status: number): Reply =>
  Promise.resolve({ data: undefined, error: undefined, response: new Response(null, { status }) })

const notification = (over: Partial<Notification>): Notification => ({
  id: 'n1',
  kind: 'workflow.task-assigned',
  category: 'workflow',
  subject: 'Review requested: Travel policy (2.0)',
  body: 'You are assigned to step “Legal review” of “Travel policy” (version 2.0).',
  link: '/tasks',
  createdAt: '2026-10-29T09:00:00Z',
  ...over,
})

let state: {
  roles: string[]
  items: Notification[]
  preferences: NotificationPreferences
  owned: unknown[]
}

beforeEach(() => {
  state = {
    roles: ['Reader', 'Owner'],
    items: [
      notification({}),
      notification({
        id: 'n2',
        kind: 'acknowledgement.reminder',
        category: 'acknowledgement',
        subject: 'Reminder: acknowledgement open – Code of conduct (1.0)',
        body: 'You have not acknowledged “Code of conduct” (version 1.0) yet.',
        link: '/documents/d7',
        createdAt: '2026-10-28T09:00:00Z',
      }),
      notification({
        id: 'n3',
        kind: 'deputyship.started',
        category: 'deputyship',
        subject: 'Your deputy starts',
        body: 'Ben stands in for you from now until 14 Nov 2026.',
        link: undefined,
        readAt: '2026-10-27T10:00:00Z',
        createdAt: '2026-10-27T09:00:00Z',
      }),
    ],
    preferences: {
      tenantLanguage: 'de',
      emailAvailable: true,
      categories: [
        { category: 'workflow', email: true, emailByDefault: true },
        { category: 'acknowledgement', email: true, emailByDefault: true },
        { category: 'deputyship', email: true, emailByDefault: true },
        { category: 'publication', email: true, emailByDefault: true },
        { category: 'legal-hold', email: false, emailByDefault: false },
      ],
    },
    owned: [
      {
        documentId: 'd9',
        title: 'Leave policy',
        versionId: 'v9',
        versionLabel: '3.0',
        audience: 12,
        acknowledged: 9,
        open: 3,
      },
    ],
  }
})

function renderAt(path: string) {
  const get = vi.fn((p: string, init?: { params?: { query?: Record<string, unknown> } }) => {
    switch (p) {
      case '/api/v1/me':
        return ok({ userId: 'owner-1', tenantId: 'tenant-1', roles: state.roles })
      case '/api/v1/notifications/unread-count':
        return ok({ unread: state.items.filter((n) => !n.readAt).length })
      case '/api/v1/notifications': {
        const query = init?.params?.query ?? {}
        const shown = query.unread ? state.items.filter((n) => !n.readAt) : state.items
        const offset = Number(query.offset ?? 0)
        const limit = Number(query.limit ?? 20)
        return ok({
          items: shown.slice(offset, offset + limit),
          total: shown.length,
          unread: state.items.filter((n) => !n.readAt).length,
        })
      }
      case '/api/v1/notifications/preferences':
        return ok(state.preferences)
      case '/api/v1/acknowledgements/owned':
        return ok(state.owned)
      case '/api/v1/workflow/tasks':
      case '/api/v1/reviews':
      case '/api/v1/acknowledgements':
      case '/api/v1/documents':
        return ok([])
      default:
        return failed(404)
    }
  })
  const post = vi.fn((p: string, init?: { params?: { path?: { id?: string } } }) => {
    if (p === '/api/v1/notifications/read-all') {
      const marked = state.items.filter((n) => !n.readAt).length
      state.items = state.items.map((n) => ({ ...n, readAt: n.readAt ?? '2026-10-30T08:00:00Z' }))
      return ok({ marked })
    }
    if (p === '/api/v1/notifications/{id}/read') {
      state.items = state.items.map((n) =>
        n.id === init?.params?.path?.id ? { ...n, readAt: '2026-10-30T08:00:00Z' } : n,
      )
      return ok(undefined, 204)
    }
    return failed(404)
  })
  const put = vi.fn(
    (_p: string, init: { body: { language?: string; email: Record<string, boolean> } }) => {
      state.preferences = {
        ...state.preferences,
        language: init.body.language || undefined,
        categories: (state.preferences.categories ?? []).map((c) => ({
          ...c,
          email: init.body.email[c.category] ?? c.email,
        })),
      }
      return ok(state.preferences)
    },
  )
  const client = { GET: get, POST: post, PUT: put } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: [path] }))
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <RouterProvider router={router} context={{ auth: signedIn }} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return { router, get, post, put, user: userEvent.setup() }
}

describe('notification bell (DMS-130)', () => {
  it('shows the unread count in the badge and in the accessible name', async () => {
    renderAt('/')
    const bell = await screen.findByRole('button', { name: 'Notifications, 2 unread' })
    expect(bell).toHaveTextContent('2')
  })

  it('lists the newest notifications; opening one marks it read and follows its link', async () => {
    const { user, router, post } = renderAt('/')
    await user.click(await screen.findByRole('button', { name: 'Notifications, 2 unread' }))
    const menu = await screen.findByRole('menu')
    const item = await within(menu).findByRole('menuitem', {
      name: /Reminder: acknowledgement open – Code of conduct/,
    })
    expect(item).toHaveTextContent('Unread')
    await user.click(item)
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/documents/d7')
    })
    expect(post).toHaveBeenCalledWith('/api/v1/notifications/{id}/read', {
      params: { path: { id: 'n2' } },
    })
    expect(
      await screen.findByRole('button', { name: 'Notifications, 1 unread' }),
    ).toBeInTheDocument()
  })

  it('marks all as read from the menu', async () => {
    const { user, post } = renderAt('/')
    await user.click(await screen.findByRole('button', { name: 'Notifications, 2 unread' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Mark all as read' }))
    expect(post).toHaveBeenCalledWith('/api/v1/notifications/read-all')
    expect(await screen.findByRole('button', { name: 'Notifications' })).toBeInTheDocument()
    expect(await screen.findByText('2 notifications marked as read.')).toBeInTheDocument()
  })

  it('without the notification service the bell stays quiet', async () => {
    state.items = []
    renderAt('/')
    expect(await screen.findByRole('button', { name: 'Notifications' })).not.toHaveTextContent(/\d/)
  })
})

describe('notifications page', () => {
  it('lists notifications with category and filter for unread ones', async () => {
    const { user, get } = renderAt('/notifications')
    const list = await screen.findByRole('list', { name: 'Notifications' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(3)
    expect(within(list).getByText('Your deputy starts')).toBeInTheDocument()
    expect(within(list).getAllByText(/Reviews and approvals/)).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: 'Unread only' }))
    await waitFor(() => {
      expect(
        within(screen.getByRole('list', { name: 'Notifications' })).getAllByRole('listitem'),
      ).toHaveLength(2)
    })
    expect(get).toHaveBeenCalledWith('/api/v1/notifications', {
      params: { query: { unread: true, offset: 0, limit: 20 } },
    })
    expect(screen.getByRole('button', { name: 'Unread only' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
  })

  it('pages through many notifications', async () => {
    state.items = Array.from({ length: 25 }, (_, i) =>
      notification({ id: `n${i}`, subject: `Subject ${i}`, readAt: '2026-10-29T10:00:00Z' }),
    )
    const { user } = renderAt('/notifications')
    expect(await screen.findByText('1–20 of 25')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByText('21–25 of 25')).toBeInTheDocument()
    expect(screen.getByText('Subject 24')).toBeInTheDocument()
  })
})

describe('notification settings', () => {
  it('saves the language and email per category', async () => {
    const { user, put } = renderAt('/settings/notifications')
    expect(
      await screen.findByRole('heading', { name: 'Notification settings', level: 1 }),
    ).toBeInTheDocument()
    expect(await screen.findByLabelText('Language of notifications')).toHaveValue('')
    expect(
      screen.getByRole('option', { name: 'Like the organization (German)' }),
    ).toBeInTheDocument()
    const workflow = screen.getByRole('checkbox', { name: 'Reviews and approvals' })
    expect(workflow).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Legal holds' })).not.toBeChecked()
    await user.click(workflow)
    await user.selectOptions(screen.getByLabelText('Language of notifications'), 'en')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => {
      expect(put).toHaveBeenCalledTimes(1)
    })
    expect(put.mock.calls[0]?.[1]).toEqual({
      body: {
        language: 'en',
        email: {
          workflow: false,
          acknowledgement: true,
          deputyship: true,
          publication: true,
          'legal-hold': false,
        },
      },
    })
    expect(await screen.findByText('Notification settings saved.')).toBeInTheDocument()
  })

  it('says when email is not available', async () => {
    state.preferences = { ...state.preferences, emailAvailable: false }
    renderAt('/settings/notifications')
    expect(await screen.findByText(/Email is not set up on this platform/)).toBeInTheDocument()
    expect(await screen.findByRole('checkbox', { name: 'Reviews and approvals' })).toBeDisabled()
  })
})

describe('owner dashboard', () => {
  it('lists documents of the owner with open read confirmations', async () => {
    renderAt('/')
    const list = await screen.findByRole('list', {
      name: 'My documents with open read confirmations',
    })
    expect(within(list).getByRole('link', { name: 'Leave policy' })).toHaveAttribute(
      'href',
      '/documents/d9',
    )
    expect(within(list).getByText('3 of 12 open')).toBeInTheDocument()
  })

  it('is only shown to owners', async () => {
    state.roles = ['Reader']
    renderAt('/')
    await screen.findByRole('heading', { name: /Welcome/ })
    expect(
      screen.queryByRole('heading', { name: 'Read confirmations of my documents' }),
    ).not.toBeInTheDocument()
  })
})

describe('German texts', () => {
  it('bell, page and settings speak German', async () => {
    setLocale('de')
    const { user } = renderAt('/settings/notifications')
    expect(
      await screen.findByRole('button', { name: 'Benachrichtigungen, 2 ungelesen' }),
    ).toBeInTheDocument()
    expect(
      await screen.findByRole('heading', { name: 'Benachrichtigungseinstellungen', level: 1 }),
    ).toBeInTheDocument()
    expect(await screen.findByLabelText('Sprache der Benachrichtigungen')).toBeInTheDocument()
    expect(
      screen.getByRole('option', { name: 'Wie die Organisation (Deutsch)' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Prüfungen und Freigaben' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Kenntnisnahmen' })).toBeChecked()
    await user.click(screen.getByRole('button', { name: 'Benachrichtigungen, 2 ungelesen' }))
    expect(
      await screen.findByRole('menuitem', { name: 'Alle als gelesen markieren' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('menuitem', { name: 'Alle Benachrichtigungen anzeigen' }),
    ).toBeInTheDocument()
  })
})
