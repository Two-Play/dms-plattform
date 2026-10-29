import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory } from '@tanstack/react-router'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AuthContextProps } from 'react-oidc-context'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from '@/App'
import type { ApiClient } from '@/api/client'
import { ApiClientProvider } from '@/api/context'
import { setLocale } from '@/i18n'
import { createAppRouter } from '@/router'

const signedIn = {
  isAuthenticated: true,
  isLoading: false,
  user: { profile: { sub: 'author-1', name: 'Anna Author' } },
  error: undefined,
  activeNavigator: undefined,
  signinRedirect: vi.fn(),
  signoutRedirect: vi.fn(),
} as unknown as AuthContextProps

vi.mock('react-oidc-context', () => ({ useAuth: () => signedIn }))

type Reply = Promise<{ data: unknown; error: unknown; response: Response }>
const ok = (data: unknown): Reply =>
  Promise.resolve({ data, error: undefined, response: new Response(null, { status: 200 }) })

function renderApp(path: string) {
  const get = vi.fn((p: string): Reply => {
    switch (p) {
      case '/api/v1/me':
        return ok({ userId: 'author-1', tenantId: 'tenant-1', roles: ['Author'] })
      case '/api/v1/workflow/tasks':
        return ok([
          {
            id: 'task-1',
            caseId: 'c1',
            documentId: 'd1',
            versionId: 'v1',
            kind: 'Review',
            stepName: 'Legal review',
            role: 'Reviewer',
            round: 1,
            status: 'Open',
            createdAt: '2026-03-01T09:00:00Z',
            dueAt: '2026-03-02T09:00:00Z',
            candidates: ['reviewer-1'],
          },
        ])
      case '/api/v1/users':
        return ok([{ id: 'reviewer-1', displayName: 'Rita Reviewer' }])
      case '/api/v1/reviews':
      case '/api/v1/acknowledgements':
        return ok([])
      default:
        return Promise.resolve({
          data: undefined,
          error: undefined,
          response: new Response(null, { status: 404 }),
        })
    }
  })
  const client = { GET: get } as unknown as ApiClient
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createAppRouter(queryClient, createMemoryHistory({ initialEntries: [path] }))
  render(
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={client}>
        <App router={router} />
      </ApiClientProvider>
    </QueryClientProvider>,
  )
  return userEvent.setup()
}

describe('German UI (DMS-110)', () => {
  beforeEach(() => {
    setLocale('de')
  })

  it('shows the shell, the task inbox and dates in German, with user names', async () => {
    renderApp('/tasks')

    expect(await screen.findByRole('heading', { name: 'Aufgaben', level: 1 })).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Hauptnavigation' })
    expect(within(nav).getByRole('link', { name: 'Dokumente' })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: /Aufgaben/ })).toBeInTheDocument()
    expect(document.title).toBe('Aufgaben · DMS')
    expect(document.documentElement.lang).toBe('de')

    const task = await screen.findByRole('listitem', { name: 'Prüfung: Dokument (überfällig)' })
    expect(within(task).getByText('Überfällig')).toBeInTheDocument()
    expect(within(task).getByText(/Überfällig seit 02\.03\.2026/)).toBeInTheDocument()
    expect(within(task).getByRole('button', { name: 'Abstimmen …' })).toBeInTheDocument()
    expect(await within(task).findByText('Rita Reviewer')).toHaveAttribute('title', 'reviewer-1')
    expect(within(task).getByText(/Legal review \(Prüfer\/in\)/)).toBeInTheDocument()
  })

  it('switches the language from the user menu', async () => {
    const user = renderApp('/tasks')
    await screen.findByRole('heading', { name: 'Aufgaben', level: 1 })

    await user.click(screen.getByRole('button', { name: 'Benutzermenü' }))
    await user.click(screen.getByRole('menuitemradio', { name: 'English' }))

    expect(await screen.findByRole('heading', { name: 'Tasks', level: 1 })).toBeInTheDocument()
    expect(document.documentElement.lang).toBe('en')
    expect(localStorage.getItem('dms-locale')).toBe('en')
    expect(localStorage.getItem('dms-locale:author-1')).toBe('en')
  })
})
