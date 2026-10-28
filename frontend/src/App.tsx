import { RouterProvider } from '@tanstack/react-router'
import { useEffect } from 'react'
import { useAuth } from 'react-oidc-context'
import { FullPageMessage } from '@/components/full-page-message'
import type { createAppRouter } from '@/router'

export function App({ router }: { router: ReturnType<typeof createAppRouter> }) {
  const auth = useAuth()

  // Re-run route guards when the session starts or ends (sign-in, sign-out, failed renewal).
  useEffect(() => {
    void router.invalidate()
  }, [router, auth.isAuthenticated])

  // Wait until the stored session is loaded or the sign-in callback is processed,
  // otherwise the guards would see a signed-out user for a moment.
  if (auth.isLoading) return <FullPageMessage title="Loading…" />

  return <RouterProvider router={router} context={{ auth }} />
}
