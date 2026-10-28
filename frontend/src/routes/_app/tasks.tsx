import { createFileRoute } from '@tanstack/react-router'
import { PlaceholderPage } from '@/components/placeholder-page'

export const Route = createFileRoute('/_app/tasks')({
  component: () => (
    <PlaceholderPage title="Tasks" description="Review and approval tasks assigned to you." />
  ),
})
