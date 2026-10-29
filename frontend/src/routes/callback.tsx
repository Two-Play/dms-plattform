import { createFileRoute, Link, useRouter } from '@tanstack/react-router'
import { useEffect } from 'react'
import { useAuth } from 'react-oidc-context'
import type { SignInState } from '@/auth/oidc'
import { safeReturnTo } from '@/auth/return-to'
import { FullPageMessage } from '@/components/full-page-message'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n'

/**
 * Redirect target of the authorization code flow. AuthProvider exchanges the code for tokens
 * before the router renders; this page only forwards to where the user wanted to go.
 */
export const Route = createFileRoute('/callback')({
  component: CallbackPage,
})

function CallbackPage() {
  const auth = useAuth()
  const router = useRouter()

  useEffect(() => {
    if (auth.isAuthenticated) {
      const state = auth.user?.state as SignInState | undefined
      router.history.replace(safeReturnTo(state?.returnTo))
    }
  }, [auth.isAuthenticated, auth.user, router])

  if (auth.error) {
    return (
      <FullPageMessage title={t('auth.signInFailed')}>
        <p className="text-muted-foreground">{auth.error.message}</p>
        <Button asChild>
          <Link to="/sign-in">{t('common.retry')}</Link>
        </Button>
      </FullPageMessage>
    )
  }

  if (!auth.isAuthenticated) {
    return (
      <FullPageMessage title={t('auth.noSignIn')}>
        <Button asChild>
          <Link to="/sign-in">{t('auth.signIn')}</Link>
        </Button>
      </FullPageMessage>
    )
  }

  return <FullPageMessage title={t('auth.signingIn')} />
}
