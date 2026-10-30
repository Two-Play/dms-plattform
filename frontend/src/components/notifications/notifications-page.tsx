import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { BellOff, CheckCheck, Settings } from 'lucide-react'
import { useState } from 'react'
import { useApiClient } from '@/api/context'
import { notificationsQuery, unreadCountQuery } from '@/api/notifications'
import { ErrorState } from '@/components/shared/error-state'
import { PageHeader } from '@/components/shared/page-header'
import { EmptyState, LoadingState } from '@/components/shared/states'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n'
import { formatDateTime } from '@/i18n/format'
import { useMarkAllRead, useOpenNotification } from './use-notifications'

const PAGE = 20

/** All notifications of the user, newest first, with a filter for unread ones and paging (DMS-130). */
export function NotificationsPage() {
  const client = useApiClient()
  const [unreadOnly, setUnreadOnly] = useState(false)
  const [offset, setOffset] = useState(0)
  const list = useQuery(notificationsQuery(client, { unreadOnly, offset, limit: PAGE }))
  const unread = useQuery(unreadCountQuery(client))
  const openNotification = useOpenNotification()
  const markAll = useMarkAllRead()
  const items = list.data?.items ?? []
  return (
    <div className="space-y-6">
      <PageHeader
        title={t('notifications.page.title')}
        documentTitle={t('notifications.page.documentTitle')}
        description={t('notifications.page.description')}
        actions={
          <>
            <Button
              variant="outline"
              disabled={!unread.data || markAll.isPending}
              onClick={() => {
                markAll.mutate()
              }}
            >
              <CheckCheck />
              {t('notifications.markAllRead')}
            </Button>
            <Button asChild variant="ghost">
              <Link to="/settings/notifications">
                <Settings />
                {t('notifications.settingsLink')}
              </Link>
            </Button>
          </>
        }
      />
      <div role="group" aria-label={t('notifications.page.filter')} className="flex gap-2">
        {[false, true].map((only) => (
          <Button
            key={String(only)}
            size="sm"
            variant={unreadOnly === only ? 'default' : 'outline'}
            aria-pressed={unreadOnly === only}
            onClick={() => {
              setUnreadOnly(only)
              setOffset(0)
            }}
          >
            {only ? t('notifications.page.filterUnread') : t('notifications.page.filterAll')}
          </Button>
        ))}
      </div>
      {list.isPending ? (
        <LoadingState label={t('notifications.loading')} rows={5} />
      ) : list.error ? (
        <ErrorState error={list.error} onRetry={() => void list.refetch()} />
      ) : items.length === 0 ? (
        <EmptyState
          icon={BellOff}
          title={
            unreadOnly
              ? t('notifications.page.emptyUnreadTitle')
              : t('notifications.page.emptyTitle')
          }
          description={
            unreadOnly ? t('notifications.page.emptyUnreadText') : t('notifications.page.emptyText')
          }
        />
      ) : (
        <>
          <p className="text-sm text-muted-foreground" aria-live="polite">
            {t('notifications.page.count', { count: list.data.total })}
          </p>
          <ul aria-label={t('notifications.page.list')} className="divide-y rounded-lg border">
            {items.map((n) => (
              <li key={n.id} className="flex flex-wrap items-start justify-between gap-3 p-4">
                <div className="min-w-0 flex-1 space-y-1">
                  <p className={n.readAt ? 'text-sm' : 'text-sm font-semibold'}>
                    {!n.readAt && (
                      <Badge variant="secondary" className="mr-2">
                        {t('notifications.unread')}
                      </Badge>
                    )}
                    {n.subject}
                  </p>
                  <p className="text-sm text-muted-foreground">{n.body}</p>
                  <p className="text-xs text-muted-foreground">
                    {t(`notifications.categories.${categoryKey(n.category)}`)} ·{' '}
                    {t('notifications.received', { date: formatDateTime(n.createdAt) })}
                  </p>
                </div>
                <div className="flex shrink-0 gap-2">
                  {n.link && (
                    <Button size="sm" onClick={() => void openNotification(n)}>
                      {t('notifications.page.open')}
                    </Button>
                  )}
                  {!n.readAt && !n.link && (
                    <Button size="sm" variant="outline" onClick={() => void openNotification(n)}>
                      {t('notifications.page.markRead')}
                    </Button>
                  )}
                </div>
              </li>
            ))}
          </ul>
          {list.data.total > PAGE && (
            <nav aria-label={t('notifications.page.paging')} className="flex items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={offset === 0}
                onClick={() => {
                  setOffset((o) => Math.max(0, o - PAGE))
                }}
              >
                {t('notifications.page.previous')}
              </Button>
              <span className="text-sm text-muted-foreground">
                {t('notifications.page.range', {
                  from: offset + 1,
                  to: offset + items.length,
                  total: list.data.total,
                })}
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={offset + items.length >= list.data.total}
                onClick={() => {
                  setOffset((o) => o + PAGE)
                }}
              >
                {t('notifications.page.next')}
              </Button>
            </nav>
          )}
        </>
      )}
    </div>
  )
}

type CategoryKey =
  'workflow' | 'deadline' | 'acknowledgement' | 'deputyship' | 'publication' | 'legal-hold'

function categoryKey(category: string): CategoryKey {
  switch (category) {
    case 'workflow':
    case 'deadline':
    case 'acknowledgement':
    case 'deputyship':
    case 'publication':
    case 'legal-hold':
      return category
    default:
      return 'workflow'
  }
}
