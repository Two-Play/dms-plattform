import { createFileRoute, redirect } from '@tanstack/react-router'
import { useAuth } from 'react-oidc-context'
import { safeReturnTo } from '@/auth/return-to'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { t } from '@/i18n'
import { useDocumentTitle } from '@/lib/document-title'

interface SignInSearch {
  redirect?: string
}

export const Route = createFileRoute('/sign-in')({
  validateSearch: (search: Record<string, unknown>): SignInSearch =>
    typeof search.redirect === 'string' ? { redirect: search.redirect } : {},
  beforeLoad: ({ context, search }) => {
    if (context.auth.isAuthenticated) {
      throw redirect({ href: safeReturnTo(search.redirect) })
    }
  },
  component: SignInPage,
})

function SignInPage() {
  const auth = useAuth()
  const { redirect: returnTo } = Route.useSearch()
  useDocumentTitle(t('auth.signIn'))

  return (
    <main className="flex min-h-svh items-center justify-center bg-muted/40 p-6">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle className="text-xl">{t('common.product')}</CardTitle>
          <CardDescription>{t('auth.intro')}</CardDescription>
        </CardHeader>
        <CardContent>
          {auth.error && (
            <p role="alert" className="text-sm text-destructive">
              {t('auth.signInFailedWith', { message: auth.error.message })}
            </p>
          )}
        </CardContent>
        <CardFooter>
          <Button
            className="w-full"
            disabled={auth.activeNavigator === 'signinRedirect'}
            onClick={() =>
              void auth.signinRedirect({ state: { returnTo: safeReturnTo(returnTo) } })
            }
          >
            {t('auth.signIn')}
          </Button>
        </CardFooter>
      </Card>
    </main>
  )
}
