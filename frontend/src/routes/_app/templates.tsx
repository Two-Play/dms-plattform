import { createFileRoute } from '@tanstack/react-router'
import { PlaceholderPage } from '@/components/placeholder-page'

export const Route = createFileRoute('/_app/templates')({
  component: () => (
    <PlaceholderPage title="Templates" description="Author and version document templates." />
  ),
})
