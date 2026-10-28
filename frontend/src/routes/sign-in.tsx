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

  return (
    <main className="flex min-h-svh items-center justify-center bg-muted/40 p-6">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle className="text-xl">DMS Platform</CardTitle>
          <CardDescription>
            Document and template management with approval workflows and audit-proof archiving.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {auth.error && (
            <p role="alert" className="text-sm text-destructive">
              Sign-in failed: {auth.error.message}
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
            Sign in
          </Button>
        </CardFooter>
      </Card>
    </main>
  )
}
