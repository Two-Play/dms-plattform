import { createFileRoute } from '@tanstack/react-router'
import { PlaceholderPage } from '@/components/placeholder-page'

export const Route = createFileRoute('/_app/documents')({
  component: () => (
    <PlaceholderPage title="Documents" description="Search, view and manage documents." />
  ),
})
