import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { Bell, CheckCheck, Settings } from 'lucide-react'
import { useState } from 'react'
import { useApiClient } from '@/api/context'
import { notificationsQuery, unreadCountQuery } from '@/api/notifications'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { t } from '@/i18n'
import { formatDateTime } from '@/i18n/format'
import { useMarkAllRead, useOpenNotification } from './use-notifications'

/** How many notifications the dropdown lists. */
const NEWEST = 6

/**
 * The bell in the header (DMS-130): a badge with the unread count (polled every minute), a menu with
 * the newest notifications, "mark all as read", the notifications page and the settings. Screen
 * readers hear the count in the button's name.
 */
export function NotificationBell() {
  const client = useApiClient()
  const [open, setOpen] = useState(false)
  const unread = useQuery(unreadCountQuery(client))
  const newest = useQuery({ ...notificationsQuery(client, { limit: NEWEST }), enabled: open })
  const openNotification = useOpenNotification()
  const markAll = useMarkAllRead()
  const count = unread.data ?? 0
  const label = count > 0 ? t('notifications.bellUnread', { count }) : t('notifications.bell')
  return (
    <DropdownMenu open={open} onOpenChange={setOpen}>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" className="relative" aria-label={label}>
          <Bell />
          {count > 0 && (
            <span
              aria-hidden="true"
              className="absolute -top-0.5 -right-0.5 min-w-4 rounded-full bg-destructive px-1 text-center text-[10px] leading-4 font-semibold text-white tabular-nums"
            >
              {count > 99 ? '99+' : count}
            </span>
          )}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-80 max-w-[calc(100vw-2rem)]">
        <DropdownMenuLabel className="flex items-center justify-between gap-2">
          <span className="font-medium text-foreground">{t('notifications.menuTitle')}</span>
          {count > 0 && (
            <span className="text-xs font-normal text-muted-foreground">
              {t('notifications.bellUnread', { count })}
            </span>
          )}
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        {newest.isPending ? (
          <p role="status" className="px-2 py-3 text-sm text-muted-foreground">
            {t('notifications.loading')}
          </p>
        ) : newest.error ? (
          <p role="alert" className="px-2 py-3 text-sm text-destructive">
            {t('notifications.failed')}
          </p>
        ) : (newest.data.items ?? []).length === 0 ? (
          <p className="px-2 py-3 text-sm text-muted-foreground">{t('notifications.empty')}</p>
        ) : (
          (newest.data.items ?? []).map((n) => (
            <DropdownMenuItem
              key={n.id}
              className="flex flex-col items-start gap-0.5 py-2"
              onSelect={() => void openNotification(n)}
            >
              <span className={n.readAt ? 'text-sm' : 'text-sm font-semibold'}>
                {!n.readAt && <span className="sr-only">{t('notifications.unread')}: </span>}
                {n.subject}
              </span>
              <span className="line-clamp-2 text-xs text-muted-foreground">{n.body}</span>
              <span className="text-xs text-muted-foreground">{formatDateTime(n.createdAt)}</span>
            </DropdownMenuItem>
          ))
        )}
        <DropdownMenuSeparator />
        <DropdownMenuItem
          disabled={count === 0 || markAll.isPending}
          onSelect={() => {
            markAll.mutate()
          }}
        >
          <CheckCheck />
          {t('notifications.markAllRead')}
        </DropdownMenuItem>
        <DropdownMenuItem asChild>
          <Link to="/notifications">
            <Bell />
            {t('notifications.showAll')}
          </Link>
        </DropdownMenuItem>
        <DropdownMenuItem asChild>
          <Link to="/settings/notifications">
            <Settings />
            {t('notifications.settingsLink')}
          </Link>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
