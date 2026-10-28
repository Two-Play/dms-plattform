import { createFileRoute, redirect } from '@tanstack/react-router'
import { AppShell } from '@/components/app-shell'

/** Pathless layout route: every child requires a signed-in user. */
export const Route = createFileRoute('/_app')({
  beforeLoad: ({ context, location }) => {
    if (!context.auth.isAuthenticated) {
      throw redirect({ to: '/sign-in', search: { redirect: location.href } })
    }
  },
  component: AppShell,
})
