import { createFileRoute } from '@tanstack/react-router'
import { PlaceholderPage } from '@/components/placeholder-page'

export const Route = createFileRoute('/_app/admin')({
  component: () => (
    <PlaceholderPage title="Admin" description="Tenants, users, roles and system settings." />
  ),
})
