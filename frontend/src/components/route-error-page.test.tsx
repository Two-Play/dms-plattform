import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from '@tanstack/react-router'
import { afterEach, describe, expect, it } from 'vitest'
import { setLocale } from '@/i18n'
import { InAppShell } from './in-app-shell'
import { RouteErrorPage } from './route-error-page'

/** A router with one page that fails to render as long as `failing.error` is set. */
function renderFailingPage(
  failing: { error?: Error },
  wrap: (page: React.ReactNode) => React.ReactNode,
) {
  const rootRoute = createRootRoute({ component: () => wrap(<Outlet />) })
  const page = createRoute({
    getParentRoute: () => rootRoute,
    path: '/',
    component: function Page() {
      if (failing.error) throw failing.error
      return <p>The page</p>
    },
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([page]),
    history: createMemoryHistory({ initialEntries: ['/'] }),
    defaultErrorComponent: RouteErrorPage,
  })
  render(<RouterProvider router={router} />)
}

const inShell = (page: React.ReactNode) => (
  <InAppShell value={true}>
    <nav aria-label="Main">navigation</nav>
    {page}
  </InAppShell>
)

afterEach(() => {
  sessionStorage.clear()
})

describe('RouteErrorPage', () => {
  it('stays inside the shell, in the language of the user, and offers reload and retry', async () => {
    setLocale('de')
    renderFailingPage({ error: new Error('boom') }, inShell)

    expect(
      await screen.findByRole('heading', { name: 'Diese Seite konnte nicht angezeigt werden' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Main' })).toBeInTheDocument()
    expect(screen.queryByRole('main')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Neu laden' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Erneut versuchen' })).toBeInTheDocument()
    expect(screen.getByText('boom')).not.toBeVisible()
  })

  it('is a page of its own outside the shell', async () => {
    renderFailingPage({ error: new Error('boom') }, (page) => page)
    await screen.findByRole('heading', { name: 'This page could not be shown' })
    expect(screen.getByRole('main')).toBeInTheDocument()
  })

  it('shows the page again when the retry works', async () => {
    const failing: { error?: Error } = { error: new Error('boom') }
    renderFailingPage(failing, inShell)
    const retry = await screen.findByRole('button', { name: 'Try again' })

    failing.error = undefined
    await userEvent.click(retry)

    await waitFor(() => {
      expect(screen.getByText('The page')).toBeInTheDocument()
    })
  })

  it('explains a chunk that stays missing after the reload', async () => {
    const message = 'Failed to fetch dynamically imported module: /assets/page-abc.js'
    sessionStorage.setItem(`tanstack_router_reload:${message}`, '1')
    renderFailingPage({ error: new TypeError(message) }, inShell)

    expect(
      await screen.findByRole('heading', { name: 'A new version is available' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument()
  })
})
