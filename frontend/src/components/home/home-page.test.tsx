import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AuthContextProps } from 'react-oidc-context'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import type { DocumentSummary } from '@/api/documents'
import { createAppRouter } from '@/router'

const signedIn = {
  isAuthenticated: true,
  isLoading: false,
  user: { profile: { sub: 'author-1', preferred_username: 'author' } },
  error: undefined,
  activeNavigator: undefined,
  signinRedirect: vi.fn(),
  signoutRedirect: vi.fn(),
} as unknown as AuthContextProps

vi.mock('react-oidc-context', () => ({ useAuth: () => signedIn }))

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>

const ok = (data: unknown): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status: 200 }) })
const failed = (status: number): Reply =>
  Promise.resolve({ data: undefined, error: undefined, response: new Response(null, { status }) })

const doc = (over: Partial<DocumentSummary>): DocumentSummary => ({
  id: 'd1',
  title: 'Doc',
  templateId: 't1',
  templateVersion: 1,
  authorId: 'author-1',
  versionId: 'v1',
  version: '1.0',
  status: 'Draft',
  updatedAt: '2026-10-20T10:00:00Z',
  mine: true,
  ...over,
  kind: over.kind ?? 'Structured',
})

let state: {
  roles: string[]
  tasks: () => Reply
  documents: DocumentSummary[]
}

beforeEach(() => {
  state = {
    roles: ['Author'],
    tasks: () =>
      ok([
        {
          id: 'task-1',
          caseId: 'c1',
          documentId: 'd2',
          versionId: 'v2',
          round: 1,
          step: 'review',
          stepName: 'Legal review',
          kind: 'Review',
          lane: 'l',
          role: 'Reviewer',
          status: 'Open',
          createdAt: '2026-10-25T09:00:00Z',
        },
      ]),
    documents: [
      doc({ id: 'd1', title: 'My travel policy draft' }),
      doc({ id: 'd2', title: 'Service contract ACME', status: 'InReview', mine: false }),
      doc({
        id: 'd3',
        title: 'Lab safety policy',
        status: 'Published',
        mine: false,
        updatedAt: '2026-10-21T10:00:00Z',
      }),
    ],
  }
})

