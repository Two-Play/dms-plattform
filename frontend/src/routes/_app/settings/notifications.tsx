import { createFileRoute } from '@tanstack/react-router'
import { NotificationSettingsPage } from '@/components/notifications/notification-settings-page'

export const Route = createFileRoute('/_app/settings/notifications')({
  component: NotificationSettingsPage,
})
