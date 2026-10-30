import { createFileRoute } from '@tanstack/react-router'
import { NotificationsPage } from '@/components/notifications/notifications-page'

export const Route = createFileRoute('/_app/notifications')({
  component: NotificationsPage,
})
