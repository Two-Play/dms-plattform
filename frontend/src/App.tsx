import { RouterProvider } from '@tanstack/react-router'
import { useEffect } from 'react'
import { useAuth } from 'react-oidc-context'
import { FullPageMessage } from '@/components/full-page-message'
import { applyUserLocale, t, useLocale } from '@/i18n'
import type { createAppRouter } from '@/router'

export function App({ router }: { router: ReturnType<typeof createAppRouter> }) {
  const auth = useAuth()
  const locale = useLocale()
  const userId = auth.user?.profile.sub

  // The language this user chose before in this browser, if any (DMS-110).
  useEffect(() => {
    applyUserLocale(userId)
  }, [userId])

  // Re-run route guards when the session starts or ends (sign-in, sign-out, failed renewal).
  // Not while the session is loading: the router has no `auth` in its context yet (DMS-78).
  useEffect(() => {
    if (!auth.isLoading) void router.invalidate()
  }, [router, auth.isLoading, auth.isAuthenticated])

  // Wait until the stored session is loaded or the sign-in callback is processed,
  // otherwise the guards would see a signed-out user for a moment.
  if (auth.isLoading) return <FullPageMessage title={t('common.loading')} />

  // Keyed by the language: switching it renders every page again with the new texts and formats.
  // Server data stays in the query cache and the location in the URL; unsaved input is lost.
  return <RouterProvider key={locale} router={router} context={{ auth }} />
}
