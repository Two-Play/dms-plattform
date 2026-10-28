import { createFileRoute } from '@tanstack/react-router'
import { useAuth } from 'react-oidc-context'
import { SystemStatusCard } from '@/components/system-status-card'

export const Route = createFileRoute('/_app/')({
  component: HomePage,
})

function HomePage() {
  const auth = useAuth()
  const name = auth.user?.profile.given_name ?? auth.user?.profile.name

  return (
    <div className="space-y-6">
      <h1 className="font-heading text-2xl font-semibold">
        {name ? `Welcome, ${name}` : 'Welcome'}
      </h1>
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        <SystemStatusCard />
      </div>
    </div>
  )
}
