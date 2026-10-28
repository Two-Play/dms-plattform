import { QueryClient } from '@tanstack/react-query'
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import type { AuthContextProps } from 'react-oidc-context'
import { describe, expect, it, vi } from 'vitest'
import { createAppRouter } from '@/router'

const signinRedirect = vi.fn()
const signedOut = {
  isAuthenticated: false,
  isLoading: false,
  user: null,
  error: undefined,
  activeNavigator: undefined,
  signinRedirect,
} as unknown as AuthContextProps

vi.mock('react-oidc-context', () => ({ useAuth: () => signedOut }))

function renderAt(path: string) {
  const router = createAppRouter(new QueryClient(), createMemoryHistory({ initialEntries: [path] }))
  render(<RouterProvider router={router} context={{ auth: signedOut }} />)
  return router
}

describe('protected routes', () => {
  it('redirects a signed-out user to the sign-in page and remembers the target', async () => {
    const router = renderAt('/documents?view=list')

    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/sign-in')
    expect(router.state.location.search).toEqual({ redirect: '/documents?view=list' })
  })

  it('passes the remembered target to the sign-in redirect', async () => {
    renderAt('/tasks')

    ;(await screen.findByRole('button', { name: 'Sign in' })).click()

    expect(signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/tasks' } })
  })

  it('shows the 404 page for unknown paths', async () => {
    renderAt('/does-not-exist')

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
  })
})
