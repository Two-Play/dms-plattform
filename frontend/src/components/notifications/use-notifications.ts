import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useRouter } from '@tanstack/react-router'
import { useApiClient } from '@/api/context'
import {
  markAllNotificationsRead,
  markNotificationRead,
  notificationKeys,
  type Notification,
} from '@/api/notifications'
import { t } from '@/i18n'
import { toast } from '@/lib/toast'

/** Opens the notification's target inside the app (absolute paths only, never other sites). */
export function useOpenNotification() {
  const client = useApiClient()
  const queryClient = useQueryClient()
  const router = useRouter()
  return async (notification: Notification) => {
    if (!notification.readAt) {
      try {
        await markNotificationRead(client, notification.id)
      } finally {
        void queryClient.invalidateQueries({ queryKey: notificationKeys.all })
      }
    }
    const link = notification.link
    if (link?.startsWith('/') && !link.startsWith('//')) {
      router.history.push(link)
    }
  }
}

export function useMarkAllRead() {
  const client = useApiClient()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => markAllNotificationsRead(client),
    onSuccess: (count) => {
      toast(t('notifications.markedAllRead', { count }))
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: notificationKeys.all }),
  })
}