function renderAt(path: string) {
  const get = vi.fn((p: string) => {
    switch (p) {
      case '/api/v1/me':
        return ok({ userId: 'author-1', tenantId: 'tenant-1', roles: state.roles })
      case '/api/v1/workflow/tasks':
        return state.tasks()
      case '/api/v1/reviews':
        return ok([])
      case '/api/v1/acknowledgements':
        return ok([
          {
            documentId: 'd3',
            versionId: 'v3',
            title: 'Lab safety policy',
            versionLabel: '2.0',
            status: 'Published',
            opened: false,
            publishedAt: '2026-10-21T10:00:00Z',
          },
        ])
      case '/api/v1/documents':
        return ok(state.documents)
      case '/api/v1/compliance/overview':
        return ok({
          today: '2026-10-29',
          overdueReviews: 3,
          acknowledgements: { versions: 1, audience: 4, acknowledged: 1, open: 3, rate: 0.25 },
        })
      case '/api/v1/admin/templates':
        return ok([])
      case '/health':
        return ok({ status: 'ok', version: '9.9.9' })
      default:
        return failed(404)
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

const card = (name: string) =>
  screen.getByRole('heading', { name }).closest('[data-slot="card"]') as HTMLElement

describe('home dashboard (DMS-109)', () => {
  it('greets the user by user name and sets the tab title', async () => {
    renderAt('/')

    expect(await screen.findByRole('heading', { name: 'Welcome, author' })).toBeInTheDocument()
    expect(document.title).toBe('Home · DMS')
    expect(screen.getByRole('button', { name: 'User menu' })).toHaveTextContent('author')
  })

  it('shows open tasks, read confirmations, drafts and recently published documents', async () => {
    renderAt('/')
    await screen.findByRole('heading', { name: 'Welcome, author' })

    const tasks = await screen.findByRole('list', { name: 'Most urgent tasks' })
    expect(within(tasks).getByRole('link', { name: 'Service contract ACME' })).toBeInTheDocument()
    expect(within(card('My open tasks')).getByText('1 open task')).toBeInTheDocument()
    expect(
      within(card('My open tasks')).getByRole('link', { name: 'View all tasks' }),
    ).toHaveAttribute('href', '/tasks')

    const acks = await screen.findByRole('list', { name: 'Open read confirmations' })
    expect(within(acks).getByRole('link', { name: 'Lab safety policy' })).toBeInTheDocument()

    const drafts = await screen.findByRole('list', { name: 'Recently changed drafts' })
    expect(within(drafts).getByRole('link', { name: 'My travel policy draft' })).toBeInTheDocument()

    const published = screen.getByRole('list', { name: 'Recently published documents' })
    expect(within(published).getByRole('link', { name: 'Lab safety policy' })).toBeInTheDocument()
    expect(within(published).queryByText('My travel policy draft')).not.toBeInTheDocument()

    // Role cards only for holders of the role.
    expect(screen.queryByRole('heading', { name: 'Compliance' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'System status' })).not.toBeInTheDocument()
  })

  it('shows an empty state with the next step', async () => {
    state.documents = []
    renderAt('/')
    await screen.findByRole('heading', { name: 'Welcome, author' })

    const drafts = card('My drafts')
    expect(await within(drafts).findByText('No drafts')).toBeInTheDocument()
    expect(within(drafts).getByRole('link', { name: 'New document' })).toHaveAttribute(
      'href',
      '/documents/new',
    )
  })

  it('shows a friendly error with "Try again" that reloads the card', async () => {
    let fail = true
    state.tasks = () => (fail ? failed(500) : ok([]))
    const { user } = renderAt('/')
    await screen.findByRole('heading', { name: 'Welcome, author' })

    const tasks = card('My open tasks')
    expect(await within(tasks).findByRole('alert')).toHaveTextContent(
      'Something went wrong on the server',
    )
    fail = false
    await user.click(within(tasks).getByRole('button', { name: 'Try again' }))
    expect(await within(tasks).findByText('You are all caught up')).toBeInTheDocument()
  })

  it('adds the cards of compliance officers and tenant administrators', async () => {
    state.roles = ['ComplianceOfficer', 'TenantAdmin', 'TemplateDesigner']
    renderAt('/')
    await screen.findByRole('heading', { name: 'Welcome, author' })

    const compliance = await screen.findByRole('heading', { name: 'Compliance' })
    const complianceCard = compliance.closest('[data-slot="card"]') as HTMLElement
    expect(await within(complianceCard).findByText('3 overdue reviews')).toBeInTheDocument()
    expect(
      within(complianceCard).getByRole('link', { name: 'Open the compliance cockpit' }),
    ).toHaveAttribute('href', '/compliance')
    expect(screen.getByRole('heading', { name: 'System status' })).toBeInTheDocument()
    expect(await screen.findByText('No templates awaiting release')).toBeInTheDocument()
  })
})

describe('app shell (DMS-109)', () => {
  it('shows the open task count on "Tasks" without changing its name', async () => {
    renderAt('/')
    await screen.findByRole('heading', { name: 'Welcome, author' })

    const nav = screen.getByRole('navigation', { name: 'Main' })
    const tasks = within(nav).getByRole('link', { name: 'Tasks' })
    // one workflow task and one read confirmation
    await waitFor(() => {
      expect(tasks).toHaveAccessibleDescription('2 open tasks')
    })
  })

  it('highlights the section of nested pages', async () => {
    renderAt('/documents/new')

    expect(await screen.findByRole('heading', { name: 'New document' })).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Main' })
    expect(within(nav).getByRole('link', { name: 'Documents' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(within(nav).getByRole('link', { name: 'Home' })).not.toHaveAttribute('aria-current')
    expect(screen.getByRole('navigation', { name: 'Breadcrumb' })).toHaveTextContent(
      'DocumentsNew document',
    )
    expect(document.title).toBe('New document · DMS')
  })

  it('searches from the header', async () => {
    const { router, user } = renderAt('/')
    await screen.findByRole('heading', { name: 'Welcome, author' })

    await user.type(screen.getByRole('searchbox', { name: 'Search documents' }), 'travel{Enter}')

    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/search')
    })
    expect(router.state.location.search).toEqual({ q: 'travel' })
    // The search page has its own field; the header field is hidden there.
    expect(screen.queryByRole('searchbox', { name: 'Search documents' })).not.toBeInTheDocument()
  })

  it('moves focus to the page heading after navigation and offers a skip link', async () => {
    const { user } = renderAt('/')
    await screen.findByRole('heading', { name: 'Welcome, author' })

    await user.click(
      within(screen.getByRole('navigation', { name: 'Main' })).getByRole('link', {
        name: 'Tasks',
      }),
    )
    const heading = await screen.findByRole('heading', { name: 'Tasks', level: 1 })
    await waitFor(() => {
      expect(heading).toHaveFocus()
    })
    expect(document.title).toBe('Tasks · DMS')

    await user.click(screen.getByRole('link', { name: 'Skip to content' }))
    expect(screen.getByRole('main')).toHaveFocus()
  })

  it('opens the navigation from a menu on small screens', async () => {
    const { user } = renderAt('/')
    await screen.findByRole('heading', { name: 'Welcome, author' })

    await user.click(screen.getByRole('button', { name: 'Open menu' }))
    const menu = await screen.findByRole('menu')
    expect(within(menu).getByRole('menuitem', { name: 'Documents' })).toBeInTheDocument()
  })
})
