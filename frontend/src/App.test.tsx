import { QueryClient } from '@tanstack/react-query'
import { createMemoryHistory } from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import { StrictMode } from 'react'
import type { AuthContextProps } from 'react-oidc-context'
import { describe, expect, it, vi } from 'vitest'
import { App } from '@/App'
import { createAppRouter } from '@/router'

let auth = { isAuthenticated: false, isLoading: true } as unknown as AuthContextProps

vi.mock('react-oidc-context', () => ({ useAuth: () => auth }))

describe('App', () => {
  it('runs the route guards only after the session has loaded (DMS-78)', async () => {
    const router = createAppRouter(
      new QueryClient(),
      createMemoryHistory({ initialEntries: ['/'] }),
    )

    // StrictMode runs effects twice, as in main.tsx.
    const { rerender } = render(
      <StrictMode>
        <App router={router} />
      </StrictMode>,
    )
    expect(screen.getByText('Loading…')).toBeInTheDocument()
    // Loading a stored session takes a moment; the router must not run guards meanwhile.
    await new Promise((resolve) => setTimeout(resolve, 50))

    auth = { ...auth, isLoading: false, signinRedirect: vi.fn() }
    rerender(
      <StrictMode>
        <App router={router} />
      </StrictMode>,
    )

    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.queryByText(/something went wrong/i)).not.toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/sign-in')
  })
})
