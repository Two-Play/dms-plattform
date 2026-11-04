import { createFileRoute } from '@tanstack/react-router'
import { AdminPage } from '@/components/admin/admin-page'

export const Route = createFileRoute('/_app/admin')({
  validateSearch: (raw: Record<string, unknown>): { tab?: string } =>
    typeof raw.tab === 'string' ? { tab: raw.tab } : {},
  component: AdminRoute,
})

function AdminRoute() {
  const { tab } = Route.useSearch()
  return <AdminPage tab={tab} />
}
