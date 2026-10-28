import { createFileRoute } from '@tanstack/react-router'
import { TaskInboxPage } from '@/components/tasks/task-inbox-page'

export const Route = createFileRoute('/_app/tasks')({
  component: TaskInboxPage,
})
